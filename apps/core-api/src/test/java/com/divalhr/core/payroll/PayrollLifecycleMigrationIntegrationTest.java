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
import java.util.concurrent.atomic.AtomicReference;
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
 * MVP-092 (Issue #149): V38 by direct SQL against real PostgreSQL. The refusing rollback that
 * restores V37 exactly; the version chain is gap-free, fork-free and bound to one tenant, legal
 * entity and period; only the latest version locks, once; a locked run is never superseded; only a
 * locked run owns one export whose rows are all and only the run's source rows in the deterministic
 * order with reconciling counts and minutes; every new table is append-only.
 */
@IntegrationTest
class PayrollLifecycleMigrationIntegrationTest {

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
    String database = "payroll38_" + UUID.randomUUID().toString().replace("-", "");
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
  // V38 fixtures
  // ------------------------------------------------------------------------------------------

  /** A run of the given version with one week, one employee and two rows (15 + 15 minutes). */
  private static Shape versioned(Connection c, World w, int version, LocalDate start, LocalDate end)
      throws SQLException {
    Shape s =
        new Shape(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    exec(
        c,
        "INSERT INTO payroll.input_run (tenant_id, id, legal_entity_id, version, status,"
            + " schema_version, period_start, period_end, business_timezone, week_count,"
            + " employee_count, timesheet_count, row_count, worked_minutes, excluded_rows,"
            + " excluded_timesheets, evidence_digest, generated_at, generated_by) VALUES (?, ?, ?,"
            + " ?, 'GENERATED', 1, ?, ?, ?, 1, 1, 1, 2, 30, 0, 0, ?, ?, 'sub')",
        w.tenant(),
        s.run(),
        w.entity(),
        version,
        start,
        end,
        KINSHASA,
        DIGEST,
        AT);
    week(c, w, s, 1, 2, 30);
    employee(c, w, s, MONDAY, MONDAY.plusDays(1), 2, 30);
    UUID timesheet = UUID.randomUUID();
    row(c, w, s, 1, MONDAY, UUID.randomUUID(), timesheet, 15);
    row(c, w, s, 2, MONDAY.plusDays(1), UUID.randomUUID(), timesheet, 15);
    return s;
  }

  private static void supersedes(
      Connection c, World w, Shape next, Shape previous, String reason, String explanation)
      throws SQLException {
    exec(
        c,
        "INSERT INTO payroll.input_run_supersession (tenant_id, run_id, supersedes_run_id,"
            + " reason_code, explanation, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, 'sub')",
        w.tenant(),
        next.run(),
        previous.run(),
        reason,
        explanation,
        AT);
  }

  private static UUID lock(Connection c, World w, Shape s, int version, String digest)
      throws SQLException {
    return lockIn(c, w, s, MONDAY, MONDAY.plusDays(6), version, digest);
  }

  /** A lock of a run whose scope is the given period. */
  private static UUID lockIn(
      Connection c, World w, Shape s, LocalDate start, LocalDate end, int version, String digest)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO payroll.input_run_lock (tenant_id, id, run_id, legal_entity_id, period_start,"
            + " period_end, run_version, action, lock_version, evidence_digest, locked_at,"
            + " locked_by) VALUES (?, ?, ?, ?, ?, ?, ?, 'LOCKED', 1, ?, ?, 'sub')",
        w.tenant(),
        id,
        s.run(),
        w.entity(),
        start,
        end,
        version,
        digest,
        AT);
    return id;
  }

  private static UUID exportHeader(
      Connection c, World w, Shape s, UUID lock, int version, int rows, int minutes)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO payroll.input_export (tenant_id, id, run_id, lock_id, schema_version,"
            + " legal_entity_id, period_start, period_end, run_version, employee_count, row_count,"
            + " worked_minutes, csv_sha256, created_at, created_by) VALUES (?, ?, ?, ?, 1, ?, ?, ?,"
            + " ?, 1, ?, ?, ?, ?, 'sub')",
        w.tenant(),
        id,
        s.run(),
        lock,
        w.entity(),
        MONDAY,
        MONDAY.plusDays(6),
        version,
        rows,
        minutes,
        DIGEST,
        AT);
    return id;
  }

  /** Copies the run's source rows into the export; the line and minute expressions are given. */
  private static void exportRows(
      Connection c, World w, Shape s, UUID export, String line, String minutes, String filter)
      throws SQLException {
    exec(
        c,
        "INSERT INTO payroll.input_export_row (tenant_id, export_id, line, source_line,"
            + " employee_id, employment_id, legal_entity_id, site_id, cost_center_id, work_date,"
            + " approved_worked_minutes, timesheet_id, timesheet_revision, approval_action_id,"
            + " approval_cycle, source_week_start, source_generation_id, source_generation_version,"
            + " source_lock_action_id, source_lock_version, payroll_input_run_id,"
            + " payroll_input_run_version) SELECT r.tenant_id, ?, "
            + line
            + ", r.line, r.employee_id, r.employment_id, run.legal_entity_id, r.site_id,"
            + " r.cost_center_id, r.work_date, "
            + minutes
            + ", r.timesheet_id, r.timesheet_revision, r.approval_action_id, r.approval_cycle,"
            + " w.week_start, r.generation_id, w.generation_version, r.lock_action_id,"
            + " w.lock_version, r.run_id, run.version FROM payroll.input_source_row r JOIN"
            + " payroll.input_run run ON run.tenant_id = r.tenant_id AND run.id = r.run_id JOIN"
            + " payroll.input_source_week w ON w.tenant_id = r.tenant_id AND w.run_id = r.run_id"
            + " AND w.generation_id = r.generation_id WHERE r.tenant_id = ? AND r.run_id = ?"
            + filter,
        export,
        w.tenant(),
        s.run());
  }

  private static Shape shape(AtomicReference<Shape> holder) {
    return holder.get();
  }

  // ------------------------------------------------------------------------------------------
  // Rollback and upgrade
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV37ExactlyAndRefusesVersionLockAndExportEvidence() {
    withDatabase(
        db -> {
          migrate(db.url(), "37");
          String v37 = signature(db.jdbc());
          migrate(db.url(), "38");
          String v38 = signature(db.jdbc());
          assertThat(v38)
              .isNotEqualTo(v37)
              .contains("input_export,input_export_row")
              .contains("input_run_lock")
              .contains("input_run_supersession")
              .contains("payroll_input_export_exact:O")
              .contains("payroll_input_run_chain:O")
              .contains("payroll_input_lock_valid:O")
              .contains("payroll_input_supersession_valid:O")
              .contains("input_scope_lock")
              .contains("input_export_exact")
              .contains("input_run_chain");
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V38__rollback.sql"));
          assertThat(signature(db.jdbc())).isEqualTo(v37);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);

          db.jdbc().execute(resource("db/migration/V38__payroll_input_versions_locks_exports.sql"));
          assertThat(signature(db.jdbc())).isEqualTo(v38);

          // Version 1 evidence alone does not block the rollback of the V38 objects.
          World w = world(db);
          committed(db, c -> consistent(c, w, MONDAY, MONDAY.plusDays(6)));
          db.jdbc().execute(resource("db/rollback/V38__rollback.sql"));
          assertThat(signature(db.jdbc())).isEqualTo(v37);
          assertThat(count(db, "SELECT count(*) FROM payroll.input_run")).isEqualTo(1);
          db.jdbc().execute(resource("db/migration/V38__payroll_input_versions_locks_exports.sql"));

          // A replacement version, a lock or an export each make the rollback refuse; nothing is
          // changed or deleted.
          World again = world(db);
          AtomicReference<Shape> first = new AtomicReference<>();
          committed(db, c -> first.set(consistent(c, again, MONDAY, MONDAY.plusDays(6))));
          assertThat(first.get()).isNotNull();
          committed(
              db,
              c -> {
                Shape next = versioned(c, again, 2, MONDAY, MONDAY.plusDays(6));
                supersedes(c, again, next, shape(first), "UPSTREAM_CORRECTION", null);
              });
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V38__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining(
                  "V38 rollback refused: payroll-input version, lock or export evidence exists");
          assertThat(signature(db.jdbc())).isEqualTo(v38);
          assertThat(count(db, "SELECT count(*) FROM payroll.input_run")).isEqualTo(3);
          assertThat(count(db, "SELECT count(*) FROM payroll.input_run_supersession")).isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // The chain, the lock and the export by direct SQL
  // ------------------------------------------------------------------------------------------

  @Test
  void theChainIsGapFreeForkFreeAndBoundToItsScopeAndOnlyTheLatestVersionLocksOnce() {
    withDatabase(
        db -> {
          migrate(db.url(), "38");
          World w = world(db);
          LocalDate sunday = MONDAY.plusDays(6);
          AtomicReference<Shape> v1 = new AtomicReference<>();
          AtomicReference<Shape> v2 = new AtomicReference<>();
          committed(db, c -> v1.set(consistent(c, w, MONDAY, sunday)));

          // A version above 1 without its supersession is refused at commit.
          refused(db, "payroll_input_run_chain", c -> versioned(c, w, 2, MONDAY, sunday));
          // A supersession must be the next version of the previous one, in the same scope.
          refused(
              db,
              "payroll_input_supersession_valid",
              c -> {
                Shape skip = versioned(c, w, 3, MONDAY, sunday);
                supersedes(c, w, skip, shape(v1), "UPSTREAM_CORRECTION", null);
              });
          refused(
              db,
              "payroll_input_supersession_valid",
              c -> {
                Shape other = versioned(c, w, 2, MONDAY.plusDays(7), MONDAY.plusDays(13));
                supersedes(c, w, other, shape(v1), "UPSTREAM_CORRECTION", null);
              });
          // The reason is closed; OTHER needs a bounded plain-text explanation, others none.
          for (String[] bad :
              new String[][] {
                {"OTHER", null},
                {"UPSTREAM_CORRECTION", "text"},
                {"OTHER", "see <b>this</b>"},
                {"OTHER", "see https://example.com"},
                {"OTHER", "mail a@b"},
                {"OTHER", "x".repeat(201)},
                {"PAYROLL", null}
              }) {
            refused(
                db,
                bad[0].equals("PAYROLL")
                    ? "payroll_input_supersession_reason"
                    : "payroll_input_supersession_explanation",
                c -> {
                  Shape next = versioned(c, w, 2, MONDAY, sunday);
                  supersedes(c, w, next, shape(v1), bad[0], bad[1]);
                });
          }
          // The valid replacement commits with its chain link.
          committed(
              db,
              c -> {
                Shape next = versioned(c, w, 2, MONDAY, sunday);
                supersedes(c, w, next, shape(v1), "OTHER", "Scope corrected after the review");
                v2.set(next);
              });
          assertThat(count(db, "SELECT count(*) FROM payroll.input_run")).isEqualTo(2);
          // A run is replaced at most once (no fork), whichever version the fork claims.
          refused(
              db,
              "payroll_input_run_version_unique",
              c -> {
                Shape fork = versioned(c, w, 2, MONDAY, sunday);
                supersedes(c, w, fork, shape(v1), "UPSTREAM_REAPPROVAL", null);
              });

          // Only the latest version is locked, with exactly its own facts, once.
          refused(db, "payroll_input_lock_latest", c -> lock(c, w, shape(v1), 1, DIGEST));
          refused(db, "payroll_input_lock_valid", c -> lock(c, w, shape(v2), 2, "b".repeat(64)));
          refused(db, "payroll_input_lock_valid", c -> lock(c, w, shape(v2), 1, DIGEST));
          AtomicReference<UUID> lock = new AtomicReference<>();
          committed(db, c -> lock.set(lock(c, w, shape(v2), 2, DIGEST)));
          refused(db, "payroll_input_lock_one", c -> lock(c, w, shape(v2), 2, DIGEST));
          // A locked run is never superseded.
          refused(
              db,
              "payroll_input_supersession_locked",
              c -> {
                Shape next = versioned(c, w, 3, MONDAY, sunday);
                supersedes(c, w, next, shape(v2), "UPSTREAM_CORRECTION", null);
              });
          assertThat(count(db, "SELECT count(*) FROM payroll.input_run")).isEqualTo(2);

          // The export: only a locked run owns one; rows are all and only the run's source rows.
          refused(
              db,
              "payroll_input_export_lock",
              c -> {
                UUID header = exportHeader(c, w, shape(v1), lock.get(), 1, 2, 30);
                exportRows(c, w, shape(v1), header, "r.line", "r.worked_minutes", "");
              });
          // Provenance: an export's lock_id is the lock row of its own run. Two locked runs (two
          // scopes) each satisfy both single-column foreign keys with the other's lock id; only the
          // composite key refuses the crossed pairing, in either direction.
          AtomicReference<Shape> other = new AtomicReference<>();
          AtomicReference<UUID> otherLock = new AtomicReference<>();
          committed(
              db,
              c -> {
                other.set(consistent(c, w, MONDAY, MONDAY.plusDays(13)));
                otherLock.set(lockIn(c, w, shape(other), MONDAY, MONDAY.plusDays(13), 1, DIGEST));
              });
          refused(
              db,
              "payroll_input_export_lock_id",
              c -> {
                UUID header = exportHeader(c, w, shape(v2), otherLock.get(), 2, 2, 30);
                exportRows(c, w, shape(v2), header, "r.line", "r.worked_minutes", "");
              });
          refused(
              db,
              "payroll_input_export_lock_id",
              c -> {
                UUID header = exportHeader(c, w, shape(other), lock.get(), 1, 2, 30);
                exportRows(c, w, shape(other), header, "r.line", "r.worked_minutes", "");
              });
          refused(
              db,
              "payroll_input_export_exact",
              c -> {
                UUID header = exportHeader(c, w, shape(v2), lock.get(), 2, 3, 30);
                exportRows(c, w, shape(v2), header, "r.line", "r.worked_minutes", "");
              });
          refused(
              db,
              "payroll_input_export_exact",
              c -> {
                UUID header = exportHeader(c, w, shape(v2), lock.get(), 2, 2, 30);
                exportRows(
                    c, w, shape(v2), header, "r.line", "r.worked_minutes", " AND r.line = 1");
              });
          refused(
              db,
              "payroll_input_export_exact",
              c -> {
                UUID header = exportHeader(c, w, shape(v2), lock.get(), 2, 2, 30);
                exportRows(c, w, shape(v2), header, "3 - r.line", "r.worked_minutes", "");
              });
          refused(
              db,
              "payroll_input_export_exact",
              c -> {
                UUID header = exportHeader(c, w, shape(v2), lock.get(), 2, 2, 30);
                exportRows(c, w, shape(v2), header, "r.line", "r.worked_minutes + r.line", "");
              });
          refused(
              db,
              "payroll_input_export_exact",
              c -> {
                UUID header = exportHeader(c, w, shape(v2), lock.get(), 1, 2, 30);
                exportRows(c, w, shape(v2), header, "r.line", "r.worked_minutes", "");
              });
          refused(
              db,
              "payroll_input_export_row_source",
              c -> {
                UUID header = exportHeader(c, w, shape(v2), lock.get(), 2, 2, 30);
                exportRows(c, w, shape(v2), header, "r.line", "r.worked_minutes", "");
                exec(
                    c,
                    "INSERT INTO payroll.input_export_row SELECT tenant_id, export_id, 3, 9,"
                        + " employee_id, employment_id, legal_entity_id, site_id, cost_center_id,"
                        + " work_date, approved_worked_minutes, timesheet_id, timesheet_revision,"
                        + " approval_action_id, approval_cycle, source_week_start,"
                        + " source_generation_id, source_generation_version,"
                        + " source_lock_action_id, source_lock_version, payroll_input_run_id,"
                        + " payroll_input_run_version FROM payroll.input_export_row WHERE"
                        + " export_id = ? AND line = 1",
                    header);
              });
          assertThat(count(db, "SELECT count(*) FROM payroll.input_export")).isZero();
          AtomicReference<UUID> export = new AtomicReference<>();
          committed(
              db,
              c -> {
                UUID header = exportHeader(c, w, shape(v2), lock.get(), 2, 2, 30);
                exportRows(c, w, shape(v2), header, "r.line", "r.worked_minutes", "");
                export.set(header);
              });
          assertThat(count(db, "SELECT count(*) FROM payroll.input_export_row")).isEqualTo(2);
          // One logical export per locked run, ever.
          refused(
              db,
              "payroll_input_export_one",
              c -> {
                UUID header = exportHeader(c, w, shape(v2), lock.get(), 2, 2, 30);
                exportRows(c, w, shape(v2), header, "r.line", "r.worked_minutes", "");
              });

          // Append-only: no update, delete or truncate of any new evidence.
          String[][] tables = {
            {"input_run_supersession", "reason_code = reason_code"},
            {"input_run_lock", "locked_by = locked_by"},
            {"input_export", "created_by = created_by"},
            {"input_export_row", "line = line"}
          };
          for (String[] table : tables) {
            refused(
                db,
                "payroll_input_immutable",
                c -> exec(c, "UPDATE payroll." + table[0] + " SET " + table[1]));
            refused(db, "payroll_input_immutable", c -> exec(c, "DELETE FROM payroll." + table[0]));
            refused(
                db,
                "payroll_input_no_truncate",
                c -> exec(c, "TRUNCATE payroll." + table[0] + " CASCADE"));
          }
          assertThat(count(db, "SELECT count(*) FROM payroll.input_run_supersession")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM payroll.input_run_lock")).isEqualTo(2);
          assertThat(count(db, "SELECT count(*) FROM payroll.input_export")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM payroll.input_export_row")).isEqualTo(2);
        });
  }
}
