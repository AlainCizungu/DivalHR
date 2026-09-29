package com.divalhr.core.tenant;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.assign;
import static com.divalhr.core.support.Hierarchy.assignment;
import static com.divalhr.core.support.Hierarchy.bearer;
import static com.divalhr.core.support.Hierarchy.code;
import static com.divalhr.core.support.Hierarchy.create;
import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Hierarchy.list;
import static com.divalhr.core.support.Hierarchy.siteInRegion;
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
 * MVP-002 Increment 3A: sites created with an optional region, and the first region assignment of
 * an existing site ({@code PUT /api/v1/sites/{siteId}/region}), through HTTP, security,
 * idempotency, audit, outbox and PostgreSQL.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SiteRegionApiIntegrationTest {

  private static final String SITES = "/api/v1/sites";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  private UUID tenant;
  private UUID legalEntity;
  private UUID region;

  @BeforeEach
  void hierarchy() throws Exception {
    tenant = Hierarchy.newTenant(mvc);
    legalEntity = Hierarchy.newLegalEntity(mvc, tenant, code("le"), "2026-01-01", null);
    region = Hierarchy.newRegion(mvc, tenant, legalEntity, code("rg"), "2026-01-01", "2026-12-31");
  }

  private double count(String operation, String outcome) {
    var counter =
        meters
            .find(OperationMetrics.METRIC)
            .tags("operation", operation, "outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private int rows(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private int audits(UUID resource, String action) {
    return rows(
        "SELECT count(*) FROM platform.audit_event WHERE resource_id = ? AND action = ?",
        resource,
        action);
  }

  private int events(UUID subject, String type) {
    return rows(
        "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'subject' = ?"
            + " AND event_type = ?",
        subject.toString(),
        type);
  }

  private Object storedRegion(UUID site) {
    return jdbc.queryForObject(
        "SELECT region_id FROM tenant.site WHERE id = ?", Object.class, site);
  }

  private UUID unassignedSite(String from, String to) throws Exception {
    return Hierarchy.newSite(mvc, tenant, legalEntity, code("st"), from, to);
  }

  // ------------------------------------------------------------------------------------------
  // Site creation with an optional region.
  // ------------------------------------------------------------------------------------------

  @Test
  void createsASiteInARegionWithOneSiteCreateAuditAndEvent() throws Exception {
    String code = code("in");
    String body =
        mvc.perform(
                create(
                    SITES,
                    admin(tenant),
                    Organizations.newKey(),
                    siteInRegion(legalEntity, region, code, "2026-02-01", "2026-11-30")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.regionId").value(region.toString()))
            .andExpect(jsonPath("$.legalEntityId").value(legalEntity.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID site = UUID.fromString(json(body).get("id").asText());
    assertThat(storedRegion(site)).isEqualTo(region);

    assertThat(audits(site, "site.create")).isEqualTo(1);
    assertThat(audits(site, "site.region.assign")).isZero();
    String metadata =
        jdbc.queryForObject(
            "SELECT metadata::text FROM platform.audit_event WHERE resource_id = ?",
            String.class,
            site);
    assertThat(json(metadata).get("regionId").asText()).isEqualTo(region.toString());

    assertThat(events(site, "tenant.site-created.v1")).isEqualTo(1);
    assertThat(events(site, "tenant.site-region-assigned.v1")).isZero();
    String envelope =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
            String.class,
            site.toString());
    CreateOrganizationApiIntegrationTest.assertEnvelopeValid(envelope);
    JsonNode data = json(envelope).get("data");
    assertThat(data.get("regionId").asText()).isEqualTo(region.toString());
    assertThat(data.get("legalEntityId").asText()).isEqualTo(legalEntity.toString());
  }

  @Test
  void aSiteWithoutARegionKeepsTheExistingShapeAndSerializesANullRegion() throws Exception {
    for (String body :
        List.of(
            Hierarchy.site(
                legalEntity, code("n1"), "Sans région", "Africa/Kinshasa", "2026-01-01", null),
            siteInRegion(legalEntity, null, code("n2"), "2026-01-01", null))) {
      String response =
          mvc.perform(create(SITES, admin(tenant), Organizations.newKey(), body))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse()
              .getContentAsString();
      JsonNode site = json(response);
      assertThat(site.has("regionId")).isTrue();
      assertThat(site.get("regionId").isNull()).isTrue();
      UUID id = UUID.fromString(site.get("id").asText());
      String envelope =
          jdbc.queryForObject(
              "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
              String.class,
              id.toString());
      assertThat(json(envelope).get("data").has("regionId")).isFalse();
      String metadata =
          jdbc.queryForObject(
              "SELECT metadata::text FROM platform.audit_event WHERE resource_id = ?",
              String.class,
              id);
      assertThat(json(metadata).has("regionId")).isFalse();
    }
  }

  @Test
  void siteCreationRejectsMissingForeignMismatchedAndNonContainingRegions() throws Exception {
    UUID otherTenant = Hierarchy.newTenant(mvc);
    UUID otherParent = Hierarchy.newLegalEntity(mvc, otherTenant, code("ox"), "2026-01-01", null);
    UUID foreignRegion =
        Hierarchy.newRegion(mvc, otherTenant, otherParent, code("fr"), "2026-01-01", null);
    JsonNode foreign =
        siteProblem(
            siteInRegion(legalEntity, foreignRegion, code("f"), "2026-01-01", "2026-06-30"),
            404,
            "REGION_NOT_FOUND");
    JsonNode missing =
        siteProblem(
            siteInRegion(legalEntity, UUID.randomUUID(), code("m"), "2026-01-01", "2026-06-30"),
            404,
            "REGION_NOT_FOUND");
    assertThat(SiteApiIntegrationTest.withoutCorrelationId(foreign))
        .isEqualTo(SiteApiIntegrationTest.withoutCorrelationId(missing));
    assertThat(foreign.get("params").get("field").asText()).isEqualTo("regionId");
    assertThat(foreign.toString())
        .doesNotContain(foreignRegion.toString())
        .doesNotContain(otherTenant.toString());

    UUID otherLegalEntity = Hierarchy.newLegalEntity(mvc, tenant, code("le2"), "2026-01-01", null);
    UUID otherRegion =
        Hierarchy.newRegion(mvc, tenant, otherLegalEntity, code("r2"), "2026-01-01", null);
    JsonNode mismatch =
        siteProblem(
            siteInRegion(legalEntity, otherRegion, code("mm"), "2026-01-01", "2026-06-30"),
            400,
            "SITE_REGION_LEGAL_ENTITY_MISMATCH");
    assertThat(mismatch.get("params").get("field").asText()).isEqualTo("regionId");

    // The region ends 2026-12-31: an open-ended site and one starting before it are outside.
    for (String[] period :
        List.of(new String[] {"2026-01-01", null}, new String[] {"2026-06-01", "2027-01-01"})) {
      JsonNode outside =
          siteProblem(
              siteInRegion(legalEntity, region, code("out"), period[0], period[1]),
              400,
              "SITE_PERIOD_OUTSIDE_REGION");
      assertThat(outside.get("params").get("field").asText()).isEqualTo("regionId");
    }

    // Precedence without changing the pre-region order: a missing legal entity wins.
    siteProblem(
        siteInRegion(UUID.randomUUID(), UUID.randomUUID(), code("pr"), "2026-01-01", null),
        404,
        "LEGAL_ENTITY_NOT_FOUND");
    // A malformed region id is a validation error, and never echoed.
    mvc.perform(
            create(
                SITES,
                admin(tenant),
                Organizations.newKey(),
                siteInRegion(legalEntity, "not-a-region", code("fm"), "2026-01-01", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("regionId"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("FORMAT"))
        .andExpect(
            content ->
                assertThat(content.getResponse().getContentAsString())
                    .doesNotContain("not-a-region"));
    assertThat(rows("SELECT count(*) FROM tenant.site WHERE tenant_id = ?", tenant)).isZero();
  }

  // ------------------------------------------------------------------------------------------
  // First assignment of an existing site.
  // ------------------------------------------------------------------------------------------

  @Test
  void assignsARegionToAnUnassignedSiteWithAuditAndEvent(CapturedOutput output) throws Exception {
    UUID site = unassignedSite("2026-03-01", "2026-09-30");
    UUID department =
        Hierarchy.newSiteUnit(
            mvc, "/api/v1/departments", tenant, site, code("d"), "2026-03-01", "2026-09-30");
    double updated = count("site.region.assign", "updated");
    String key = Organizations.newKey();
    String body =
        mvc.perform(
                assign(site, bearer(tenant, "sub-assign", "tenant-admin"), key, assignment(region))
                    .header("X-Correlation-Id", "mvp002-assign-001"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andExpect(jsonPath("$.id").value(site.toString()))
            .andExpect(jsonPath("$.regionId").value(region.toString()))
            .andExpect(jsonPath("$.legalEntityId").value(legalEntity.toString()))
            .andExpect(jsonPath("$.tenantId").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json(body).get("effectiveFrom").asText()).isEqualTo("2026-03-01");
    assertThat(count("site.region.assign", "updated")).isEqualTo(updated + 1);

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM tenant.site WHERE id = ?", site);
    assertThat(row.get("region_id")).isEqualTo(region);
    assertThat(row.get("legal_entity_id")).isEqualTo(legalEntity);
    assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);

    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT * FROM platform.audit_event WHERE resource_id = ? AND action = ?",
            site,
            "site.region.assign");
    assertThat(audit.get("resource_type")).isEqualTo("site");
    assertThat(audit.get("actor_subject")).isEqualTo("sub-assign");
    assertThat(audit.get("tenant_id")).isEqualTo(tenant);
    assertThat(audit.get("correlation_id")).isEqualTo("mvp002-assign-001");
    JsonNode metadata = json(audit.get("metadata").toString());
    assertThat(metadata.get("regionId").asText()).isEqualTo(region.toString());
    assertThat(metadata.get("legalEntityId").asText()).isEqualTo(legalEntity.toString());
    assertThat(metadata.has("name")).isFalse();

    String envelope =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?"
                + " AND event_type = 'tenant.site-region-assigned.v1'",
            String.class,
            site.toString());
    CreateOrganizationApiIntegrationTest.assertEnvelopeValid(envelope);
    JsonNode event = json(envelope);
    assertThat(event.get("tenantId").asText()).isEqualTo(tenant.toString());
    assertThat(event.get("correlationId").asText()).isEqualTo("mvp002-assign-001");
    JsonNode data = event.get("data");
    assertThat(data.get("siteId").asText()).isEqualTo(site.toString());
    assertThat(data.get("regionId").asText()).isEqualTo(region.toString());
    assertThat(data.get("legalEntityId").asText()).isEqualTo(legalEntity.toString());
    assertThat(data.get("assignedAt").asText()).endsWith("Z");
    assertThat(data.has("name")).isFalse();

    Map<String, Object> record =
        jdbc.queryForMap(
            "SELECT state, response_status, resource_id FROM platform.idempotency_record"
                + " WHERE operation = 'site.region.assign' AND idempotency_key = ?",
            key);
    assertThat(record.get("state")).isEqualTo("COMPLETED");
    assertThat(record.get("response_status")).isEqualTo(200);
    assertThat(record.get("resource_id")).isEqualTo(site);

    assertThat(output.getAll())
        .contains("site_region_assigned")
        .doesNotContain("site_region_created")
        .doesNotContain("site_region_assign_created")
        .doesNotContain(key);

    // Departments and cost centers keep working beneath the assigned site.
    assertThat(
            rows(
                "SELECT count(*) FROM tenant.department WHERE id = ? AND site_id = ?",
                department,
                site))
        .isEqualTo(1);
    Hierarchy.newSiteUnit(
        mvc, "/api/v1/cost-centers", tenant, site, code("c"), "2026-04-01", "2026-09-30");
  }

  @Test
  void replayOfTheSameKeyReturnsTheOriginalAndKeyReuseConflicts() throws Exception {
    UUID site = unassignedSite("2026-02-01", "2026-03-31");
    UUID other = unassignedSite("2026-02-01", "2026-03-31");
    String key = Organizations.newKey();
    String first =
        mvc.perform(assign(site, admin(tenant), key, assignment(region)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    double replayed = count("site.region.assign", "replayed");
    String second =
        mvc.perform(assign(site, admin(tenant), key, assignment(region)))
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json(second)).isEqualTo(json(first));
    assertThat(count("site.region.assign", "replayed")).isEqualTo(replayed + 1);

    UUID secondRegion =
        Hierarchy.newRegion(mvc, tenant, legalEntity, code("r2"), "2026-01-01", null);
    // Same key, other region (same site) and same region (other site): different payloads.
    mvc.perform(assign(site, admin(tenant), key, assignment(secondRegion)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    mvc.perform(assign(other, admin(tenant), key, assignment(region)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(storedRegion(other)).isNull();
    assertThat(audits(site, "site.region.assign")).isEqualTo(1);
    assertThat(events(site, "tenant.site-region-assigned.v1")).isEqualTo(1);
  }

  @Test
  void sameRegionWithANewKeyReturnsTheSiteAndRecordsNothingNew(CapturedOutput output)
      throws Exception {
    UUID site = unassignedSite("2026-02-01", "2026-03-31");
    String first =
        mvc.perform(assign(site, admin(tenant), Organizations.newKey(), assignment(region)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    double unchanged = count("site.region.assign", "unchanged");
    String key = Organizations.newKey();
    String second =
        mvc.perform(assign(site, admin(tenant), key, assignment(region)))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json(second)).isEqualTo(json(first));
    assertThat(count("site.region.assign", "unchanged")).isEqualTo(unchanged + 1);
    assertThat(audits(site, "site.region.assign")).isEqualTo(1);
    assertThat(events(site, "tenant.site-region-assigned.v1")).isEqualTo(1);
    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM tenant.site WHERE id = ?", site);
    assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);

    // The new key is consumed: completed, 200, replayable with the replay header.
    Map<String, Object> record =
        jdbc.queryForMap(
            "SELECT state, response_status, resource_id FROM platform.idempotency_record"
                + " WHERE operation = 'site.region.assign' AND idempotency_key = ?",
            key);
    assertThat(record.get("state")).isEqualTo("COMPLETED");
    assertThat(record.get("response_status")).isEqualTo(200);
    assertThat(record.get("resource_id")).isEqualTo(site);
    mvc.perform(assign(site, admin(tenant), key, assignment(region)))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"));
    assertThat(output.getAll()).contains("site_region_assign_unchanged");

    // A site created in the region behaves the same way.
    UUID created =
        Hierarchy.newSiteInRegion(
            mvc, tenant, legalEntity, region, code("c"), "2026-05-01", "2026-05-31");
    mvc.perform(assign(created, admin(tenant), Organizations.newKey(), assignment(region)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.regionId").value(region.toString()));
    assertThat(audits(created, "site.region.assign")).isZero();
  }

  @Test
  void aDifferentRegionAfterAssignmentConflictsWithoutConsumingTheKey() throws Exception {
    UUID site = unassignedSite("2026-02-01", "2026-03-31");
    mvc.perform(assign(site, admin(tenant), Organizations.newKey(), assignment(region)))
        .andExpect(status().isOk());
    UUID secondRegion =
        Hierarchy.newRegion(mvc, tenant, legalEntity, code("r2"), "2026-01-01", null);
    UUID otherLegalEntity = Hierarchy.newLegalEntity(mvc, tenant, code("le3"), "2026-01-01", null);
    UUID mismatched =
        Hierarchy.newRegion(mvc, tenant, otherLegalEntity, code("r3"), "2026-01-01", null);
    double conflicts = count("site.region.assign", "state_conflict");
    String key = Organizations.newKey();
    for (UUID target : List.of(secondRegion, mismatched)) {
      String body =
          mvc.perform(assign(site, admin(tenant), key, assignment(target)))
              .andExpect(status().isConflict())
              .andExpect(jsonPath("$.code").value("SITE_REGION_ALREADY_ASSIGNED"))
              .andExpect(jsonPath("$.params").isEmpty())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body).doesNotContain(region.toString()).doesNotContain(target.toString());
    }
    assertThat(count("site.region.assign", "state_conflict")).isEqualTo(conflicts + 2);
    assertThat(storedRegion(site)).isEqualTo(region);
    assertThat(
            rows("SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
  }

  @ParameterizedTest(name = "region 2026-01-01..2026-12-31, site {0}..{1} -> {2}")
  @CsvSource(
      nullValues = "open",
      value = {
        "2026-01-01, 2026-12-31, 200",
        "2026-12-31, 2026-12-31, 200",
        "2026-06-15, 2026-06-15, 200",
        "2026-01-01, open, SITE_PERIOD_OUTSIDE_REGION",
        "2026-06-01, 2027-01-01, SITE_PERIOD_OUTSIDE_REGION",
      })
  void assignmentRequiresTheSitePeriodWithinTheRegion(String from, String to, String expected)
      throws Exception {
    UUID site = unassignedSite(from, to);
    var result =
        mvc.perform(assign(site, admin(tenant), Organizations.newKey(), assignment(region)));
    if ("200".equals(expected)) {
      result.andExpect(status().isOk());
      assertThat(storedRegion(site)).isEqualTo(region);
    } else {
      result
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(expected))
          .andExpect(jsonPath("$.params.field").value("regionId"));
      assertThat(storedRegion(site)).isNull();
    }
  }

  @Test
  void assignmentStartingBeforeTheRegionIsRejected() throws Exception {
    UUID late = Hierarchy.newRegion(mvc, tenant, legalEntity, code("lt"), "2026-06-01", null);
    UUID site = unassignedSite("2026-05-31", null);
    mvc.perform(assign(site, admin(tenant), Organizations.newKey(), assignment(late)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SITE_PERIOD_OUTSIDE_REGION"));
    assertThat(storedRegion(site)).isNull();
  }

  @Test
  void assignmentRejectsARegionOfAnotherLegalEntity() throws Exception {
    UUID site = unassignedSite("2026-02-01", "2026-03-31");
    UUID otherLegalEntity = Hierarchy.newLegalEntity(mvc, tenant, code("le2"), "2026-01-01", null);
    UUID otherRegion =
        Hierarchy.newRegion(mvc, tenant, otherLegalEntity, code("r2"), "2026-01-01", null);
    mvc.perform(assign(site, admin(tenant), Organizations.newKey(), assignment(otherRegion)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SITE_REGION_LEGAL_ENTITY_MISMATCH"))
        .andExpect(jsonPath("$.params.field").value("regionId"));
    assertThat(storedRegion(site)).isNull();
    assertThat(audits(site, "site.region.assign")).isZero();
  }

  @Test
  void missingAndForeignSitesAndRegionsAreIndistinguishable() throws Exception {
    UUID site = unassignedSite("2026-02-01", "2026-03-31");
    UUID otherTenant = Hierarchy.newTenant(mvc);
    UUID otherParent = Hierarchy.newLegalEntity(mvc, otherTenant, code("ox"), "2026-01-01", null);
    UUID foreignRegion =
        Hierarchy.newRegion(mvc, otherTenant, otherParent, code("fr"), "2026-01-01", null);
    UUID foreignSite =
        Hierarchy.newSite(mvc, otherTenant, otherParent, code("fs"), "2026-02-01", "2026-03-31");

    JsonNode foreignS = assignProblem(foreignSite, assignment(region), 404, "SITE_NOT_FOUND");
    JsonNode missingS = assignProblem(UUID.randomUUID(), assignment(region), 404, "SITE_NOT_FOUND");
    assertThat(SiteApiIntegrationTest.withoutCorrelationId(foreignS))
        .isEqualTo(SiteApiIntegrationTest.withoutCorrelationId(missingS));
    assertThat(foreignS.get("params").get("field").asText()).isEqualTo("siteId");

    JsonNode foreignR = assignProblem(site, assignment(foreignRegion), 404, "REGION_NOT_FOUND");
    JsonNode missingR = assignProblem(site, assignment(UUID.randomUUID()), 404, "REGION_NOT_FOUND");
    assertThat(SiteApiIntegrationTest.withoutCorrelationId(foreignR))
        .isEqualTo(SiteApiIntegrationTest.withoutCorrelationId(missingR));
    assertThat(foreignR.get("params").get("field").asText()).isEqualTo("regionId");
    assertThat(foreignR.toString() + foreignS)
        .doesNotContain(foreignRegion.toString())
        .doesNotContain(foreignSite.toString())
        .doesNotContain(otherTenant.toString());

    // A foreign site with a foreign region of its own tenant is still not found for tenant A.
    assignProblem(foreignSite, assignment(foreignRegion), 404, "SITE_NOT_FOUND");
    // Missing site and missing region: the path's site is reported first.
    assignProblem(UUID.randomUUID(), assignment(UUID.randomUUID()), 404, "SITE_NOT_FOUND");

    assertThat(storedRegion(site)).isNull();
    assertThat(storedRegion(foreignSite)).isNull();
    assertThat(
            rows(
                "SELECT count(*) FROM platform.idempotency_record"
                    + " WHERE operation = ? AND principal = ?",
                "site.region.assign",
                "sub-admin-" + tenant))
        .isZero();
  }

  @Test
  void validationUsesStableCodesRejectsExtraPropertiesAndConsumesNoKey(CapturedOutput output)
      throws Exception {
    UUID site = unassignedSite("2026-02-01", "2026-03-31");
    mvc.perform(assign("not-a-site", admin(tenant), Organizations.newKey(), assignment(region)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.params.fields[0].field").value("siteId"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("FORMAT"))
        .andExpect(
            content ->
                assertThat(content.getResponse().getContentAsString())
                    .doesNotContain("not-a-site"));
    mvc.perform(assign(site, admin(tenant), Organizations.newKey(), assignment("bad-region")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("regionId"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("FORMAT"));
    mvc.perform(assign(site, admin(tenant), Organizations.newKey(), "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("regionId"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("REQUIRED"));
    mvc.perform(assign(site, admin(tenant), null, assignment(region)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("Idempotency-Key"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("REQUIRED"));
    for (String property :
        List.of("tenantId", "legalEntityId", "siteId", "id", "organizationId", "extra")) {
      mvc.perform(
              assign(
                  site,
                  admin(tenant),
                  Organizations.newKey(),
                  "{\"regionId\": \"%s\", \"%s\": \"%s\"}"
                      .formatted(region, property, UUID.randomUUID())))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.params.fields[0].field").value("body"))
          .andExpect(jsonPath("$.params.fields[0].constraint").value("UNKNOWN_PROPERTY"));
    }
    assertThat(output.getAll()).doesNotContain("bad-region").doesNotContain("not-a-site");
    assertThat(storedRegion(site)).isNull();
    assertThat(
            rows(
                "SELECT count(*) FROM platform.idempotency_record"
                    + " WHERE operation = ? AND principal = ?",
                "site.region.assign",
                "sub-admin-" + tenant))
        .isZero();
  }

  @Test
  void onlyTenantAdministratorsMayAssignRegions() throws Exception {
    UUID site = unassignedSite("2026-02-01", "2026-03-31");
    double before = count("site.region.assign", "denied");
    for (String role : List.of("employee", "platform-admin")) {
      String caller = bearer(tenant, "sub-" + role, role);
      mvc.perform(assign(site, caller, Organizations.newKey(), assignment(region)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      mvc.perform(assign(site, caller, Organizations.newKey(), "{not json"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    assertThat(count("site.region.assign", "denied")).isEqualTo(before + 4);
    mvc.perform(
            assign(site, bearer(tenant, null, "tenant-admin"), Organizations.newKey(), "{not json"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(
            assign(
                site,
                bearer(null, "sub-no-tenant", "tenant-admin"),
                Organizations.newKey(),
                "{not json"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));
    mvc.perform(assign(site, null, Organizations.newKey(), assignment(region)))
        .andExpect(status().isUnauthorized());
    // Another tenant's administrator cannot reach the site either.
    UUID otherTenant = Hierarchy.newTenant(mvc);
    mvc.perform(assign(site, admin(otherTenant), Organizations.newKey(), assignment(region)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SITE_NOT_FOUND"));
    assertThat(storedRegion(site)).isNull();
  }

  @Test
  void siteListsShowAssignedAndUnassignedSitesTogether() throws Exception {
    UUID unassigned = unassignedSite("2026-02-01", "2026-03-31");
    UUID assigned = unassignedSite("2026-02-01", "2026-03-31");
    mvc.perform(assign(assigned, admin(tenant), Organizations.newKey(), assignment(region)))
        .andExpect(status().isOk());
    JsonNode page =
        json(
            mvc.perform(list(admin(tenant), SITES + "?legalEntityId=" + legalEntity))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page.get("data")).hasSize(2);
    for (JsonNode site : page.get("data")) {
      assertThat(site.has("regionId")).isTrue();
      if (site.get("id").asText().equals(assigned.toString())) {
        assertThat(site.get("regionId").asText()).isEqualTo(region.toString());
      } else {
        assertThat(site.get("id").asText()).isEqualTo(unassigned.toString());
        assertThat(site.get("regionId").isNull()).isTrue();
      }
    }
  }

  @Test
  void logsAndMetricsCarryNoIdentifiersOrValues(CapturedOutput output) throws Exception {
    UUID site = unassignedSite("2026-02-01", "2026-03-31");
    String key = Organizations.newKey();
    String token = admin(tenant);
    mvc.perform(assign(site, token, key, assignment(region))).andExpect(status().isOk());
    mvc.perform(assign(site, token, Organizations.newKey(), assignment(region)))
        .andExpect(status().isOk());
    assertThat(output.getAll())
        .contains("site_region_assigned")
        .contains("site_region_assign_unchanged")
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
                    .doesNotContain(region.toString());
              });
    }
  }

  private JsonNode siteProblem(String body, int status, String code) throws Exception {
    return json(
        mvc.perform(create(SITES, admin(tenant), Organizations.newKey(), body))
            .andExpect(status().is(status))
            .andExpect(jsonPath("$.code").value(code))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode assignProblem(Object site, String body, int status, String code)
      throws Exception {
    return json(
        mvc.perform(assign(site, admin(tenant), Organizations.newKey(), body))
            .andExpect(status().is(status))
            .andExpect(jsonPath("$.code").value(code))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }
}
