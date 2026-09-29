package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The database is an independent line of defence behind the services: direct SQL cannot cross
 * tenants, duplicate codes by case, violate containment, or move ownership.
 */
@IntegrationTest
class HierarchyConstraintsIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  private UUID tenantA;
  private UUID tenantB;

  @BeforeEach
  void organizations() {
    tenantA = organization();
    tenantB = organization();
  }

  @Test
  void siteCannotReferenceAnotherTenantsLegalEntity() {
    UUID foreignParent = legalEntity(tenantB, "FOREIGN", "2026-01-01", null);
    assertThat(constraintOf(() -> site(tenantA, foreignParent, "CROSS", "2026-01-01", null)))
        .isEqualTo("site_legal_entity_same_tenant");
  }

  @Test
  void codesAreUniquePerTenantIgnoringCase() {
    legalEntity(tenantA, "DUP-1", "2026-01-01", null);
    // Lower-case codes never reach the index: the format check requires normalized codes.
    assertThat(constraintOf(() -> legalEntity(tenantA, "dup-2", "2026-01-01", null)))
        .isEqualTo("legal_entity_code_format");
    assertThat(constraintOf(() -> legalEntity(tenantA, "DUP-1", "2026-01-01", null)))
        .isEqualTo("legal_entity_code_ci_unique");
    legalEntity(tenantB, "DUP-1", "2026-01-01", null);

    UUID parent = legalEntity(tenantA, "PARENT", "2026-01-01", null);
    site(tenantA, parent, "S-DUP", "2026-01-01", null);
    assertThat(constraintOf(() -> site(tenantA, parent, "S-DUP", "2026-01-01", null)))
        .isEqualTo("site_code_ci_unique");
  }

  @Test
  void containmentIsEnforcedByTheDatabase() {
    UUID closed = legalEntity(tenantA, "CLOSED", "2026-01-01", "2026-12-31");
    UUID open = legalEntity(tenantA, "OPEN", "2026-01-01", null);

    // Inclusive boundaries, same-day periods and open/closed combinations are accepted.
    site(tenantA, closed, "IN-1", "2026-01-01", "2026-12-31");
    site(tenantA, closed, "IN-2", "2026-01-01", "2026-01-01");
    site(tenantA, closed, "IN-3", "2026-12-31", "2026-12-31");
    site(tenantA, open, "IN-4", "2026-01-01", null);
    site(tenantA, open, "IN-5", "2099-01-01", "2099-01-01");

    for (String[] period :
        new String[][] {
          {"2025-12-31", "2026-06-30"},
          {"2026-06-01", "2027-01-01"},
          {"2026-06-01", null},
        }) {
      assertThat(constraintOf(() -> site(tenantA, closed, "OUT-X", period[0], period[1])))
          .isEqualTo("site_period_within_legal_entity");
    }
    assertThat(constraintOf(() -> site(tenantA, open, "OUT-Y", "2025-12-31", null)))
        .isEqualTo("site_period_within_legal_entity");

    // A parent cannot be narrowed below its sites, nor a site moved outside its parent.
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.legal_entity SET effective_to = ? WHERE id = ?",
                        Date.valueOf("2026-12-30"),
                        closed)))
        .isEqualTo("legal_entity_period_covers_sites");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE tenant.site SET effective_from = ? WHERE code = 'IN-2'"
                            + " AND tenant_id = ?",
                        Date.valueOf("2025-01-01"),
                        tenantA)))
        .isEqualTo("site_period_within_legal_entity");
  }

  @Test
  void ownershipIsImmutableAndDatesAreBounded() {
    UUID parent = legalEntity(tenantA, "OWNER", "2026-01-01", null);
    UUID other = legalEntity(tenantA, "OTHER", "2026-01-01", null);
    UUID siteId = site(tenantA, parent, "OWNED", "2026-01-01", null);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE tenant.legal_entity SET tenant_id = ? WHERE id = ?", tenantB, parent))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE tenant.site SET legal_entity_id = ? WHERE id = ?", other, siteId))
        .isInstanceOf(DataAccessException.class);

    assertThat(constraintOf(() -> legalEntity(tenantA, "OLD", "1899-12-31", null)))
        .isEqualTo("legal_entity_effective_range");
    assertThat(constraintOf(() -> legalEntity(tenantA, "LATE", "2026-01-01", "3000-01-01")))
        .isEqualTo("legal_entity_effective_range");
    assertThat(constraintOf(() -> legalEntity(tenantA, "BACK", "2026-01-02", "2026-01-01")))
        .isEqualTo("legal_entity_effective_order");
  }

  @Test
  void widenedOperationNamesAreAcceptedAndMalformedOnesStillRejected() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO platform.audit_event
          (id, occurred_at, actor_subject, action, resource_type, resource_id, tenant_id, result,
           correlation_id, metadata, after_state_sha256)
        VALUES (?, now(), 'sub-db', 'legal-entity.create', 'legal-entity', ?, ?, 'SUCCESS',
                'corr-db-0001', '{}'::jsonb, repeat('a', 64))
        """,
        id,
        id,
        tenantA);
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        """
                        INSERT INTO platform.audit_event
                          (id, occurred_at, actor_subject, action, resource_type, resource_id,
                           tenant_id, result, correlation_id, metadata, after_state_sha256)
                        VALUES (?, now(), 'sub-db', '-legal.create', 'legal-entity', ?, ?,
                                'SUCCESS', 'corr-db-0002', '{}'::jsonb, repeat('a', 64))
                        """,
                        UUID.randomUUID(),
                        id,
                        tenantA)))
        .isEqualTo("audit_action_format");
  }

  private UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.organization
          (id, name, country_code, default_locale, timezone, created_at, created_by)
        VALUES (?, 'Hierarchy Constraint Org', 'CD', 'fr', 'Africa/Kinshasa', now(), 'sub-db')
        """,
        id);
    return id;
  }

  private UUID legalEntity(UUID tenant, String code, String from, String to) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.legal_entity
          (id, tenant_id, code, name, country_code, effective_from, effective_to, created_at,
           created_by)
        VALUES (?, ?, ?, 'Entité de test', 'CD', ?, ?, now(), 'sub-db')
        """,
        id,
        tenant,
        code,
        Date.valueOf(LocalDate.parse(from)),
        to == null ? null : Date.valueOf(LocalDate.parse(to)));
    return id;
  }

  private UUID site(UUID tenant, UUID parent, String code, String from, String to) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.site
          (id, tenant_id, legal_entity_id, code, name, timezone, effective_from, effective_to,
           created_at, created_by)
        VALUES (?, ?, ?, ?, 'Site de test', 'Africa/Kinshasa', ?, ?, now(), 'sub-db')
        """,
        id,
        tenant,
        parent,
        code,
        Date.valueOf(LocalDate.parse(from)),
        to == null ? null : Date.valueOf(LocalDate.parse(to)));
    return id;
  }

  /** Runs a statement expected to fail and returns the violated constraint's name. */
  private static String constraintOf(Runnable statement) {
    try {
      statement.run();
    } catch (DataAccessException failure) {
      Throwable cause = failure;
      while (cause != null) {
        if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
          return psql.getServerErrorMessage().getConstraint();
        }
        cause = cause.getCause();
      }
      throw new AssertionError("no PostgreSQL error in " + failure.getClass().getName(), failure);
    }
    throw new AssertionError("statement unexpectedly succeeded");
  }
}
