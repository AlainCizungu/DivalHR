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
 * MVP-041F V23 directly against PostgreSQL, bypassing the application (the database is the final
 * authority): the refusing rollback that restores V22 exactly, the one APPROVED-to-WITHDRAWN
 * transition with every other move refused, the terminal evidence of a withdrawn request (its
 * unchanged APPROVED decision and one withdrawal, deferred), append-only withdrawals with the
 * shared reason grammar, the overlap release on commit, and the withdrawal lookup's index.
 */
@IntegrationTest
class LeaveWithdrawalMigrationIntegrationTest {

  private static final LocalDate START = LocalDate.of(2026, 3, 1);

  private static final String REQUEST =
      "INSERT INTO people.leave_request (id, tenant_id, employee_id, employment_id,"
          + " policy_version_id, start_date, end_date, requested_amount, state, submitted_at,"
          + " submitted_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'test')";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  private record Hired(UUID tenant, UUID employee, UUID employment) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "leave23_" + UUID.randomUUID().toString().replace("-", "");
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

  /** A valid request row; each test breaks one column. */
  private static Object[] row(Hired h, UUID version, LocalDate start, LocalDate end) {
    return new Object[] {
      UUID.randomUUID(),
      h.tenant(),
      h.employee(),
      h.employment(),
      version,
      start,
      end,
      new BigDecimal("2.50"),
      "PENDING",
      Timestamp.from(Instant.now())
    };
  }

  private static Object[] change(Object[] row, int index, Object value) {
    Object[] copy = row.clone();
    copy[index] = value;
    return copy;
  }

  private static final String DECISION =
      "INSERT INTO people.leave_request_decision (id, tenant_id, request_id, outcome,"
          + " approval_route, manager_employee_id, reason_locale, reason_text, decided_at,"
          + " decided_by, decision_authority) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?)";

  /** A valid decision row; each test breaks one column. */
  private static Object[] decisionRow(
      UUID tenant, Object request, String outcome, String route, UUID manager) {
    return new Object[] {
      UUID.randomUUID(), tenant, request, outcome, route, manager, "fr", "Accordé.", "test", route
    };
  }

  /** Records the decision and moves the request in one transaction. */
  private static void decide(Connection c, Object[] decision) throws SQLException {
    exec(c, DECISION, decision);
    exec(
        c,
        "UPDATE people.leave_request SET state = ? WHERE tenant_id = ? AND id = ?",
        decision[3],
        decision[1],
        decision[2]);
  }

  private static final String CANCELLATION =
      "INSERT INTO people.leave_request_cancellation (id, tenant_id, request_id, reason_locale,"
          + " reason_text, cancelled_at, cancelled_by) VALUES (?, ?, ?, ?, ?, now(), ?)";

  /** A valid cancellation row; each test breaks one column. */
  private static Object[] cancellationRow(UUID tenant, Object request) {
    return new Object[] {UUID.randomUUID(), tenant, request, "fr", "Mes dates ont changé.", "test"};
  }

  /** Records the cancellation and moves the request in one transaction. */
  private static void cancel(Connection c, Object[] cancellation) throws SQLException {
    exec(c, CANCELLATION, cancellation);
    exec(
        c,
        "UPDATE people.leave_request SET state = 'CANCELLED' WHERE tenant_id = ? AND id = ?",
        cancellation[1],
        cancellation[2]);
  }

  private static final String AMENDMENT =
      "INSERT INTO people.leave_request_amendment (id, tenant_id, original_request_id,"
          + " replacement_request_id, reason_locale, reason_text, amended_at, amended_by)"
          + " VALUES (?, ?, ?, ?, ?, ?, now(), ?)";

  /** A valid amendment row; each test breaks one column. */
  private static Object[] amendmentRow(UUID tenant, Object original, Object replacement) {
    return new Object[] {
      UUID.randomUUID(), tenant, original, replacement, "fr", "Mes dates ont changé.", "test"
    };
  }

  /**
   * Amends in the application's order: the original moves to AMENDED, then the replacement and the
   * evidence are inserted, in one transaction.
   */
  private static void amend(Connection c, Object[] replacement, Object[] amendment)
      throws SQLException {
    exec(
        c,
        "UPDATE people.leave_request SET state = 'AMENDED' WHERE tenant_id = ? AND id = ?",
        amendment[1],
        amendment[2]);
    exec(c, REQUEST, replacement);
    exec(c, AMENDMENT, amendment);
  }

