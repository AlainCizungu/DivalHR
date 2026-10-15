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
 * MVP-070D V34 directly against PostgreSQL, bypassing the application (the database is the final
 * authority, T70D-8, T70D-11 items 5, 15 and 16): the refusing rollback that restores V33 exactly;
 * the reopening's exact shape and binding to the superseded approval, its correction of the same
 * employee and its manager; the only legal order APPROVED, REOPENED, a DRAFT at the next revision,
 * SUBMITTED; append-only evidence; refreshes refused while time is submitted and approved time
 * never revised; and the plans of the reads a correction adds.
 */
@IntegrationTest
class TimesheetReopeningMigrationIntegrationTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final String KINSHASA = "Africa/Kinshasa";
  private static final Timestamp EARLY = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
  private static final Timestamp AT = Timestamp.from(Instant.parse("2026-11-08T11:00:00Z"));
  private static final Timestamp LATER = Timestamp.from(Instant.parse("2026-11-09T09:00:00Z"));
  private static final String DIGEST = "a".repeat(64);
  private static final String DIGEST2 = "b".repeat(64);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  /**
   * A tenant with Ana (managed by Mia) covering one 08-16 shift on Monday, and her completed
   * attendance record.
   */
  private record World(
      UUID tenant,
      UUID site,
      UUID employee,
      UUID employment,
      UUID manager,
      UUID schedule,
      UUID slot,
      Timestamp start,
      Timestamp end,
      UUID record) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "timesheet34_" + UUID.randomUUID().toString().replace("-", "");
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
    UUID manager = UUID.randomUUID();
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
          for (Object[] person :
              List.of(
                  new Object[] {employee, "E-ANA", "Ana", " ana mbuyi "},
                  new Object[] {manager, "E-MIA", "Mia", " mia mbuyi "})) {
            exec(
                c,
                "INSERT INTO people.employee (id, tenant_id, employee_number, given_names,"
                    + " family_name, search_key, created_at, created_by) VALUES (?, ?, ?, ?,"
                    + " 'Mbuyi', ?, now(), 'test')",
                person[0],
                tenant,
                person[1],
                person[2],
                person[3]);
          }
          UUID change = UUID.randomUUID();
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
        tenant, site, employee, employment, manager, schedule, slot, ts(start), ts(end), record);
  }

  /** The week's generation at version 1 and one draft whose revision 1 has one row. */
  private static void draft(Connection c, World w, UUID generation, UUID timesheet, boolean ready)
      throws SQLException {
    exec(
        c,
        "INSERT INTO people.timesheet_generation (tenant_id, id, site_id, week_start, week_end,"
            + " site_timezone, period_start_at, period_end_at, version, status, generated_at,"
            + " generated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'DRAFT', ?, 'admin')",
        w.tenant(),
        generation,
        w.site(),
        MONDAY,
        MONDAY.plusDays(7),
        KINSHASA,
        ts(MONDAY.atStartOfDay()),
        ts(MONDAY.plusDays(7).atStartOfDay()),
        AT);
    exec(
        c,
        "INSERT INTO people.timesheet (tenant_id, id, generation_id, employee_id, site_id,"
            + " week_start, status, current_revision, row_count, ready_rows, missing_rows,"
            + " open_rows, changed_rows, worked_minutes, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', 1, 1, ?, ?, 0, 0, ?, ?, ?)",
        w.tenant(),
        timesheet,
        generation,
        w.employee(),
        w.site(),
        MONDAY,
        ready ? 1 : 0,
        ready ? 0 : 1,
        ready ? 470 : 0,
        AT,
        AT);
    revision(c, w, timesheet, 1, 1, DIGEST, ready);
  }

  /** A revision header and its single row (READY: 470 minutes; otherwise missing attendance). */
  private static void revision(
      Connection c,
      World w,
      UUID timesheet,
      int revision,
      int generationVersion,
      String digest,
      boolean ready)
      throws SQLException {
    exec(
        c,
        "INSERT INTO people.timesheet_revision (tenant_id, timesheet_id, revision,"
            + " generation_version, source_digest, row_count, ready_rows, missing_rows,"
            + " open_rows, changed_rows, worked_minutes, created_at)"
            + " VALUES (?, ?, ?, ?, ?, 1, ?, ?, 0, 0, ?, ?)",
        w.tenant(),
        timesheet,
        revision,
        generationVersion,
        digest,
        ready ? 1 : 0,
        ready ? 0 : 1,
        ready ? 470 : 0,
        AT);
    exec(
        c,
        "INSERT INTO people.timesheet_row (tenant_id, timesheet_id, revision, slot_id, schedule_id,"
            + " employee_id, employment_id, site_id, label_en, label_fr, scheduled_start_at,"
            + " scheduled_end_at, attendance_record_id, checked_in_at, checked_out_at,"
            + " correction_version, state, worked_minutes) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
            + " 'Cashier', 'Caissière', ?, ?, ?, ?, ?, ?, ?, ?)",
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
        ready ? w.record() : null,
        ready ? plus(w.start(), Duration.ofMinutes(5)) : null,
        ready ? plus(w.start(), Duration.ofMinutes(475)) : null,
        ready ? 0 : null,
        ready ? "READY" : "MISSING_ATTENDANCE",
        ready ? 470 : null);
  }

  /** An action's evidence (one READY row of 470 minutes unless stated otherwise). */
  private record Act(
      UUID id,
      int sequence,
      String action,
      int cycle,
      UUID submission,
      int revision,
      int generationVersion,
      String digest,
      int worked,
      UUID manager,
      String reason,
      Timestamp at) {}

  private static Act submitted(World w, UUID id, int sequence, int cycle, int revision) {
    return new Act(
        id,
        sequence,
        "SUBMITTED",
        cycle,
        null,
        revision,
        revision,
        DIGEST,
        470,
        w.manager(),
        null,
        AT);
  }

  private static Act decided(
      World w, String kind, int sequence, Act submission, String reason, Timestamp at) {
    return new Act(
        UUID.randomUUID(),
        sequence,
        kind,
        submission.cycle(),
        submission.id(),
        submission.revision(),
        submission.generationVersion(),
        submission.digest(),
        submission.worked(),
        w.manager(),
        reason,
        at);
  }

  private static void insert(Connection c, World w, UUID timesheet, Act a) throws SQLException {
    exec(
        c,
        "INSERT INTO people.timesheet_action (tenant_id, id, timesheet_id, sequence, action, cycle,"
            + " submission_action_id, revision, generation_version, source_digest, row_count,"
            + " ready_rows, missing_rows, open_rows, changed_rows, worked_minutes, employee_id,"
            + " site_id, employment_id, manager_employee_id, actor_subject, return_reason,"
            + " occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 0, 0, 0, ?, ?, ?, ?, ?,"
            + " 'sub-1', ?, ?)",
        w.tenant(),
        a.id(),
        timesheet,
        a.sequence(),
        a.action(),
        a.cycle(),
        a.submission(),
        a.revision(),
        a.generationVersion(),
        a.digest(),
        a.worked(),
        w.employee(),
        w.site(),
        w.employment(),
        a.manager(),
        a.reason(),
        a.at());
  }

  /** The timesheet's move to an action's state. */
  private static void moveTo(Connection c, World w, UUID timesheet, String status, int cycle, Act a)
      throws SQLException {
    exec(
        c,
        "UPDATE people.timesheet SET status = ?, submission_cycle = ?, current_action_id = ?"
            + " WHERE tenant_id = ? AND id = ?",
        status,
        cycle,
        a.id(),
        w.tenant(),
        timesheet);
  }

  /** An action and the timesheet's matching move. */
  private static void act(Connection c, World w, UUID timesheet, Act a) throws SQLException {
    insert(c, w, timesheet, a);
    String status =
        switch (a.action()) {
          case "SUBMITTED" -> "SUBMITTED";
          case "APPROVED" -> "APPROVED";
          default -> "DRAFT";
        };
    moveTo(c, w, timesheet, status, a.cycle(), a);
  }

  // ------------------------------------------------------------------------------------------
  // V34 fixtures
  // ------------------------------------------------------------------------------------------

  /**
   * A committed correction of the person's record (inserted with triggers and keys set aside: only
   * its identity matters to V34).
   */
  private static UUID correction(Connection c, World w, UUID employee, int version)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(c, "SET LOCAL session_replication_role = replica");
    exec(
        c,
        "INSERT INTO people.attendance_correction (tenant_id, id, record_id, version, operation,"
            + " schedule_id, slot_id, employee_id, employment_id, slot_start_at, slot_end_at,"
            + " before_checked_in_at, before_checked_out_at, after_checked_in_at,"
            + " after_checked_out_at, reason_code, explanation, corrected_by, preview_digest,"
            + " idempotency_digest, corrected_at) VALUES (?, ?, ?, ?, 'REPLACE_TIMES', ?, ?, ?,"
            + " ?, ?, ?, ?, NULL, ?, ?, 'DEVICE_OR_CONNECTIVITY', 'Lecteur hors service ici.',"
            + " 'hr', ?, ?, ?)",
        w.tenant(),
        id,
        w.record(),
        version,
        w.schedule(),
        w.slot(),
        employee,
        w.employment(),
        w.start(),
        w.end(),
        w.start(),
        plus(w.start(), Duration.ofMinutes(20)),
        w.end(),
        DIGEST,
        DIGEST,
        LATER);
    exec(c, "SET LOCAL session_replication_role = origin");
    return id;
  }

  /** A reopening of an approval (its evidence, manager and cycle) with the given facts. */
  private static void reopen(
      Connection c,
      World w,
      UUID timesheet,
      Act approval,
      int sequence,
      UUID superseded,
      int generationVersion,
      UUID manager,
      String after,
      UUID correction)
      throws SQLException {
    exec(
        c,
        "INSERT INTO people.timesheet_action (tenant_id, id, timesheet_id, sequence, action, cycle,"
            + " submission_action_id, revision, generation_version, source_digest, row_count,"
            + " ready_rows, missing_rows, open_rows, changed_rows, worked_minutes, employee_id,"
            + " site_id, employment_id, manager_employee_id, actor_subject, return_reason,"
            + " occurred_at, superseded_action_id, after_source_digest, correction_id) VALUES (?,"
            + " ?, ?, ?, 'REOPENED', ?, NULL, ?, ?, ?, 1, 1, 0, 0, 0, ?, ?, ?, ?, ?, 'hr', NULL,"
            + " ?, ?, ?, ?)",
        w.tenant(),
        UUID.randomUUID(),
        timesheet,
        sequence,
        approval.cycle(),
        approval.revision(),
        generationVersion,
        approval.digest(),
        approval.worked(),
        w.employee(),
        w.site(),
        w.employment(),
        manager,
        LATER,
        superseded,
        after,
        correction);
  }

  private static void reopenedMove(Connection c, World w, UUID timesheet, String status)
      throws SQLException {
    exec(
        c,
        "UPDATE people.timesheet SET status = ?, current_action_id = (SELECT id FROM"
            + " people.timesheet_action WHERE timesheet_id = ? ORDER BY sequence DESC LIMIT 1)"
            + " WHERE tenant_id = ? AND id = ?",
        status,
        timesheet,
        w.tenant(),
        timesheet);
  }

  private static final String DIGEST3 = "c".repeat(64);

  /** Ana's week generated, submitted and approved (cycle 1). */
  private Act approved(Db db, World w, UUID g, UUID t) {
    committed(db, c -> draft(c, w, g, t, true));
    Act first = submitted(w, UUID.randomUUID(), 1, 1, 1);
    committed(db, c -> act(c, w, t, first));
    Act approval = decided(w, "APPROVED", 2, first, null, AT);
    committed(db, c -> act(c, w, t, approval));
    return approval;
  }

  // ------------------------------------------------------------------------------------------
  // Rollback and upgrade
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV33ExactlyAndRefusesReopeningEvidence() {
    withDatabase(
        db -> {
          migrate(db.url(), "33");
          World w = world(db);
          UUID g = UUID.randomUUID();
          UUID t = UUID.randomUUID();
          Act approval = approved(db, w, g, t);
          String v33 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          migrate(db.url(), "34");
          String v34 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          assertThat(v34)
              .isNotEqualTo(v33)
              .contains("timesheet_action_reopening_unique")
              .contains("timesheet_action_superseded")
              .contains("attendance_correction_employee_key");
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V34__rollback.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v33);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.timesheet_action")).isEqualTo(2);

          db.jdbc().execute(resource("db/migration/V34__timesheet_reopening.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v34);
          // Once a correction reopened approved time, the rollback refuses and changes nothing.
          committed(
              db,
              c -> {
                UUID fix = correction(c, w, w.employee(), 1);
                reopen(c, w, t, approval, 3, approval.id(), 1, w.manager(), DIGEST2, fix);
                reopenedMove(c, w, t, "REOPENED");
              });
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V34__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V34 rollback refused: timesheet reopening evidence exists");
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v34);
        });
  }

  @Test
  void theRollbackRefusesApprovedTimeKeptThroughALaterRefresh() {
    withDatabase(
        db -> {
          migrate(db.url(), "34");
          World w = world(db);
          UUID g = UUID.randomUUID();
          UUID t = UUID.randomUUID();
          approved(db, w, g, t);
          // V34 refreshes a week whose approved time stays locked; V33 could never hold that.
          committed(
              db,
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_generation SET version = 2, refreshed_at = ?,"
                          + " refreshed_by = 'hr' WHERE tenant_id = ?",
                      LATER,
                      w.tenant()));
          String v34 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V34__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V34 rollback refused: timesheet reopening evidence exists");
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v34);
        });
  }

  // ------------------------------------------------------------------------------------------
  // The lifecycle
  // ------------------------------------------------------------------------------------------

  @Test
  void approvedReopenedRefreshedResubmittedFollowTheirOnlyLegalOrder() {
    withDatabase(
        db -> {
          migrate(db.url(), "34");
          World w = world(db);
          UUID g = UUID.randomUUID();
          UUID t = UUID.randomUUID();
          Act approval = approved(db, w, g, t);
          UUID[] fix = new UUID[1];
          committed(db, c -> fix[0] = correction(c, w, w.employee(), 1));
          UUID[] foreign = new UUID[1];
          committed(db, c -> foreign[0] = correction(c, w, w.manager(), 2));
          List<Map<String, Object>> approvedEvidence =
              db.jdbc().queryForList("SELECT * FROM people.timesheet_action ORDER BY sequence");

          // The digest after the correction differs from the approved one.
          refused(
              db,
              "timesheet_action_shape",
              c -> reopen(c, w, t, approval, 3, approval.id(), 1, w.manager(), DIGEST, fix[0]));
          // Only the current approval is superseded, by its approving manager.
          refused(
              db,
              "timesheet_action_transition",
              c ->
                  reopen(
                      c,
                      w,
                      t,
                      approval,
                      3,
                      approval.submission(),
                      1,
                      w.manager(),
                      DIGEST2,
                      fix[0]));
          refused(
              db,
              "timesheet_action_transition",
              c -> reopen(c, w, t, approval, 3, approval.id(), 1, w.employee(), DIGEST2, fix[0]));
          // The approval's exact evidence (its generation version included).
          refused(
              db,
              "timesheet_action_superseded",
              c -> reopen(c, w, t, approval, 3, approval.id(), 7, w.manager(), DIGEST2, fix[0]));
          // The correction is the same employee's.
          refused(
              db,
              "timesheet_action_correction",
              c ->
                  reopen(c, w, t, approval, 3, approval.id(), 1, w.manager(), DIGEST2, foreign[0]));
          refused(
              db,
              "timesheet_action_shape",
              c -> reopen(c, w, t, approval, 3, approval.id(), 1, w.manager(), DIGEST2, null));
          // The timesheet moves to REOPENED with it, and to nothing else.
          refused(
              db,
              "timesheet_action_current",
              c -> reopen(c, w, t, approval, 3, approval.id(), 1, w.manager(), DIGEST2, fix[0]));
          refused(
              db,
              "timesheet_guard",
              c -> {
                reopen(c, w, t, approval, 3, approval.id(), 1, w.manager(), DIGEST2, fix[0]);
                reopenedMove(c, w, t, "DRAFT");
              });
          committed(
              db,
              c -> {
                reopen(c, w, t, approval, 3, approval.id(), 1, w.manager(), DIGEST2, fix[0]);
                reopenedMove(c, w, t, "REOPENED");
              });
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.timesheet WHERE status = 'REOPENED'"
                          + " AND submission_cycle = 1 AND current_revision = 1"))
              .isEqualTo(1);
          // Append-only; never reopened twice; one reopening per cycle.
          refused(
              db,
              "timesheet_action_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_action SET after_source_digest = ? WHERE action ="
                          + " 'REOPENED'",
                      DIGEST3));
          refused(
              db,
              "timesheet_action_transition",
              c -> reopen(c, w, t, approval, 4, approval.id(), 1, w.manager(), DIGEST3, fix[0]));
          refused(
              db,
              "timesheet_action_reopened_cycle_unique",
              c -> {
                exec(
                    c,
                    "ALTER TABLE people.timesheet_action DISABLE TRIGGER"
                        + " timesheet_action_transition");
                reopen(c, w, t, approval, 4, approval.id(), 1, w.manager(), DIGEST3, fix[0]);
              });
          // A reopened timesheet is never submitted, nor made a draft without its next revision.
          refused(
              db,
              "timesheet_action_transition",
              c ->
                  act(
                      c,
                      w,
                      t,
                      new Act(
                          UUID.randomUUID(),
                          4,
                          "SUBMITTED",
                          2,
                          null,
                          1,
                          1,
                          DIGEST,
                          470,
                          w.manager(),
                          null,
                          LATER)));
          refused(
              db,
              "timesheet_guard",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet SET status = 'DRAFT' WHERE tenant_id = ?",
                      w.tenant()));

          // The refresh: the generation's next version and the timesheet's next revision, a DRAFT.
          committed(
              db,
              c -> {
                exec(
                    c,
                    "UPDATE people.timesheet_generation SET version = 2, refreshed_at = ?,"
                        + " refreshed_by = 'hr' WHERE tenant_id = ?",
                    LATER,
                    w.tenant());
                revision(c, w, t, 2, 2, DIGEST2, true);
                exec(
                    c,
                    "UPDATE people.timesheet SET current_revision = 2, status = 'DRAFT',"
                        + " updated_at = ? WHERE tenant_id = ? AND id = ?",
                    LATER,
                    w.tenant(),
                    t);
              });
          // Cycle 2 binds the refreshed revision (never the approved one).
          refused(
              db,
              "timesheet_action_transition",
              c ->
                  act(
                      c,
                      w,
                      t,
                      new Act(
                          UUID.randomUUID(),
                          4,
                          "SUBMITTED",
                          2,
                          null,
                          1,
                          2,
                          DIGEST,
                          470,
                          w.manager(),
                          null,
                          LATER)));
          Act second =
              new Act(
                  UUID.randomUUID(),
                  4,
                  "SUBMITTED",
                  2,
                  null,
                  2,
                  2,
                  DIGEST2,
                  470,
                  w.manager(),
                  null,
                  LATER);
          committed(db, c -> act(c, w, t, second));
          // Submitted time is never refreshed.
          refused(
              db,
              "timesheet_generation_locked",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_generation SET version = 3, refreshed_at = ?,"
                          + " refreshed_by = 'hr' WHERE tenant_id = ?",
                      LATER,
                      w.tenant()));
          Act reapproved = decided(w, "APPROVED", 5, second, null, LATER);
          committed(db, c -> act(c, w, t, reapproved));
          // Approved time is never revised; its week may still be refreshed (it stays locked).
          refused(
              db,
              "timesheet_guard",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet SET current_revision = 3 WHERE tenant_id = ?",
                      w.tenant()));
          committed(
              db,
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_generation SET version = 3, refreshed_at = ?,"
                          + " refreshed_by = 'hr' WHERE tenant_id = ?",
                      LATER,
                      w.tenant()));
          assertThat(
                  db.jdbc()
                      .queryForList(
                          "SELECT action || ':' || cycle || ':' || revision || ':v'"
                              + " || generation_version FROM people.timesheet_action"
                              + " WHERE tenant_id = ? ORDER BY sequence",
                          String.class,
                          w.tenant()))
              .containsExactly(
                  "SUBMITTED:1:1:v1",
                  "APPROVED:1:1:v1",
                  "REOPENED:1:1:v1",
                  "SUBMITTED:2:2:v2",
                  "APPROVED:2:2:v2");
          // The first approval and its submission never changed.
          assertThat(
                  db.jdbc()
                      .queryForList(
                          "SELECT * FROM people.timesheet_action ORDER BY sequence LIMIT 2"))
              .isEqualTo(approvedEvidence);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Query plans
  // ------------------------------------------------------------------------------------------

  @Test
  void theReadsACorrectionAddsUseTheirKeys() {
    withDatabase(
        db -> {
          migrate(db.url(), "34");
          try (Connection c = connect(db)) {
            exec(c, "SET LOCAL session_replication_role = replica");
            exec(
                c,
                "INSERT INTO people.timesheet (tenant_id, id, generation_id, employee_id,"
                    + " site_id, week_start, status, current_revision, row_count, ready_rows,"
                    + " missing_rows, open_rows, changed_rows, worked_minutes, created_at,"
                    + " updated_at, submission_cycle, current_action_id)"
                    + " SELECT ('00000000-0000-4000-8000-' || lpad((g % 20)::text, 12,"
                    + " '0'))::uuid, gen_random_uuid(), gen_random_uuid(),"
                    + " ('00000000-0000-4000-9000-' || lpad((g % 4000)::text, 12, '0'))::uuid,"
                    + " gen_random_uuid(), DATE '2024-01-01' + 7 * (g / 4000), CASE WHEN g % 3"
                    + " = 0 THEN 'APPROVED' ELSE 'DRAFT' END, 1, 1, 1, 0, 0, 0, 480, now(),"
                    + " now(), CASE WHEN g % 3 = 0 THEN 1 ELSE 0 END, CASE WHEN g % 3 = 0 THEN"
                    + " gen_random_uuid() END FROM generate_series(1, 40000) g");
            exec(
                c,
                "INSERT INTO people.timesheet_generation (tenant_id, id, site_id, week_start,"
                    + " week_end, site_timezone, period_start_at, period_end_at, version, status,"
                    + " generated_at, generated_by) SELECT tenant_id, generation_id, site_id,"
                    + " week_start, week_start + 7, 'Africa/Kinshasa', week_start::timestamptz,"
                    + " (week_start + 7)::timestamptz, 1, 'DRAFT', now(), 'test'"
                    + " FROM people.timesheet");
            exec(
                c,
                "INSERT INTO people.timesheet_action (tenant_id, id, timesheet_id, sequence,"
                    + " action, cycle, submission_action_id, revision, generation_version,"
                    + " source_digest, row_count, ready_rows, missing_rows, open_rows,"
                    + " changed_rows, worked_minutes, employee_id, site_id, employment_id,"
                    + " manager_employee_id, actor_subject, occurred_at, superseded_action_id,"
                    + " after_source_digest, correction_id) SELECT t.tenant_id,"
                    + " gen_random_uuid(), t.id, k, CASE WHEN k = 3 THEN 'REOPENED' WHEN k = 2"
                    + " THEN 'APPROVED' ELSE 'SUBMITTED' END, 1, CASE WHEN k = 2 THEN"
                    + " gen_random_uuid() END, 1, 1, repeat('a', 64), 1, 1, 0, 0, 0, 480,"
                    + " t.employee_id, t.site_id, gen_random_uuid(), gen_random_uuid(), 'test',"
                    + " now(), CASE WHEN k = 3 THEN gen_random_uuid() END, CASE WHEN k = 3 THEN"
                    + " repeat('b', 64) END, CASE WHEN k = 3 THEN gen_random_uuid() END"
                    + " FROM people.timesheet t CROSS JOIN generate_series(1, 3) k"
                    + " WHERE t.status = 'APPROVED'");
            for (String table : List.of("timesheet", "timesheet_generation", "timesheet_action")) {
              exec(c, "ANALYZE people." + table);
            }
            List<String> plans = new ArrayList<>();
            for (String sql :
                List.of(
                    // The approved timesheets of an employee whose week contains an instant.
                    "SELECT t.id FROM people.timesheet t JOIN people.timesheet_generation g ON"
                        + " g.tenant_id = t.tenant_id AND g.id = t.generation_id WHERE"
                        + " t.tenant_id = ? AND t.employee_id = ? AND t.status = 'APPROVED'"
                        + " AND g.period_start_at <= ? AND g.period_end_at > ? ORDER BY t.id",
                    // The reopenings of a correction.
                    "SELECT a.id FROM people.timesheet_action a WHERE a.tenant_id = ?"
                        + " AND a.correction_id = ?")) {
              StringBuilder plan = new StringBuilder();
              try (PreparedStatement explain = c.prepareStatement("EXPLAIN " + sql)) {
                UUID tenant = UUID.fromString("00000000-0000-4000-8000-000000000007");
                explain.setObject(1, tenant);
                if (sql.contains("employee_id")) {
                  explain.setObject(2, UUID.fromString("00000000-0000-4000-9000-000000000407"));
                  explain.setObject(3, AT);
                  explain.setObject(4, AT);
                } else {
                  explain.setObject(2, UUID.randomUUID());
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
            assertThat(plans.get(0)).contains("timesheet_employee_week");
            assertThat(plans.get(1)).contains("timesheet_action_correction_idx");
            for (String plan : plans) {
              assertThat(plan).doesNotContain("Seq Scan on timesheet ");
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
