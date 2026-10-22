package com.divalhr.core.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.platform.ai.OperationalSummaryContract.Fact;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Request;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Response;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Shifts;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Waiting;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVP-101 (ADR 0027, D101-5, D101-8): the Core side of the AI service boundary against a real local
 * HTTP server: client-credentials token acquisition and caching, exactly the closed request body,
 * strict parsing of the answer, one bounded attempt, and a bounded failure reason for every fault
 * without a secret, a token or a body in it. No Spring context.
 */
class AiServiceBoundaryTest {

  private static final String SECRET = "unit-test-client-secret-0123456789";
  private static final String BEARER = "header.payload.signature-test-only";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private HttpServer server;
  private final AtomicInteger tokenCalls = new AtomicInteger();
  private final AtomicInteger summaryCalls = new AtomicInteger();
  private final List<String> tokenForms = new CopyOnWriteArrayList<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private volatile Consumer<HttpExchange> summary;
  private volatile Consumer<HttpExchange> token;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    token =
        exchange ->
            reply(exchange, 200, "{\"access_token\":\"" + BEARER + "\",\"expires_in\":300}");
    summary = exchange -> reply(exchange, 200, ok());
    server.createContext(
        "/token",
        exchange -> {
          tokenCalls.incrementAndGet();
          tokenForms.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          token.accept(exchange);
        });
    server.createContext(
        "/api/v1/internal/operational-summary",
        exchange -> {
          summaryCalls.incrementAndGet();
          authorizations.add(
              String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          summary.accept(exchange);
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static void reply(HttpExchange exchange, int status, String body) {
    try {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    } catch (IOException io) {
      throw new IllegalStateException(io);
    } finally {
      exchange.close();
    }
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private AiServiceProperties properties(Duration read) {
    return new AiServiceProperties(
        url(), url() + "/token", "divalhr-core-ai", SECRET, Duration.ofSeconds(1), read);
  }

  private AiServiceOperationalSummaryClient client(Duration read) {
    return new AiServiceOperationalSummaryClient(
        properties(read), new ServiceTokenProvider(properties(read), "test"));
  }

  private static Request request() {
    return new Request(
        "en",
        "2026-10-21T10:00:00.123456Z",
        "ORGANIZATION",
        true,
        false,
        new Shifts(4, 1, 1, 1, 1, 1),
        2,
        new Waiting(3, 1),
        new Waiting(0, 0),
        List.of(
            new Fact("f2", "NOT_CHECKED_IN", 1, 30, "LOW", "ATTENDANCE"),
            new Fact("f1", "SCHEDULED_SHIFTS", 4, null, "NONE", "SCHEDULES")));
  }

  private static String ok() {
    return """
    {"language":"en","generationMode":"MODEL","templateVersion":"ops-summary-1",
     "headline":{"code":"ATTENDANCE_NEEDS_ATTENTION","text":"Attendance records need attention."},
     "highlights":[{"factId":"f2","factType":"NOT_CHECKED_IN","severity":"LOW","routeId":"ATTENDANCE",
                    "text":"1 shift has no check-in recorded more than 30 minutes after its start."}],
     "limitations":[{"code":"RECORDED_FACTS_ONLY","text":"a"},{"code":"NO_CONCLUSION","text":"b"},
                    {"code":"REVIEW_SOURCES","text":"c"}]}
    """;
  }

  private String failureOf(AiServiceOperationalSummaryClient client) {
    AiServiceUnavailableException failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            AiServiceUnavailableException.class, () -> client.generate(request()));
    assertThat(failure.getMessage()).doesNotContain(SECRET).doesNotContain(BEARER);
    assertThat(failure.reason()).matches("[a-z_]{3,32}");
    return failure.reason();
  }

  @Test
  void aSuccessfulCallSendsTheServiceTokenAndExactlyTheClosedBody() throws Exception {
    Response response = client(Duration.ofSeconds(2)).generate(request());

    assertThat(response.generationMode()).isEqualTo("MODEL");
    assertThat(response.highlights()).hasSize(1);
    assertThat(response.limitations()).hasSize(3);
    assertThat(tokenForms.get(0))
        .contains("grant_type=client_credentials")
        .contains("client_id=divalhr-core-ai")
        .contains("client_secret=" + SECRET);
    assertThat(authorizations).containsExactly("Bearer " + BEARER);

    JsonNode body = JSON.readTree(bodies.get(0));
    assertThat(keys(body))
        .containsExactly(
            "approvals",
            "asOfInstant",
            "coverage",
            "facts",
            "language",
            "schemaVersion",
            "scope",
            "shifts",
            "thresholds");
    assertThat(keys(body.get("scope"))).containsExactly("kind", "legalEntityFilter", "siteFilter");
    assertThat(keys(body.get("thresholds")))
        .containsExactly(
            "lateMinutes",
            "leaveOverdueHours",
            "notCheckedInGraceMinutes",
            "timesheetOverdueHours");
    assertThat(keys(body.get("facts").get(0)))
        .containsExactly("factId", "routeId", "severity", "thresholdValue", "type", "value");
    assertThat(body.get("facts").get(1).get("thresholdValue").isNull()).isTrue();
    assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
    // Nothing that could identify a person, a place or a request crosses the boundary.
    assertThat(bodies.get(0))
        .doesNotContain("tenant")
        .doesNotContain("subject")
        .doesNotContain("http");
  }

  private static Set<String> keys(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.propertyNames().forEach(names::add);
    return names;
  }

  @Test
  void aTeamSnapshotSendsAnExplicitNullCoverage() throws Exception {
    Request team =
        new Request(
            "fr",
            "2026-10-21T10:00:00Z",
            "TEAM",
            false,
            false,
            new Shifts(1, 0, 0, 1, 0, 0),
            null,
            new Waiting(0, 0),
            new Waiting(0, 0),
            List.of(new Fact("f1", "SCHEDULED_SHIFTS", 1, null, "NONE", "SCHEDULES")));
    client(Duration.ofSeconds(2)).generate(team);
    JsonNode body = JSON.readTree(bodies.get(0));
    assertThat(body.has("coverage")).isTrue();
    assertThat(body.get("coverage").isNull()).isTrue();
  }

  @Test
  void theTokenIsCachedAndRefetchedOnlyAfterTheServiceRefusesIt() {
    AiServiceOperationalSummaryClient client = client(Duration.ofSeconds(2));
    client.generate(request());
    client.generate(request());
    assertThat(tokenCalls.get()).isEqualTo(1);

    summary = exchange -> reply(exchange, 401, "{\"code\":\"SERVICE_TOKEN_INVALID\"}");
    assertThat(failureOf(client)).isEqualTo("unauthorized");
    summary = exchange -> reply(exchange, 200, ok());
    client.generate(request());
    assertThat(tokenCalls.get()).isEqualTo(2);
  }

  @Test
  void everyFaultIsABoundedReasonAndIsNeverRetried() {
    AiServiceOperationalSummaryClient client = client(Duration.ofSeconds(2));
    List<String> reasons = new ArrayList<>();
    for (int status : new int[] {400, 403, 422, 500, 503, 404}) {
      summaryCalls.set(0);
      summary =
          exchange -> reply(exchange, status, "{\"code\":\"X\",\"detail\":\"" + SECRET + "\"}");
      reasons.add(failureOf(client));
      assertThat(summaryCalls.get()).as("attempts for %s", status).isEqualTo(1);
    }
    assertThat(reasons)
        .containsExactly(
            "request_rejected",
            "forbidden",
            "snapshot_rejected",
            "service_error",
            "service_error",
            "unexpected_status");
  }

  @Test
  void aSlowAnswerIsATimeoutNotAWait() {
    summary =
        exchange -> {
          try {
            Thread.sleep(1_500);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          reply(exchange, 200, ok());
        };
    long started = System.nanoTime();
    assertThat(failureOf(client(Duration.ofMillis(300)))).isEqualTo("timeout");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(summaryCalls.get()).isEqualTo(1);
  }

  @Test
  void anUnreachableServiceIsATransportFailure() {
    AiServiceOperationalSummaryClient client = client(Duration.ofSeconds(1));
    server.stop(0);
    assertThat(failureOf(client)).isIn("transport", "token_unreachable");
  }

  @Test
  void theAnswerIsParsedStrictly() {
    String[] bad = {
      "not json",
      "[]",
      "{}",
      ok().replace("\"language\":\"en\",", "\"language\":\"en\",\"model\":\"x\","),
      ok().replace("\"templateVersion\":\"ops-summary-1\",", ""),
      ok().replace("\"text\":\"Attendance records need attention.\"", "\"text\":5"),
      ok().replace("\"highlights\":[", "\"highlights\":[{\"factId\":\"f1\"},"),
      ok().replace("\"routeId\":\"ATTENDANCE\",", "\"routeId\":\"ATTENDANCE\",\"url\":\"x\","),
      ok().replace("\"headline\":{", "\"headline\":{\"extra\":1,"),
      ok().replace("\"limitations\":[", "\"limitations\":{\"x\":1},\"y\":["),
    };
    AiServiceOperationalSummaryClient client = client(Duration.ofSeconds(2));
    for (String answer : bad) {
      summary = exchange -> reply(exchange, 200, answer);
      assertThat(failureOf(client)).isEqualTo("invalid_response");
    }
  }

  @Test
  void aTokenEndpointFaultIsBoundedAndNoRequestIsSent() {
    for (String body : new String[] {"{}", "not json", "{\"access_token\":\"\"}"}) {
      token = exchange -> reply(exchange, 200, body);
      assertThat(failureOf(client(Duration.ofSeconds(2)))).startsWith("token_");
    }
    token = exchange -> reply(exchange, 401, "{\"error\":\"unauthorized_client\"}");
    assertThat(failureOf(client(Duration.ofSeconds(2)))).isEqualTo("token_rejected");
    assertThat(summaryCalls.get()).isZero();
  }

  @Test
  void anUnconfiguredCapabilityNeverLeavesCore() {
    AiServiceProperties none = new AiServiceProperties(null, null, null, null, null, null);
    assertThat(none.isConfigured()).isFalse();
    ServiceTokenProvider tokens = new ServiceTokenProvider(none, "production");
    assertThat(tokens.available()).isFalse();
    AiServiceOperationalSummaryClient client = new AiServiceOperationalSummaryClient(none, tokens);
    assertThat(failureOf(client)).isEqualTo("not_configured");
    assertThat(tokenCalls.get()).isZero();
    assertThat(summaryCalls.get()).isZero();
  }

  @Test
  void configurationFailsClosedAndNeverPrintsTheSecret() {
    String https = "https://ai.internal.example";
    String token = "https://id.example/realms/r/protocol/openid-connect/token";
    new AiServiceProperties(https, token, "c", SECRET, null, null).validate("production");
    List<AiServiceProperties> bad =
        List.of(
            new AiServiceProperties("http://ai.internal.example", token, "c", SECRET, null, null),
            new AiServiceProperties(https, "http://id.example/token", "c", SECRET, null, null),
            new AiServiceProperties(https, token, "c", "short", null, null),
            new AiServiceProperties(https, token, "c", null, null, null),
            new AiServiceProperties(null, token, "c", SECRET, null, null),
            new AiServiceProperties(https, null, "c", SECRET, null, null),
            new AiServiceProperties("not a url", token, "c", SECRET, null, null),
            new AiServiceProperties(https, token, "c", "dev-only-0123456789abcdef", null, null),
            new AiServiceProperties(https, token, "c", SECRET, Duration.ZERO, null));
    for (AiServiceProperties properties : bad) {
      assertThatThrownBy(() -> properties.validate("production"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining(SECRET);
    }
    // Development may use plain HTTP and the development secret; test too, except the secret.
    new AiServiceProperties(
            "http://localhost:8000",
            "http://localhost:8180/t",
            "c",
            "dev-only-0123456789abcdef",
            null,
            null)
        .validate("development");
    assertThatThrownBy(
            () ->
                new AiServiceProperties(
                        "http://localhost:8000",
                        "http://localhost:8180/t",
                        "c",
                        "dev-only-0123456789abcdef",
                        null,
                        null)
                    .validate("test"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(new AiServiceProperties(https, token, "c", SECRET, null, null).toString())
        .doesNotContain(SECRET);
    // A configured but invalid capability stops the application from starting.
    assertThatThrownBy(
            () ->
                new ServiceTokenProvider(
                    new AiServiceProperties(https, null, "c", SECRET, null, null), "production"))
        .isInstanceOf(IllegalStateException.class);
  }
}
