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
 * MVP-041C V21 directly against PostgreSQL, bypassing the application (the database is the final
 * authority): the refusing rollback that restores V20 exactly, the PENDING-to-CANCELLED transition,
 * exactly one matching kind of terminal evidence per request (deferred), append-only cancellations
 * with the shared reason grammar, the release of a cancelled request's dates, and the narrow index
 * of the cancellation lookup.
 */
@IntegrationTest
class LeaveCancellationMigrationIntegrationTest {

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
    String database = "leave21_" + UUID.randomUUID().toString().replace("-", "");
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
  void theRollbackRestoresV20ExactlyAndRefusesOnceACancellationExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "20");
          String v20 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "21");
          String v21 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v21).isNotEqualTo(v20).contains("leave_request_cancellation");
          int recorded = history(db);
          db.jdbc().execute(resource("db/rollback/V21__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v20);
          assertThat(history(db)).isEqualTo(recorded);

          // With pending and decided requests, the rollback still restores V20.
          db.jdbc().execute(resource("db/migration/V21__leave_request_cancellation.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v21);
          UUID tenant = organization(db.jdbc());
          Object[][] rows = new Object[2][];
          try {
            committed(
                db,
                c -> {
                  Hired h = hire(c, tenant, "E-1");
                  UUID version = policyVersion(c, tenant, "AN", "TENANT_ADMIN");
                  rows[0] = row(h, version, START, START);
                  rows[1] = row(h, version, START.plusDays(5), START.plusDays(5));
                  exec(c, REQUEST, rows[0]);
                  exec(c, REQUEST, rows[1]);
                  decide(c, decisionRow(tenant, rows[1][0], "REJECTED", "TENANT_ADMIN", null));
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          db.jdbc().execute(resource("db/rollback/V21__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v20);

          // Once a request is cancelled, it refuses and changes nothing.
          db.jdbc().execute(resource("db/migration/V21__leave_request_cancellation.sql"));
          try {
            committed(db, c -> cancel(c, cancellationRow(tenant, rows[0][0])));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V21__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V21 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v21);
          assertThat(history(db)).isEqualTo(recorded);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Transition, exactly-one terminal evidence and cancellation rules
  // ------------------------------------------------------------------------------------------

  @Test
  void everyCancellationRuleIsEnforcedByTheDatabase() {
    withDatabase(
        db -> {
          migrate(db.url(), "21");
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
            LocalDate d = LocalDate.of(2026, 11, 2);
            Object[] managed = row(a, versions[0], d, d.plusDays(1));
            Object[] central = row(a, versions[1], d.plusDays(10), d.plusDays(10));
            Object[] decided = row(a, versions[1], d.plusDays(20), d.plusDays(20));
            Object[] foreign = row(people[2], versions[2], d, d);
            committed(
                db,
                c -> {
                  exec(c, REQUEST, managed);
                  exec(c, REQUEST, central);
                  exec(c, REQUEST, decided);
                  exec(c, REQUEST, foreign);
                  decide(c, decisionRow(tenant, decided[0], "APPROVED", "TENANT_ADMIN", null));
                });
            Object mid = managed[0];
            Object cid = central[0];

            // A cancellation needs its transition, and a cancelled request its cancellation.
            refused(
                db,
                "leave_request_decided",
                c ->
                    exec(
                        c,
                        "UPDATE people.leave_request SET state = 'CANCELLED' WHERE id = ?",
                        mid));
            refused(
                db,
                "leave_request_cancellation_consistent",
                c -> exec(c, CANCELLATION, cancellationRow(tenant, mid)));
            // Exactly one kind of evidence. Each per-row check would also refuse these; running
            // leave_request_decided first (as the application runs it IMMEDIATE) proves that the
            // request-side rule refuses them on its own.
            refused(
                db,
                "leave_request_decided",
                c -> {
                  exec(c, DECISION, decisionRow(tenant, mid, "APPROVED", "MANAGER", manager));
                  cancel(c, cancellationRow(tenant, mid));
                  exec(c, "SET CONSTRAINTS people.leave_request_decided IMMEDIATE");
                });
            refused(
                db,
                "leave_request_decided",
                c -> {
                  exec(c, CANCELLATION, cancellationRow(tenant, mid));
                  decide(c, decisionRow(tenant, mid, "APPROVED", "MANAGER", manager));
                  exec(c, "SET CONSTRAINTS people.leave_request_decided IMMEDIATE");
                });
            refused(
                db,
                "leave_request_cancellation_consistent",
                c -> exec(c, CANCELLATION, cancellationRow(tenant, decided[0])));
            refused(
                db,
                "leave_request_transition",
                c -> cancel(c, cancellationRow(tenant, decided[0])));
            refused(
                db,
                "leave_request_cancellation_request",
                c -> exec(c, CANCELLATION, cancellationRow(tenant, foreign[0])));
            // A request inserted CANCELLED commits only with its cancellation.
            Object[] insertedCancelled =
                change(row(a, versions[1], d.plusDays(300), d.plusDays(300)), 8, "CANCELLED");
            refused(db, "leave_request_decided", c -> exec(c, REQUEST, insertedCancelled));
            committed(
                db,
                c -> {
                  exec(c, REQUEST, insertedCancelled);
                  exec(c, CANCELLATION, cancellationRow(tenant, insertedCancelled[0]));
                });

            // Shapes and the shared reason grammar (version 1) of the cancellation row.
            record Broken(String constraint, int index, Object value) {}
            for (Broken broken :
                List.of(
                    new Broken("leave_request_cancellation_locale_valid", 3, "de"),
                    new Broken("leave_request_cancellation_reason_valid", 4, "x"),
                    new Broken("leave_request_cancellation_reason_valid", 4, " Mes dates."),
                    new Broken("leave_request_cancellation_reason_valid", 4, "a".repeat(501)),
                    new Broken("leave_request_cancellation_reason_valid", 4, "Mes\u200Bdates"),
                    new Broken("leave_request_cancellation_reason_valid", 4, "Dates\u0007"),
                    new Broken("leave_request_cancellation_reason_valid", 4, "Annule\u0301e"),
                    new Broken("leave_request_cancellation_cancelled_by_length", 5, ""),
                    new Broken(
                        "leave_request_cancellation_cancelled_by_length", 5, "s".repeat(256)),
                    new Broken(
                        "leave_request_cancellation_cancelled_by_length", 5, "s".repeat(300)))) {
              Object[] bad = change(cancellationRow(tenant, mid), broken.index(), broken.value());
              refused(db, broken.constraint(), c -> cancel(c, bad));
            }
            // 500 code points with a supplementary character, NFC, trimmed: accepted.
            Object[] longest = cancellationRow(tenant, mid);
            longest[4] = "é".repeat(498) + "\uD83D\uDE00";
            committed(db, c -> cancel(c, longest));

            // Exactly one cancellation; cancelled requests never change; cancellations append-only.
            refused(
                db,
                "leave_request_cancellation_request_unique",
                c -> exec(c, CANCELLATION, cancellationRow(tenant, mid)));
            for (String state : List.of("PENDING", "APPROVED", "REJECTED", "CANCELLED")) {
              refused(
                  db,
                  "leave_request_transition",
                  c ->
                      exec(
                          c, "UPDATE people.leave_request SET state = ? WHERE id = ?", state, mid));
            }
            refused(
                db,
                "leave_request_decision_consistent",
                c -> exec(c, DECISION, decisionRow(tenant, mid, "REJECTED", "MANAGER", manager)));
            for (String sql :
                List.of(
                    "UPDATE people.leave_request_cancellation SET reason_text = 'Autre raison.'",
                    "UPDATE people.leave_request_cancellation SET cancelled_by = 'other'",
                    "DELETE FROM people.leave_request_cancellation")) {
              refused(db, "leave_request_cancellation_immutable", c -> exec(c, sql));
            }
            refused(
                db,
                "leave_request_cancellation_no_truncate",
                c -> {
                  try (Statement st = c.createStatement()) {
                    st.execute("TRUNCATE people.leave_request_cancellation");
                  }
                });

            // A pending request blocks its dates; cancelling it releases them in the same commit.
            Object[] overlapping = row(a, versions[1], d.plusDays(10), d.plusDays(11));
            refused(db, "leave_request_no_overlap", c -> exec(c, REQUEST, overlapping));
            committed(
                db,
                c -> {
                  cancel(c, cancellationRow(tenant, cid));
                  exec(c, REQUEST, overlapping);
                });
            assertThat(
                    count(
                        db,
                        "SELECT count(*) FROM people.leave_request WHERE employee_id = ?"
                            + " AND state IN ('PENDING', 'APPROVED')"
                            + " AND daterange(start_date, end_date, '[]') && daterange(?, ?, '[]')",
                        a.employee(),
                        d.plusDays(10),
                        d.plusDays(11)))
                .isEqualTo(1);
            // Approved dates keep blocking (V20, unchanged).
            refused(
                db,
                "leave_request_no_overlap",
                c -> exec(c, REQUEST, row(a, versions[1], d.plusDays(20), d.plusDays(20))));
            assertThat(
                    count(
                        db,
                        "SELECT count(*) FROM people.leave_request_cancellation WHERE tenant_id ="
                            + " ?",
                        tenant))
                .isEqualTo(3);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Query plan (narrow index assertion; no large fixture, no shared-table statistics)
  // ------------------------------------------------------------------------------------------

  @Test
  void theCancellationLookupReadsItsUniqueIndex() {
    withDatabase(
        db -> {
          migrate(db.url(), "21");
          UUID tenant = organization(db.jdbc());
          try (Connection c = connect(db)) {
            exec(c, "SET LOCAL enable_seqscan = off");
            StringBuilder plan = new StringBuilder();
            try (PreparedStatement explain =
                c.prepareStatement(
                    "EXPLAIN SELECT x.id, x.reason_locale, x.reason_text, x.cancelled_at"
                        + " FROM people.leave_request_cancellation x"
                        + " WHERE x.tenant_id = ? AND x.request_id = ?")) {
              explain.setObject(1, tenant);
              explain.setObject(2, UUID.randomUUID());
              try (var rows = explain.executeQuery()) {
                while (rows.next()) {
                  plan.append(rows.getString(1)).append('\n');
                }
              }
            }
            c.rollback();
            assertThat(plan.toString())
                .containsPattern(
                    "Index (Only )?Scan using leave_request_cancellation_request_unique");
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
