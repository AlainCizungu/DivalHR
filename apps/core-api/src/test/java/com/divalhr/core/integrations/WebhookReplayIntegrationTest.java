package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.integrations.WebhookTestConfig.SwitchableDns.Mode;
import com.divalhr.core.integrations.application.WebhookSecrets;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * R162-1: a completed create or update replays without DNS, network or encryption-key checks; the
 * outside-world validation still guards every new execution, and a reused key with different input
 * is still refused.
 */
@IntegrationTest
@Import({WebhookTestConfig.class, SettableClock.Config.class})
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.webhook-write=600",
      "divalhr.request-limits.subject.webhook-read=600",
      "divalhr.integrations.jitter=false"
    })
class WebhookReplayIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PATH = "/api/v1/integrations/webhooks";

  @Autowired private MockMvc mvc;
  @Autowired private WebhookTestConfig.SwitchableDns dns;
  @MockitoSpyBean private WebhookSecrets secrets;

  private String admin;

  @BeforeEach
  void organization() throws Exception {
    dns.reset();
    UUID tenant = Hierarchy.newTenant(mvc);
    admin = Hierarchy.bearer(tenant, "sub-replay-" + UUID.randomUUID(), "tenant-admin");
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static String host(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
  }

  private static String createBody(String host) {
    return "{\"name\":\"Replay feed\",\"url\":\"https://"
        + host
        + "/in\",\"eventTypes\":[\"leave.request.approved.v1\"],\"confirm\":true}";
  }

  private static String updateBody(String host, int version) {
    return "{\"name\":\"Replay feed 2\",\"url\":\"https://"
        + host
        + "/in\",\"eventTypes\":[\"timesheet.approved.v1\"],\"configVersion\":"
        + version
        + ",\"confirm\":true}";
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String idempotencyKey, String body) {
    return request
        .header("Authorization", admin)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private JsonNode created(String idempotencyKey, String body) throws Exception {
    return JSON.readTree(
        mvc.perform(json(post(PATH), idempotencyKey, body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void aCompletedCreateReplaysAfterDnsBecomesBlockedWithoutAnotherLookupAndWithoutTheSecret()
      throws Exception {
    String host = host("create");
    String idempotencyKey = key();
    String body = createBody(host);
    JsonNode first = created(idempotencyKey, body);
    String secret = first.get("secret").asText();
    String id = first.get("webhook").get("id").asText();
    int lookups = dns.lookups(host);
    assertThat(lookups).isPositive();

    dns.mode(Mode.BLOCKED);
    String replay =
        mvc.perform(json(post(PATH), idempotencyKey, body))
            .andExpect(status().is2xxSuccessful())
            .andExpect(jsonPath("$.webhook.id").value(id))
            .andExpect(jsonPath("$.secret").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(replay).doesNotContain(secret);
    assertThat(dns.lookups(host)).as("no DNS lookup on replay").isEqualTo(lookups);

    dns.mode(Mode.DOWN);
    mvc.perform(json(post(PATH), idempotencyKey, body))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$.webhook.id").value(id));
    assertThat(dns.lookups(host)).isEqualTo(lookups);
  }

  @Test
  void aCompletedUpdateReplaysAfterDnsChangesWithoutAnotherLookup() throws Exception {
    String id = created(key(), createBody(host("upd-old"))).get("webhook").get("id").asText();
    String newHost = host("upd-new");
    String updateKey = key();
    String body = updateBody(newHost, 1);
    mvc.perform(json(patch(PATH + "/" + id), updateKey, body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.webhook.configVersion").value(2));
    int lookups = dns.lookups(newHost);
    assertThat(lookups).isPositive();

    dns.mode(Mode.BLOCKED);
    mvc.perform(json(patch(PATH + "/" + id), updateKey, body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.webhook.configVersion").value(2))
        .andExpect(jsonPath("$.secret").doesNotExist());
    dns.mode(Mode.DOWN);
    mvc.perform(json(patch(PATH + "/" + id), updateKey, body)).andExpect(status().isOk());
    assertThat(dns.lookups(newHost)).as("no DNS lookup on replay").isEqualTo(lookups);
  }

  @Test
  void aNewKeyStillMeetsTheDestinationRulesAndAReusedKeyWithOtherInputIsStillRefused()
      throws Exception {
    String host = host("blocked");
    String idempotencyKey = key();
    String body = createBody(host);
    String id = created(idempotencyKey, body).get("webhook").get("id").asText();

    dns.mode(Mode.BLOCKED);
    // A different key aimed at the now-blocked destination is refused (create and update).
    mvc.perform(json(post(PATH), key(), body))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("WEBHOOK_DESTINATION_BLOCKED"));
    mvc.perform(json(patch(PATH + "/" + id), key(), updateBody(host, 1)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("WEBHOOK_DESTINATION_BLOCKED"));
    // Fingerprint conflict detection is untouched: same key, different input.
    mvc.perform(json(post(PATH), idempotencyKey, createBody(host("other"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    // ... and a blocked destination under a reused key is a conflict, not a DNS answer.
    String updateKey = key();
    dns.mode(Mode.NORMAL);
    mvc.perform(json(patch(PATH + "/" + id), updateKey, updateBody(host("fine"), 1)))
        .andExpect(status().isOk());
    dns.mode(Mode.BLOCKED);
    mvc.perform(json(patch(PATH + "/" + id), updateKey, updateBody(host("changed"), 1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
  }

  @Test
  void secretUnavailabilityDoesNotStopTheReplayOfCompletedCommands() throws Exception {
    String host = host("keyless");
    String createKey = key();
    String createBody = createBody(host);
    String id = created(createKey, createBody).get("webhook").get("id").asText();
    String updateKey = key();
    String updateBody = updateBody(host("keyless-new"), 1);
    mvc.perform(json(patch(PATH + "/" + id), updateKey, updateBody)).andExpect(status().isOk());

    doReturn(false).when(secrets).available();
    mvc.perform(json(post(PATH), createKey, createBody))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$.webhook.id").value(id))
        .andExpect(jsonPath("$.secret").doesNotExist());
    mvc.perform(json(patch(PATH + "/" + id), updateKey, updateBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.webhook.configVersion").value(2));
    // A new execution still needs the key (validation is preserved).
    mvc.perform(json(post(PATH), key(), createBody(host("fresh"))))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("WEBHOOK_SECRET_UNAVAILABLE"));
    mvc.perform(json(patch(PATH + "/" + id), key(), updateBody(host("fresh2"), 2)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("WEBHOOK_SECRET_UNAVAILABLE"));
  }
}
