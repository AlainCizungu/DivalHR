package com.divalhr.core.tenant;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.bearer;
import static com.divalhr.core.support.Hierarchy.code;
import static com.divalhr.core.support.Hierarchy.create;
import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Hierarchy.team;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-002 Increment 3B team creation through HTTP, security, idempotency, audit, outbox and
 * PostgreSQL. Every scenario runs for both parent types.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class TeamApiIntegrationTest {

  private static final String PATH = "/api/v1/teams";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  private UUID tenant;
  private UUID site;
  private UUID department;
  private UUID costCenter;

  @BeforeEach
  void hierarchy() throws Exception {
    tenant = Hierarchy.newTenant(mvc);
    UUID legalEntity = Hierarchy.newLegalEntity(mvc, tenant, code("le"), "2026-01-01", null);
    site = Hierarchy.newSite(mvc, tenant, legalEntity, code("st"), "2026-01-01", null);
    department =
        Hierarchy.newSiteUnit(
            mvc, "/api/v1/departments", tenant, site, code("dp"), "2026-01-01", "2026-12-31");
    costCenter =
        Hierarchy.newSiteUnit(
            mvc, "/api/v1/cost-centers", tenant, site, code("cc"), "2026-01-01", "2026-12-31");
  }

  private UUID parent(TeamParentResource resource) {
    return resource == TeamParentResource.DEPARTMENT ? department : costCenter;
  }

  private double count(String outcome) {
    var counter =
        meters
            .find(OperationMetrics.METRIC)
            .tags("operation", "team.create", "outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private int teams(UUID tenantId) {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM tenant.team WHERE tenant_id = ?", Integer.class, tenantId);
    return value == null ? 0 : value;
  }

  private String body(String parents, String code) {
    return """
    {%s"code": "%s", "name": "Équipe", "effectiveFrom": "2026-01-01",
     "effectiveTo": "2026-06-30"}
    """
        .formatted(parents, code);
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void tenantAdminCreatesTeamWithDerivedSiteAuditAndEvent(TeamParentResource resource)
      throws Exception {
    String code = code("eq");
    String name = "Équipe de maintenance électrique du Haut-Katanga " + code;
    double created = count("created");
    String body =
        mvc.perform(
                create(
                        PATH,
                        bearer(tenant, "sub-team-create-" + tenant, "tenant-admin"),
                        Organizations.newKey(),
                        team(
                            resource.field,
                            parent(resource),
                            " " + code.toLowerCase(Locale.ROOT) + " ",
                            "  " + name + " ",
                            "2026-02-01",
                            "2026-11-30"))
                    .header("X-Correlation-Id", "mvp002-team-00001"))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andExpect(jsonPath("$.siteId").value(site.toString()))
            .andExpect(jsonPath("$." + resource.field).value(parent(resource).toString()))
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
    // Both parent ids are always serialized; exactly one is non-null.
    assertThat(response.has(resource.otherField)).isTrue();
    assertThat(response.get(resource.otherField).isNull()).isTrue();
    UUID id = UUID.fromString(response.get("id").asText());
    assertThat(count("created")).isEqualTo(created + 1);

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM tenant.team WHERE id = ?", id);
    assertThat(row.get("tenant_id")).isEqualTo(tenant);
    assertThat(row.get("site_id")).isEqualTo(site);
    assertThat(row.get(resource.column)).isEqualTo(parent(resource));
    assertThat(row.get(resource.other().column)).isNull();
    assertThat(row.get("created_by")).isEqualTo("sub-team-create-" + tenant);

    Map<String, Object> audit =
        jdbc.queryForMap("SELECT * FROM platform.audit_event WHERE resource_id = ?", id);
    assertThat(audit.get("action")).isEqualTo("team.create");
    assertThat(audit.get("resource_type")).isEqualTo("team");
    assertThat(audit.get("tenant_id")).isEqualTo(tenant);
    assertThat(audit.get("correlation_id")).isEqualTo("mvp002-team-00001");
    JsonNode metadata = json(audit.get("metadata").toString());
    assertThat(metadata.get("siteId").asText()).isEqualTo(site.toString());
    assertThat(metadata.get("parentType").asText()).isEqualTo(resource.parentType);
    assertThat(metadata.get(resource.field).asText()).isEqualTo(parent(resource).toString());
    assertThat(metadata.has(resource.otherField)).isFalse();
    assertThat(metadata.get("code").asText()).isEqualTo(code);
    assertThat(metadata.toString())
        .doesNotContain(name)
        .doesNotContain("sub-team-create-" + tenant);

    String envelope =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
            String.class,
            id.toString());
    CreateOrganizationApiIntegrationTest.assertEnvelopeValid(envelope);
    JsonNode event = json(envelope);
    assertThat(event.get("eventType").asText()).isEqualTo("tenant.team-created.v1");
    assertThat(event.get("source").asText()).isEqualTo("core-api/tenant");
    assertThat(event.get("causationId").isNull()).isTrue();
    JsonNode data = event.get("data");
    assertThat(data.get("teamId").asText()).isEqualTo(id.toString());
    assertThat(data.get("siteId").asText()).isEqualTo(site.toString());
    assertThat(data.get("parentType").asText()).isEqualTo(resource.parentType);
    assertThat(data.get(resource.field).asText()).isEqualTo(parent(resource).toString());
    assertThat(data.get("effectiveTo").asText()).isEqualTo("2026-11-30");
    assertThat(data.has("name")).isFalse();
    assertThat(data.has("createdBy")).isFalse();
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void openEndedTeamOmitsEffectiveToFromAuditAndEventData(TeamParentResource resource)
      throws Exception {
    UUID openParent =
        Hierarchy.newSiteUnit(
            mvc, resource.parentPath, tenant, site, code("op"), "2026-01-01", null);
    UUID id =
        Hierarchy.newTeam(mvc, tenant, resource.field, openParent, code("o"), "2026-01-01", null);
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

  /** Issue #23 amendment 2: the exactly-one-parent matrix, enforced by the server. */
  @ParameterizedTest(name = "{0} -> {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "department UUID only | \"departmentId\": \"DEPT\", | 201",
        "cost-center UUID only | \"costCenterId\": \"COST\", | 201",
        "department UUID, cost center null | \"departmentId\": \"DEPT\", \"costCenterId\": null, |"
            + " 201",
        "cost-center UUID, department null | \"costCenterId\": \"COST\", \"departmentId\": null, |"
            + " 201",
        "both absent | '' | TEAM_PARENT_REQUIRED",
        "both explicit null | \"departmentId\": null, \"costCenterId\": null, |"
            + " TEAM_PARENT_REQUIRED",
        "both UUIDs | \"departmentId\": \"DEPT\", \"costCenterId\": \"COST\", |"
            + " TEAM_PARENT_AMBIGUOUS",
        "malformed department, cost center UUID | \"departmentId\": \"nope\", \"costCenterId\":"
            + " \"COST\", | VALIDATION_FAILED",
        "malformed department only | \"departmentId\": \"\", | VALIDATION_FAILED",
      })
  void exactlyOneParentIsRequired(String label, String parents, String expected) throws Exception {
    String fields =
        parents.replace("DEPT", department.toString()).replace("COST", costCenter.toString());
    var result =
        mvc.perform(create(PATH, admin(tenant), Organizations.newKey(), body(fields, code("x"))));
    if ("201".equals(expected)) {
      result.andExpect(status().isCreated());
      return;
    }
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(expected))
        .andExpect(
            content ->
                assertThat(content.getResponse().getContentAsString())
                    .doesNotContain(department.toString())
                    .doesNotContain(costCenter.toString())
                    .doesNotContain("nope"));
    if (!"VALIDATION_FAILED".equals(expected)) {
      result.andExpect(jsonPath("$.params").isEmpty());
    } else {
      result.andExpect(jsonPath("$.params.fields[0].field").value("departmentId"));
    }
    assertThat(teams(tenant)).isZero();
  }

  @Test
  void cardinalityFollowsFieldValidationAndPrecedesDateOrder() throws Exception {
    // Field defects are reported first, all together, even without a parent.
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                "{\"code\": \"x\", \"name\": \"Nom\", \"effectiveFrom\": \"2026-01-01\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.params.fields[0].field").value("code"));
    // Cardinality precedes date order.
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                """
                {"code": "OK-01", "name": "Nom", "effectiveFrom": "2026-06-01",
                 "effectiveTo": "2026-05-31"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TEAM_PARENT_REQUIRED"));
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                team("departmentId", department, "OK-02", "Nom", "2026-06-01", "2026-05-31")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EFFECTIVE_DATE_INVALID"))
        .andExpect(jsonPath("$.params.field").value("effectiveTo"));
    // A missing parent is reported before containment.
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                team("departmentId", UUID.randomUUID(), "OK-03", "Nom", "2025-01-01", null)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DEPARTMENT_NOT_FOUND"));
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void siteTenantAndServerFieldsCannotBeSupplied(TeamParentResource resource) throws Exception {
    UUID otherTenant = Hierarchy.newTenant(mvc);
    for (String property :
        List.of(
            "siteId",
            "tenantId",
            "organizationId",
            "legalEntityId",
            "regionId",
            "id",
            "createdBy",
            "extra")) {
      mvc.perform(
              create(
                  PATH,
                  admin(tenant),
                  Organizations.newKey(),
                  """
                  {"%s": "%s", "code": "OK-01", "name": "Valide", "effectiveFrom": "2026-01-01",
                   "effectiveTo": "2026-06-30", "%s": "%s"}
                  """
                      .formatted(resource.field, parent(resource), property, otherTenant)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.params.fields[0].field").value("body"))
          .andExpect(jsonPath("$.params.fields[0].constraint").value("UNKNOWN_PROPERTY"));
    }
    mvc.perform(
            create(
                    PATH,
                    admin(tenant),
                    Organizations.newKey(),
                    team(
                        resource.field,
                        parent(resource),
                        code("hd"),
                        "En-tête",
                        "2026-01-01",
                        "2026-06-30"))
                .header("X-Tenant-Id", otherTenant.toString()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.siteId").value(site.toString()));
    assertThat(teams(otherTenant)).isZero();
    assertThat(teams(tenant)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void missingAndForeignParentsAreIndistinguishable(TeamParentResource resource) throws Exception {
    UUID otherTenant = Hierarchy.newTenant(mvc);
    UUID otherLegal = Hierarchy.newLegalEntity(mvc, otherTenant, code("ol"), "2026-01-01", null);
    UUID otherSite =
        Hierarchy.newSite(mvc, otherTenant, otherLegal, code("os"), "2026-01-01", null);
    UUID foreignParent =
        Hierarchy.newSiteUnit(
            mvc, resource.parentPath, otherTenant, otherSite, code("fp"), "2026-01-01", null);
    String code = code("iso");
    JsonNode foreign =
        notFound(resource, team(resource.field, foreignParent, code, "Intrus", "2026-01-01", null));
    JsonNode missing =
        notFound(
            resource, team(resource.field, UUID.randomUUID(), code, "Intrus", "2026-01-01", null));
    assertThat(SiteApiIntegrationTest.withoutCorrelationId(foreign))
        .isEqualTo(SiteApiIntegrationTest.withoutCorrelationId(missing));
    assertThat(foreign.get("params").get("field").asText()).isEqualTo(resource.field);
    assertThat(foreign.toString())
        .doesNotContain(foreignParent.toString())
        .doesNotContain(otherTenant.toString());
    assertThat(teams(otherTenant)).isZero();
    assertThat(teams(tenant)).isZero();
  }

  @ParameterizedTest(name = "{0}: parent 2026-01-01..2026-12-31, team {1}..{2} -> {3}")
  @CsvSource(
      nullValues = "open",
      value = {
        "DEPARTMENT, 2026-01-01, 2026-12-31, 201",
        "DEPARTMENT, 2026-01-01, 2026-01-01, 201",
        "DEPARTMENT, 2026-12-31, 2026-12-31, 201",
        "DEPARTMENT, 2026-01-01, open, effectiveTo",
        "DEPARTMENT, 2025-12-31, 2026-06-30, effectiveFrom",
        "DEPARTMENT, 2026-06-01, 2027-01-01, effectiveTo",
        "COST_CENTER, 2026-01-01, 2026-12-31, 201",
        "COST_CENTER, 2026-06-15, 2026-06-15, 201",
        "COST_CENTER, 2026-01-01, open, effectiveTo",
        "COST_CENTER, 2025-12-31, 2026-06-30, effectiveFrom",
        "COST_CENTER, 2026-06-01, 2027-01-01, effectiveTo",
      })
  void teamPeriodMustLieWithinTheParent(
      TeamParentResource resource, String from, String to, String expected) throws Exception {
    var result =
        mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                team(resource.field, parent(resource), code("p"), "Période", from, to)));
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
  @EnumSource(TeamParentResource.class)
  void openEndedTeamUnderOpenEndedParent(TeamParentResource resource) throws Exception {
    UUID openParent =
        Hierarchy.newSiteUnit(
            mvc, resource.parentPath, tenant, site, code("op"), "2026-01-01", null);
    Hierarchy.newTeam(mvc, tenant, resource.field, openParent, code("o"), "2030-01-01", null);
  }

  @Test
  void codesAreUniquePerTenantAcrossParentsRegardlessOfCase() throws Exception {
    String code = code("dup");
    Hierarchy.newTeam(mvc, tenant, "departmentId", department, code, "2026-01-01", "2026-06-30");
    double duplicates = count("duplicate_conflict");
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                team(
                    "costCenterId",
                    costCenter,
                    code.toLowerCase(Locale.ROOT),
                    "Doublon",
                    "2026-01-01",
                    "2026-06-30")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_TEAM_CODE"))
        .andExpect(jsonPath("$.params.field").value("code"))
        .andExpect(
            content ->
                assertThat(content.getResponse().getContentAsString())
                    .doesNotContain(code)
                    .doesNotContain(code.toLowerCase(Locale.ROOT)));
    assertThat(count("duplicate_conflict")).isEqualTo(duplicates + 1);
    // Same code for another resource type and in another tenant.
    Hierarchy.newSiteUnit(mvc, "/api/v1/departments", tenant, site, code, "2026-01-01", null);
    UUID otherTenant = Hierarchy.newTenant(mvc);
    UUID otherLegal = Hierarchy.newLegalEntity(mvc, otherTenant, code("ol"), "2026-01-01", null);
    UUID otherSite =
        Hierarchy.newSite(mvc, otherTenant, otherLegal, code("os"), "2026-01-01", null);
    UUID otherDepartment =
        Hierarchy.newSiteUnit(
            mvc, "/api/v1/departments", otherTenant, otherSite, code("od"), "2026-01-01", null);
    Hierarchy.newTeam(mvc, otherTenant, "departmentId", otherDepartment, code, "2026-01-01", null);
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void replayReturnsTheOriginalTeamAndKeyReuseConflicts(TeamParentResource resource)
      throws Exception {
    String key = Organizations.newKey();
    String code = code("rp");
    String body = team(resource.field, parent(resource), code, "Rejeu", "2026-01-01", "2026-06-30");
    String first =
        mvc.perform(create(PATH, admin(tenant), key, body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // The same parent in the other JSON shape (other parent explicitly null) is the same payload.
    String sameParent =
        """
        {"%s": null, "%s": "%s", "code": "%s", "name": "Rejeu", "effectiveFrom": "2026-01-01",
         "effectiveTo": "2026-06-30"}
        """
            .formatted(resource.otherField, resource.field, parent(resource), code);
    String second =
        mvc.perform(create(PATH, admin(tenant), key, sameParent))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json(second)).isEqualTo(json(first));
    // Same key under the other parent type: a different payload.
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                key,
                team(
                    resource.otherField,
                    parent(resource.other()),
                    code,
                    "Rejeu",
                    "2026-01-01",
                    "2026-06-30")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    UUID id = UUID.fromString(json(first).get("id").asText());
    assertThat(teams(tenant)).isEqualTo(1);
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
  void validationUsesStableCodesAndConsumesNoKey(CapturedOutput output) throws Exception {
    String secret = "Nom Secret Confidentiel";
    String key = Organizations.newKey();
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                key,
                team(
                    "departmentId",
                    "not-a-uuid",
                    "x",
                    secret + "\\u0007",
                    "2026-02-30",
                    "3000-01-01")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(
            jsonPath("$.params.fields[?(@.field=='departmentId')].constraint").value("FORMAT"))
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
                null,
                team("departmentId", department, "NK-01", "Sans clé", "2026-01-01", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("Idempotency-Key"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("REQUIRED"));
    assertThat(output.getAll()).doesNotContain(secret).doesNotContain("not-a-uuid");
    Integer records =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?",
            Integer.class,
            key);
    assertThat(records).isZero();
    assertThat(teams(tenant)).isZero();
  }

  @Test
  void onlyTenantAdministratorsMayCreateTeams() throws Exception {
    double before = count("denied");
    for (String role : List.of("employee", "platform-admin")) {
      String caller = bearer(tenant, "sub-" + role, role);
      mvc.perform(
              create(
                  PATH,
                  caller,
                  Organizations.newKey(),
                  team("departmentId", department, code("den"), "Refusé", "2026-01-01", null)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      mvc.perform(create(PATH, caller, Organizations.newKey(), "{not json"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    assertThat(count("denied")).isEqualTo(before + 4);
    for (String subject : new String[] {null, "  "}) {
      mvc.perform(
              create(
                  PATH,
                  bearer(tenant, subject, "tenant-admin"),
                  Organizations.newKey(),
                  "{not json"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
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
    assertThat(teams(tenant)).isZero();
  }

  @Test
  void logsAndMetricsCarryNoIdentifiersOrValues(CapturedOutput output) throws Exception {
    String code = code("log");
    String name = "Nom Confidentiel " + code;
    String key = Organizations.newKey();
    String token = admin(tenant);
    mvc.perform(
            create(
                PATH,
                token,
                key,
                team("costCenterId", costCenter, code, name, "2026-01-01", "2026-06-30")))
        .andExpect(status().isCreated());
    assertThat(output.getAll())
        .contains("team_created")
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
                    .doesNotContain(costCenter.toString())
                    .doesNotContain(code);
              });
    }
  }

  private JsonNode notFound(TeamParentResource resource, String body) throws Exception {
    return json(
        mvc.perform(create(PATH, admin(tenant), Organizations.newKey(), body))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(resource.notFoundCode))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }
}
