package com.divalhr.core.people.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MVP-041G V24 directly against PostgreSQL, bypassing the application (the database is the final
 * authority): the refusing rollback that restores V23 exactly, append-only appointments and
 * revocations with their grammar, one unrevoked appointment per manager per date held by the
 * coverage projection and its safeguards (R98-1: a revocation frees the dates), the exact decision
 * authority shapes, the deferred substitute-decision rule (line, unrevoked appointment, never the
 * requester), and the lookups' indexes on a fresh database.
 */
@IntegrationTest
class LeaveSubstitutionMigrationIntegrationTest {

  private static final LocalDate START = LocalDate.of(2026, 3, 1);

  private static final String REQUEST =
      "INSERT INTO people.leave_request (id, tenant_id, employee_id, employment_id,"
          + " policy_version_id, start_date, end_date, requested_amount, state, submitted_at,"
          + " submitted_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'test')";

  private static final String DECISION =
      "INSERT INTO people.leave_request_decision (id, tenant_id, request_id, outcome,"
          + " approval_route, decision_authority, manager_employee_id,"
          + " delegated_from_manager_employee_id, reason_locale, reason_text, decided_at,"
          + " decided_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'fr', 'Accordé.', now(), 'test')";

  private static final String V23_DECISION =
      "INSERT INTO people.leave_request_decision (id, tenant_id, request_id, outcome,"
          + " approval_route, decision_authority, manager_employee_id, reason_locale,"
          + " reason_text, decided_at, decided_by) VALUES (?, ?, ?, ?, ?, ?, ?, 'fr',"
          + " 'Accordé.', now(), 'test')";

  private static final String SUBSTITUTION =
      "INSERT INTO people.leave_approval_substitution (id, tenant_id, manager_employee_id,"
          + " substitute_employee_id, effective_from, effective_to, reason_locale, reason_text,"
          + " created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), ?)";

  private static final String REVOCATION =
      "INSERT INTO people.leave_approval_substitution_revocation (id, tenant_id, substitution_id,"
          + " reason_locale, reason_text, revoked_at, revoked_by) VALUES (?, ?, ?, ?, ?, now(), ?)";

  private static final String COVERAGE =
      "INSERT INTO people.leave_approval_substitution_coverage (tenant_id, substitution_id,"
          + " manager_employee_id, effective_from, effective_to) VALUES (?, ?, ?, ?, ?)";

  /** Appoints in the application's order: the appointment, then its coverage row. */
  private static void appoint(Connection c, Object[] row) throws SQLException {
    exec(c, SUBSTITUTION, row);
    exec(c, COVERAGE, row[1], row[0], row[2], row[4], row[5]);
  }

