package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.integrations.application.WebhookJobs;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-110 (D110-13, D110-15): the database itself keeps delivery history and secrets append-only
 * and refuses hard deletes, so no code path (or operator mistake) can erase what was sent.
 */
@IntegrationTest
@Import(WebhookTestConfig.class)
class WebhookEvidenceGuardsIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private WebhookJobs jobs;

  private UUID webhook(UUID tenant, String bearer) throws Exception {
    String body =
        mvc.perform(
                post("/api/v1/integrations/webhooks")
                    .header("Authorization", bearer)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"Evidence\",\"url\":\"https://evidence.example.com/in\","
                            + "\"eventTypes\":[\"leave.request.approved.v1\"],\"confirm\":true}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JSON.readTree(body).get("webhook").get("id").asText());
    mvc.perform(
            post("/api/v1/integrations/webhooks/" + id + "/test-deliveries")
                .header("Authorization", bearer)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isAccepted());
    jobs.deliverDue();
    return id;
  }

  @Test
  void historyAndSecretsCannotBeEditedOrDeleted() throws Exception {
    UUID tenant = Hierarchy.newTenant(mvc);
    UUID id = webhook(tenant, Hierarchy.admin(tenant));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.delivery_attempt WHERE tenant_id = ?",
                Integer.class,
                tenant))
        .isGreaterThanOrEqualTo(1);

    for (String statement :
        new String[] {
          "DELETE FROM integrations.delivery_attempt WHERE tenant_id = '" + tenant + "'",
          "UPDATE integrations.delivery_attempt SET http_status = 500 WHERE tenant_id = '"
              + tenant
              + "'",
          "DELETE FROM integrations.delivery WHERE tenant_id = '" + tenant + "'",
          "UPDATE integrations.delivery SET payload = '{}'::bytea WHERE tenant_id = '"
              + tenant
              + "'",
          "DELETE FROM integrations.webhook WHERE tenant_id = '" + tenant + "'",
          "DELETE FROM integrations.webhook_secret WHERE tenant_id = '" + tenant + "'",
          "UPDATE integrations.webhook_secret SET ciphertext = decode(repeat('ab', 60), 'hex')"
              + " WHERE tenant_id = '"
              + tenant
              + "'",
          "UPDATE integrations.webhook SET tenant_id = '"
              + UUID.randomUUID()
              + "' WHERE id = '"
              + id
              + "'"
        }) {
      assertThatThrownBy(() -> jdbc.execute(statement))
          .as(statement)
          .isInstanceOf(DataAccessException.class);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.webhook WHERE id = ?", Integer.class, id))
        .isEqualTo(1);
  }
}
