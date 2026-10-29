package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * MVP-093A V43 directly against PostgreSQL in a database of its own (D93-6, D93-14): the refusing
 * rollback that restores V42 exactly, and every rule the database enforces whatever the application
 * checks: the tenant and employee binding, the increasing sequence, the legal action shape, the
 * money bounds, the restricted explanation, the append-only evidence, the exclusion of overlapping
 * active facts, the exact deferred match between the latest change and the timeline and the
 * 100-change limit. Each refusal is proved by the constraint it names.
 */
@IntegrationTest
class CompensationMigrationIntegrationTest {

  /** The payroll schema: tables, columns, indexes, constraints, triggers and functions. */
  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'payroll'), '')
          || '|' || coalesce((SELECT string_agg(table_name || '.' || column_name || ':'
                                  || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, ''),
                                  ',' ORDER BY table_name, column_name)
                              FROM information_schema.columns
                              WHERE table_schema = 'payroll'), '')
          || '|' || coalesce((SELECT string_agg(indexname || ':' || indexdef, ','
                                  ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'payroll'), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY conname)
                              FROM pg_constraint
                              WHERE connamespace = 'payroll'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(pg_get_triggerdef(t.oid), ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'payroll'::regnamespace
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ','
                                  ORDER BY proname) FROM pg_proc
                              WHERE pronamespace = 'payroll'::regnamespace), '')
      """;

  private static final String CHANGE =
      "INSERT INTO payroll.compensation_change (tenant_id, employee_id, id, seq, action,"
          + " occurred_at, actor_subject, actor_role, currency, exponent, amount_minor,"
          + " effective_from, target_change_id, reason_code, explanation,"
          + " retroactive_acknowledged, preview_digest) VALUES (?, ?, ?, ?, ?, now(), 'subject',"
          + " 'tenant-admin', ?, ?, ?, ?, ?, ?, ?, ?, repeat('a', 64))";
  private static final String FACT =
      "INSERT INTO payroll.compensation (tenant_id, employee_id, id, origin_change_id,"
          + " created_seq, frequency, currency, exponent, amount_minor, effective_from,"
          + " effective_to) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
  private static final LocalDate JAN = LocalDate.of(2026, 10, 1);
  private static final LocalDate DEC = LocalDate.of(2026, 12, 1);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcConnectionDetails connection;

  private record Db(String url, JdbcTemplate jdbc) {}

  /** A tenant and its employee, with the first fact established on {@code JAN}. */
  private record Shape(UUID tenant, UUID employee, UUID established, UUID fact) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "compensation93_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url = connection.getJdbcUrl().replaceAll("/[^/?]+(\\?.*)?$", "/" + database + "$1");
      body.accept(
          new Db(
              url,
              new JdbcTemplate(
                  new DriverManagerDataSource(
                      url, connection.getUsername(), connection.getPassword()))));
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }

  private void migrate(String url, String target) {
    Flyway.configure()
        .dataSource(url, connection.getUsername(), connection.getPassword())
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

  /** Runs a script as written (no JDBC escape processing, as Flyway sends it). */
  private static void script(Db db, String sql) {
    db.jdbc()
        .execute(
            (org.springframework.jdbc.core.ConnectionCallback<Void>)
                c -> {
                  try (java.sql.Statement statement = c.createStatement()) {
                    statement.setEscapeProcessing(false);
                    statement.execute(sql);
                  }
                  return null;
                });
  }

  private Connection connect(Db db) throws SQLException {
    Connection c =
        DriverManager.getConnection(db.url(), connection.getUsername(), connection.getPassword());
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

  private static UUID organization(JdbcTemplate db) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone,"
            + " status, created_at, created_by) VALUES (?, 'Org', 'CD', 'fr', 'Africa/Kinshasa',"
            + " 'ACTIVE', now(), 'test')",
        id);
    return id;
  }

  private static UUID employee(JdbcTemplate db, UUID tenant) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,"
            + " search_key, created_at, created_by) VALUES (?, ?, ?, 'Ana', 'Mbuyi',"
            + " ' ana mbuyi ', now(), 'test')",
        id,
        tenant,
        "E-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT));
    return id;
  }

  private static void change(
      Connection c,
      Shape shape,
      UUID id,
      int seq,
      String action,
      String currency,
      long minor,
      LocalDate from,
      UUID target,
      String reason,
      String explanation,
      boolean acknowledged)
      throws SQLException {
    exec(
        c,
        CHANGE,
        shape.tenant(),
        shape.employee(),
        id,
        seq,
        action,
        currency,
        currency.equals("CDF") ? 0 : 2,
        minor,
        from,
        target,
        reason,
        explanation,
        acknowledged);
  }

  /** A plain {@code CHANGED} change starting on {@code DEC}. */
  private static void changed(
      Connection c, Shape shape, UUID id, int seq, String currency, long minor)
      throws SQLException {
    change(c, shape, id, seq, "CHANGED", currency, minor, DEC, null, null, null, false);
  }

  private static void fact(
      Connection c,
      Shape shape,
      UUID origin,
      int seq,
      long minor,
      LocalDate from,
      LocalDate to,
      String frequency)
      throws SQLException {
    exec(
        c,
        FACT,
        shape.tenant(),
        shape.employee(),
        UUID.randomUUID(),
        origin,
        seq,
        frequency,
        "USD",
        2,
        minor,
        from,
        to);
  }

  /** A tenant and employee with an established USD 1,000.00 from {@code JAN}, committed. */
  private Shape seeded(Db db) throws SQLException {
    UUID tenant = organization(db.jdbc());
    Shape shape = new Shape(tenant, employee(db.jdbc(), tenant), UUID.randomUUID(), null);
    try (Connection c = connect(db)) {
      change(
          c,
          shape,
          shape.established(),
          1,
          "ESTABLISHED",
          "USD",
          100000,
          JAN,
          null,
          null,
          null,
          false);
      fact(c, shape, shape.established(), 1, 100000, JAN, null, "MONTHLY");
      c.commit();
    }
    return shape;
  }

  /**
   * Runs statements in one transaction with the deferred checks forced to run, and proves that the
   * database refuses them by the named constraint.
   */
  private void refuses(Db db, String constraint, SqlWork work) {
    assertThatThrownBy(
            () -> {
              try (Connection c = connect(db)) {
                try {
                  work.run(c);
                  exec(c, "SET CONSTRAINTS ALL IMMEDIATE");
                } finally {
                  c.rollback();
                }
              }
            })
        .as(constraint)
        .isInstanceOfSatisfying(
            PSQLException.class,
            e -> assertThat(e.getServerErrorMessage().getConstraint()).isEqualTo(constraint));
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  @Test
  void theRollbackRestoresV42ExactlyAndRefusesOnceCompensationExists() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "42");
          String v42 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "43");
          String v43 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v43).isNotEqualTo(v42).contains("compensation_change");
          int recorded =
              java.util.Objects.requireNonNull(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
          script(db, resource("db/rollback/V43__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v42);
          assertThat(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
              .isEqualTo(recorded);

          // With no rows the rollback ran; the migration can be applied again.
          script(db, resource("db/migration/V43__payroll_monthly_compensation.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v43);

          // Once a compensation exists, the rollback refuses and changes nothing.
          try {
            seeded(db);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> script(db, resource("db/rollback/V43__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V43 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v43);
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT count(*) FROM payroll.compensation_change", Integer.class))
              .isEqualTo(1);
        });
  }

  @Test
  void theEvidenceIsAppendOnlyAndTheTimelineIsExact() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "43");
          try {
            Shape one = seeded(db);
            Shape two = seeded(db);
            UUID next = UUID.randomUUID();

            // Change evidence and facts are never rewritten or removed.
            refuses(
                db,
                "payroll_compensation_immutable",
                c ->
                    exec(
                        c,
                        "DELETE FROM payroll.compensation_change WHERE id = ?",
                        one.established()));
            refuses(
                db,
                "payroll_compensation_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE payroll.compensation_change SET amount_minor = 1 WHERE id = ?",
                        one.established()));
            refuses(
                db,
                "payroll_compensation_immutable",
                c ->
                    exec(
                        c,
                        "DELETE FROM payroll.compensation WHERE employee_id = ?",
                        one.employee()));
            refuses(
                db,
                "payroll_compensation_immutable",
                c -> exec(c, "TRUNCATE payroll.compensation"));
            refuses(
                db,
                "payroll_compensation_supersession",
                c ->
                    exec(
                        c,
                        "UPDATE payroll.compensation SET amount_minor = 1 WHERE employee_id = ?",
                        one.employee()));

            // One increasing sequence; the establishment is first and only.
            refuses(
                db,
                "payroll_compensation_change_sequence",
                c -> changed(c, one, next, 9, "USD", 5));
            refuses(
                db,
                "payroll_compensation_change_first",
                c ->
                    change(c, one, next, 2, "ESTABLISHED", "USD", 5, DEC, null, null, null, false));

            // The tenant and employee binding.
            refuses(
                db,
                "payroll_compensation_change_employee",
                c ->
                    change(
                        c,
                        new Shape(one.tenant(), two.employee(), null, null),
                        next,
                        1,
                        "ESTABLISHED",
                        "USD",
                        5,
                        JAN,
                        null,
                        null,
                        null,
                        false));

            // Money bounds per currency and exponent.
            refuses(
                db,
                "payroll_compensation_change_money",
                c -> changed(c, one, next, 2, "USD", 10000001));
            refuses(
                db,
                "payroll_compensation_change_money",
                c -> changed(c, one, next, 2, "CDF", 1000000001));
            refuses(
                db, "payroll_compensation_change_money", c -> changed(c, one, next, 2, "USD", 0));

            // The legal shape of a correction and its restricted explanation.
            refuses(
                db,
                "payroll_compensation_change_shape",
                c ->
                    change(
                        c,
                        one,
                        next,
                        2,
                        "CORRECTED",
                        "USD",
                        5,
                        DEC,
                        one.established(),
                        null,
                        null,
                        true));
            refuses(
                db,
                "payroll_compensation_change_shape",
                c ->
                    change(
                        c,
                        one,
                        next,
                        2,
                        "CORRECTED",
                        "USD",
                        5,
                        DEC,
                        one.established(),
                        "DATA_ENTRY_ERROR",
                        "explication suffisante",
                        false));
            refuses(
                db,
                "payroll_compensation_change_explanation",
                c ->
                    change(
                        c,
                        one,
                        next,
                        2,
                        "CORRECTED",
                        "USD",
                        5,
                        DEC,
                        one.established(),
                        "DATA_ENTRY_ERROR",
                        "<b>explication</b> x",
                        true));
            refuses(
                db,
                "payroll_compensation_change_explanation",
                c ->
                    change(
                        c,
                        one,
                        next,
                        2,
                        "CORRECTED",
                        "USD",
                        5,
                        DEC,
                        one.established(),
                        "DATA_ENTRY_ERROR",
                        "https://exemple.cd/x",
                        true));
            refuses(
                db,
                "payroll_compensation_change_reason",
                c ->
                    change(
                        c,
                        one,
                        next,
                        2,
                        "CORRECTED",
                        "USD",
                        5,
                        DEC,
                        one.established(),
                        "WHATEVER",
                        "explication suffisante",
                        true));
            refuses(
                db,
                "payroll_compensation_change_target",
                c ->
                    change(
                        c,
                        one,
                        next,
                        2,
                        "SCHEDULED_CHANGE_CANCELLED",
                        "USD",
                        5,
                        DEC,
                        one.established(),
                        null,
                        null,
                        false));

            // A fact: one frequency, never overlapping another active fact, written by the latest
            // change, and the active timeline equals the latest change exactly (deferred).
            refuses(
                db,
                "payroll_compensation_frequency",
                c -> {
                  changed(c, one, next, 2, "USD", 5);
                  fact(c, one, next, 2, 5, DEC, null, "HOURLY");
                });
            refuses(
                db,
                "payroll_compensation_no_overlap",
                c -> {
                  changed(c, one, next, 2, "USD", 5);
                  fact(c, one, next, 2, 5, DEC, null, "MONTHLY");
                });
            refuses(
                db,
                "payroll_compensation_creation",
                c -> {
                  // A fact written by an earlier change than the employee's latest.
                  changed(c, one, next, 2, "USD", 5);
                  fact(c, one, one.established(), 1, 5, LocalDate.of(2030, 1, 1), null, "MONTHLY");
                });
            refuses(
                db, "payroll_compensation_change_exact", c -> changed(c, one, next, 2, "USD", 5));
            refuses(
                db,
                "payroll_compensation_exact",
                c -> {
                  changed(c, one, next, 2, "USD", 130000);
                  exec(
                      c,
                      "UPDATE payroll.compensation SET superseded_seq = 2 WHERE employee_id = ?",
                      one.employee());
                  fact(c, one, one.established(), 2, 100000, JAN, DEC.minusDays(1), "MONTHLY");
                  // The new fact does not carry the change's value: the timeline is not exact.
                  fact(c, one, next, 2, 999, DEC, null, "MONTHLY");
                });
            refuses(
                db,
                "payroll_compensation_exact",
                c -> {
                  changed(c, one, next, 2, "USD", 130000);
                  exec(
                      c,
                      "UPDATE payroll.compensation SET superseded_seq = 2 WHERE employee_id = ?",
                      one.employee());
                  fact(c, one, one.established(), 2, 100000, JAN, DEC.minusDays(2), "MONTHLY");
                  // A gap of one day between the first interval and the new one.
                  fact(c, one, next, 2, 130000, DEC, null, "MONTHLY");
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Test
  void aLegalChangeCommitsAndAnEmployeeHasAtMostOneHundred() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "43");
          try {
            Shape shape = seeded(db);
            UUID changed = UUID.randomUUID();
            try (Connection c = connect(db)) {
              changed(c, shape, changed, 2, "USD", 120000);
              exec(
                  c,
                  "UPDATE payroll.compensation SET superseded_seq = 2 WHERE employee_id = ?",
                  shape.employee());
              fact(c, shape, shape.established(), 2, 100000, JAN, DEC.minusDays(1), "MONTHLY");
              fact(c, shape, changed, 2, 120000, DEC, null, "MONTHLY");
              c.commit();
            }
            assertThat(
                    db.jdbc()
                        .queryForObject(
                            "SELECT count(*) FROM payroll.compensation WHERE employee_id = ?"
                                + " AND superseded_seq IS NULL",
                            Integer.class,
                            shape.employee()))
                .isEqualTo(2);

            // Changes 3 to 100 pass the sequence guard (their exactness is checked at commit,
            // which this test never reaches); the 101st is refused by name.
            refuses(
                db,
                "payroll_compensation_change_limit",
                c ->
                    exec(
                        c,
                        "INSERT INTO payroll.compensation_change SELECT ?, ?, gen_random_uuid(), g,"
                            + " 'CHANGED', now(), 's', 'tenant-admin', 'USD', 2, 5, current_date,"
                            + " NULL, NULL, NULL, false, repeat('a', 64)"
                            + " FROM generate_series(3, 101) g",
                        shape.tenant(),
                        shape.employee()));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