  private static int history(Db db) {
    return java.util.Objects.requireNonNull(
        db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
  }

  private static int count(Db db, String sql, Object... args) {
    return java.util.Objects.requireNonNull(db.jdbc().queryForObject(sql, Integer.class, args));
  }

  private static String one(Db db, String sql, Object... args) {
    return db.jdbc().queryForObject(sql, String.class, args);
  }

  private static final String WITHDRAWAL =
      "INSERT INTO people.leave_request_withdrawal (id, tenant_id, request_id, reason_locale,"
          + " reason_text, withdrawn_at, withdrawn_by) VALUES (?, ?, ?, ?, ?, now(), ?)";

  /** A valid withdrawal row; each test breaks one column. */
  private static Object[] withdrawalRow(UUID tenant, Object request) {
    return new Object[] {
      UUID.randomUUID(), tenant, request, "fr", "Mes projets ont changé.", "test"
    };
  }

  /** Withdraws in the application's order: the evidence, then the transition. */
  private static void withdraw(Connection c, Object[] withdrawal) throws SQLException {
    exec(c, WITHDRAWAL, withdrawal);
    exec(
        c,
        "UPDATE people.leave_request SET state = 'WITHDRAWN' WHERE tenant_id = ? AND id = ?",
        withdrawal[1],
        withdrawal[2]);
  }

  /** An approved request of the employee on the day; returns its row. */
  private static Object[] approved(Connection c, Hired h, UUID version, LocalDate day)
      throws SQLException {
    Object[] request = row(h, version, day, day);
    exec(c, REQUEST, request);
    decide(c, decisionRow(h.tenant(), request[0], "APPROVED", "TENANT_ADMIN", null));
    return request;
  }

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV22ExactlyAndRefusesOnceAWithdrawalExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "22");
          String v22 = LeaveAmendmentMigrationIntegrationTest.signature(db.jdbc());
          UUID tenant = organization(db.jdbc());
          Object[][] rows = new Object[5][];
          try {
            committed(
                db,
                c -> {
                  Hired a = hire(c, tenant, "E-1");
                  UUID version = policyVersion(c, tenant, "AD", "TENANT_ADMIN");
                  rows[0] = approved(c, a, version, START);
                  rows[1] = row(a, version, START.plusDays(5), START.plusDays(5));
                  exec(c, REQUEST, rows[1]);
                  decide(c, decisionRow(tenant, rows[1][0], "REJECTED", "TENANT_ADMIN", null));
                  rows[2] = row(a, version, START.plusDays(10), START.plusDays(10));
                  exec(c, REQUEST, rows[2]);
                  cancel(c, cancellationRow(tenant, rows[2][0]));
                  rows[3] = row(a, version, START.plusDays(15), START.plusDays(15));
                  exec(c, REQUEST, rows[3]);
                  Object[] replacement = row(a, version, START.plusDays(16), START.plusDays(16));
                  amend(c, replacement, amendmentRow(tenant, rows[3][0], replacement[0]));
                  rows[4] = approved(c, a, version, START.plusDays(20));
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }

          migrate(db.url(), "23");
          String v23 = LeaveAmendmentMigrationIntegrationTest.signature(db.jdbc());
          assertThat(v23).isNotEqualTo(v22).contains("leave_request_withdrawal");
          // With every V22 kind of evidence and no withdrawal, the rollback restores V22 exactly.
          int recorded = history(db);
          db.jdbc().execute(resource("db/rollback/V23__rollback.sql"));
          assertThat(LeaveAmendmentMigrationIntegrationTest.signature(db.jdbc())).isEqualTo(v22);
          assertThat(history(db)).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.leave_request_decision")).isEqualTo(3);
          assertThat(count(db, "SELECT count(*) FROM people.leave_request_cancellation"))
              .isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM people.leave_request_amendment")).isEqualTo(1);

          // Once a withdrawal exists, it refuses and changes nothing.
          db.jdbc().execute(resource("db/migration/V23__approved_leave_withdrawal.sql"));
          assertThat(LeaveAmendmentMigrationIntegrationTest.signature(db.jdbc())).isEqualTo(v23);
          try {
            committed(db, c -> withdraw(c, withdrawalRow(tenant, rows[4][0])));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V23__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V23 rollback refused");
          assertThat(LeaveAmendmentMigrationIntegrationTest.signature(db.jdbc())).isEqualTo(v23);
          assertThat(history(db)).isEqualTo(recorded);
          assertThat(one(db, "SELECT state FROM people.leave_request WHERE id = ?", rows[4][0]))
              .isEqualTo("WITHDRAWN");
        });
  }

