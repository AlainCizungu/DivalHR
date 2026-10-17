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
 * MVP-071 V36 directly against PostgreSQL, bypassing the application (the database is the final
 * authority; L71-8, L71-11 items 16 and 17): the refusing rollback that restores V35 exactly; the
 * transition order and monotonic version of the period actions; a LOCKED action bound to a fully
 * approved current generation with exactly its counts; an UNLOCKED action bound to the current lock
 * with a closed reason and a valid explanation; the projection consistent with the latest action at
 * commit; append-only evidence; the guards that hold every timesheet, action, refresh and
 * correction write of a locked week; and the plans of the lock reads at representative volume.
 */
@IntegrationTest
class TimesheetPeriodMigrationIntegrationTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final String KINSHASA = "Africa/Kinshasa";
  private static final Timestamp EARLY = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
  private static final Timestamp AT = Timestamp.from(Instant.parse("2026-11-08T11:00:00Z"));
  private static final Timestamp LATER = Timestamp.from(Instant.parse("2026-11-09T09:00:00Z"));
  private static final Timestamp EVEN_LATER = Timestamp.from(Instant.parse("2026-11-09T10:00:00Z"));
  private static final String DIGEST = "a".repeat(64);
  private static final String EVIDENCE = "e".repeat(64);
  private static final String EXPLANATION = "Pointage du lundi à corriger.";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  /** A tenant with Ana (managed by Mia) covering one 08-16 shift on Monday, checked in. */
  private record World(
      UUID tenant,
      UUID entity,
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
    String database = "period36_" + UUID.randomUUID().toString().replace("-", "");
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

  /** Runs the work and rolls it back: it must be accepted by every trigger and constraint. */
  private void accepted(Db db, SqlWork work) {
    try (Connection c = connect(db)) {
      work.run(c);
      c.rollback();
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
  // Fixtures (as the V35 migration test's)
  // ------------------------------------------------------------------------------------------

  private World world(Db db) {
    UUID tenant = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
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
        tenant,
        entity,
        site,
        employee,
        employment,
        manager,
        schedule,
        slot,
        ts(start),
        ts(end),
        record);
  }

  /** The week's generation at version 1 (at {@code AT}) and Ana's draft with one READY row. */
  private static void draft(Connection c, World w, UUID site, UUID generation, UUID timesheet)
      throws SQLException {
    exec(
        c,
        "INSERT INTO people.timesheet_generation (tenant_id, id, site_id, week_start, week_end,"
            + " site_timezone, period_start_at, period_end_at, version, status, generated_at,"
            + " generated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'DRAFT', ?, 'admin')",
        w.tenant(),
        generation,
        site,
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
            + " VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', 1, 1, 1, 0, 0, 0, 470, ?, ?)",
        w.tenant(),
        timesheet,
        generation,
        w.employee(),
        site,
        MONDAY,
        AT,
        AT);
    exec(
        c,
        "INSERT INTO people.timesheet_revision (tenant_id, timesheet_id, revision,"
            + " generation_version, source_digest, row_count, ready_rows, missing_rows,"
            + " open_rows, changed_rows, worked_minutes, created_at)"
            + " VALUES (?, ?, 1, 1, ?, 1, 1, 0, 0, 0, 470, ?)",
        w.tenant(),
        timesheet,
        DIGEST,
        AT);
    exec(
        c,
        "INSERT INTO people.timesheet_row (tenant_id, timesheet_id, revision, slot_id, schedule_id,"
            + " employee_id, employment_id, site_id, label_en, label_fr, scheduled_start_at,"
            + " scheduled_end_at, attendance_record_id, checked_in_at, checked_out_at,"
            + " correction_version, state, worked_minutes) VALUES (?, ?, 1, ?, ?, ?, ?, ?,"
            + " 'Cashier', 'Caissière', ?, ?, ?, ?, ?, 0, 'READY', 470)",
        w.tenant(),
        timesheet,
        w.slot(),
        w.schedule(),
        w.employee(),
        w.employment(),
        site,
        w.start(),
        w.end(),
        w.record(),
        plus(w.start(), Duration.ofMinutes(5)),
        plus(w.start(), Duration.ofMinutes(475)));
  }

  /** A submission or decision action of the timesheet's cycle 1, revision 1 (470 minutes). */
  private static void action(
      Connection c,
      World w,
      UUID timesheet,
      UUID id,
      int sequence,
      String kind,
      UUID submission,
      Timestamp at)
      throws SQLException {
    exec(
        c,
        "INSERT INTO people.timesheet_action (tenant_id, id, timesheet_id, sequence, action, cycle,"
            + " submission_action_id, revision, generation_version, source_digest, row_count,"
            + " ready_rows, missing_rows, open_rows, changed_rows, worked_minutes, employee_id,"
            + " site_id, employment_id, manager_employee_id, actor_subject, occurred_at)"
            + " VALUES (?, ?, ?, ?, ?, 1, ?, 1, 1, ?, 1, 1, 0, 0, 0, 470, ?, ?, ?, ?, 'sub-1', ?)",
        w.tenant(),
        id,
        timesheet,
        sequence,
        kind,
        submission,
        DIGEST,
        w.employee(),
        w.site(),
        w.employment(),
        w.manager(),
        at);
    exec(
        c,
        "UPDATE people.timesheet SET status = ?, submission_cycle = 1, current_action_id = ?"
            + " WHERE tenant_id = ? AND id = ?",
        kind,
        id,
        w.tenant(),
        timesheet);
  }

  /** Ana's week generated, submitted and approved by Mia (cycle 1); the approval's id. */
  private UUID approved(Db db, World w, UUID g, UUID t) {
    UUID submission = UUID.randomUUID();
    UUID approval = UUID.randomUUID();
    committed(
        db,
        c -> {
          draft(c, w, w.site(), g, t);
          action(c, w, t, submission, 1, "SUBMITTED", null, AT);
          action(c, w, t, approval, 2, "APPROVED", submission, AT);
        });
    return approval;
  }

  // ------------------------------------------------------------------------------------------
  // V36 fixtures
  // ------------------------------------------------------------------------------------------

  /** A period action's facts. */
  private record Period(
      UUID id,
      UUID site,
      UUID generation,
      int sequence,
      String action,
      UUID legalEntity,
      LocalDate weekEnd,
      int generationVersion,
      int timesheets,
      int employees,
      int rows,
      int minutes,
      String evidence,
      UUID locked,
      String reason,
      String explanation,
      Timestamp at) {}

  private static Period lock(World w, UUID generation, int sequence, Timestamp at) {
    return lockAt(w, w.site(), generation, sequence, at);
  }

  private static Period lockAt(World w, UUID site, UUID generation, int sequence, Timestamp at) {
    return new Period(
        UUID.randomUUID(),
        site,
        generation,
        sequence,
        "LOCKED",
        w.entity(),
        MONDAY.plusDays(7),
        1,
        1,
        1,
        1,
        470,
        EVIDENCE,
        null,
        null,
        null,
        at);
  }

  private static Period unlock(World w, Period locked, int sequence, Timestamp at) {
    return new Period(
        UUID.randomUUID(),
        locked.site(),
        locked.generation(),
        sequence,
        "UNLOCKED",
        w.entity(),
        MONDAY.plusDays(7),
        1,
        locked.timesheets(),
        locked.employees(),
        locked.rows(),
        locked.minutes(),
        locked.evidence(),
        locked.id(),
        "ATTENDANCE_CORRECTION_REQUIRED",
        EXPLANATION,
        at);
  }

  private static void insert(Connection c, World w, Period p) throws SQLException {
    exec(
        c,
        "INSERT INTO people.timesheet_period_action (tenant_id, id, generation_id, sequence,"
            + " action, legal_entity_id, site_id, week_start, week_end, site_timezone,"
            + " period_start_at, period_end_at, generation_version, timesheet_count,"
            + " employee_count, row_count, worked_minutes, evidence_digest, locked_action_id,"
            + " unlock_reason, unlock_explanation, actor_subject, occurred_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'hr', ?)",
        w.tenant(),
        p.id(),
        p.generation(),
        p.sequence(),
        p.action(),
        p.legalEntity(),
        p.site(),
        MONDAY,
        p.weekEnd(),
        KINSHASA,
        ts(MONDAY.atStartOfDay()),
        ts(MONDAY.plusDays(7).atStartOfDay()),
        p.generationVersion(),
        p.timesheets(),
        p.employees(),
        p.rows(),
        p.minutes(),
        p.evidence(),
        p.locked(),
        p.reason(),
        p.explanation(),
        p.at());
  }

  /** The projection as the action leaves it (inserted at version 1, updated afterwards). */
  private static void project(Connection c, World w, Period p) throws SQLException {
    String state = "LOCKED".equals(p.action()) ? "LOCKED" : "OPEN";
    if (p.sequence() == 1) {
      exec(
          c,
          "INSERT INTO people.timesheet_period_lock (tenant_id, generation_id, site_id, week_start,"
              + " state, version, current_action_id, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
          w.tenant(),
          p.generation(),
          p.site(),
          MONDAY,
          state,
          p.id(),
          p.at());
    } else {
      exec(
          c,
          "UPDATE people.timesheet_period_lock SET state = ?, version = ?, current_action_id = ?,"
              + " updated_at = ? WHERE tenant_id = ? AND generation_id = ?",
          state,
          p.sequence(),
          p.id(),
          p.at(),
          w.tenant(),
          p.generation());
    }
  }

  /** A transition: the action and its projection. */
  private static void transition(Connection c, World w, Period p) throws SQLException {
    insert(c, w, p);
    project(c, w, p);
  }

  /** The valid lock of a fully approved week. */
  private Period locked(Db db, World w, UUID g) {
    Period p = lock(w, g, 1, LATER);
    committed(db, c -> transition(c, w, p));
    return p;
  }

  // ------------------------------------------------------------------------------------------
  // 16: rollback and upgrade
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV35ExactlyAndRefusesLockEvidence() {
    withDatabase(
        db -> {
          migrate(db.url(), "35");
          World w = world(db);
          UUID g = UUID.randomUUID();
          UUID t = UUID.randomUUID();
          approved(db, w, g, t);
          String v35 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          migrate(db.url(), "36");
          String v36 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          assertThat(v36)
              .isNotEqualTo(v35)
              .contains("timesheet_period_lock")
              .contains("timesheet_period_action_history")
              .contains("timesheet_period_action_transition")
              .contains("timesheet_period_guard_correction");
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V36__rollback.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v35);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.timesheet_action")).isEqualTo(2);

          db.jdbc().execute(resource("db/migration/V36__timesheet_period_lock.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v36);
          // Once a week was locked, the rollback refuses and changes nothing.
          locked(db, w, g);
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V36__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V36 rollback refused: timesheet period lock evidence exists");
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v36);
          assertThat(count(db, "SELECT count(*) FROM people.timesheet_period_action")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM people.timesheet_period_lock")).isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // 16: transitions, binding, immutability and the guards by direct SQL
  // ------------------------------------------------------------------------------------------

  @Test
  void periodActionsFollowTheLifecycleBindApprovedEvidenceAndHoldTheLockedWeek() {
    withDatabase(
        db -> {
          migrate(db.url(), "36");
          World w = world(db);
          UUID g = UUID.randomUUID();
          UUID t = UUID.randomUUID();
          UUID approval = approved(db, w, g, t);
          Period ok = lock(w, g, 1, LATER);

          // The first action is LOCKED at sequence 1.
          refused(
              db, "timesheet_period_action_sequence", c -> transition(c, w, lock(w, g, 2, LATER)));
          refused(
              db,
              "timesheet_period_action_sequence",
              c -> insert(c, w, withAction(ok, "UNLOCKED")));
          // The exact scope and the current generation version.
          refused(
              db,
              "timesheet_period_action_scope",
              c -> insert(c, w, withWeekEnd(ok, MONDAY.plusDays(6))));
          refused(
              db, "timesheet_period_action_scope", c -> insert(c, w, withGenerationVersion(ok, 2)));
          refused(
              db,
              "timesheet_period_action_scope",
              c -> insert(c, w, withLegalEntity(ok, UUID.randomUUID())));
          // A lock binds a fully approved generation with exactly its counts.
          refused(
              db, "timesheet_period_action_approved", c -> insert(c, w, withCounts(ok, 2, 2, 2)));
          refused(db, "timesheet_period_action_approved", c -> insert(c, w, withMinutes(ok, 471)));
          refused(
              db, "timesheet_period_action_approved", c -> insert(c, w, withCounts(ok, 1, 2, 1)));
          refused(db, "timesheet_period_action_digest", c -> insert(c, w, withEvidence(ok, "x")));
          // A week whose timesheet is not APPROVED now (a draft of another site's week, or an
          // approval that is not the current action) never locks.
          UUID site2 = UUID.randomUUID();
          UUID g2 = UUID.randomUUID();
          committed(
              db,
              c -> {
                exec(
                    c,
                    "INSERT INTO tenant.site (id, tenant_id, legal_entity_id, code, name,"
                        + " timezone, effective_from, created_at, created_by) VALUES (?, ?, ?,"
                        + " 'S2', 'Site 2', ?, DATE '2026-01-01', now(), 'test')",
                    site2,
                    w.tenant(),
                    w.entity(),
                    KINSHASA);
                draft(c, w, site2, g2, UUID.randomUUID());
              });
          refused(
              db,
              "timesheet_period_action_approved",
              c -> transition(c, w, lockAt(w, site2, g2, 1, LATER)));
          refused(
              db,
              "timesheet_period_action_approved",
              c -> {
                // The approval superseded by a REOPENED action is not current.
                exec(
                    c,
                    "INSERT INTO people.timesheet_action (tenant_id, id, timesheet_id, sequence,"
                        + " action, cycle, submission_action_id, revision, generation_version,"
                        + " source_digest, row_count, ready_rows, missing_rows, open_rows,"
                        + " changed_rows, worked_minutes, employee_id, site_id, employment_id,"
                        + " manager_employee_id, actor_subject, occurred_at, superseded_action_id,"
                        + " after_source_digest, correction_id) VALUES (?, ?, ?, 3, 'REOPENED', 1,"
                        + " NULL, 1, 1, ?, 1, 1, 0, 0, 0, 470, ?, ?, ?, ?, 'hr', ?, ?, ?, ?)",
                    w.tenant(),
                    UUID.randomUUID(),
                    t,
                    DIGEST,
                    w.employee(),
                    w.site(),
                    w.employment(),
                    w.manager(),
                    LATER,
                    approval,
                    "b".repeat(64),
                    correction(c, w, 1));
                exec(
                    c,
                    "UPDATE people.timesheet SET status = 'REOPENED', current_action_id = (SELECT"
                        + " id FROM people.timesheet_action WHERE timesheet_id = ? ORDER BY"
                        + " sequence DESC LIMIT 1) WHERE tenant_id = ? AND id = ?",
                    t,
                    w.tenant(),
                    t);
                transition(c, w, ok);
              });
          // The action without its projection, or a projection that does not show it, never
          // commits.
          refused(db, "timesheet_period_lock_current", c -> insert(c, w, ok));
          refused(
              db,
              "timesheet_period_lock_guard",
              c -> {
                insert(c, w, ok);
                exec(
                    c,
                    "INSERT INTO people.timesheet_period_lock (tenant_id, generation_id, site_id,"
                        + " week_start, state, version, current_action_id, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'OPEN', 1, ?, ?)",
                    w.tenant(),
                    g,
                    w.site(),
                    MONDAY,
                    ok.id(),
                    LATER);
              });
          refused(
              db,
              "timesheet_period_lock_guard",
              c -> {
                insert(c, w, ok);
                exec(
                    c,
                    "INSERT INTO people.timesheet_period_lock (tenant_id, generation_id, site_id,"
                        + " week_start, state, version, current_action_id, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'LOCKED', 1, ?, ?)",
                    w.tenant(),
                    g,
                    w.site(),
                    MONDAY,
                    ok.id(),
                    EVEN_LATER);
              });

          // Valid: the lock.
          committed(db, c -> transition(c, w, ok));
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT state FROM people.timesheet_period_lock WHERE generation_id = ?",
                          String.class,
                          g))
              .isEqualTo("LOCKED");

          // While locked: no timesheet change, no action, no refresh, no correction of the week.
          refused(
              db,
              "timesheet_period_locked",
              c -> {
                onlyGuard(c, "people.timesheet", "timesheet_period_guard_timesheet");
                exec(
                    c,
                    "UPDATE people.timesheet SET updated_at = ? WHERE tenant_id = ? AND id = ?",
                    EVEN_LATER,
                    w.tenant(),
                    t);
              });
          refused(
              db,
              "timesheet_period_locked",
              c -> {
                onlyGuard(c, "people.timesheet_action", "timesheet_period_guard_action");
                action(c, w, t, UUID.randomUUID(), 3, "REOPENED", null, EVEN_LATER);
              });
          refused(
              db,
              "timesheet_period_locked",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_generation SET version = 2, refreshed_at = ?"
                          + " WHERE tenant_id = ? AND id = ?",
                      EVEN_LATER,
                      w.tenant(),
                      g));
          refused(db, "timesheet_period_locked", c -> guardedCorrection(c, w, thisWeek(w)));
          // A shift of another week of the site is not its concern.
          accepted(db, c -> guardedCorrection(c, w, nextWeek(c, w)));

          // The lock again is not a transition; a second LOCKED never follows a LOCKED.
          refused(
              db, "timesheet_period_action_sequence", c -> transition(c, w, lock(w, g, 2, LATER)));
          refused(
              db,
              "timesheet_period_action_sequence",
              c -> transition(c, w, withSequence(lock(w, g, 3, LATER), 3)));
          // An unlock reverses the current lock with its evidence, a closed reason and a valid
          // explanation.
          Period back = unlock(w, ok, 2, EVEN_LATER);
          refused(
              db, "timesheet_period_action_unlock", c -> transition(c, w, withMinutes(back, 469)));
          refused(
              db,
              "timesheet_period_action_unlock",
              c -> transition(c, w, withEvidence(back, "f".repeat(64))));
          refused(
              db,
              "timesheet_period_action_unlock",
              c -> transition(c, w, withLocked(back, UUID.randomUUID())));
          refused(
              db,
              "timesheet_period_action_reason",
              c -> transition(c, w, withReason(back, "FRAUD")));
          refused(
              db,
              "timesheet_period_action_explanation",
              c -> transition(c, w, withExplanation(back, "Court.")));
          refused(
              db,
              "timesheet_period_action_explanation",
              c -> transition(c, w, withExplanation(back, "Voir https://exemple.test/x svp")));
          refused(
              db, "timesheet_period_action_shape", c -> transition(c, w, withReason(back, null)));
          refused(
              db,
              "timesheet_period_action_sequence",
              c ->
                  transition(
                      c, w, withAt(back, Timestamp.from(LATER.toInstant().minusSeconds(1)))));
          // Append-only evidence; the projection is never deleted.
          refused(
              db,
              "timesheet_period_action_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_period_action SET actor_subject = 'x' WHERE id = ?",
                      ok.id()));
          refused(
              db,
              "timesheet_period_action_immutable",
              c -> exec(c, "DELETE FROM people.timesheet_period_action WHERE id = ?", ok.id()));
          refused(
              db,
              "timesheet_period_action_no_truncate",
              c -> exec(c, "TRUNCATE people.timesheet_period_action CASCADE"));
          refused(
              db,
              "timesheet_period_lock_immutable",
              c -> exec(c, "DELETE FROM people.timesheet_period_lock WHERE generation_id = ?", g));
          refused(
              db,
              "timesheet_period_lock_no_truncate",
              c -> exec(c, "TRUNCATE people.timesheet_period_lock"));
          refused(
              db,
              "timesheet_period_lock_guard",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_period_lock SET state = 'OPEN', version = 2"
                          + " WHERE generation_id = ?",
                      g));

          // Valid: the unlock; the week changes again; the relock follows.
          committed(db, c -> transition(c, w, back));
          accepted(
              db,
              c -> {
                onlyGuard(c, "people.timesheet", "timesheet_period_guard_timesheet");
                exec(
                    c,
                    "UPDATE people.timesheet SET updated_at = ? WHERE tenant_id = ? AND id = ?",
                    EVEN_LATER,
                    w.tenant(),
                    t);
              });
          accepted(db, c -> guardedCorrection(c, w, thisWeek(w)));
          Period again = lock(w, g, 3, EVEN_LATER);
          committed(db, c -> transition(c, w, again));
          assertThat(
                  db.jdbc()
                      .queryForList(
                          "SELECT action FROM people.timesheet_period_action WHERE generation_id"
                              + " = ? ORDER BY sequence",
                          String.class,
                          g))
              .containsExactly("LOCKED", "UNLOCKED", "LOCKED");
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT version FROM people.timesheet_period_lock WHERE generation_id"
                              + " = ?",
                          Integer.class,
                          g))
              .isEqualTo(3);
        });
  }

  /** A shift of Ana's with its checked-in record. */
  private record Shift(UUID schedule, UUID slot, UUID record, Timestamp start, Timestamp end) {}

  private static Shift thisWeek(World w) {
    return new Shift(w.schedule(), w.slot(), w.record(), w.start(), w.end());
  }

  /** Ana's same shift the following Monday (another generated week of the site), checked in. */
  private static Shift nextWeek(Connection c, World w) throws SQLException {
    UUID schedule = UUID.randomUUID();
    UUID slot = UUID.randomUUID();
    UUID record = UUID.randomUUID();
    UUID event = UUID.randomUUID();
    LocalDate monday = MONDAY.plusDays(7);
    Timestamp start = ts(monday.atTime(8, 0));
    Timestamp end = ts(monday.atTime(16, 0));
    exec(
        c,
        "INSERT INTO people.work_schedule (id, tenant_id, site_id, first_date, last_date,"
            + " site_timezone, slot_count, assigned_count, published_at, published_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?, 'test')",
        schedule,
        w.tenant(),
        w.site(),
        monday,
        monday,
        KINSHASA,
        EARLY);
    exec(
        c,
        "INSERT INTO people.work_schedule_slot (id, tenant_id, schedule_id, position,"
            + " start_local, end_local, start_at, end_at, label_en, label_fr,"
            + " assigned_employee_id) VALUES (?, ?, ?, 1, ?, ?, ?, ?, 'Cashier', 'Caissière', ?)",
        slot,
        w.tenant(),
        schedule,
        monday.atTime(8, 0),
        monday.atTime(16, 0),
        start,
        end,
        w.employee());
    exec(
        c,
        "INSERT INTO people.work_schedule_site_coverage (tenant_id, schedule_id, site_id,"
            + " first_date, last_date) VALUES (?, ?, ?, ?, ?)",
        w.tenant(),
        schedule,
        w.site(),
        monday,
        monday);
    exec(
        c,
        "INSERT INTO people.work_schedule_slot_coverage (tenant_id, slot_id, schedule_id,"
            + " employee_id, start_at, end_at) VALUES (?, ?, ?, ?, ?, ?)",
        w.tenant(),
        slot,
        schedule,
        w.employee(),
        start,
        end);
    exec(
        c,
        "INSERT INTO people.attendance_event (tenant_id, id, record_id, schedule_id, slot_id,"
            + " employee_id, employment_id, event_type, recorded_at, recorded_by,"
            + " idempotency_digest, recording_method) VALUES (?, ?, ?, ?, ?, ?, ?, 'CHECKED_IN',"
            + " ?, 'test', ?, 'SELF')",
        w.tenant(),
        event,
        record,
        schedule,
        slot,
        w.employee(),
        w.employment(),
        start,
        "d".repeat(64));
    exec(
        c,
        "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id, employee_id,"
            + " employment_id, slot_start_at, slot_end_at, check_out_until, check_in_event_id,"
            + " checked_in_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        w.tenant(),
        record,
        schedule,
        slot,
        w.employee(),
        w.employment(),
        start,
        end,
        plus(end, Duration.ofHours(24)),
        event,
        start);
    return new Shift(schedule, slot, record, start, end);
  }

  /**
   * Sets every other trigger of the table aside inside the transaction, so that only V36's guard
   * answers (ALTER TABLE is transactional: the rollback restores them).
   */
  private static void onlyGuard(Connection c, String table, String guard) throws SQLException {
    List<String> others = new ArrayList<>();
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT tgname FROM pg_trigger WHERE tgrelid = ?::regclass AND NOT tgisinternal"
                + " AND tgname <> ?")) {
      s.setString(1, table);
      s.setString(2, guard);
      try (var rows = s.executeQuery()) {
        while (rows.next()) {
          others.add(rows.getString(1));
        }
      }
    }
    for (String trigger : others) {
      exec(c, "ALTER TABLE " + table + " DISABLE TRIGGER " + trigger);
    }
  }

  /**
   * A correction of one of Ana's shifts with every other trigger of the table set aside, so that
   * only V36's guard answers.
   */
  private static void guardedCorrection(Connection c, World w, Shift shift) throws SQLException {
    onlyGuard(c, "people.attendance_correction", "timesheet_period_guard_correction");
    exec(
        c,
        "INSERT INTO people.attendance_correction (tenant_id, id, record_id, version, operation,"
            + " schedule_id, slot_id, employee_id, employment_id, slot_start_at, slot_end_at,"
            + " before_checked_in_at, before_checked_out_at, after_checked_in_at,"
            + " after_checked_out_at, reason_code, explanation, corrected_by, preview_digest,"
            + " idempotency_digest, corrected_at) VALUES (?, ?, ?, 1, 'REPLACE_TIMES', ?, ?, ?, ?,"
            + " ?, ?, ?, NULL, ?, ?, 'DEVICE_OR_CONNECTIVITY', 'Lecteur hors service ici.', 'hr',"
            + " ?, ?, ?)",
        w.tenant(),
        UUID.randomUUID(),
        shift.record(),
        shift.schedule(),
        shift.slot(),
        w.employee(),
        w.employment(),
        shift.start(),
        shift.end(),
        shift.start(),
        plus(shift.start(), Duration.ofMinutes(20)),
        shift.end(),
        DIGEST,
        DIGEST,
        plus(shift.end(), Duration.ofDays(2)));
  }

  /** A committed correction row (its identity only; triggers set aside). */
  private static UUID correction(Connection c, World w, int version) throws SQLException {
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
        w.employee(),
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

  private static Period withAction(Period p, String action) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        p.sequence(),
        action,
        p.legalEntity(),
        p.weekEnd(),
        p.generationVersion(),
        p.timesheets(),
        p.employees(),
        p.rows(),
        p.minutes(),
        p.evidence(),
        p.locked(),
        p.reason(),
        p.explanation(),
        p.at());
  }

  private static Period withWeekEnd(Period p, LocalDate weekEnd) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        p.sequence(),
        p.action(),
        p.legalEntity(),
        weekEnd,
        p.generationVersion(),
        p.timesheets(),
        p.employees(),
        p.rows(),
        p.minutes(),
        p.evidence(),
        p.locked(),
        p.reason(),
        p.explanation(),
        p.at());
  }

  private static Period withGenerationVersion(Period p, int version) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        p.sequence(),
        p.action(),
        p.legalEntity(),
        p.weekEnd(),
        version,
        p.timesheets(),
        p.employees(),
        p.rows(),
        p.minutes(),
        p.evidence(),
        p.locked(),
        p.reason(),
        p.explanation(),
        p.at());
  }

  private static Period withLegalEntity(Period p, UUID legalEntity) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        p.sequence(),
        p.action(),
        legalEntity,
        p.weekEnd(),
        p.generationVersion(),
        p.timesheets(),
        p.employees(),
        p.rows(),
        p.minutes(),
        p.evidence(),
        p.locked(),
        p.reason(),
        p.explanation(),
        p.at());
  }

  private static Period withCounts(Period p, int timesheets, int employees, int rows) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        p.sequence(),
        p.action(),
        p.legalEntity(),
        p.weekEnd(),
        p.generationVersion(),
        timesheets,
        employees,
        rows,
        p.minutes(),
        p.evidence(),
        p.locked(),
        p.reason(),
        p.explanation(),
        p.at());
  }

  private static Period withMinutes(Period p, int minutes) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        p.sequence(),
        p.action(),
        p.legalEntity(),
        p.weekEnd(),
        p.generationVersion(),
        p.timesheets(),
        p.employees(),
        p.rows(),
        minutes,
        p.evidence(),
        p.locked(),
        p.reason(),
        p.explanation(),
        p.at());
  }

  private static Period withEvidence(Period p, String evidence) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        p.sequence(),
        p.action(),
        p.legalEntity(),
        p.weekEnd(),
        p.generationVersion(),
        p.timesheets(),
        p.employees(),
        p.rows(),
        p.minutes(),
        evidence,
        p.locked(),
        p.reason(),
        p.explanation(),
        p.at());
  }

  private static Period withLocked(Period p, UUID locked) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        p.sequence(),
        p.action(),
        p.legalEntity(),
        p.weekEnd(),
        p.generationVersion(),
        p.timesheets(),
        p.employees(),
        p.rows(),
        p.minutes(),
        p.evidence(),
        locked,
        p.reason(),
        p.explanation(),
        p.at());
  }

  private static Period withReason(Period p, String reason) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        p.sequence(),
        p.action(),
        p.legalEntity(),
        p.weekEnd(),
        p.generationVersion(),
        p.timesheets(),
        p.employees(),
        p.rows(),
        p.minutes(),
        p.evidence(),
        p.locked(),
        reason,
        p.explanation(),
        p.at());
  }

  private static Period withExplanation(Period p, String explanation) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        p.sequence(),
        p.action(),
        p.legalEntity(),
        p.weekEnd(),
        p.generationVersion(),
        p.timesheets(),
        p.employees(),
        p.rows(),
        p.minutes(),
        p.evidence(),
        p.locked(),
        p.reason(),
        explanation,
        p.at());
  }

  private static Period withSequence(Period p, int sequence) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        sequence,
        p.action(),
        p.legalEntity(),
        p.weekEnd(),
        p.generationVersion(),
        p.timesheets(),
        p.employees(),
        p.rows(),
        p.minutes(),
        p.evidence(),
        p.locked(),
        p.reason(),
        p.explanation(),
        p.at());
  }

  private static Period withAt(Period p, Timestamp at) {
    return new Period(
        p.id(),
        p.site(),
        p.generation(),
        p.sequence(),
        p.action(),
        p.legalEntity(),
        p.weekEnd(),
        p.generationVersion(),
        p.timesheets(),
        p.employees(),
        p.rows(),
        p.minutes(),
        p.evidence(),
        p.locked(),
        p.reason(),
        p.explanation(),
        at);
  }

  // ------------------------------------------------------------------------------------------
  // 17: the lock reads use their keys at representative volume
  // ------------------------------------------------------------------------------------------

  @Test
  void theLockReadsUseTheirKeys() {
    withDatabase(
        db -> {
          migrate(db.url(), "36");
          try (Connection c = connect(db)) {
            exec(c, "SET LOCAL session_replication_role = replica");
            // 40,000 timesheets in 1,600 generations (25 each) of 200 sites of 20 tenants.
            exec(
                c,
                "INSERT INTO people.timesheet (tenant_id, id, generation_id, employee_id,"
                    + " site_id, week_start, status, current_revision, row_count, ready_rows,"
                    + " missing_rows, open_rows, changed_rows, worked_minutes, created_at,"
                    + " updated_at, submission_cycle, current_action_id)"
                    + " SELECT ('00000000-0000-4000-8000-' || lpad(((g / 25) % 20)::text, 12,"
                    + " '0'))::uuid, gen_random_uuid(),"
                    + " ('00000000-0000-4000-a000-' || lpad((g / 25)::text, 12, '0'))::uuid,"
                    + " gen_random_uuid(), ('00000000-0000-4000-b000-' || lpad(((g / 25) %"
                    + " 200)::text, 12, '0'))::uuid, DATE '2024-01-01' + 7 * ((g / 25) / 200),"
                    + " 'APPROVED', 1, 1, 1, 0, 0, 0, 480, now(), now(), 1, gen_random_uuid()"
                    + " FROM generate_series(1, 40000) g");
            exec(
                c,
                "INSERT INTO people.timesheet_generation (tenant_id, id, site_id, week_start,"
                    + " week_end, site_timezone, period_start_at, period_end_at, version, status,"
                    + " generated_at, generated_by) SELECT DISTINCT ON (generation_id) tenant_id,"
                    + " generation_id, site_id, week_start, week_start + 7, 'Africa/Kinshasa',"
                    + " week_start::timestamptz, (week_start + 7)::timestamptz, 1, 'DRAFT', now(),"
                    + " 'test' FROM people.timesheet");
            exec(
                c,
                "INSERT INTO people.timesheet_action (tenant_id, id, timesheet_id, sequence,"
                    + " action, cycle, submission_action_id, revision, generation_version,"
                    + " source_digest, row_count, ready_rows, missing_rows, open_rows,"
                    + " changed_rows, worked_minutes, employee_id, site_id, employment_id,"
                    + " manager_employee_id, actor_subject, occurred_at) SELECT t.tenant_id,"
                    + " t.current_action_id, t.id, 2, 'APPROVED', 1, gen_random_uuid(), 1, 1,"
                    + " repeat('a', 64), 1, 1, 0, 0, 0, 480, t.employee_id, t.site_id,"
                    + " gen_random_uuid(), gen_random_uuid(), 'test', now()"
                    + " FROM people.timesheet t");
            // Every generation locked, unlocked and locked again (3 actions), the projection at
            // version 3.
            exec(
                c,
                "INSERT INTO people.timesheet_period_action (tenant_id, id, generation_id,"
                    + " sequence, action, legal_entity_id, site_id, week_start, week_end,"
                    + " site_timezone, period_start_at, period_end_at, generation_version,"
                    + " timesheet_count, employee_count, row_count, worked_minutes,"
                    + " evidence_digest, locked_action_id, unlock_reason, unlock_explanation,"
                    + " actor_subject, occurred_at) SELECT g.tenant_id, md5(g.id::text ||"
                    + " k)::uuid, g.id, k, CASE WHEN k % 2 = 1 THEN 'LOCKED' ELSE 'UNLOCKED' END,"
                    + " gen_random_uuid(), g.site_id, g.week_start, g.week_end, g.site_timezone,"
                    + " g.period_start_at, g.period_end_at, 1, 25, 25, 25, 12000, repeat('e',"
                    + " 64), CASE WHEN k = 2 THEN md5(g.id::text || 1)::uuid END, CASE WHEN k = 2"
                    + " THEN 'OTHER' END, CASE WHEN k = 2 THEN 'Explication de test ici.' END,"
                    + " 'test', now() + k * interval '1 minute' FROM people.timesheet_generation g"
                    + " CROSS JOIN generate_series(1, 3) k");
            exec(
                c,
                "INSERT INTO people.timesheet_period_lock (tenant_id, generation_id, site_id,"
                    + " week_start, state, version, current_action_id, updated_at)"
                    + " SELECT a.tenant_id, a.generation_id, a.site_id, a.week_start, 'LOCKED', 3,"
                    + " a.id, a.occurred_at FROM people.timesheet_period_action a WHERE"
                    + " a.sequence = 3");
            for (String table :
                List.of(
                    "timesheet",
                    "timesheet_generation",
                    "timesheet_action",
                    "timesheet_period_action",
                    "timesheet_period_lock")) {
              exec(c, "ANALYZE people." + table);
            }
            UUID tenant = UUID.fromString("00000000-0000-4000-8000-000000000007");
            UUID site = UUID.fromString("00000000-0000-4000-b000-000000000007");
            UUID generation = UUID.fromString("00000000-0000-4000-a000-000000000207");
            List<String> plans = new ArrayList<>();
            List<Object[]> queries =
                List.of(
                    // The lock state of a generation (JdbcTimesheetPeriodRepository#lockState).
                    new Object[] {
                      "SELECT k.state, k.version, k.current_action_id, k.updated_at FROM"
                          + " people.timesheet_period_lock k WHERE k.tenant_id = ? AND"
                          + " k.generation_id = ?",
                      tenant,
                      generation
                    },
                    // Whether a locked week of the site covers a shift (#lockedWeekCovers).
                    new Object[] {
                      "SELECT count(*) FROM people.timesheet_period_lock k JOIN"
                          + " people.timesheet_generation g ON g.tenant_id = k.tenant_id AND g.id ="
                          + " k.generation_id WHERE k.tenant_id = ? AND k.site_id = ? AND k.state ="
                          + " 'LOCKED' AND k.week_start BETWEEN ? AND ? AND (CAST(? AS timestamptz)"
                          + " AT TIME ZONE g.site_timezone)::date >= g.week_start AND (CAST(? AS"
                          + " timestamptz) AT TIME ZONE g.site_timezone)::date < g.week_end",
                      tenant,
                      site,
                      java.sql.Date.valueOf(LocalDate.of(2023, 12, 27)),
                      java.sql.Date.valueOf(LocalDate.of(2024, 1, 4)),
                      Timestamp.from(Instant.parse("2024-01-03T10:00:00Z")),
                      Timestamp.from(Instant.parse("2024-01-03T10:00:00Z"))
                    },
                    // The history, newest first (#history).
                    new Object[] {
                      "SELECT a.id FROM people.timesheet_period_action a WHERE a.tenant_id = ?"
                          + " AND a.generation_id = ? ORDER BY a.sequence DESC",
                      tenant,
                      generation
                    },
                    // The generation's members evaluated for readiness (#members).
                    new Object[] {
                      "SELECT t.id, t.status, a.action FROM people.timesheet t LEFT JOIN"
                          + " people.timesheet_action a ON a.tenant_id = t.tenant_id AND a.id ="
                          + " t.current_action_id AND a.timesheet_id = t.id WHERE t.tenant_id = ?"
                          + " AND t.generation_id = ?",
                      tenant,
                      generation
                    },
                    // The guard of a timesheet write (people.timesheet_period_is_locked).
                    new Object[] {
                      "SELECT people.timesheet_period_is_locked(?, ?)", tenant, generation
                    });
            for (Object[] query : queries) {
              StringBuilder plan = new StringBuilder();
              try (PreparedStatement explain =
                  c.prepareStatement("EXPLAIN (VERBOSE false) " + query[0])) {
                for (int i = 1; i < query.length; i++) {
                  explain.setObject(i, query[i]);
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
            assertThat(plans.get(0)).contains("timesheet_period_lock_pkey");
            assertThat(plans.get(1)).contains("timesheet_period_lock_site_week");
            assertThat(plans.get(2)).contains("timesheet_period_action_history");
            assertThat(plans.get(3)).contains("timesheet_generation_member");
            for (String plan : plans.subList(0, 4)) {
              assertThat(plan)
                  .doesNotContain("Seq Scan on timesheet ")
                  .doesNotContain("Seq Scan on timesheet_period")
                  .doesNotContain("Seq Scan on timesheet_generation");
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
