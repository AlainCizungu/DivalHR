package com.divalhr.core.people.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * MVP-070A V32 directly against PostgreSQL, bypassing the application (the database is the final
 * authority, T70A-8, T70A-12 items 12 and 16): the refusing rollback that restores V31 exactly;
 * append-only revisions and rows; the single permitted updates of a generation (a refresh) and a
 * timesheet (its next revision); contiguous revisions; totals exactly the rows' and a timesheet
 * showing its latest revision; the exact row shape with truncated minutes; the slot snapshot's
 * immutable instants; the week's shape; and the plans of the reads.
 */
@IntegrationTest
class TimesheetMigrationIntegrationTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final String KINSHASA = "Africa/Kinshasa";
  private static final Timestamp EARLY = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
  private static final Timestamp AT = Timestamp.from(Instant.parse("2026-11-08T11:00:00Z"));
  private static final String DIGEST = "a".repeat(64);

  private static final String ROW =
      "INSERT INTO people.timesheet_row (tenant_id, timesheet_id, revision, slot_id, schedule_id,"
          + " employee_id, employment_id, site_id, label_en, label_fr, scheduled_start_at,"
          + " scheduled_end_at, attendance_record_id, checked_in_at, checked_out_at,"
          + " correction_version, state, worked_minutes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Cashier',"
          + " 'Caissière', ?, ?, ?, ?, ?, ?, ?, ?)";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  /** A tenant with Ana covering one 08-16 shift on Monday, and her open attendance record. */
  private record World(
      UUID tenant,
      UUID site,
      UUID employee,
      UUID employment,
      UUID schedule,
      UUID slot,
      Timestamp start,
      Timestamp end,
      UUID record) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "timesheet32_" + UUID.randomUUID().toString().replace("-", "");
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

  private static int count(Db db, String sql, Object... args) {
    return Objects.requireNonNull(db.jdbc().queryForObject(sql, Integer.class, args));
  }

  private static Timestamp ts(LocalDateTime local) {
    return Timestamp.from(local.atZone(ZoneId.of(KINSHASA)).toInstant());
  }

  private static Timestamp plus(Timestamp at, Duration duration) {
    return Timestamp.from(at.toInstant().plus(duration));
  }

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

  private World world(Db db) {
    UUID tenant = UUID.randomUUID();
    UUID site = UUID.randomUUID();
    UUID employee = UUID.randomUUID();
    UUID employment = UUID.randomUUID();
    UUID schedule = UUID.randomUUID();
    UUID slot = UUID.randomUUID();
    UUID record = UUID.randomUUID();
    LocalDateTime start = MONDAY.atTime(8, 0);
    LocalDateTime end = MONDAY.atTime(16, 0);
    committed(
        db,
        c -> {
          UUID entity = UUID.randomUUID();
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
          UUID change = UUID.randomUUID();
          exec(
              c,
              "INSERT INTO people.employee (id, tenant_id, employee_number, given_names,"
                  + " family_name, search_key, created_at, created_by) VALUES (?, ?, 'E-ANA',"
                  + " 'Ana', 'Mbuyi', ' ana mbuyi ', now(), 'test')",
              employee,
              tenant);
          exec(
              c,
              "INSERT INTO people.employment (id, tenant_id, employee_id, effective_from,"
                  + " created_at, created_by) VALUES (?, ?, ?, ?, now(), 'test')",
              employment,
              tenant,
              employee,
              START);
          exec(
              c,
              "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id,"
                  + " type, effective_from, kinds, recorded_at, recorded_by, version_after)"
                  + " VALUES (?, ?, ?, ?, 'HIRE', ?, ARRAY['PLACEMENT'], now(), 'test', 0)",
              change,
              tenant,
              employee,
              employment,
              START);
          exec(
              c,
              "INSERT INTO people.employment_assignment (id, tenant_id, employee_id,"
                  + " employment_id, kind, effective_from, legal_entity_id, site_id,"
                  + " created_by_change_id, origin_change_id) VALUES (?, ?, ?, ?, 'PLACEMENT', ?,"
                  + " ?, ?, ?, ?)",
              UUID.randomUUID(),
              tenant,
              employee,
              employment,
              START,
              entity,
              site,
              change,
              change);
          exec(
              c,
              "INSERT INTO people.work_schedule (id, tenant_id, site_id, first_date, last_date,"
                  + " site_timezone, slot_count, assigned_count, published_at, published_by)"
                  + " VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?, 'test')",
              schedule,
              tenant,
              site,
              MONDAY,
              MONDAY,
              KINSHASA,
              EARLY);
          exec(
              c,
              "INSERT INTO people.work_schedule_slot (id, tenant_id, schedule_id, position,"
                  + " start_local, end_local, start_at, end_at, label_en, label_fr,"
                  + " assigned_employee_id) VALUES (?, ?, ?, 1, ?, ?, ?, ?, 'Cashier',"
                  + " 'Caissière', ?)",
              slot,
              tenant,
              schedule,
              start,
              end,
              ts(start),
              ts(end),
              employee);
          exec(
              c,
              "INSERT INTO people.work_schedule_site_coverage (tenant_id, schedule_id, site_id,"
                  + " first_date, last_date) VALUES (?, ?, ?, ?, ?)",
              tenant,
              schedule,
              site,
              MONDAY,
              MONDAY);
          exec(
              c,
              "INSERT INTO people.work_schedule_slot_coverage (tenant_id, slot_id, schedule_id,"
                  + " employee_id, start_at, end_at) VALUES (?, ?, ?, ?, ?, ?)",
              tenant,
              slot,
              schedule,
              employee,
              ts(start),
              ts(end));
          UUID event = UUID.randomUUID();
          exec(
              c,
              "INSERT INTO people.attendance_event (tenant_id, id, record_id, schedule_id,"
                  + " slot_id, employee_id, employment_id, event_type, recorded_at, recorded_by,"
                  + " idempotency_digest, recording_method) VALUES (?, ?, ?, ?, ?, ?, ?,"
                  + " 'CHECKED_IN', ?, 'test', ?, 'SELF')",
              tenant,
              event,
              record,
              schedule,
              slot,
              employee,
              employment,
              ts(start),
              DIGEST);
          exec(
              c,
              "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id,"
                  + " employee_id, employment_id, slot_start_at, slot_end_at, check_out_until,"
                  + " check_in_event_id, checked_in_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              tenant,
              record,
              schedule,
              slot,
              employee,
              employment,
              ts(start),
              ts(end),
              plus(ts(end), Duration.ofHours(24)),
              event,
              ts(start));
        });
    return new World(
        tenant, site, employee, employment, schedule, slot, ts(start), ts(end), record);
  }

  /** The week's generation at version 1. */
  private static void generation(Connection c, World w, UUID id, LocalDate monday)
      throws SQLException {
    exec(
        c,
        "INSERT INTO people.timesheet_generation (tenant_id, id, site_id, week_start, week_end,"
            + " site_timezone, period_start_at, period_end_at, version, status, generated_at,"
            + " generated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'DRAFT', ?, 'admin')",
        w.tenant(),
        id,
        w.site(),
        monday,
        monday.plusDays(7),
        KINSHASA,
        ts(monday.atStartOfDay()),
        ts(monday.plusDays(7).atStartOfDay()),
        AT);
  }

  /** A row's attendance: record, effective instants, correction version, state and minutes. */
  private record Att(
      UUID record,
      Timestamp in,
      Timestamp out,
      Integer correction,
      String state,
      Integer minutes) {}

  private Att ready(World w, Duration in, Duration out, int minutes) {
    return new Att(w.record(), plus(w.start(), in), plus(w.start(), out), 1, "READY", minutes);
  }

  private static final Att MISSING = new Att(null, null, null, null, "MISSING_ATTENDANCE", null);

  private static void row(Connection c, World w, UUID timesheet, int revision, Att a)
      throws SQLException {
    exec(
        c,
        ROW,
        w.tenant(),
        timesheet,
        revision,
        w.slot(),
        w.schedule(),
        w.employee(),
        w.employment(),
        w.site(),
        w.start(),
        w.end(),
        a.record(),
        a.in(),
        a.out(),
        a.correction(),
        a.state(),
        a.minutes());
  }

  /** Revision {@code revision}'s header with the totals of one row. */
  private static void revision(Connection c, World w, UUID timesheet, int revision, Att a)
      throws SQLException {
    boolean ready = "READY".equals(a.state());
    exec(
        c,
        "INSERT INTO people.timesheet_revision (tenant_id, timesheet_id, revision,"
            + " generation_version, source_digest, row_count, ready_rows, missing_rows,"
            + " open_rows, changed_rows, worked_minutes, created_at)"
            + " VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)",
        w.tenant(),
        timesheet,
        revision,
        revision,
        DIGEST,
        ready ? 1 : 0,
        "MISSING_ATTENDANCE".equals(a.state()) ? 1 : 0,
        "OPEN_ATTENDANCE".equals(a.state()) ? 1 : 0,
        "SOURCE_CHANGED".equals(a.state()) ? 1 : 0,
        ready ? a.minutes() : 0,
        AT);
  }

  /** A complete draft at revision 1 with one row. */
  private static void draft(Connection c, World w, UUID generation, UUID timesheet, Att a)
      throws SQLException {
    header(c, w, generation, timesheet, MONDAY, a);
    row(c, w, timesheet, 1, a);
  }

  /** A draft and its revision 1 header with one row's totals (the row still to insert). */
  private static void header(
      Connection c, World w, UUID generation, UUID timesheet, LocalDate monday, Att a)
      throws SQLException {
    boolean ready = "READY".equals(a.state());
    exec(
        c,
        "INSERT INTO people.timesheet (tenant_id, id, generation_id, employee_id, site_id,"
            + " week_start, status, current_revision, row_count, ready_rows, missing_rows,"
            + " open_rows, changed_rows, worked_minutes, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', 1, 1, ?, ?, ?, ?, ?, ?, ?)",
        w.tenant(),
        timesheet,
        generation,
        w.employee(),
        w.site(),
        monday,
        ready ? 1 : 0,
        "MISSING_ATTENDANCE".equals(a.state()) ? 1 : 0,
        "OPEN_ATTENDANCE".equals(a.state()) ? 1 : 0,
        "SOURCE_CHANGED".equals(a.state()) ? 1 : 0,
        ready ? a.minutes() : 0,
        AT,
        AT);
    revision(c, w, timesheet, 1, a);
  }

  /** The draft's guarded move to {@code revision} with one row's totals. */
  private static void advance(Connection c, World w, UUID timesheet, int revision, Att a)
      throws SQLException {
    boolean ready = "READY".equals(a.state());
    exec(
        c,
        "UPDATE people.timesheet SET current_revision = ?, row_count = 1, ready_rows = ?,"
            + " missing_rows = ?, open_rows = ?, changed_rows = ?, worked_minutes = ?,"
            + " updated_at = ? WHERE tenant_id = ? AND id = ?",
        revision,
        ready ? 1 : 0,
        "MISSING_ATTENDANCE".equals(a.state()) ? 1 : 0,
        "OPEN_ATTENDANCE".equals(a.state()) ? 1 : 0,
        "SOURCE_CHANGED".equals(a.state()) ? 1 : 0,
        ready ? a.minutes() : 0,
        AT,
        w.tenant(),
        timesheet);
  }

  // ------------------------------------------------------------------------------------------
  // Rollback and upgrade
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV31ExactlyAndRefusesTimesheetEvidence() {
    withDatabase(
        db -> {
          migrate(db.url(), "31");
          World w = world(db);
          String v31 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          migrate(db.url(), "32");
          String v32 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          assertThat(v32).isNotEqualTo(v31).contains("timesheet_row").contains("timesheet_current");
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V32__rollback.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v31);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.attendance_record")).isEqualTo(1);

          db.jdbc().execute(resource("db/migration/V32__timesheet.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v32);
          // Once one generation exists, the rollback refuses and changes nothing.
          committed(db, c -> generation(c, w, UUID.randomUUID(), MONDAY));
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V32__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V32 rollback refused: timesheet evidence exists");
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v32);
          assertThat(count(db, "SELECT count(*) FROM people.timesheet_generation")).isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Append-only evidence and the single permitted updates
  // ------------------------------------------------------------------------------------------

  @Test
  void revisionsAndRowsAreAppendOnlyAndOnlyTheNextRevisionOrRefreshIsAccepted() {
    withDatabase(
        db -> {
          migrate(db.url(), "32");
          World w = world(db);
          UUID g = UUID.randomUUID();
          UUID t = UUID.randomUUID();
          committed(
              db,
              c -> {
                generation(c, w, g, MONDAY);
                draft(c, w, g, t, MISSING);
              });

          // Evidence is never changed, deleted or truncated.
          refused(
              db,
              "timesheet_row_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_row SET label_en = 'X' WHERE tenant_id = ?",
                      w.tenant()));
          refused(
              db,
              "timesheet_row_immutable",
              c -> exec(c, "DELETE FROM people.timesheet_row WHERE tenant_id = ?", w.tenant()));
          refused(
              db,
              "timesheet_revision_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_revision SET source_digest = ? WHERE tenant_id = ?",
                      "b".repeat(64),
                      w.tenant()));
          refused(
              db,
              "timesheet_immutable",
              c -> exec(c, "DELETE FROM people.timesheet WHERE tenant_id = ?", w.tenant()));
          refused(
              db,
              "timesheet_generation_immutable",
              c ->
                  exec(
                      c,
                      "DELETE FROM people.timesheet_generation WHERE tenant_id = ?",
                      w.tenant()));
          for (String table :
              List.of("timesheet_generation", "timesheet", "timesheet_revision", "timesheet_row")) {
            refused(
                db, table + "_no_truncate", c -> exec(c, "TRUNCATE people." + table + " CASCADE"));
          }

          // A generation changes only by a refresh: version + 1 with its instant and subject.
          refused(
              db,
              "timesheet_generation_guard",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_generation SET version = 3, refreshed_at = ?,"
                          + " refreshed_by = 'admin' WHERE id = ?",
                      AT,
                      g));
          refused(
              db,
              "timesheet_generation_guard",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_generation SET version = 2, refreshed_at = ?,"
                          + " refreshed_by = 'admin', site_timezone = 'Africa/Lubumbashi'"
                          + " WHERE id = ?",
                      AT,
                      g));
          refused(
              db,
              "timesheet_generation_guard",
              c ->
                  exec(
                      c,
                      "INSERT INTO people.timesheet_generation (tenant_id, id, site_id,"
                          + " week_start, week_end, site_timezone, period_start_at,"
                          + " period_end_at, version, status, generated_at, generated_by,"
                          + " refreshed_at, refreshed_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 2,"
                          + " 'DRAFT', ?, 'admin', ?, 'admin')",
                      w.tenant(),
                      UUID.randomUUID(),
                      w.site(),
                      MONDAY.plusDays(7),
                      MONDAY.plusDays(14),
                      KINSHASA,
                      ts(MONDAY.plusDays(7).atStartOfDay()),
                      ts(MONDAY.plusDays(14).atStartOfDay()),
                      AT,
                      AT));
          committed(
              db,
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_generation SET version = 2, refreshed_at = ?,"
                          + " refreshed_by = 'admin' WHERE id = ?",
                      AT,
                      g));

          // Revisions follow the latest one; the timesheet moves to exactly its next revision.
          Att ready = ready(w, Duration.ZERO, Duration.ofHours(8), 480);
          refused(db, "timesheet_revision_sequence", c -> revision(c, w, t, 3, ready));
          refused(
              db,
              "timesheet_guard",
              c -> {
                revision(c, w, t, 2, ready);
                row(c, w, t, 2, ready);
                exec(c, "UPDATE people.timesheet SET current_revision = 3 WHERE id = ?", t);
              });
          // A new revision without moving the timesheet to it: it would not show its latest.
          refused(
              db,
              "timesheet_revision_exact",
              c -> {
                revision(c, w, t, 2, ready);
                row(c, w, t, 2, ready);
              });
          // Totals other than the current revision's.
          refused(
              db,
              "timesheet_current",
              c -> {
                revision(c, w, t, 2, ready);
                row(c, w, t, 2, ready);
                advance(c, w, t, 2, ready(w, Duration.ZERO, Duration.ofHours(8), 479));
              });
          // A header whose totals differ from its rows.
          refused(
              db,
              "timesheet_revision_exact",
              c -> {
                revision(c, w, t, 2, ready);
                row(c, w, t, 2, MISSING);
                advance(c, w, t, 2, ready);
              });
          committed(
              db,
              c -> {
                revision(c, w, t, 2, ready);
                row(c, w, t, 2, ready);
                advance(c, w, t, 2, ready);
              });
          assertThat(
                  count(db, "SELECT count(*) FROM people.timesheet_row WHERE timesheet_id = ?", t))
              .isEqualTo(2);
          assertThat(count(db, "SELECT current_revision FROM people.timesheet WHERE id = ?", t))
              .isEqualTo(2);
        });
  }

  // ------------------------------------------------------------------------------------------
  // The exact row and week shapes
  // ------------------------------------------------------------------------------------------

  @Test
  void aRowHasItsExactShapeTruncatedMinutesAndTheSlotsInstants() {
    withDatabase(
        db -> {
          migrate(db.url(), "32");
          World w = world(db);
          UUID g = UUID.randomUUID();
          committed(db, c -> generation(c, w, g, MONDAY));

          // 7 h 59 min 59.999999 s: 479 minutes (truncated, never rounded).
          Duration almost = Duration.ofHours(8).minusNanos(1000);
          committed(
              db, c -> draft(c, w, g, UUID.randomUUID(), ready(w, Duration.ZERO, almost, 479)));
          for (Att bad :
              List.of(
                  ready(w, Duration.ZERO, almost, 480),
                  ready(w, Duration.ofHours(8), Duration.ofHours(8), 0),
                  new Att(w.record(), w.start(), null, 0, "READY", null),
                  new Att(w.record(), w.start(), w.end(), 0, "OPEN_ATTENDANCE", null),
                  new Att(w.record(), w.start(), null, 0, "OPEN_ATTENDANCE", 1),
                  new Att(w.record(), null, null, null, "MISSING_ATTENDANCE", null),
                  new Att(null, null, null, 0, "SOURCE_CHANGED", null),
                  new Att(null, null, null, null, "SOURCE_CHANGED", 0))) {
            refused(db, "timesheet_row_shape", c -> row(c, w, UUID.randomUUID(), 1, bad));
          }
          refused(
              db,
              "timesheet_row_state",
              c -> row(c, w, UUID.randomUUID(), 1, new Att(null, null, null, null, "LATE", null)));
          // The slot's snapshot is its immutable instants.
          refused(
              db,
              "timesheet_row_slot",
              c -> {
                UUID g2 = UUID.randomUUID();
                UUID t = UUID.randomUUID();
                generation(c, w, g2, MONDAY.plusDays(7));
                header(c, w, g2, t, MONDAY.plusDays(7), MISSING);
                exec(
                    c,
                    ROW,
                    w.tenant(),
                    t,
                    1,
                    w.slot(),
                    w.schedule(),
                    w.employee(),
                    w.employment(),
                    w.site(),
                    w.start(),
                    plus(w.end(), Duration.ofMinutes(1)),
                    null,
                    null,
                    null,
                    null,
                    "MISSING_ATTENDANCE",
                    null);
              });

          // A week is a Monday and seven local days.
          refused(
              db,
              "timesheet_generation_week",
              c -> generation(c, w, UUID.randomUUID(), MONDAY.plusDays(8)));
          refused(
              db,
              "timesheet_generation_period",
              c ->
                  exec(
                      c,
                      "INSERT INTO people.timesheet_generation (tenant_id, id, site_id,"
                          + " week_start, week_end, site_timezone, period_start_at,"
                          + " period_end_at, version, status, generated_at, generated_by)"
                          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'DRAFT', ?, 'admin')",
                      w.tenant(),
                      UUID.randomUUID(),
                      w.site(),
                      MONDAY.plusDays(7),
                      MONDAY.plusDays(14),
                      KINSHASA,
                      ts(MONDAY.plusDays(7).atStartOfDay()),
                      ts(MONDAY.plusDays(15).atStartOfDay()),
                      AT));
          refused(
              db,
              "timesheet_generation_site_week_unique",
              c -> generation(c, w, UUID.randomUUID(), MONDAY));
          // A foreign tenant's site is not a key.
          refused(
              db,
              "timesheet_generation_site",
              c ->
                  exec(
                      c,
                      "INSERT INTO people.timesheet_generation (tenant_id, id, site_id,"
                          + " week_start, week_end, site_timezone, period_start_at,"
                          + " period_end_at, version, status, generated_at, generated_by)"
                          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'DRAFT', ?, 'admin')",
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      w.site(),
                      MONDAY.plusDays(7),
                      MONDAY.plusDays(14),
                      KINSHASA,
                      ts(MONDAY.plusDays(7).atStartOfDay()),
                      ts(MONDAY.plusDays(14).atStartOfDay()),
                      AT));
          assertThat(count(db, "SELECT count(*) FROM people.timesheet_row")).isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Query plans
  // ------------------------------------------------------------------------------------------

  @Test
  void theReadsUseTheirKeys() {
    withDatabase(
        db -> {
          migrate(db.url(), "32");
          UUID tenant = UUID.fromString("00000000-0000-4000-8000-000000000007");
          UUID id = UUID.randomUUID();
          List<String> plans =
              analyzedPlans(
                  db,
                  List.of(
                      // A site's week.
                      "SELECT g.id FROM people.timesheet_generation g WHERE g.tenant_id = ?"
                          + " AND g.site_id = ? AND g.week_start = ?",
                      // A generation's drafts.
                      "SELECT t.id FROM people.timesheet t WHERE t.tenant_id = ?"
                          + " AND t.generation_id = ? ORDER BY t.id",
                      // An employee's drafts, newest week first.
                      "SELECT t.id FROM people.timesheet t WHERE t.tenant_id = ?"
                          + " AND t.employee_id = ? ORDER BY t.week_start DESC, t.id DESC LIMIT 21",
                      // A timesheet's revisions.
                      "SELECT revision FROM people.timesheet_revision WHERE tenant_id = ?"
                          + " AND timesheet_id = ? ORDER BY revision DESC",
                      // A revision's rows.
                      "SELECT r.slot_id FROM people.timesheet_row r WHERE r.tenant_id = ?"
                          + " AND r.timesheet_id = ? AND r.revision = 1"
                          + " ORDER BY r.scheduled_start_at, r.slot_id"),
                  List.of(
                      new Object[] {tenant, id, MONDAY},
                      new Object[] {tenant, id},
                      new Object[] {tenant, id},
                      new Object[] {tenant, id},
                      new Object[] {tenant, id}));
          assertThat(plans.get(0)).contains("timesheet_generation_site_week_unique");
          assertThat(plans.get(1)).contains("timesheet_generation_member");
          assertThat(plans.get(2)).contains("timesheet_employee_week");
          assertThat(plans.get(3))
              .containsAnyOf("timesheet_revision_pkey", "timesheet_revision_totals_key");
          assertThat(plans.get(4)).contains("timesheet_row_pkey");
          for (String plan : plans) {
            assertThat(plan).doesNotContain("Seq Scan");
          }
        });
  }

  /**
   * The plans of {@code sqls} over 40,000 analyzed drafts of 20 tenants and 4,000 employees, one
   * revision and one row each (inserted with triggers and foreign keys set aside, then rolled
   * back).
   */
  private List<String> analyzedPlans(Db db, List<String> sqls, List<Object[]> args) {
    try (Connection c = connect(db)) {
      exec(c, "SET LOCAL session_replication_role = replica");
      exec(
          c,
          "INSERT INTO people.timesheet_generation (tenant_id, id, site_id, week_start, week_end,"
              + " site_timezone, period_start_at, period_end_at, version, status, generated_at,"
              + " generated_by) SELECT ('00000000-0000-4000-8000-' || lpad((g % 20)::text, 12,"
              + " '0'))::uuid, gen_random_uuid(), ('00000000-0000-4000-a000-'"
              + " || lpad((g / 100)::text, 12, '0'))::uuid, DATE '2024-01-01' + 7 * (g % 100),"
              + " DATE '2024-01-08' + 7 * (g % 100), 'Africa/Kinshasa', now(),"
              + " now() + interval '168 hours', 1, 'DRAFT', now(), 'test'"
              + " FROM generate_series(1, 20000) g");
      exec(
          c,
          "INSERT INTO people.timesheet (tenant_id, id, generation_id, employee_id, site_id,"
              + " week_start, status, current_revision, row_count, ready_rows, missing_rows,"
              + " open_rows, changed_rows, worked_minutes, created_at, updated_at)"
              + " SELECT g.tenant_id, gen_random_uuid(), g.id, e, g.site_id, g.week_start,"
              + " 'DRAFT', 1, 1, 0, 1, 0, 0, 0, now(), now() FROM people.timesheet_generation g"
              + " CROSS JOIN LATERAL (SELECT ('00000000-0000-4000-9000-'"
              + " || lpad(((hashtext(g.id::text) & 2047) * 2 + k)::text, 12, '0'))::uuid"
              + " AS e FROM generate_series(0, 1) k) emp");
      exec(
          c,
          "INSERT INTO people.timesheet_revision (tenant_id, timesheet_id, revision,"
              + " generation_version, source_digest, row_count, ready_rows, missing_rows,"
              + " open_rows, changed_rows, worked_minutes, created_at) SELECT tenant_id, id, 1,"
              + " 1, repeat('a', 64), 1, 0, 1, 0, 0, 0, now() FROM people.timesheet");
      exec(
          c,
          "INSERT INTO people.timesheet_row (tenant_id, timesheet_id, revision, slot_id,"
              + " schedule_id, employee_id, employment_id, site_id, label_en, label_fr,"
              + " scheduled_start_at, scheduled_end_at, state) SELECT tenant_id, id, 1,"
              + " gen_random_uuid(), gen_random_uuid(), employee_id, gen_random_uuid(), site_id,"
              + " 'Cashier', 'Caissière', week_start + time '08:00', week_start + time '16:00',"
              + " 'MISSING_ATTENDANCE' FROM people.timesheet");
      for (String table :
          List.of("timesheet_generation", "timesheet", "timesheet_revision", "timesheet_row")) {
        exec(c, "ANALYZE people." + table);
      }
      List<String> plans = new ArrayList<>();
      for (int i = 0; i < sqls.size(); i++) {
        StringBuilder plan = new StringBuilder();
        try (PreparedStatement explain = c.prepareStatement("EXPLAIN " + sqls.get(i))) {
          Object[] values = args.get(i);
          for (int k = 0; k < values.length; k++) {
            explain.setObject(k + 1, values[k]);
          }
          try (var rows = explain.executeQuery()) {
            while (rows.next()) {
              plan.append(rows.getString(1)).append('\n');
            }
          }
        }
        plans.add(plan.toString());
      }
      c.rollback();
      return plans;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
