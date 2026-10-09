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
import java.sql.Statement;
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
 * MVP-041B V20 directly against PostgreSQL, bypassing the application (the database is the final
 * authority): the refusing rollback that restores V19 exactly (triggers included), the one
 * PENDING-to-terminal transition, the deferred request/decision consistency, append-only decisions
 * and their reason, locale, route and manager rules, overlap of pending and approved requests only,
 * and narrow index assertions for both approval queues.
 */
@IntegrationTest
class LeaveDecisionMigrationIntegrationTest {

  /** The people schema: tables, columns, indexes, constraints, triggers and functions. */
  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'people'), '')
          || '|' || coalesce((SELECT string_agg(table_name || '.' || column_name || ':'
                                  || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, ''),
                                  ',' ORDER BY table_name, column_name)
                              FROM information_schema.columns
                              WHERE table_schema = 'people'), '')
          || '|' || coalesce((SELECT string_agg(indexname || ':' || indexdef, ','
                                  ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'people'), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY conname)
                              FROM pg_constraint
                              WHERE connamespace = 'people'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(c.relname || '.' || t.tgname, ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'people'::regnamespace
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(pg_get_triggerdef(t.oid), ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'people'::regnamespace
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ','
                                  ORDER BY proname) FROM pg_proc
                              WHERE pronamespace = 'people'::regnamespace), '')
      """;

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
    String database = "leave20_" + UUID.randomUUID().toString().replace("-", "");
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
          + " decided_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), ?)";

  /** A valid decision row; each test breaks one column. */
  private static Object[] decisionRow(
      UUID tenant, Object request, String outcome, String route, UUID manager) {
    return new Object[] {
      UUID.randomUUID(), tenant, request, outcome, route, manager, "fr", "Accordé.", "test"
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

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV19ExactlyAndRefusesOnceADecisionExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "19");
          String v19 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "20");
          String v20 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v20).isNotEqualTo(v19).contains("leave_request_decision");
          int recorded = history(db);
          db.jdbc().execute(resource("db/rollback/V20__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v19);
          assertThat(history(db)).isEqualTo(recorded);

          // With pending requests only, the rollback still restores V19.
          db.jdbc().execute(resource("db/migration/V20__leave_request_decision.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v20);
          UUID tenant = organization(db.jdbc());
          Object[][] row = new Object[1][];
          try {
            committed(
                db,
                c -> {
                  Hired h = hire(c, tenant, "E-1");
                  row[0] = row(h, policyVersion(c, tenant, "AN", "TENANT_ADMIN"), START, START);
                  exec(c, REQUEST, row[0]);
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          db.jdbc().execute(resource("db/rollback/V20__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v19);

          // Once a request is decided, it refuses and changes nothing.
          db.jdbc().execute(resource("db/migration/V20__leave_request_decision.sql"));
          try {
            committed(
                db,
                c -> decide(c, decisionRow(tenant, row[0][0], "REJECTED", "TENANT_ADMIN", null)));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V20__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V20 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v20);
        });
  }

  private static int history(Db db) {
    return java.util.Objects.requireNonNull(
        db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
  }

  // ------------------------------------------------------------------------------------------
  // Transition, consistency and decision rules
  // ------------------------------------------------------------------------------------------

  @Test
  void everyDecisionRuleIsEnforcedByTheDatabase() {
    withDatabase(
        db -> {
          migrate(db.url(), "20");
          UUID tenant = organization(db.jdbc());
          UUID other = organization(db.jdbc());
          try {
            Hired[] people = new Hired[3];
            UUID[] versions = new UUID[3];
            committed(
                db,
                c -> {
                  people[0] = hire(c, tenant, "E-1");
                  people[1] = hire(c, tenant, "E-2");
                  people[2] = hire(c, other, "E-1");
                  versions[0] = policyVersion(c, tenant, "MG", "MANAGER");
                  versions[1] = policyVersion(c, tenant, "AD", "TENANT_ADMIN");
                  versions[2] = policyVersion(c, other, "AD", "TENANT_ADMIN");
                });
            Hired a = people[0];
            UUID manager = people[1].employee();
            UUID foreignEmployee = people[2].employee();
            LocalDate d = LocalDate.of(2026, 11, 2);
            Object[] managed = row(a, versions[0], d, d);
            Object[] central = row(a, versions[1], d.plusDays(10), d.plusDays(10));
            Object[] foreign = row(people[2], versions[2], d, d);
            committed(
                db,
                c -> {
                  exec(c, REQUEST, managed);
                  exec(c, REQUEST, central);
                  exec(c, REQUEST, foreign);
                });
            Object mid = managed[0];
            Object cid = central[0];

            // A transition needs its decision, and a decision its transition (deferred).
            refused(
                db,
                "leave_request_decided",
                c ->
                    exec(
                        c, "UPDATE people.leave_request SET state = 'APPROVED' WHERE id = ?", mid));
            refused(
                db,
                "leave_request_decision_consistent",
                c -> exec(c, DECISION, decisionRow(tenant, mid, "APPROVED", "MANAGER", manager)));
            // The decision matches the request's state and its policy version's route.
            refused(
                db,
                "leave_request_decision_consistent",
                c -> {
                  exec(c, DECISION, decisionRow(tenant, mid, "REJECTED", "MANAGER", manager));
                  exec(c, "UPDATE people.leave_request SET state = 'APPROVED' WHERE id = ?", mid);
                });
            refused(
                db,
                "leave_request_decision_consistent",
                c -> decide(c, decisionRow(tenant, mid, "APPROVED", "TENANT_ADMIN", null)));
            refused(
                db,
                "leave_request_decision_consistent",
                c -> decide(c, decisionRow(tenant, cid, "APPROVED", "MANAGER", manager)));

            // Shapes, enums, grammar and keys of the decision row.
            record Broken(String constraint, int index, Object value) {}
            for (Broken broken :
                List.of(
                    new Broken("leave_request_decision_outcome_valid", 3, "PENDING"),
                    new Broken("leave_request_decision_route_shape", 5, null),
                    new Broken("leave_request_decision_locale_valid", 6, "de"),
                    new Broken("leave_request_decision_reason_valid", 7, "x"),
                    new Broken("leave_request_decision_reason_valid", 7, " Accordé."),
                    new Broken("leave_request_decision_reason_valid", 7, "a".repeat(501)),
                    new Broken("leave_request_decision_reason_valid", 7, "Accord\u0007é"),
                    new Broken("leave_request_decision_reason_valid", 7, "Accorde\u0301"),
                    new Broken("leave_request_decision_decided_by_length", 8, ""),
                    new Broken("leave_request_decision_manager", 5, foreignEmployee),
                    new Broken("leave_request_decision_request", 2, foreign[0]))) {
              Object[] bad =
                  change(
                      decisionRow(tenant, mid, "APPROVED", "MANAGER", manager),
                      broken.index(),
                      broken.value());
              refused(db, broken.constraint(), c -> exec(c, DECISION, bad));
            }
            refused(
                db,
                "leave_request_decision_route_shape",
                c ->
                    exec(
                        c,
                        DECISION,
                        decisionRow(tenant, cid, "APPROVED", "TENANT_ADMIN", manager)));
            // 500 code points, NFC, trimmed: accepted.
            Object[] longest = decisionRow(tenant, mid, "APPROVED", "MANAGER", manager);
            longest[7] = "é".repeat(500);
            committed(db, c -> decide(c, longest));

            // Exactly one decision; terminal requests never change; decisions are append-only.
            refused(
                db,
                "leave_request_decision_request_unique",
                c -> exec(c, DECISION, decisionRow(tenant, mid, "APPROVED", "MANAGER", manager)));
            for (String sql :
                List.of(
                    "UPDATE people.leave_request SET state = 'REJECTED' WHERE id = '" + mid + "'",
                    "UPDATE people.leave_request SET state = 'PENDING' WHERE id = '" + mid + "'",
                    "UPDATE people.leave_request SET requested_amount = 1 WHERE id = '" + cid + "'",
                    "UPDATE people.leave_request SET state = 'APPROVED', end_date = end_date + 1"
                        + " WHERE id = '"
                        + cid
                        + "'")) {
              refused(db, "leave_request_transition", c -> exec(c, sql));
            }
            refused(
                db,
                "leave_request_immutable",
                c -> exec(c, "DELETE FROM people.leave_request WHERE id = ?", cid));
            for (String sql :
                List.of(
                    "UPDATE people.leave_request_decision SET reason_text = 'Autre raison.'",
                    "DELETE FROM people.leave_request_decision")) {
              refused(db, "leave_request_decision_immutable", c -> exec(c, sql));
            }
            for (String table : List.of("leave_request", "leave_request_decision")) {
              refused(
                  db,
                  table + "_no_truncate",
                  c -> {
                    try (Statement st = c.createStatement()) {
                      st.execute("TRUNCATE people." + table + " CASCADE");
                    }
                  });
            }

            // Approved dates keep blocking; rejected dates are released.
            refused(
                db,
                "leave_request_no_overlap",
                c -> exec(c, REQUEST, row(a, versions[0], d, d.plusDays(1))));
            committed(
                db, c -> decide(c, decisionRow(tenant, cid, "REJECTED", "TENANT_ADMIN", null)));
            committed(
                db, c -> exec(c, REQUEST, row(a, versions[1], d.plusDays(10), d.plusDays(10))));
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

  @Test
  void bothQueuesReadTheirIndexes() {
    withDatabase(
        db -> {
          migrate(db.url(), "20");
          UUID tenant = organization(db.jdbc());
          try {
            String admin =
                plan(
                    db,
                    "SELECT r.id FROM people.leave_request r WHERE r.tenant_id = ? AND r.state ="
                        + " 'PENDING' AND (r.submitted_at, r.id) < (?, ?)"
                        + " ORDER BY r.submitted_at DESC, r.id DESC LIMIT 26",
                    tenant,
                    Timestamp.from(Instant.now()),
                    UUID.randomUUID());
            assertThat(admin)
                .containsPattern("Index (Only )?Scan using leave_request_pending_queue")
                .doesNotContain("Sort");
            String manager =
                plan(
                    db,
                    "WITH lines AS MATERIALIZED (SELECT a.employment_id, a.effective_from,"
                        + " a.effective_to FROM people.employment_assignment a"
                        + " WHERE a.tenant_id = ? AND a.manager_employee_id = ?"
                        + " AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL)"
                        + " SELECT q.id FROM lines l CROSS JOIN LATERAL ("
                        + "SELECT r.id, r.submitted_at FROM people.leave_request r"
                        + " WHERE r.tenant_id = ? AND r.employment_id = l.employment_id"
                        + " AND r.state = 'PENDING' AND l.effective_from <= r.start_date"
                        + " AND (l.effective_to IS NULL OR l.effective_to >= r.start_date)"
                        + " ORDER BY r.submitted_at DESC, r.id DESC LIMIT 26) q"
                        + " ORDER BY q.submitted_at DESC, q.id DESC LIMIT 26",
                    tenant,
                    UUID.randomUUID(),
                    tenant);
            assertThat(manager)
                .contains("employment_assignment_reports")
                .contains("leave_request_employment_pending");
            String decision =
                plan(
                    db,
                    "SELECT d.id FROM people.leave_request_decision d"
                        + " WHERE d.tenant_id = ? AND d.request_id = ?",
                    tenant,
                    UUID.randomUUID());
            assertThat(decision).contains("leave_request_decision_request_unique");
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
