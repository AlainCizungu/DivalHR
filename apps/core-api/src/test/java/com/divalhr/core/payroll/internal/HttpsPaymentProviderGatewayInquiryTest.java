package com.divalhr.core.payroll.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.application.PaymentProviderGateway.InquiryResult;
import com.divalhr.core.payroll.application.PaymentProviderGateway.Result;
import com.divalhr.core.payroll.application.PaymentSubmissionSecrets;
import com.divalhr.core.payroll.domain.PaymentInquiryBody;
import com.divalhr.core.payroll.domain.PaymentInquiryOutcome;
import com.divalhr.core.payroll.domain.PaymentMethod;
import com.divalhr.core.payroll.domain.PaymentSubmissionOutcome;
import com.divalhr.core.payroll.domain.PaymentSubmissionProfile;
import com.divalhr.core.payroll.domain.PaymentSubmissionSigner;
import com.divalhr.core.payroll.internal.HttpsPaymentProviderGateway.Hooks;
import com.sun.net.httpserver.HttpExchange;
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
 * MVP-095C (ADR 0038 section 4) status inquiry against an in-process HTTPS receiver and a
 * controlled resolver: path, headers and bytes, the classification table, the shared pinning and
 * SSRF rules, and the unchanged payment-request path. Synthetic values only.
 */
class HttpsPaymentProviderGatewayInquiryTest {

  private static final String KEY = "test-only-payment-submission-signing-key-001";
  private static final UUID REQUEST = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID INQUIRY = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final byte[] BODY = PaymentInquiryBody.canonical(REQUEST, INQUIRY);
  private static final byte[] PAYMENT_BODY =
      "{\"requestId\":\"22222222-2222-4222-8222-222222222222\"}".getBytes(StandardCharsets.UTF_8);
  private static final PaymentSubmissionSigner SIGNER = new PaymentSubmissionSigner(KEY);
  private static final List<String> NETWORKS = List.of("172.20.0.0/16");
  private static final InetAddress INTERNAL = address(172, 20, 0, 5);

  /** What the receiver does with the next request. */
  private interface Handler {
    void handle(HttpExchange exchange, byte[] request) throws IOException;
  }

  private HttpsServer server;
  private volatile int port;
  private volatile Handler handler;
  private SSLSocketFactory trusting;
  private final CountDownLatch release = new CountDownLatch(1);
  private final CopyOnWriteArrayList<String> inquiriesSeen = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<String> paymentsSeen = new CopyOnWriteArrayList<>();
  private final AtomicInteger hits = new AtomicInteger();
  private final AtomicInteger redirectTargetHits = new AtomicInteger();
  private volatile InetAddress[] answers;
  private final AtomicInteger resolutions = new AtomicInteger();
  private final CopyOnWriteArrayList<InetAddress> dialed = new CopyOnWriteArrayList<>();

