package com.divalhr.core.integrations.internal;

import com.divalhr.core.integrations.application.DeliveryTransport;
import com.divalhr.core.integrations.application.DnsResolver;
import com.divalhr.core.integrations.application.IntegrationProperties;
import com.divalhr.core.integrations.domain.AddressPolicy;
import com.divalhr.core.integrations.domain.FailureClass;
import com.divalhr.core.integrations.domain.WebhookUrl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers one attempt over HTTPS (D110-8). Every attempt resolves the name again, refuses the
 * whole attempt when any answer is not a public address, then connects only to a validated address
 * while keeping the original host name for SNI and certificate verification (anti DNS rebinding).
 * No redirect is followed, response headers are bounded, and the response body is never read or
 * stored: the connection is closed after the status line and headers.
 *
 * <p>The only exception to the address policy is the configured synthetic receiver (exact host and
 * port, development and test only).
 */
@Component
public final class HttpsDeliveryTransport implements DeliveryTransport {

  /** Largest accepted status line plus headers. */
  static final int MAX_HEADER_BYTES = 16 * 1024;

  private final DnsResolver dns;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final IntegrationProperties.TestReceiver receiver;
  private final SSLSocketFactory sockets;

  /**
   * Creates the transport.
   *
   * @param dns resolver
   * @param properties settings
   */
  public HttpsDeliveryTransport(DnsResolver dns, IntegrationProperties properties) {
    this(dns, properties, trustFactory(properties.testReceiver()));
  }

  /**
   * Creates the transport with an explicit TLS factory (tests).
   *
   * @param dns resolver
   * @param properties settings
   * @param sockets TLS socket factory
   */
  public HttpsDeliveryTransport(
      DnsResolver dns, IntegrationProperties properties, SSLSocketFactory sockets) {
    this.dns = dns;
    this.connectTimeout = properties.connectTimeout();
    this.requestTimeout = properties.requestTimeout();
    this.receiver = properties.testReceiver();
    this.sockets = sockets;
  }

