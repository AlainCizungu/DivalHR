package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.integrations.application.WebhookJobs;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-110 (D110-1..D110-16): authorization and tenant isolation, the resource and its commands, the
 * secret lifecycle, idempotency without secret replay, the 20-webhook cap, the test-delivery
 * limits, fan-out, the retry schedule, auto-suspension, cancellation and what is stored.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@Import({WebhookTestConfig.class, SettableClock.Config.class})
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.webhook-write=600",
      "divalhr.request-limits.subject.webhook-read=600",
      "divalhr.integrations.jitter=false"
    })
class WebhookApiIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PATH = "/api/v1/integrations/webhooks";
  private static final String NO_STORE = "private, no-store";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private WebhookJobs jobs;
  @Autowired private SettableClock clock;
  @Autowired private WebhookTestConfig.ScriptedTransport transport;

  private UUID tenant;
  private UUID otherTenant;
  private String admin;
  private String otherAdmin;

  @BeforeEach
  void organizations() throws Exception {
    clock.reset();
    transport.reset();
    tenant = Hierarchy.newTenant(mvc);
    otherTenant = Hierarchy.newTenant(mvc);
    admin = Hierarchy.bearer(tenant, "sub-hook-" + UUID.randomUUID(), "tenant-admin");
    otherAdmin = Hierarchy.bearer(otherTenant, "sub-hook-" + UUID.randomUUID(), "tenant-admin");
  }

  @AfterEach
  void restoreClock() {
    clock.reset();
  }

  // ------------------------------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------------------------------

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static String createBody(String name, String url, String... events) {
    String list = events.length == 0 ? "\"leave.request.approved.v1\"" : quoted(events);
    return "{\"name\":\""
        + name
        + "\",\"url\":\""
        + url
        + "\",\"eventTypes\":["
        + list
        + "],\"confirm\":true}";
  }

  private static String quoted(String... values) {
    return String.join(",", java.util.Arrays.stream(values).map(v -> "\"" + v + "\"").toList());
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String bearer, String idempotencyKey, String body) {
    MockHttpServletRequestBuilder built =
        request
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    return idempotencyKey == null ? built : built.header("Idempotency-Key", idempotencyKey);
  }

  private JsonNode create(String bearer, String name, String url) throws Exception {
    String body =
        mvc.perform(json(post(PATH), bearer, key(), createBody(name, url)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body);
  }

  private JsonNode create(String bearer, String name) throws Exception {
    return create(bearer, name, "https://hooks.example.com/" + UUID.randomUUID());
  }

  private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
    return JSON.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
  }

  private JsonNode detail(String bearer, String id) throws Exception {
    return read(get(PATH + "/" + id).header("Authorization", bearer)).get("webhook");
  }

  private List<WebhookTestConfig.Sent> sentTo(String webhookId) throws Exception {
    String url = detail(admin, webhookId).get("url").asText();
    return transport.sent().stream().filter(s -> s.url().equals(url)).toList();
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private void publish(UUID owner, String internalType, UUID subject) {
    UUID eventId = UUID.randomUUID();
    String time = Instant.now().toString();
    String envelope =
        "{\"eventId\":\""
            + eventId
            + "\",\"eventType\":\""
            + internalType
            + "\",\"schemaVersion\":1,\"tenantId\":\""
            + owner
            + "\",\"source\":\"test\",\"subject\":\""
            + subject
            + "\",\"eventTime\":\""
            + time.replaceAll("\\.\\d+Z$", "Z")
            + "\",\"correlationId\":\"c\",\"causationId\":null,\"data\":{}}";
    jdbc.update(
        "INSERT INTO platform.outbox_event (event_id, event_type, tenant_id, envelope, created_at)"
            + " VALUES (?, ?, ?, ?::jsonb, now())",
        eventId,
        internalType,
        owner,
        envelope);
  }

  // ------------------------------------------------------------------------------------------
  // Authorization (D110-1)
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyAMfaTenantAdministratorMayUseTheSurface() throws Exception {
    String employee = Hierarchy.bearer(tenant, "sub-emp-" + UUID.randomUUID(), "employee");
    String password =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject("sub-pwd-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    mvc.perform(get(PATH).header("Authorization", employee)).andExpect(status().isForbidden());
    mvc.perform(get(PATH).header("Authorization", password))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MFA_REQUIRED"))
        .andExpect(header().string("Cache-Control", NO_STORE));
    mvc.perform(json(post(PATH), employee, key(), createBody("x", "https://a.example.com/")))
        .andExpect(status().isForbidden());
    assertThat(count("SELECT count(*) FROM integrations.webhook WHERE tenant_id = ?", tenant))
        .isZero();
  }

  @Test
  void aForeignOrUnknownWebhookIsNotFoundAndNothingLeaks() throws Exception {
    String id = create(admin, "Payroll feed").get("webhook").get("id").asText();
    for (MockHttpServletRequestBuilder request :
        List.of(
            get(PATH + "/" + id).header("Authorization", otherAdmin),
            get(PATH + "/" + UUID.randomUUID()).header("Authorization", otherAdmin),
            get(PATH + "/" + id + "/deliveries").header("Authorization", otherAdmin),
            get(PATH + "/" + id + "/deliveries/" + UUID.randomUUID())
                .header("Authorization", otherAdmin))) {
      mvc.perform(request)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("WEBHOOK_NOT_FOUND"));
    }
    mvc.perform(
            json(
                post(PATH + "/" + id + "/disable"),
                otherAdmin,
                key(),
                "{\"configVersion\":1,\"confirm\":true}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WEBHOOK_NOT_FOUND"));
    assertThat(detail(admin, id).get("status").asText()).isEqualTo("ACTIVE");
    mvc.perform(get(PATH).header("Authorization", otherAdmin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  // ------------------------------------------------------------------------------------------
  // Create, secret, idempotency (D110-2, D110-5, D110-6)
  // ------------------------------------------------------------------------------------------

  @Test
  void theSecretIsShownOnceAndNeverStoredOrReplayed(CapturedOutput output) throws Exception {
    String idempotencyKey = key();
    String body =
        createBody(
            "Payroll feed", "https://Hooks.Example.com:443/in?b=2&a=1#frag".replace("#frag", ""));
    String first =
        mvc.perform(json(post(PATH), admin, idempotencyKey, body))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", NO_STORE))
            .andExpect(jsonPath("$.webhook.status").value("ACTIVE"))
            .andExpect(jsonPath("$.webhook.secretVersion").value(1))
            .andExpect(jsonPath("$.secret").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode created = JSON.readTree(first);
    String secret = created.get("secret").asText();
    String id = created.get("webhook").get("id").asText();
    assertThat(secret).hasSizeGreaterThanOrEqualTo(43);

    // Replay: same outcome, never the secret.
    String replay =
        mvc.perform(json(post(PATH), admin, idempotencyKey, body))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(replay).doesNotContain(secret);
    assertThat(
            JSON.readTree(replay).path("secret").isNull()
                || JSON.readTree(replay).path("secret").isMissingNode())
        .isTrue();
    assertThat(count("SELECT count(*) FROM integrations.webhook WHERE tenant_id = ?", tenant))
        .isEqualTo(1);

    // Reads never carry it; the normalized endpoint is shown.
    String read =
        mvc.perform(get(PATH + "/" + id).header("Authorization", admin))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", NO_STORE))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(read).doesNotContain(secret);
    assertThat(JSON.readTree(read).path("secret").isNull()).isTrue();
    assertThat(JSON.readTree(read).get("webhook").get("url").asText())
        .startsWith("https://hooks.example.com/");

    // Nothing durable holds the plaintext (store, idempotency, audit, outbox, logs).
    for (String table :
        List.of(
            "integrations.webhook_secret",
            "platform.idempotency_record",
            "platform.audit_event",
            "platform.outbox_event")) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?",
                  Integer.class,
                  "%" + secret + "%"))
          .as(table)
          .isZero();
    }
    assertThat(output.getAll()).doesNotContain(secret);
    assertThat(
            count(
                "SELECT octet_length(ciphertext) FROM integrations.webhook_secret"
                    + " WHERE webhook_id = ?::uuid",
                id))
        .isBetween(40, 256);

    // A different body under the same key is a conflict, not a second webhook.
    mvc.perform(
            json(post(PATH), admin, idempotencyKey, createBody("Other", "https://b.example.com/")))
        .andExpect(status().isConflict());
  }

  @Test
  void createRequiresConfirmationAKeyAndAValidEndpoint() throws Exception {
    mvc.perform(
            json(
                post(PATH),
                admin,
                key(),
                "{\"name\":\"a\",\"url\":\"https://a.example.com/\","
                    + "\"eventTypes\":[\"leave.request.approved.v1\"]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(json(post(PATH), admin, null, createBody("a", "https://a.example.com/")))
        .andExpect(status().isBadRequest());
    for (String bad :
        List.of(
            "http://a.example.com/",
            "https://user:pw@a.example.com/",
            "https://127.0.0.1/",
            "https://a.example.com/#f",
            "https://a.example.com:8443/")) {
      mvc.perform(json(post(PATH), admin, key(), createBody("a", bad)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("WEBHOOK_URL_INVALID"));
    }
    mvc.perform(
            json(post(PATH), admin, key(), createBody("a", "https://a.example.com/", "nope.v1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("WEBHOOK_EVENT_TYPE_INVALID"));
    for (String host : List.of("private-1.example.com", "mixed-1.example.com")) {
      mvc.perform(json(post(PATH), admin, key(), createBody("a", "https://" + host + "/")))
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("WEBHOOK_DESTINATION_BLOCKED"));
    }
    assertThat(count("SELECT count(*) FROM integrations.webhook WHERE tenant_id = ?", tenant))
        .isZero();
  }

  @Test
  void aTenantMayHoldTwentyWebhooksAndNeverMore() throws Exception {
    for (int i = 0; i < 20; i++) {
      create(admin, "Hook " + i);
    }
    mvc.perform(json(post(PATH), admin, key(), createBody("Hook 21", "https://z.example.com/")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WEBHOOK_LIMIT_REACHED"));
    // Another tenant is unaffected.
    create(otherAdmin, "Elsewhere");
  }

  @Test
  void concurrentCreatesNeverExceedTheCap() throws Exception {
    for (int i = 0; i < 18; i++) {
      create(admin, "Hook " + i);
    }
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Callable<Integer>> tasks =
          java.util.stream.IntStream.range(0, 6)
              .<Callable<Integer>>mapToObj(
                  i ->
                      () ->
                          mvc.perform(
                                  json(
                                      post(PATH),
                                      admin,
                                      key(),
                                      createBody("Race " + i, "https://r" + i + ".example.com/")))
                              .andReturn()
                              .getResponse()
                              .getStatus())
              .toList();
      List<Integer> statuses = new java.util.ArrayList<>();
      for (Future<Integer> f : pool.invokeAll(tasks)) {
        statuses.add(f.get());
      }
      assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(2);
      assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(4);
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("SELECT count(*) FROM integrations.webhook WHERE tenant_id = ?", tenant))
        .isEqualTo(20);
  }

  // ------------------------------------------------------------------------------------------
  // List, detail, update (D110-3, D110-4)
  // ------------------------------------------------------------------------------------------

  @Test
  void listPagesByKeysetAndBindsTheCursor() throws Exception {
    for (int i = 0; i < 5; i++) {
      create(admin, "Hook " + i);
    }
    JsonNode page = read(get(PATH + "?limit=2").header("Authorization", admin));
    assertThat(page.get("items")).hasSize(2);
    String cursor = page.get("nextCursor").asText();
    JsonNode second = read(get(PATH + "?limit=2&cursor=" + cursor).header("Authorization", admin));
    assertThat(second.get("items")).hasSize(2);
    assertThat(second.get("items").get(0).get("id").asText())
        .isNotEqualTo(page.get("items").get(0).get("id").asText());
    // A cursor from another tenant is refused.
    mvc.perform(get(PATH + "?limit=2&cursor=" + cursor).header("Authorization", otherAdmin))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateChangesConfigurationWithOptimisticVersionAndIdempotency() throws Exception {
    JsonNode created = create(admin, "Payroll feed");
    String id = created.get("webhook").get("id").asText();
    String body =
        "{\"name\":\"Payroll feed 2\",\"url\":\"https://new.example.com/in\","
            + "\"eventTypes\":[\"timesheet.approved.v1\"],\"configVersion\":1,\"confirm\":true}";
    String updateKey = key();
    mvc.perform(json(patch(PATH + "/" + id), admin, updateKey, body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.webhook.configVersion").value(2))
        .andExpect(jsonPath("$.webhook.name").value("Payroll feed 2"))
        .andExpect(jsonPath("$.secret").doesNotExist());
    mvc.perform(json(patch(PATH + "/" + id), admin, updateKey, body)).andExpect(status().isOk());
    mvc.perform(json(patch(PATH + "/" + id), admin, key(), body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WEBHOOK_VERSION_STALE"));
    mvc.perform(
            json(
                patch(PATH + "/" + id),
                admin,
                key(),
                body.replace("https://new.example.com/in", "https://private-9.example.com/")
                    .replace("\"configVersion\":1", "\"configVersion\":2")))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("WEBHOOK_DESTINATION_BLOCKED"));
    assertThat(detail(admin, id).get("configVersion").asInt()).isEqualTo(2);
  }

  // ------------------------------------------------------------------------------------------
  // Secret rotation, disable, enable (D110-5, D110-7)
  // ------------------------------------------------------------------------------------------

  @Test
  void rotationSignsWithBothSecretsForADayThenErasesTheOldCiphertext() throws Exception {
    Instant start = Instant.parse("2026-10-20T10:00:00Z");
    clock.set(start);
    JsonNode created = create(admin, "Payroll feed");
    String id = created.get("webhook").get("id").asText();
    JsonNode rotated =
        JSON.readTree(
            mvc.perform(
                    json(
                        post(PATH + "/" + id + "/rotate-secret"),
                        admin,
                        key(),
                        "{\"configVersion\":1,\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhook.secretVersion").value(2))
                .andExpect(jsonPath("$.secret").isString())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(rotated.get("secret").asText()).isNotEqualTo(created.get("secret").asText());
    assertThat(Instant.parse(rotated.get("previousSecretExpiresAt").asText()))
        .isEqualTo(start.plusSeconds(24 * 3600));
    assertThat(
            count(
                "SELECT count(*) FROM integrations.webhook_secret WHERE webhook_id = ?::uuid"
                    + " AND ciphertext IS NOT NULL",
                id))
        .isEqualTo(2);

    // A test delivery during the overlap carries two signatures.
    mvc.perform(
            json(post(PATH + "/" + id + "/test-deliveries"), admin, key(), "{\"confirm\":true}"))
        .andExpect(status().isAccepted());
    jobs.deliverDue();
    assertThat(sentTo(id)).hasSize(1);
    assertThat(sentTo(id).get(0).headers().get("X-DivalHR-Signature").split(",")).hasSize(2);

    // After 24 h only the current secret signs and the old ciphertext is erased.
    clock.set(start.plusSeconds(24 * 3600 + 1));
    jobs.eraseExpiredSecrets();
    assertThat(
            count(
                "SELECT count(*) FROM integrations.webhook_secret WHERE webhook_id = ?::uuid"
                    + " AND ciphertext IS NOT NULL",
                id))
        .isEqualTo(1);
    mvc.perform(
            json(post(PATH + "/" + id + "/test-deliveries"), admin, key(), "{\"confirm\":true}"))
        .andExpect(status().isAccepted());
    jobs.deliverDue();
    assertThat(sentTo(id)).hasSize(2);
    assertThat(sentTo(id).get(1).headers().get("X-DivalHR-Signature").split(",")).hasSize(1);
  }

  @Test
  void disableAndEnableAreExplicitAndDisableCancelsPendingDeliveries() throws Exception {
    String id = create(admin, "Payroll feed").get("webhook").get("id").asText();
    mvc.perform(
            json(post(PATH + "/" + id + "/test-deliveries"), admin, key(), "{\"confirm\":true}"))
        .andExpect(status().isAccepted());
    mvc.perform(json(post(PATH + "/" + id + "/disable"), admin, key(), "{\"configVersion\":1}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            json(
                post(PATH + "/" + id + "/disable"),
                admin,
                key(),
                "{\"configVersion\":1,\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.webhook.status").value("DISABLED"));
    assertThat(
            count(
                "SELECT count(*) FROM integrations.delivery WHERE webhook_id = ?::uuid"
                    + " AND state = 'CANCELLED'",
                id))
        .isEqualTo(1);
    jobs.deliverDue();
    assertThat(sentTo(id)).isEmpty();
    int version = detail(admin, id).get("configVersion").asInt();
    mvc.perform(
            json(
                post(PATH + "/" + id + "/disable"),
                admin,
                key(),
                "{\"configVersion\":" + version + ",\"confirm\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WEBHOOK_ALREADY_DISABLED"));
    mvc.perform(
            json(post(PATH + "/" + id + "/test-deliveries"), admin, key(), "{\"confirm\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WEBHOOK_DISABLED"));
    mvc.perform(
            json(
                post(PATH + "/" + id + "/enable"),
                admin,
                key(),
                "{\"configVersion\":" + version + ",\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.webhook.status").value("ACTIVE"));
  }

  // ------------------------------------------------------------------------------------------
  // Test deliveries (D110-12)
  // ------------------------------------------------------------------------------------------

  @Test
  void aWebhookAcceptsFiveTestDeliveriesAnHourAndReplaysDoNotCount() throws Exception {
    String id = create(admin, "Payroll feed").get("webhook").get("id").asText();
    String replayed = key();
    String first = null;
    for (int i = 0; i < 5; i++) {
      String k = i == 0 ? replayed : key();
      String response =
          mvc.perform(
                  json(post(PATH + "/" + id + "/test-deliveries"), admin, k, "{\"confirm\":true}"))
              .andExpect(status().isAccepted())
              .andReturn()
              .getResponse()
              .getContentAsString();
      if (i == 0) {
        first = response;
      }
    }
    // Replay of an already accepted key is not a sixth command.
    String again =
        mvc.perform(
                json(
                    post(PATH + "/" + id + "/test-deliveries"),
                    admin,
                    replayed,
                    "{\"confirm\":true}"))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JSON.readTree(again).get("deliveryId"))
        .isEqualTo(JSON.readTree(first).get("deliveryId"));
    // Invalid and unauthorized attempts do not consume quota (nothing here is the sixth).
    mvc.perform(json(post(PATH + "/" + id + "/test-deliveries"), admin, key(), "{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            json(
                post(PATH + "/" + id + "/test-deliveries"),
                otherAdmin,
                key(),
                "{\"confirm\":true}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            json(post(PATH + "/" + id + "/test-deliveries"), admin, key(), "{\"confirm\":true}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("WEBHOOK_TEST_RATE_LIMITED"));
    assertThat(count("SELECT count(*) FROM integrations.delivery WHERE webhook_id = ?::uuid", id))
        .isEqualTo(5);
  }

  @Test
  void simultaneousTestCommandsNeverExceedFivePerWebhook() throws Exception {
    String id = create(admin, "Payroll feed").get("webhook").get("id").asText();
    ExecutorService pool = Executors.newFixedThreadPool(10);
    try {
      List<Callable<Integer>> tasks =
          java.util.stream.IntStream.range(0, 10)
              .<Callable<Integer>>mapToObj(
                  i ->
                      () ->
                          mvc.perform(
                                  json(
                                      post(PATH + "/" + id + "/test-deliveries"),
                                      admin,
                                      key(),
                                      "{\"confirm\":true}"))
                              .andReturn()
                              .getResponse()
                              .getStatus())
              .toList();
      List<Integer> statuses = new java.util.ArrayList<>();
      for (Future<Integer> f : pool.invokeAll(tasks)) {
        statuses.add(f.get());
      }
      assertThat(statuses.stream().filter(s -> s == 202).count()).isEqualTo(5);
      assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(5);
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("SELECT count(*) FROM integrations.delivery WHERE webhook_id = ?::uuid", id))
        .isEqualTo(5);
  }

  @Test
  void aTenantAcceptsTwentyTestDeliveriesAnHourWithTheSameErrorCode() throws Exception {
    Instant start = Instant.parse("2026-10-20T11:00:00Z");
    clock.set(start);
    List<String> ids = new java.util.ArrayList<>();
    for (int i = 0; i < 5; i++) {
      ids.add(create(admin, "Hook " + i).get("webhook").get("id").asText());
    }
    int accepted = 0;
    int limited = 0;
    for (int i = 0; i < 22; i++) {
      var response =
          mvc.perform(
                  json(
                      post(PATH + "/" + ids.get(i % 5) + "/test-deliveries"),
                      admin,
                      key(),
                      "{\"confirm\":true}"))
              .andReturn()
              .getResponse();
      if (response.getStatus() == 202) {
        accepted++;
      } else {
        limited++;
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("WEBHOOK_TEST_RATE_LIMITED");
        assertThat(response.getHeader("Retry-After")).isNotBlank();
      }
    }
    assertThat(accepted).isEqualTo(20);
    assertThat(limited).isEqualTo(2);
  }

  // ------------------------------------------------------------------------------------------
  // Fan-out, delivery, retry, suspension (D110-4, D110-9, D110-10)
  // ------------------------------------------------------------------------------------------

  @Test
  void aBusinessEventBecomesOneMinimalSignedDeliveryPerSubscribedWebhook() throws Exception {
    String subscribed = create(admin, "A").get("webhook").get("id").asText();
    mvc.perform(
            json(
                post(PATH),
                admin,
                key(),
                createBody("B", "https://other.example.com/", "timesheet.approved.v1")))
        .andExpect(status().isCreated());
    create(otherAdmin, "Foreign");
    UUID leave = UUID.randomUUID();
    for (String internal :
        List.of(
            "people.leave-request.approved.v1",
            "people.leave-request.routing-exception-approved.v1",
            "people.leave-request.substitute-approved.v1")) {
      publish(tenant, internal, UUID.randomUUID());
    }
    publish(otherTenant, "people.leave-request.approved.v1", leave);
    publish(tenant, "people.leave-request.rejected.v1", UUID.randomUUID());

    assertThat(jobs.fanOut()).isEqualTo(4);
    assertThat(jobs.fanOut()).isZero();
    assertThat(count("SELECT count(*) FROM integrations.delivery WHERE tenant_id = ?", tenant))
        .isEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM integrations.delivery WHERE tenant_id = ?"
                    + " AND event_type = 'leave.request.approved.v1' AND webhook_id = ?::uuid",
                tenant,
                subscribed))
        .isEqualTo(3);

    jobs.deliverDue();
    List<String> mine =
        jdbc.queryForList(
            "SELECT id::text FROM integrations.delivery WHERE tenant_id = ?", String.class, tenant);
    List<WebhookTestConfig.Sent> delivered =
        transport.sent().stream()
            .filter(x -> mine.contains(x.headers().get("X-DivalHR-Delivery-Id")))
            .toList();
    assertThat(delivered).hasSize(3);
    WebhookTestConfig.Sent sent = delivered.get(0);
    JsonNode payload = JSON.readTree(sent.body());
    assertThat(payload.get("eventType").asText()).isEqualTo("leave.request.approved.v1");
    assertThat(payload.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder(
            "schemaVersion", "eventId", "eventType", "occurredAt", "tenantId", "resource", "links");
    assertThat(payload.toString()).doesNotContain("routing").doesNotContain("substitute");
    assertThat(sent.headers())
        .containsKeys(
            "X-DivalHR-Event-Id",
            "X-DivalHR-Delivery-Id",
            "X-DivalHR-Attempt",
            "X-DivalHR-Timestamp",
            "X-DivalHR-Signature");
    assertThat(sent.headers().get("User-Agent")).isEqualTo("DivalHR-Webhooks/1");
    assertThat(sent.headers().get("X-DivalHR-Attempt")).isEqualTo("1");
  }

  @Test
  void failuresFollowTheScheduleKeepTheBodyAndSuspendAfterTenTerminalFailures() throws Exception {
    Instant start = Instant.parse("2026-10-20T12:00:00Z");
    clock.set(start);
    String id = create(admin, "Payroll feed").get("webhook").get("id").asText();
    transport.respond(
        new com.divalhr.core.integrations.application.DeliveryTransport.Outcome(
            503, com.divalhr.core.integrations.domain.FailureClass.HTTP_RESPONSE, 7));
    mvc.perform(
            json(post(PATH + "/" + id + "/test-deliveries"), admin, key(), "{\"confirm\":true}"))
        .andExpect(status().isAccepted());

    long[] waits = {0, 60, 300, 1800, 7200, 43200};
    Instant at = start;
    for (int attempt = 1; attempt <= 6; attempt++) {
      at = at.plusSeconds(waits[attempt - 1]);
      clock.set(at);
      if (attempt > 1) {
        clock.set(at.minusSeconds(1));
        jobs.deliverDue();
        assertThat(sentTo(id)).as("not before the scheduled time").hasSize(attempt - 1);
        clock.set(at);
      }
      jobs.deliverDue();
      assertThat(sentTo(id)).hasSize(attempt);
    }
    assertThat(sentTo(id).stream().map(s -> new String(s.body())).distinct()).hasSize(1);
    assertThat(
            count(
                "SELECT count(*) FROM integrations.delivery WHERE webhook_id = ?::uuid"
                    + " AND state = 'FAILED' AND attempt_count = 6",
                id))
        .isEqualTo(1);
    assertThat(detail(admin, id).get("consecutiveFailures").asInt()).isEqualTo(1);

    // Nine more terminal failures suspend the endpoint; success elsewhere would have reset it.
    for (int i = 0; i < 9; i++) {
      jdbc.update(
          "INSERT INTO integrations.delivery (tenant_id, id, webhook_id, event_id, event_type,"
              + " kind, payload, state, attempt_count, created_at, next_attempt_at)"
              + " VALUES (?, ?, ?::uuid, ?, 'leave.request.approved.v1', 'EVENT', '{}'::bytea,"
              + " 'PENDING', 0,"
              + " ?, ?)",
          tenant,
          UUID.randomUUID(),
          id,
          UUID.randomUUID(),
          java.sql.Timestamp.from(at),
          java.sql.Timestamp.from(at));
    }
    for (int round = 0; round < 6; round++) {
      at = at.plusSeconds(43201);
      clock.set(at);
      jobs.deliverDue();
    }
    assertThat(detail(admin, id).get("status").asText()).isEqualTo("SUSPENDED");
    mvc.perform(
            json(post(PATH + "/" + id + "/test-deliveries"), admin, key(), "{\"confirm\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WEBHOOK_SUSPENDED"));

    // Re-enabling resets the counter and never replays history.
    int sentBefore = sentTo(id).size();
    int version = detail(admin, id).get("configVersion").asInt();
    mvc.perform(
            json(
                post(PATH + "/" + id + "/enable"),
                admin,
                key(),
                "{\"configVersion\":" + version + ",\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.webhook.status").value("ACTIVE"))
        .andExpect(jsonPath("$.webhook.consecutiveFailures").value(0));
    jobs.deliverDue();
    assertThat(sentTo(id)).hasSize(sentBefore);
  }

  @Test
  void historyShowsClosedFieldsOnlyAndNeverResponseData() throws Exception {
    String id = create(admin, "Payroll feed").get("webhook").get("id").asText();
    transport.fail(com.divalhr.core.integrations.domain.FailureClass.CONNECTION_FAILED);
    String deliveryId =
        JSON.readTree(
                mvc.perform(
                        json(
                            post(PATH + "/" + id + "/test-deliveries"),
                            admin,
                            key(),
                            "{\"confirm\":true}"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("deliveryId")
            .asText();
    jobs.deliverDue();
    String list =
        mvc.perform(get(PATH + "/" + id + "/deliveries").header("Authorization", admin))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", NO_STORE))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode row = JSON.readTree(list).get("items").get(0);
    assertThat(row.get("state").asText()).isEqualTo("RETRYING");
    assertThat(row.get("lastFailureClass").asText()).isEqualTo("CONNECTION_FAILED");
    assertThat(row.get("attemptCount").asInt()).isEqualTo(1);
    JsonNode one =
        read(get(PATH + "/" + id + "/deliveries/" + deliveryId).header("Authorization", admin));
    assertThat(one.get("attempts")).hasSize(1);
    assertThat(one.toString()).doesNotContain("payload").doesNotContain("body");
    mvc.perform(
            get(PATH + "/" + id + "/deliveries/" + UUID.randomUUID())
                .header("Authorization", admin))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WEBHOOK_DELIVERY_NOT_FOUND"));
  }

  // ------------------------------------------------------------------------------------------
  // Audit (D110-14)
  // ------------------------------------------------------------------------------------------

  @Test
  void commandsAndDisclosureReadsAreAuditedAndFailClosed() throws Exception {
    String id = create(admin, "Payroll feed").get("webhook").get("id").asText();
    mvc.perform(get(PATH + "/" + id).header("Authorization", admin)).andExpect(status().isOk());
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action = 'webhook.create'",
                tenant))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action = 'webhook.read'",
                tenant))
        .isGreaterThanOrEqualTo(1);
    String metadata =
        jdbc.queryForObject(
            "SELECT string_agg(metadata::text, ' ') FROM platform.audit_event WHERE tenant_id = ?",
            String.class,
            tenant);
    assertThat(metadata).doesNotContain("hooks.example.com");

    jdbc.execute(
        "CREATE OR REPLACE FUNCTION platform.test_fail_hook_audit() RETURNS trigger LANGUAGE"
            + " plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit down'; END $$");
    jdbc.execute(
        "CREATE TRIGGER test_fail_hook_audit BEFORE INSERT ON platform.audit_event"
            + " FOR EACH ROW EXECUTE FUNCTION platform.test_fail_hook_audit()");
    try {
      mvc.perform(get(PATH + "/" + id).header("Authorization", admin))
          .andExpect(status().is5xxServerError());
      mvc.perform(json(post(PATH), admin, key(), createBody("Nope", "https://n.example.com/")))
          .andExpect(status().is5xxServerError());
      assertThat(count("SELECT count(*) FROM integrations.webhook WHERE tenant_id = ?", tenant))
          .isEqualTo(1);
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS test_fail_hook_audit ON platform.audit_event");
      jdbc.execute("DROP FUNCTION IF EXISTS platform.test_fail_hook_audit()");
    }
  }

  @SuppressWarnings("unused")
  private static Map<String, Object> unused() {
    return Map.of();
  }
}
