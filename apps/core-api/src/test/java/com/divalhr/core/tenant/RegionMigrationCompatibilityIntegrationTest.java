package com.divalhr.core.tenant;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.code;
import static com.divalhr.core.support.Hierarchy.create;
import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Hierarchy.site;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.tenant.application.SiteCommand;
import com.divalhr.core.tenant.domain.EffectivePeriod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue #21 compatibility: V6 preserves every pre-existing site, a {@code site.create} key recorded
 * before V6 keeps replaying with the new code, the previous application's SQL keeps working against
 * V6 for unassigned and assigned sites, and the manual rollback restores the V5 shape.
 */
@IntegrationTest
class RegionMigrationCompatibilityIntegrationTest {

  private static final ObjectMapper LEGACY_JSON = new ObjectMapper();

  /** The previous application's site columns (V5, before {@code region_id}). */
  private static final String LEGACY_SITE_COLUMNS =
      "id, tenant_id, legal_entity_id, code, name, timezone, effective_from, effective_to,"
          + " created_at, created_by";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;
  @Autowired private PostgreSQLContainer postgres;

  @Test
  void v6PreservesExistingSitesAndTheManualRollbackRestoresV5() throws Exception {
    String database = "compat_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      migrate(url, "5");
      JdbcTemplate v5 =
          new JdbcTemplate(
              new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));

      UUID tenant = UUID.randomUUID();
      v5.update(
          """
          INSERT INTO tenant.organization
            (id, name, country_code, default_locale, timezone, created_at, created_by)
          VALUES (?, 'Compat Org', 'CD', 'fr', 'Africa/Kinshasa', now(), 'sub-compat')
          """,
          tenant);
      UUID legal = UUID.randomUUID();
      v5.update(
          """
          INSERT INTO tenant.legal_entity
            (id, tenant_id, code, name, country_code, effective_from, created_at, created_by)
          VALUES (?, ?, 'LE-COMPAT', 'Entité', 'CD', DATE '2026-01-01', now(), 'sub-compat')
          """,
          legal,
          tenant);
      UUID openSite = legacySite(v5, tenant, legal, "S-OPEN", "2026-01-01", null);
      UUID closedSite = legacySite(v5, tenant, legal, "S-CLOSED", "2026-02-01", "2026-06-30");
      v5.update(
          """
          INSERT INTO tenant.department
            (id, tenant_id, site_id, code, name, effective_from, effective_to, created_at,
             created_by)
          VALUES (?, ?, ?, 'D-COMPAT', 'Département', DATE '2026-02-01', DATE '2026-06-30',
                  now(), 'sub-compat')
          """,
          UUID.randomUUID(),
          tenant,
          closedSite);
      List<String> before =
          v5.queryForList("SELECT to_jsonb(s)::text FROM tenant.site s ORDER BY id", String.class);
      assertThat(before).hasSize(2);

      migrate(url, null);
      assertThat(
              v5.queryForObject(
                  "SELECT max(version) FROM flyway_schema_history WHERE success", String.class))
          .isEqualTo("6");
      List<String> after =
          v5.queryForList(
              "SELECT (to_jsonb(s) - 'region_id')::text FROM tenant.site s ORDER BY id",
              String.class);
      assertThat(after).isEqualTo(before);
      assertThat(
              v5.queryForObject(
                  "SELECT count(*) FROM tenant.site WHERE region_id IS NULL", Integer.class))
          .isEqualTo(2);
      assertThat(v5.queryForObject("SELECT count(*) FROM tenant.department", Integer.class))
          .isEqualTo(1);
      assertThat(
              v5.queryForObject(
                  "SELECT count(*) FROM tenant.site WHERE id IN (?, ?)",
                  Integer.class,
                  openSite,
                  closedSite))
          .isEqualTo(2);

