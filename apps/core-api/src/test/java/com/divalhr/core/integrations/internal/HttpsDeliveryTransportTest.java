package com.divalhr.core.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.integrations.application.DeliveryTransport.Outcome;
import com.divalhr.core.integrations.application.DnsResolver;
import com.divalhr.core.integrations.application.IntegrationProperties;
import com.divalhr.core.integrations.application.IntegrationProperties.TestReceiver;
import com.divalhr.core.integrations.domain.FailureClass;
import com.divalhr.core.integrations.domain.WebhookUrl;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The delivery network boundary against an in-process HTTPS receiver and a controlled resolver. */
class HttpsDeliveryTransportTest {

  private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
  private static final Map<String, String> HEADERS =
      Map.of("Content-Type", "application/json", "X-DivalHR-Signature", "v1=abc");

  private HttpsServer server;
  private volatile int port;
  private SSLSocketFactory trusting;
  private final CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
  private final AtomicInteger redirectTargetHits = new AtomicInteger();
  private final AtomicInteger resolutions = new AtomicInteger();
  private volatile InetAddress[] answers;

  @BeforeEach
  void start() throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = getClass().getResourceAsStream("/integrations/receiver.p12")) {
      store.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(store, "changeit".toCharArray());
    SSLContext serverContext = SSLContext.getInstance("TLS");
    serverContext.init(kmf.getKeyManagers(), null, null);
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(store);
    SSLContext clientContext = SSLContext.getInstance("TLS");
    clientContext.init(null, tmf.getTrustManagers(), null);
    trusting = clientContext.getSocketFactory();

    server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
    server.createContext(
        "/ok",
        exchange -> {
          seen.add(
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI()
                  + " host="
                  + exchange.getRequestHeaders().getFirst("Host")
                  + " type="
                  + exchange.getRequestHeaders().getFirst("Content-Type")
                  + " sig="
                  + exchange.getRequestHeaders().getFirst("X-DivalHR-Signature")
                  + " body="
                  + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.createContext(
        "/fail",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/target");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/target",
        exchange -> {
          redirectTargetHits.incrementAndGet();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.createContext(
        "/huge-body",
        exchange -> {
          exchange.sendResponseHeaders(200, 5_000_000);
          try (var out = exchange.getResponseBody()) {
            out.write(new byte[5_000_000]);
          } catch (java.io.IOException clientClosedEarly) {
            // the transport does not read bodies
          }
        });
    server.createContext(
        "/huge-header",
        exchange -> {
          exchange.getResponseHeaders().add("X-Big", "x".repeat(40_000));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    port = server.getAddress().getPort();
    answers = new InetAddress[] {InetAddress.getLoopbackAddress()};
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private DnsResolver resolver() {
    return host -> {
      resolutions.incrementAndGet();
      if (answers == null) {
        throw new UnknownHostException(host);
      }
      return answers;
    };
  }

  private HttpsDeliveryTransport transport(
      String exemptHost, Duration request, SSLSocketFactory tls) {
    TestReceiver receiver = exemptHost == null ? null : new TestReceiver(exemptHost, port, null);
    var properties =
        new IntegrationProperties(
            null, List.of(port), false, Duration.ofSeconds(3), request, receiver);
    return new HttpsDeliveryTransport(resolver(), properties, tls);
  }

  private WebhookUrl url(String host, String path) {
    return WebhookUrl.parse("https://" + host + ":" + port + path, List.of(port)).orElseThrow();
  }

  @Test
  void deliversExactBytesAndHeadersToTheValidatedAddress() {
    Outcome outcome =
        transport("receiver.test", Duration.ofSeconds(5), trusting)
            .post(url("receiver.test", "/ok?x=1"), HEADERS, BODY);
    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.httpStatus()).isEqualTo(204);
    assertThat(seen)
        .containsExactly(
            "POST /ok?x=1 host=receiver.test:"
                + port
                + " type=application/json sig=v1=abc body={\"a\":1}");
  }

  @Test
  void resolvesAgainOnEveryAttempt() {
    var transport = transport("receiver.test", Duration.ofSeconds(5), trusting);
    transport.post(url("receiver.test", "/ok"), HEADERS, BODY);
    transport.post(url("receiver.test", "/ok"), HEADERS, BODY);
    assertThat(resolutions).hasValue(2);
  }

  @Test
  void anyNon2xxIsAFailedAttemptWithTheExactStatus() {
    Outcome outcome =
        transport("receiver.test", Duration.ofSeconds(5), trusting)
            .post(url("receiver.test", "/fail"), HEADERS, BODY);
    assertThat(outcome.failure()).isEqualTo(FailureClass.HTTP_RESPONSE);
    assertThat(outcome.httpStatus()).isEqualTo(500);
  }

  @Test
  void redirectsAreNeverFollowed() {
    Outcome outcome =
        transport("receiver.test", Duration.ofSeconds(5), trusting)
            .post(url("receiver.test", "/redirect"), HEADERS, BODY);
    assertThat(outcome.failure()).isEqualTo(FailureClass.HTTP_RESPONSE);
    assertThat(outcome.httpStatus()).isEqualTo(302);
    assertThat(redirectTargetHits).hasValue(0);
  }

  @Test
  void theResponseBodyIsNotConsumed() {
    Outcome outcome =
        transport("receiver.test", Duration.ofSeconds(5), trusting)
            .post(url("receiver.test", "/huge-body"), HEADERS, BODY);
    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.latencyMillis()).isLessThan(4000);
  }

  @Test
  void oversizedResponseHeadersAreBounded() {
    Outcome outcome =
        transport("receiver.test", Duration.ofSeconds(5), trusting)
            .post(url("receiver.test", "/huge-header"), HEADERS, BODY);
    assertThat(outcome.succeeded()).isTrue();
  }

  @Test
  void forbiddenDnsAnswersFailBeforeAnyConnection() throws Exception {
    for (String literal :
        new String[] {
          "127.0.0.1",
          "10.1.2.3",
          "169.254.169.254",
          "100.64.0.9",
          "::1",
          "fd00:ec2::254",
          "::ffff:127.0.0.1",
          "192.168.0.10",
          "0.0.0.0"
        }) {
      answers = new InetAddress[] {InetAddress.getByName(literal)};
      Outcome outcome =
          transport(null, Duration.ofSeconds(5), trusting)
              .post(url("hooks.example.com", "/ok"), HEADERS, BODY);
      assertThat(outcome.failure()).as(literal).isEqualTo(FailureClass.DNS_BLOCKED);
      assertThat(outcome.httpStatus()).isNull();
    }
    assertThat(seen).isEmpty();
  }

  @Test
  void aMixOfSafeAndUnsafeAnswersFailsClosed() throws Exception {
    answers =
        new InetAddress[] {
          InetAddress.getByName("93.184.216.34"), InetAddress.getLoopbackAddress()
        };
    Outcome outcome =
        transport(null, Duration.ofSeconds(5), trusting)
            .post(url("hooks.example.com", "/ok"), HEADERS, BODY);
    assertThat(outcome.failure()).isEqualTo(FailureClass.DNS_BLOCKED);
    assertThat(seen).isEmpty();
  }

  @Test
  void tooManyAnswersAreRefused() throws Exception {
    InetAddress[] many = new InetAddress[DnsResolver.MAX_ANSWERS + 1];
    java.util.Arrays.fill(many, InetAddress.getByName("93.184.216.34"));
    answers = many;
    Outcome outcome =
        transport(null, Duration.ofSeconds(5), trusting)
            .post(url("hooks.example.com", "/ok"), HEADERS, BODY);
    assertThat(outcome.failure()).isEqualTo(FailureClass.DNS_BLOCKED);
  }

  @Test
  void unresolvableNamesAreADnsFailure() {
    answers = null;
    assertThat(
            transport(null, Duration.ofSeconds(5), trusting)
                .post(url("hooks.example.com", "/ok"), HEADERS, BODY)
                .failure())
        .isEqualTo(FailureClass.DNS_FAILED);
    answers = new InetAddress[0];
    assertThat(
            transport(null, Duration.ofSeconds(5), trusting)
                .post(url("hooks.example.com", "/ok"), HEADERS, BODY)
                .failure())
        .isEqualTo(FailureClass.DNS_FAILED);
  }

  @Test
  void theReceiverExceptionIsOneExactHostAndPort() {
    var transport = transport("receiver.test", Duration.ofSeconds(5), trusting);
    // another host that resolves to the same loopback address stays blocked
    assertThat(transport.post(url("other.test", "/ok"), HEADERS, BODY).failure())
        .isEqualTo(FailureClass.DNS_BLOCKED);
    // the same host on another port stays blocked
    var otherPort =
        WebhookUrl.parse("https://receiver.test:" + (port + 1) + "/ok", List.of(port + 1))
            .orElseThrow();
    assertThat(transport.post(otherPort, HEADERS, BODY).failure())
        .isEqualTo(FailureClass.DNS_BLOCKED);
    // a sibling name does not match
    assertThat(transport.post(url("a.receiver.test", "/ok"), HEADERS, BODY).failure())
        .isEqualTo(FailureClass.DNS_BLOCKED);
    assertThat(seen).isEmpty();
  }

  @Test
  void theReceiverExceptionDoesNotOpenMetadataOrPrivateRangesForOtherHosts() throws Exception {
    var transport = transport("receiver.test", Duration.ofSeconds(5), trusting);
    for (String literal :
        new String[] {"169.254.169.254", "10.0.0.5", "127.0.0.1", "fd00:ec2::254"}) {
      answers = new InetAddress[] {InetAddress.getByName(literal)};
      assertThat(transport.post(url("hooks.example.com", "/ok"), HEADERS, BODY).failure())
          .as(literal)
          .isEqualTo(FailureClass.DNS_BLOCKED);
    }
  }

  @Test
  void certificateAndHostnameVerificationStayOnForTheReceiver() {
    // the certificate names receiver.test: another exact host fails the hostname check
    assertThat(
            transport("other.test", Duration.ofSeconds(5), trusting)
                .post(url("other.test", "/ok"), HEADERS, BODY)
                .failure())
        .isEqualTo(FailureClass.TLS_FAILED);
    // an untrusted certificate fails with the default trust
    assertThat(
            transport(
                    "receiver.test",
                    Duration.ofSeconds(5),
                    (SSLSocketFactory) SSLSocketFactory.getDefault())
                .post(url("receiver.test", "/ok"), HEADERS, BODY)
                .failure())
        .isEqualTo(FailureClass.TLS_FAILED);
    assertThat(seen).isEmpty();
  }

  @Test
  void aRefusedConnectionIsAConnectionFailure() throws Exception {
    int closed;
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closed = probe.getLocalPort();
    }
    var properties =
        new IntegrationProperties(
            null,
            List.of(closed),
            false,
            Duration.ofSeconds(3),
            Duration.ofSeconds(3),
            new TestReceiver("receiver.test", closed, null));
    var transport = new HttpsDeliveryTransport(resolver(), properties, trusting);
    var target =
        WebhookUrl.parse("https://receiver.test:" + closed + "/ok", List.of(closed)).orElseThrow();
    assertThat(transport.post(target, HEADERS, BODY).failure())
        .isEqualTo(FailureClass.CONNECTION_FAILED);
  }

  @Test
  void aSilentServerHitsTheRequestDeadline() throws Exception {
    try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
      Thread acceptor =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket held = silent.accept()) {
                      held.getInetAddress();
                      Thread.sleep(3000);
                    } catch (IOException | InterruptedException ignored) {
                      // test teardown
                    }
                  });
      int quiet = silent.getLocalPort();
      var properties =
          new IntegrationProperties(
              null,
              List.of(quiet),
              false,
              Duration.ofSeconds(3),
              Duration.ofMillis(600),
              new TestReceiver("receiver.test", quiet, null));
      var transport = new HttpsDeliveryTransport(resolver(), properties, trusting);
      var target =
          WebhookUrl.parse("https://receiver.test:" + quiet + "/ok", List.of(quiet)).orElseThrow();
      Outcome outcome = transport.post(target, HEADERS, BODY);
      assertThat(outcome.failure()).isEqualTo(FailureClass.REQUEST_TIMEOUT);
      assertThat(outcome.latencyMillis()).isBetween(500L, 2500L);
      acceptor.interrupt();
    }
  }

  @Test
  void statusLinesAreParsedStrictly() {
    assertThat(HttpsDeliveryTransport.parseStatus("HTTP/1.1 204 No Content")).isEqualTo(204);
    for (String bad :
        new String[] {
          "", "HTTP/2 200", "HTTP/1.1 20", "HTTP/1.1 abc", "HTTP/1.1 099 x", "XTTP/1.1 200 OK"
        }) {
      org.junit.jupiter.api.Assertions.assertThrows(
          RuntimeException.class, () -> HttpsDeliveryTransport.parseStatus(bad));
    }
  }
}
