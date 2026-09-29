package com.divalhr.core.tenant;

import static com.divalhr.core.support.Hierarchy.code;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Issue #23 compatibility: V7 changes no existing row, the new composite keys hold for every
 * existing department and cost center, the previous application's exact statements keep working
 * against V7 (with and without teams beneath the parents), and the manual rollback restores V6.
 */
@IntegrationTest
class TeamMigrationCompatibilityIntegrationTest {

  /** The previous application's site-unit columns (PR #22 JdbcSiteUnitRepository). */
  private static final String LEGACY_UNIT_COLUMNS =
      "id, tenant_id, site_id, code, name, effective_from, effective_to, created_at, created_by";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  @Test
  void v7PreservesExistingRowsAndTheManualRollbackRestoresV6() {
    String database = "compat7_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      migrate(url, "6");
      JdbcTemplate db =
          new JdbcTemplate(
              new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));

      UUID tenant = UUID.randomUUID();
      db.update(
          """
          INSERT INTO tenant.organization
            (id, name, country_code, default_locale, timezone, created_at, created_by)
          VALUES (?, 'Compat Org', 'CD', 'fr', 'Africa/Kinshasa', now(), 'sub-compat')
          """,
          tenant);
      UUID legal = UUID.randomUUID();
      db.update(
          """
          INSERT INTO tenant.legal_entity
            (id, tenant_id, code, name, country_code, effective_from, created_at, created_by)
          VALUES (?, ?, 'LE-COMPAT', 'Entité', 'CD', DATE '2026-01-01', now(), 'sub-compat')
          """,
          legal,
          tenant);
      UUID region = UUID.randomUUID();
      db.update(
          """
          INSERT INTO tenant.region
            (id, tenant_id, legal_entity_id, code, name, effective_from, created_at, created_by)
          VALUES (?, ?, ?, 'R-COMPAT', 'Région', DATE '2026-01-01', now(), 'sub-compat')
          """,
          region,
          tenant,
          legal);
      UUID assignedSite = UUID.randomUUID();
      UUID unassignedSite = UUID.randomUUID();
      for (Object[] site :
          List.of(
              new Object[] {assignedSite, region, "S-IN"},
              new Object[] {unassignedSite, null, "S-OUT"})) {
        db.update(
            """
            INSERT INTO tenant.site
              (id, tenant_id, legal_entity_id, region_id, code, name, timezone, effective_from,
               created_at, created_by)
            VALUES (?, ?, ?, ?, ?, 'Site', 'Africa/Kinshasa', DATE '2026-01-01', now(), 'sub')
            """,
            site[0],
            tenant,
            legal,
            site[1],
            site[2]);
      }
      for (String table : List.of("tenant.department", "tenant.cost_center")) {
        for (UUID site : List.of(assignedSite, unassignedSite)) {
          legacyUnit(
              db,
              table,
              tenant,
              site,
              "U"
                  + UUID.randomUUID()
                      .toString()
                      .substring(0, 6)
                      .toUpperCase(java.util.Locale.ROOT));
        }
      }
      String snapshot =
          "SELECT string_agg(j, ',' ORDER BY j) FROM ("
              + "SELECT to_jsonb(d)::text j FROM tenant.department d UNION ALL"
              + " SELECT to_jsonb(c)::text FROM tenant.cost_center c UNION ALL"
              + " SELECT to_jsonb(s)::text FROM tenant.site s UNION ALL"
              + " SELECT to_jsonb(r)::text FROM tenant.region r) rows";
      String before = db.queryForObject(snapshot, String.class);

      migrate(url, null);
      assertThat(
              db.queryForObject(
                  "SELECT max(version::int) FROM flyway_schema_history WHERE success",
                  Integer.class))
          .isEqualTo(7);
      assertThat(db.queryForObject(snapshot, String.class)).isEqualTo(before);
      assertThat(
              db.queryForList(
                  "SELECT conname FROM pg_constraint WHERE conname IN"
                      + " ('department_tenant_site_id_unique', 'cost_center_tenant_site_id_unique')"
                      + " ORDER BY conname",
                  String.class))
          .containsExactly("cost_center_tenant_site_id_unique", "department_tenant_site_id_unique");
      assertThat(db.queryForObject("SELECT count(*) FROM tenant.team", Integer.class)).isZero();

      String rollback = read("db/rollback/V7__rollback.sql");
      db.execute(rollback);
      assertThat(db.queryForObject("SELECT to_regclass('tenant.team')::text", String.class))
          .isNull();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM pg_constraint WHERE conname LIKE '%tenant_site_id_unique'",
                  Integer.class))
          .isZero();
      assertThat(db.queryForObject(snapshot, String.class)).isEqualTo(before);
      db.update("DELETE FROM flyway_schema_history WHERE version = '7'");
      migrate(url, null);
      assertThat(db.queryForObject(snapshot, String.class)).isEqualTo(before);
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }

  @Test
  void thePreviousApplicationsStatementsWorkAgainstV7WithAndWithoutTeams() throws Exception {
    UUID tenant = Hierarchy.newTenant(mvc);
    UUID legal = Hierarchy.newLegalEntity(mvc, tenant, code("le"), "2026-01-01", null);
    UUID site = Hierarchy.newSite(mvc, tenant, legal, code("st"), "2026-01-01", null);
    for (String table : List.of("tenant.department", "tenant.cost_center")) {
      String field = table.equals("tenant.department") ? "departmentId" : "costCenterId";
      UUID withTeams = legacyUnit(jdbc, table, tenant, site, code("wt"));
      UUID withoutTeams = legacyUnit(jdbc, table, tenant, site, code("wo"));
      Hierarchy.newTeam(mvc, tenant, field, withTeams, code("tm"), "2026-01-01", null);

      for (UUID unit : List.of(withTeams, withoutTeams)) {
        Map<String, Object> row =
            jdbc.queryForMap(
                "SELECT "
                    + LEGACY_UNIT_COLUMNS
                    + " FROM "
                    + table
                    + " WHERE tenant_id = ? AND id = ? FOR SHARE",
                tenant,
                unit);
        assertThat(row).containsEntry("id", unit);
      }
      List<Map<String, Object>> page =
          jdbc.queryForList(
              "SELECT "
                  + LEGACY_UNIT_COLUMNS
                  + " FROM "
                  + table
                  + " WHERE tenant_id = ? AND site_id = ?"
                  + " ORDER BY code COLLATE \"C\", id LIMIT 51",
              tenant,
              site);
      assertThat(page.stream().map(r -> r.get("id")).toList()).contains(withTeams, withoutTeams);
    }
  }

  private static UUID legacyUnit(
      JdbcTemplate db, String table, UUID tenant, UUID site, String code) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO "
            + table
            + " ("
            + LEGACY_UNIT_COLUMNS
            + ") VALUES (?, ?, ?, ?, 'Unité', ?, NULL, now(), 'sub-legacy')",
        id,
        tenant,
        site,
        code,
        Date.valueOf("2026-01-01"));
    return id;
  }

  private static String read(String resource) {
    try {
      return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
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
}