  private static SSLSocketFactory trustFactory(IntegrationProperties.TestReceiver receiver) {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      if (receiver.configured() && receiver.caFile() != null && !receiver.caFile().isBlank()) {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        TrustManagerFactory defaults =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaults.init((KeyStore) null);
        int i = 0;
        for (var tm : defaults.getTrustManagers()) {
          if (tm instanceof javax.net.ssl.X509TrustManager x509) {
            for (var cert : x509.getAcceptedIssuers()) {
              store.setCertificateEntry("default-" + i++, cert);
            }
          }
        }
        try (InputStream in = Files.newInputStream(Path.of(receiver.caFile()))) {
          Collection<? extends Certificate> extra =
              CertificateFactory.getInstance("X.509").generateCertificates(in);
          for (Certificate cert : extra) {
            store.setCertificateEntry("receiver-" + i++, cert);
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
      throw new IllegalStateException("webhook TLS trust could not be initialised");
    }
  }

  @Override
  public Outcome post(WebhookUrl url, Map<String, String> headers, byte[] body) {
    long started = System.nanoTime();
    long deadline = started + requestTimeout.toNanos();
    FailureClass failure = null;
    Integer status = null;
    try {
      List<InetAddress> targets = validatedAddresses(url);
      status = send(url, headers, body, targets, deadline);
      if (status < 200 || status > 299) {
        failure = FailureClass.HTTP_RESPONSE;
      }
    } catch (Failure refused) {
      failure = refused.failure;
    } catch (RuntimeException unexpected) {
      failure = FailureClass.INTERNAL_FAILURE;
    }
    return new Outcome(status, failure, (System.nanoTime() - started) / 1_000_000L);
  }

  private List<InetAddress> validatedAddresses(WebhookUrl url) {
    InetAddress[] answers;
    try {
      answers = dns.resolve(url.host());
    } catch (UnknownHostException unknown) {
      throw new Failure(FailureClass.DNS_FAILED);
    }
    if (answers == null || answers.length == 0) {
      throw new Failure(FailureClass.DNS_FAILED);
    }
    if (answers.length > DnsResolver.MAX_ANSWERS) {
      throw new Failure(FailureClass.DNS_BLOCKED);
    }
    boolean exempt =
        receiver.configured()
            && receiver.host().equals(url.host())
            && receiver.port() == url.port();
    List<InetAddress> accepted = new ArrayList<>();
    for (InetAddress address : answers) {
      if (!exempt && !AddressPolicy.isPublic(address)) {
        throw new Failure(FailureClass.DNS_BLOCKED);
      }
      accepted.add(address);
    }
    return accepted;
  }

  private int send(
      WebhookUrl url,
      Map<String, String> headers,
      byte[] body,
      List<InetAddress> targets,
      long deadline) {
    Socket plain = null;
    try {
      plain = connect(url, targets, deadline);
      SSLSocket tls = (SSLSocket) sockets.createSocket(plain, url.host(), url.port(), true);
      SSLParameters parameters = tls.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      parameters.setServerNames(List.of(new SNIHostName(url.host())));
      parameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
      tls.setSSLParameters(parameters);
      tls.setSoTimeout(remaining(deadline));
      tls.startHandshake();
      OutputStream out = tls.getOutputStream();
      out.write(request(url, headers, body));
      out.flush();
      return readStatus(tls, deadline);
    } catch (Failure failure) {
      throw failure;
    } catch (InterruptedIOException timeout) {
      throw new Failure(FailureClass.REQUEST_TIMEOUT);
    } catch (SSLException tls) {
      throw new Failure(
          tls.getCause() instanceof SocketTimeoutException
              ? FailureClass.REQUEST_TIMEOUT
              : FailureClass.TLS_FAILED);
    } catch (IOException io) {
      throw new Failure(FailureClass.CONNECTION_FAILED);
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

  private Socket connect(WebhookUrl url, List<InetAddress> targets, long deadline) {
    long connectDeadline = Math.min(deadline, System.nanoTime() + connectTimeout.toNanos());
    Failure last = new Failure(FailureClass.CONNECTION_FAILED);
    for (InetAddress address : targets) {
      Socket socket = new Socket();
      try {
        int wait = remaining(connectDeadline);
        socket.connect(new InetSocketAddress(address, url.port()), wait);
        return socket;
      } catch (SocketTimeoutException timeout) {
        last = new Failure(FailureClass.CONNECT_TIMEOUT);
      } catch (IOException refused) {
        last = new Failure(FailureClass.CONNECTION_FAILED);
      }
      try {
        socket.close();
      } catch (IOException ignored) {
        // not connected
      }
    }
    throw last;
  }

  private static int remaining(long deadlineNanos) {
    long millis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
    if (millis <= 0) {
      throw new Failure(FailureClass.REQUEST_TIMEOUT);
    }
    return (int) Math.min(millis, Integer.MAX_VALUE);
  }

  private static byte[] request(WebhookUrl url, Map<String, String> headers, byte[] body) {
    StringBuilder head = new StringBuilder(512);
    head.append("POST ").append(url.requestTarget()).append(" HTTP/1.1\r\n");
    head.append("Host: ")
        .append(url.port() == 443 ? url.host() : url.host() + ":" + url.port())
        .append("\r\n");
    headers.forEach((k, v) -> head.append(k).append(": ").append(v).append("\r\n"));
    head.append("Content-Length: ").append(body.length).append("\r\n");
    head.append("Connection: close\r\n\r\n");
    byte[] header = head.toString().getBytes(StandardCharsets.US_ASCII);
    byte[] all = new byte[header.length + body.length];
    System.arraycopy(header, 0, all, 0, header.length);
    System.arraycopy(body, 0, all, header.length, body.length);
    return all;
  }

  private static int readStatus(SSLSocket tls, long deadline) throws IOException {
    InputStream in = tls.getInputStream();
    ByteArrayOutputStream head = new ByteArrayOutputStream(512);
    byte[] buffer = new byte[1024];
    while (true) {
      tls.setSoTimeout(remaining(deadline));
      int read = in.read(buffer);
      if (read < 0) {
        throw new Failure(FailureClass.CONNECTION_FAILED);
      }
      head.write(buffer, 0, read);
      if (head.size() > MAX_HEADER_BYTES) {
        throw new Failure(FailureClass.CONNECTION_FAILED);
      }
      String text = head.toString(StandardCharsets.ISO_8859_1);
      int end = text.indexOf("\r\n");
      if (end >= 0) {
        return parseStatus(text.substring(0, end));
      }
    }
  }

  static int parseStatus(String line) {
    if (line.length() < 12 || !line.startsWith("HTTP/1.") || line.charAt(8) != ' ') {
      throw new Failure(FailureClass.CONNECTION_FAILED);
    }
    try {
      int code = Integer.parseInt(line.substring(9, 12));
      if (code < 100 || code > 599) {
        throw new Failure(FailureClass.CONNECTION_FAILED);
      }
      return code;
    } catch (NumberFormatException malformed) {
      throw new Failure(FailureClass.CONNECTION_FAILED);
    }
  }

  /** Internal control flow carrying only the closed class. */
  private static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient FailureClass failure;

    Failure(FailureClass failure) {
      super(failure.name(), null, false, false);
      this.failure = failure;
    }
  }
}
