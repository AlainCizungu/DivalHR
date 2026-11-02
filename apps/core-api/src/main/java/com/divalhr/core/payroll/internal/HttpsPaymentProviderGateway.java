package com.divalhr.core.payroll.internal;

import com.divalhr.core.payroll.application.PaymentProviderGateway;
import com.divalhr.core.payroll.application.PaymentSubmissionProperties;
import com.divalhr.core.payroll.application.PaymentSubmissionSecrets;
import com.divalhr.core.payroll.domain.PaymentProviderAddressPolicy;
import com.divalhr.core.payroll.domain.PaymentSubmissionOutcome;
import com.divalhr.core.payroll.domain.PaymentSubmissionProfile;
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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
 * The only HTTPS adapter of {@link PaymentProviderGateway} (MVP-095B, ADR 0037 section 3). Every
 * attempt resolves the profile's host again, refuses the whole attempt when any answer is not a
 * public address (the single exception is the exact synthetic receiver host and port of the
 * profile), connects only to a validated address while keeping the host name for SNI and
 * certificate verification, never follows a redirect, enforces explicit deadlines, and reads at
 * most {@link #MAX_BODY_BYTES} of response body.
 *
 * <p>Outcome classification is fail-closed for money: a failure <em>before</em> any request byte
 * was written is retryable; a failure after the request was written, a response that cannot be
 * authenticated with the signing key, an oversized or malformed response, or any 2xx without a
 * valid acceptance is {@code UNKNOWN_*} and is never resent. Neither the request body, the
 * signature, a response body nor a reference is logged; the adapter does not log at all.
 */
@Component
public final class HttpsPaymentProviderGateway implements PaymentProviderGateway {

  /** Largest accepted response body. */
  public static final int MAX_BODY_BYTES = 4096;

  /** Largest accepted status line plus headers. */
  static final int MAX_HEADER_BYTES = 8192;

  private static final String PATH = "/v1/payment-requests";
  private static final Pattern REFERENCE = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{5,63}$");
  private static final JsonMapper JSON = JsonMapper.builder().build();

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

  /** Internal control flow carrying only the closed outcome. */
  private static final class Stop extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient PaymentSubmissionOutcome outcome;

    Stop(PaymentSubmissionOutcome outcome) {
      super(outcome.name(), null, false, false);
      this.outcome = outcome;
    }
  }

  private final Dns dns;
  private final PaymentSubmissionSecrets keys;
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
  public HttpsPaymentProviderGateway(
      PaymentSubmissionProperties properties, PaymentSubmissionSecrets keys) {
    this(
        InetAddress::getAllByName,
        keys,
        properties.worker().connectTimeout(),
        properties.worker().requestTimeout(),
        trustFactory(properties.synthetic().caFile()));
  }

  /**
   * Creates the gateway with explicit collaborators (tests).
   *
   * @param dns resolver
   * @param keys the signing key holder
   * @param connectTimeout connect deadline
   * @param requestTimeout overall deadline
   * @param sockets TLS socket factory
   */
  public HttpsPaymentProviderGateway(
      Dns dns,
      PaymentSubmissionSecrets keys,
      Duration connectTimeout,
      Duration requestTimeout,
      SSLSocketFactory sockets) {
    this.dns = dns;
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
      throw new IllegalStateException("payment provider TLS trust could not be initialised");
    }
  }

  @Override
  public Result send(
      PaymentSubmissionProfile profile,
      UUID requestId,
      int keyVersion,
      String signature,
      byte[] body) {
    long deadline = System.nanoTime() + requestTimeout.toNanos();
    try {
      List<InetAddress> targets = validated(profile);
      return exchange(profile, requestId, keyVersion, signature, body, targets, deadline);
    } catch (Stop stop) {
      return new Result(stop.outcome, null);
    } catch (RuntimeException unexpected) {
      // Nothing is known about what was written: never resend blindly.
      return new Result(PaymentSubmissionOutcome.UNKNOWN_CONNECTION_LOST, null);
    }
  }

  /** Every answer must be public, except the exact synthetic receiver of the profile. */
  private List<InetAddress> validated(PaymentSubmissionProfile profile) {
    InetAddress[] answers;
    try {
      answers = dns.resolve(profile.host());
    } catch (UnknownHostException unknown) {
      throw new Stop(PaymentSubmissionOutcome.TRANSIENT_CONNECT);
    }
    if (answers == null || answers.length == 0 || answers.length > 16) {
      throw new Stop(PaymentSubmissionOutcome.TRANSIENT_CONNECT);
    }
    List<InetAddress> accepted = new ArrayList<>();
    for (InetAddress address : answers) {
      if (!profile.synthetic() && !PaymentProviderAddressPolicy.isPublic(address)) {
        throw new Stop(PaymentSubmissionOutcome.TRANSIENT_CONNECT);
      }
      accepted.add(address);
    }
    return accepted;
  }

  private Result exchange(
      PaymentSubmissionProfile profile,
      UUID requestId,
      int keyVersion,
      String signature,
      byte[] body,
      List<InetAddress> targets,
      long deadline) {
    Socket plain = null;
    boolean written = false;
    try {
      plain = connect(profile, targets, deadline);
      SSLSocket tls = (SSLSocket) sockets.createSocket(plain, profile.host(), profile.port(), true);
      SSLParameters parameters = tls.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      parameters.setServerNames(List.of(new SNIHostName(profile.host())));
      parameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
      tls.setSSLParameters(parameters);
      tls.setSoTimeout(remaining(deadline, PaymentSubmissionOutcome.TRANSIENT_TLS));
      tls.startHandshake();
      byte[] request = request(profile, requestId, keyVersion, signature, body);
      OutputStream out = tls.getOutputStream();
      written = true;
      out.write(request);
      out.flush();
      Response response = read(tls, deadline);
      return classify(requestId, response);
    } catch (Stop stop) {
      throw stop;
    } catch (InterruptedIOException timeout) {
      throw new Stop(
          written
              ? PaymentSubmissionOutcome.UNKNOWN_TIMEOUT
              : PaymentSubmissionOutcome.TRANSIENT_TLS);
    } catch (SSLException tls) {
      throw new Stop(
          written
              ? PaymentSubmissionOutcome.UNKNOWN_CONNECTION_LOST
              : PaymentSubmissionOutcome.TRANSIENT_TLS);
    } catch (IOException io) {
      throw new Stop(
          written
              ? PaymentSubmissionOutcome.UNKNOWN_CONNECTION_LOST
              : PaymentSubmissionOutcome.TRANSIENT_CONNECT);
    } finally {
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
      PaymentSubmissionProfile profile, List<InetAddress> targets, long deadline) {
    long connectDeadline = Math.min(deadline, System.nanoTime() + connectTimeout.toNanos());
    for (InetAddress address : targets) {
      Socket socket = new Socket();
      try {
        int wait = remaining(connectDeadline, PaymentSubmissionOutcome.TRANSIENT_CONNECT);
        socket.connect(new InetSocketAddress(address, profile.port()), wait);
        return socket;
      } catch (IOException refused) {
        try {
          socket.close();
        } catch (IOException ignored) {
          // not connected
        }
      }
    }
    throw new Stop(PaymentSubmissionOutcome.TRANSIENT_CONNECT);
  }

  private static int remaining(long deadlineNanos, PaymentSubmissionOutcome onExpiry) {
    long millis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
    if (millis <= 0) {
      throw new Stop(onExpiry);
    }
    return (int) Math.min(millis, Integer.MAX_VALUE);
  }

  private static byte[] request(
      PaymentSubmissionProfile profile,
      UUID requestId,
      int keyVersion,
      String signature,
      byte[] body) {
    StringBuilder head = new StringBuilder(384);
    head.append("POST ").append(PATH).append(" HTTP/1.1\r\n");
    head.append("Host: ").append(profile.host()).append(':').append(profile.port()).append("\r\n");
    head.append("Content-Type: application/json\r\n");
    head.append("Accept: application/json\r\n");
    head.append("User-Agent: DivalHR-PaymentSubmission/1\r\n");
    head.append("X-Divalhr-Request-Id: ").append(requestId).append("\r\n");
    head.append("X-Divalhr-Key-Version: ").append(keyVersion).append("\r\n");
    head.append("X-Divalhr-Signature: ").append(signature).append("\r\n");
    head.append("Content-Length: ").append(body.length).append("\r\n");
    head.append("Connection: close\r\n\r\n");
    byte[] header = head.toString().getBytes(StandardCharsets.US_ASCII);
    byte[] all = new byte[header.length + body.length];
    System.arraycopy(header, 0, all, 0, header.length);
    System.arraycopy(body, 0, all, header.length, body.length);
    return all;
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
      tls.setSoTimeout(remaining(deadline, PaymentSubmissionOutcome.UNKNOWN_TIMEOUT));
      int read = in.read(buffer);
      if (read < 0) {
        break;
      }
      all.write(buffer, 0, read);
      if (all.size() > MAX_HEADER_BYTES + MAX_BODY_BYTES + 4) {
        throw new Stop(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
      }
      if (headerEnd < 0) {
        byte[] bytes = all.toByteArray();
        headerEnd = indexOfBlankLine(bytes);
        if (headerEnd < 0 && bytes.length > MAX_HEADER_BYTES) {
          throw new Stop(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
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
      throw new Stop(PaymentSubmissionOutcome.UNKNOWN_CONNECTION_LOST);
    }
    String head = new String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1);
    int status = status(head);
    int bodyStart = headerEnd + 4;
    int bodyLength = bytes.length - bodyStart;
    if (contentLength >= 0) {
      if (contentLength > MAX_BODY_BYTES || bodyLength < contentLength) {
        throw new Stop(
            contentLength > MAX_BODY_BYTES
                ? PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE
                : PaymentSubmissionOutcome.UNKNOWN_CONNECTION_LOST);
      }
      bodyLength = contentLength;
    } else if (bodyLength > MAX_BODY_BYTES) {
      throw new Stop(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
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
      throw new Stop(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
    }
    try {
      int code = Integer.parseInt(line.substring(9, 12));
      if (code < 100 || code > 599) {
        throw new Stop(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
      }
      return code;
    } catch (NumberFormatException malformed) {
      throw new Stop(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
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
        throw new Stop(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
      }
      return length;
    } catch (NumberFormatException malformed) {
      throw new Stop(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
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

  /** The contract: only an authenticated, well-formed answer decides anything. */
  private Result classify(UUID requestId, Response response) {
    int status = response.status();
    if (status == 429) {
      return new Result(PaymentSubmissionOutcome.TRANSIENT_RATE_LIMITED, null);
    }
    if (status >= 500) {
      return new Result(PaymentSubmissionOutcome.TRANSIENT_SERVER, null);
    }
    boolean accepted = status >= 200 && status <= 299;
    boolean rejected = status == 400 || status == 409 || status == 422;
    if (!accepted && !rejected) {
      return new Result(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE, null);
    }
    if (!keys.signer().verify(requestId, response.body(), response.signature())) {
      return new Result(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE, null);
    }
    JsonNode node;
    try {
      node = JSON.readTree(new String(response.body(), StandardCharsets.UTF_8));
    } catch (RuntimeException malformed) {
      return new Result(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE, null);
    }
    if (node == null
        || !node.isObject()
        || !node.path("requestId").isString()
        || !requestId.toString().equals(node.path("requestId").asString())) {
      return new Result(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE, null);
    }
    if (accepted) {
      JsonNode result = node.path("result");
      JsonNode reference = node.path("reference");
      if (!result.isString()
          || !"ACCEPTED".equals(result.asString())
          || !reference.isString()
          || !REFERENCE.matcher(reference.asString()).matches()) {
        return new Result(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE, null);
      }
      return new Result(PaymentSubmissionOutcome.ACCEPTED, reference.asString());
    }
    JsonNode code = node.path("code");
    if (!node.path("result").isString()
        || !"REJECTED".equals(node.path("result").asString())
        || !code.isString()) {
      return new Result(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE, null);
    }
    return new Result(rejection(code.asString()), null);
  }

  private static PaymentSubmissionOutcome rejection(String code) {
    return switch (code) {
      case "INVALID_REQUEST" -> PaymentSubmissionOutcome.REJECTED_INVALID_REQUEST;
      case "SIGNATURE" -> PaymentSubmissionOutcome.REJECTED_SIGNATURE;
      case "UNSUPPORTED" -> PaymentSubmissionOutcome.REJECTED_UNSUPPORTED;
      case "DUPLICATE_MISMATCH" -> PaymentSubmissionOutcome.REJECTED_DUPLICATE_MISMATCH;
      case "LIMIT" -> PaymentSubmissionOutcome.REJECTED_LIMIT;
      default -> PaymentSubmissionOutcome.REJECTED_OTHER;
    };
  }
}
