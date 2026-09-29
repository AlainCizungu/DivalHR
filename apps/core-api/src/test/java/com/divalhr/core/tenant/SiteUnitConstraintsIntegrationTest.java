package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
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
 * V5: the database independently prevents cross-tenant parents, case-insensitive duplicates,
 * periods outside the site, moved ownership, and site updates that would strand children.
 */
@IntegrationTest
class SiteUnitConstraintsIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;

  private UUID tenantA;
  private UUID tenantB;
  private UUID closedSite;
  private UUID openSite;
  private UUID foreignSite;

  @BeforeEach
  void hierarchy() {
    tenantA = organization();
    tenantB = organization();
    UUID legalA = legalEntity(tenantA);
    UUID legalB = legalEntity(tenantB);
    closedSite = site(tenantA, legalA, "2026-01-01", "2026-12-31");
    openSite = site(tenantA, legalA, "2026-01-01", null);
    foreignSite = site(tenantB, legalB, "2026-01-01", null);
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void parentMustBelongToTheSameTenant(SiteUnitResource resource) {
    assertThat(
            constraintOf(() -> unit(resource, tenantA, foreignSite, "CROSS", "2026-01-01", null)))
        .isEqualTo(resource.bareTable() + "_site_same_tenant");
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void codesAreUniquePerTenantAndTypeIgnoringCase(SiteUnitResource resource) {
    unit(resource, tenantA, openSite, "DUP-1", "2026-01-01", null);
    assertThat(
            constraintOf(
                () -> unit(resource, tenantA, closedSite, "DUP-1", "2026-01-01", "2026-12-31")))
        .isEqualTo(resource.bareTable() + "_code_ci_unique");
    assertThat(constraintOf(() -> unit(resource, tenantA, openSite, "dup-2", "2026-01-01", null)))
        .isEqualTo(resource.bareTable() + "_code_format");
    SiteUnitResource other =
        resource == SiteUnitResource.DEPARTMENT
            ? SiteUnitResource.COST_CENTER
            : SiteUnitResource.DEPARTMENT;
    unit(other, tenantA, openSite, "DUP-1", "2026-01-01", null);
    unit(resource, tenantB, foreignSite, "DUP-1", "2026-01-01", null);
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void containmentIsEnforcedByTheDatabase(SiteUnitResource resource) {
    String constraint = resource.bareTable() + "_period_within_site";
    unit(resource, tenantA, closedSite, "IN-1", "2026-01-01", "2026-12-31");
    unit(resource, tenantA, closedSite, "IN-2", "2026-01-01", "2026-01-01");
    unit(resource, tenantA, closedSite, "IN-3", "2026-12-31", "2026-12-31");
    unit(resource, tenantA, openSite, "IN-4", "2026-01-01", null);
    for (String[] period :
        new String[][] {
          {"2025-12-31", "2026-06-30"}, {"2026-06-01", "2027-01-01"}, {"2026-06-01", null}
        }) {
      assertThat(
              constraintOf(
                  () -> unit(resource, tenantA, closedSite, "OUT-X", period[0], period[1])))
          .isEqualTo(constraint);
    }
    assertThat(constraintOf(() -> unit(resource, tenantA, openSite, "OUT-Y", "2025-12-31", null)))
        .isEqualTo(constraint);
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE "
                            + resource.table
                            + " SET effective_from = ? WHERE code = 'IN-2'"
                            + " AND tenant_id = ?",
                        Date.valueOf("2025-01-01"),
                        tenantA)))
        .isEqualTo(constraint);
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void siteCannotBeNarrowedBelowItsChildren(SiteUnitResource resource) {
    unit(resource, tenantA, closedSite, "EDGE", "2026-12-31", "2026-12-31");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.site SET effective_to = ? WHERE id = ?",
                        Date.valueOf("2026-12-30"),
                        closedSite)))
        .isEqualTo("site_period_covers_units");
    unit(resource, tenantA, openSite, "OPEN", "2026-01-01", null);
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.site SET effective_to = ? WHERE id = ?",
                        Date.valueOf("2030-12-31"),
                        openSite)))
        .isEqualTo("site_period_covers_units");
    // Widening remains possible.
    jdbc.update("UPDATE tenant.site SET effective_to = NULL WHERE id = ?", closedSite);
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void ownershipAndParentAreImmutableAndDatesBounded(SiteUnitResource resource) {
    UUID id = unit(resource, tenantA, openSite, "OWN", "2026-01-01", null);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE " + resource.table + " SET tenant_id = ? WHERE id = ?", tenantB, id))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE " + resource.table + " SET site_id = ? WHERE id = ?", closedSite, id))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE " + resource.table + " SET id = ? WHERE id = ?", UUID.randomUUID(), id))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            constraintOf(
                () -> unit(resource, tenantA, openSite, "LATE", "2026-01-01", "3000-01-01")))
        .isEqualTo(resource.bareTable() + "_effective_range");
    assertThat(
            constraintOf(
                () -> unit(resource, tenantA, openSite, "BACK", "2026-01-02", "2026-01-01")))
        .isEqualTo(resource.bareTable() + "_effective_order");
  }

  @Test
  void listQueriesUseTheKeysetIndexes() {
    for (SiteUnitResource resource : SiteUnitResource.values()) {
      List<String> plan =
          transactions.execute(
              status -> {
                jdbc.execute("SET LOCAL enable_seqscan = off");
                return jdbc.queryForList(
                    "EXPLAIN SELECT id FROM "
                        + resource.table
                        + " WHERE tenant_id = ? AND site_id = ?"
                        + " AND (code COLLATE \"C\", id) > (CAST(? AS text) COLLATE \"C\", ?)"
                        + " ORDER BY code COLLATE \"C\", id LIMIT 51",
                    String.class,
                    tenantA,
                    openSite,
                    "A",
                    new UUID(0, 0));
              });
      assertThat(String.join("\n", plan))
          .contains(resource.bareTable() + "_tenant_site_code_id")
          .doesNotContain("Sort");
    }
  }

  private UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.organization
          (id, name, country_code, default_locale, timezone, created_at, created_by)
        VALUES (?, 'Site Unit Constraint Org', 'CD', 'fr', 'Africa/Kinshasa', now(), 'sub-db')
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
        "LE-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT));
    return id;
  }

  private UUID site(UUID tenant, UUID legalEntity, String from, String to) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.site
          (id, tenant_id, legal_entity_id, code, name, timezone, effective_from, effective_to,
           created_at, created_by)
        VALUES (?, ?, ?, ?, 'Site', 'Africa/Kinshasa', ?, ?, now(), 'sub-db')
        """,
        id,
        tenant,
        legalEntity,
        "S-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT),
        Date.valueOf(LocalDate.parse(from)),
        to == null ? null : Date.valueOf(LocalDate.parse(to)));
    return id;
  }

  private UUID unit(
      SiteUnitResource resource, UUID tenant, UUID site, String code, String from, String to) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + resource.table
            + " (id, tenant_id, site_id, code, name, effective_from, effective_to, created_at,"
            + " created_by) VALUES (?, ?, ?, ?, 'Unité', ?, ?, now(), 'sub-db')",
        id,
        tenant,
        site,
        code,
        Date.valueOf(LocalDate.parse(from)),
        to == null ? null : Date.valueOf(LocalDate.parse(to)));
    return id;
  }

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
