package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-001 through the real HTTP and Spring Security chain (real signed tokens, real PostgreSQL).
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class CreateOrganizationApiIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  private static String platformAdmin(String subject) {
    return "Bearer "
        + TestTokens.token()
            .subject(subject)
            .roles(List.of("platform-admin"))
            .tenant(TestTokens.TENANT_A)
            .build();
  }

  private static MockHttpServletRequestBuilder create(String bearer, String key, String body) {
    MockHttpServletRequestBuilder request =
        post("/api/v1/organizations").contentType(MediaType.APPLICATION_JSON).content(body);
    if (bearer != null) {
      request.header("Authorization", bearer);
    }
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return request;
  }

  private double count(String outcome) {
    var counter =
        meters
            .find(OperationMetrics.METRIC)
            .tags("operation", "organization.create", "outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void platformAdminCreatesOrganizationWithServerGeneratedTenantId() throws Exception {
    String name = Organizations.uniqueName();
    MvcResult result =
        mvc.perform(
                create(
                        platformAdmin("sub-create"),
                        Organizations.newKey(),
                        Organizations.body("  " + name + "  "))
                    .header("X-Correlation-Id", "mvp001-create-0001"))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.countryCode").value("CD"))
            .andExpect(jsonPath("$.defaultLocale").value("fr"))
            .andExpect(jsonPath("$.timezone").value("Africa/Kinshasa"))
            .andExpect(jsonPath("$.currencies[0]").value("CDF"))
            .andExpect(jsonPath("$.currencies[1]").value("USD"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.createdAt").isString())
            .andReturn();

    UUID id =
        UUID.fromString(
            JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    assertThat(id).isNotEqualTo(TestTokens.TENANT_A).isNotEqualTo(TestTokens.TENANT_B);
    assertThat(result.getResponse().getContentAsString()).endsWith("}");
    assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("createdAt").asText())
        .endsWith("Z");

    Map<String, Object> row =
        jdbc.queryForMap("SELECT * FROM tenant.organization WHERE id = ?", id);
    assertThat(row.get("name")).isEqualTo(name);
    assertThat(row.get("country_code")).isEqualTo("CD");
    assertThat(row.get("default_locale")).isEqualTo("fr");
    assertThat(row.get("timezone")).isEqualTo("Africa/Kinshasa");
    assertThat(row.get("status")).isEqualTo("ACTIVE");
    assertThat(row.get("created_by")).isEqualTo("sub-create");
    assertThat(
            jdbc.queryForList(
                "SELECT currency_code FROM tenant.organization_currency WHERE organization_id = ? ORDER BY 1",
                String.class,
                id))
        .containsExactly("CDF", "USD");

    Map<String, Object> audit =
        jdbc.queryForMap("SELECT * FROM platform.audit_event WHERE resource_id = ?", id);
    assertThat(audit.get("actor_subject")).isEqualTo("sub-create");
    assertThat(audit.get("action")).isEqualTo("organization.create");
    assertThat(audit.get("result")).isEqualTo("SUCCESS");
    assertThat(audit.get("tenant_id")).isEqualTo(id);
    assertThat(audit.get("correlation_id")).isEqualTo("mvp001-create-0001");
    JsonNode metadata = JSON.readTree(audit.get("metadata").toString());
    assertThat(metadata.has("name")).isFalse();
    assertThat(metadata.get("countryCode").asText()).isEqualTo("CD");
    assertThat(audit.get("metadata").toString()).doesNotContain(name);

    String envelope =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?",
            String.class,
            id);
    assertEnvelopeValid(envelope);
    JsonNode event = JSON.readTree(envelope);
    assertThat(event.get("eventType").asText()).isEqualTo("tenant.organization-created.v1");
    assertThat(event.get("tenantId").asText()).isEqualTo(id.toString());
    assertThat(event.get("subject").asText()).isEqualTo(id.toString());
    assertThat(event.get("source").asText()).isEqualTo("core-api/tenant");
    assertThat(event.get("correlationId").asText()).isEqualTo("mvp001-create-0001");
    assertThat(event.has("causationId")).isTrue();
    assertThat(event.get("causationId").isNull()).isTrue();
    assertThat(event.get("eventTime").asText()).endsWith("Z");
    assertThat(event.get("data").get("organizationId").asText()).isEqualTo(id.toString());
    assertThat(event.get("data").has("name")).isFalse();
    assertThat(envelope).doesNotContain(name);
  }

  @Test
  void replayReturnsOriginalResponseWithoutNewRecords() throws Exception {
    String key = Organizations.newKey();
    String bearer = platformAdmin("sub-replay");
    String name = Organizations.uniqueName();
    double replayedBefore = count("replayed");
    String first =
        mvc.perform(create(bearer, key, Organizations.body(name)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // Semantically identical: surrounding spaces and currency order differ.
    String second =
        mvc.perform(
                create(
                    bearer,
                    key,
                    Organizations.body(
                        " " + name, "CD", "fr", "Africa/Kinshasa", "[\"CDF\",\"USD\"]")))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JSON.readTree(second)).isEqualTo(JSON.readTree(first));
    UUID id = UUID.fromString(JSON.readTree(first).get("id").asText());
    assertThat(countRows("tenant.organization", "name = ?", name)).isEqualTo(1);
    assertThat(countRows("platform.audit_event", "resource_id = ?", id)).isEqualTo(1);
    assertThat(countRows("platform.outbox_event", "tenant_id = ?", id)).isEqualTo(1);
    assertThat(count("replayed")).isEqualTo(replayedBefore + 1);
  }

  @Test
  void sameKeyWithDifferentPayloadIsRejected() throws Exception {
    String key = Organizations.newKey();
    String bearer = platformAdmin("sub-conflict");
    String name = Organizations.uniqueName();
    mvc.perform(create(bearer, key, Organizations.body(name))).andExpect(status().isCreated());
    mvc.perform(
            create(
                bearer, key, Organizations.body(name, "CD", "en", "Africa/Kinshasa", "[\"CDF\"]")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        .andExpect(jsonPath("$.params").isEmpty());
    assertThat(countRows("tenant.organization", "name = ?", name)).isEqualTo(1);
  }

  @Test
  void sameKeyFromAnotherPrincipalIsIndependent() throws Exception {
    String key = Organizations.newKey();
    mvc.perform(
            create(platformAdmin("sub-one"), key, Organizations.body(Organizations.uniqueName())))
        .andExpect(status().isCreated());
    mvc.perform(
            create(platformAdmin("sub-two"), key, Organizations.body(Organizations.uniqueName())))
        .andExpect(status().isCreated())
        .andExpect(header().doesNotExist("Idempotent-Replayed"));
  }

  @Test
  void tenantAdminAndEmployeeAreForbiddenBeforeTheBodyIsRead() throws Exception {
    double deniedBefore = count("denied");
    for (String role : List.of("tenant-admin", "employee")) {
      String bearer =
          "Bearer " + TestTokens.token().roles(List.of(role)).tenant(TestTokens.TENANT_A).build();
      mvc.perform(
              create(
                  bearer, Organizations.newKey(), Organizations.body(Organizations.uniqueName())))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      // Even a malformed body yields 403, proving authorization precedes parsing.
      mvc.perform(create(bearer, Organizations.newKey(), "{not json"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    assertThat(count("denied")).isEqualTo(deniedBefore + 4);
  }

  @Test
  void missingOrInvalidTokensAreUnauthenticated() throws Exception {
    mvc.perform(create(null, Organizations.newKey(), Organizations.body("Valid name")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    String wrongAudience =
        "Bearer " + TestTokens.token().audience("account").roles(List.of("platform-admin")).build();
    mvc.perform(create(wrongAudience, Organizations.newKey(), Organizations.body("Valid name")))
        .andExpect(status().isUnauthorized());
    String expired =
        "Bearer "
            + TestTokens.token()
                .roles(List.of("platform-admin"))
                .expiresAt(java.time.Instant.now().minusSeconds(600))
                .build();
    mvc.perform(create(expired, Organizations.newKey(), Organizations.body("Valid name")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tokenWithoutSubjectIsRefused() throws Exception {
    String bearer =
        "Bearer " + TestTokens.token().subject(null).roles(List.of("platform-admin")).build();
    mvc.perform(
            create(bearer, Organizations.newKey(), Organizations.body(Organizations.uniqueName())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void callerCannotChooseTheIdOrTenant() throws Exception {
    String body =
        """
        {"id":"%s","tenantId":"%s","name":"Chosen","countryCode":"CD","defaultLocale":"fr",
         "timezone":"Africa/Kinshasa","currencies":["CDF"]}
        """
            .formatted(TestTokens.TENANT_B, TestTokens.TENANT_B);
    mvc.perform(create(platformAdmin("sub-choose"), Organizations.newKey(), body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.params.fields[0].field").value("body"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("UNKNOWN_PROPERTY"));
    assertThat(countRows("tenant.organization", "id = ?", TestTokens.TENANT_B)).isZero();
  }

  @Test
  void validationFailuresUseStableCodesAndNeverEchoValues(CapturedOutput output) throws Exception {
    String bearer = platformAdmin("sub-validate");
    double failedBefore = count("validation_failed");
    mvc.perform(create(bearer, null, Organizations.body("Secret Clinic Name")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.params.fields[0].field").value("Idempotency-Key"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("REQUIRED"));
    mvc.perform(create(bearer, Organizations.newKey(), "{broken"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("body"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("FORMAT"));
    mvc.perform(
            create(
                bearer,
                Organizations.newKey(),
                Organizations.body("Secret Clinic Name", "CD", "fr", "Europe/Paris", "[\"CDF\"]")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TIMEZONE_NOT_SUPPORTED"))
        .andExpect(jsonPath("$.params.field").value("timezone"))
        .andExpect(jsonPath("$.params.supported[0]").value("Africa/Kinshasa"));
    MvcResult currency =
        mvc.perform(
                create(
                    bearer,
                    Organizations.newKey(),
                    Organizations.body(
                        "Secret Clinic Name", "CD", "fr", "Africa/Kinshasa", "[\"XAF\"]")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CURRENCY_NOT_SUPPORTED"))
            .andReturn();
    assertThat(currency.getResponse().getContentAsString()).doesNotContain("XAF", "Secret Clinic");
    assertThat(count("validation_failed")).isEqualTo(failedBefore + 4);
    assertThat(output.getAll()).doesNotContain("Secret Clinic Name");
  }

  @Test
  void logsNeverContainNameKeyOrToken(CapturedOutput output) throws Exception {
    String name = "Confidential Org " + UUID.randomUUID();
    String key = "log-check-" + UUID.randomUUID();
    String bearer = platformAdmin("sub-logs");
    mvc.perform(create(bearer, key, Organizations.body(name))).andExpect(status().isCreated());
    mvc.perform(create(bearer, key, Organizations.body(name))).andExpect(status().isCreated());
    mvc.perform(create(bearer, key, Organizations.body(name + " changed")))
        .andExpect(status().isConflict());
    assertThat(output.getAll())
        .contains("organization_created")
        .doesNotContain(name)
        .doesNotContain(key)
        .doesNotContain(bearer.substring("Bearer ".length()));
  }

  @Test
  void corsPreflightAllowsIdempotentBrowserRequests() throws Exception {
    mvc.perform(
            options("/api/v1/organizations")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header(
                    "Access-Control-Request-Headers",
                    "authorization,content-type,idempotency-key,x-correlation-id"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Headers",
                    org.hamcrest.Matchers.containsStringIgnoringCase("idempotency-key")));

    mvc.perform(
            create(
                    platformAdmin("sub-cors"),
                    Organizations.newKey(),
                    Organizations.body(Organizations.uniqueName()))
                .header("Origin", "http://localhost:5173"))
        .andExpect(status().isCreated())
        .andExpect(
            header()
                .string(
                    "Access-Control-Expose-Headers",
                    org.hamcrest.Matchers.containsString("Idempotent-Replayed")));

    mvc.perform(
            options("/api/v1/organizations")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }

  @Test
  void existingTenantScopedIsolationStillHolds() throws Exception {
    String bearer =
        "Bearer "
            + TestTokens.token()
                .roles(List.of("platform-admin"))
                .tenant(TestTokens.TENANT_A)
                .build();
    // A platform administrator gains no cross-tenant access to tenant-scoped resources.
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/test-support/tenants/{id}/probe", TestTokens.TENANT_B)
                .header("Authorization", bearer))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
  }

  private int countRows(String table, String where, Object arg) {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, arg);
    return value == null ? 0 : value;
  }

  static void assertEnvelopeValid(String envelope) throws Exception {
    Path schemaPath =
        Path.of(
            System.getProperty(
                "divalhr.eventEnvelopeSchemaPath",
                "../../packages/shared-contracts/schemas/event-envelope.schema.json"));
    JsonSchema schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(Files.readString(schemaPath));
    Set<ValidationMessage> errors = schema.validate(JSON.readTree(envelope));
    assertThat(errors).isEmpty();
  }
}
