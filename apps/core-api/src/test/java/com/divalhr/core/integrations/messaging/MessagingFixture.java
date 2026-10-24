package com.divalhr.core.integrations.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Request builders and one-call setups for the MVP-111 integration tests. */
final class MessagingFixture {

  static final ObjectMapper JSON = new ObjectMapper();
  static final String PATH = "/api/v1/integrations/messaging/whatsapp";
  static final String CALLBACK = "/api/v1/public/messaging/whatsapp/callback";
  static final String NO_STORE = "private, no-store";
  static final String[] TYPES = {
    "LEAVE_DECISION",
    "TIMESHEET_APPROVED",
    "ATTENDANCE_CORRECTED",
    "PAYROLL_INPUT_LOCKED",
    "PAYROLL_EXPORT_READY",
    "CONNECTOR_TEST"
  };
  private static final SecureRandom RANDOM = new SecureRandom();

  private final MockMvc mvc;

  MessagingFixture(MockMvc mvc) {
    this.mvc = mvc;
  }

  static String key() {
    return UUID.randomUUID().toString();
  }

  /** A syntactically valid synthetic token; random so no value is a committed secret. */
  static String newToken() {
    return "EAAG" + UUID.randomUUID().toString().replace("-", "") + "Zq";
  }

  static String digits() {
    StringBuilder b = new StringBuilder("9");
    for (int i = 0; i < 12; i++) {
      b.append(RANDOM.nextInt(10));
    }
    return b.toString();
  }

  /** A random valid E.164 number. */
  static String newNumber() {
    StringBuilder b = new StringBuilder("+2438");
    for (int i = 0; i < 8; i++) {
      b.append(RANDOM.nextInt(10));
    }
    return b.toString();
  }

  static String templates() {
    List<String> items = new ArrayList<>();
    for (String type : TYPES) {
      for (String language : new String[] {"en", "fr"}) {
        items.add(
            "{\"notificationType\":\""
                + type
                + "\",\"language\":\""
                + language
                + "\",\"templateName\":\"divalhr_"
                + type.toLowerCase()
                + "_"
                + language
                + "\"}");
      }
    }
    return "[" + String.join(",", items) + "]";
  }

  static String connectorBody(String waba, String phoneId, String token) {
    return "{\"wabaId\":\""
        + waba
        + "\",\"phoneNumberId\":\""
        + phoneId
        + "\",\"displayNumber\":\"+243812000111\",\"accessToken\":\""
        + token
        + "\",\"templates\":"
        + templates()
        + ",\"confirm\":true}";
  }

  static String recipientBody(String label, String number, String language, String... types) {
    String list =
        String.join(",", java.util.Arrays.stream(types).map(t -> "\"" + t + "\"").toList());
    return "{\"label\":\""
        + label
        + "\",\"phoneNumber\":\""
        + number
        + "\",\"language\":\""
        + language
        + "\",\"notificationTypes\":["
        + list
        + "],\"consent\":{\"source\":\"WRITTEN\",\"confirmedAt\":\""
        + Instant.now().minusSeconds(3600)
        + "\",\"attested\":true},\"confirm\":true}";
  }

  static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String bearer, String idempotencyKey, String body) {
    MockHttpServletRequestBuilder built =
        request
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    return idempotencyKey == null ? built : built.header("Idempotency-Key", idempotencyKey);
  }

  JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
    return JSON.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
  }

  /** Connects a draft connector with all twelve templates. */
  JsonNode connect(String admin, String token) throws Exception {
    String body =
        mvc.perform(json(post(PATH), admin, key(), connectorBody(digits(), digits(), token)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body);
  }

  JsonNode connector(String admin) throws Exception {
    return read(get(PATH).header("Authorization", admin));
  }

  /** Connects and enables: the connector ends ACTIVE. */
  JsonNode activate(String admin) throws Exception {
    JsonNode created = connect(admin, newToken());
    int version = created.get("configVersion").asInt();
    String body =
        mvc.perform(
                json(
                    post(PATH + "/enable"),
                    admin,
                    key(),
                    "{\"configVersion\":" + version + ",\"confirm\":true}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode active = JSON.readTree(body);
    assertThat(active.get("state").asText()).isEqualTo("ACTIVE");
    return active;
  }

  /** Registers a recipient and returns its view. */
  JsonNode recipient(String admin, String number, String language, String... types)
      throws Exception {
    String body =
        mvc.perform(
                json(
                    post(PATH + "/recipients"),
                    admin,
                    key(),
                    recipientBody("Payroll lead", number, language, types)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body);
  }

  /** Queues a connector test message and returns the delivery id. */
  UUID test(String admin, String recipientId) throws Exception {
    String body =
        mvc.perform(
                json(
                    post(PATH + "/recipients/" + recipientId + "/test-deliveries"),
                    admin,
                    key(),
                    "{\"confirm\":true}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JSON.readTree(body).get("deliveryId").asText());
  }

  static MockHttpServletRequestBuilder patchJson(String path, String bearer, String body) {
    return json(patch(path), bearer, key(), body);
  }

  /**
   * Commits one internal business event into the platform outbox (as a domain transaction would).
   */
  static UUID event(org.springframework.jdbc.core.JdbcTemplate jdbc, UUID tenant, String type) {
    UUID id = UUID.randomUUID();
    UUID subject = UUID.randomUUID();
    String envelope =
        "{\"eventId\":\""
            + id
            + "\",\"eventType\":\""
            + type
            + "\",\"schemaVersion\":1,"
            + "\"tenantId\":\""
            + tenant
            + "\",\"source\":\"test\",\"subject\":\""
            + subject
            + "\",\"eventTime\":\""
            + Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            + "\",\"correlationId\":\""
            + UUID.randomUUID()
            + "\",\"causationId\":null,\"data\":{}}";
    jdbc.update(
        "INSERT INTO platform.outbox_event (event_id, event_type, tenant_id, envelope, created_at) "
            + "VALUES (?, ?, ?, ?::jsonb, now())",
        id,
        type,
        tenant,
        envelope);
    return id;
  }
}
