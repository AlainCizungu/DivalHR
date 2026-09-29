package com.divalhr.core.tenant;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.bearer;
import static com.divalhr.core.support.Hierarchy.code;
import static com.divalhr.core.support.Hierarchy.create;
import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Hierarchy.region;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-002 Increment 3A region creation through HTTP, security, idempotency, audit, outbox and
 * PostgreSQL.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RegionApiIntegrationTest {

  private static final String PATH = "/api/v1/regions";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  private UUID tenant;
  private UUID legalEntity;

  @BeforeEach
  void hierarchy() throws Exception {
    tenant = Hierarchy.newTenant(mvc);
    legalEntity = Hierarchy.newLegalEntity(mvc, tenant, code("le"), "2026-01-01", null);
  }

  private double count(String outcome) {
    var counter =
        meters
            .find(OperationMetrics.METRIC)
            .tags("operation", "region.create", "outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private int regions(UUID tenantId) {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM tenant.region WHERE tenant_id = ?", Integer.class, tenantId);
    return value == null ? 0 : value;
  }

  @Test
  void tenantAdminCreatesRegionBeneathOwnLegalEntityWithAuditAndEvent() throws Exception {
    String code = code("kat");
    String name = "Région Grand Katanga et Haut-Lomami " + code;
    double created = count("created");
    String body =
        mvc.perform(
                create(
                        PATH,
                        bearer(tenant, "sub-region-create", "tenant-admin"),
                        Organizations.newKey(),
                        region(
                            legalEntity,
                            "  " + code.toLowerCase(Locale.ROOT) + " ",
                            "  " + name + " ",
                            "2026-02-01",
                            "2026-12-31"))
                    .header("X-Correlation-Id", "mvp002-region-0001"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.legalEntityId").value(legalEntity.toString()))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.effectiveFrom").value("2026-02-01"))
            .andExpect(jsonPath("$.effectiveTo").value("2026-12-31"))
            .andExpect(jsonPath("$.tenantId").doesNotExist())
            .andExpect(jsonPath("$.createdBy").doesNotExist())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(json(body).get("id").asText());
    assertThat(count("created")).isEqualTo(created + 1);

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM tenant.region WHERE id = ?", id);
    assertThat(row.get("tenant_id")).isEqualTo(tenant);
    assertThat(row.get("legal_entity_id")).isEqualTo(legalEntity);
    assertThat(row.get("code")).isEqualTo(code);
    assertThat(row.get("name")).isEqualTo(name);
    assertThat(row.get("created_by")).isEqualTo("sub-region-create");

    Map<String, Object> audit =
        jdbc.queryForMap("SELECT * FROM platform.audit_event WHERE resource_id = ?", id);
    assertThat(audit.get("action")).isEqualTo("region.create");
    assertThat(audit.get("resource_type")).isEqualTo("region");
    assertThat(audit.get("tenant_id")).isEqualTo(tenant);
    assertThat(audit.get("actor_subject")).isEqualTo("sub-region-create");
    assertThat(audit.get("correlation_id")).isEqualTo("mvp002-region-0001");
    JsonNode metadata = json(audit.get("metadata").toString());
    assertThat(metadata.get("legalEntityId").asText()).isEqualTo(legalEntity.toString());
    assertThat(metadata.get("code").asText()).isEqualTo(code);
    assertThat(metadata.get("effectiveFrom").asText()).isEqualTo("2026-02-01");
    assertThat(metadata.get("effectiveTo").asText()).isEqualTo("2026-12-31");
    assertThat(metadata.toString()).doesNotContain(name).doesNotContain("sub-region-create");

    String envelope =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
            String.class,
            id.toString());
    CreateOrganizationApiIntegrationTest.assertEnvelopeValid(envelope);
    JsonNode event = json(envelope);
    assertThat(event.get("eventType").asText()).isEqualTo("tenant.region-created.v1");
    assertThat(event.get("tenantId").asText()).isEqualTo(tenant.toString());
    assertThat(event.get("correlationId").asText()).isEqualTo("mvp002-region-0001");
    JsonNode data = event.get("data");
    assertThat(data.get("regionId").asText()).isEqualTo(id.toString());
    assertThat(data.get("legalEntityId").asText()).isEqualTo(legalEntity.toString());
    assertThat(data.get("code").asText()).isEqualTo(code);
    assertThat(data.get("effectiveTo").asText()).isEqualTo("2026-12-31");
    assertThat(data.has("name")).isFalse();
    assertThat(data.has("createdBy")).isFalse();
  }

  @Test
  void openEndedRegionOmitsEffectiveToFromAuditAndEventData() throws Exception {
    UUID id = Hierarchy.newRegion(mvc, tenant, legalEntity, code("open"), "2026-01-01", null);
    String metadata =
        jdbc.queryForObject(
            "SELECT metadata::text FROM platform.audit_event WHERE resource_id = ?",
            String.class,
            id);
    assertThat(json(metadata).has("effectiveTo")).isFalse();
    String envelope =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
            String.class,
            id.toString());
    assertThat(json(envelope).get("data").has("effectiveTo")).isFalse();
  }

  @Test
  void replayReturnsTheOriginalRegionAndKeyReuseConflicts() throws Exception {
    String key = Organizations.newKey();
    String code = code("rp");
    String body = region(legalEntity, code, "Rejeu", "2026-01-01", null);
    String first =
        mvc.perform(create(PATH, admin(tenant), key, body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    double replayed = count("replayed");
    String second =
        mvc.perform(create(PATH, admin(tenant), key, body))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json(second)).isEqualTo(json(first));
    assertThat(count("replayed")).isEqualTo(replayed + 1);

    mvc.perform(
            create(
                PATH, admin(tenant), key, region(legalEntity, code, "Autre", "2026-01-01", null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    UUID id = UUID.fromString(json(first).get("id").asText());
    assertThat(regions(tenant)).isEqualTo(1);
    Integer audits =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.audit_event WHERE resource_id = ?", Integer.class, id);
    Integer events =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
            Integer.class,
            id.toString());
    assertThat(audits).isEqualTo(1);
    assertThat(events).isEqualTo(1);
  }

  @Test
  void codesAreUniquePerTenantAcrossLegalEntitiesRegardlessOfCase() throws Exception {
    UUID second = Hierarchy.newLegalEntity(mvc, tenant, code("le2"), "2026-01-01", null);
    String code = code("dup");
    Hierarchy.newRegion(mvc, tenant, legalEntity, code, "2026-01-01", null);
    double duplicates = count("duplicate_conflict");
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                region(second, code.toLowerCase(Locale.ROOT), "Doublon", "2026-01-01", null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_REGION_CODE"))
        .andExpect(jsonPath("$.params.field").value("code"))
        .andExpect(
            content ->
                assertThat(content.getResponse().getContentAsString())
                    .doesNotContain(code)
                    .doesNotContain(code.toLowerCase(Locale.ROOT)));
    assertThat(count("duplicate_conflict")).isEqualTo(duplicates + 1);

    // The same code is free for another resource type and for another tenant.
    Hierarchy.newSite(mvc, tenant, legalEntity, code, "2026-01-01", null);
    UUID otherTenant = Hierarchy.newTenant(mvc);
    UUID otherParent = Hierarchy.newLegalEntity(mvc, otherTenant, code("o"), "2026-01-01", null);
    Hierarchy.newRegion(mvc, otherTenant, otherParent, code, "2026-01-01", null);
  }

  @ParameterizedTest(name = "legal entity {0}..{1}, region {2}..{3} -> {4}")
  @CsvSource(
      nullValues = "open",
      value = {
        // Inclusive boundaries and same-day periods.
        "2026-01-01, 2026-12-31, 2026-01-01, 2026-12-31, 201",
        "2026-01-01, 2026-12-31, 2026-01-01, 2026-01-01, 201",
        "2026-01-01, 2026-12-31, 2026-12-31, 2026-12-31, 201",
        "2026-06-15, 2026-06-15, 2026-06-15, 2026-06-15, 201",
        // Open and closed combinations.
        "2026-01-01, open, 2026-01-01, open, 201",
        "2026-01-01, open, 2030-01-01, 2031-12-31, 201",
        "2026-01-01, 2026-12-31, 2026-01-01, open, effectiveTo",
        // One day outside either end.
        "2026-01-01, 2026-12-31, 2025-12-31, 2026-06-30, effectiveFrom",
        "2026-01-01, 2026-12-31, 2026-06-01, 2027-01-01, effectiveTo",
        "2026-01-01, open, 2025-12-31, open, effectiveFrom",
      })
  void regionPeriodMustLieWithinTheLegalEntity(
      String parentFrom, String parentTo, String from, String to, String expected)
      throws Exception {
    UUID parent = Hierarchy.newLegalEntity(mvc, tenant, code("per"), parentFrom, parentTo);
    var result =
        mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                region(parent, code("r"), "Période", from, to)));
    if ("201".equals(expected)) {
      result.andExpect(status().isCreated());
    } else {
      result
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("REGION_PERIOD_OUTSIDE_LEGAL_ENTITY"))
          .andExpect(jsonPath("$.params.field").value(expected));
    }
  }

  @Test
  void foreignAndMissingLegalEntitiesAreIndistinguishable() throws Exception {
    UUID otherTenant = Hierarchy.newTenant(mvc);
    UUID foreignParent = Hierarchy.newLegalEntity(mvc, otherTenant, code("fx"), "2026-01-01", null);
    String code = code("iso");
    JsonNode foreign = notFound(region(foreignParent, code, "Intrusion", "2026-01-01", null));
    JsonNode missing = notFound(region(UUID.randomUUID(), code, "Intrusion", "2026-01-01", null));
    assertThat(SiteApiIntegrationTest.withoutCorrelationId(foreign))
        .isEqualTo(SiteApiIntegrationTest.withoutCorrelationId(missing));
    assertThat(foreign.toString())
        .doesNotContain(foreignParent.toString())
        .doesNotContain(otherTenant.toString());
    assertThat(regions(otherTenant)).isZero();
    assertThat(regions(tenant)).isZero();
  }

  @Test
  void validationUsesStableCodesAndRejectsCallerChosenIdentity(CapturedOutput output)
      throws Exception {
    String secret = "Nom Secret Confidentiel";
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                region("not-a-uuid", "x", secret + "\\u0007", "2026-02-30", "3000-01-01")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(
            jsonPath("$.params.fields[?(@.field=='legalEntityId')].constraint").value("FORMAT"))
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
                region(legalEntity, code("ord"), "Ordre", "2026-06-01", "2026-05-31")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EFFECTIVE_DATE_INVALID"))
        .andExpect(jsonPath("$.params.field").value("effectiveTo"));

    for (String property :
        List.of("id", "tenantId", "organizationId", "createdBy", "regionId", "extra")) {
      mvc.perform(
              create(
                  PATH,
                  admin(tenant),
                  Organizations.newKey(),
                  """
                  {"legalEntityId": "%s", "code": "OK-01", "name": "Valide",
                   "effectiveFrom": "2026-01-01", "%s": "%s"}
                  """
                      .formatted(legalEntity, property, UUID.randomUUID())))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.params.fields[0].field").value("body"))
          .andExpect(jsonPath("$.params.fields[0].constraint").value("UNKNOWN_PROPERTY"));
    }

    mvc.perform(
            create(
                PATH,
                admin(tenant),
                null,
                region(legalEntity, "NK-01", "Sans clé", "2026-01-01", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("Idempotency-Key"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("REQUIRED"));

    assertThat(output.getAll()).doesNotContain(secret).doesNotContain("not-a-uuid");
    assertThat(regions(tenant)).isZero();
  }

  @Test
  void headersAndBodiesCannotChooseTheTenant() throws Exception {
    UUID otherTenant = Hierarchy.newTenant(mvc);
    String body =
        """
        {"legalEntityId": "%s", "code": "%s", "name": "Tenant", "effectiveFrom": "2026-01-01",
         "tenantId": "%s"}
        """
            .formatted(legalEntity, code("tn"), otherTenant);
    mvc.perform(create(PATH, admin(tenant), Organizations.newKey(), body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].constraint").value("UNKNOWN_PROPERTY"));
    mvc.perform(
            create(
                    PATH,
                    admin(tenant),
                    Organizations.newKey(),
                    region(legalEntity, code("hd"), "En-tête", "2026-01-01", null))
                .header("X-Tenant-Id", otherTenant.toString()))
        .andExpect(status().isCreated());
    assertThat(regions(otherTenant)).isZero();
    assertThat(regions(tenant)).isEqualTo(1);
  }

  @Test
  void onlyTenantAdministratorsMayCreateRegions() throws Exception {
    double before = count("denied");
    for (String role : List.of("employee", "platform-admin")) {
      String caller = bearer(tenant, "sub-" + role, role);
      mvc.perform(
              create(
                  PATH,
                  caller,
                  Organizations.newKey(),
                  region(legalEntity, code("den"), "Refusé", "2026-01-01", null)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      mvc.perform(create(PATH, caller, Organizations.newKey(), "{not json"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    assertThat(count("denied")).isEqualTo(before + 4);
    mvc.perform(
            create(PATH, bearer(tenant, null, "tenant-admin"), Organizations.newKey(), "{not json"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(
            create(
                PATH,
                bearer(null, "sub-no-tenant", "tenant-admin"),
                Organizations.newKey(),
                "{not json"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));
    mvc.perform(create(PATH, null, Organizations.newKey(), "{}"))
        .andExpect(status().isUnauthorized());
    assertThat(regions(tenant)).isZero();
  }

  @Test
  void logsAndMetricsCarryNoIdentifiersOrValues(CapturedOutput output) throws Exception {
    String code = code("log");
    String name = "Nom Confidentiel " + code;
    String key = Organizations.newKey();
    String token = admin(tenant);
    mvc.perform(create(PATH, token, key, region(legalEntity, code, name, "2026-01-01", null)))
        .andExpect(status().isCreated());
    assertThat(output.getAll())
        .contains("region_created")
        .doesNotContain(name)
        .doesNotContain(key)
        .doesNotContain(token.substring("Bearer ".length()));
    for (Meter meter : meters.find(OperationMetrics.METRIC).meters()) {
      assertThat(meter.getId().getTags())
          .allSatisfy(
              tag -> {
                assertThat(tag.getKey()).isIn("operation", "outcome");
                assertThat(tag.getValue())
                    .doesNotContain(tenant.toString())
                    .doesNotContain(legalEntity.toString())
                    .doesNotContain(code);
              });
    }
  }

  private JsonNode notFound(String body) throws Exception {
    return json(
        mvc.perform(create(PATH, admin(tenant), Organizations.newKey(), body))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("LEGAL_ENTITY_NOT_FOUND"))
            .andExpect(jsonPath("$.params.field").value("legalEntityId"))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }
}
