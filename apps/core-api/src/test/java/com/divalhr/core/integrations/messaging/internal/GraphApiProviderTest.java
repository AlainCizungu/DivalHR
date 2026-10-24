package com.divalhr.core.integrations.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.integrations.messaging.application.MessagingProperties;
import com.divalhr.core.integrations.messaging.application.MessagingProperties.TestProvider;
import com.divalhr.core.integrations.messaging.application.MessagingProvider.CredentialResult;
import com.divalhr.core.integrations.messaging.application.MessagingProvider.SendOutcome;
import com.divalhr.core.integrations.messaging.application.MessagingProvider.SendRequest;
import com.divalhr.core.integrations.messaging.application.MessagingProvider.TemplateCheck;
import com.divalhr.core.integrations.messaging.application.MessagingProvider.TemplateResult;
import com.divalhr.core.integrations.messaging.domain.MessagingLanguage;
import com.divalhr.core.integrations.messaging.domain.PhoneNumber;
import com.divalhr.core.integrations.messaging.domain.SendFailure;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVP-111 (D111-8): the Graph client against a local stub: error classification, no redirect, a
 * bounded response, the token only in {@code Authorization}, and the closed template shape.
 */
class GraphApiProviderTest {

  private static final String TOKEN = "EAAGsyntheticTOKEN0123456789abcdef";
  private static final String BASE = "https://hr.example.com";
  private static final String BUTTON = BASE + "/{{1}}";

  private record Seen(String method, String uri, Map<String, List<String>> headers, String body) {}

