package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.IntegrationTest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V7: the database independently enforces exactly one parent, tenant/site/parent consistency,
 * case-insensitive team codes, team periods within the parent, parent backstops and immutable
 * ownership, site and parent.
 */
@IntegrationTest
class TeamConstraintsIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;

  private UUID tenantA;
  private UUID tenantB;
  private UUID siteA;
  private UUID siteA2;
  private UUID siteB;
  private UUID closedDepartment;
  private UUID closedCostCenter;
  private UUID otherSiteDepartment;
  private UUID foreignDepartment;

  @BeforeEach
  void hierarchy() {
    tenantA = organization();
    tenantB = organization();
    UUID legalA = legalEntity(tenantA);
    UUID legalB = legalEntity(tenantB);
    siteA = site(tenantA, legalA);
    siteA2 = site(tenantA, legalA);
    siteB = site(tenantB, legalB);
    closedDepartment = unit("tenant.department", tenantA, siteA, "2026-01-01", "2026-12-31");
    closedCostCenter = unit("tenant.cost_center", tenantA, siteA, "2026-01-01", "2026-12-31");
    otherSiteDepartment = unit("tenant.department", tenantA, siteA2, "2026-01-01", null);
    foreignDepartment = unit("tenant.department", tenantB, siteB, "2026-01-01", null);
  }

  private UUID parent(TeamParentResource resource) {
    return resource == TeamParentResource.DEPARTMENT ? closedDepartment : closedCostCenter;
  }

  /** Amendment 3: the named check wins for both invalid shapes, whatever the dates. */
  @Test
  void exactlyOneParentCheckRejectsNeitherAndBoth() {
    for (String[] period : new String[][] {{"2026-01-01", "2026-06-30"}, {"2025-01-01", null}}) {
      assertThat(
              constraintOf(() -> team(tenantA, siteA, null, null, code("N"), period[0], period[1])))
          .isEqualTo("team_exactly_one_parent");
      assertThat(
              constraintOf(
                  () ->
                      team(
                          tenantA,
                          siteA,
                          closedDepartment,
                          closedCostCenter,
                          code("B"),
                          period[0],
                          period[1])))
          .isEqualTo("team_exactly_one_parent");
    }
    // Updating a valid team into either invalid shape is rejected (the parent is immutable too).
    UUID id = team(tenantA, siteA, closedDepartment, null, code("V"), "2026-01-01", "2026-06-30");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.team SET cost_center_id = ? WHERE id = ?",
                        closedCostCenter,
                        id)))
        .isNull();
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void tenantSiteAndParentMustBelongTogether(TeamParentResource resource) {
    String fk =
        resource == TeamParentResource.DEPARTMENT
            ? "team_department_same_tenant_and_site"
            : "team_cost_center_same_tenant_and_site";
    UUID parent = parent(resource);
    // Parent of the same tenant, but another site.
    assertThat(constraintOf(() -> teamUnder(resource, tenantA, siteA2, parent, code("S"))))
        .isEqualTo(fk);
    // Parent of another tenant.
    if (resource == TeamParentResource.DEPARTMENT) {
      assertThat(
              constraintOf(() -> teamUnder(resource, tenantA, siteA, foreignDepartment, code("T"))))
          .isEqualTo(fk);
      // The same department on its own site is fine.
      teamUnder(resource, tenantA, siteA2, otherSiteDepartment, code("W"));
    }
    // Missing parent.
    assertThat(
            constraintOf(() -> teamUnder(resource, tenantA, siteA, UUID.randomUUID(), code("M"))))
        .isEqualTo(fk);
    // Site of another tenant.
    assertThat(constraintOf(() -> teamUnder(resource, tenantA, siteB, parent, code("X"))))
        .isIn(fk, "team_site_same_tenant");
  }

  @Test
  void teamCodesAreUniquePerTenantAcrossParentsIgnoringCase() {
    team(tenantA, siteA, closedDepartment, null, "DUP-1", "2026-01-01", "2026-06-30");
    assertThat(
            constraintOf(
                () ->
                    team(
                        tenantA,
                        siteA,
                        null,
                        closedCostCenter,
                        "DUP-1",
                        "2026-01-01",
                        "2026-06-30")))
        .isEqualTo("team_code_ci_unique");
    assertThat(
            constraintOf(
                () ->
                    team(
                        tenantA,
                        siteA,
                        closedDepartment,
                        null,
                        "dup-2",
                        "2026-01-01",
                        "2026-06-30")))
        .isEqualTo("team_code_format");
    team(tenantB, siteB, foreignDepartment, null, "DUP-1", "2026-01-01", null);
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void containmentIsEnforcedByTheDatabase(TeamParentResource resource) {
    String constraint = resource.periodConstraint();
    UUID parent = parent(resource);
    teamUnder(resource, tenantA, siteA, parent, code("I1"), "2026-01-01", "2026-12-31");
    UUID sameDay =
        teamUnder(resource, tenantA, siteA, parent, code("I2"), "2026-01-01", "2026-01-01");
    teamUnder(resource, tenantA, siteA, parent, code("I3"), "2026-12-31", "2026-12-31");
    for (String[] period :
        new String[][] {
          {"2025-12-31", "2026-06-30"}, {"2026-06-01", "2027-01-01"}, {"2026-06-01", null}
        }) {
      assertThat(
              constraintOf(
                  () ->
                      teamUnder(resource, tenantA, siteA, parent, code("O"), period[0], period[1])))
          .isEqualTo(constraint);
    }
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.team SET effective_from = ? WHERE id = ?",
                        Date.valueOf("2025-01-01"),
                        sameDay)))
        .isEqualTo(constraint);
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void parentsCannotBeNarrowedBelowTheirTeams(TeamParentResource resource) {
    String backstop =
        resource == TeamParentResource.DEPARTMENT
            ? "department_period_covers_teams"
            : "cost_center_period_covers_teams";
    UUID parent = parent(resource);
    teamUnder(resource, tenantA, siteA, parent, code("E"), "2026-12-31", "2026-12-31");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE " + resource.parentTable + " SET effective_to = ? WHERE id = ?",
                        Date.valueOf("2026-12-30"),
                        parent)))
        .isEqualTo(backstop);
    // Narrowing the start to the team's own start is still allowed (inclusive boundary).
    assertThat(
            jdbc.update(
                "UPDATE " + resource.parentTable + " SET effective_from = ? WHERE id = ?",
                Date.valueOf("2026-12-31"),
                parent))
        .isEqualTo(1);
    // Widening remains possible.
    jdbc.update("UPDATE " + resource.parentTable + " SET effective_to = NULL WHERE id = ?", parent);
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void ownershipSiteAndParentAreImmutableAndFieldsChecked(TeamParentResource resource) {
    UUID id =
        teamUnder(
            resource, tenantA, siteA, parent(resource), code("OW"), "2026-01-01", "2026-06-30");
    for (String update :
        List.of(
            "UPDATE tenant.team SET tenant_id = '" + tenantB + "' WHERE id = ?",
            "UPDATE tenant.team SET id = '" + UUID.randomUUID() + "' WHERE id = ?",
            "UPDATE tenant.team SET site_id = '" + siteA2 + "' WHERE id = ?",
            "UPDATE tenant.team SET "
                + resource.column
                + " = '"
                + UUID.randomUUID()
                + "' WHERE id = ?",
            "UPDATE tenant.team SET "
                + resource.column
                + " = NULL, "
                + resource.other().column
                + " = '"
                + parent(resource.other())
                + "' WHERE id = ?")) {
      assertThat(constraintOrFailure(() -> jdbc.update(update, id))).as(update).isTrue();
    }
    assertThat(
            constraintOf(
                () ->
                    teamUnder(
                        resource,
                        tenantA,
                        siteA,
                        parent(resource),
                        code("LT"),
                        "2026-01-01",
                        "3000-01-01")))
        .isEqualTo("team_effective_range");
    assertThat(
            constraintOf(
                () ->
                    teamUnder(
                        resource,
                        tenantA,
                        siteA,
                        parent(resource),
                        code("BK"),
                        "2026-01-02",
                        "2026-01-01")))
        .isEqualTo("team_effective_order");
  }

  @Test
  void listQueriesUseThePartialKeysetIndexes() {
    for (TeamParentResource resource : TeamParentResource.values()) {
      List<String> plan =
          transactions.execute(
              status -> {
                jdbc.execute("SET LOCAL enable_seqscan = off");
                return jdbc.queryForList(
                    "EXPLAIN SELECT id FROM tenant.team WHERE tenant_id = ? AND "
                        + resource.column
                        + " = ?"
                        + " AND (code COLLATE \"C\", id) > (CAST(? AS text) COLLATE \"C\", ?)"
                        + " ORDER BY code COLLATE \"C\", id LIMIT 51",
                    String.class,
                    tenantA,
                    parent(resource),
                    "A",
                    new UUID(0, 0));
              });
      assertThat(String.join("\n", plan))
          .contains("team_tenant_" + resource.column.replace("_id", "") + "_code_id")
          .doesNotContain("Sort");
    }
  }

  private static String code(String prefix) {
    return (prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase(Locale.ROOT);
  }

  private UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.organization
          (id, name, country_code, default_locale, timezone, created_at, created_by)
        VALUES (?, 'Team Constraint Org', 'CD', 'fr', 'Africa/Kinshasa', now(), 'sub-db')
        """,
        id);
    return id;
  }

  private UUID legalEntity(UUID tenant) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.legal_entity
          (id, tenant_id, code, name, country_code, effective_from, created_at, created_by)
        VALUES (?, ?, ?, 'Entité', 'CD', DATE '2026-01-01', now(), 'sub-db')
        """,
        id,
        tenant,
        code("LE"));
    return id;
  }

  private UUID site(UUID tenant, UUID legalEntity) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.site
          (id, tenant_id, legal_entity_id, code, name, timezone, effective_from, created_at,
           created_by)
        VALUES (?, ?, ?, ?, 'Site', 'Africa/Kinshasa', DATE '2026-01-01', now(), 'sub-db')
        """,
        id,
        tenant,
        legalEntity,
        code("S"));
    return id;
  }

  private UUID unit(String table, UUID tenant, UUID site, String from, String to) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + table
            + " (id, tenant_id, site_id, code, name, effective_from, effective_to, created_at,"
            + " created_by) VALUES (?, ?, ?, ?, 'Unité', ?, ?, now(), 'sub-db')",
        id,
        tenant,
        site,
        code("U"),
        Date.valueOf(LocalDate.parse(from)),
        to == null ? null : Date.valueOf(LocalDate.parse(to)));
    return id;
  }

  private UUID teamUnder(
      TeamParentResource resource, UUID tenant, UUID site, UUID parent, String code) {
    return teamUnder(resource, tenant, site, parent, code, "2026-01-01", "2026-06-30");
  }

  private UUID teamUnder(
      TeamParentResource resource,
      UUID tenant,
      UUID site,
      UUID parent,
      String code,
      String from,
      String to) {
    return resource == TeamParentResource.DEPARTMENT
        ? team(tenant, site, parent, null, code, from, to)
        : team(tenant, site, null, parent, code, from, to);
  }

  private UUID team(
      UUID tenant,
      UUID site,
      UUID department,
      UUID costCenter,
      String code,
      String from,
      String to) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.team
          (id, tenant_id, site_id, department_id, cost_center_id, code, name, effective_from,
           effective_to, created_at, created_by)
        VALUES (?, ?, ?, ?, ?, ?, 'Équipe', ?, ?, now(), 'sub-db')
        """,
        id,
        tenant,
        site,
        department,
        costCenter,
        code,
        Date.valueOf(LocalDate.parse(from)),
        to == null ? null : Date.valueOf(LocalDate.parse(to)));
    return id;
  }

  /** Whether the statement failed in PostgreSQL (with or without a named constraint). */
  private static boolean constraintOrFailure(Runnable statement) {
    try {
      statement.run();
    } catch (DataAccessException failure) {
      return true;
    }
    return false;
  }

  /** The violated constraint's name, or {@code null} for a failure that names none. */
  private static String constraintOf(Runnable statement) {
    try {
      statement.run();
    } catch (DataAccessException failure) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof PSQLException psql) {
          ServerErrorMessage server = psql.getServerErrorMessage();
          if (server != null) {
            return server.getConstraint();
          }
        }
      }
      throw new AssertionError("no PostgreSQL error", failure);
    }
    throw new AssertionError("statement unexpectedly succeeded");
  }
}
