package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V6: the database independently prevents cross-tenant and cross-legal-entity links, duplicate
 * region codes, periods outside the parent, narrowing that would strand children, moved ownership,
 * and changing or clearing an assigned site region, while unassigned sites stay valid.
 */
@IntegrationTest
class RegionConstraintsIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;

  private UUID tenantA;
  private UUID tenantB;
  private UUID openLegal;
  private UUID closedLegal;
  private UUID foreignLegal;

  @BeforeEach
  void hierarchy() {
    tenantA = organization();
    tenantB = organization();
    openLegal = legalEntity(tenantA, "2026-01-01", null);
    closedLegal = legalEntity(tenantA, "2026-01-01", "2026-12-31");
    foreignLegal = legalEntity(tenantB, "2026-01-01", null);
  }

  @Test
  void regionParentMustBelongToTheSameTenant() {
    assertThat(constraintOf(() -> region(tenantA, foreignLegal, "CROSS", "2026-01-01", null)))
        .isEqualTo("region_legal_entity_same_tenant");
  }

  @Test
  void regionCodesAreUniquePerTenantAcrossLegalEntities() {
    region(tenantA, openLegal, "DUP-1", "2026-01-01", null);
    assertThat(
            constraintOf(() -> region(tenantA, closedLegal, "DUP-1", "2026-01-01", "2026-12-31")))
        .isEqualTo("region_code_ci_unique");
    assertThat(constraintOf(() -> region(tenantA, openLegal, "dup-2", "2026-01-01", null)))
        .isEqualTo("region_code_format");
    region(tenantB, foreignLegal, "DUP-1", "2026-01-01", null);
    site(tenantA, openLegal, null, "DUP-1", "2026-01-01", null);
  }

  @Test
  void regionContainmentIsEnforcedByTheDatabase() {
    String constraint = "region_period_within_legal_entity";
    region(tenantA, closedLegal, "IN-1", "2026-01-01", "2026-12-31");
    UUID sameDay = region(tenantA, closedLegal, "IN-2", "2026-01-01", "2026-01-01");
    region(tenantA, closedLegal, "IN-3", "2026-12-31", "2026-12-31");
    region(tenantA, openLegal, "IN-4", "2026-01-01", null);
    for (String[] period :
        new String[][] {
          {"2025-12-31", "2026-06-30"}, {"2026-06-01", "2027-01-01"}, {"2026-06-01", null}
        }) {
      assertThat(constraintOf(() -> region(tenantA, closedLegal, "OUT-X", period[0], period[1])))
          .isEqualTo(constraint);
    }
    assertThat(constraintOf(() -> region(tenantA, openLegal, "OUT-Y", "2025-12-31", null)))
        .isEqualTo(constraint);
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.region SET effective_from = ? WHERE id = ?",
                        Date.valueOf("2025-01-01"),
                        sameDay)))
        .isEqualTo(constraint);
  }

  @Test
  void legalEntityCannotBeNarrowedBelowItsRegions() {
    region(tenantA, closedLegal, "EDGE", "2026-12-31", "2026-12-31");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.legal_entity SET effective_to = ? WHERE id = ?",
                        Date.valueOf("2026-12-30"),
                        closedLegal)))
        .isEqualTo("legal_entity_period_covers_regions");
    jdbc.update("UPDATE tenant.legal_entity SET effective_to = NULL WHERE id = ?", closedLegal);
  }

  @Test
  void regionOwnershipAndParentAreImmutableAndDatesBounded() {
    UUID id = region(tenantA, openLegal, "OWN", "2026-01-01", null);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE tenant.region SET tenant_id = ? WHERE id = ?", tenantB, id))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE tenant.region SET legal_entity_id = ? WHERE id = ?", closedLegal, id))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update("UPDATE tenant.region SET id = ? WHERE id = ?", UUID.randomUUID(), id))
        .isInstanceOf(DataAccessException.class);
    assertThat(constraintOf(() -> region(tenantA, openLegal, "LATE", "2026-01-01", "3000-01-01")))
        .isEqualTo("region_effective_range");
    assertThat(constraintOf(() -> region(tenantA, openLegal, "BACK", "2026-01-02", "2026-01-01")))
        .isEqualTo("region_effective_order");
    assertThat(constraintOf(() -> regionNamed(tenantA, openLegal, "NAME", " padded")))
        .isEqualTo("region_name_trimmed_length");
    assertThat(constraintOf(() -> regionNamed(tenantA, openLegal, "CTRL", "Bad\nname")))
        .isEqualTo("region_name_no_control_chars");
  }

  @Test
  void aSiteRegionMustShareTheSiteTenantAndLegalEntity() {
    UUID regionA = region(tenantA, openLegal, "RA", "2026-01-01", null);
    UUID otherLegal = legalEntity(tenantA, "2026-01-01", null);
    UUID otherLegalRegion = region(tenantA, otherLegal, "RO", "2026-01-01", null);
    UUID foreignRegion = region(tenantB, foreignLegal, "RB", "2026-01-01", null);
    String fk = "site_region_same_tenant_and_legal_entity";

    assertThat(
            constraintOf(
                () -> site(tenantA, openLegal, otherLegalRegion, "S1", "2026-01-01", null)))
        .isEqualTo(fk);
    assertThat(
            constraintOf(() -> site(tenantA, openLegal, foreignRegion, "S2", "2026-01-01", null)))
        .isEqualTo(fk);
    assertThat(
            constraintOf(
                () -> site(tenantA, openLegal, UUID.randomUUID(), "S3", "2026-01-01", null)))
        .isEqualTo(fk);

    UUID unassigned = site(tenantA, openLegal, null, "S4", "2026-01-01", null);
    for (UUID wrong : List.of(otherLegalRegion, foreignRegion, UUID.randomUUID())) {
      assertThat(constraintOf(() -> assignDirectly(unassigned, wrong))).isEqualTo(fk);
    }
    assignDirectly(unassigned, regionA);
    assertThat(regionOf(unassigned)).isEqualTo(regionA);
  }

  @Test
  void assignedSitesStayWithinTheirRegionAndRegionsCannotStrandThem() {
    UUID closedRegion = region(tenantA, openLegal, "RC", "2026-02-01", "2026-06-30");
    UUID inside = site(tenantA, openLegal, closedRegion, "S1", "2026-02-01", "2026-06-30");
    UUID edge = site(tenantA, openLegal, closedRegion, "S2", "2026-06-30", "2026-06-30");
    assertThat(regionOf(inside)).isEqualTo(closedRegion);
    assertThat(regionOf(edge)).isEqualTo(closedRegion);
    String within = "site_period_within_region";
    assertThat(
            constraintOf(
                () -> site(tenantA, openLegal, closedRegion, "S3", "2026-01-31", "2026-03-01")))
        .isEqualTo(within);
    assertThat(constraintOf(() -> site(tenantA, openLegal, closedRegion, "S4", "2026-02-01", null)))
        .isEqualTo(within);
    UUID open = site(tenantA, openLegal, null, "S5", "2026-02-01", null);
    assertThat(constraintOf(() -> assignDirectly(open, closedRegion))).isEqualTo(within);
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.site SET effective_to = ? WHERE id = ?",
                        Date.valueOf("2026-07-01"),
                        inside)))
        .isEqualTo(within);

    String covers = "region_period_covers_sites";
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.region SET effective_to = ? WHERE id = ?",
                        Date.valueOf("2026-06-29"),
                        closedRegion)))
        .isEqualTo(covers);
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.region SET effective_from = ? WHERE id = ?",
                        Date.valueOf("2026-02-02"),
                        closedRegion)))
        .isEqualTo(covers);
    // Widening remains possible.
    jdbc.update("UPDATE tenant.region SET effective_to = NULL WHERE id = ?", closedRegion);
  }

  @Test
  void anAssignedRegionCanBeRewrittenToItselfButNotChangedOrCleared() {
    UUID first = region(tenantA, openLegal, "R1", "2026-01-01", null);
    UUID second = region(tenantA, openLegal, "R2", "2026-01-01", null);
    UUID site = site(tenantA, openLegal, null, "S1", "2026-01-01", null);
    assignDirectly(site, first);
    // No-op: value -> same value.
    assertThat(jdbc.update("UPDATE tenant.site SET region_id = ? WHERE id = ?", first, site))
        .isEqualTo(1);
    assertThat(constraintOf(() -> assignDirectly(site, second)))
        .isEqualTo("site_region_assigned_once");
    assertThat(
            constraintOf(
                () -> jdbc.update("UPDATE tenant.site SET region_id = NULL WHERE id = ?", site)))
        .isEqualTo("site_region_assigned_once");
    assertThat(regionOf(site)).isEqualTo(first);
  }

  @Test
  void unassignedSitesKeepWorkingWithAllExistingRules() {
    UUID site = site(tenantA, openLegal, null, "S1", "2026-01-01", null);
    assertThat(regionOf(site)).isNull();
    jdbc.update(
        """
        INSERT INTO tenant.department
          (id, tenant_id, site_id, code, name, effective_from, created_at, created_by)
        VALUES (?, ?, ?, 'D1', 'Département', DATE '2026-01-01', now(), 'sub-db')
        """,
        UUID.randomUUID(),
        tenantA,
        site);
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.site SET legal_entity_id = ? WHERE id = ?",
                        closedLegal,
                        site)))
        .isNull();
  }

  @Test
  void regionListUsesTheKeysetIndex() {
    List<String> plan =
        transactions.execute(
            status -> {
              jdbc.execute("SET LOCAL enable_seqscan = off");
              return jdbc.queryForList(
                  "EXPLAIN SELECT id FROM tenant.region"
                      + " WHERE tenant_id = ? AND legal_entity_id = ?"
                      + " AND (code COLLATE \"C\", id) > (CAST(? AS text) COLLATE \"C\", ?)"
                      + " ORDER BY code COLLATE \"C\", id LIMIT 51",
                  String.class,
                  tenantA,
                  openLegal,
                  "A",
                  new UUID(0, 0));
            });
    assertThat(String.join("\n", plan))
        .contains("region_tenant_legal_entity_code_id")
        .doesNotContain("Sort");
  }

  private UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.organization
          (id, name, country_code, default_locale, timezone, created_at, created_by)
        VALUES (?, 'Region Constraint Org', 'CD', 'fr', 'Africa/Kinshasa', now(), 'sub-db')
        """,
        id);
    return id;
  }

  private UUID legalEntity(UUID tenant, String from, String to) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.legal_entity
          (id, tenant_id, code, name, country_code, effective_from, effective_to, created_at,
           created_by)
        VALUES (?, ?, ?, 'Entité', 'CD', ?, ?, now(), 'sub-db')
        """,
        id,
        tenant,
        "LE-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT),
        Date.valueOf(LocalDate.parse(from)),
        to == null ? null : Date.valueOf(LocalDate.parse(to)));
    return id;
  }

  private UUID region(UUID tenant, UUID legalEntity, String code, String from, String to) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.region
          (id, tenant_id, legal_entity_id, code, name, effective_from, effective_to, created_at,
           created_by)
        VALUES (?, ?, ?, ?, 'Région', ?, ?, now(), 'sub-db')
        """,
        id,
        tenant,
        legalEntity,
        code,
        Date.valueOf(LocalDate.parse(from)),
        to == null ? null : Date.valueOf(LocalDate.parse(to)));
    return id;
  }

  private UUID regionNamed(UUID tenant, UUID legalEntity, String code, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.region
          (id, tenant_id, legal_entity_id, code, name, effective_from, created_at, created_by)
        VALUES (?, ?, ?, ?, ?, DATE '2026-01-01', now(), 'sub-db')
        """,
        id,
        tenant,
        legalEntity,
        code,
        name);
    return id;
  }

  private UUID site(
      UUID tenant, UUID legalEntity, UUID region, String code, String from, String to) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.site
          (id, tenant_id, legal_entity_id, region_id, code, name, timezone, effective_from,
           effective_to, created_at, created_by)
        VALUES (?, ?, ?, ?, ?, 'Site', 'Africa/Kinshasa', ?, ?, now(), 'sub-db')
        """,
        id,
        tenant,
        legalEntity,
        region,
        code + "-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT),
        Date.valueOf(LocalDate.parse(from)),
        to == null ? null : Date.valueOf(LocalDate.parse(to)));
    return id;
  }

  private void assignDirectly(UUID site, UUID region) {
    jdbc.update("UPDATE tenant.site SET region_id = ? WHERE id = ?", region, site);
  }

  private Object regionOf(UUID site) {
    return jdbc.queryForObject(
        "SELECT region_id FROM tenant.site WHERE id = ?", Object.class, site);
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
