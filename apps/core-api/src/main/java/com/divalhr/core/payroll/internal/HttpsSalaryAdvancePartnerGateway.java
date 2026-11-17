package com.divalhr.core.payroll.internal;

import com.divalhr.core.payroll.application.SalaryAdvancePartnerGateway;
import com.divalhr.core.payroll.application.SalaryAdvancePartnerProfile;
import com.divalhr.core.payroll.application.SalaryAdvancePartnerProperties;
import com.divalhr.core.payroll.application.SalaryAdvancePartnerSecrets;
import com.divalhr.core.payroll.domain.PaymentProviderAddressPolicy;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffOutcome;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffSigner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only HTTPS adapter of {@link SalaryAdvancePartnerGateway} (MVP-120B, ADR 0042 section 5). It
 * shares the transport rules of ADR 0037: every attempt resolves the profile's exact host once,
 * refuses the whole attempt when any answer is not inside the configured private networks, connects
 * only to a validated address (pinned, no second lookup) while keeping the host name for SNI and
 * certificate verification, never follows a redirect, enforces explicit deadlines, and reads at
 * most {@link #MAX_BODY_BYTES} of response body. Tenants and employees supply no URL, header or
 * credential.
 *
 * <p>Outcome classification is fail-closed for disclosure: only a failure <em>before</em> any
 * request byte was written (refused address, DNS, connect or TLS failure) is {@code
 * TRANSIENT_NOT_SENT} and retryable. Once the request was written, the partner may have kept the
 * application whatever it then answered: a 429 or 5xx (which no partner signs), a failure after the
 * write, a response that cannot be authenticated with the delivery key, an oversized or malformed
 * response, or any 2xx without a valid acknowledgement is {@code UNKNOWN_*} and is never resent
 * automatically. A resend is only ever permitted after a signed, application-bound {@code
 * NOT_RECEIVED} inquiry answer (the administrator's reconcile). Neither the request body, the
 * signature, a response body nor a reference is logged; the adapter does not log at all.
 */
@Component
public final class HttpsSalaryAdvancePartnerGateway implements SalaryAdvancePartnerGateway {

  /** Largest accepted response body. */
  public static final int MAX_BODY_BYTES = 4096;

  /** Largest accepted status line plus headers. */
  static final int MAX_HEADER_BYTES = 8192;

  private static final String PATH = "/v1/salary-advance-handoffs";
  private static final String INQUIRY_PATH = "/v1/salary-advance-handoff-inquiries";
  private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{7,63}$");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The internal failure classes, mapped to the closed outcomes at the edge. */
  enum Failure {
    TRANSIENT_CONNECT,
    TRANSIENT_TLS,
    UNKNOWN_TIMEOUT,
    UNKNOWN_CONNECTION_LOST,
    UNKNOWN_BAD_RESPONSE,
    ACKNOWLEDGED,
    REJECTED
  }

  /** Resolves a host name. */
  @FunctionalInterface
  public interface Dns {
    /**
     * Resolves a host.
     *
     * @param host the host
     * @return every answer
     * @throws UnknownHostException when it does not resolve
     */
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  /** Opens a TCP connection to an already validated address (never a second lookup). */
  @FunctionalInterface
  public interface Dialer {
    /**
     * Connects.
     *
     * @param address the validated address
     * @param port the port
     * @param timeoutMillis connect deadline
     * @return the connected socket
     * @throws IOException when the connection cannot be made
     */
    Socket connect(InetAddress address, int port, int timeoutMillis) throws IOException;
  }

  /** Writes the request bytes: the header buffer, then the caller's body buffer. */
  @FunctionalInterface
  public interface Wire {
    /**
     * Writes and flushes.
     *
     * @param out the TLS stream
     * @param header header bytes (contain the signature)
     * @param body the body, owned and wiped by the caller
     * @throws IOException on a write failure
     */
    void write(OutputStream out, byte[] header, byte[] body) throws IOException;
  }

  /**
   * The seams of the adapter. Production uses {@link #production()}; tests substitute the resolver
   * and the dialer (to observe resolution and pinning), the writer (to inject a write failure) and
   * a buffer observer that receives every temporary array holding a signature.
   *
   * @param dns resolver
   * @param dialer connection opener
   * @param wire request writer
   * @param buffers observer of temporary signature-bearing arrays (they are zeroed afterwards)
   */
  public record Hooks(Dns dns, Dialer dialer, Wire wire, Consumer<byte[]> buffers) {
    /**
     * @return the production seams
     */
    public static Hooks production() {
      return new Hooks(
          InetAddress::getAllByName,
          (address, port, timeout) -> {
            Socket socket = new Socket();
            try {
              socket.connect(new InetSocketAddress(address, port), timeout);
              return socket;
            } catch (IOException refused) {
              try {
                socket.close();
              } catch (IOException ignored) {
                // not connected
              }
              throw refused;
            }
          },
          (out, header, body) -> {
            out.write(header);
            out.write(body);
            out.flush();
          },
          buffer -> {});
    }
  }

  /** Internal control flow carrying only the closed outcome. */
  private static final class Stop extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient Failure outcome;

    Stop(Failure outcome) {
      super(outcome.name(), null, false, false);
      this.outcome = outcome;
    }
  }

  private final Hooks hooks;
  private final List<String> syntheticNetworks;
  private final SalaryAdvancePartnerSecrets keys;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final SSLSocketFactory sockets;

  /**
   * Creates the gateway.
   *
   * @param properties settings
   * @param keys the signing key holder (authenticates responses)
   */
  @Autowired
  public HttpsSalaryAdvancePartnerGateway(
      SalaryAdvancePartnerProperties properties, SalaryAdvancePartnerSecrets keys) {
    this(
        Hooks.production(),
        keys,
        properties.worker().connectTimeout(),
        properties.worker().requestTimeout(),
        trustFactory(properties.synthetic().caFile()),
        properties.synthetic().networks());
  }

  /**
   * Creates the gateway with explicit collaborators (tests).
   *
   * @param hooks resolver, dialer, writer and buffer observer
   * @param keys the signing key holder
   * @param connectTimeout connect deadline
   * @param requestTimeout overall deadline
   * @param sockets TLS socket factory
   * @param syntheticNetworks the configured internal networks of the synthetic receiver
   */
  public HttpsSalaryAdvancePartnerGateway(
      Hooks hooks,
      SalaryAdvancePartnerSecrets keys,
      Duration connectTimeout,
      Duration requestTimeout,
      SSLSocketFactory sockets,
      List<String> syntheticNetworks) {
    this.hooks = hooks;
    this.syntheticNetworks = List.copyOf(syntheticNetworks);
    this.keys = keys;
    this.connectTimeout = connectTimeout;
    this.requestTimeout = requestTimeout;
    this.sockets = sockets;
  }

  private static SSLSocketFactory trustFactory(String caFile) {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      if (caFile != null && !caFile.isBlank()) {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        TrustManagerFactory defaults =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaults.init((KeyStore) null);
        int i = 0;
        for (var tm : defaults.getTrustManagers()) {
          if (tm instanceof X509TrustManager x509) {
            for (var cert : x509.getAcceptedIssuers()) {
              store.setCertificateEntry("default-" + i++, cert);
            }
          }
        }
        try (InputStream in = Files.newInputStream(Path.of(caFile))) {
          Collection<? extends Certificate> extra =
              CertificateFactory.getInstance("X.509").generateCertificates(in);
          for (Certificate cert : extra) {
            store.setCertificateEntry("provider-" + i++, cert);
          }
        }
        TrustManagerFactory tmf =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        context.init(null, tmf.getTrustManagers(), null);
      } else {
        context.init(null, null, null);
      }
      return context.getSocketFactory();
    } catch (GeneralSecurityException | IOException failure) {
      throw new IllegalStateException("partner TLS trust could not be initialised");
    }
  }

  @Override
  public Result send(
      SalaryAdvancePartnerProfile profile,
      UUID applicationId,
      String keyVersion,
      long timestamp,
      String signature,
      byte[] body) {
    long deadline = System.nanoTime() + requestTimeout.toNanos();
    try {
      List<InetAddress> targets = validated(profile);
      return exchange(
          profile,
          PATH,
          "X-Divalhr-Application-Id: "
              + applicationId
              + "\r\nX-Divalhr-Key-Version: "
              + keyVersion
              + "\r\nX-Divalhr-Timestamp: "
              + timestamp
              + "\r\n",
          signature,
          body,
          targets,
          deadline,
          response -> classify(applicationId, response));
    } catch (Stop stop) {
      return new Result(delivery(stop.outcome), null);
    } catch (RuntimeException unexpected) {
      // Nothing is known about what was written: never resend blindly.
      return new Result(SalaryAdvanceHandoffOutcome.UNKNOWN_TIMEOUT, null);
    }
  }

  /**
   * Asks the receiver for the status of an application (ADR 0042 section 6). Same resolution,
   * pinning, TLS, deadlines and response cap as {@link #send}; the request carries no payload. An
   * inquiry is read-like, so any failure before a usable, authenticated answer is {@code
   * INCONCLUSIVE} and never a delivery decision.
   */
  @Override
  public InquiryResult inquire(
      SalaryAdvancePartnerProfile profile,
      UUID applicationId,
      String keyVersion,
      long timestamp,
      String signature,
      byte[] body) {
    long deadline = System.nanoTime() + requestTimeout.toNanos();
    try {
      List<InetAddress> targets = validated(profile);
      return exchange(
          profile,
          INQUIRY_PATH,
          "X-Divalhr-Application-Id: "
              + applicationId
              + "\r\nX-Divalhr-Key-Version: "
              + keyVersion
              + "\r\nX-Divalhr-Timestamp: "
              + timestamp
              + "\r\n",
          signature,
          body,
          targets,
          deadline,
          response -> classifyInquiry(applicationId, response));
    } catch (Stop stop) {
      return new InquiryResult(InquiryOutcome.INCONCLUSIVE, null);
    } catch (RuntimeException unexpected) {
      return new InquiryResult(InquiryOutcome.INCONCLUSIVE, null);
    }
  }

  /** Maps an internal stop to the closed delivery outcome. */
  private static SalaryAdvanceHandoffOutcome delivery(Failure stop) {
    return switch (stop) {
      case TRANSIENT_CONNECT, TRANSIENT_TLS -> SalaryAdvanceHandoffOutcome.TRANSIENT_NOT_SENT;
      case UNKNOWN_TIMEOUT, UNKNOWN_CONNECTION_LOST -> SalaryAdvanceHandoffOutcome.UNKNOWN_TIMEOUT;
      case UNKNOWN_BAD_RESPONSE, ACKNOWLEDGED, REJECTED ->
          SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE;
    };
  }

  /**
   * One resolution per attempt; every answer must be acceptable for the profile, otherwise the
   * whole attempt is refused before anything is connected or written.
   */
  private List<InetAddress> validated(SalaryAdvancePartnerProfile profile) {
    InetAddress[] answers;
    try {
      answers = hooks.dns().resolve(profile.host());
    } catch (UnknownHostException unknown) {
      throw new Stop(Failure.TRANSIENT_CONNECT);
    }
    if (answers == null || answers.length == 0 || answers.length > 16) {
      throw new Stop(Failure.TRANSIENT_CONNECT);
    }
    List<InetAddress> accepted = new ArrayList<>();
    for (InetAddress address : answers) {
      // The controlled receiver is the only profile: its address must lie in the configured private
      // networks and
      // outside every loopback, link-local, metadata and special-purpose range; one bad answer
      // refuses the attempt.
      boolean acceptable =
          PaymentProviderAddressPolicy.isInternalProvider(address, syntheticNetworks);
      if (!acceptable) {
        throw new Stop(Failure.TRANSIENT_CONNECT);
      }
      accepted.add(address);
    }
    return accepted;
  }

  private <T> T exchange(
      SalaryAdvancePartnerProfile profile,
      String path,
      String identity,
      String signature,
      byte[] body,
      List<InetAddress> targets,
      long deadline,
      Function<Response, T> classifier) {
    Socket plain = null;
    boolean written = false;
    byte[] header = null;
    try {
      plain = connect(profile, targets, deadline);
      SSLSocket tls = (SSLSocket) sockets.createSocket(plain, profile.host(), profile.port(), true);
      SSLParameters parameters = tls.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      parameters.setServerNames(List.of(new SNIHostName(profile.host())));
      parameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
      tls.setSSLParameters(parameters);
      tls.setSoTimeout(remaining(deadline, Failure.TRANSIENT_TLS));
      tls.startHandshake();
      header = header(profile, path, identity, signature, body.length);
      OutputStream out = tls.getOutputStream();
      // From here any byte may have escaped: a failure is an unknown delivery, never a retry.
      written = true;
      hooks.wire().write(out, header, body);
      Response response = read(tls, deadline);
      return classifier.apply(response);
    } catch (Stop stop) {
      throw stop;
    } catch (InterruptedIOException timeout) {
      throw new Stop(written ? Failure.UNKNOWN_TIMEOUT : Failure.TRANSIENT_TLS);
    } catch (SSLException tls) {
      throw new Stop(written ? Failure.UNKNOWN_CONNECTION_LOST : Failure.TRANSIENT_TLS);
    } catch (IOException io) {
      throw new Stop(written ? Failure.UNKNOWN_CONNECTION_LOST : Failure.TRANSIENT_CONNECT);
    } finally {
      if (header != null) {
        Arrays.fill(header, (byte) 0);
      }
      if (plain != null) {
        try {
          plain.close();
        } catch (IOException ignored) {
          // closing a finished attempt
        }
      }
    }
  }

  private Socket connect(
      SalaryAdvancePartnerProfile profile, List<InetAddress> targets, long deadline) {
    long connectDeadline = Math.min(deadline, System.nanoTime() + connectTimeout.toNanos());
    for (InetAddress address : targets) {
      try {
        int wait = remaining(connectDeadline, Failure.TRANSIENT_CONNECT);
        return hooks.dialer().connect(address, profile.port(), wait);
      } catch (IOException refused) {
        // try the next validated address
      }
    }
    throw new Stop(Failure.TRANSIENT_CONNECT);
  }

  private static int remaining(long deadlineNanos, Failure onExpiry) {
    long millis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
    if (millis <= 0) {
      throw new Stop(onExpiry);
    }
    return (int) Math.min(millis, Integer.MAX_VALUE);
  }

  /**
   * The request header. The signature is copied from its string into one array that is zeroed at
   * once; the combined header array is zeroed by the caller. Both are shown to the buffer observer
   * (tests) before they are cleared. The body is never copied.
   */
  private byte[] header(
      SalaryAdvancePartnerProfile profile,
      String path,
      String identity,
      String signature,
      int bodyLength) {
    byte[] before =
        ("POST "
                + path
                + " HTTP/1.1\r\nHost: "
                + profile.host()
                + ':'
                + profile.port()
                + "\r\nContent-Type: application/json\r\nAccept: application/json\r\n"
                + "User-Agent: DivalHR-PartnerHandoff/1\r\n"
                + identity
                + "X-Divalhr-Signature: ")
            .getBytes(StandardCharsets.US_ASCII);
    byte[] after =
        ("\r\nContent-Length: " + bodyLength + "\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
    byte[] sig = signature.getBytes(StandardCharsets.US_ASCII);
    byte[] all = new byte[before.length + sig.length + after.length];
    try {
      hooks.buffers().accept(sig);
      System.arraycopy(before, 0, all, 0, before.length);
      System.arraycopy(sig, 0, all, before.length, sig.length);
      System.arraycopy(after, 0, all, before.length + sig.length, after.length);
      hooks.buffers().accept(all);
      return all;
    } finally {
      Arrays.fill(sig, (byte) 0);
    }
  }

  /** A bounded response. */
  private record Response(int status, String signature, byte[] body) {}

  private Response read(SSLSocket tls, long deadline) throws IOException {
    InputStream in = tls.getInputStream();
    ByteArrayOutputStream all = new ByteArrayOutputStream(1024);
    byte[] buffer = new byte[1024];
    int headerEnd = -1;
    int contentLength = -1;
    while (true) {
      tls.setSoTimeout(remaining(deadline, Failure.UNKNOWN_TIMEOUT));
      int read = in.read(buffer);
      if (read < 0) {
        break;
      }
      all.write(buffer, 0, read);
      if (all.size() > MAX_HEADER_BYTES + MAX_BODY_BYTES + 4) {
        throw new Stop(Failure.UNKNOWN_BAD_RESPONSE);
      }
      if (headerEnd < 0) {
        byte[] bytes = all.toByteArray();
        headerEnd = indexOfBlankLine(bytes);
        if (headerEnd < 0 && bytes.length > MAX_HEADER_BYTES) {
          throw new Stop(Failure.UNKNOWN_BAD_RESPONSE);
        }
        if (headerEnd >= 0) {
          contentLength =
              contentLength(new String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1));
        }
      }
      if (headerEnd >= 0 && contentLength >= 0 && all.size() - (headerEnd + 4) >= contentLength) {
        break;
      }
    }
    byte[] bytes = all.toByteArray();
    if (headerEnd < 0) {
      headerEnd = indexOfBlankLine(bytes);
    }
    if (headerEnd < 0) {
      throw new Stop(Failure.UNKNOWN_CONNECTION_LOST);
    }
    String head = new String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1);
    int status = status(head);
    int bodyStart = headerEnd + 4;
    int bodyLength = bytes.length - bodyStart;
    if (contentLength >= 0) {
      if (contentLength > MAX_BODY_BYTES || bodyLength < contentLength) {
        throw new Stop(
            contentLength > MAX_BODY_BYTES
                ? Failure.UNKNOWN_BAD_RESPONSE
                : Failure.UNKNOWN_CONNECTION_LOST);
      }
      bodyLength = contentLength;
    } else if (bodyLength > MAX_BODY_BYTES) {
      throw new Stop(Failure.UNKNOWN_BAD_RESPONSE);
    }
    byte[] body = new byte[bodyLength];
    System.arraycopy(bytes, bodyStart, body, 0, bodyLength);
    return new Response(status, header(head, "x-divalhr-response-signature"), body);
  }

  private static int indexOfBlankLine(byte[] bytes) {
    for (int i = 0; i + 3 < bytes.length; i++) {
      if (bytes[i] == '\r'
          && bytes[i + 1] == '\n'
          && bytes[i + 2] == '\r'
          && bytes[i + 3] == '\n') {
        return i;
      }
    }
    return -1;
  }

  private static int status(String head) {
    int end = head.indexOf("\r\n");
    String line = end < 0 ? head : head.substring(0, end);
    if (line.length() < 12 || !line.startsWith("HTTP/1.") || line.charAt(8) != ' ') {
      throw new Stop(Failure.UNKNOWN_BAD_RESPONSE);
    }
    try {
      int code = Integer.parseInt(line.substring(9, 12));
      if (code < 100 || code > 599) {
        throw new Stop(Failure.UNKNOWN_BAD_RESPONSE);
      }
      return code;
    } catch (NumberFormatException malformed) {
      throw new Stop(Failure.UNKNOWN_BAD_RESPONSE);
    }
  }

  private static int contentLength(String head) {
    String value = header(head, "content-length");
    if (value == null) {
      return -1;
    }
    try {
      int length = Integer.parseInt(value.trim());
      if (length < 0) {
        throw new Stop(Failure.UNKNOWN_BAD_RESPONSE);
      }
      return length;
    } catch (NumberFormatException malformed) {
      throw new Stop(Failure.UNKNOWN_BAD_RESPONSE);
    }
  }

  private static String header(String head, String name) {
    String prefix = name + ":";
    for (String line : head.split("\r\n")) {
      if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
        return line.substring(prefix.length()).trim();
      }
    }
    return null;
  }

  /** Parses the bounded answer body, or {@code null} when it is not a JSON object. */
  private static JsonNode object(byte[] body) {
    try {
      JsonNode node = JSON.readTree(new String(body, StandardCharsets.UTF_8));
      return node != null && node.isObject() ? node : null;
    } catch (RuntimeException malformed) {
      return null;
    }
  }

  /**
   * The contract: only an authenticated, well-formed answer for exactly this application decides
   * anything.
   */
  private Result classify(UUID applicationId, Response response) {
    int status = response.status();
    // 429 and 5xx carry no authenticated decision, and the request was already written: the
    // partner may have stored the application before it answered. Never TRANSIENT_NOT_SENT, never
    // resent automatically; only a signed NOT_RECEIVED inquiry permits a resend.
    if (status == 429 || status >= 500) {
      return new Result(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE, null);
    }
    boolean accepted = status >= 200 && status <= 299;
    boolean rejected = status == 400 || status == 409 || status == 422;
    SalaryAdvanceHandoffSigner signer = keys.delivery();
    if (!(accepted || rejected)
        || !SalaryAdvanceHandoffSigner.matches(
            signer.signAck(applicationId, response.body()), response.signature())) {
      return new Result(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE, null);
    }
    JsonNode node = object(response.body());
    if (node == null
        || !node.path("applicationId").isString()
        || !applicationId.toString().equals(node.path("applicationId").asString())
        || !node.path("result").isString()) {
      return new Result(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE, null);
    }
    String result = node.path("result").asString();
    if (accepted) {
      JsonNode reference = node.path("reference");
      if (!"RECEIVED".equals(result)
          || !reference.isString()
          || !REFERENCE.matcher(reference.asString()).matches()) {
        return new Result(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE, null);
      }
      return new Result(SalaryAdvanceHandoffOutcome.RECEIVED_ACKNOWLEDGED, reference.asString());
    }
    if (!"REJECTED".equals(result) || !node.path("code").isString()) {
      return new Result(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE, null);
    }
    // An authenticated rejection proves nothing was accepted; the code itself is never kept.
    return new Result(SalaryAdvanceHandoffOutcome.REJECTED_BY_PARTNER, null);
  }

  /**
   * The inquiry contract: only an authenticated answer for exactly this application proves
   * anything, and only a signed {@code NOT_RECEIVED} permits a resend.
   */
  private InquiryResult classifyInquiry(UUID applicationId, Response response) {
    if (response.status() != 200
        || !SalaryAdvanceHandoffSigner.matches(
            keys.delivery().signInquiryAnswer(applicationId, response.body()),
            response.signature())) {
      return new InquiryResult(InquiryOutcome.INCONCLUSIVE, null);
    }
    JsonNode node = object(response.body());
    if (node == null
        || !node.path("applicationId").isString()
        || !applicationId.toString().equals(node.path("applicationId").asString())
        || !node.path("result").isString()) {
      return new InquiryResult(InquiryOutcome.INCONCLUSIVE, null);
    }
    return switch (node.path("result").asString()) {
      case "RECEIVED" ->
          node.path("reference").isString()
                  && REFERENCE.matcher(node.path("reference").asString()).matches()
              ? new InquiryResult(InquiryOutcome.RECEIVED, node.path("reference").asString())
              : new InquiryResult(InquiryOutcome.INCONCLUSIVE, null);
      case "NOT_RECEIVED" -> new InquiryResult(InquiryOutcome.NOT_RECEIVED, null);
      default -> new InquiryResult(InquiryOutcome.INCONCLUSIVE, null);
    };
  }
}