      // Manual rollback (documented): drops the region layer and keeps every site.
      UUID region = UUID.randomUUID();
      v5.update(
          """
          INSERT INTO tenant.region
            (id, tenant_id, legal_entity_id, code, name, effective_from, created_at, created_by)
          VALUES (?, ?, ?, 'R-COMPAT', 'Région', DATE '2026-01-01', now(), 'sub-compat')
          """,
          region,
          tenant,
          legal);
      v5.update("UPDATE tenant.site SET region_id = ? WHERE id = ?", region, closedSite);
      String rollback =
          new ClassPathResource("db/rollback/V6__rollback.sql")
              .getContentAsString(StandardCharsets.UTF_8);
      v5.execute(rollback);
      assertThat(
              v5.queryForObject(
                  "SELECT count(*) FROM information_schema.columns"
                      + " WHERE table_schema = 'tenant' AND table_name = 'site'"
                      + " AND column_name = 'region_id'",
                  Integer.class))
          .isZero();
      assertThat(v5.queryForObject("SELECT to_regclass('tenant.region')::text", String.class))
          .isNull();
      assertThat(
              v5.queryForList(
                  "SELECT to_jsonb(s)::text FROM tenant.site s ORDER BY id", String.class))
          .isEqualTo(before);
      // Re-applying V6 after removing its history row works.
      v5.update("DELETE FROM flyway_schema_history WHERE version = '6'");
      migrate(url, null);
      assertThat(
              v5.queryForObject(
                  "SELECT count(*) FROM tenant.site WHERE region_id IS NULL", Integer.class))
          .isEqualTo(2);
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }

  @Test
  void aSiteCreateKeyRecordedBeforeV6ReplaysUnchanged() throws Exception {
    UUID tenant = Hierarchy.newTenant(mvc);
    UUID legal = Hierarchy.newLegalEntity(mvc, tenant, code("le"), "2026-01-01", null);
    String code = code("pre");
    String name = "Site d’avant V6 " + code;
    Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    UUID siteId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenant.site ("
            + LEGACY_SITE_COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, 'Africa/Kinshasa', DATE '2026-01-01', NULL, ?,"
            + " 'sub-legacy')",
        siteId,
        tenant,
        legal,
        code,
        name,
        Timestamp.from(createdAt));

    // The canonical command and stored body exactly as the V5-era application wrote them.
    Map<String, Object> legacyCanonical = new TreeMap<>();
    legacyCanonical.put("code", code);
    legacyCanonical.put("effectiveFrom", "2026-01-01");
    legacyCanonical.put("effectiveTo", "");
    legacyCanonical.put("legalEntityId", legal.toString());
    legacyCanonical.put("name", name);
    legacyCanonical.put("tenantId", tenant.toString());
    legacyCanonical.put("timezone", "Africa/Kinshasa");
    String legacyFingerprint = Fingerprints.sha256(LEGACY_JSON.writeValueAsString(legacyCanonical));
    Map<String, Object> legacyBody = new LinkedHashMap<>();
    legacyBody.put("id", siteId.toString());
    legacyBody.put("legalEntityId", legal.toString());
    legacyBody.put("code", code);
    legacyBody.put("name", name);
    legacyBody.put("timezone", "Africa/Kinshasa");
    legacyBody.put("effectiveFrom", "2026-01-01");
    legacyBody.put("effectiveTo", null);
    legacyBody.put("createdAt", createdAt.toString());
    String storedBody = LEGACY_JSON.writeValueAsString(legacyBody);
    assertThat(storedBody).doesNotContain("regionId");

    // The new code computes the same fingerprint for a request without a region.
    SiteCommand command =
        new SiteCommand(
            legal,
            null,
            code,
            name,
            "Africa/Kinshasa",
            new EffectivePeriod(LocalDate.parse("2026-01-01"), null));
    assertThat(
            Fingerprints.sha256(json.writeValueAsString(command.canonical(new TenantId(tenant)))))
        .isEqualTo(legacyFingerprint);

    String key = Organizations.newKey();
    jdbc.update(
        """
        INSERT INTO platform.idempotency_record
          (operation, principal, idempotency_key, request_fingerprint, state, response_status,
           response_body, resource_id, created_at, expires_at)
        VALUES ('site.create', ?, ?, ?, 'COMPLETED', 201, CAST(? AS jsonb), ?, now(),
                now() + interval '7 days')
        """,
        "sub-admin-" + tenant,
        key,
        legacyFingerprint,
        storedBody,
        siteId);

    int sites = count("SELECT count(*) FROM tenant.site WHERE tenant_id = ?", tenant);
    int audits = count("SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?", tenant);
    int events = count("SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?", tenant);

    String response =
        mvc.perform(
                create(
                    "/api/v1/sites",
                    admin(tenant),
                    key,
                    site(
                        legal,
                        code.toLowerCase(Locale.ROOT),
                        " " + name + " ",
                        "Africa/Kinshasa",
                        "2026-01-01",
                        null)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode replayed = json(response);
    assertThat(replayed.get("id").asText()).isEqualTo(siteId.toString());
    assertThat(replayed.get("code").asText()).isEqualTo(code);
    assertThat(replayed.has("regionId")).isTrue();
    assertThat(replayed.get("regionId").isNull()).isTrue();
    assertThat(replayed.has("effectiveTo")).isTrue();
    assertThat(replayed.get("effectiveTo").isNull()).isTrue();
    // Replayed, not recreated: no site, audit or outbox row was written.
    assertThat(count("SELECT count(*) FROM tenant.site WHERE tenant_id = ?", tenant))
        .isEqualTo(sites);
    assertThat(count("SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?", tenant))
        .isEqualTo(audits);
    assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?", tenant))
        .isEqualTo(events);
    // Same shape as a fresh response.
    String fresh =
        mvc.perform(
                create(
                    "/api/v1/sites",
                    admin(tenant),
                    Organizations.newKey(),
                    site(legal, code("new"), "Nouveau", "Africa/Kinshasa", "2026-01-01", null)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> freshFields = new ArrayList<>();
    json(fresh).fieldNames().forEachRemaining(freshFields::add);
    List<String> replayedFields = new ArrayList<>();
    replayed.fieldNames().forEachRemaining(replayedFields::add);
    assertThat(replayedFields).containsExactlyInAnyOrderElementsOf(freshFields);
    assertThat(
            count(
                "SELECT count(*) FROM platform.idempotency_record"
                    + " WHERE idempotency_key = ? AND request_fingerprint = ?",
                key,
                legacyFingerprint))
        .isEqualTo(1);
  }

  @Test
  void thePreviousApplicationsStatementsWorkAgainstV6ForAssignedAndUnassignedSites()
      throws Exception {
    UUID tenant = Hierarchy.newTenant(mvc);
    UUID legal = Hierarchy.newLegalEntity(mvc, tenant, code("le"), "2026-01-01", null);
    UUID region = Hierarchy.newRegion(mvc, tenant, legal, code("rg"), "2026-01-01", null);
    UUID assigned =
        Hierarchy.newSiteInRegion(mvc, tenant, legal, region, code("as"), "2026-01-01", null);
    UUID unassigned = Hierarchy.newSite(mvc, tenant, legal, code("un"), "2026-01-01", null);

    // V5-era insert (no region_id): the new site has no region.
    UUID legacy = legacySite(jdbc, tenant, legal, code("lg"), "2026-01-01", null);
    assertThat(
            jdbc.queryForObject(
                "SELECT region_id FROM tenant.site WHERE id = ?", Object.class, legacy))
        .isNull();

    // V5-era reads: lookup FOR SHARE and keyset page, for assigned and unassigned sites alike.
    for (UUID site : List.of(assigned, unassigned, legacy)) {
      Map<String, Object> row =
          jdbc.queryForMap(
              "SELECT "
                  + LEGACY_SITE_COLUMNS
                  + " FROM tenant.site WHERE tenant_id = ? AND id = ? FOR SHARE",
              tenant,
              site);
      assertThat(row).containsEntry("id", site).doesNotContainKey("region_id");
    }
    List<Map<String, Object>> page =
        jdbc.queryForList(
            "SELECT "
                + LEGACY_SITE_COLUMNS
                + " FROM tenant.site WHERE tenant_id = ? AND legal_entity_id = ?"
                + " ORDER BY code COLLATE \"C\", id LIMIT 51",
            tenant,
            legal);
    assertThat(page.stream().map(row -> row.get("id")).toList())
        .contains(assigned, unassigned, legacy);

    // V5-era department and cost-center inserts beneath an assigned site.
    for (String table : List.of("tenant.department", "tenant.cost_center")) {
      jdbc.update(
          "INSERT INTO "
              + table
              + " (id, tenant_id, site_id, code, name, effective_from, effective_to, created_at,"
              + " created_by) VALUES (?, ?, ?, ?, 'Unité', ?, NULL, now(), 'sub-legacy')",
          UUID.randomUUID(),
          tenant,
          assigned,
          code("u"),
          Date.valueOf("2026-01-01"));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT region_id FROM tenant.site WHERE id = ?", Object.class, assigned))
        .isEqualTo(region);
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private void migrate(String url, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(url, postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration");
    if (target != null) {
      configuration = configuration.target(target);
    }
    configuration.load().migrate();
  }

  private static UUID legacySite(
      JdbcTemplate jdbc, UUID tenant, UUID legal, String code, String from, String to) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenant.site ("
            + LEGACY_SITE_COLUMNS
            + ") VALUES (?, ?, ?, ?, 'Site', 'Africa/Kinshasa', ?, ?, now(), 'sub-legacy')",
        id,
        tenant,
        legal,
        code,
        Date.valueOf(from),
        to == null ? null : Date.valueOf(to));
    return id;
  }
}
