package com.divalhr.core.payroll.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.application.PaymentProviderGateway.Result;
import com.divalhr.core.payroll.application.PaymentSubmissionSecrets;
import com.divalhr.core.payroll.domain.PaymentMethod;
import com.divalhr.core.payroll.domain.PaymentSubmissionOutcome;
import com.divalhr.core.payroll.domain.PaymentSubmissionProfile;
import com.divalhr.core.payroll.domain.PaymentSubmissionSigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MVP-095B (ADR 0037) network boundary against an in-process HTTPS receiver and a controlled
 * resolver: exact bytes and signed headers, only an authenticated well-formed answer decides
 * anything, a rejection is final, a timeout or loss after the write is an unknown delivery, a
 * failure before any byte was written is transient, redirects are never followed, oversized
 * responses are refused, a private address is refused for a real provider and a certificate for
 * another host name is refused. Synthetic values only.
 */
class HttpsPaymentProviderGatewayTest {

  private static final String KEY = "test-only-payment-submission-signing-key-001";
  private static final UUID REQUEST = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final byte[] BODY =
      "{\"requestId\":\"22222222-2222-4222-8222-222222222222\"}".getBytes(StandardCharsets.UTF_8);
  private static final PaymentSubmissionSigner SIGNER = new PaymentSubmissionSigner(KEY);

  /** What the receiver does with the next request. */
  private interface Handler {
    void handle(HttpExchange exchange, byte[] request) throws IOException;
  }