  private static InetAddress address(int a, int b, int c, int d) {
    try {
      return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
    } catch (UnknownHostException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static InetAddress literal(String text) {
    try {
      return InetAddress.getByName(text);
    } catch (UnknownHostException impossible) {
      throw new AssertionError(impossible);
    }
  }

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
        "/v1/payment-status-inquiries",
        exchange -> {
          hits.incrementAndGet();
          byte[] request = exchange.getRequestBody().readAllBytes();
          inquiriesSeen.add(
              exchange.getRequestMethod()
                  + " id="
                  + exchange.getRequestHeaders().getFirst("X-Divalhr-Request-Id")
                  + " inquiry="
                  + exchange.getRequestHeaders().getFirst("X-Divalhr-Inquiry-Id")
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
        "/v1/payment-requests",
        exchange -> {
          hits.incrementAndGet();
          byte[] request = exchange.getRequestBody().readAllBytes();
          paymentsSeen.add(
              exchange.getRequestMethod()
                  + " id="
                  + exchange.getRequestHeaders().getFirst("X-Divalhr-Request-Id")
                  + " inquiry="
                  + exchange.getRequestHeaders().getFirst("X-Divalhr-Inquiry-Id")
                  + " kv="
                  + exchange.getRequestHeaders().getFirst("X-Divalhr-Key-Version")
                  + " sig="
                  + exchange.getRequestHeaders().getFirst("X-Divalhr-Signature")
                  + " type="
                  + exchange.getRequestHeaders().getFirst("Content-Type")
                  + " body="
                  + new String(request, StandardCharsets.UTF_8));
          byte[] answer =
              ("{\"requestId\":\""
                      + REQUEST
                      + "\",\"result\":\"ACCEPTED\",\"reference\":\"SYNTH-ABC123DEF456\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange
              .getResponseHeaders()
              .add("X-Divalhr-Response-Signature", SIGNER.sign(REQUEST, answer));
          exchange.sendResponseHeaders(200, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
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
    answers = new InetAddress[] {INTERNAL};
    handler = (exchange, request) -> respond(exchange, 200, json("PENDING", null), true);
  }

  @AfterEach
  void stop() {
    release.countDown();
    server.stop(0);
  }

  private static String json(String result, String code) {
    return "{\"requestId\":\""
        + REQUEST
        + "\",\"inquiryId\":\""
        + INQUIRY
        + "\",\"result\":\""
        + result
        + "\""
        + (code == null ? "" : ",\"code\":\"" + code + "\"")
        + "}";
  }

  private static void respond(HttpExchange exchange, int status, String json, boolean sign)
      throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    if (sign) {
      exchange
          .getResponseHeaders()
          .add("X-Divalhr-Response-Signature", SIGNER.signInquiry(REQUEST, INQUIRY, bytes));
    }
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private void reply(int status, String json, boolean sign) {
    handler = (exchange, request) -> respond(exchange, status, json, sign);
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
    Hooks hooks =
        new Hooks(
            host -> {
              resolutions.incrementAndGet();
              if (answers == null) {
                throw new UnknownHostException(host);
              }
              return answers;
            },
            (address, dialPort, timeout) -> {
              dialed.add(address);
              Socket socket = new Socket();
              socket.connect(
                  new InetSocketAddress(InetAddress.getLoopbackAddress(), dialPort), timeout);
              return socket;
            },
            Hooks.production().wire(),
            buffer -> {});
    return new HttpsPaymentProviderGateway(
        hooks,
        new PaymentSubmissionSecrets(SIGNER),
        Duration.ofSeconds(2),
        request,
        trusting,
        NETWORKS);
  }

  private PaymentInquiryOutcome inquire(boolean synthetic) {
    return inquire(gateway(Duration.ofSeconds(5)), profile("receiver.test", synthetic));
  }

  private static PaymentInquiryOutcome inquire(
      HttpsPaymentProviderGateway gateway, PaymentSubmissionProfile profile) {
    InquiryResult result =
        gateway.inquire(
            profile, REQUEST, INQUIRY, 1, SIGNER.signInquiry(REQUEST, INQUIRY, BODY), BODY);
    return result.outcome();
  }

  @Test
  void anInquiryIsPostedToTheInquiryPathWithTheExactBytesAndSignedHeaders() {
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.PENDING);
    assertThat(paymentsSeen).isEmpty();
    assertThat(inquiriesSeen)
        .containsExactly(
            "POST id="
                + REQUEST
                + " inquiry="
                + INQUIRY
                + " kv=1 sig="
                + SIGNER.signInquiry(REQUEST, INQUIRY, BODY)
                + " type=application/json body="
                + new String(BODY, StandardCharsets.UTF_8));
    assertThat(new String(BODY, StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"inquiryId\":\""
                + INQUIRY
                + "\",\"requestId\":\""
                + REQUEST
                + "\",\"schemaVersion\":1}");
  }

  @Test
  void aSignedAnswerIsClassifiedByItsResult() {
    reply(200, json("ACCEPTED_FOR_PROCESSING", null), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.ACCEPTED_FOR_PROCESSING);
    reply(200, json("NOT_FOUND", null), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.NOT_FOUND);
    reply(200, json("PENDING", null), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.PENDING);
    reply(200, json("UNKNOWN", null), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.UNKNOWN);
  }

  @Test
  void aSignedRejectionMapsItsAllowListedCodeAndAnyOtherCodeIsOther() {
    reply(200, json("REJECTED", "INVALID_REQUEST"), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.REJECTED_INVALID_REQUEST);
    reply(200, json("REJECTED", "SIGNATURE"), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.REJECTED_SIGNATURE);
    reply(200, json("REJECTED", "UNSUPPORTED"), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.REJECTED_UNSUPPORTED);
    reply(200, json("REJECTED", "DUPLICATE_MISMATCH"), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.REJECTED_DUPLICATE_MISMATCH);
    reply(200, json("REJECTED", "LIMIT"), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.REJECTED_LIMIT);
    reply(200, json("REJECTED", "SOMETHING_NEW"), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.REJECTED_OTHER);
    // A rejection without a code is not a decision.
    reply(200, json("REJECTED", null), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);
  }

  @Test
  void anUnsignedWronglySignedMismatchedOrMalformedAnswerIsBadResponse() {
    reply(200, json("ACCEPTED_FOR_PROCESSING", null), false);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);

    // A signature made as a payment-request signature (other domain) is refused.
    handler =
        (exchange, request) -> {
          byte[] bytes = json("ACCEPTED_FOR_PROCESSING", null).getBytes(StandardCharsets.UTF_8);
          exchange
              .getResponseHeaders()
              .add("X-Divalhr-Response-Signature", SIGNER.sign(REQUEST, bytes));
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        };
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);

    // Signed, but for another request or another inquiry.
    reply(
        200,
        "{\"requestId\":\"33333333-3333-4333-8333-333333333333\",\"inquiryId\":\""
            + INQUIRY
            + "\",\"result\":\"ACCEPTED_FOR_PROCESSING\"}",
        true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);
    reply(
        200,
        "{\"requestId\":\""
            + REQUEST
            + "\",\"inquiryId\":\"33333333-3333-4333-8333-333333333333\","
            + "\"result\":\"ACCEPTED_FOR_PROCESSING\"}",
        true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);

    reply(200, "{\"requestId\":\"" + REQUEST + "\",\"result\":\"ACCEPTED_FOR_PROCESSING\"}", true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);
    reply(200, "not json", true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);
    reply(200, json("PAID", null), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);
    reply(200, json("SETTLED", null), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);
    // A non-200 signed answer is not an answer either.
    reply(404, json("NOT_FOUND", null), true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);
  }

  @Test
  void aTooManyRequestsOrServerErrorAnswerIsTransient() {
    reply(429, "{}", false);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.TRANSIENT_RATE_LIMITED);
    reply(503, "{}", false);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.TRANSIENT_SERVER);
    reply(500, "{}", true);
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.TRANSIENT_SERVER);
  }

  @Test
  void redirectsAreNeverFollowedAndAreNotAnAnswer() {
    handler =
        (exchange, request) -> {
          exchange.getResponseHeaders().add("Location", "/target");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        };
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);
    assertThat(redirectTargetHits).hasValue(0);
  }

