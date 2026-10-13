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
import java.util.Map;
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
 * MVP-062 V31 directly against PostgreSQL, bypassing the application (the database is the final
 * authority, C62-6, C62-11 items 5, 6, 15 and 16): the refusing rollback that restores V30 exactly
 * and keeps uncorrected attendance; append-only corrections numbered 1, 2, ... per record whose
 * before values are the record's current values; the exact operation shape, ordering, windows,
 * future bound, reason and explanation grammar; a record created only by its CREATE_MISSING_RECORD
 * correction for the slot's effective assignee; the projection changed only by its matching
 * correction or its single check-out; original events never changed; the deferred agreement; a
 * removed check-out never re-attached; and the plans of the target and history reads.
 */
@IntegrationTest
class AttendanceCorrectionMigrationIntegrationTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final String KINSHASA = "Africa/Kinshasa";
  private static final Timestamp EARLY = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
  private static final String DIGEST = "a".repeat(64);
  private static final String EXPLANATION = "Badge reader offline at the gate";

  private static final String EVENT =
      "INSERT INTO people.attendance_event (tenant_id, id, record_id, schedule_id, slot_id,"
          + " employee_id, employment_id, event_type, recorded_at, recorded_by,"
          + " idempotency_digest, recording_method) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'test', ?,"
          + " 'SELF')";

  private static final String RECORD =
      "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id, employee_id,"
          + " employment_id, slot_start_at, slot_end_at, check_out_until, check_in_event_id,"
          + " checked_in_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String CORRECTION =
      "INSERT INTO people.attendance_correction (tenant_id, id, record_id, version, operation,"
          + " schedule_id, slot_id, employee_id, employment_id, slot_start_at, slot_end_at,"
          + " before_checked_in_at, before_checked_out_at, after_checked_in_at,"
          + " after_checked_out_at, reason_code, explanation, corrected_by, preview_digest,"
          + " idempotency_digest, corrected_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
          + " ?, ?, ?, 'admin-subject', ?, ?, ?)";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  /** An employee and their one employment. */
  private record Hired(UUID employee, UUID employment) {}

  /** A tenant with Ana (covering one 08-16 shift) and Ben, at the 8 o'clock shift's day. */
  private record World(
      UUID tenant,
      UUID site,
      Hired ana,
      Hired ben,
      UUID schedule,
      UUID slot,
      Timestamp start,
      Timestamp end) {}

  /** One correction's values. */
  private record Values(
      String operation,
      int version,
      Timestamp beforeIn,
      Timestamp beforeOut,
      Timestamp afterIn,
      Timestamp afterOut,
      String reason,
      String explanation,
      Timestamp at) {

    Values with(String field, Object value) {
      Map<String, Object> m = new java.util.HashMap<>();
      m.put("operation", operation);
      m.put("version", version);
      m.put("beforeIn", beforeIn);
      m.put("beforeOut", beforeOut);
      m.put("afterIn", afterIn);
      m.put("afterOut", afterOut);
      m.put("reason", reason);
      m.put("explanation", explanation);
      m.put("at", at);
      m.put(field, value);
      return new Values(
          (String) m.get("operation"),
          (Integer) m.get("version"),
          (Timestamp) m.get("beforeIn"),
          (Timestamp) m.get("beforeOut"),
          (Timestamp) m.get("afterIn"),
          (Timestamp) m.get("afterOut"),
          (String) m.get("reason"),
          (String) m.get("explanation"),
          (Timestamp) m.get("at"));
    }
  }

  private void withDatabase(Consumer<Db> body) {
    String database = "attend31_" + UUID.randomUUID().toString().replace("-", "");
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
    LocalDateTime start = DAY.atTime(8, 0);
    LocalDateTime end = DAY.atTime(16, 0);
    World[] built = new World[1];
    committed(
        db,
        c -> {
          UUID tenant = organization(c, site);
          Hired ana = hire(c, tenant, site, "E-ANA");
          Hired ben = hire(c, tenant, site, "E-BEN");
          UUID slot = operational(c, tenant, site, schedule, start, end, ana.employee());
          built[0] = new World(tenant, site, ana, ben, schedule, slot, ts(start), ts(end));
        });
    return built[0];
  }

  private static void event(
      Connection c, World w, UUID id, UUID record, Hired who, String type, Timestamp at)
      throws SQLException {
    exec(
        c,
        EVENT,
        w.tenant(),
        id,
        record,
        w.schedule(),
        w.slot(),
        who.employee(),
        who.employment(),
        type,
        at,
        DIGEST);
  }

  /** Ana's self-service check-in at {@code at}: the event, then the open record. */
  private static void checkIn(Connection c, World w, UUID record, UUID event, Timestamp at)
      throws SQLException {
    event(c, w, event, record, w.ana(), "CHECKED_IN", at);
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
        plus(w.end(), Duration.ofHours(24)),
        event,
        at);
  }

  /** The check-out of a record at {@code at}: the event, then the single update. */
  private static void checkOut(Connection c, World w, UUID record, UUID event, Timestamp at)
      throws SQLException {
    event(c, w, event, record, w.ana(), "CHECKED_OUT", at);
    exec(
        c,
        "UPDATE people.attendance_record SET check_out_event_id = ?, checked_out_at = ?"
            + " WHERE tenant_id = ? AND id = ?",
        event,
        at,
        w.tenant(),
        record);
  }

  /** One correction row of {@code who}'s record on the shift. */
  private static void correction(Connection c, World w, UUID id, UUID record, Hired who, Values v)
      throws SQLException {
    exec(
        c,
        CORRECTION,
        w.tenant(),
        id,
        record,
        v.version(),
        v.operation(),
        w.schedule(),
        w.slot(),
        who.employee(),
        who.employment(),
        w.start(),
        w.end(),
        v.beforeIn(),
        v.beforeOut(),
        v.afterIn(),
        v.afterOut(),
        v.reason(),
        v.explanation(),
        DIGEST,
        DIGEST,
        v.at());
  }

  /** The record's guarded REPLACE_TIMES update to version {@code v.version()}. */
  private static void apply(Connection c, World w, UUID record, Values v) throws SQLException {
    exec(
        c,
        "UPDATE people.attendance_record SET checked_in_at = ?, checked_out_at = ?,"
            + " correction_version = ?, corrected_at = ? WHERE tenant_id = ? AND id = ?",
        v.afterIn(),
        v.afterOut(),
        v.version(),
        v.at(),
        w.tenant(),
        record);
  }

  /** A record created by its CREATE_MISSING_RECORD correction {@code v}. */
  private static void created(Connection c, World w, UUID record, Hired who, Values v)
      throws SQLException {
    exec(
        c,
        "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id, employee_id,"
            + " employment_id, slot_start_at, slot_end_at, check_out_until, check_in_event_id,"
            + " checked_in_at, checked_out_at, correction_version, corrected_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, 1, ?)",
        w.tenant(),
        record,
        w.schedule(),
        w.slot(),
        who.employee(),
        who.employment(),
        w.start(),
        w.end(),
        plus(w.end(), Duration.ofHours(24)),
        v.afterIn(),
        v.afterOut(),
        v.at());
  }

  private Timestamp at(World w, int minutes) {
    return plus(w.start(), Duration.ofMinutes(minutes));
  }

  /** A correction committed a day after the shift. */
  private Values replace(
      World w,
      int version,
      Timestamp beforeIn,
      Timestamp beforeOut,
      Timestamp afterIn,
      Timestamp afterOut) {
    return new Values(
        "REPLACE_TIMES",
        version,
        beforeIn,
        beforeOut,
        afterIn,
        afterOut,
        "DATA_ENTRY_ERROR",
        EXPLANATION,
        plus(w.end(), Duration.ofHours(30)));
  }

  private Values create(World w, Timestamp afterIn, Timestamp afterOut) {
    return new Values(
        "CREATE_MISSING_RECORD",
        1,
        null,
        null,
        afterIn,
        afterOut,
        "SUPERVISOR_OR_EMPLOYEE_REPORT",
        EXPLANATION,
        plus(w.end(), Duration.ofHours(30)));
  }

  private static String events(Db db, UUID record) {
    return db.jdbc()
        .queryForObject(
            "SELECT coalesce(string_agg(row_to_json(e)::text, '|' ORDER BY e.event_type), '')"
                + " FROM people.attendance_event e WHERE e.record_id = ?",
            String.class,
            record);
  }

  private static String row(Db db, UUID record) {
    return db.jdbc()
        .queryForObject(
            "SELECT status || ' ' || to_char(checked_in_at AT TIME ZONE 'UTC',"
                + " 'HH24:MI') || ' ' || coalesce(to_char(checked_out_at AT TIME ZONE 'UTC',"
                + " 'HH24:MI'), '-') || ' v' || correction_version FROM people.attendance_record"
                + " WHERE id = ?",
            String.class,
            record);
  }

  // ------------------------------------------------------------------------------------------
  // Rollback and upgrade
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV30ExactlyKeepsUncorrectedAttendanceAndRefusesCorrectionEvidence() {
    withDatabase(
        db -> {
          migrate(db.url(), "30");
          World w = world(db);
          UUID open = UUID.randomUUID();
          committed(db, c -> checkIn(c, w, open, UUID.randomUUID(), at(w, -30)));
          String v30 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          String status = statusExpression(db);
          migrate(db.url(), "31");
          String v31 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          assertThat(v31)
              .isNotEqualTo(v30)
              .contains("attendance_correction")
              .contains("correction_version");
          assertThat(statusExpression(db)).isNotEqualTo(status).contains("checked_out_at");
          // Existing evidence is uncorrected (version 0) and unchanged; it completes as before.
          assertThat(row(db, open)).isEqualTo("OPEN 06:30 - v0");
          committed(db, c -> checkOut(c, w, open, UUID.randomUUID(), at(w, 400)));
          assertThat(row(db, open)).isEqualTo("COMPLETED 06:30 13:40 v0");
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V31__rollback.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v30);
          assertThat(statusExpression(db)).isEqualTo(status);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.attendance_record")).isEqualTo(1);

          db.jdbc().execute(resource("db/migration/V31__attendance_correction.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v31);
          // Once one correction exists, the rollback refuses and changes nothing.
          committed(
              db,
              c -> {
                Values v = replace(w, 1, at(w, -30), at(w, 400), at(w, 0), at(w, 400));
                correction(c, w, UUID.randomUUID(), open, w.ana(), v);
                apply(c, w, open, v);
              });
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V31__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V31 rollback refused: attendance correction evidence exists");
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v31);
          assertThat(count(db, "SELECT count(*) FROM people.attendance_correction")).isEqualTo(1);
        });
  }

  private static String statusExpression(Db db) {
    return db.jdbc()
        .queryForObject(
            "SELECT generation_expression FROM information_schema.columns WHERE table_schema ="
                + " 'people' AND table_name = 'attendance_record' AND column_name = 'status'",
            String.class);
  }

  // ------------------------------------------------------------------------------------------
  // REPLACE_TIMES: append-only, numbered, matching, events unchanged
  // ------------------------------------------------------------------------------------------

  @Test
  void correctionsAreAppendOnlyNumberedAndOnlyTheirOwnRecordChangeMatchesThem() {
    withDatabase(
        db -> {
          migrate(db.url(), "31");
          World w = world(db);
          UUID record = UUID.randomUUID();
          committed(
              db,
              c -> {
                checkIn(c, w, record, UUID.randomUUID(), at(w, 20));
                checkOut(c, w, record, UUID.randomUUID(), at(w, 300));
              });
          String original = events(db, record);
          Values first = replace(w, 1, at(w, 20), at(w, 300), at(w, 0), at(w, 300));
          UUID firstId = UUID.randomUUID();

          // The projection never changes without its matching correction, nor the reverse.
          refused(db, "attendance_record_correction", c -> apply(c, w, record, first));
          refused(
              db,
              "attendance_exact",
              c -> correction(c, w, UUID.randomUUID(), record, w.ana(), first));
          refused(
              db,
              "attendance_record_correction",
              c -> {
                correction(c, w, UUID.randomUUID(), record, w.ana(), first);
                apply(c, w, record, first.with("afterIn", at(w, 1)));
              });
          // The before values are the record's current values; the version is the next one.
          refused(
              db,
              "attendance_correction_sequence",
              c ->
                  correction(
                      c, w, UUID.randomUUID(), record, w.ana(), first.with("beforeIn", at(w, 21))));
          refused(
              db,
              "attendance_correction_sequence",
              c ->
                  correction(
                      c, w, UUID.randomUUID(), record, w.ana(), first.with("beforeOut", null)));
          refused(
              db,
              "attendance_correction_sequence",
              c -> correction(c, w, UUID.randomUUID(), record, w.ana(), first.with("version", 2)));
          // Never another employee's record or another slot (composite binding).
          refused(
              db,
              "attendance_correction_sequence",
              c -> correction(c, w, UUID.randomUUID(), record, w.ben(), first));

          committed(
              db,
              c -> {
                correction(c, w, firstId, record, w.ana(), first);
                apply(c, w, record, first);
              });
          assertThat(row(db, record)).isEqualTo("COMPLETED 07:00 12:00 v1");
          // Clear the check-out: the record is open again; its check-out event stays.
          Values second = replace(w, 2, at(w, 0), at(w, 300), at(w, 0), null);
          committed(
              db,
              c -> {
                correction(c, w, UUID.randomUUID(), record, w.ana(), second);
                apply(c, w, record, second);
              });
          assertThat(row(db, record)).isEqualTo("OPEN 07:00 - v2");
          // A removed check-out is never re-attached, and no second check-out event exists.
          refused(
              db,
              "attendance_record_guard",
              c ->
                  exec(
                      c,
                      "UPDATE people.attendance_record SET checked_out_at = ? WHERE id = ?",
                      at(w, 300),
                      record));
          refused(
              db,
              "attendance_event_check_out",
              c -> event(c, w, UUID.randomUUID(), record, w.ana(), "CHECKED_OUT", at(w, 310)));
          // A third version adds a check-out again.
          Values third = replace(w, 3, at(w, 0), null, at(w, 0), at(w, 480));
          committed(
              db,
              c -> {
                correction(c, w, UUID.randomUUID(), record, w.ana(), third);
                apply(c, w, record, third);
              });
          assertThat(row(db, record)).isEqualTo("COMPLETED 07:00 15:00 v3");
          assertThat(
                  db.jdbc()
                      .queryForList(
                          "SELECT version FROM people.attendance_correction WHERE record_id = ?"
                              + " ORDER BY version",
                          Integer.class,
                          record))
              .containsExactly(1, 2, 3);
          // The original events are byte-for-byte unchanged.
          assertThat(events(db, record)).isEqualTo(original);

          // Corrections and events are immutable; neither table is truncated.
          refused(
              db,
              "attendance_correction_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE people.attendance_correction SET explanation = ? WHERE id = ?",
                      "Another explanation text",
                      firstId));
          refused(
              db,
              "attendance_correction_immutable",
              c -> exec(c, "DELETE FROM people.attendance_correction WHERE id = ?", firstId));
          refused(
              db,
              "attendance_correction_no_truncate",
              c -> exec(c, "TRUNCATE people.attendance_correction CASCADE"));
          refused(
              db,
              "attendance_event_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE people.attendance_event SET recorded_at = recorded_at"
                          + " WHERE record_id = ?",
                      record));
          refused(
              db,
              "attendance_record_immutable",
              c -> exec(c, "DELETE FROM people.attendance_record WHERE id = ?", record));
        });
  }

  @Test
  void aCorrectionHasItsExactShapeTimesReasonAndExplanation() {
    withDatabase(
        db -> {
          migrate(db.url(), "31");
          World w = world(db);
          UUID record = UUID.randomUUID();
          committed(db, c -> checkIn(c, w, record, UUID.randomUUID(), at(w, 20)));
          Values ok = replace(w, 1, at(w, 20), null, at(w, 0), at(w, 480));
          Timestamp opens = at(w, -120);
          Timestamp until = plus(w.end(), Duration.ofHours(24));
          List<Object[]> cases = new ArrayList<>();
          cases.add(new Object[] {"attendance_correction_operation", ok.with("operation", "VOID")});
          cases.add(
              new Object[] {
                "attendance_correction_shape", ok.with("beforeIn", null).with("beforeOut", null)
              });
          cases.add(new Object[] {"attendance_correction_version_positive", ok.with("version", 0)});
          cases.add(
              new Object[] {
                "attendance_correction_changes",
                ok.with("afterIn", at(w, 20)).with("afterOut", null)
              });
          cases.add(new Object[] {"attendance_correction_order", ok.with("afterOut", at(w, 0))});
          cases.add(
              new Object[] {
                "attendance_correction_check_in_window",
                ok.with("afterIn", plus(opens, Duration.ofNanos(-1000)))
              });
          cases.add(
              new Object[] {
                "attendance_correction_check_in_window",
                ok.with("afterIn", w.end()).with("afterOut", plus(w.end(), Duration.ofMinutes(1)))
              });
          cases.add(
              new Object[] {
                "attendance_correction_check_out_window",
                ok.with("afterOut", plus(until, Duration.ofNanos(1000)))
              });
          cases.add(
              new Object[] {
                "attendance_correction_not_future",
                ok.with("at", plus(at(w, 480), Duration.ofMinutes(-5).minusNanos(1000)))
              });
          cases.add(new Object[] {"attendance_correction_reason", ok.with("reason", "PAYROLL")});
          for (String bad :
              List.of(
                  "Too short",
                  " Badge reader offline",
                  "Badge reader <b>offline</b>",
                  "See https://example.test now",
                  "See www.example.test now",
                  "Badge reader\noffline",
                  "x".repeat(501))) {
            cases.add(
                new Object[] {
                  "attendance_correction_explanation_valid", ok.with("explanation", bad)
                });
          }
          for (Object[] c0 : cases) {
            refused(
                db,
                (String) c0[0],
                c -> correction(c, w, UUID.randomUUID(), record, w.ana(), (Values) c0[1]));
          }
          // The exact boundaries are accepted (the earliest check-in, the latest check-out, a
          // correction at the five-minute future bound, a 500-character explanation).
          Values edge =
              ok.with("afterIn", opens)
                  .with("afterOut", until)
                  .with("at", plus(until, Duration.ofMinutes(-5)))
                  .with("explanation", "é".repeat(500));
          committed(
              db,
              c -> {
                correction(c, w, UUID.randomUUID(), record, w.ana(), edge);
                apply(c, w, record, edge);
              });
          assertThat(row(db, record)).isEqualTo("COMPLETED 05:00 15:00 v1");
        });
  }

  // ------------------------------------------------------------------------------------------
  // CREATE_MISSING_RECORD
  // ------------------------------------------------------------------------------------------

  @Test
  void aMissingRecordIsCreatedOnlyByItsCorrectionForTheEffectiveAssignee() {
    withDatabase(
        db -> {
          migrate(db.url(), "31");
          World w = world(db);
          UUID record = UUID.randomUUID();
          Values v = create(w, at(w, 0), null);
          // No record without its correction; no correction for another employee.
          refused(db, "attendance_record_correction", c -> created(c, w, record, w.ana(), v));
          refused(
              db,
              "attendance_correction_create",
              c -> {
                correction(c, w, UUID.randomUUID(), record, w.ben(), v);
                created(c, w, record, w.ben(), v);
              });
          refused(
              db,
              "attendance_correction_shape",
              c -> correction(c, w, UUID.randomUUID(), record, w.ana(), v.with("version", 2)));
          refused(
              db,
              "attendance_correction_shape",
              c ->
                  correction(
                      c, w, UUID.randomUUID(), record, w.ana(), v.with("beforeIn", at(w, 1))));
          refused(
              db,
              "attendance_record_correction",
              c -> {
                correction(c, w, UUID.randomUUID(), record, w.ana(), v);
                created(c, w, record, w.ana(), v.with("afterIn", at(w, 1)));
              });
          committed(
              db,
              c -> {
                correction(c, w, UUID.randomUUID(), record, w.ana(), v);
                created(c, w, record, w.ana(), v);
              });
          assertThat(row(db, record)).isEqualTo("OPEN 07:00 - v1");
          assertThat(events(db, record)).isEmpty();
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.attendance_record WHERE id = ?"
                          + " AND check_in_event_id IS NULL",
                      record))
              .isEqualTo(1);
          // Never a second record of the slot and employee (a record exists: replace it).
          refused(
              db,
              "attendance_correction_sequence",
              c -> {
                UUID other = UUID.randomUUID();
                correction(c, w, UUID.randomUUID(), other, w.ana(), v);
                created(c, w, other, w.ana(), v);
              });
          // Never an invented check-in event for it.
          refused(
              db,
              "attendance_exact",
              c ->
                  exec(
                      c,
                      EVENT,
                      w.tenant(),
                      UUID.randomUUID(),
                      record,
                      w.schedule(),
                      w.slot(),
                      w.ana().employee(),
                      w.ana().employment(),
                      "CHECKED_IN",
                      at(w, 5),
                      DIGEST));
          // Its single check-out event still attaches after the correction (the deferred
          // agreement accepts the event's instant when the latest correction left no check-out).
          committed(db, c -> checkOut(c, w, record, UUID.randomUUID(), at(w, 470)));
          assertThat(row(db, record)).isEqualTo("COMPLETED 07:00 14:50 v1");
        });
  }

  // ------------------------------------------------------------------------------------------
  // Query plans
  // ------------------------------------------------------------------------------------------

  @Test
  void theTargetAndHistoryReadsUseTheirKeys() {
    withDatabase(
        db -> {
          migrate(db.url(), "31");
          UUID tenant = UUID.fromString("00000000-0000-4000-8000-000000000007");
          UUID id = UUID.randomUUID();
          Timestamp now = Timestamp.from(Instant.parse("2026-06-02T08:00:00Z"));
          List<String> plans =
              analyzedPlans(
                  db,
                  List.of(
                      // A record's history, newest first.
                      "SELECT c.id FROM people.attendance_correction c WHERE c.tenant_id = ?"
                          + " AND c.record_id = ? ORDER BY c.version DESC",
                      // The record target.
                      "SELECT a.id FROM people.attendance_record a WHERE a.tenant_id = ?"
                          + " AND a.id = ? FOR UPDATE",
                      // The missing-record target's existing record.
                      "SELECT a.id FROM people.attendance_record a WHERE a.tenant_id = ?"
                          + " AND a.slot_id = ? AND a.employee_id = ?",
                      // The employee's overlapping open records (effective check-out).
                      "SELECT id FROM people.attendance_record WHERE tenant_id = ?"
                          + " AND employee_id = ? AND checked_out_at IS NULL"
                          + " AND checked_in_at <= ? AND check_out_until >= ? ORDER BY id"
                          + " FOR UPDATE",
                      // A record's original events.
                      "SELECT e.event_type FROM people.attendance_event e WHERE e.tenant_id = ?"
                          + " AND e.record_id = ? ORDER BY e.event_type"),
                  List.of(
                      new Object[] {tenant, id},
                      new Object[] {tenant, id},
                      new Object[] {tenant, id, UUID.randomUUID()},
                      new Object[] {tenant, id, now, now},
                      new Object[] {tenant, id}));
          assertThat(plans.get(0)).contains("attendance_correction_version_unique");
          assertThat(plans.get(1)).containsAnyOf("attendance_record_pkey", "attendance_record_key");
          assertThat(plans.get(2))
              .containsAnyOf("attendance_record_slot_employee_unique", "attendance_record_history");
          assertThat(plans.get(3)).containsAnyOf("attendance_record_history", "one_open");
          assertThat(plans.get(4)).contains("attendance_event_record_type_unique");
          for (String plan : plans) {
            assertThat(plan).doesNotContain("Seq Scan");
          }
        });
  }

  /**
   * The plans of {@code sqls} over 40,000 analyzed records, their events and 40,000 corrections of
   * 20 tenants and 4,000 employees (inserted with triggers and foreign keys set aside, then rolled
   * back).
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
              + " idempotency_digest, recording_method) SELECT tenant_id, check_in_event_id, id,"
              + " schedule_id, slot_id, employee_id, employment_id, 'CHECKED_IN', checked_in_at,"
              + " 'test', repeat('a', 64), 'SELF' FROM people.attendance_record");
      exec(
          c,
          "INSERT INTO people.attendance_correction (tenant_id, id, record_id, version, operation,"
              + " schedule_id, slot_id, employee_id, employment_id, slot_start_at, slot_end_at,"
              + " before_checked_in_at, before_checked_out_at, after_checked_in_at,"
              + " after_checked_out_at, reason_code, explanation, corrected_by, preview_digest,"
              + " idempotency_digest, corrected_at) SELECT tenant_id, gen_random_uuid(), id, 1,"
              + " 'REPLACE_TIMES', schedule_id, slot_id, employee_id, employment_id,"
              + " slot_start_at, slot_end_at, checked_in_at, checked_out_at, checked_in_at,"
              + " checked_out_at - interval '1 minute', 'OTHER', 'An explanation text', 'test',"
              + " repeat('a', 64), repeat('a', 64), slot_end_at + interval '30 hours'"
              + " FROM people.attendance_record");
      exec(c, "ANALYZE people.attendance_event");
      exec(c, "ANALYZE people.attendance_record");
      exec(c, "ANALYZE people.attendance_correction");
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