  private HttpsServer server;
  private volatile int port;
  private volatile Handler handler;
  private SSLSocketFactory trusting;
  private final CountDownLatch release = new CountDownLatch(1);
  private final CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
  private final AtomicInteger hits = new AtomicInteger();
  private final AtomicInteger redirectTargetHits = new AtomicInteger();
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
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.createContext(
        "/v1/payment-requests",
        exchange -> {
          hits.incrementAndGet();
          byte[] request = exchange.getRequestBody().readAllBytes();
          seen.add(
              exchange.getRequestMethod()
                  + " id="
                  + exchange.getRequestHeaders().getFirst("X-Divalhr-Request-Id")
                  + " kv="
                  + exchange.getRequestHeaders().getFirst("X-Divalhr-Key-Version")
                  + " sig="
                  + exchange.getRequestHeaders().getFirst("X-Divalhr-Signature")
                  + " type="
                  + exchange.getRequestHeaders().getFirst("Content-Type")
                  + " body="
                  + new String(request, StandardCharsets.UTF_8));
          handler.handle(exchange, request);
        });
    server.createContext(
        "/target",
        exchange -> {
          redirectTargetHits.incrementAndGet();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    port = server.getAddress().getPort();
    answers = new InetAddress[] {InetAddress.getLoopbackAddress()};
    handler = (exchange, request) -> respond(exchange, 200, accepted("SYNTH-ABC123DEF456"), true);
  }

  @AfterEach
  void stop() {
    release.countDown();
    server.stop(0);
  }

  private static String accepted(String reference) {
    return "{\"requestId\":\""
        + REQUEST
        + "\",\"result\":\"ACCEPTED\",\"reference\":\""
        + reference
        + "\"}";
  }

  private static String rejected(String code) {
    return "{\"requestId\":\"" + REQUEST + "\",\"result\":\"REJECTED\",\"code\":\"" + code + "\"}";
  }

  private static void respond(HttpExchange exchange, int status, String json, boolean sign)
      throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    if (sign) {
      exchange
          .getResponseHeaders()
          .add("X-Divalhr-Response-Signature", SIGNER.sign(REQUEST, bytes));
    }
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private PaymentSubmissionProfile profile(String host, boolean synthetic) {
    return new PaymentSubmissionProfile(
        "SYNTHETIC-TEST",
        "Synthetic test provider",
        host,
        port,
        null,
        Set.of("CDF"),
        Set.of(PaymentMethod.BANK_ACCOUNT, PaymentMethod.MOBILE_MONEY),
        synthetic,
        1);
  }

  private HttpsPaymentProviderGateway gateway(Duration request) {
    return new HttpsPaymentProviderGateway(
        host -> {
          if (answers == null) {
            throw new UnknownHostException(host);
          }
          return answers;
        },
        new PaymentSubmissionSecrets(SIGNER),
        Duration.ofSeconds(2),
        request,
        trusting);
  }

  private Result send(boolean synthetic) {
    return gateway(Duration.ofSeconds(5))
        .send(profile("receiver.test", synthetic), REQUEST, 1, SIGNER.sign(REQUEST, BODY), BODY);
  }

  @Test
  void sendsTheExactBytesAndSignedHeadersAndReturnsAnAuthenticatedAcceptance() {
    Result result = send(true);
    assertThat(result.outcome()).isEqualTo(PaymentSubmissionOutcome.ACCEPTED);
    assertThat(result.reference()).isEqualTo("SYNTH-ABC123DEF456");
    assertThat(seen)
        .containsExactly(
            "POST id="
                + REQUEST
                + " kv=1 sig="
                + SIGNER.sign(REQUEST, BODY)
                + " type=application/json body="
                + new String(BODY, StandardCharsets.UTF_8));
  }

  @Test
  void aSignedRejectionIsFinalAndAnUnknownRejectCodeIsOther() {
    handler = (exchange, request) -> respond(exchange, 422, rejected("SIGNATURE"), true);
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.REJECTED_SIGNATURE);
    handler = (exchange, request) -> respond(exchange, 409, rejected("DUPLICATE_MISMATCH"), true);
    assertThat(send(true).outcome())
        .isEqualTo(PaymentSubmissionOutcome.REJECTED_DUPLICATE_MISMATCH);
    handler = (exchange, request) -> respond(exchange, 400, rejected("SOMETHING_NEW"), true);
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.REJECTED_OTHER);
  }

  @Test
  void anUnsignedOrMismatchedOrMalformedAcceptanceIsNeverAnAcceptance() {
    handler = (exchange, request) -> respond(exchange, 200, accepted("SYNTH-ABC123DEF456"), false);
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);

    String otherRequest =
        "{\"requestId\":\"33333333-3333-4333-8333-333333333333\",\"result\":\"ACCEPTED\","
            + "\"reference\":\"SYNTH-ABC123DEF456\"}";
    handler = (exchange, request) -> respond(exchange, 200, otherRequest, true);
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);

    handler = (exchange, request) -> respond(exchange, 200, accepted("x"), true);
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);

    handler = (exchange, request) -> respond(exchange, 200, "not json", true);
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);

    handler =
        (exchange, request) ->
            respond(exchange, 200, "{\"requestId\":\"" + REQUEST + "\",\"result\":\"PAID\"}", true);
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
  }

  @Test
  void aTooManyRequestsOrServerErrorAnswerIsTransient() {
    handler = (exchange, request) -> respond(exchange, 429, "{}", false);
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.TRANSIENT_RATE_LIMITED);
    handler = (exchange, request) -> respond(exchange, 503, "{}", false);
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.TRANSIENT_SERVER);
  }

  @Test
  void redirectsAreNeverFollowedAndAreNotAnAnswer() {
    handler =
        (exchange, request) -> {
          exchange.getResponseHeaders().add("Location", "/target");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        };
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
    assertThat(redirectTargetHits).hasValue(0);
  }

  @Test
  void anOversizedResponseIsRefusedAsUnknown() {
    handler =
        (exchange, request) -> {
          byte[] big = new byte[5000];
          java.util.Arrays.fill(big, (byte) 'a');
          exchange.sendResponseHeaders(200, big.length);
          exchange.getResponseBody().write(big);
          exchange.close();
        };
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
  }

  @Test
  void aTimeoutAfterTheRequestWasWrittenIsAnUnknownDeliveryNotATransientFailure() {
    handler =
        (exchange, request) -> {
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        };
    Result result =
        gateway(Duration.ofMillis(1500))
            .send(profile("receiver.test", true), REQUEST, 1, SIGNER.sign(REQUEST, BODY), BODY);
    assertThat(result.outcome()).isEqualTo(PaymentSubmissionOutcome.UNKNOWN_TIMEOUT);
    assertThat(hits).hasValue(1);
  }

  @Test
  void aConnectionClosedWithoutAnAnswerAfterTheWriteIsAnUnknownDelivery() {
    handler = (exchange, request) -> exchange.close();
    assertThat(send(true).outcome())
        .isIn(
            PaymentSubmissionOutcome.UNKNOWN_CONNECTION_LOST,
            PaymentSubmissionOutcome.UNKNOWN_BAD_RESPONSE);
    assertThat(hits).hasValue(1);
  }

  @Test
  void aFailureBeforeAnyByteWasWrittenIsTransient() throws Exception {
    // Nothing is listening.
    int closed;
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closed = probe.getLocalPort();
    }
    PaymentSubmissionProfile refused =
        new PaymentSubmissionProfile(
            "SYNTHETIC-TEST",
            "x",
            "receiver.test",
            closed,
            null,
            Set.of("CDF"),
            Set.of(PaymentMethod.BANK_ACCOUNT),
            true,
            1);
    assertThat(
            gateway(Duration.ofSeconds(5))
                .send(refused, REQUEST, 1, SIGNER.sign(REQUEST, BODY), BODY)
                .outcome())
        .isEqualTo(PaymentSubmissionOutcome.TRANSIENT_CONNECT);

    // The name does not resolve.
    answers = null;
    assertThat(send(true).outcome()).isEqualTo(PaymentSubmissionOutcome.TRANSIENT_CONNECT);
    assertThat(hits).hasValue(0);
  }

  @Test
  void aRealProviderIsNeverContactedOnAPrivateOrLoopbackAddress() {
    assertThat(send(false).outcome()).isEqualTo(PaymentSubmissionOutcome.TRANSIENT_CONNECT);
    assertThat(hits).hasValue(0);
    answers = new InetAddress[] {InetAddress.getLoopbackAddress(), publicAddress()};
    assertThat(send(false).outcome()).isEqualTo(PaymentSubmissionOutcome.TRANSIENT_CONNECT);
    assertThat(hits).hasValue(0);
  }

  private static InetAddress publicAddress() {
    try {
      return InetAddress.getByAddress(new byte[] {(byte) 93, (byte) 184, (byte) 216, (byte) 34});
    } catch (UnknownHostException impossible) {
      throw new AssertionError(impossible);
    }
  }

  @Test
  void aCertificateForAnotherHostNameFailsTheHandshakeBeforeAnythingIsWritten() {
    Result result =
        gateway(Duration.ofSeconds(5))
            .send(profile("other.test", true), REQUEST, 1, SIGNER.sign(REQUEST, BODY), BODY);
    assertThat(result.outcome()).isEqualTo(PaymentSubmissionOutcome.TRANSIENT_TLS);
    assertThat(hits).hasValue(0);
    assertThat(result.reference()).isNull();
  }
}
