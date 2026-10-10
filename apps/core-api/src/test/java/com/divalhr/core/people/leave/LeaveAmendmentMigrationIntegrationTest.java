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
 * MVP-041D/E V22 directly against PostgreSQL, bypassing the application (the database is the final
 * authority): the deterministic decision-authority backfill, the refusing rollback that restores
 * V21 exactly, the PENDING-to-AMENDED transition, exactly one matching kind of terminal evidence
 * per request (deferred), append-only amendments with the shared reason grammar, amendment chains,
 * the overlap release that excludes only the original, the exact route/authority/manager shapes,
 * and the narrow indexes of the amendment, exception-queue and override lookups.
 */
@IntegrationTest
class LeaveAmendmentMigrationIntegrationTest {

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

  /**
   * The people schema signature of a database (shared with the V23 test, which keeps the query in
   * one class file).
   *
   * @param db the database
   * @return its signature
   */
  static String signature(JdbcTemplate db) {
    return db.queryForObject(SIGNATURE, String.class);
  }

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
    String database = "leave22_" + UUID.randomUUID().toString().replace("-", "");
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

  /** V21's decision row (no authority), to prove the backfill. */
  private static final String DECISION_V21 =
      "INSERT INTO people.leave_request_decision (id, tenant_id, request_id, outcome,"
          + " approval_route, manager_employee_id, reason_locale, reason_text, decided_at,"
          + " decided_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), ?)";

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

