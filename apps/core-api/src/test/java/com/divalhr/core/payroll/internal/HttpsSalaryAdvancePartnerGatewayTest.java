package com.divalhr.core.payroll.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.application.SalaryAdvancePartnerGateway.InquiryOutcome;
import com.divalhr.core.payroll.application.SalaryAdvancePartnerGateway.InquiryResult;
import com.divalhr.core.payroll.application.SalaryAdvancePartnerGateway.Result;
import com.divalhr.core.payroll.application.SalaryAdvancePartnerProfile;
import com.divalhr.core.payroll.application.SalaryAdvancePartnerSecrets;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffOutcome;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffSigner;
import com.divalhr.core.payroll.internal.HttpsSalaryAdvancePartnerGateway.Hooks;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MVP-120B (ADR 0042 section 6) delivery classification against an in-process HTTPS receiver and a
 * controlled resolver. Once the request was written the partner may have kept the application, so
 * an unsigned 429 or 5xx is an unknown delivery and never a retry; only a failure before any byte
 * was written is transient; and only a signed, application-bound {@code NOT_RECEIVED} inquiry
 * answer permits a resend. Synthetic values only.
 */
class HttpsSalaryAdvancePartnerGatewayTest {

  private static final String KEY = "test-only-salary-advance-handoff-signing-key-001";
  private static final UUID APPLICATION = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID OTHER = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final byte[] BODY =
      ("{\"applicationId\":\"" + APPLICATION + "\",\"schemaVersion\":1}")
          .getBytes(StandardCharsets.UTF_8);
  private static final SalaryAdvanceHandoffSigner SIGNER = new SalaryAdvanceHandoffSigner(KEY);
  private static final List<String> NETWORKS = List.of("172.20.0.0/16");
  private static final InetAddress INTERNAL = address(172, 20, 0, 5);
  private static final String REFERENCE = "SYNTH-HANDOFF-0001";