  @Test
  void anOversizedResponseIsBadResponse() {
    handler =
        (exchange, request) -> {
          byte[] big = new byte[5000];
          java.util.Arrays.fill(big, (byte) 'a');
          exchange.sendResponseHeaders(200, big.length);
          exchange.getResponseBody().write(big);
          exchange.close();
        };
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.BAD_RESPONSE);
  }

  @Test
  void aTimeoutIsATransientTimeoutBecauseAnInquiryIsSafeToRepeat() {
    handler =
        (exchange, request) -> {
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        };
    assertThat(inquire(gateway(Duration.ofMillis(1500)), profile("receiver.test", true)))
        .isEqualTo(PaymentInquiryOutcome.TRANSIENT_TIMEOUT);
    assertThat(hits).hasValue(1);
  }

  @Test
  void aConnectionClosedWithoutAnAnswerIsRetryableOrBad() {
    handler = (exchange, request) -> exchange.close();
    assertThat(inquire(true))
        .isIn(PaymentInquiryOutcome.TRANSIENT_CONNECT, PaymentInquiryOutcome.BAD_RESPONSE);
    assertThat(inquire(true).kind())
        .isIn(PaymentInquiryOutcome.Kind.TRANSIENT, PaymentInquiryOutcome.Kind.BAD);
  }

  @Test
  void connectAndNameFailuresAreTransientConnectAndTlsFailureIsTransientTls() throws Exception {
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
    assertThat(inquire(gateway(Duration.ofSeconds(5)), refused))
        .isEqualTo(PaymentInquiryOutcome.TRANSIENT_CONNECT);
    answers = null;
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.TRANSIENT_CONNECT);
    assertThat(hits).hasValue(0);
    answers = new InetAddress[] {INTERNAL};
    assertThat(inquire(gateway(Duration.ofSeconds(5)), profile("other.test", true)))
        .isEqualTo(PaymentInquiryOutcome.TRANSIENT_TLS);
    assertThat(hits).hasValue(0);
  }

  @Test
  void theSameResolutionPinningAndAddressRulesApplyToAnInquiry() {
    assertThat(inquire(true)).isEqualTo(PaymentInquiryOutcome.PENDING);
    assertThat(resolutions).hasValue(1);
    assertThat(dialed).containsExactly(INTERNAL);

    dialed.clear();
    InetAddress[] forbidden = {
      InetAddress.getLoopbackAddress(),
      literal("169.254.169.254"),
      literal("10.0.0.5"),
      literal("93.184.216.34"),
      literal("::1"),
      literal("::ffff:127.0.0.1"),
    };
    int before = hits.get();
    for (InetAddress bad : forbidden) {
      answers = new InetAddress[] {bad};
      assertThat(inquire(true))
          .as("%s alone", bad.getHostAddress())
          .isEqualTo(PaymentInquiryOutcome.TRANSIENT_CONNECT);
      answers = new InetAddress[] {INTERNAL, bad};
      assertThat(inquire(true))
          .as("%s mixed", bad.getHostAddress())
          .isEqualTo(PaymentInquiryOutcome.TRANSIENT_CONNECT);
    }
    assertThat(dialed).isEmpty();
    assertThat(hits).hasValue(before);
  }

  @Test
  void aRealProviderIsNeverInquiredOnAPrivateOrInternalAddress() {
    answers = new InetAddress[] {INTERNAL};
    assertThat(inquire(false)).isEqualTo(PaymentInquiryOutcome.TRANSIENT_CONNECT);
    answers = new InetAddress[] {InetAddress.getLoopbackAddress()};
    assertThat(inquire(false)).isEqualTo(PaymentInquiryOutcome.TRANSIENT_CONNECT);
    assertThat(dialed).isEmpty();
    assertThat(hits).hasValue(0);
  }

  @Test
  void aRebindingStyleSecondAnswerIsNeverLookedUpOrDialedForAnInquiry() {
    AtomicInteger calls = new AtomicInteger();
    InetAddress[] first = {INTERNAL};
    InetAddress[] second = {InetAddress.getLoopbackAddress()};
    Hooks hooks =
        new Hooks(
            host -> calls.incrementAndGet() == 1 ? first : second,
            (address, dialPort, timeout) -> {
              dialed.add(address);
              Socket socket = new Socket();
              socket.connect(
                  new InetSocketAddress(InetAddress.getLoopbackAddress(), dialPort), timeout);
              return socket;
            },
            Hooks.production().wire(),
            buffer -> {});
    HttpsPaymentProviderGateway gateway =
        new HttpsPaymentProviderGateway(
            hooks,
            new PaymentSubmissionSecrets(SIGNER),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            trusting,
            NETWORKS);
    assertThat(inquire(gateway, profile("receiver.test", true)))
        .isEqualTo(PaymentInquiryOutcome.PENDING);
    assertThat(calls).hasValue(1);
    assertThat(dialed).containsExactly(INTERNAL);
  }

  @Test
  void thePaymentRequestPathHeadersAndBytesAreUnchanged() {
    Result result =
        gateway(Duration.ofSeconds(5))
            .send(
                profile("receiver.test", true),
                REQUEST,
                1,
                SIGNER.sign(REQUEST, PAYMENT_BODY),
                PAYMENT_BODY);
    assertThat(result.outcome()).isEqualTo(PaymentSubmissionOutcome.ACCEPTED);
    assertThat(result.reference()).isEqualTo("SYNTH-ABC123DEF456");
    assertThat(inquiriesSeen).isEmpty();
    assertThat(paymentsSeen)
        .containsExactly(
            "POST id="
                + REQUEST
                + " inquiry=null kv=1 sig="
                + SIGNER.sign(REQUEST, PAYMENT_BODY)
                + " type=application/json body="
                + new String(PAYMENT_BODY, StandardCharsets.UTF_8));
  }
}