  /** A routing-exception override: MANAGER route, no manager. */
  private static Object[] overrideRow(UUID tenant, Object request, String outcome) {
    return new Object[] {
      UUID.randomUUID(),
      tenant,
      request,
      outcome,
      "MANAGER",
      null,
      "fr",
      "Aucun responsable admissible.",
      "test",
      "TENANT_ADMIN_OVERRIDE"
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

  // ------------------------------------------------------------------------------------------
  // Backfill and rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theBackfillIsTheRouteAndTheRollbackRestoresV21ExactlyUnlessOverridesExist() {
    withDatabase(
        db -> {
          migrate(db.url(), "21");
          String v21 = db.jdbc().queryForObject(SIGNATURE, String.class);
          UUID tenant = organization(db.jdbc());
          Object[][] rows = new Object[5][];
          UUID[] versions = new UUID[2];
          Hired[] people = new Hired[2];
          try {
            committed(
                db,
                c -> {
                  people[0] = hire(c, tenant, "E-1");
                  people[1] = hire(c, tenant, "E-2");
                  versions[0] = policyVersion(c, tenant, "MG", "MANAGER");
                  versions[1] = policyVersion(c, tenant, "AD", "TENANT_ADMIN");
                  Hired a = people[0];
                  rows[0] = row(a, versions[0], START, START);
                  rows[1] = row(a, versions[1], START.plusDays(5), START.plusDays(5));
                  rows[2] = row(a, versions[1], START.plusDays(10), START.plusDays(10));
                  rows[3] = row(a, versions[1], START.plusDays(15), START.plusDays(15));
                  rows[4] = row(a, versions[0], START.plusDays(20), START.plusDays(20));
                  for (Object[] r : rows) {
                    exec(c, REQUEST, r);
                  }
                  // V21 decisions: no authority column yet.
                  exec(
                      c,
                      DECISION_V21,
                      UUID.randomUUID(),
                      tenant,
                      rows[0][0],
                      "APPROVED",
                      "MANAGER",
                      people[1].employee(),
                      "fr",
                      "Accordé.",
                      "test");
                  exec(
                      c,
                      "UPDATE people.leave_request SET state = 'APPROVED' WHERE id = ?",
                      rows[0][0]);
                  exec(
                      c,
                      DECISION_V21,
                      UUID.randomUUID(),
                      tenant,
                      rows[1][0],
                      "REJECTED",
                      "TENANT_ADMIN",
                      null,
                      "en",
                      "Not this week.",
                      "test");
                  exec(
                      c,
                      "UPDATE people.leave_request SET state = 'REJECTED' WHERE id = ?",
                      rows[1][0]);
                  cancel(c, cancellationRow(tenant, rows[2][0]));
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }

          // The backfill is each decision's own route; the append-only trigger is back on.
          migrate(db.url(), "22");
          String v22 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v22).isNotEqualTo(v21).contains("leave_request_amendment");
          assertThat(
                  one(
                      db,
                      "SELECT decision_authority FROM people.leave_request_decision"
                          + " WHERE request_id = ?",
                      rows[0][0]))
              .isEqualTo("MANAGER");
          assertThat(
                  one(
                      db,
                      "SELECT decision_authority FROM people.leave_request_decision"
                          + " WHERE request_id = ?",
                      rows[1][0]))
              .isEqualTo("TENANT_ADMIN");
          try {
            refused(
                db,
                "leave_request_decision_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE people.leave_request_decision SET decision_authority ="
                            + " 'TENANT_ADMIN_OVERRIDE' WHERE request_id = ?",
                        rows[0][0]));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }

          // With pending, decided and cancelled requests, the rollback restores V21 and keeps them.
          int recorded = history(db);
          db.jdbc().execute(resource("db/rollback/V22__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v21);
          assertThat(history(db)).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.leave_request_decision")).isEqualTo(2);
          assertThat(count(db, "SELECT count(*) FROM people.leave_request_cancellation"))
              .isEqualTo(1);

          // Once a routing-exception override exists, it refuses and changes nothing.
          db.jdbc()
              .execute(
                  resource("db/migration/V22__leave_request_amendment_and_routing_exception.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v22);
          try {
            committed(db, c -> decide(c, overrideRow(tenant, rows[4][0], "APPROVED")));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V22__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V22 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v22);
          assertThat(history(db)).isEqualTo(recorded);
        });
  }

  @Test
  void theRollbackRefusesOnceAnAmendmentExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "22");
          String v22 = db.jdbc().queryForObject(SIGNATURE, String.class);
          UUID tenant = organization(db.jdbc());
          try {
            committed(
                db,
                c -> {
                  Hired a = hire(c, tenant, "E-1");
                  UUID version = policyVersion(c, tenant, "AD", "TENANT_ADMIN");
                  Object[] original = row(a, version, START, START);
                  exec(c, REQUEST, original);
                  Object[] replacement = row(a, version, START, START.plusDays(1));
                  amend(c, replacement, amendmentRow(tenant, original[0], replacement[0]));
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          int recorded = history(db);
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V22__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V22 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v22);
          assertThat(history(db)).isEqualTo(recorded);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Transition, exactly-one terminal evidence, amendment rules and chains
  // ------------------------------------------------------------------------------------------

  @Test
  void everyAmendmentRuleIsEnforcedByTheDatabase() {
    withDatabase(
        db -> {
          migrate(db.url(), "22");
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
            Hired b = people[1];
            LocalDate d = LocalDate.of(2026, 11, 2);
            Object[] first = row(a, versions[1], d, d.plusDays(1));
            Object[] second = row(a, versions[1], d.plusDays(10), d.plusDays(10));
            Object[] decided = row(a, versions[1], d.plusDays(20), d.plusDays(20));
            Object[] foreign = row(people[2], versions[2], d, d);
            Object[] others = row(b, versions[1], d, d);
            committed(
                db,
                c -> {
                  for (Object[] r : List.of(first, second, decided, foreign, others)) {
                    exec(c, REQUEST, r);
                  }
                  decide(c, decisionRow(tenant, decided[0], "APPROVED", "TENANT_ADMIN", null));
                });
            Object fid = first[0];

            // An AMENDED request needs its amendment, and an amendment its AMENDED original.
            refused(
                db,
                "leave_request_decided",
                c ->
                    exec(c, "UPDATE people.leave_request SET state = 'AMENDED' WHERE id = ?", fid));
            Object[] loose = row(a, versions[1], d.plusDays(40), d.plusDays(40));
            refused(
                db,
                "leave_request_amendment_consistent",
                c -> {
                  exec(c, REQUEST, loose);
                  exec(c, AMENDMENT, amendmentRow(tenant, fid, loose[0]));
                });
            // The replacement is PENDING at the deferred check, and the same employee's.
            refused(
                db,
                "leave_request_amendment_consistent",
                c -> {
                  exec(c, "UPDATE people.leave_request SET state = 'AMENDED' WHERE id = ?", fid);
                  exec(c, AMENDMENT, amendmentRow(tenant, fid, decided[0]));
                  exec(c, "SET CONSTRAINTS people.leave_request_amendment_consistent IMMEDIATE");
                });
            refused(
                db,
                "leave_request_amendment_consistent",
                c -> {
                  exec(c, "UPDATE people.leave_request SET state = 'AMENDED' WHERE id = ?", fid);
                  exec(c, AMENDMENT, amendmentRow(tenant, fid, others[0]));
                  exec(c, "SET CONSTRAINTS people.leave_request_amendment_consistent IMMEDIATE");
                });
            Object[] cancelledLater = row(a, versions[1], d.plusDays(50), d.plusDays(50));
            refused(
                db,
                "leave_request_amendment_consistent",
                c -> {
                  amend(c, cancelledLater, amendmentRow(tenant, fid, cancelledLater[0]));
                  cancel(c, cancellationRow(tenant, cancelledLater[0]));
                  exec(c, "SET CONSTRAINTS people.leave_request_amendment_consistent IMMEDIATE");
                });
            refused(
                db,
                "leave_request_amendment_replacement",
                c -> {
                  exec(c, "UPDATE people.leave_request SET state = 'AMENDED' WHERE id = ?", fid);
                  exec(c, AMENDMENT, amendmentRow(tenant, fid, foreign[0]));
                });
            refused(
                db,
                "leave_request_amendment_distinct",
                c -> {
                  exec(c, "UPDATE people.leave_request SET state = 'AMENDED' WHERE id = ?", fid);
                  exec(c, AMENDMENT, amendmentRow(tenant, fid, fid));
                });
            // The replacement takes the original's dates only once the original left PENDING.
            Object[] early = row(a, versions[1], d, d.plusDays(1));
            refused(
                db,
                "leave_request_no_overlap",
                c -> {
                  exec(c, REQUEST, early);
                  exec(c, "UPDATE people.leave_request SET state = 'AMENDED' WHERE id = ?", fid);
                  exec(c, AMENDMENT, amendmentRow(tenant, fid, early[0]));
                });

            // Shapes and the shared reason grammar (version 1) of the amendment row.
            record Broken(String constraint, int index, Object value) {}
            for (Broken broken :
                List.of(
                    new Broken("leave_request_amendment_locale_valid", 4, "de"),
                    new Broken("leave_request_amendment_reason_valid", 5, "x"),
                    new Broken("leave_request_amendment_reason_valid", 5, " Mes dates."),
                    new Broken("leave_request_amendment_reason_valid", 5, "a".repeat(501)),
                    new Broken("leave_request_amendment_reason_valid", 5, "Mes\u200Bdates"),
                    new Broken("leave_request_amendment_reason_valid", 5, "Dates\u0007"),
                    new Broken("leave_request_amendment_reason_valid", 5, "Modifie\u0301e"),
                    new Broken("leave_request_amendment_amended_by_length", 6, ""),
                    new Broken("leave_request_amendment_amended_by_length", 6, "s".repeat(256)))) {
              Object[] replacement = row(a, versions[1], d, d.plusDays(1));
              Object[] bad =
                  change(amendmentRow(tenant, fid, replacement[0]), broken.index(), broken.value());
              refused(db, broken.constraint(), c -> amend(c, replacement, bad));
            }

            // Same dates are allowed: only the locked original is excluded from the overlap.
            Object[] r1 = row(a, versions[1], d, d.plusDays(1));
            Object[] a1 = amendmentRow(tenant, fid, r1[0]);
            a1[5] = "é".repeat(498) + "😀";
            committed(db, c -> amend(c, r1, a1));
            // ... and no other request is: a replacement overlapping another pending one is
            // refused.
            Object[] clash = row(a, versions[1], d.plusDays(10), d.plusDays(10));
            refused(
                db,
                "leave_request_no_overlap",
                c -> amend(c, clash, amendmentRow(tenant, r1[0], clash[0])));

            // One amendment per original and one per replacement; AMENDED never changes.
            Object[] spare = row(a, versions[1], d.plusDays(60), d.plusDays(60));
            refused(
                db,
                "leave_request_amendment_original_unique",
                c -> {
                  exec(c, REQUEST, spare);
                  exec(c, AMENDMENT, amendmentRow(tenant, fid, spare[0]));
                });
            refused(
                db,
                "leave_request_amendment_replacement_unique",
                c -> {
                  exec(
                      c,
                      "UPDATE people.leave_request SET state = 'AMENDED' WHERE id = ?",
                      second[0]);
                  exec(c, AMENDMENT, amendmentRow(tenant, second[0], r1[0]));
                });
            for (String state : List.of("PENDING", "APPROVED", "REJECTED", "CANCELLED")) {
              refused(
                  db,
                  "leave_request_transition",
                  c ->
                      exec(
                          c, "UPDATE people.leave_request SET state = ? WHERE id = ?", state, fid));
            }
            refused(
                db,
                "leave_request_decision_consistent",
                c -> exec(c, DECISION, decisionRow(tenant, fid, "REJECTED", "TENANT_ADMIN", null)));
            refused(
                db,
                "leave_request_cancellation_consistent",
                c -> exec(c, CANCELLATION, cancellationRow(tenant, fid)));
            // Exactly one kind of evidence: a decided request cannot also be amended.
            Object[] twice = row(a, versions[1], d.plusDays(70), d.plusDays(70));
            refused(
                db,
                "leave_request_decided",
                c -> {
                  decide(c, decisionRow(tenant, second[0], "APPROVED", "TENANT_ADMIN", null));
                  exec(c, REQUEST, twice);
                  exec(c, AMENDMENT, amendmentRow(tenant, second[0], twice[0]));
                  exec(c, "SET CONSTRAINTS people.leave_request_decided IMMEDIATE");
                });
            // A request inserted AMENDED commits only with its amendment and replacement.
            Object[] insertedAmended =
                change(row(a, versions[1], d.plusDays(300), d.plusDays(300)), 8, "AMENDED");
            refused(db, "leave_request_decided", c -> exec(c, REQUEST, insertedAmended));

            // A chain: the replacement is amended again, then cancelled normally.
            Object[] r2 = row(a, versions[1], d.plusDays(2), d.plusDays(3));
            committed(db, c -> amend(c, r2, amendmentRow(tenant, r1[0], r2[0])));
            committed(db, c -> cancel(c, cancellationRow(tenant, r2[0])));
            assertThat(
                    count(
                        db,
                        "SELECT count(*) FROM people.leave_request WHERE employee_id = ?"
                            + " AND state = 'AMENDED'",
                        a.employee()))
                .isEqualTo(2);
            assertThat(
                    one(
                        db,
                        "SELECT string_agg(state, ',' ORDER BY start_date, end_date)"
                            + " FROM people.leave_request WHERE id IN (?, ?, ?)",
                        fid,
                        r1[0],
                        r2[0]))
                .isEqualTo("AMENDED,AMENDED,CANCELLED");

            // Amendments are append-only.
            for (String sql :
                List.of(
                    "UPDATE people.leave_request_amendment SET reason_text = 'Autre raison.'",
                    "UPDATE people.leave_request_amendment SET amended_by = 'other'",
                    "UPDATE people.leave_request_amendment SET replacement_request_id ="
                        + " original_request_id",
                    "DELETE FROM people.leave_request_amendment")) {
              refused(db, "leave_request_amendment_immutable", c -> exec(c, sql));
            }
            refused(
                db,
                "leave_request_amendment_no_truncate",
                c -> {
                  try (Statement st = c.createStatement()) {
                    st.execute("TRUNCATE people.leave_request_amendment");
                  }
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Decision authority shapes
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheThreeAuthorityShapesCommit() {
    withDatabase(
        db -> {
          migrate(db.url(), "22");
          UUID tenant = organization(db.jdbc());
          try {
            Hired[] people = new Hired[2];
            UUID[] versions = new UUID[2];
            committed(
                db,
                c -> {
                  people[0] = hire(c, tenant, "E-1");
                  people[1] = hire(c, tenant, "E-2");
                  versions[0] = policyVersion(c, tenant, "MG", "MANAGER");
                  versions[1] = policyVersion(c, tenant, "AD", "TENANT_ADMIN");
                });
            Hired a = people[0];
            UUID manager = people[1].employee();
            LocalDate d = LocalDate.of(2026, 11, 2);
            Object[][] managed = new Object[6][];
            Object[] central = row(a, versions[1], d.plusDays(30), d.plusDays(30));
            committed(
                db,
                c -> {
                  for (int i = 0; i < managed.length; i++) {
                    managed[i] = row(a, versions[0], d.plusDays(i * 2), d.plusDays(i * 2));
                    exec(c, REQUEST, managed[i]);
                  }
                  exec(c, REQUEST, central);
                });

            // MANAGER/MANAGER with the manager, and MANAGER/TENANT_ADMIN_OVERRIDE without.
            committed(
                db,
                c -> decide(c, decisionRow(tenant, managed[0][0], "APPROVED", "MANAGER", manager)));
            committed(db, c -> decide(c, overrideRow(tenant, managed[1][0], "REJECTED")));

            record Shape(String route, String authority, UUID manager) {}
            for (Shape shape :
                List.of(
                    new Shape("MANAGER", "TENANT_ADMIN_OVERRIDE", manager),
                    new Shape("MANAGER", "TENANT_ADMIN", null),
                    new Shape("MANAGER", "MANAGER", null),
                    new Shape("MANAGER", "SOMEONE", null))) {
              Object[] bad = decisionRow(tenant, managed[2][0], "APPROVED", shape.route(), null);
              bad[5] = shape.manager();
              bad[9] = shape.authority();
              refused(db, "leave_request_decision_authority_shape", c -> decide(c, bad));
            }
            // TENANT_ADMIN route: only its own authority, never an override.
            Object[] adminOverride = overrideRow(tenant, central[0], "APPROVED");
            adminOverride[4] = "TENANT_ADMIN";
            refused(db, "leave_request_decision_authority_shape", c -> decide(c, adminOverride));
            // The route is the request's policy route, whatever the authority.
            Object[] wrongRoute = overrideRow(tenant, central[0], "APPROVED");
            refused(db, "leave_request_decision_consistent", c -> decide(c, wrongRoute));
            refused(
                db,
                "leave_request_decision_consistent",
                c ->
                    decide(
                        c, decisionRow(tenant, managed[3][0], "APPROVED", "TENANT_ADMIN", null)));
            committed(
                db,
                c -> decide(c, decisionRow(tenant, central[0], "APPROVED", "TENANT_ADMIN", null)));
            assertThat(
                    one(
                        db,
                        "SELECT string_agg(decision_authority, ',' ORDER BY decision_authority)"
                            + " FROM people.leave_request_decision"))
                .isEqualTo("MANAGER,TENANT_ADMIN,TENANT_ADMIN_OVERRIDE");
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
  void theAmendmentExceptionAndOverrideLookupsReadTheirIndexes() {
    withDatabase(
        db -> {
          migrate(db.url(), "22");
          UUID tenant = organization(db.jdbc());
          try {
            assertThat(
                    plan(
                        db,
                        "SELECT a.id FROM people.leave_request_amendment a"
                            + " WHERE a.tenant_id = ? AND a.original_request_id = ?",
                        tenant,
                        UUID.randomUUID()))
                .containsPattern(
                    "Index (Only )?Scan using leave_request_amendment_original_unique");
            assertThat(
                    plan(
                        db,
                        "SELECT a.id FROM people.leave_request_amendment a"
                            + " WHERE a.tenant_id = ? AND a.replacement_request_id = ?",
                        tenant,
                        UUID.randomUUID()))
                .containsPattern(
                    "Index (Only )?Scan using leave_request_amendment_replacement_unique");
            // The routing-exception queue: pending requests in submission order, each checked
            // against its employment's MANAGER lines.
            String queue =
                plan(
                    db,
                    "SELECT r.id FROM people.leave_request r"
                        + " WHERE r.tenant_id = ? AND r.state = 'PENDING'"
                        + " AND (r.submitted_at, r.id) < (?, ?)"
                        + " AND NOT EXISTS (SELECT 1 FROM people.employment_assignment a"
                        + " WHERE a.tenant_id = r.tenant_id AND a.employment_id = r.employment_id"
                        + " AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL"
                        + " AND a.effective_from <= r.start_date"
                        + " AND (a.effective_to IS NULL OR a.effective_to >= r.start_date))"
                        + " ORDER BY r.submitted_at DESC, r.id DESC LIMIT 26",
                    tenant,
                    Timestamp.from(Instant.now()),
                    UUID.randomUUID());
            assertThat(queue)
                .containsPattern("Index (Only )?Scan using leave_request_pending_queue")
                .contains("employment_assignment_active")
                .doesNotContain("Sort");
            assertThat(
                    plan(
                        db,
                        "SELECT d.decision_authority FROM people.leave_request_decision d"
                            + " WHERE d.tenant_id = ? AND d.request_id = ?",
                        tenant,
                        UUID.randomUUID()))
                .contains("leave_request_decision_request_unique");
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
