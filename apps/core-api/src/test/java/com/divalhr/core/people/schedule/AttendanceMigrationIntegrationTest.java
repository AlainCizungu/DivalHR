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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
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
 * MVP-060A V28 directly against PostgreSQL, bypassing the application (the database is the final
 * authority, A60A-3, A60A-9 item 9, A60A-11): the refusing rollback that restores V27 exactly;
 * append-only events; the guarded projection inserted open and completed once, never moved,
 * reopened or deleted; the check-in guard (current coverage, operational schedule, window,
 * qualifying employment) and the check-out guard (own open record, order, window); one check-in and
 * one check-out per record; one actionable open record per employee; the deferred agreement of
 * evidence and projection; and the history's query plan.
 */
@IntegrationTest
class AttendanceMigrationIntegrationTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final String KINSHASA = "Africa/Kinshasa";
  private static final Timestamp EARLY = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
  private static final String DIGEST = "a".repeat(64);

  private static final String EVENT =
      "INSERT INTO people.attendance_event (tenant_id, id, record_id, schedule_id, slot_id,"
          + " employee_id, employment_id, event_type, recorded_at, recorded_by,"
          + " idempotency_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'test', ?)";

  private static final String RECORD =
      "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id, employee_id,"
          + " employment_id, slot_start_at, slot_end_at, check_out_until, check_in_event_id,"
          + " checked_in_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  /** An employee and their one employment. */
  private record Hired(UUID employee, UUID employment) {}

  /** A tenant with Ana (covering one 08-16 shift) and Ben; a second, later shift of Ana's. */
  private record World(
      UUID tenant,
      UUID site,
      Hired ana,
      Hired ben,
      UUID schedule,
      UUID slot,
      Timestamp start,
      Timestamp end,
      UUID laterSchedule,
      UUID laterSlot,
      Timestamp laterStart,
      Timestamp laterEnd) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "attend28_" + UUID.randomUUID().toString().replace("-", "");
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

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

  private static UUID organization(Connection c, UUID site) throws SQLException {
    UUID tenant = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone,"
            + " status, created_at, created_by) VALUES (?, 'Org', 'CD', 'fr', ?, 'ACTIVE', now(),"
            + " 'test')",
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
    return tenant;
  }

  /** An employee placed at the site from START. */
  private static Hired hire(Connection c, UUID tenant, UUID site, String number)
      throws SQLException {
    Hired h = new Hired(UUID.randomUUID(), UUID.randomUUID());
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
        site,
        change,
        change);
    return h;
  }

  /** A complete, operational one-slot schedule (evidence and coverage); its slot id. */
  private static UUID operational(
      Connection c,
      UUID tenant,
      UUID site,
      UUID schedule,
      LocalDateTime start,
      LocalDateTime end,
      UUID employee)
      throws SQLException {
    UUID slot = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.work_schedule (id, tenant_id, site_id, first_date, last_date,"
            + " site_timezone, slot_count, assigned_count, published_at, published_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?, 'test')",
        schedule,
        tenant,
        site,
        start.toLocalDate(),
        start.toLocalDate(),
        KINSHASA,
        EARLY);
    exec(
        c,
        "INSERT INTO people.work_schedule_slot (id, tenant_id, schedule_id, position,"
            + " start_local, end_local, start_at, end_at, label_en, label_fr,"
            + " assigned_employee_id) VALUES (?, ?, ?, 1, ?, ?, ?, ?, 'Cashier', 'Caissière', ?)",
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
        start.toLocalDate(),
        start.toLocalDate());
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
    return slot;
  }

  private World world(Db db) {
    UUID site = UUID.randomUUID();
    UUID schedule = UUID.randomUUID();
    UUID later = UUID.randomUUID();
    LocalDateTime start = DAY.atTime(8, 0);
    LocalDateTime end = DAY.atTime(16, 0);
    LocalDateTime laterStart = DAY.plusDays(3).atTime(8, 0);
    LocalDateTime laterEnd = DAY.plusDays(3).atTime(16, 0);
    World[] built = new World[1];
    committed(
        db,
        c -> {
          UUID tenant = organization(c, site);
          Hired ana = hire(c, tenant, site, "E-ANA");
          Hired ben = hire(c, tenant, site, "E-BEN");
          UUID slot = operational(c, tenant, site, schedule, start, end, ana.employee());
          UUID laterSlot =
              operational(c, tenant, site, later, laterStart, laterEnd, ana.employee());
          built[0] =
              new World(
                  tenant,
                  site,
                  ana,
                  ben,
                  schedule,
                  slot,
                  ts(start),
                  ts(end),
                  later,
                  laterSlot,
                  ts(laterStart),
                  ts(laterEnd));
        });
    return built[0];
  }

  private static Timestamp plus(Timestamp at, Duration duration) {
    return Timestamp.from(at.toInstant().plus(duration));
  }

  private static void event(
      Connection c,
      World w,
      UUID id,
      UUID record,
      UUID slot,
      UUID schedule,
      Hired who,
      String type,
      Timestamp at)
      throws SQLException {
    exec(
        c,
        EVENT,
        w.tenant(),
        id,
        record,
        schedule,
        slot,
        who.employee(),
        who.employment(),
        type,
        at,
        DIGEST);
  }

  private static void record(
      Connection c,
      World w,
      UUID id,
      UUID slot,
      UUID schedule,
      Hired who,
      Timestamp start,
      Timestamp end,
      UUID checkIn,
      Timestamp at)
      throws SQLException {
    exec(
        c,
        RECORD,
        w.tenant(),
        id,
        schedule,
        slot,
        who.employee(),
        who.employment(),
        start,
        end,
        plus(end, Duration.ofHours(24)),
        checkIn,
        at);
  }

  /** Ana's check-in on the first shift at {@code at}: the event, then the record. */
  private static void checkIn(Connection c, World w, UUID record, UUID event, Timestamp at)
      throws SQLException {
    event(c, w, event, record, w.slot(), w.schedule(), w.ana(), "CHECKED_IN", at);
    record(c, w, record, w.slot(), w.schedule(), w.ana(), w.start(), w.end(), event, at);
  }

  /** The check-out of a record at {@code at}: the event, then the single update. */
  private static void checkOut(Connection c, World w, UUID record, UUID event, Timestamp at)
      throws SQLException {
    event(c, w, event, record, w.slot(), w.schedule(), w.ana(), "CHECKED_OUT", at);
    exec(
        c,
        "UPDATE people.attendance_record SET check_out_event_id = ?, checked_out_at = ?"
            + " WHERE tenant_id = ? AND id = ?",
        event,
        at,
        w.tenant(),
        record);
  }

  private Timestamp in(World w) {
    return plus(w.start(), Duration.ofHours(-1));
  }

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV27ExactlyAndRefusesOnceEvidenceExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "27");
          String v27 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          World w = world(db);
          migrate(db.url(), "28");
          String v28 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          assertThat(v28)
              .isNotEqualTo(v27)
              .contains("attendance_event")
              .contains("attendance_record");
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V28__rollback.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v27);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);

          db.jdbc().execute(resource("db/migration/V28__attendance.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v28);
          committed(db, c -> checkIn(c, w, UUID.randomUUID(), UUID.randomUUID(), in(w)));
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V28__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V28 rollback refused: attendance evidence exists");
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v28);
          assertThat(count(db, "SELECT count(*) FROM people.attendance_record")).isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Evidence and projection
  // ------------------------------------------------------------------------------------------

  @Test
  void eventsAreAppendOnlyAndTheRecordIsCompletedOnceAndNeverMoved() {
    withDatabase(
        db -> {
          migrate(db.url(), "28");
          World w = world(db);
          UUID record = UUID.randomUUID();
          UUID in = UUID.randomUUID();
          committed(db, c -> checkIn(c, w, record, in, in(w)));
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT status FROM people.attendance_record WHERE id = ?",
                          String.class,
                          record))
              .isEqualTo("OPEN");

          refused(
              db,
              "attendance_event_immutable",
              c -> exec(c, "UPDATE people.attendance_event SET recorded_by = 'x'"));
          refused(
              db,
              "attendance_event_immutable",
              c -> exec(c, "DELETE FROM people.attendance_event"));
          refused(
              db,
              "attendance_event_no_truncate",
              c -> exec(c, "TRUNCATE people.attendance_event CASCADE"));
          refused(
              db,
              "attendance_record_immutable",
              c -> exec(c, "DELETE FROM people.attendance_record"));
          refused(
              db,
              "attendance_record_no_truncate",
              c -> exec(c, "TRUNCATE people.attendance_record CASCADE"));
          // Never moved to another employee, slot or instant, never reopened without a check-out.
          refused(
              db,
              "attendance_record_guard",
              c ->
                  exec(
                      c,
                      "UPDATE people.attendance_record SET employee_id = ?",
                      w.ben().employee()));
          refused(
              db,
              "attendance_record_guard",
              c -> exec(c, "UPDATE people.attendance_record SET checked_in_at = checked_in_at"));
          // Inserted open only.
          refused(
              db,
              "attendance_record_guard",
              c -> {
                UUID other = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                event(
                    c,
                    w,
                    event,
                    other,
                    w.laterSlot(),
                    w.laterSchedule(),
                    w.ana(),
                    "CHECKED_IN",
                    plus(w.laterStart(), Duration.ofHours(-1)));
                exec(
                    c,
                    "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id,"
                        + " employee_id, employment_id, slot_start_at, slot_end_at,"
                        + " check_out_until, check_in_event_id, checked_in_at,"
                        + " check_out_event_id, checked_out_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?)",
                    w.tenant(),
                    other,
                    w.laterSchedule(),
                    w.laterSlot(),
                    w.ana().employee(),
                    w.ana().employment(),
                    w.laterStart(),
                    w.laterEnd(),
                    plus(w.laterEnd(), Duration.ofHours(24)),
                    event,
                    plus(w.laterStart(), Duration.ofHours(-1)),
                    event,
                    w.laterEnd());
              });

          // One check-in per record.
          refused(
              db,
              "attendance_event_record_type_unique",
              c ->
                  event(
                      c,
                      w,
                      UUID.randomUUID(),
                      record,
                      w.slot(),
                      w.schedule(),
                      w.ana(),
                      "CHECKED_IN",
                      in(w)));
          // A check-out event without the record's update fails at commit.
          refused(
              db,
              "attendance_exact",
              c ->
                  event(
                      c,
                      w,
                      UUID.randomUUID(),
                      record,
                      w.slot(),
                      w.schedule(),
                      w.ana(),
                      "CHECKED_OUT",
                      w.end()));
          // A record's check-out must reference its own CHECKED_OUT event.
          refused(
              db,
              "attendance_record_check_out",
              c ->
                  exec(
                      c,
                      "UPDATE people.attendance_record SET check_out_event_id = ?,"
                          + " checked_out_at = ? WHERE id = ?",
                      in,
                      w.end(),
                      record));

          UUID out = UUID.randomUUID();
          committed(db, c -> checkOut(c, w, record, out, w.end()));
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT status FROM people.attendance_record WHERE id = ?",
                          String.class,
                          record))
              .isEqualTo("COMPLETED");
          // Completed once: never a second check-out or a reopening.
          refused(
              db,
              "attendance_event_check_out",
              c ->
                  event(
                      c,
                      w,
                      UUID.randomUUID(),
                      record,
                      w.slot(),
                      w.schedule(),
                      w.ana(),
                      "CHECKED_OUT",
                      plus(w.end(), Duration.ofMinutes(5))));
          refused(
              db,
              "attendance_record_guard",
              c ->
                  exec(
                      c,
                      "UPDATE people.attendance_record SET check_out_event_id = NULL,"
                          + " checked_out_at = NULL WHERE id = ?",
                      record));
          assertThat(count(db, "SELECT count(*) FROM people.attendance_event")).isEqualTo(2);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Guards
  // ------------------------------------------------------------------------------------------

  @Test
  void aCheckInNeedsTheCurrentCoverageTheWindowAndAQualifyingEmployment() {
    withDatabase(
        db -> {
          migrate(db.url(), "28");
          World w = world(db);
          // Another employee than the slot's coverage.
          refused(
              db,
              "attendance_event_check_in",
              c -> {
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                event(c, w, event, record, w.slot(), w.schedule(), w.ben(), "CHECKED_IN", in(w));
              });
          // A slot whose coverage was released (as by a cancellation).
          refused(
              db,
              "attendance_event_check_in",
              c -> {
                exec(c, "ALTER TABLE people.work_schedule_slot_coverage DISABLE TRIGGER USER");
                exec(
                    c,
                    "DELETE FROM people.work_schedule_slot_coverage WHERE slot_id = ?",
                    w.slot());
                event(
                    c,
                    w,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    w.slot(),
                    w.schedule(),
                    w.ana(),
                    "CHECKED_IN",
                    in(w));
              });
          // Outside the window: before start - 2 h, or at the end.
          refused(
              db,
              "attendance_event_check_in_window",
              c ->
                  event(
                      c,
                      w,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      w.slot(),
                      w.schedule(),
                      w.ana(),
                      "CHECKED_IN",
                      plus(w.start(), Duration.ofHours(-2).minusNanos(1000))));
          refused(
              db,
              "attendance_event_check_in_window",
              c ->
                  event(
                      c,
                      w,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      w.slot(),
                      w.schedule(),
                      w.ana(),
                      "CHECKED_IN",
                      w.end()));
          // An employment that no longer covers the shift.
          refused(
              db,
              "attendance_event_employment_qualifies",
              c -> {
                exec(c, "ALTER TABLE people.employment DISABLE TRIGGER USER");
                exec(
                    c,
                    "UPDATE people.employment SET effective_to = ? WHERE id = ?",
                    DAY.minusDays(1),
                    w.ana().employment());
                event(
                    c,
                    w,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    w.slot(),
                    w.schedule(),
                    w.ana(),
                    "CHECKED_IN",
                    in(w));
              });
          // The record's window and deadline mirror the guard; the event needs its record.
          refused(
              db,
              "attendance_event_record",
              c ->
                  event(
                      c,
                      w,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      w.slot(),
                      w.schedule(),
                      w.ana(),
                      "CHECKED_IN",
                      in(w)));
          refused(
              db,
              "attendance_record_check_out_until",
              c -> {
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                event(c, w, event, record, w.slot(), w.schedule(), w.ana(), "CHECKED_IN", in(w));
                exec(
                    c,
                    RECORD,
                    w.tenant(),
                    record,
                    w.schedule(),
                    w.slot(),
                    w.ana().employee(),
                    w.ana().employment(),
                    w.start(),
                    w.end(),
                    plus(w.end(), Duration.ofHours(48)),
                    event,
                    in(w));
              });
          assertThat(count(db, "SELECT count(*) FROM people.attendance_event")).isZero();
        });
  }

  @Test
  void aCheckOutCompletesItsOwnOpenRecordInsideTheWindow() {
    withDatabase(
        db -> {
          migrate(db.url(), "28");
          World w = world(db);
          UUID record = UUID.randomUUID();
          committed(db, c -> checkIn(c, w, record, UUID.randomUUID(), in(w)));
          // Not strictly after the check-in.
          refused(
              db,
              "attendance_event_check_out",
              c -> checkOut(c, w, record, UUID.randomUUID(), in(w)));
          // After the scheduled end + 24 h.
          refused(
              db,
              "attendance_event_check_out",
              c ->
                  checkOut(
                      c,
                      w,
                      record,
                      UUID.randomUUID(),
                      plus(w.end(), Duration.ofHours(24).plusNanos(1000))));
          // Another employee's event on the record.
          refused(
              db,
              "attendance_event_check_out",
              c ->
                  event(
                      c,
                      w,
                      UUID.randomUUID(),
                      record,
                      w.slot(),
                      w.schedule(),
                      w.ben(),
                      "CHECKED_OUT",
                      w.end()));
          committed(
              db,
              c -> checkOut(c, w, record, UUID.randomUUID(), plus(w.end(), Duration.ofHours(24))));
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.attendance_record WHERE status = 'COMPLETED'"))
              .isEqualTo(1);
        });
  }

  @Test
  void anEmployeeHasAtMostOneActionableOpenRecord() {
    withDatabase(
        db -> {
          migrate(db.url(), "28");
          World w = world(db);
          // A third shift of Ana's the next morning, inside the first one's check-out window.
          UUID extraSchedule = UUID.randomUUID();
          UUID[] extra = new UUID[1];
          committed(
              db,
              c ->
                  extra[0] =
                      operational(
                          c,
                          w.tenant(),
                          w.site(),
                          extraSchedule,
                          DAY.plusDays(1).atTime(8, 0),
                          DAY.plusDays(1).atTime(12, 0),
                          w.ana().employee()));
          committed(db, c -> checkIn(c, w, UUID.randomUUID(), UUID.randomUUID(), in(w)));
          Timestamp extraStart = ts(DAY.plusDays(1).atTime(8, 0));
          Timestamp extraEnd = ts(DAY.plusDays(1).atTime(12, 0));
          // While the first is open within its check-out window: refused.
          refused(
              db,
              "attendance_record_one_open",
              c -> {
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                Timestamp at = plus(extraStart, Duration.ofMinutes(-30));
                event(c, w, event, record, extra[0], extraSchedule, w.ana(), "CHECKED_IN", at);
                record(
                    c,
                    w,
                    record,
                    extra[0],
                    extraSchedule,
                    w.ana(),
                    extraStart,
                    extraEnd,
                    event,
                    at);
              });
          // After the first's window closed (end + 24 h), a later shift's check-in is accepted
          // and the missed record stays open as evidence.
          committed(
              db,
              c -> {
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                Timestamp at = plus(w.laterStart(), Duration.ofHours(-1));
                event(
                    c,
                    w,
                    event,
                    record,
                    w.laterSlot(),
                    w.laterSchedule(),
                    w.ana(),
                    "CHECKED_IN",
                    at);
                record(
                    c,
                    w,
                    record,
                    w.laterSlot(),
                    w.laterSchedule(),
                    w.ana(),
                    w.laterStart(),
                    w.laterEnd(),
                    event,
                    at);
              });
          assertThat(
                  count(db, "SELECT count(*) FROM people.attendance_record WHERE status = 'OPEN'"))
              .isEqualTo(2);
        });
  }

  @Test
  void theOpenRangeIncludesTheCheckOutDeadline() {
    withDatabase(
        db -> {
          migrate(db.url(), "28");
          World w = world(db);
          // A shift of Ana's whose check-in window (15:00 to 21:00) contains the first shift's
          // check-out deadline (the next day at 16:00).
          UUID boundarySchedule = UUID.randomUUID();
          UUID[] boundary = new UUID[1];
          committed(
              db,
              c ->
                  boundary[0] =
                      operational(
                          c,
                          w.tenant(),
                          w.site(),
                          boundarySchedule,
                          DAY.plusDays(1).atTime(17, 0),
                          DAY.plusDays(1).atTime(21, 0),
                          w.ana().employee()));
          Timestamp boundaryStart = ts(DAY.plusDays(1).atTime(17, 0));
          Timestamp boundaryEnd = ts(DAY.plusDays(1).atTime(21, 0));
          UUID first = UUID.randomUUID();
          committed(db, c -> checkIn(c, w, first, UUID.randomUUID(), in(w)));
          Timestamp until = plus(w.end(), Duration.ofHours(24));
          Function<Timestamp, SqlWork> checkInAt =
              at ->
                  c -> {
                    UUID record = UUID.randomUUID();
                    UUID event = UUID.randomUUID();
                    event(
                        c,
                        w,
                        event,
                        record,
                        boundary[0],
                        boundarySchedule,
                        w.ana(),
                        "CHECKED_IN",
                        at);
                    record(
                        c,
                        w,
                        record,
                        boundary[0],
                        boundarySchedule,
                        w.ana(),
                        boundaryStart,
                        boundaryEnd,
                        event,
                        at);
                  };
          SqlWork atDeadline = checkInAt.apply(until);
          // Exactly at the first record's deadline it can still be checked out: a second open
          // record is refused.
          refused(db, "attendance_record_one_open", atDeadline);
          // One microsecond later the first record's window has closed: accepted (rolled back
          // after the deferred checks, so the first record can still be completed below).
          try (Connection c = connect(db)) {
            checkInAt.apply(plus(until, Duration.ofNanos(1_000))).run(c);
            exec(c, "SET CONSTRAINTS ALL IMMEDIATE");
            c.rollback();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          // Completing the first record at its deadline removes the conflict at that instant.
          committed(db, c -> checkOut(c, w, first, UUID.randomUUID(), until));
          committed(db, atDeadline);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.attendance_record WHERE status = 'COMPLETED'"
                          + " AND checked_out_at = ?",
                      until))
              .isEqualTo(1);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.attendance_record WHERE status = 'OPEN'"
                          + " AND checked_in_at = ?",
                      until))
              .isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // MVP-060B (V29): supervisor evidence
  // ------------------------------------------------------------------------------------------

  private static final String METHOD_EVENT =
      "INSERT INTO people.attendance_event (tenant_id, id, record_id, schedule_id, slot_id,"
          + " employee_id, employment_id, event_type, recorded_at, recorded_by,"
          + " idempotency_digest, recording_method) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'test', ?,"
          + " ?)";

  private static final String ACTION =
      "INSERT INTO people.attendance_supervisor_action (tenant_id, id, event_id, record_id,"
          + " schedule_id, slot_id, employee_id, employment_id, event_type, recorded_at,"
          + " manager_employee_id, manager_employment_id, reason_locale, reason, recorded_by,"
          + " idempotency_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'fr', ?, 'test', ?)";

  private static final String REASON = "Badgeuse en panne à l'entrée du site";

  /** An open-ended MANAGER line of the report's employment, from START. */
  private static void reportsTo(Connection c, UUID tenant, Hired report, Hired manager)
      throws SQLException {
    UUID change = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, timing, recorded_at, recorded_by, version_after) VALUES"
            + " (?, ?, ?, ?, 'CHANGE', ?, ARRAY['MANAGER'], 'SCHEDULED', now(), 'test', 1)",
        change,
        tenant,
        report.employee(),
        report.employment(),
        START);
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, manager_employee_id, created_by_change_id,"
            + " origin_change_id) VALUES (?, ?, ?, ?, 'MANAGER', ?, ?, ?, ?)",
        UUID.randomUUID(),
        tenant,
        report.employee(),
        report.employment(),
        START,
        manager.employee(),
        change,
        change);
  }

  /** One of Ana's events on {@code slot} with its recording method. */
  private static void methodEvent(
      Connection c,
      World w,
      UUID id,
      UUID record,
      UUID slot,
      UUID schedule,
      String type,
      Timestamp at,
      String method)
      throws SQLException {
    exec(
        c,
        METHOD_EVENT,
        w.tenant(),
        id,
        record,
        schedule,
        slot,
        w.ana().employee(),
        w.ana().employment(),
        type,
        at,
        DIGEST,
        method);
  }

  /** The supervisor action of one of Ana's events, by {@code manager}. */
  private static void action(
      Connection c,
      World w,
      UUID event,
      UUID record,
      UUID slot,
      UUID schedule,
      String type,
      Timestamp at,
      Hired manager,
      String reason)
      throws SQLException {
    exec(
        c,
        ACTION,
        w.tenant(),
        UUID.randomUUID(),
        event,
        record,
        schedule,
        slot,
        w.ana().employee(),
        w.ana().employment(),
        type,
        at,
        manager.employee(),
        manager.employment(),
        reason,
        DIGEST);
  }

  /** Ben checks Ana in on her first shift: the SUPERVISOR event, the record, the action. */
  private static void supervisedCheckIn(
      Connection c, World w, UUID record, UUID event, Timestamp at) throws SQLException {
    methodEvent(c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, "SUPERVISOR");
    record(c, w, record, w.slot(), w.schedule(), w.ana(), w.start(), w.end(), event, at);
    action(c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, w.ben(), REASON);
  }

  @Test
  void theV29RollbackRestoresV28ExactlyKeepsSelfAttendanceAndRefusesSupervisorEvidence() {
    withDatabase(
        db -> {
          migrate(db.url(), "28");
          World w = world(db);
          UUID first = UUID.randomUUID();
          committed(db, c -> checkIn(c, w, first, UUID.randomUUID(), in(w)));
          String v28 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          migrate(db.url(), "29");
          String v29 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          assertThat(v29).isNotEqualTo(v28).contains("attendance_supervisor_action");
          // Every V28 event is SELF; no default remains for later inserts.
          assertThat(
                  db.jdbc()
                      .queryForList(
                          "SELECT recording_method FROM people.attendance_event", String.class))
              .containsExactly("SELF");
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM information_schema.columns WHERE table_schema ="
                          + " 'people' AND table_name = 'attendance_event' AND column_name ="
                          + " 'recording_method' AND column_default IS NULL"))
              .isEqualTo(1);
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V29__rollback.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v28);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);
          // The SELF attendance survives the rollback unchanged.
          assertThat(count(db, "SELECT count(*) FROM people.attendance_event")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM people.attendance_record")).isEqualTo(1);

          db.jdbc().execute(resource("db/migration/V29__supervisor_attendance.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v29);
          // Once a manager has recorded one check-in, the rollback refuses and changes nothing.
          committed(
              db,
              c -> {
                UUID out = UUID.randomUUID();
                Timestamp outAt = plus(w.end(), Duration.ofHours(1));
                methodEvent(c, w, out, first, w.slot(), w.schedule(), "CHECKED_OUT", outAt, "SELF");
                exec(
                    c,
                    "UPDATE people.attendance_record SET check_out_event_id = ?, checked_out_at = ?"
                        + " WHERE tenant_id = ? AND id = ?",
                    out,
                    outAt,
                    w.tenant(),
                    first);
                reportsTo(c, w.tenant(), w.ana(), w.ben());
                UUID event = UUID.randomUUID();
                UUID record = UUID.randomUUID();
                Timestamp at = plus(w.laterStart(), Duration.ofHours(-1));
                methodEvent(
                    c,
                    w,
                    event,
                    record,
                    w.laterSlot(),
                    w.laterSchedule(),
                    "CHECKED_IN",
                    at,
                    "SUPERVISOR");
                record(
                    c,
                    w,
                    record,
                    w.laterSlot(),
                    w.laterSchedule(),
                    w.ana(),
                    w.laterStart(),
                    w.laterEnd(),
                    event,
                    at);
                action(
                    c,
                    w,
                    event,
                    record,
                    w.laterSlot(),
                    w.laterSchedule(),
                    "CHECKED_IN",
                    at,
                    w.ben(),
                    REASON);
              });
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V29__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V29 rollback refused: supervisor attendance evidence exists");
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v29);
          assertThat(count(db, "SELECT count(*) FROM people.attendance_supervisor_action"))
              .isEqualTo(1);
        });
  }

  @Test
  void supervisorEvidenceIsCompleteMatchingAuthorizedAndAppendOnly() {
    withDatabase(
        db -> {
          migrate(db.url(), "29");
          World w = world(db);
          UUID zoeEmployment = UUID.randomUUID();
          Hired[] zoe = new Hired[1];
          committed(
              db,
              c -> {
                reportsTo(c, w.tenant(), w.ana(), w.ben());
                zoe[0] = hire(c, w.tenant(), w.site(), "E-ZOE");
              });
          Timestamp at = in(w);
          Timestamp later = plus(w.laterStart(), Duration.ofHours(-1));

          // A SUPERVISOR event needs its one action; a SELF event has none.
          refused(
              db,
              "attendance_supervisor_exact",
              c -> {
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                methodEvent(
                    c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, "SUPERVISOR");
                record(
                    c, w, record, w.slot(), w.schedule(), w.ana(), w.start(), w.end(), event, at);
              });
          refused(
              db,
              "attendance_supervisor_action_event",
              c -> {
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                methodEvent(c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, "SELF");
                record(
                    c, w, record, w.slot(), w.schedule(), w.ana(), w.start(), w.end(), event, at);
                action(
                    c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, w.ben(), REASON);
              });
          // The action agrees with its event: another instant, slot or type is refused.
          refused(
              db,
              "attendance_supervisor_action_event",
              c -> {
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                methodEvent(
                    c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, "SUPERVISOR");
                record(
                    c, w, record, w.slot(), w.schedule(), w.ana(), w.start(), w.end(), event, at);
                action(
                    c,
                    w,
                    event,
                    record,
                    w.slot(),
                    w.schedule(),
                    "CHECKED_IN",
                    plus(at, Duration.ofSeconds(1)),
                    w.ben(),
                    REASON);
              });
          // Only the target employment's manager, with their own employment, never the target.
          refused(
              db,
              "attendance_supervisor_action_authority",
              c -> {
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                methodEvent(
                    c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, "SUPERVISOR");
                record(
                    c, w, record, w.slot(), w.schedule(), w.ana(), w.start(), w.end(), event, at);
                action(
                    c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, zoe[0], REASON);
              });
          // The authority guard already refuses a self-manager; with it set aside (inside the
          // rolled-back transaction) the CHECK still holds on its own.
          refused(
              db,
              "attendance_supervisor_action_not_self",
              c -> {
                exec(
                    c,
                    "ALTER TABLE people.attendance_supervisor_action"
                        + " DISABLE TRIGGER attendance_supervisor_action_guard");
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                methodEvent(
                    c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, "SUPERVISOR");
                record(
                    c, w, record, w.slot(), w.schedule(), w.ana(), w.start(), w.end(), event, at);
                action(
                    c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, w.ana(), REASON);
              });
          refused(
              db,
              "attendance_supervisor_action_manager_employment",
              c -> {
                // The guard checks the pair too; set aside, the foreign key still refuses it.
                exec(
                    c,
                    "ALTER TABLE people.attendance_supervisor_action"
                        + " DISABLE TRIGGER attendance_supervisor_action_guard");
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                methodEvent(
                    c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, "SUPERVISOR");
                record(
                    c, w, record, w.slot(), w.schedule(), w.ana(), w.start(), w.end(), event, at);
                action(
                    c,
                    w,
                    event,
                    record,
                    w.slot(),
                    w.schedule(),
                    "CHECKED_IN",
                    at,
                    new Hired(w.ben().employee(), w.ana().employment()),
                    REASON);
              });
          refused(
              db,
              "attendance_supervisor_action_reason_valid",
              c -> {
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                methodEvent(
                    c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, "SUPERVISOR");
                record(
                    c, w, record, w.slot(), w.schedule(), w.ana(), w.start(), w.end(), event, at);
                action(c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", at, w.ben(), "x");
              });

          // The complete check-in: the record is the target's, never the manager's.
          UUID record = UUID.randomUUID();
          UUID checkIn = UUID.randomUUID();
          committed(db, c -> supervisedCheckIn(c, w, record, checkIn, at));
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT employee_id FROM people.attendance_record WHERE id = ?",
                          UUID.class,
                          record))
              .isEqualTo(w.ana().employee());
          // One action per event.
          refused(
              db,
              "attendance_supervisor_action_event_unique",
              c ->
                  action(
                      c,
                      w,
                      checkIn,
                      record,
                      w.slot(),
                      w.schedule(),
                      "CHECKED_IN",
                      at,
                      w.ben(),
                      REASON));
          // Append-only: no update, delete or truncate of the action; the event's method is fixed.
          refused(
              db,
              "attendance_supervisor_action_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE people.attendance_supervisor_action SET reason = 'Autre motif ici'"
                          + " WHERE event_id = ?",
                      checkIn));
          refused(
              db,
              "attendance_supervisor_action_immutable",
              c ->
                  exec(
                      c,
                      "DELETE FROM people.attendance_supervisor_action WHERE event_id = ?",
                      checkIn));
          refused(
              db,
              "attendance_supervisor_action_no_truncate",
              c -> exec(c, "TRUNCATE people.attendance_supervisor_action CASCADE"));
          refused(
              db,
              "attendance_event_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE people.attendance_event SET recording_method = 'SELF' WHERE id = ?",
                      checkIn));
          // A supervised check-out completes the record; the record never names the manager.
          committed(
              db,
              c -> {
                UUID out = UUID.randomUUID();
                Timestamp when = plus(w.end(), Duration.ofHours(1));
                methodEvent(
                    c, w, out, record, w.slot(), w.schedule(), "CHECKED_OUT", when, "SUPERVISOR");
                action(
                    c,
                    w,
                    out,
                    record,
                    w.slot(),
                    w.schedule(),
                    "CHECKED_OUT",
                    when,
                    w.ben(),
                    REASON);
                exec(
                    c,
                    "UPDATE people.attendance_record SET check_out_event_id = ?, checked_out_at ="
                        + " ? WHERE tenant_id = ? AND id = ?",
                    out,
                    when,
                    w.tenant(),
                    record);
              });
          assertThat(count(db, "SELECT count(*) FROM people.attendance_supervisor_action"))
              .isEqualTo(2);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM information_schema.columns WHERE table_schema ="
                          + " 'people' AND table_name = 'attendance_record' AND (column_name"
                          + " LIKE '%manager%' OR column_name LIKE '%reason%')"))
              .isZero();
          assertThat(zoeEmployment).isNotNull();
          assertThat(later).isNotNull();
        });
  }

  // ------------------------------------------------------------------------------------------
  // MVP-061A (V30): offline provenance
  // ------------------------------------------------------------------------------------------

  private static final String OFFLINE_EVENT =
      "INSERT INTO people.attendance_event (tenant_id, id, record_id, schedule_id, slot_id,"
          + " employee_id, employment_id, event_type, recorded_at, recorded_by,"
          + " idempotency_digest, recording_method, client_event_id, device_offset_minutes,"
          + " received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'test', ?, ?, ?, ?, ?)";

  private static final String OFFLINE = "EMPLOYEE_OFFLINE_SYNC";

  /** One of Ana's events on {@code slot} with any method and provenance. */
  private static void provenanced(
      Connection c,
      World w,
      UUID id,
      UUID record,
      UUID slot,
      UUID schedule,
      String type,
      Timestamp at,
      String method,
      UUID client,
      Integer offset,
      Timestamp received)
      throws SQLException {
    exec(
        c,
        OFFLINE_EVENT,
        w.tenant(),
        id,
        record,
        schedule,
        slot,
        w.ana().employee(),
        w.ana().employment(),
        type,
        at,
        DIGEST,
        method,
        client,
        offset,
        received);
  }

  /** Ana's offline check-in on her first shift, claimed at {@code at}. */
  private static void offlineCheckIn(
      Connection c, World w, UUID record, UUID client, Timestamp at, Timestamp received)
      throws SQLException {
    UUID event = UUID.randomUUID();
    provenanced(
        c,
        w,
        event,
        record,
        w.slot(),
        w.schedule(),
        "CHECKED_IN",
        at,
        OFFLINE,
        client,
        60,
        received);
    record(c, w, record, w.slot(), w.schedule(), w.ana(), w.start(), w.end(), event, at);
  }

  /** Ana's offline check-out of {@code record} on her first shift, claimed at {@code at}. */
  private static void offlineCheckOut(
      Connection c, World w, UUID record, UUID client, Timestamp at, Timestamp received)
      throws SQLException {
    UUID event = UUID.randomUUID();
    provenanced(
        c,
        w,
        event,
        record,
        w.slot(),
        w.schedule(),
        "CHECKED_OUT",
        at,
        OFFLINE,
        client,
        60,
        received);
    exec(
        c,
        "UPDATE people.attendance_record SET check_out_event_id = ?, checked_out_at = ?"
            + " WHERE tenant_id = ? AND id = ?",
        event,
        at,
        w.tenant(),
        record);
  }

  @Test
  void theV30RollbackRestoresV29ExactlyKeepsOnlineAttendanceAndRefusesOfflineEvidence() {
    withDatabase(
        db -> {
          migrate(db.url(), "29");
          World w = world(db);
          committed(
              db,
              c -> {
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                methodEvent(
                    c, w, event, record, w.slot(), w.schedule(), "CHECKED_IN", in(w), "SELF");
                record(
                    c,
                    w,
                    record,
                    w.slot(),
                    w.schedule(),
                    w.ana(),
                    w.start(),
                    w.end(),
                    event,
                    in(w));
              });
          String v29 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          migrate(db.url(), "30");
          String v30 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          assertThat(v30)
              .isNotEqualTo(v29)
              .contains("client_event_id")
              .contains("attendance_event_offline_guard");
          // Existing evidence keeps its exact shape: no provenance.
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.attendance_event WHERE recording_method ="
                          + " 'SELF' AND client_event_id IS NULL AND device_offset_minutes IS NULL"
                          + " AND received_at IS NULL"))
              .isEqualTo(1);
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V30__rollback.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v29);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.attendance_event")).isEqualTo(1);

          db.jdbc().execute(resource("db/migration/V30__offline_attendance_sync.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v30);
          // Once one offline event exists, the rollback refuses and changes nothing.
          committed(
              db,
              c -> {
                UUID record = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                Timestamp at = plus(w.laterStart(), Duration.ofHours(-1));
                provenanced(
                    c,
                    w,
                    event,
                    record,
                    w.laterSlot(),
                    w.laterSchedule(),
                    "CHECKED_IN",
                    at,
                    OFFLINE,
                    UUID.randomUUID(),
                    60,
                    w.laterStart());
                record(
                    c,
                    w,
                    record,
                    w.laterSlot(),
                    w.laterSchedule(),
                    w.ana(),
                    w.laterStart(),
                    w.laterEnd(),
                    event,
                    at);
              });
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V30__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V30 rollback refused: offline attendance evidence exists");
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v30);
          assertThat(count(db, "SELECT count(*) FROM people.attendance_event")).isEqualTo(2);
        });
  }

  @Test
  void offlineEvidenceHasItsExactShapeBoundsIdentityAndImmutability() {
    withDatabase(
        db -> {
          migrate(db.url(), "30");
          World w = world(db);
          Timestamp at = in(w);
          Timestamp received = w.start();
          Duration micro = Duration.ofNanos(1_000);

          // Provenance exactly for EMPLOYEE_OFFLINE_SYNC; online and supervisor events keep their
          // V29 shape.
          for (Object[] shape :
              new Object[][] {
                {OFFLINE, null, 60, received},
                {OFFLINE, UUID.randomUUID(), null, received},
                {OFFLINE, UUID.randomUUID(), 60, null},
                {"SELF", UUID.randomUUID(), 60, received},
                {"SELF", null, null, received},
                {"SUPERVISOR", null, 60, null}
              }) {
            refused(
                db,
                "attendance_event_offline_shape",
                c ->
                    provenanced(
                        c,
                        w,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        w.slot(),
                        w.schedule(),
                        "CHECKED_IN",
                        at,
                        (String) shape[0],
                        (UUID) shape[1],
                        (Integer) shape[2],
                        (Timestamp) shape[3]));
          }
          refused(
              db,
              "attendance_event_recording_method",
              c ->
                  provenanced(
                      c,
                      w,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      w.slot(),
                      w.schedule(),
                      "CHECKED_IN",
                      at,
                      "OFFLINE",
                      null,
                      null,
                      null));
          for (int offset : new int[] {841, -841}) {
            refused(
                db,
                "attendance_event_offline_offset",
                c ->
                    provenanced(
                        c,
                        w,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        w.slot(),
                        w.schedule(),
                        "CHECKED_IN",
                        at,
                        OFFLINE,
                        UUID.randomUUID(),
                        offset,
                        received));
          }
          // The claimed time at most 5 minutes after the receipt; the receipt at most 72 hours
          // after the claimed time.
          refused(
              db,
              "attendance_event_offline_receipt",
              c ->
                  offlineCheckIn(
                      c,
                      w,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      at,
                      plus(at, Duration.ofMinutes(-5).minus(micro))));
          refused(
              db,
              "attendance_event_offline_receipt",
              c ->
                  offlineCheckIn(
                      c,
                      w,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      at,
                      plus(at, Duration.ofHours(72).plus(micro))));
          // The receipt at most 72 hours after the window closed (the guard fires before the
          // receipt check, so it is seen on its own).
          refused(
              db,
              "attendance_event_offline_guard",
              c ->
                  offlineCheckIn(
                      c,
                      w,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      plus(w.end(), micro.negated()),
                      plus(w.end(), Duration.ofHours(72).plus(micro))));
          // Every MVP-060A rule applies to the claimed time.
          refused(
              db,
              "attendance_event_check_in_window",
              c ->
                  offlineCheckIn(
                      c,
                      w,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      plus(w.start(), Duration.ofHours(-2).minus(micro)),
                      w.start()));

          UUID record = UUID.randomUUID();
          UUID client = UUID.randomUUID();
          committed(db, c -> offlineCheckIn(c, w, record, client, at, received));
          // One event per tenant, employee and client event.
          refused(
              db,
              "attendance_event_client_event_unique",
              c ->
                  offlineCheckOut(
                      c,
                      w,
                      record,
                      client,
                      plus(w.end(), Duration.ofHours(1)),
                      plus(w.end(), Duration.ofHours(1))));
          // A check-out strictly after the accepted check-in.
          refused(
              db,
              "attendance_event_check_out",
              c -> offlineCheckOut(c, w, record, UUID.randomUUID(), at, received));
          // Both instants and the provenance are immutable.
          for (String change :
              new String[] {
                "received_at = received_at + interval '1 second'",
                "recorded_at = recorded_at + interval '1 second'",
                "device_offset_minutes = 0",
                "client_event_id = gen_random_uuid()"
              }) {
            refused(
                db,
                "attendance_event_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE people.attendance_event SET "
                            + change
                            + " WHERE client_event_id = ?",
                        client));
          }
          // The check-out deadline and both 72-hour bounds are inclusive at once.
          Timestamp until = plus(w.end(), Duration.ofHours(24));
          committed(
              db,
              c ->
                  offlineCheckOut(
                      c, w, record, UUID.randomUUID(), until, plus(until, Duration.ofHours(72))));
          // Another tenant's employee may use the same client event ID.
          World other = world(db);
          committed(
              db,
              c -> {
                UUID theirs = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                Timestamp claimed = in(other);
                exec(
                    c,
                    OFFLINE_EVENT,
                    other.tenant(),
                    event,
                    theirs,
                    other.schedule(),
                    other.slot(),
                    other.ana().employee(),
                    other.ana().employment(),
                    "CHECKED_IN",
                    claimed,
                    DIGEST,
                    OFFLINE,
                    client,
                    -840,
                    other.start());
                record(
                    c,
                    other,
                    theirs,
                    other.slot(),
                    other.schedule(),
                    other.ana(),
                    other.start(),
                    other.end(),
                    event,
                    claimed);
              });
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.attendance_event WHERE client_event_id = ?",
                      client))
              .isEqualTo(2);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.attendance_record WHERE id = ?"
                          + " AND status = 'COMPLETED'",
                      record))
              .isEqualTo(1);
        });
  }

  @Test
  void theOfflineLookupsUseTheClientEventKeyAndTheExistingIndexes() {
    withDatabase(
        db -> {
          migrate(db.url(), "30");
          UUID tenant = UUID.fromString("00000000-0000-4000-8000-000000000007");
          UUID employee = UUID.fromString("00000000-0000-4000-9000-000000000042");
          UUID id = UUID.randomUUID();
          Timestamp now = Timestamp.from(Instant.parse("2026-06-02T08:00:00Z"));
          List<String> plans =
              analyzedPlans(
                  db,
                  List.of(
                      // The replay lookup.
                      "SELECT e.id FROM people.attendance_event e JOIN people.attendance_record r"
                          + " ON r.tenant_id = e.tenant_id AND r.id = e.record_id"
                          + " WHERE e.tenant_id = ? AND e.client_event_id = ?"
                          + " AND e.employee_id = ?",
                      // A slot's records (the check-out).
                      "SELECT a.id FROM people.attendance_record a WHERE a.tenant_id = ?"
                          + " AND a.slot_id = ? ORDER BY a.employee_id",
                      // The employee's overlapping open records (the check-in).
                      "SELECT id FROM people.attendance_record WHERE tenant_id = ?"
                          + " AND employee_id = ? AND check_out_event_id IS NULL"
                          + " AND checked_in_at <= ? AND check_out_until >= ? ORDER BY id"
                          + " FOR UPDATE"),
                  List.of(
                      new Object[] {tenant, id, employee},
                      new Object[] {tenant, id},
                      new Object[] {tenant, employee, now, now}));
          assertThat(plans.get(0)).contains("attendance_event_client_event_unique");
          assertThat(plans.get(1)).contains("attendance_record_slot_employee_unique");
          assertThat(plans.get(2)).containsAnyOf("attendance_record_history", "one_open");
          for (String plan : plans) {
            assertThat(plan).doesNotContain("Seq Scan");
          }
        });
  }

  /**
   * The plans of {@code sqls} over 40,000 analyzed records and offline events of 20 tenants and
   * 4,000 employees (inserted with triggers and foreign keys set aside, then rolled back).
   */
  private List<String> analyzedPlans(Db db, List<String> sqls, List<Object[]> args) {
    try (Connection c = connect(db)) {
      exec(c, "SET LOCAL session_replication_role = replica");
      exec(
          c,
          "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id,"
              + " employee_id, employment_id, slot_start_at, slot_end_at, check_out_until,"
              + " check_in_event_id, checked_in_at, check_out_event_id, checked_out_at)"
              + " SELECT t, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), e,"
              + " gen_random_uuid(), s, s + interval '8 hours', s + interval '32 hours',"
              + " gen_random_uuid(), s, gen_random_uuid(), s + interval '8 hours'"
              + " FROM (SELECT ('00000000-0000-4000-8000-' || lpad((g % 20)::text, 12, '0'))::uuid"
              + " AS t, ('00000000-0000-4000-9000-' || lpad((g % 4000)::text, 12, '0'))::uuid"
              + " AS e, timestamptz '2026-01-01' + (g % 300) * interval '1 day' AS s"
              + " FROM generate_series(1, 40000) g) x");
      exec(
          c,
          "INSERT INTO people.attendance_event (tenant_id, id, record_id, schedule_id, slot_id,"
              + " employee_id, employment_id, event_type, recorded_at, recorded_by,"
              + " idempotency_digest, recording_method, client_event_id, device_offset_minutes,"
              + " received_at) SELECT tenant_id, check_in_event_id, id, schedule_id, slot_id,"
              + " employee_id, employment_id, 'CHECKED_IN', checked_in_at, 'test',"
              + " repeat('a', 64), 'EMPLOYEE_OFFLINE_SYNC', gen_random_uuid(), 60,"
              + " checked_in_at FROM people.attendance_record");
      exec(c, "ANALYZE people.attendance_event");
      exec(c, "ANALYZE people.attendance_record");
      List<String> plans = new java.util.ArrayList<>();
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

  // ------------------------------------------------------------------------------------------
  // Query plans
  // ------------------------------------------------------------------------------------------

  @Test
  void theLookupsUseTheirIndexes() {
    withDatabase(
        db -> {
          migrate(db.url(), "28");
          UUID tenant = UUID.randomUUID();
          UUID id = UUID.randomUUID();
          assertThat(
                  plan(
                      db,
                      "SELECT id FROM people.attendance_record a WHERE a.tenant_id = ?"
                          + " AND a.employee_id = ? ORDER BY a.slot_start_at DESC, a.id DESC"
                          + " LIMIT 21",
                      tenant,
                      id))
              .contains("attendance_record_history");
          assertThat(
                  plan(
                      db,
                      "SELECT id FROM people.attendance_record WHERE tenant_id = ?"
                          + " AND slot_id = ? AND employee_id = ?",
                      tenant,
                      id,
                      id))
              .doesNotContain("Seq Scan");
          assertThat(
                  plan(
                      db,
                      "SELECT id FROM people.attendance_record WHERE tenant_id = ?"
                          + " AND employee_id = ? AND check_out_event_id IS NULL",
                      tenant,
                      id))
              .doesNotContain("Seq Scan");
        });
  }

  private String plan(Db db, String sql, Object... args) {
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
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