  private HttpServer server;
  private final List<Seen> seen = new CopyOnWriteArrayList<>();
  private volatile Consumer<com.sun.net.httpserver.HttpExchange> script;
  private GraphApiProvider provider;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          seen.add(
              new Seen(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().toString(),
                  Map.copyOf(exchange.getRequestHeaders()),
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          script.accept(exchange);
          exchange.close();
        });
    server.start();
    var props =
        new MessagingProperties(
            "x".repeat(40),
            null,
            null,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "v22.0",
            BASE,
            false,
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            new TestProvider(null, null, null),
            null,
            null);
    provider =
        new GraphApiProvider(
            props,
            JsonMapper.builder().build(),
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void reply(int status, String body, String... headers) {
    script =
        exchange -> {
          try {
            for (int i = 0; i < headers.length; i += 2) {
              exchange.getResponseHeaders().add(headers[i], headers[i + 1]);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
              exchange.getResponseBody().write(bytes);
            }
          } catch (IOException ignored) {
            // the client may have stopped reading
          }
        };
  }

  private static SendRequest request(String label) {
    return new SendRequest(
        TOKEN,
        "123456789012",
        PhoneNumber.parse("+243812345678").orElseThrow(),
        "divalhr_leave_decision",
        MessagingLanguage.FR,
        label,
        "admin/leave-approvals");
  }

  // ------------------------------------------------------------------------------------------
  // classify
  // ------------------------------------------------------------------------------------------

  @Test
  void classificationIsClosedAndTreatsDocumentedThrottleCodesAsRetryable() {
    assertThat(GraphApiProvider.classify(429, null)).isEqualTo(SendFailure.RATE_LIMITED);
    for (int code : new int[] {4, 80007, 130429, 131056}) {
      assertThat(GraphApiProvider.classify(400, code))
          .as("throttle " + code)
          .isEqualTo(SendFailure.RATE_LIMITED);
    }
    assertThat(GraphApiProvider.classify(500, null)).isEqualTo(SendFailure.PROVIDER_FAILED);
    assertThat(GraphApiProvider.classify(503, 1)).isEqualTo(SendFailure.PROVIDER_FAILED);
    assertThat(GraphApiProvider.classify(401, null)).isEqualTo(SendFailure.CREDENTIAL_INVALID);
    for (int code : new int[] {190, 10, 200, 368}) {
      assertThat(GraphApiProvider.classify(400, code))
          .as("credential " + code)
          .isEqualTo(SendFailure.CREDENTIAL_INVALID);
    }
    assertThat(GraphApiProvider.classify(400, 132000)).isEqualTo(SendFailure.TEMPLATE_INVALID);
    assertThat(GraphApiProvider.classify(400, 132999)).isEqualTo(SendFailure.TEMPLATE_INVALID);
    assertThat(GraphApiProvider.classify(400, 131026)).isEqualTo(SendFailure.RECIPIENT_REJECTED);
    assertThat(GraphApiProvider.classify(400, 133000)).isEqualTo(SendFailure.HTTP_RESPONSE);
    assertThat(GraphApiProvider.classify(404, null)).isEqualTo(SendFailure.HTTP_RESPONSE);
    // A throttle code outranks a 5xx class only through the documented 429/code rule.
    assertThat(GraphApiProvider.classify(500, 4)).isEqualTo(SendFailure.RATE_LIMITED);
  }

  // ------------------------------------------------------------------------------------------
  // judge
  // ------------------------------------------------------------------------------------------

  private static Map<String, Object> template(
      String name, String language, String status, List<Object> components) {
    return Map.of("name", name, "language", language, "status", status, "components", components);
  }

  private static Map<String, Object> body(String text) {
    return Map.of("type", "BODY", "text", text);
  }

  private static Map<String, Object> button(String url) {
    return Map.of(
        "type", "BUTTONS", "buttons", List.of(Map.of("type", "URL", "text", "Open", "url", url)));
  }

  @Test
  void judgeAcceptsTheExactSupportedShapeWithAndWithoutABodyVariable() {
    var plain =
        List.<Object>of(
            template("t", "fr", "APPROVED", List.of(body("Mise à jour"), button(BUTTON))));
    TemplateCheck zero = GraphApiProvider.judge(plain, "t", MessagingLanguage.FR, BUTTON);
    assertThat(zero.result()).isEqualTo(TemplateResult.OK);
    assertThat(zero.bodyParameters()).isZero();
    var one =
        List.<Object>of(
            template("t", "fr", "APPROVED", List.of(body("Mise à jour : {{1}}"), button(BUTTON))));
    TemplateCheck withVariable = GraphApiProvider.judge(one, "t", MessagingLanguage.FR, BUTTON);
    assertThat(withVariable.result()).isEqualTo(TemplateResult.OK);
    assertThat(withVariable.bodyParameters()).isEqualTo(1);
    var footer =
        List.<Object>of(
            template(
                "t",
                "fr",
                "APPROVED",
                List.of(body("x"), Map.of("type", "FOOTER", "text", "DivalHR"), button(BUTTON))));
    assertThat(GraphApiProvider.judge(footer, "t", MessagingLanguage.FR, BUTTON).result())
        .isEqualTo(TemplateResult.OK);
  }

  @Test
  void judgeNamesEachWayATemplateCanBeWrong() {
    var data = List.<Object>of(template("t", "en", "APPROVED", List.of(body("x"), button(BUTTON))));
    assertThat(GraphApiProvider.judge(data, "missing", MessagingLanguage.EN, BUTTON).result())
        .isEqualTo(TemplateResult.MISSING);
    assertThat(GraphApiProvider.judge(data, "t", MessagingLanguage.FR, BUTTON).result())
        .isEqualTo(TemplateResult.WRONG_LANGUAGE);
    var pending =
        List.<Object>of(template("t", "en", "PENDING", List.of(body("x"), button(BUTTON))));
    assertThat(GraphApiProvider.judge(pending, "t", MessagingLanguage.EN, BUTTON).result())
        .isEqualTo(TemplateResult.NOT_APPROVED);
    assertThat(GraphApiProvider.judge(List.of(), "t", MessagingLanguage.EN, BUTTON).result())
        .isEqualTo(TemplateResult.MISSING);
  }

  @Test
  void judgeRefusesAnyShapeThatCouldCarryMoreThanTheGenericText() {
    for (List<Object> components :
        List.<List<Object>>of(
            // a header (media or variables)
            List.of(Map.of("type", "HEADER", "format", "IMAGE"), body("x"), button(BUTTON)),
            // a second variable, or a second use of the first
            List.of(body("a {{1}} b {{2}}"), button(BUTTON)),
            List.of(body("a {{1}} b {{1}}"), button(BUTTON)),
            // a button pointing elsewhere or with another type
            List.of(body("x"), button("https://evil.example.com/{{1}}")),
            List.of(body("x"), button(BASE + "/static")),
            List.of(
                body("x"),
                Map.of(
                    "type",
                    "BUTTONS",
                    "buttons",
                    List.of(Map.of("type", "PHONE_NUMBER", "phone_number", "+1")))),
            // two buttons
            List.of(
                body("x"),
                Map.of(
                    "type",
                    "BUTTONS",
                    "buttons",
                    List.of(
                        Map.of("type", "URL", "url", BUTTON),
                        Map.of("type", "URL", "url", BUTTON)))),
            // no button, no body, unknown component
            List.of(body("x")),
            List.of(button(BUTTON)),
            List.of(body("x"), Map.of("type", "CAROUSEL"), button(BUTTON)),
            // a variable in the footer
            List.of(body("x"), Map.of("type", "FOOTER", "text", "{{1}}"), button(BUTTON)))) {
      var data = List.<Object>of(template("t", "en", "APPROVED", components));
      assertThat(GraphApiProvider.judge(data, "t", MessagingLanguage.EN, BUTTON).result())
          .as(components.toString())
          .isEqualTo(TemplateResult.WRONG_SHAPE);
    }
  }

  // ------------------------------------------------------------------------------------------
  // HTTP behaviour
  // ------------------------------------------------------------------------------------------

  @Test
  void theTokenTravelsOnlyInTheAuthorizationHeaderNeverInUrlOrBody() {
    reply(200, "{\"messages\":[{\"id\":\"wamid.HBgLMjQzODEyMzQ1Njc4FQIAERgSQURGRTdD\"}]}");
    SendOutcome outcome = provider.send(request("Décision sur un congé"));
    assertThat(outcome.accepted()).isTrue();
    assertThat(outcome.providerMessageId()).startsWith("wamid.");
    Seen sent = seen.get(0);
    assertThat(sent.method()).isEqualTo("POST");
    assertThat(sent.uri()).isEqualTo("/v22.0/123456789012/messages");
    assertThat(sent.headers().get("Authorization")).containsExactly("Bearer " + TOKEN);
    assertThat(sent.uri()).doesNotContain(TOKEN);
    assertThat(sent.body()).doesNotContain(TOKEN);
  }

  @Test
  void theSentPayloadIsAGenericTemplateWithOnlyTheLabelAndTheButtonSuffix() throws Exception {
    reply(200, "{\"messages\":[{\"id\":\"wamid.HBgLMjQzODEyMzQ1Njc4FQIAERgSQURGRTdD\"}]}");
    provider.send(request("Décision sur un congé"));
    Object root = JsonMapper.builder().build().readValue(seen.get(0).body(), Object.class);
    assertThat(root).isInstanceOf(Map.class);
    Map<?, ?> top = (Map<?, ?>) root;
    assertThat(new ArrayList<Object>(top.keySet()))
        .containsExactlyInAnyOrder("messaging_product", "to", "type", "template");
    assertThat(top.get("to")).isEqualTo("243812345678");
    assertThat(top.get("type")).isEqualTo("template");
    Map<?, ?> template = (Map<?, ?>) top.get("template");
    assertThat(template.get("name")).isEqualTo("divalhr_leave_decision");
    assertThat(((Map<?, ?>) template.get("language")).get("code")).isEqualTo("fr");
    List<?> components = (List<?>) template.get("components");
    assertThat(components).hasSize(2);
    assertThat(seen.get(0).body())
        .contains("Décision sur un congé")
        .contains("admin/leave-approvals");
    assertThat(seen.get(0).body()).doesNotContain("http").doesNotContain("@");
    // Without a body variable only the button is sent.
    seen.clear();
    provider.send(request(null));
    Map<?, ?> without =
        (Map<?, ?>) JsonMapper.builder().build().readValue(seen.get(0).body(), Object.class);
    assertThat((List<?>) ((Map<?, ?>) without.get("template")).get("components")).hasSize(1);
  }

  @Test
  void sendClassifiesEveryAnswerIntoAClosedClass() {
    reply(429, "{}", "Retry-After", "120");
    SendOutcome throttled = provider.send(request(null));
    assertThat(throttled.failure()).isEqualTo(SendFailure.RATE_LIMITED);
    assertThat(throttled.retryAfter()).isEqualTo(Duration.ofSeconds(120));
    assertThat(throttled.httpStatus()).isEqualTo(429);

    reply(400, "{\"error\":{\"code\":130429,\"message\":\"ignored text\"}}");
    assertThat(provider.send(request(null)).failure()).isEqualTo(SendFailure.RATE_LIMITED);

    reply(503, "oops");
    assertThat(provider.send(request(null)).failure()).isEqualTo(SendFailure.PROVIDER_FAILED);

    reply(401, "{\"error\":{\"code\":190}}");
    assertThat(provider.send(request(null)).failure()).isEqualTo(SendFailure.CREDENTIAL_INVALID);

    reply(400, "{\"error\":{\"code\":132001}}");
    assertThat(provider.send(request(null)).failure()).isEqualTo(SendFailure.TEMPLATE_INVALID);

    reply(400, "{\"error\":{\"code\":131026}}");
    assertThat(provider.send(request(null)).failure()).isEqualTo(SendFailure.RECIPIENT_REJECTED);

    reply(404, "{}");
    assertThat(provider.send(request(null)).failure()).isEqualTo(SendFailure.HTTP_RESPONSE);
  }

  @Test
  void aRetryAfterIsBoundedAndNonNumericHintsAreIgnored() {
    reply(429, "{}", "Retry-After", "999999");
    assertThat(provider.send(request(null)).retryAfter()).isEqualTo(Duration.ofHours(2));
    reply(429, "{}", "Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT");
    assertThat(provider.send(request(null)).retryAfter()).isNull();
  }

  @Test
  void anAcceptedAnswerWithoutAUsableMessageIdIsAProviderFailure() {
    reply(200, "{}");
    assertThat(provider.send(request(null)).failure()).isEqualTo(SendFailure.PROVIDER_FAILED);
    reply(200, "{\"messages\":[{\"id\":\"x\"}]}");
    assertThat(provider.send(request(null)).failure()).isEqualTo(SendFailure.PROVIDER_FAILED);
    reply(200, "{\"messages\":[{\"id\":\"has space and <html>\"}]}");
    assertThat(provider.send(request(null)).failure()).isEqualTo(SendFailure.PROVIDER_FAILED);
    reply(200, "not json at all");
    assertThat(provider.send(request(null)).failure()).isEqualTo(SendFailure.PROVIDER_FAILED);
  }

  @Test
  void aRedirectIsNeverFollowed() {
    AtomicInteger elsewhere = new AtomicInteger();
    server.createContext(
        "/elsewhere",
        exchange -> {
          elsewhere.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    reply(302, "", "Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere");
    SendOutcome outcome = provider.send(request(null));
    assertThat(outcome.accepted()).isFalse();
    assertThat(outcome.httpStatus()).isEqualTo(302);
    assertThat(outcome.failure()).isEqualTo(SendFailure.HTTP_RESPONSE);
    assertThat(elsewhere).hasValue(0);
    assertThat(seen).hasSize(1);
  }

  @Test
  void anOversizedResponseIsRefusedWithoutBeingParsed() {
    String huge =
        "{\"messages\":[{\"id\":\"wamid.HBgLMjQzODEyMzQ1Njc4FQIAERgSQURGRTdD\"}],\"pad\":\""
            + "x".repeat(GraphApiProvider.MAX_RESPONSE_BYTES)
            + "\"}";
    reply(200, huge);
    SendOutcome outcome = provider.send(request(null));
    assertThat(outcome.accepted()).isFalse();
    assertThat(outcome.failure()).isEqualTo(SendFailure.PROVIDER_FAILED);
  }

  @Test
  void aConnectionThatCannotBeOpenedIsAClassNotAnException() {
    server.stop(0);
    SendOutcome outcome = provider.send(request(null));
    assertThat(outcome.accepted()).isFalse();
    assertThat(outcome.failure()).isIn(SendFailure.CONNECTION_FAILED, SendFailure.CONNECT_TIMEOUT);
    assertThat(outcome.failure().retryable()).isTrue();
  }

  @Test
  void aMalformedPhoneNumberIdNeverReachesTheNetwork() {
    SendRequest bad =
        new SendRequest(
            TOKEN,
            "12/../x",
            PhoneNumber.parse("+243812345678").orElseThrow(),
            "t",
            MessagingLanguage.EN,
            null,
            "a");
    assertThat(provider.send(bad).failure()).isEqualTo(SendFailure.CREDENTIAL_INVALID);
    assertThat(provider.checkCredential(TOKEN, "12/../x")).isEqualTo(CredentialResult.REJECTED);
    assertThat(provider.checkTemplate(TOKEN, "12/../x", "t", MessagingLanguage.EN, BUTTON).result())
        .isEqualTo(TemplateResult.CREDENTIAL_REJECTED);
    assertThat(seen).isEmpty();
  }

  @Test
  void checkCredentialDistinguishesValidRejectedAndUnavailable() {
    reply(200, "{\"id\":\"123456789012\"}");
    assertThat(provider.checkCredential(TOKEN, "123456789012")).isEqualTo(CredentialResult.VALID);
    assertThat(seen.get(0).uri()).isEqualTo("/v22.0/123456789012?fields=id");
    assertThat(seen.get(0).uri()).doesNotContain(TOKEN);
    reply(200, "{\"id\":\"999999999999\"}");
    assertThat(provider.checkCredential(TOKEN, "123456789012"))
        .isEqualTo(CredentialResult.UNAVAILABLE);
    reply(400, "{\"error\":{\"code\":190}}");
    assertThat(provider.checkCredential(TOKEN, "123456789012"))
        .isEqualTo(CredentialResult.REJECTED);
    reply(500, "{}");
    assertThat(provider.checkCredential(TOKEN, "123456789012"))
        .isEqualTo(CredentialResult.UNAVAILABLE);
    reply(429, "{}");
    assertThat(provider.checkCredential(TOKEN, "123456789012"))
        .isEqualTo(CredentialResult.UNAVAILABLE);
  }

  @Test
  void checkTemplateEncodesTheNameAndJudgesTheList() {
    reply(
        200,
        "{\"data\":[{\"name\":\"divalhr_leave_decision\",\"language\":\"fr\",\"status\":\"APPROVED\",\"components\":[{\"type\":\"BODY\",\"text\":\"Mise"
            + " à jour\"},{\"type\":\"BUTTONS\","
            + "\"buttons\":[{\"type\":\"URL\",\"text\":\"Open\",\"url\":\""
            + BUTTON
            + "\"}]}]}]}");
    TemplateCheck check =
        provider.checkTemplate(
            TOKEN, "123456789012", "divalhr_leave_decision", MessagingLanguage.FR, BUTTON);
    assertThat(check.result()).isEqualTo(TemplateResult.OK);
    assertThat(seen.get(0).uri()).startsWith("/v22.0/123456789012/message_templates?");
    assertThat(seen.get(0).uri()).contains("name=divalhr_leave_decision").doesNotContain(TOKEN);
    reply(401, "{}");
    assertThat(
            provider
                .checkTemplate(TOKEN, "123456789012", "t", MessagingLanguage.EN, BUTTON)
                .result())
        .isEqualTo(TemplateResult.CREDENTIAL_REJECTED);
    reply(503, "{}");
    assertThat(
            provider
                .checkTemplate(TOKEN, "123456789012", "t", MessagingLanguage.EN, BUTTON)
                .result())
        .isEqualTo(TemplateResult.UNAVAILABLE);
  }

  @Test
  void sendRequestNeverPrintsItsToken() {
    assertThat(request("x").toString()).doesNotContain(TOKEN).doesNotContain("243812345678");
  }

  @Test
  void theClientNeverLogsOrStoresAResponseBody() throws Exception {
    String source =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/main/java/com/divalhr/core/integrations/messaging/internal/GraphApiProvider.java"));
    assertThat(source)
        .doesNotContain("LOG.")
        .doesNotContain("LoggerFactory")
        .doesNotContain("System.out");
    assertThat(new ArrayList<>(List.of(source))).isNotEmpty();
  }
}