  /** What the receiver does with the next request on one path. */
  private interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }

  private HttpsServer server;
  private volatile int port;
  private volatile Handler delivery;
  private volatile Handler inquiry;
  private volatile InetAddress[] answers = new InetAddress[] {INTERNAL};
  private SSLSocketFactory trusting;
  private final AtomicInteger deliveries = new AtomicInteger();
  private final AtomicInteger inquiries = new AtomicInteger();

  /** The synthetic receiver's own memory: application ids it has kept. */
  private final Map<UUID, String> kept = new ConcurrentHashMap<>();

  private static InetAddress address(int a, int b, int c, int d) {
    try {
      return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
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
        "/v1/salary-advance-handoffs",
        exchange -> {
          deliveries.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          delivery.handle(exchange);
        });
    server.createContext(
        "/v1/salary-advance-handoff-inquiries",
        exchange -> {
          inquiries.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          inquiry.handle(exchange);
        });
    server.start();
    port = server.getAddress().getPort();
    delivery = acknowledge();
    inquiry = truth();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  // ---- the synthetic receiver's behaviours -------------------------------------------------

  private static void answer(HttpExchange exchange, int status, String json, String signature)
      throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    if (signature != null) {
      exchange.getResponseHeaders().add("X-Divalhr-Response-Signature", signature);
    }
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static String received(UUID id) {
    return "{\"applicationId\":\""
        + id
        + "\",\"result\":\"RECEIVED\",\"reference\":\""
        + REFERENCE
        + "\"}";
  }

  private Handler acknowledge() {
    return exchange -> {
      kept.put(APPLICATION, REFERENCE);
      String json = received(APPLICATION);
      answer(
          exchange, 200, json, SIGNER.signAck(APPLICATION, json.getBytes(StandardCharsets.UTF_8)));
    };
  }

  /** Refuses before keeping anything. */
  private static Handler refuseUnsigned(int status) {
    return exchange -> {
      exchange.getResponseHeaders().add("Retry-After", "1");
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    };
  }

  /** Keeps the application, then fails: indistinguishable from {@link #refuseUnsigned}. */
  private Handler storeThenFail(int status) {
    return exchange -> {
      kept.put(APPLICATION, REFERENCE);
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    };
  }

  /** The inquiry answers the truth, signed. */
  private Handler truth() {
    return exchange -> {
      String json =
          kept.containsKey(APPLICATION)
              ? received(APPLICATION)
              : "{\"applicationId\":\"" + APPLICATION + "\",\"result\":\"NOT_RECEIVED\"}";
      answer(
          exchange,
          200,
          json,
          SIGNER.signInquiryAnswer(APPLICATION, json.getBytes(StandardCharsets.UTF_8)));
    };
  }

  private static Handler inquiryAnswer(int status, String json, String signature) {
    return exchange -> answer(exchange, status, json, signature);
  }

  // ---- the gateway under test --------------------------------------------------------------

  private HttpsSalaryAdvancePartnerGateway gateway() {
    Hooks hooks =
        new Hooks(
            host -> {
              if (answers == null) {
                throw new UnknownHostException(host);
              }
              return answers;
            },
            (address, dialPort, timeout) -> {
              // The pinned address is validated; the receiver itself listens on loopback.
              Socket socket = new Socket();
              socket.connect(
                  new InetSocketAddress(InetAddress.getLoopbackAddress(), dialPort), timeout);
              return socket;
            },
            Hooks.production().wire(),
            buffer -> {});
    return new HttpsSalaryAdvancePartnerGateway(
        hooks,
        new SalaryAdvancePartnerSecrets(SIGNER, null, "1"),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        trusting,
        NETWORKS);
  }

  private SalaryAdvancePartnerProfile profile() {
    return new SalaryAdvancePartnerProfile(
        SalaryAdvancePartnerProfile.SYNTHETIC_TEST, "receiver.test", port);
  }

  private Result send() {
    long timestamp = System.currentTimeMillis() / 1000;
    return gateway()
        .send(
            profile(),
            APPLICATION,
            "1",
            timestamp,
            SIGNER.signDelivery(timestamp, APPLICATION, BODY),
            BODY);
  }

  private InquiryResult inquire() {
    long timestamp = System.currentTimeMillis() / 1000;
    return gateway()
        .inquire(
            profile(),
            APPLICATION,
            "1",
            timestamp,
            SIGNER.signInquiry(timestamp, APPLICATION, BODY),
            BODY);
  }

  // ---- 429 and 5xx: unknown, never a retry -------------------------------------------------

  @Test
  void anUnsignedTooManyRequestsAnswerIsAnUnknownDeliveryNeverATransientOne() {
    delivery = refuseUnsigned(429);
    Result result = send();
    assertThat(result.outcome()).isEqualTo(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE);
    assertThat(result.reference()).isNull();
    assertThat(deliveries).as("the adapter itself never sends twice").hasValue(1);
  }

  @Test
  void everyUnsignedServerErrorAnswerIsAnUnknownDeliveryNeverATransientOne() {
    for (int status : new int[] {500, 502, 503, 504, 599}) {
      delivery = refuseUnsigned(status);
      assertThat(send().outcome())
          .as("HTTP %d", status)
          .isEqualTo(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE);
    }
    assertThat(deliveries).hasValue(5);
  }

  @Test
  void aBodyOnA429Or503DoesNotMakeItATransientFailureEvenWhenItLooksLikeAnAcknowledgement() {
    // A 5xx or 429 carrying an otherwise valid signed acknowledgement still decides nothing.
    String json = received(APPLICATION);
    String signature = SIGNER.signAck(APPLICATION, json.getBytes(StandardCharsets.UTF_8));
    delivery = exchange -> answer(exchange, 503, json, signature);
    assertThat(send().outcome()).isEqualTo(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE);
    delivery = exchange -> answer(exchange, 429, json, signature);
    assertThat(send().outcome()).isEqualTo(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE);
  }

  @Test
  void aReceiverThatStoredTheApplicationAndThenAnswered503IsUnknownUntilAnInquiryProvesIt() {
    delivery = storeThenFail(503);
    assertThat(send().outcome()).isEqualTo(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE);
    assertThat(kept).as("the receiver really holds it").containsKey(APPLICATION);
    assertThat(deliveries).hasValue(1);
    assertThat(inquiries).as("an inquiry is never implied by a delivery").hasValue(0);

    InquiryResult proof = inquire();
    assertThat(proof.outcome()).isEqualTo(InquiryOutcome.RECEIVED);
    assertThat(proof.reference()).isEqualTo(REFERENCE);
    assertThat(deliveries).as("an inquiry never delivers or redelivers anything").hasValue(1);
  }

  @Test
  void aSignedNotReceivedAnswerIsTheOnlyProofThatPermitsAResend() {
    delivery = refuseUnsigned(503);
    assertThat(send().outcome()).isEqualTo(SalaryAdvanceHandoffOutcome.UNKNOWN_BAD_RESPONSE);
    assertThat(kept).isEmpty();

    InquiryResult proof = inquire();
    assertThat(proof.outcome()).isEqualTo(InquiryOutcome.NOT_RECEIVED);
    assertThat(proof.reference()).isNull();

    // Requeued by the administrator's reconcile: the same application id is sent again and, with
    // the receiver healthy, acknowledged exactly once.
    delivery = acknowledge();
    Result resent = send();
    assertThat(resent.outcome()).isEqualTo(SalaryAdvanceHandoffOutcome.RECEIVED_ACKNOWLEDGED);
    assertThat(resent.reference()).isEqualTo(REFERENCE);
    assertThat(deliveries).hasValue(2);
    assertThat(kept).hasSize(1);
  }

  // ---- the inquiry: only a signed, application-bound answer proves anything ----------------

  @Test
  void anInquiryAnswerThatIsNotSignedBoundAndWellFormedProvesNothing() {
    String notReceived = "{\"applicationId\":\"" + APPLICATION + "\",\"result\":\"NOT_RECEIVED\"}";
    byte[] bytes = notReceived.getBytes(StandardCharsets.UTF_8);

    inquiry = inquiryAnswer(200, notReceived, null);
    assertThat(inquire().outcome()).as("unsigned").isEqualTo(InquiryOutcome.INCONCLUSIVE);

    inquiry = inquiryAnswer(200, notReceived, SIGNER.signAck(APPLICATION, bytes));
    assertThat(inquire().outcome())
        .as("signed as a delivery acknowledgement, not as an inquiry answer")
        .isEqualTo(InquiryOutcome.INCONCLUSIVE);

    inquiry = inquiryAnswer(200, notReceived, SIGNER.signInquiryAnswer(OTHER, bytes));
    assertThat(inquire().outcome())
        .as("signed for another application")
        .isEqualTo(InquiryOutcome.INCONCLUSIVE);

    String other = "{\"applicationId\":\"" + OTHER + "\",\"result\":\"NOT_RECEIVED\"}";
    inquiry =
        inquiryAnswer(
            200, other, SIGNER.signInquiryAnswer(OTHER, other.getBytes(StandardCharsets.UTF_8)));
    assertThat(inquire().outcome())
        .as("a validly signed answer about another application")
        .isEqualTo(InquiryOutcome.INCONCLUSIVE);

    String unknown = "{\"applicationId\":\"" + APPLICATION + "\",\"result\":\"UNKNOWN\"}";
    inquiry =
        inquiryAnswer(
            200,
            unknown,
            SIGNER.signInquiryAnswer(APPLICATION, unknown.getBytes(StandardCharsets.UTF_8)));
    assertThat(inquire().outcome()).as("UNKNOWN").isEqualTo(InquiryOutcome.INCONCLUSIVE);

    inquiry =
        inquiryAnswer(
            200,
            "not json",
            SIGNER.signInquiryAnswer(APPLICATION, "not json".getBytes(StandardCharsets.UTF_8)));
    assertThat(inquire().outcome()).as("malformed").isEqualTo(InquiryOutcome.INCONCLUSIVE);

    for (int status : new int[] {429, 503}) {
      inquiry = inquiryAnswer(status, notReceived, SIGNER.signInquiryAnswer(APPLICATION, bytes));
      assertThat(inquire().outcome())
          .as("a signed NOT_RECEIVED carried by HTTP %d", status)
          .isEqualTo(InquiryOutcome.INCONCLUSIVE);
    }

    // Finally the one valid answer.
    inquiry = inquiryAnswer(200, notReceived, SIGNER.signInquiryAnswer(APPLICATION, bytes));
    assertThat(inquire().outcome()).isEqualTo(InquiryOutcome.NOT_RECEIVED);
  }

  @Test
  void anInquiryThatCannotBeOpenedIsInconclusiveNeverNotReceived() {
    answers = null;
    assertThat(inquire().outcome()).isEqualTo(InquiryOutcome.INCONCLUSIVE);
    answers = new InetAddress[] {address(8, 8, 8, 8)};
    assertThat(inquire().outcome()).isEqualTo(InquiryOutcome.INCONCLUSIVE);
    assertThat(inquiries).hasValue(0);
  }

  // ---- only a failure before any byte was written is transient ----------------------------

  @Test
  void aFailureBeforeAnyByteWasWrittenIsTheOnlyTransientNotSent() {
    answers = null; // DNS does not resolve
    assertThat(send().outcome()).isEqualTo(SalaryAdvanceHandoffOutcome.TRANSIENT_NOT_SENT);
    answers = new InetAddress[] {address(8, 8, 8, 8)}; // outside the configured private networks
    assertThat(send().outcome()).isEqualTo(SalaryAdvanceHandoffOutcome.TRANSIENT_NOT_SENT);
    assertThat(deliveries).as("nothing was ever written").hasValue(0);
  }

  @Test
  void anAuthenticatedAcknowledgementAndAnAuthenticatedRejectionStillDecide() {
    assertThat(send().outcome()).isEqualTo(SalaryAdvanceHandoffOutcome.RECEIVED_ACKNOWLEDGED);
    String rejected =
        "{\"applicationId\":\""
            + APPLICATION
            + "\",\"result\":\"REJECTED\",\"code\":\"UNSUPPORTED\"}";
    delivery =
        exchange ->
            answer(
                exchange,
                422,
                rejected,
                SIGNER.signAck(APPLICATION, rejected.getBytes(StandardCharsets.UTF_8)));
    assertThat(send().outcome()).isEqualTo(SalaryAdvanceHandoffOutcome.REJECTED_BY_PARTNER);
  }
}