  // ------------------------------------------------------------------------------------------
  // Transition, terminal evidence and the withdrawal rules
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyAnApprovedRequestIsWithdrawnOnceWithItsApprovalKept() {
    withDatabase(
        db -> {
          migrate(db.url(), "23");
          UUID tenant = organization(db.jdbc());
          Hired[] people = new Hired[1];
          UUID[] versions = new UUID[1];
          Object[][] rows = new Object[6][];
          try {
            committed(
                db,
                c -> {
                  people[0] = hire(c, tenant, "E-1");
                  versions[0] = policyVersion(c, tenant, "AD", "TENANT_ADMIN");
                  Hired a = people[0];
                  rows[0] = approved(c, a, versions[0], START);
                  rows[1] = row(a, versions[0], START.plusDays(3), START.plusDays(3));
                  exec(c, REQUEST, rows[1]);
                  rows[2] = row(a, versions[0], START.plusDays(5), START.plusDays(5));
                  exec(c, REQUEST, rows[2]);
                  decide(c, decisionRow(tenant, rows[2][0], "REJECTED", "TENANT_ADMIN", null));
                  rows[3] = row(a, versions[0], START.plusDays(7), START.plusDays(7));
                  exec(c, REQUEST, rows[3]);
                  cancel(c, cancellationRow(tenant, rows[3][0]));
                  rows[4] = approved(c, a, versions[0], START.plusDays(9));
                  rows[5] = approved(c, a, versions[0], START.plusDays(11));
                });
            String approval =
                one(
                    db,
                    "SELECT to_jsonb(d)::text FROM people.leave_request_decision d"
                        + " WHERE request_id = ?",
                    rows[0][0]);

            // PENDING, REJECTED and CANCELLED never move to WITHDRAWN.
            for (int i = 1; i <= 3; i++) {
              Object request = rows[i][0];
              refused(
                  db, "leave_request_transition", c -> withdraw(c, withdrawalRow(tenant, request)));
            }
            // The transition alone, or the evidence alone, never commits.
            refused(
                db,
                "leave_request_decided",
                c ->
                    exec(
                        c,
                        "UPDATE people.leave_request SET state = 'WITHDRAWN' WHERE id = ?",
                        rows[0][0]));
            refused(
                db,
                "leave_request_withdrawal_consistent",
                c -> exec(c, WITHDRAWAL, withdrawalRow(tenant, rows[0][0])));
            // Nothing else about the request changes with the transition.
            refused(
                db,
                "leave_request_transition",
                c -> {
                  exec(c, WITHDRAWAL, withdrawalRow(tenant, rows[0][0]));
                  exec(
                      c,
                      "UPDATE people.leave_request SET state = 'WITHDRAWN', end_date = end_date + 1"
                          + " WHERE id = ?",
                      rows[0][0]);
                });
            // A withdrawn request cannot also carry a cancellation.
            refused(
                db,
                "leave_request_withdrawal_consistent",
                c -> {
                  withdraw(c, withdrawalRow(tenant, rows[5][0]));
                  exec(c, CANCELLATION, cancellationRow(tenant, rows[5][0]));
                });

            // The approved request is withdrawn once; its decision is kept exactly.
            committed(db, c -> withdraw(c, withdrawalRow(tenant, rows[0][0])));
            assertThat(
                    one(
                        db,
                        "SELECT to_jsonb(d)::text FROM people.leave_request_decision d"
                            + " WHERE request_id = ?",
                        rows[0][0]))
                .isEqualTo(approval);
            // WITHDRAWN never moves again, and the decision cannot be removed.
            for (String state : List.of("APPROVED", "PENDING", "REJECTED", "CANCELLED")) {
              refused(
                  db,
                  "leave_request_transition",
                  c ->
                      exec(
                          c,
                          "UPDATE people.leave_request SET state = ? WHERE id = ?",
                          state,
                          rows[0][0]));
            }
            refused(
                db,
                "leave_request_decision_immutable",
                c ->
                    exec(
                        c,
                        "DELETE FROM people.leave_request_decision WHERE request_id = ?",
                        rows[0][0]));
            // One withdrawal per request.
            refused(
                db,
                "leave_request_withdrawal_request_unique",
                c -> exec(c, WITHDRAWAL, withdrawalRow(tenant, rows[0][0])));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Test
  void withdrawalsAreAppendOnlyAndUseTheSharedReasonGrammar() {
    withDatabase(
        db -> {
          migrate(db.url(), "23");
          UUID tenant = organization(db.jdbc());
          UUID other = organization(db.jdbc());
          Object[][] rows = new Object[2][];
          try {
            committed(
                db,
                c -> {
                  Hired a = hire(c, tenant, "E-1");
                  UUID version = policyVersion(c, tenant, "AD", "TENANT_ADMIN");
                  rows[0] = approved(c, a, version, START);
                  rows[1] = approved(c, a, version, START.plusDays(3));
                });
            Object[] good = withdrawalRow(tenant, rows[1][0]);
            for (Object[] bad :
                List.of(
                    new Object[] {"leave_request_withdrawal_locale_valid", change(good, 3, "de")},
                    new Object[] {"leave_request_withdrawal_reason_valid", change(good, 4, "x")},
                    new Object[] {
                      "leave_request_withdrawal_reason_valid", change(good, 4, "secret\nreason")
                    },
                    new Object[] {
                      "leave_request_withdrawal_reason_valid", change(good, 4, " untrimmed ")
                    },
                    new Object[] {
                      "leave_request_withdrawal_withdrawn_by_length",
                      change(good, 5, "s".repeat(256))
                    },
                    new Object[] {"leave_request_withdrawal_request", change(good, 1, other)})) {
              refused(db, (String) bad[0], c -> withdraw(c, (Object[]) bad[1]));
            }
            committed(db, c -> withdraw(c, withdrawalRow(tenant, rows[0][0])));
            refused(
                db,
                "leave_request_withdrawal_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE people.leave_request_withdrawal SET reason_text = 'Autre motif.'"));
            refused(
                db,
                "leave_request_withdrawal_immutable",
                c -> exec(c, "DELETE FROM people.leave_request_withdrawal"));
            refused(
                db,
                "leave_request_withdrawal_no_truncate",
                c -> exec(c, "TRUNCATE people.leave_request_withdrawal CASCADE"));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Test
  void theDatesAreReleasedWithTheWithdrawalAndTheLookupReadsItsIndex() {
    withDatabase(
        db -> {
          migrate(db.url(), "23");
          UUID tenant = organization(db.jdbc());
          Hired[] people = new Hired[1];
          UUID[] versions = new UUID[1];
          Object[][] rows = new Object[1][];
          try {
            committed(
                db,
                c -> {
                  people[0] = hire(c, tenant, "E-1");
                  versions[0] = policyVersion(c, tenant, "AD", "TENANT_ADMIN");
                  rows[0] = approved(c, people[0], versions[0], START.plusDays(4));
                });
            Object[] again = row(people[0], versions[0], START.plusDays(4), START.plusDays(4));
            refused(db, "leave_request_no_overlap", c -> exec(c, REQUEST, again));
            // In one transaction with the withdrawal, and committed afterwards.
            committed(
                db,
                c -> {
                  withdraw(c, withdrawalRow(tenant, rows[0][0]));
                  exec(c, REQUEST, again);
                });
            assertThat(count(db, "SELECT count(*) FROM people.leave_request")).isEqualTo(2);
            // History and replay lookups read the one unique index, whatever the statistics.
            assertThat(
                    plan(
                        db,
                        "SELECT w.id FROM people.leave_request_withdrawal w"
                            + " WHERE w.tenant_id = ? AND w.request_id = ?",
                        tenant,
                        UUID.randomUUID()))
                .containsPattern(
                    "Index (Only )?Scan using leave_request_withdrawal_request_unique");
            assertThat(
                    plan(
                        db,
                        "SELECT r.id, w.id FROM people.leave_request r"
                            + " LEFT JOIN people.leave_request_withdrawal w"
                            + " ON w.tenant_id = r.tenant_id AND w.request_id = r.id"
                            + " WHERE r.tenant_id = ? AND r.id = ?",
                        tenant,
                        UUID.randomUUID()))
                .contains("leave_request_withdrawal_request_unique");
            assertThat(
                    count(
                        db,
                        "SELECT count(*) FROM pg_indexes WHERE schemaname = 'people'"
                            + " AND tablename = 'leave_request_withdrawal'"))
                .isEqualTo(2);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Query plans (narrow index assertions; no large fixture, no shared-table statistics)
  // ------------------------------------------------------------------------------------------

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
