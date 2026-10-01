package com.divalhr.core.tenant;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.bearer;
import static com.divalhr.core.support.Hierarchy.code;
import static com.divalhr.core.support.Hierarchy.create;
import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Hierarchy.legalEntity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** MVP-002 legal-entity creation through the real HTTP and security chain and PostgreSQL. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LegalEntityApiIntegrationTest {

  private static final String PATH = "/api/v1/legal-entities";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  private UUID tenant;

  @BeforeEach
  void newTenant() throws Exception {
    tenant = Hierarchy.newTenant(mvc);
  }

  private double count(String outcome) {
    var counter =
        meters
            .find(OperationMetrics.METRIC)
            .tags("operation", "legal-entity.create", "outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void tenantAdminCreatesLegalEntityInTheTokenTenant() throws Exception {
    String code = code("le");
    String name = "Société Générale de l'Équateur " + code;
    double createdBefore = count("created");
    String body =
        mvc.perform(
                create(
                        PATH,
                        bearer(tenant, "sub-le-create-" + tenant, "tenant-admin"),
                        Organizations.newKey(),
                        legalEntity("  " + code + " ", "  " + name + "  ", "2026-01-01", null))
                    .header("X-Correlation-Id", "mvp002-le-000001"))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andExpect(jsonPath("$.code").value(code.toUpperCase(java.util.Locale.ROOT)))
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.countryCode").value("CD"))
            .andExpect(jsonPath("$.effectiveFrom").value("2026-01-01"))
            .andExpect(jsonPath("$.effectiveTo").isEmpty())
            .andExpect(jsonPath("$.tenantId").doesNotExist())
            .andExpect(jsonPath("$.createdBy").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode response = json(body);
    assertThat(response.has("effectiveTo")).isTrue();
    assertThat(response.get("createdAt").asText()).endsWith("Z");
    UUID id = UUID.fromString(response.get("id").asText());

    Map<String, Object> row =
        jdbc.queryForMap("SELECT * FROM tenant.legal_entity WHERE id = ?", id);
    assertThat(row.get("tenant_id")).isEqualTo(tenant);
    assertThat(row.get("code")).isEqualTo(code.toUpperCase(java.util.Locale.ROOT));
    assertThat(row.get("name")).isEqualTo(name);
    assertThat(row.get("created_by")).isEqualTo("sub-le-create-" + tenant);
    assertThat(row.get("effective_to")).isNull();

    Map<String, Object> audit =
        jdbc.queryForMap("SELECT * FROM platform.audit_event WHERE resource_id = ?", id);
    assertThat(audit.get("action")).isEqualTo("legal-entity.create");
    assertThat(audit.get("resource_type")).isEqualTo("legal-entity");
    assertThat(audit.get("tenant_id")).isEqualTo(tenant);
    assertThat(audit.get("actor_subject")).isEqualTo("sub-le-create-" + tenant);
    assertThat(audit.get("correlation_id")).isEqualTo("mvp002-le-000001");
    assertThat(audit.get("metadata").toString()).doesNotContain(name).contains("CD");

    String envelope =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
            String.class,
            id.toString());
    CreateOrganizationApiIntegrationTest.assertEnvelopeValid(envelope);
    JsonNode event = json(envelope);
    assertThat(event.get("eventType").asText()).isEqualTo("tenant.legal-entity-created.v1");
    assertThat(event.get("tenantId").asText()).isEqualTo(tenant.toString());
    assertThat(event.get("causationId").isNull()).isTrue();
    assertThat(event.get("data").get("legalEntityId").asText()).isEqualTo(id.toString());
    assertThat(event.get("data").has("name")).isFalse();
    assertThat(envelope).doesNotContain(name);
    assertThat(count("created")).isEqualTo(createdBefore + 1);
  }

  @Test
  void replayReturnsTheOriginalResponseAndKeyReuseConflicts() throws Exception {
    String key = Organizations.newKey();
    String code = code("rp");
    String first =
        mvc.perform(
                create(
                    PATH, admin(tenant), key, legalEntity(code, "Replay SA", "2026-01-01", null)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        mvc.perform(
                create(
                    PATH,
                    admin(tenant),
                    key,
                    legalEntity(
                        code.toLowerCase(java.util.Locale.ROOT), " Replay SA", "2026-01-01", null)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json(second)).isEqualTo(json(first));
    assertThat(rows("tenant.legal_entity", tenant)).isEqualTo(1);

    mvc.perform(create(PATH, admin(tenant), key, legalEntity(code, "Other SA", "2026-01-01", null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        .andExpect(jsonPath("$.params").isEmpty());
  }

  @Test
  void codesAreUniquePerTenantRegardlessOfCase() throws Exception {
    String code = code("dup");
    double duplicatesBefore = count("duplicate_conflict");
    Hierarchy.newLegalEntity(mvc, tenant, code, "2026-01-01", null);
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                legalEntity(
                    code.toLowerCase(java.util.Locale.ROOT), "Doublon", "2026-01-01", null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_LEGAL_ENTITY_CODE"))
        .andExpect(jsonPath("$.params.field").value("code"))
        .andExpect(jsonPath("$.params.length()").value(1));
    assertThat(count("duplicate_conflict")).isEqualTo(duplicatesBefore + 1);

    // The same code is free in another tenant.
    UUID other = Hierarchy.newTenant(mvc);
    Hierarchy.newLegalEntity(mvc, other, code, "2026-01-01", null);
  }

  @Test
  void validationUsesStableCodesAndNeverEchoesValues(CapturedOutput output) throws Exception {
    String secret = "Secret Holding Name";
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                legalEntity("x", secret + "\\u0007", "2026-02-30", "3000-01-01")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.params.fields[?(@.field=='code')].constraint").value("LENGTH"))
        .andExpect(jsonPath("$.params.fields[?(@.field=='name')].constraint").value("FORMAT"))
        .andExpect(
            jsonPath("$.params.fields[?(@.field=='effectiveFrom')].constraint").value("FORMAT"))
        .andExpect(
            jsonPath("$.params.fields[?(@.field=='effectiveTo')].constraint").value("RANGE"));

    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                legalEntity(code("ord"), "Ordre inversé", "2026-06-01", "2026-05-31")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EFFECTIVE_DATE_INVALID"))
        .andExpect(jsonPath("$.params.field").value("effectiveTo"));

    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                """
                {"code": "FR-01", "name": "Hors RDC", "countryCode": "FR",
                 "effectiveFrom": "2026-01-01"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COUNTRY_NOT_SUPPORTED"))
        .andExpect(jsonPath("$.params.field").value("countryCode"));

    mvc.perform(
            create(
                PATH, admin(tenant), null, legalEntity(code("nk"), "No key", "2026-01-01", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("Idempotency-Key"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("REQUIRED"));

    assertThat(output.getAll()).doesNotContain(secret);
    assertThat(rows("tenant.legal_entity", tenant)).isZero();
  }

  @Test
  void callerCannotChooseTheTenant() throws Exception {
    UUID other = Hierarchy.newTenant(mvc);
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                """
                {"code": "TEN-01", "name": "Tentative", "countryCode": "CD",
                 "effectiveFrom": "2026-01-01", "tenantId": "%s"}
                """
                    .formatted(other)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("body"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("UNKNOWN_PROPERTY"));

    // A tenant header is ignored: the row lands in the token's tenant.
    String code = code("hdr");
    mvc.perform(
            create(
                    PATH,
                    admin(tenant),
                    Organizations.newKey(),
                    legalEntity(code, "Header ignored", "2026-01-01", null))
                .header("X-Tenant-Id", other.toString())
                .param("tenantId", other.toString()))
        .andExpect(status().isCreated());
    assertThat(
            jdbc.queryForObject(
                "SELECT tenant_id FROM tenant.legal_entity WHERE code = ?", UUID.class, code))
        .isEqualTo(tenant);
    assertThat(rows("tenant.legal_entity", other)).isZero();
  }

  @Test
  void onlyTenantAdministratorsMayCreateAndAuthorizationPrecedesParsing() throws Exception {
    double deniedBefore = count("denied");
    for (String role : List.of("employee", "platform-admin")) {
      String caller = bearer(tenant, "sub-" + role, role);
      mvc.perform(
              create(
                  PATH,
                  caller,
                  Organizations.newKey(),
                  legalEntity(code("den"), "Refusé", "2026-01-01", null)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      mvc.perform(create(PATH, caller, Organizations.newKey(), "{not json"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    assertThat(count("denied")).isEqualTo(deniedBefore + 4);
    assertThat(rows("tenant.legal_entity", tenant)).isZero();

    String noTenant =
        "Bearer " + TestTokens.token().tenant(null).roles(List.of("tenant-admin")).build();
    mvc.perform(create(PATH, noTenant, Organizations.newKey(), "{not json"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));

    mvc.perform(
            create(
                PATH, null, Organizations.newKey(), legalEntity("AB", "Anon", "2026-01-01", null)))
        .andExpect(status().isUnauthorized());

    String noSubject =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject(null)
                .roles(List.of("tenant-admin"))
                .build();
    mvc.perform(
            create(
                PATH,
                noSubject,
                Organizations.newKey(),
                legalEntity(code("sub"), "Sans sujet", "2026-01-01", null)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void tokenTenantWithoutOrganizationHasNoUsableContext() throws Exception {
    // TENANT_A has no organization in the test environment (development fixtures are absent), so
    // no membership can exist for it: since MVP-012A the membership gate denies the request before
    // the service would report the missing organization.
    mvc.perform(
            create(
                PATH,
                admin(TestTokens.TENANT_A),
                Organizations.newKey(),
                legalEntity(code("orp"), "Orpheline", "2026-01-01", null)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void logsAndMetricsCarryNoIdentifiersOrValues(CapturedOutput output) throws Exception {
    String code = code("log");
    String name = "Nom Confidentiel " + code;
    String key = Organizations.newKey();
    String token = admin(tenant);
    mvc.perform(create(PATH, token, key, legalEntity(code, name, "2026-01-01", null)))
        .andExpect(status().isCreated());
    assertThat(output.getAll())
        .doesNotContain(name)
        .doesNotContain(key)
        .doesNotContain(token.substring("Bearer ".length()));
    for (Meter meter : meters.find(OperationMetrics.METRIC).meters()) {
      assertThat(meter.getId().getTags())
          .allSatisfy(
              tag -> {
                assertThat(tag.getKey()).isIn("operation", "outcome");
                assertThat(tag.getValue()).doesNotContain(tenant.toString()).doesNotContain(code);
              });
    }
  }

  private int rows(String table, UUID tenantId) {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenantId);
    return value == null ? 0 : value;
  }
}
