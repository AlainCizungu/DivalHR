package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
 * MVP-090A (Issue #141): V37 by direct SQL against real PostgreSQL. The refusing rollback that
 * restores V36 exactly; the run, its source weeks, employee inputs and source rows reconcile at
 * commit or nothing is written; the period boundaries, the tenant and legal-entity binding, the
 * unique run identity and the shift uniqueness hold; generated evidence is append-only.
 */
@IntegrationTest
class PayrollInputMigrationIntegrationTest {

  private static final String KINSHASA = "Africa/Kinshasa";
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final Timestamp AT = Timestamp.from(Instant.parse("2026-11-09T09:00:00Z"));
  private static final String DIGEST = "a".repeat(64);

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
                              FROM pg_constraint WHERE connamespace = 'payroll'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(tgname || ':' || tgenabled::text, ',' ORDER BY tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              JOIN pg_namespace n ON n.oid = c.relnamespace
                              WHERE n.nspname = 'payroll' AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname, ',' ORDER BY proname)
                              FROM pg_proc WHERE pronamespace = 'payroll'::regnamespace), '')
      """;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  /** A tenant with a legal entity and a site. */
  private record World(UUID tenant, UUID entity, UUID site) {}

  /** The identifiers of one consistent run. */
  private record Shape(UUID run, UUID generation, UUID lock, UUID employee, UUID employment) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "payroll37_" + UUID.randomUUID().toString().replace("-", "");
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

  private static String signature(JdbcTemplate jdbc) {
    return jdbc.queryForObject(SIGNATURE, String.class);
  }

  private static int count(Db db, String sql) {
    Integer n = db.jdbc().queryForObject(sql, Integer.class);
    return n == null ? 0 : n;
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

  private void refused(Db db, String constraint, SqlWork work) {
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
                    .as(e.getMessage())
                    .isEqualTo(constraint);
              });
      c.rollback();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void committed(Db db, SqlWork work) {
    try (Connection c = connect(db)) {
      work.run(c);
      c.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

  private World world(Db db) {
    UUID tenant = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    UUID site = UUID.randomUUID();
    committed(
        db,
        c -> {
          exec(
              c,
              "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone,"
                  + " status, created_at, created_by) VALUES (?, 'Org', 'CD', 'fr', ?, 'ACTIVE',"
                  + " now(), 'test')",
              tenant,
              KINSHASA);
          exec(
              c,
              "INSERT INTO tenant.legal_entity (id, tenant_id, code, name, country_code,"
                  + " effective_from, created_at, created_by) VALUES (?, ?, 'LE', 'Entité', 'CD',"
                  + " DATE '2026-01-01', now(), 'test')",
              entity,
              tenant);
          exec(
              c,
              "INSERT INTO tenant.site (id, tenant_id, legal_entity_id, code, name, timezone,"
                  + " effective_from, created_at, created_by) VALUES (?, ?, ?, 'S1', 'Site', ?,"
                  + " DATE '2026-01-01', now(), 'test')",
              site,
              tenant,
              entity,
              KINSHASA);
        });
    return new World(tenant, entity, site);
  }

  /** A run header of one week, one employee and two rows (15 and 15 minutes). */
  private static void run(
      Connection c, World w, UUID run, LocalDate start, LocalDate end, int rows, int minutes)
      throws SQLException {
    exec(
        c,
        "INSERT INTO payroll.input_run (tenant_id, id, legal_entity_id, version, status,"
            + " schema_version, period_start, period_end, business_timezone, week_count,"
            + " employee_count, timesheet_count, row_count, worked_minutes, excluded_rows,"
            + " excluded_timesheets, evidence_digest, generated_at, generated_by) VALUES (?, ?, ?,"
            + " 1, 'GENERATED', 1, ?, ?, ?, 1, 1, 1, ?, ?, 0, 0, ?, ?, 'sub')",
        w.tenant(),
        run,
        w.entity(),
        start,
        end,
        KINSHASA,
        rows,
        minutes,
        DIGEST,
        AT);
  }

  private static void week(Connection c, World w, Shape s, int lockVersion, int rows, int minutes)
      throws SQLException {
    exec(
        c,
        "INSERT INTO payroll.input_source_week (tenant_id, run_id, generation_id, site_id,"
            + " week_start, site_timezone, generation_version, lock_action_id, lock_version,"
            + " lock_evidence_digest, timesheet_count, row_count, worked_minutes) VALUES (?, ?, ?,"
            + " ?, ?, ?, 1, ?, ?, ?, 1, ?, ?)",
        w.tenant(),
        s.run(),
        s.generation(),
        w.site(),
        MONDAY,
        KINSHASA,
        s.lock(),
        lockVersion,
        DIGEST,
        rows,
        minutes);
  }

  private static void employee(
      Connection c, World w, Shape s, LocalDate first, LocalDate last, int rows, int minutes)
      throws SQLException {
    exec(
        c,
        "INSERT INTO payroll.input_employee (tenant_id, run_id, employee_id, employment_id,"
            + " first_work_date, last_work_date, timesheet_count, row_count, worked_minutes)"
            + " VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)",
        w.tenant(),
        s.run(),
        s.employee(),
        s.employment(),
        first,
        last,
        rows,
        minutes);
  }

  private static void row(
      Connection c,
      World w,
      Shape s,
      int line,
      LocalDate day,
      UUID shift,
      UUID timesheet,
      int minutes)
      throws SQLException {
    exec(
        c,
        "INSERT INTO payroll.input_source_row (tenant_id, run_id, line, employee_id,"
            + " employment_id, generation_id, site_id, cost_center_id, work_date, shift_id,"
            + " timesheet_id, timesheet_revision, approval_action_id, approval_cycle,"
            + " lock_action_id, worked_minutes) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, 1, ?,"
            + " 1, ?, ?)",
        w.tenant(),
        s.run(),
        line,
        s.employee(),
        s.employment(),
        s.generation(),
        w.site(),
        day,
        shift,
        timesheet,
        UUID.randomUUID(),
        s.lock(),
        minutes);
  }

  /** A consistent run: one week, one employee, two rows on Monday and Tuesday, 30 minutes. */
  private static Shape consistent(Connection c, World w, LocalDate start, LocalDate end)
      throws SQLException {
    Shape s =
        new Shape(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    UUID timesheet = UUID.randomUUID();
    run(c, w, s.run(), start, end, 2, 30);
    week(c, w, s, 1, 2, 30);
    employee(c, w, s, MONDAY, MONDAY.plusDays(1), 2, 30);
    row(c, w, s, 1, MONDAY, UUID.randomUUID(), timesheet, 15);
    row(c, w, s, 2, MONDAY.plusDays(1), UUID.randomUUID(), timesheet, 15);
    return s;
  }

  // ------------------------------------------------------------------------------------------
  // Rollback and upgrade
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV36ExactlyAndRefusesPayrollInputEvidence() {
    withDatabase(
        db -> {
          migrate(db.url(), "36");
          String v36 = signature(db.jdbc());
          assertThat(v36).startsWith("|");
          migrate(db.url(), "37");
          String v37 = signature(db.jdbc());
          assertThat(v37)
              .isNotEqualTo(v36)
              .contains("input_run,input_source_row,input_source_week")
              .contains("payroll_input_run_history")
              .contains("payroll_input_run_exact:O")
              .contains("payroll_input_source_row_immutable:O")
              .contains("input_immutable,input_no_truncate,input_run_exact");
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V37__rollback.sql"));
          assertThat(signature(db.jdbc())).isEqualTo(v36);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);

          db.jdbc().execute(resource("db/migration/V37__payroll_inputs.sql"));
          assertThat(signature(db.jdbc())).isEqualTo(v37);
          // Once a run exists, the rollback refuses and changes nothing.
          World w = world(db);
          committed(db, c -> consistent(c, w, MONDAY, MONDAY.plusDays(6)));
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V37__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V37 rollback refused: payroll-input evidence exists");
          assertThat(signature(db.jdbc())).isEqualTo(v37);
          assertThat(count(db, "SELECT count(*) FROM payroll.input_run")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM payroll.input_source_row")).isEqualTo(2);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Reconciliation, boundaries, binding, identity and immutability by direct SQL
  // ------------------------------------------------------------------------------------------

  @Test
  void runsReconcileExactlyAtCommitAreBoundToTheirScopeAndAreAppendOnly() {
    withDatabase(
        db -> {
          migrate(db.url(), "37");
          World w = world(db);
          LocalDate sunday = MONDAY.plusDays(6);

          // A consistent run commits; the deferred check passes.
          committed(db, c -> consistent(c, w, MONDAY, sunday));
          assertThat(count(db, "SELECT count(*) FROM payroll.input_run")).isEqualTo(1);

          // One run per legal entity, period and version.
          refused(db, "payroll_input_run_version_unique", c -> consistent(c, w, MONDAY, sunday));
          // The period boundaries: ordered, at most 31 days, not before 2026; the legal entity
          // must exist in the tenant.
          refused(
              db,
              "payroll_input_run_period",
              c -> run(c, w, UUID.randomUUID(), sunday, MONDAY, 2, 30));
          refused(
              db,
              "payroll_input_run_period",
              c -> run(c, w, UUID.randomUUID(), MONDAY, MONDAY.plusDays(31), 2, 30));
          refused(
              db,
              "payroll_input_run_period",
              c ->
                  run(
                      c,
                      w,
                      UUID.randomUUID(),
                      LocalDate.of(2025, 12, 29),
                      LocalDate.of(2026, 1, 4),
                      2,
                      30));
          refused(
              db,
              "payroll_input_run_legal_entity",
              c ->
                  run(
                      c,
                      new World(w.tenant(), UUID.randomUUID(), w.site()),
                      UUID.randomUUID(),
                      MONDAY.plusDays(7),
                      MONDAY.plusDays(13),
                      2,
                      30));
          // Nothing partial: a run without rows, a run whose counts differ from its rows, an
          // employee row without rows, a row outside the period, rows not numbered 1..n, and a
          // week whose rows are not at its lock are all refused at commit.
          LocalDate next = MONDAY.plusDays(7);
          refused(
              db,
              "payroll_input_run_exact",
              c -> run(c, w, UUID.randomUUID(), next, next.plusDays(6), 2, 30));
          refused(
              db,
              "payroll_input_run_exact",
              c -> {
                Shape s = consistent(c, w, next, next.plusDays(6));
                // A third row makes the counts wrong (the header says 2 rows, 30 minutes).
                row(c, w, s, 3, MONDAY.plusDays(2), UUID.randomUUID(), UUID.randomUUID(), 5);
              });
          refused(
              db,
              "payroll_input_run_exact",
              c -> {
                Shape s = consistent(c, w, next, next.plusDays(6));
                employee(
                    c,
                    w,
                    new Shape(s.run(), s.generation(), s.lock(), UUID.randomUUID(), s.employment()),
                    MONDAY,
                    MONDAY,
                    1,
                    0);
              });
          refused(
              db,
              "payroll_input_run_exact",
              c -> {
                // The rows lie on MONDAY and the day after; the period is the following week.
                consistent(c, w, next.plusDays(1), next.plusDays(6));
              });
          refused(
              db,
              "payroll_input_run_exact",
              c -> {
                Shape s =
                    new Shape(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID());
                run(c, w, s.run(), next, next.plusDays(6), 2, 30);
                week(c, w, s, 1, 2, 30);
                employee(c, w, s, MONDAY, MONDAY.plusDays(1), 2, 30);
                UUID timesheet = UUID.randomUUID();
                row(c, w, s, 1, MONDAY, UUID.randomUUID(), timesheet, 15);
                row(c, w, s, 5, MONDAY.plusDays(1), UUID.randomUUID(), timesheet, 15);
              });
          // One shift of one timesheet is counted once per run.
          refused(
              db,
              "payroll_input_source_row_shift_unique",
              c -> {
                Shape s =
                    new Shape(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID());
                run(c, w, s.run(), next, next.plusDays(6), 2, 30);
                week(c, w, s, 1, 2, 30);
                employee(c, w, s, MONDAY, MONDAY, 2, 30);
                UUID timesheet = UUID.randomUUID();
                UUID shift = UUID.randomUUID();
                row(c, w, s, 1, MONDAY, shift, timesheet, 15);
                row(c, w, s, 2, MONDAY, shift, timesheet, 15);
              });
          // A source week binds a lock (odd version), a Monday and the tenant's site.
          refused(
              db,
              "payroll_input_source_week_lock",
              c -> {
                Shape s = consistent(c, w, next, next.plusDays(6));
                week(
                    c,
                    w,
                    new Shape(
                        s.run(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        s.employee(),
                        s.employment()),
                    2,
                    1,
                    15);
              });
          refused(
              db,
              "payroll_input_source_week_site",
              c -> {
                Shape s =
                    new Shape(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID());
                run(c, w, s.run(), next, next.plusDays(6), 2, 30);
                week(c, new World(w.tenant(), w.entity(), UUID.randomUUID()), s, 1, 2, 30);
              });
          assertThat(count(db, "SELECT count(*) FROM payroll.input_run")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM payroll.input_source_row")).isEqualTo(2);

          // Append-only: no update, delete or truncate of any generated evidence.
          for (String table :
              new String[] {
                "input_run", "input_source_week", "input_employee", "input_source_row"
              }) {
            refused(
                db,
                "payroll_input_immutable",
                c -> exec(c, "UPDATE payroll." + table + " SET worked_minutes = 0"));
            refused(db, "payroll_input_immutable", c -> exec(c, "DELETE FROM payroll." + table));
            refused(
                db,
                "payroll_input_no_truncate",
                c -> exec(c, "TRUNCATE payroll." + table + " CASCADE"));
          }
          assertThat(count(db, "SELECT count(*) FROM payroll.input_run")).isEqualTo(1);
          assertThat(count(db, "SELECT sum(worked_minutes) FROM payroll.input_source_row"))
              .isEqualTo(30);
        });
  }
}