  /** Revokes in the application's order: the revocation, then the coverage row is removed. */
  private static void revokeWithCoverage(Connection c, Object[] revocation) throws SQLException {
    exec(c, REVOCATION, revocation);
    exec(
        c,
        "DELETE FROM people.leave_approval_substitution_coverage WHERE tenant_id = ?"
            + " AND substitution_id = ?",
        revocation[1],
        revocation[2]);
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  private record Hired(UUID tenant, UUID employee, UUID employment) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "leave24_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      body.accept(
          new Db(
              url,
              new JdbcTemplate(
                  new DriverManagerDataSource(
                      url, postgres.getUsername(), postgres.getPassword()))));
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }

  private void migrate(String url, String target) {
    Flyway.configure()
        .dataSource(url, postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private static String resource(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Connection connect(Db db) throws SQLException {
    Connection c =
        DriverManager.getConnection(db.url(), postgres.getUsername(), postgres.getPassword());
    c.setAutoCommit(false);
    return c;
  }

  private static void exec(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      statement.execute();
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  private void refused(Db db, String constraint, SqlWork work) throws SQLException {
    try (Connection c = connect(db)) {
      assertThatThrownBy(
              () -> {
                work.run(c);
                c.commit();
              })
          .as(constraint)
          .isInstanceOfSatisfying(
              PSQLException.class,
              e -> {
                org.postgresql.util.ServerErrorMessage message = e.getServerErrorMessage();
                assertThat(message == null ? "unnamed" : String.valueOf(message.getConstraint()))
                    .isEqualTo(constraint);
              });
      c.rollback();
    }
  }

  private void committed(Db db, SqlWork work) throws SQLException {
    try (Connection c = connect(db)) {
      work.run(c);
      c.commit();
    }
  }

  private static UUID organization(JdbcTemplate db) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone,"
            + " status, created_at, created_by) VALUES (?, 'Org', 'CD', 'fr', 'Africa/Kinshasa',"
            + " 'ACTIVE', now(), 'test')",
        id);
    return id;
  }

  private static Hired hire(Connection c, UUID tenant, String number) throws SQLException {
    Hired h = new Hired(tenant, UUID.randomUUID(), UUID.randomUUID());
    UUID change = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,"
            + " search_key, created_at, created_by) VALUES (?, ?, ?, 'Ana', 'Mbuyi',"
            + " ' ana mbuyi ', now(), 'test')",
        h.employee(),
        tenant,
        number);
    exec(
        c,
        "INSERT INTO people.employment (id, tenant_id, employee_id, effective_from, created_at,"
            + " created_by) VALUES (?, ?, ?, ?, now(), 'test')",
        h.employment(),
        tenant,
        h.employee(),
        START);
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, recorded_at, recorded_by, version_after) VALUES (?, ?, ?,"
            + " ?, 'HIRE', ?, ARRAY['PLACEMENT'], now(), 'test', 0)",
        change,
        tenant,
        h.employee(),
        h.employment(),
        START);
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, legal_entity_id, site_id, created_by_change_id,"
            + " origin_change_id) VALUES (?, ?, ?, ?, 'PLACEMENT', ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        tenant,
        h.employee(),
        h.employment(),
        START,
        UUID.randomUUID(),
        UUID.randomUUID(),
        change,
        change);
    return h;
  }

  /** An open-ended MANAGER line of the report's employment, from START. */
  private static void reportsTo(Connection c, Hired report, Hired manager) throws SQLException {
    UUID change = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, timing, recorded_at, recorded_by, version_after) VALUES"
            + " (?, ?, ?, ?, 'CHANGE', ?, ARRAY['MANAGER'], 'SCHEDULED', now(), 'test', 1)",
        change,
        report.tenant(),
        report.employee(),
        report.employment(),
        START);
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, manager_employee_id, created_by_change_id,"
            + " origin_change_id) VALUES (?, ?, ?, ?, 'MANAGER', ?, ?, ?, ?)",
        UUID.randomUUID(),
        report.tenant(),
        report.employee(),
        report.employment(),
        START,
        manager.employee(),
        change,
        change);
  }

  /** A policy and its version 1 with the route; returns the version id. */
  private static UUID policyVersion(Connection c, UUID tenant, String code, String route)
      throws SQLException {
    UUID policy = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.leave_policy (id, tenant_id, code, created_at, created_by)"
            + " VALUES (?, ?, ?, now(), 'test')",
        policy,
        tenant,
        code);
    exec(
        c,
        "INSERT INTO people.leave_policy_version (id, tenant_id, policy_id, version_number,"
            + " name_en, name_fr, unit, balance_mode, annual_entitlement, minimum_service_days,"
            + " approval_route, payroll_effect, effective_from, effective_to, created_at,"
            + " created_by) VALUES (?, ?, ?, 1, 'Annual', 'Annuel', 'DAYS', 'UNTRACKED', NULL, 0,"
            + " ?, 'PAID', DATE '2026-01-01', NULL, now(), 'test')",
        version,
        tenant,
        policy,
        route);
    return version;
  }

  /** A pending request of the employee on the day; returns its id. */
  private static UUID pending(Connection c, Hired h, UUID version, LocalDate day)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        REQUEST,
        id,
        h.tenant(),
        h.employee(),
        h.employment(),
        version,
        day,
        day,
        new BigDecimal("1.00"),
        "PENDING",
        Timestamp.from(Instant.now()));
    return id;
  }

  /** A valid appointment row; each test breaks one column. */
  private static Object[] substitutionRow(UUID tenant, UUID manager, UUID substitute) {
    return new Object[] {
      UUID.randomUUID(),
      tenant,
      manager,
      substitute,
      START,
      START.plusDays(30),
      "fr",
      "Congé du responsable.",
      "test"
    };
  }

  private static Object[] revocationRow(UUID tenant, Object substitution) {
    return new Object[] {
      UUID.randomUUID(), tenant, substitution, "en", "No longer needed.", "test"
    };
  }

  private static Object[] change(Object[] row, int index, Object value) {
    Object[] copy = row.clone();
    copy[index] = value;
    return copy;
  }

  /** Records the decision with every authority column and moves the request, in one go. */
  private static void decide(
      Connection c,
      UUID tenant,
      UUID request,
      String outcome,
      String route,
      String authority,
      UUID manager,
      UUID delegatedFrom)
      throws SQLException {
    exec(
        c,
        DECISION,
        UUID.randomUUID(),
        tenant,
        request,
        outcome,
        route,
        authority,
        manager,
        delegatedFrom);
    exec(
        c,
        "UPDATE people.leave_request SET state = ? WHERE tenant_id = ? AND id = ?",
        outcome,
        tenant,
        request);
  }

  /** The whole stored appointment row, for byte-identical comparisons. */
  private static String row(Db db, Object id) {
    return db.jdbc()
        .queryForObject(
            "SELECT to_jsonb(s)::text FROM people.leave_approval_substitution s WHERE id = ?",
            String.class,
            id);
  }

  private static int history(Db db) {
    return java.util.Objects.requireNonNull(
        db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
  }

  private static int count(Db db, String sql, Object... args) {
    return java.util.Objects.requireNonNull(db.jdbc().queryForObject(sql, Integer.class, args));
  }

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV23ExactlyAndRefusesOnceASubstituteExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "23");
          String v23 = LeaveAmendmentMigrationIntegrationTest.signature(db.jdbc());
          UUID tenant = organization(db.jdbc());
          Hired[] people = new Hired[3];
          UUID[] requests = new UUID[4];
          try {
            committed(
                db,
                c -> {
                  people[0] = hire(c, tenant, "E-1");
                  people[1] = hire(c, tenant, "M-1");
                  people[2] = hire(c, tenant, "S-1");
                  reportsTo(c, people[0], people[1]);
                  UUID manager = policyVersion(c, tenant, "MG", "MANAGER");
                  UUID admin = policyVersion(c, tenant, "AD", "TENANT_ADMIN");
                  requests[0] = pending(c, people[0], manager, START);
                  exec(
                      c,
                      V23_DECISION,
                      UUID.randomUUID(),
                      tenant,
                      requests[0],
                      "APPROVED",
                      "MANAGER",
                      "MANAGER",
                      people[1].employee());
                  exec(
                      c,
                      "UPDATE people.leave_request SET state = 'APPROVED' WHERE id = ?",
                      requests[0]);
                  requests[1] = pending(c, people[0], admin, START.plusDays(2));
                  exec(
                      c,
                      V23_DECISION,
                      UUID.randomUUID(),
                      tenant,
                      requests[1],
                      "REJECTED",
                      "TENANT_ADMIN",
                      "TENANT_ADMIN",
                      null);
                  exec(
                      c,
                      "UPDATE people.leave_request SET state = 'REJECTED' WHERE id = ?",
                      requests[1]);
                  requests[2] = pending(c, people[0], manager, START.plusDays(4));
                  exec(
                      c,
                      V23_DECISION,
                      UUID.randomUUID(),
                      tenant,
                      requests[2],
                      "APPROVED",
                      "MANAGER",
                      "TENANT_ADMIN_OVERRIDE",
                      null);
                  exec(
                      c,
                      "UPDATE people.leave_request SET state = 'APPROVED' WHERE id = ?",
                      requests[2]);
                  requests[3] = pending(c, people[0], manager, START.plusDays(6));
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          String decisions =
              db.jdbc()
                  .queryForObject(
                      "SELECT string_agg(to_jsonb(d)::text, ',' ORDER BY id)"
                          + " FROM people.leave_request_decision d",
                      String.class);

          migrate(db.url(), "24");
          String v24 = LeaveAmendmentMigrationIntegrationTest.signature(db.jdbc());
          assertThat(v24)
              .isNotEqualTo(v23)
              .contains("leave_approval_substitution")
              .contains("delegated_from_manager_employee_id");
          // Every existing decision is kept, with a null delegated-from manager.
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.leave_request_decision"
                          + " WHERE delegated_from_manager_employee_id IS NULL"))
              .isEqualTo(3);
          // With every V23 authority and no substitute, the rollback restores V23 exactly.
          int recorded = history(db);
          db.jdbc().execute(resource("db/rollback/V24__rollback.sql"));
          assertThat(LeaveAmendmentMigrationIntegrationTest.signature(db.jdbc())).isEqualTo(v23);
          assertThat(history(db)).isEqualTo(recorded);
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT string_agg(to_jsonb(d)::text, ',' ORDER BY id)"
                              + " FROM people.leave_request_decision d",
                          String.class))
              .isEqualTo(decisions);

          // An appointment alone (even revoked) refuses the rollback; so does a substitute
          // decision. Nothing changes.
          db.jdbc().execute(resource("db/migration/V24__leave_approval_substitution.sql"));
          assertThat(LeaveAmendmentMigrationIntegrationTest.signature(db.jdbc())).isEqualTo(v24);
          Object[] appointment =
              substitutionRow(tenant, people[1].employee(), people[2].employee());
          try {
            committed(db, c -> appoint(c, appointment));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V24__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V24 rollback refused");
          try {
            committed(
                db,
                c ->
                    decide(
                        c,
                        tenant,
                        requests[3],
                        "APPROVED",
                        "MANAGER",
                        "MANAGER_SUBSTITUTE",
                        people[2].employee(),
                        people[1].employee()));
            committed(db, c -> revokeWithCoverage(c, revocationRow(tenant, appointment[0])));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V24__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V24 rollback refused");
          assertThat(LeaveAmendmentMigrationIntegrationTest.signature(db.jdbc())).isEqualTo(v24);
          assertThat(history(db)).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.leave_approval_substitution"))
              .isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Appointments and revocations
  // ------------------------------------------------------------------------------------------

  @Test
  void appointmentsAreAppendOnlyOnePerManagerPerDateWithTheSharedGrammar() {
    withDatabase(
        db -> {
          migrate(db.url(), "24");
          UUID tenant = organization(db.jdbc());
          UUID other = organization(db.jdbc());
          Hired[] people = new Hired[4];
          try {
            committed(
                db,
                c -> {
                  people[0] = hire(c, tenant, "M-1");
                  people[1] = hire(c, tenant, "S-1");
                  people[2] = hire(c, tenant, "S-2");
                  people[3] = hire(c, other, "X-1");
                });
            UUID manager = people[0].employee();
            UUID substitute = people[1].employee();
            Object[] good = substitutionRow(tenant, manager, substitute);
            for (Object[] bad :
                List.of(
                    new Object[] {"leave_approval_substitution_distinct", change(good, 3, manager)},
                    new Object[] {
                      "leave_approval_substitution_dates_valid", change(good, 5, START.minusDays(1))
                    },
                    new Object[] {
                      "leave_approval_substitution_dates_valid",
                      change(good, 4, LocalDate.of(1899, 12, 31))
                    },
                    new Object[] {
                      "leave_approval_substitution_dates_valid",
                      change(good, 5, LocalDate.of(3000, 1, 1))
                    },
                    new Object[] {
                      "leave_approval_substitution_locale_valid", change(good, 6, "de")
                    },
                    new Object[] {"leave_approval_substitution_reason_valid", change(good, 7, "x")},
                    new Object[] {
                      "leave_approval_substitution_reason_valid", change(good, 7, "secret\nreason")
                    },
                    new Object[] {
                      "leave_approval_substitution_created_by_length",
                      change(good, 8, "s".repeat(256))
                    },
                    // Another tenant's employee, as manager or as substitute.
                    new Object[] {
                      "leave_approval_substitution_substitute",
                      change(good, 3, people[3].employee())
                    },
                    new Object[] {
                      "leave_approval_substitution_manager", change(good, 2, people[3].employee())
                    })) {
              refused(db, (String) bad[0], c -> appoint(c, (Object[]) bad[1]));
            }
            committed(db, c -> appoint(c, good));
            // Another appointment of the manager overlapping by one day, with any substitute, is
            // refused; adjacent periods and other managers are not.
            Object[] overlapping =
                change(
                    change(substitutionRow(tenant, manager, people[2].employee()), 4, good[5]),
                    5,
                    START.plusDays(60));
            refused(
                db,
                "leave_approval_substitution_coverage_no_overlap",
                c -> appoint(c, overlapping));
            Object[] adjacent =
                change(change(overlapping, 0, UUID.randomUUID()), 4, START.plusDays(31));
            committed(
                db,
                c -> {
                  appoint(c, adjacent);
                  appoint(c, substitutionRow(tenant, substitute, manager));
                });
            // R98-1: the revocation frees the dates; the revoked appointment keeps them as
            // evidence.
            String revokedBefore = row(db, good[0]);
            committed(db, c -> revokeWithCoverage(c, revocationRow(tenant, good[0])));
            Object[] replacement =
                change(
                    change(change(good, 0, UUID.randomUUID()), 3, people[2].employee()),
                    4,
                    START.plusDays(10));
            committed(db, c -> appoint(c, replacement));
            assertThat(row(db, good[0])).isEqualTo(revokedBefore);
            // The replacement now holds the dates against another appointment.
            refused(
                db,
                "leave_approval_substitution_coverage_no_overlap",
                c -> appoint(c, change(change(good, 0, UUID.randomUUID()), 4, START.plusDays(20))));
            // Append-only.
            refused(
                db,
                "leave_approval_substitution_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE people.leave_approval_substitution SET effective_to = ?"
                            + " WHERE id = ?",
                        START.plusDays(3),
                        good[0]));
            refused(
                db,
                "leave_approval_substitution_immutable",
                c ->
                    exec(
                        c, "DELETE FROM people.leave_approval_substitution WHERE id = ?", good[0]));
            refused(
                db,
                "leave_approval_substitution_no_truncate",
                c -> exec(c, "TRUNCATE people.leave_approval_substitution CASCADE"));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Test
  void revocationsAreAppendOnlyAndOnePerAppointment() {
    withDatabase(
        db -> {
          migrate(db.url(), "24");
          UUID tenant = organization(db.jdbc());
          UUID other = organization(db.jdbc());
          Object[] appointment = new Object[1];
          try {
            committed(
                db,
                c -> {
                  Hired manager = hire(c, tenant, "M-1");
                  Hired substitute = hire(c, tenant, "S-1");
                  appointment[0] =
                      substitutionRow(tenant, manager.employee(), substitute.employee());
                  appoint(c, (Object[]) appointment[0]);
                });
            Object id = ((Object[]) appointment[0])[0];
            Object[] good = revocationRow(tenant, id);
            for (Object[] bad :
                List.of(
                    new Object[] {
                      "leave_approval_substitution_revocation_locale_valid", change(good, 3, "es")
                    },
                    new Object[] {
                      "leave_approval_substitution_revocation_reason_valid",
                      change(good, 4, " untrimmed ")
                    },
                    new Object[] {
                      "leave_approval_substitution_revocation_revoked_by_length",
                      change(good, 5, "")
                    },
                    new Object[] {
                      "leave_approval_substitution_revocation_substitution", change(good, 1, other)
                    },
                    new Object[] {
                      "leave_approval_substitution_revocation_substitution",
                      change(good, 2, UUID.randomUUID())
                    })) {
              refused(db, (String) bad[0], c -> revokeWithCoverage(c, (Object[]) bad[1]));
            }
            committed(db, c -> revokeWithCoverage(c, good));
            refused(
                db,
                "leave_approval_substitution_revocation_substitution_unique",
                c -> exec(c, REVOCATION, revocationRow(tenant, id)));
            refused(
                db,
                "leave_approval_substitution_revocation_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE people.leave_approval_substitution_revocation"
                            + " SET reason_text = 'Other.'"));
            refused(
                db,
                "leave_approval_substitution_revocation_immutable",
                c -> exec(c, "DELETE FROM people.leave_approval_substitution_revocation"));
            refused(
                db,
                "leave_approval_substitution_revocation_no_truncate",
                c -> exec(c, "TRUNCATE people.leave_approval_substitution_revocation"));
            // The appointment it revokes can no longer be removed either.
            refused(
                db,
                "leave_approval_substitution_immutable",
                c -> exec(c, "DELETE FROM people.leave_approval_substitution"));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Coverage safeguards (R98-1)
  // ------------------------------------------------------------------------------------------

  @Test
  void directSqlCannotLeaveCoverageOutOfStepWithItsAppointment() {
    withDatabase(
        db -> {
          migrate(db.url(), "24");
          UUID tenant = organization(db.jdbc());
          UUID other = organization(db.jdbc());
          Hired[] people = new Hired[4];
          Object[][] rows = new Object[1][];
          try {
            committed(
                db,
                c -> {
                  people[0] = hire(c, tenant, "M-1");
                  people[1] = hire(c, tenant, "S-1");
                  people[2] = hire(c, tenant, "M-2");
                  people[3] = hire(c, other, "X-1");
                  rows[0] = substitutionRow(tenant, people[0].employee(), people[1].employee());
                  appoint(c, rows[0]);
                });
            Object[] a = rows[0];
            // Coverage that does not match its appointment's tenant, manager or dates.
            Object[] b = substitutionRow(tenant, people[2].employee(), people[1].employee());
            for (Object[] bad :
                List.of(
                    new Object[] {other, b[0], b[2], b[4], b[5]},
                    new Object[] {b[1], b[0], people[1].employee(), b[4], b[5]},
                    new Object[] {b[1], b[0], b[2], START.plusDays(1), b[5]},
                    new Object[] {b[1], b[0], b[2], b[4], START.plusDays(40)},
                    new Object[] {b[1], UUID.randomUUID(), b[2], b[4], b[5]})) {
              refused(
                  db,
                  "leave_approval_substitution_coverage_appointment",
                  c -> {
                    exec(c, SUBSTITUTION, b);
                    exec(c, COVERAGE, bad);
                  });
            }
            // Never changed or truncated; never deleted while the appointment is not revoked.
            refused(
                db,
                "leave_approval_substitution_coverage_guard",
                c ->
                    exec(
                        c,
                        "UPDATE people.leave_approval_substitution_coverage"
                            + " SET effective_to = effective_to - 1"));
            refused(
                db,
                "leave_approval_substitution_coverage_guard",
                c -> exec(c, "DELETE FROM people.leave_approval_substitution_coverage"));
            refused(
                db,
                "leave_approval_substitution_coverage_no_truncate",
                c -> exec(c, "TRUNCATE people.leave_approval_substitution_coverage"));
            // An appointment never commits without its coverage.
            refused(
                db,
                "leave_approval_substitution_covered",
                c ->
                    exec(
                        c,
                        SUBSTITUTION,
                        substitutionRow(tenant, people[2].employee(), people[1].employee())));
            // A revocation never commits while the coverage is still there.
            refused(
                db,
                "leave_approval_substitution_revocation_uncovered",
                c -> exec(c, REVOCATION, revocationRow(tenant, a[0])));
            // Once revoked, coverage can never be (re)inserted for that appointment.
            committed(db, c -> revokeWithCoverage(c, revocationRow(tenant, a[0])));
            refused(
                db,
                "leave_approval_substitution_coverage_guard",
                c -> exec(c, COVERAGE, a[1], a[0], a[2], a[4], a[5]));
            assertThat(
                    count(
                        db,
                        "SELECT count(*) FROM people.leave_approval_substitution_coverage"
                            + " WHERE substitution_id = ?",
                        a[0]))
                .isZero();
            assertThat(count(db, "SELECT count(*) FROM people.leave_approval_substitution"))
                .isEqualTo(1);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Decision authority
  // ------------------------------------------------------------------------------------------

  @Test
  void decisionsHaveExactlyTheFourAuthorityShapesAndASubstituteNeedsItsAppointment() {
    withDatabase(
        db -> {
          migrate(db.url(), "24");
          UUID tenant = organization(db.jdbc());
          Hired[] people = new Hired[4];
          UUID[] versions = new UUID[2];
          Object[] appointment = new Object[1];
          try {
            committed(
                db,
                c -> {
                  people[0] = hire(c, tenant, "E-1");
                  people[1] = hire(c, tenant, "M-1");
                  people[2] = hire(c, tenant, "S-1");
                  people[3] = hire(c, tenant, "O-1");
                  reportsTo(c, people[0], people[1]);
                  // The substitute reports to the same manager (self-approval test below).
                  reportsTo(c, people[2], people[1]);
                  versions[0] = policyVersion(c, tenant, "MG", "MANAGER");
                  versions[1] = policyVersion(c, tenant, "AD", "TENANT_ADMIN");
                  appointment[0] =
                      substitutionRow(tenant, people[1].employee(), people[2].employee());
                  appoint(c, (Object[]) appointment[0]);
                });
            UUID employee = people[0].employee();
            UUID manager = people[1].employee();
            UUID substitute = people[2].employee();
            UUID outsider = people[3].employee();
            UUID[] requests = new UUID[1];
            committed(db, c -> requests[0] = pending(c, people[0], versions[0], START));
            UUID request = requests[0];

            // Shapes outside the four are refused by the check constraint.
            for (Object[] shape :
                List.of(
                    new Object[] {"MANAGER", "MANAGER", manager, substitute},
                    new Object[] {"MANAGER", "MANAGER_SUBSTITUTE", substitute, null},
                    new Object[] {"MANAGER", "MANAGER_SUBSTITUTE", null, manager},
                    new Object[] {"MANAGER", "MANAGER_SUBSTITUTE", manager, manager},
                    new Object[] {"MANAGER", "TENANT_ADMIN_OVERRIDE", null, manager},
                    new Object[] {"TENANT_ADMIN", "MANAGER_SUBSTITUTE", substitute, manager},
                    new Object[] {"MANAGER", "TENANT_ADMIN", null, null})) {
              refused(
                  db,
                  "leave_request_decision_authority_shape",
                  c ->
                      decide(
                          c,
                          tenant,
                          request,
                          "APPROVED",
                          (String) shape[0],
                          (String) shape[1],
                          (UUID) shape[2],
                          (UUID) shape[3]));
            }
            // A substitute decision needs the delegating manager as the request's line ...
            refused(
                db,
                "leave_request_decision_consistent",
                c ->
                    decide(
                        c,
                        tenant,
                        request,
                        "APPROVED",
                        "MANAGER",
                        "MANAGER_SUBSTITUTE",
                        substitute,
                        outsider));
            // ... and an appointment of that manager naming the deciding substitute.
            refused(
                db,
                "leave_request_decision_consistent",
                c ->
                    decide(
                        c,
                        tenant,
                        request,
                        "APPROVED",
                        "MANAGER",
                        "MANAGER_SUBSTITUTE",
                        outsider,
                        manager));
            // The substitute never decides their own request.
            UUID[] own = new UUID[1];
            committed(db, c -> own[0] = pending(c, people[2], versions[0], START));
            refused(
                db,
                "leave_request_decision_consistent",
                c ->
                    decide(
                        c,
                        tenant,
                        own[0],
                        "APPROVED",
                        "MANAGER",
                        "MANAGER_SUBSTITUTE",
                        substitute,
                        manager));
            // The valid shape commits; the decision is append-only like every decision.
            committed(
                db,
                c ->
                    decide(
                        c,
                        tenant,
                        request,
                        "REJECTED",
                        "MANAGER",
                        "MANAGER_SUBSTITUTE",
                        substitute,
                        manager));
            refused(
                db,
                "leave_request_decision_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE people.leave_request_decision"
                            + " SET delegated_from_manager_employee_id = NULL"));
            // Once the appointment is revoked, no further substitute decision commits.
            UUID[] later = new UUID[1];
            committed(
                db,
                c -> {
                  later[0] = pending(c, people[0], versions[0], START.plusDays(3));
                  revokeWithCoverage(c, revocationRow(tenant, ((Object[]) appointment[0])[0]));
                });
            refused(
                db,
                "leave_request_decision_consistent",
                c ->
                    decide(
                        c,
                        tenant,
                        later[0],
                        "APPROVED",
                        "MANAGER",
                        "MANAGER_SUBSTITUTE",
                        substitute,
                        manager));
            // The primary manager still decides it.
            committed(
                db,
                c -> decide(c, tenant, later[0], "APPROVED", "MANAGER", "MANAGER", manager, null));
            assertThat(
                    count(
                        db,
                        "SELECT count(*) FROM people.leave_request_decision"
                            + " WHERE decision_authority = 'MANAGER_SUBSTITUTE'"
                            + " AND manager_employee_id = ?"
                            + " AND delegated_from_manager_employee_id = ?",
                        substitute,
                        manager))
                .isEqualTo(1);
            assertThat(employee).isNotNull();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Query plans (narrow index assertions; fresh database, no shared-table statistics)
  // ------------------------------------------------------------------------------------------

  @Test
  void theLookupsReadTheirIndexes() {
    withDatabase(
        db -> {
          migrate(db.url(), "24");
          UUID tenant = organization(db.jdbc());
          try {
            UUID someone = UUID.randomUUID();
            LocalDate today = START.plusDays(5);
            // The substitute's effective appointments, each with its revocation lookup.
            assertThat(
                    plan(
                        db,
                        "SELECT s.manager_employee_id FROM people.leave_approval_substitution s"
                            + " WHERE s.tenant_id = ? AND s.substitute_employee_id = ?"
                            + " AND s.effective_from <= ? AND s.effective_to >= ?"
                            + " AND NOT EXISTS (SELECT 1"
                            + " FROM people.leave_approval_substitution_revocation x"
                            + " WHERE x.tenant_id = s.tenant_id AND x.substitution_id = s.id)",
                        tenant,
                        someone,
                        today,
                        today))
                .contains("leave_approval_substitution_substitute")
                .contains("leave_approval_substitution_revocation_substitution_unique");
            // The manager inbox (JdbcLeaveRequestRepository.managerQueue): the substitute's
            // effective appointments, the primary and delegated reporting lines, then each line's
            // pending requests.
            assertThat(
                    plan(
                        db,
                        "WITH delegators AS MATERIALIZED (SELECT s.manager_employee_id FROM"
                            + " people.leave_approval_substitution s WHERE s.tenant_id = ? AND"
                            + " s.substitute_employee_id = ? AND s.effective_from <= ? AND"
                            + " s.effective_to >= ? AND NOT EXISTS (SELECT 1 FROM"
                            + " people.leave_approval_substitution_revocation x WHERE x.tenant_id ="
                            + " s.tenant_id AND x.substitution_id = s.id)), lines AS MATERIALIZED"
                            + " (SELECT a.employment_id, a.effective_from, a.effective_to, true AS"
                            + " primary_line FROM people.employment_assignment a WHERE a.tenant_id"
                            + " = ? AND a.manager_employee_id = ? AND a.kind = 'MANAGER' AND"
                            + " a.superseded_by_change_id IS NULL UNION ALL SELECT a.employment_id,"
                            + " a.effective_from, a.effective_to, false FROM delegators d JOIN"
                            + " people.employment_assignment a ON a.tenant_id = ? AND"
                            + " a.manager_employee_id = d.manager_employee_id AND a.kind ="
                            + " 'MANAGER' AND a.superseded_by_change_id IS NULL) SELECT q.id FROM"
                            + " lines l CROSS JOIN LATERAL (SELECT r.id FROM people.leave_request r"
                            + " WHERE r.tenant_id = ? AND r.employment_id = l.employment_id AND"
                            + " r.state = 'PENDING' AND l.effective_from <= r.start_date AND"
                            + " (l.primary_line OR r.employee_id <> ?) ORDER BY r.submitted_at"
                            + " DESC, r.id DESC LIMIT 26) q",
                        tenant,
                        someone,
                        today,
                        today,
                        tenant,
                        someone,
                        tenant,
                        tenant,
                        someone))
                .contains("leave_approval_substitution_substitute")
                .contains("leave_approval_substitution_revocation_substitution_unique")
                .contains("employment_assignment_reports")
                .contains("leave_request_employment_pending");
            // The overlap pre-check reads the coverage exclusion's index.
            assertThat(
                    plan(
                        db,
                        "SELECT c.substitution_id FROM people.leave_approval_substitution_coverage"
                            + " c WHERE c.tenant_id = ? AND c.manager_employee_id = ? AND"
                            + " daterange(c.effective_from, c.effective_to, '[]') && ?::daterange",
                        tenant,
                        someone,
                        "[" + today + "," + today + "]"))
                .contains("leave_approval_substitution_coverage_no_overlap");
            // The revocation's delete reads the coverage key.
            assertThat(
                    plan(
                        db,
                        "DELETE FROM people.leave_approval_substitution_coverage"
                            + " WHERE tenant_id = ? AND substitution_id = ?",
                        tenant,
                        someone))
                .contains("leave_approval_substitution_coverage_pkey");
            // The administrators' list, newest first, without a sort.
            assertThat(
                    plan(
                        db,
                        "SELECT s.id FROM people.leave_approval_substitution s"
                            + " WHERE s.tenant_id = ? AND (s.created_at, s.id) < (?, ?)"
                            + " ORDER BY s.created_at DESC, s.id DESC LIMIT 26",
                        tenant,
                        Timestamp.from(Instant.now()),
                        someone))
                .containsPattern("Index (Only )?Scan using leave_approval_substitution_list_order")
                .doesNotContain("Sort");
            assertThat(
                    count(
                        db,
                        "SELECT count(*) FROM pg_indexes WHERE schemaname = 'people'"
                            + " AND tablename = 'leave_approval_substitution'"))
                .isEqualTo(5);
            assertThat(
                    count(
                        db,
                        "SELECT count(*) FROM pg_indexes WHERE schemaname = 'people'"
                            + " AND tablename = 'leave_approval_substitution_revocation'"))
                .isEqualTo(2);
            assertThat(
                    count(
                        db,
                        "SELECT count(*) FROM pg_indexes WHERE schemaname = 'people'"
                            + " AND tablename = 'leave_approval_substitution_coverage'"))
                .isEqualTo(2);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private String plan(Db db, String sql, Object... args) throws SQLException {
    try (Connection c = connect(db)) {
      exec(c, "SET LOCAL enable_seqscan = off");
      StringBuilder plan = new StringBuilder();
      try (PreparedStatement explain = c.prepareStatement("EXPLAIN " + sql)) {
        for (int i = 0; i < args.length; i++) {
          explain.setObject(i + 1, args[i]);
        }
        try (var rows = explain.executeQuery()) {
          while (rows.next()) {
            plan.append(rows.getString(1)).append('\n');
          }
        }
      }
      c.rollback();
      return plan.toString();
    }
  }
}
