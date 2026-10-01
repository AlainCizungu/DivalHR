package com.divalhr.core.tenant;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.bearer;
import static com.divalhr.core.support.Hierarchy.code;
import static com.divalhr.core.support.Hierarchy.create;
import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Hierarchy.siteUnit;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-002 Increment 2: department and cost-center creation through the real HTTP and security chain
 * and PostgreSQL. Every scenario runs for both resources.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SiteUnitApiIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  private UUID tenant;
  private UUID site;

  @BeforeEach
  void hierarchy() throws Exception {
    tenant = Hierarchy.newTenant(mvc);
    UUID legalEntity = Hierarchy.newLegalEntity(mvc, tenant, code("le"), "2026-01-01", null);
    site = Hierarchy.newSite(mvc, tenant, legalEntity, code("st"), "2026-01-01", "2026-12-31");
  }

  private double count(SiteUnitResource resource, String outcome) {
    var counter =
        meters
            .find(OperationMetrics.METRIC)
            .tags("operation", resource.createOperation(), "outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private int rows(String table, UUID tenantId) {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenantId);
    return value == null ? 0 : value;
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void tenantAdminCreatesBeneathOwnSiteWithAuditAndEvent(SiteUnitResource resource)
      throws Exception {
    String code = code("u");
    String name = "Département des Opérations Générales de l'Équateur " + code;
    double createdBefore = count(resource, "created");
    String body =
        mvc.perform(
                create(
                        resource.path,
                        bearer(tenant, "sub-unit-create-" + tenant, "tenant-admin"),
                        Organizations.newKey(),
                        siteUnit(
                            site,
                            "  " + code.toLowerCase(Locale.ROOT) + " ",
                            "  " + name + "  ",
                            "2026-02-01",
                            "2026-11-30"))
                    .header("X-Correlation-Id", "mvp002-unit-00001"))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andExpect(jsonPath("$.siteId").value(site.toString()))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.effectiveFrom").value("2026-02-01"))
            .andExpect(jsonPath("$.effectiveTo").value("2026-11-30"))
            .andExpect(jsonPath("$.tenantId").doesNotExist())
            .andExpect(jsonPath("$.createdBy").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode response = json(body);
    assertThat(response.get("createdAt").asText()).endsWith("Z");
    UUID id = UUID.fromString(response.get("id").asText());

    Map<String, Object> row =
        jdbc.queryForMap("SELECT * FROM " + resource.table + " WHERE id = ?", id);
    assertThat(row.get("tenant_id")).isEqualTo(tenant);
    assertThat(row.get("site_id")).isEqualTo(site);
    assertThat(row.get("code")).isEqualTo(code);
    assertThat(row.get("name")).isEqualTo(name);
    assertThat(row.get("created_by")).isEqualTo("sub-unit-create-" + tenant);

    List<Map<String, Object>> audits =
        jdbc.queryForList("SELECT * FROM platform.audit_event WHERE resource_id = ?", id);
    assertThat(audits).hasSize(1);
    Map<String, Object> audit = audits.get(0);
    assertThat(audit.get("action")).isEqualTo(resource.createOperation());
    assertThat(audit.get("resource_type")).isEqualTo(resource.resource);
    assertThat(audit.get("tenant_id")).isEqualTo(tenant);
    assertThat(audit.get("correlation_id")).isEqualTo("mvp002-unit-00001");
    assertThat(audit.get("metadata").toString()).doesNotContain(name).contains(site.toString());

    List<String> envelopes =
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
            String.class,
            id.toString());
    assertThat(envelopes).hasSize(1);
    String envelope = envelopes.get(0);
    CreateOrganizationApiIntegrationTest.assertEnvelopeValid(envelope);
    JsonNode event = json(envelope);
    assertThat(event.get("eventType").asText()).isEqualTo(resource.eventType());
    assertThat(event.get("tenantId").asText()).isEqualTo(tenant.toString());
    assertThat(event.get("source").asText()).isEqualTo("core-api/tenant");
    assertThat(event.get("causationId").isNull()).isTrue();
    assertThat(event.get("data").get(resource.idField).asText()).isEqualTo(id.toString());
    assertThat(event.get("data").get("siteId").asText()).isEqualTo(site.toString());
    assertThat(event.get("data").get("effectiveTo").asText()).isEqualTo("2026-11-30");
    assertThat(event.get("data").has("name")).isFalse();
    assertThat(envelope).doesNotContain(name);
    assertThat(count(resource, "created")).isEqualTo(createdBefore + 1);
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void openEndedPeriodOmitsEffectiveToFromEventData(SiteUnitResource resource) throws Exception {
    UUID openLegal = Hierarchy.newLegalEntity(mvc, tenant, code("ol"), "2026-01-01", null);
    UUID openSite = Hierarchy.newSite(mvc, tenant, openLegal, code("os"), "2026-01-01", null);
    UUID id =
        Hierarchy.newSiteUnit(mvc, resource.path, tenant, openSite, code("op"), "2026-01-01", null);
    JsonNode event =
        json(
            jdbc.queryForObject(
                "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
                String.class,
                id.toString()));
    assertThat(event.get("data").has("effectiveTo")).isFalse();
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void replayReturnsTheOriginalResponseAndKeyReuseConflicts(SiteUnitResource resource)
      throws Exception {
    String key = Organizations.newKey();
    String code = code("rp");
    String first =
        mvc.perform(
                create(
                    resource.path,
                    admin(tenant),
                    key,
                    siteUnit(site, code, "Rejeu", "2026-01-01", "2026-12-31")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        mvc.perform(
                create(
                    resource.path,
                    admin(tenant),
                    key,
                    siteUnit(
                        site,
                        code.toLowerCase(Locale.ROOT),
                        " Rejeu ",
                        "2026-01-01",
                        "2026-12-31")))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json(second)).isEqualTo(json(first));
    assertThat(rows(resource.table, tenant)).isEqualTo(1);

    mvc.perform(
            create(
                resource.path,
                admin(tenant),
                key,
                siteUnit(site, code, "Autre", "2026-01-01", "2026-12-31")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        .andExpect(jsonPath("$.params").isEmpty());
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void codesAreUniquePerTenantAndTypeRegardlessOfCase(SiteUnitResource resource) throws Exception {
    String code = code("dup");
    double before = count(resource, "duplicate_conflict");
    Hierarchy.newSiteUnit(mvc, resource.path, tenant, site, code, "2026-01-01", "2026-12-31");
    mvc.perform(
            create(
                resource.path,
                admin(tenant),
                Organizations.newKey(),
                siteUnit(
                    site, code.toLowerCase(Locale.ROOT), "Doublon", "2026-01-01", "2026-12-31")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(resource.duplicateCode))
        .andExpect(jsonPath("$.params.field").value("code"))
        .andExpect(jsonPath("$.params.length()").value(1));
    assertThat(count(resource, "duplicate_conflict")).isEqualTo(before + 1);

    // The other resource type may use the same code.
    SiteUnitResource other =
        resource == SiteUnitResource.DEPARTMENT
            ? SiteUnitResource.COST_CENTER
            : SiteUnitResource.DEPARTMENT;
    Hierarchy.newSiteUnit(mvc, other.path, tenant, site, code, "2026-01-01", "2026-12-31");

    // Another tenant may use the same code.
    UUID otherTenant = Hierarchy.newTenant(mvc);
    UUID otherLegal = Hierarchy.newLegalEntity(mvc, otherTenant, code("ol"), "2026-01-01", null);
    UUID otherSite =
        Hierarchy.newSite(mvc, otherTenant, otherLegal, code("os"), "2026-01-01", null);
    Hierarchy.newSiteUnit(mvc, resource.path, otherTenant, otherSite, code, "2026-01-01", null);
  }

  static Stream<Arguments> containment() {
    return Stream.of(SiteUnitResource.values())
        .flatMap(
            resource ->
                Stream.of(
                    // site 2026-01-01..2026-12-31 (from @BeforeEach)
                    Arguments.of(resource, "2026-01-01", "2026-12-31", "201"),
                    Arguments.of(resource, "2026-01-01", "2026-01-01", "201"),
                    Arguments.of(resource, "2026-12-31", "2026-12-31", "201"),
                    Arguments.of(resource, "2025-12-31", "2026-06-30", "effectiveFrom"),
                    Arguments.of(resource, "2026-06-01", "2027-01-01", "effectiveTo"),
                    Arguments.of(resource, "2026-01-01", null, "effectiveTo")));
  }

  @ParameterizedTest(name = "{0} {1}..{2} -> {3}")
  @MethodSource("containment")
  void periodMustLieWithinTheSite(
      SiteUnitResource resource, String from, String to, String expected) throws Exception {
    var result =
        mvc.perform(
            create(
                resource.path,
                admin(tenant),
                Organizations.newKey(),
                siteUnit(site, code("p"), "Période", from, to)));
    if ("201".equals(expected)) {
      result.andExpect(status().isCreated());
    } else {
      result
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(resource.periodCode))
          .andExpect(jsonPath("$.params.field").value(expected));
    }
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void openEndedChildNeedsOpenEndedSite(SiteUnitResource resource) throws Exception {
    UUID openLegal = Hierarchy.newLegalEntity(mvc, tenant, code("ol"), "2026-01-01", null);
    UUID openSite = Hierarchy.newSite(mvc, tenant, openLegal, code("os"), "2026-01-01", null);
    Hierarchy.newSiteUnit(mvc, resource.path, tenant, openSite, code("o1"), "2026-01-01", null);
    Hierarchy.newSiteUnit(
        mvc, resource.path, tenant, openSite, code("o2"), "2099-01-01", "2099-01-01");
    mvc.perform(
            create(
                resource.path,
                admin(tenant),
                Organizations.newKey(),
                siteUnit(openSite, code("o3"), "Avant", "2025-12-31", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(resource.periodCode))
        .andExpect(jsonPath("$.params.field").value("effectiveFrom"));
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void foreignAndMissingSitesAreIndistinguishable(SiteUnitResource resource) throws Exception {
    UUID otherTenant = Hierarchy.newTenant(mvc);
    UUID otherLegal = Hierarchy.newLegalEntity(mvc, otherTenant, code("fl"), "2026-01-01", null);
    UUID foreignSite =
        Hierarchy.newSite(mvc, otherTenant, otherLegal, code("fs"), "2026-01-01", null);
    String code = code("iso");
    JsonNode foreign =
        notFound(resource, siteUnit(foreignSite, code, "Intrusion", "2026-01-01", null));
    JsonNode missing =
        notFound(resource, siteUnit(UUID.randomUUID(), code, "Intrusion", "2026-01-01", null));
    assertThat(SiteApiIntegrationTest.withoutCorrelationId(foreign))
        .isEqualTo(SiteApiIntegrationTest.withoutCorrelationId(missing));
    assertThat(foreign.toString())
        .doesNotContain(foreignSite.toString())
        .doesNotContain(otherTenant.toString());
    assertThat(rows(resource.table, otherTenant)).isZero();
    assertThat(rows(resource.table, tenant)).isZero();
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void validationUsesStableCodesAndRejectsCallerChosenIdentity(
      SiteUnitResource resource, CapturedOutput output) throws Exception {
    String secret = "Nom Secret Confidentiel";
    mvc.perform(
            create(
                resource.path,
                admin(tenant),
                Organizations.newKey(),
                siteUnit("not-a-uuid", "x", secret + "\\u0007", "2026-02-30", "3000-01-01")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.params.fields[?(@.field=='siteId')].constraint").value("FORMAT"))
        .andExpect(jsonPath("$.params.fields[?(@.field=='code')].constraint").value("LENGTH"))
        .andExpect(jsonPath("$.params.fields[?(@.field=='name')].constraint").value("FORMAT"))
        .andExpect(
            jsonPath("$.params.fields[?(@.field=='effectiveFrom')].constraint").value("FORMAT"))
        .andExpect(
            jsonPath("$.params.fields[?(@.field=='effectiveTo')].constraint").value("RANGE"));

    mvc.perform(
            create(
                resource.path,
                admin(tenant),
                Organizations.newKey(),
                siteUnit(site, code("ord"), "Ordre", "2026-06-01", "2026-05-31")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EFFECTIVE_DATE_INVALID"))
        .andExpect(jsonPath("$.params.field").value("effectiveTo"));

    for (String property :
        List.of("id", "tenantId", "organizationId", "legalEntityId", "createdBy", "extra")) {
      mvc.perform(
              create(
                  resource.path,
                  admin(tenant),
                  Organizations.newKey(),
                  """
                  {"siteId": "%s", "code": "OK-01", "name": "Valide", "effectiveFrom": "2026-01-01",
                   "%s": "%s"}
                  """
                      .formatted(site, property, UUID.randomUUID())))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.params.fields[0].field").value("body"))
          .andExpect(jsonPath("$.params.fields[0].constraint").value("UNKNOWN_PROPERTY"));
    }

    mvc.perform(
            create(
                resource.path,
                admin(tenant),
                null,
                siteUnit(site, "NK-01", "Sans clé", "2026-01-01", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("Idempotency-Key"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("REQUIRED"));

    assertThat(output.getAll()).doesNotContain(secret).doesNotContain("not-a-uuid");
    assertThat(rows(resource.table, tenant)).isZero();
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void onlyTenantAdministratorsMayCreate(SiteUnitResource resource) throws Exception {
    double before = count(resource, "denied");
    for (String role : List.of("employee", "platform-admin")) {
      String caller = bearer(tenant, "sub-" + role, role);
      mvc.perform(
              create(
                  resource.path,
                  caller,
                  Organizations.newKey(),
                  siteUnit(site, code("den"), "Refusé", "2026-01-01", null)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      mvc.perform(create(resource.path, caller, Organizations.newKey(), "{not json"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    assertThat(count(resource, "denied")).isEqualTo(before + 4);
    mvc.perform(
            create(
                resource.path,
                bearer(tenant, null, "tenant-admin"),
                Organizations.newKey(),
                "{not json"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(
            create(
                resource.path,
                bearer(null, "sub-no-tenant", "tenant-admin"),
                Organizations.newKey(),
                "{not json"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));
    mvc.perform(create(resource.path, null, Organizations.newKey(), "{}"))
        .andExpect(status().isUnauthorized());
    assertThat(rows(resource.table, tenant)).isZero();
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void logsAndMetricsCarryNoIdentifiersOrValues(SiteUnitResource resource, CapturedOutput output)
      throws Exception {
    String code = code("log");
    String name = "Nom Confidentiel " + code;
    String key = Organizations.newKey();
    String token = admin(tenant);
    mvc.perform(
            create(
                resource.path, token, key, siteUnit(site, code, name, "2026-01-01", "2026-12-31")))
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
                assertThat(tag.getValue())
                    .doesNotContain(tenant.toString())
                    .doesNotContain(site.toString())
                    .doesNotContain(code);
              });
    }
  }

  private JsonNode notFound(SiteUnitResource resource, String body) throws Exception {
    return json(
        mvc.perform(create(resource.path, admin(tenant), Organizations.newKey(), body))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SITE_NOT_FOUND"))
            .andExpect(jsonPath("$.params.field").value("siteId"))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }
}
