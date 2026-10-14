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
 * MVP-070B/C V33 directly against PostgreSQL, bypassing the application (the database is the final
 * authority, T70BC-9, T70BC-13 items 11 and 18 to 19): the refusing rollback that restores V32
 * exactly; append-only, untruncatable actions; the exact action shape and return-reason grammar;
 * the legal transitions in order (one submission and at most one terminal decision per cycle,
 * cycles in order, a decision bound to its submission's identical evidence); the timesheet changing
 * state only through its matching latest action; no refresh of submitted or approved time; and the
 * plans of the queue and history reads.
 */
@IntegrationTest
class TimesheetApprovalMigrationIntegrationTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final String KINSHASA = "Africa/Kinshasa";
  private static final Timestamp EARLY = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
  private static final Timestamp AT = Timestamp.from(Instant.parse("2026-11-08T11:00:00Z"));
  private static final Timestamp LATER = Timestamp.from(Instant.parse("2026-11-09T09:00:00Z"));
  private static final String DIGEST = "a".repeat(64);
  private static final String DIGEST2 = "b".repeat(64);
  private static final String REASON = "Le lundi manque une pause déclarée.";

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
    String database = "timesheet33_" + UUID.randomUUID().toString().replace("-", "");
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
      Timestamp at) {

    Act withSubmission(UUID newSubmission) {
      return new Act(
          id,
          sequence,
          action,
          cycle,
          newSubmission,
          revision,
          generationVersion,
          digest,
          worked,
          manager,
          reason,
          at);
    }

    Act withGeneration(int newVersion) {
      return new Act(
          id,
          sequence,
          action,
          cycle,
          submission,
          revision,
          newVersion,
          digest,
          worked,
          manager,
          reason,
          at);
    }

    Act with(int newWorked) {
      return new Act(
          id,
          sequence,
          action,
          cycle,
          submission,
          revision,
          generationVersion,
          digest,
          newWorked,
          manager,
          reason,
          at);
    }
  }

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

  /** A refresh of a returned draft: generation version 2 and revision 2 with another digest. */
  private static void refresh(Connection c, World w, UUID generation, UUID timesheet)
      throws SQLException {
    exec(
        c,
        "UPDATE people.timesheet_generation SET version = 2, refreshed_at = ?, refreshed_by = 'hr'"
            + " WHERE tenant_id = ? AND id = ?",
        LATER,
        w.tenant(),
        generation);
    revision(c, w, timesheet, 2, 2, DIGEST2, true);
    exec(
        c,
        "UPDATE people.timesheet SET current_revision = 2, updated_at = ?"
            + " WHERE tenant_id = ? AND id = ?",
        LATER,
        w.tenant(),
        timesheet);
  }

  // ------------------------------------------------------------------------------------------
  // Rollback and upgrade
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV32ExactlyAndRefusesSubmissionEvidence() {
    withDatabase(
        db -> {
          migrate(db.url(), "32");
          World w = world(db);
          UUID g = UUID.randomUUID();
          UUID t = UUID.randomUUID();
          committed(db, c -> draft(c, w, g, t, true));
          String v32 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          migrate(db.url(), "33");
          String v33 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          assertThat(v33)
              .isNotEqualTo(v32)
              .contains("timesheet_action")
              .contains("timesheet_current_action");
          // The existing draft is a never-submitted DRAFT.
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.timesheet WHERE status = 'DRAFT'"
                          + " AND submission_cycle = 0 AND current_action_id IS NULL"))
              .isEqualTo(1);
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V33__rollback.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v32);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.timesheet_row")).isEqualTo(1);

          db.jdbc().execute(resource("db/migration/V33__timesheet_submission_approval.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v33);
          // Once one action exists, the rollback refuses and changes nothing.
          committed(db, c -> act(c, w, t, submitted(w, UUID.randomUUID(), 1, 1, 1)));
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V33__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining(
                  "V33 rollback refused: timesheet submission or decision evidence exists");
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v33);
          assertThat(count(db, "SELECT count(*) FROM people.timesheet_action")).isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // The lifecycle
  // ------------------------------------------------------------------------------------------

  @Test
  void submissionReturnRefreshResubmissionAndApprovalFollowTheirOnlyLegalOrder() {
    withDatabase(
        db -> {
          migrate(db.url(), "33");
          World w = world(db);
          UUID g = UUID.randomUUID();
          UUID t = UUID.randomUUID();
          committed(db, c -> draft(c, w, g, t, true));

          // Nothing changes state without its matching action.
          refused(
              db,
              "timesheet_guard",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet SET status = 'SUBMITTED' WHERE tenant_id = ?",
                      w.tenant()));
          // A decision before any submission.
          Act phantom = submitted(w, UUID.randomUUID(), 1, 1, 1);
          refused(
              db,
              "timesheet_action_transition",
              c -> insert(c, w, t, decided(w, "APPROVED", 1, phantom, null, AT)));
          // An action without the timesheet's move is refused at commit.
          refused(
              db,
              "timesheet_action_current",
              c -> insert(c, w, t, submitted(w, UUID.randomUUID(), 1, 1, 1)));
          // Cycles start at 1; the sequence follows the latest action.
          refused(
              db,
              "timesheet_action_transition",
              c -> insert(c, w, t, submitted(w, UUID.randomUUID(), 1, 2, 1)));
          refused(
              db,
              "timesheet_action_sequence",
              c -> insert(c, w, t, submitted(w, UUID.randomUUID(), 2, 1, 1)));
          // The evidence is exactly the revision's (digest and totals).
          refused(
              db,
              "timesheet_action_revision",
              c -> act(c, w, t, submitted(w, UUID.randomUUID(), 1, 1, 1).with(469)));
          // The manager is never the employee.
          Act self =
              new Act(
                  UUID.randomUUID(),
                  1,
                  "SUBMITTED",
                  1,
                  null,
                  1,
                  1,
                  DIGEST,
                  470,
                  w.employee(),
                  null,
                  AT);
          refused(db, "timesheet_action_not_self", c -> act(c, w, t, self));

          Act first = submitted(w, UUID.randomUUID(), 1, 1, 1);
          committed(db, c -> act(c, w, t, first));
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.timesheet WHERE status = 'SUBMITTED'"
                          + " AND submission_cycle = 1 AND current_action_id = ?",
                      first.id()))
              .isEqualTo(1);

          // Actions are append-only and never truncated.
          refused(
              db,
              "timesheet_action_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_action SET actor_subject = 'x' WHERE tenant_id = ?",
                      w.tenant()));
          refused(
              db,
              "timesheet_action_immutable",
              c -> exec(c, "DELETE FROM people.timesheet_action WHERE tenant_id = ?", w.tenant()));
          refused(
              db,
              "timesheet_action_no_truncate",
              c -> exec(c, "TRUNCATE people.timesheet_action CASCADE"));
          // A second submission of the same cycle, and a resubmission while submitted.
          refused(
              db,
              "timesheet_action_transition",
              c -> act(c, w, t, submitted(w, UUID.randomUUID(), 2, 2, 1)));
          // Submitted time is never refreshed: neither the revision nor the generation.
          refused(
              db,
              "timesheet_guard",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet SET current_revision = 2 WHERE tenant_id = ?",
                      w.tenant()));
          refused(
              db,
              "timesheet_generation_locked",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_generation SET version = 2, refreshed_at = ?,"
                          + " refreshed_by = 'hr' WHERE tenant_id = ?",
                      LATER,
                      w.tenant()));
          // A decision carries its submission's identical evidence and names it (the transition
          // set aside, the composite key is the final word).
          refused(
              db,
              "timesheet_action_submission",
              c -> {
                exec(
                    c,
                    "ALTER TABLE people.timesheet_action DISABLE TRIGGER"
                        + " timesheet_action_transition");
                act(c, w, t, decided(w, "APPROVED", 2, first, null, LATER).withGeneration(7));
              });
          refused(
              db,
              "timesheet_action_transition",
              c ->
                  act(
                      c,
                      w,
                      t,
                      decided(w, "APPROVED", 2, submitted(w, first.id(), 1, 1, 1), null, LATER)
                          .withSubmission(UUID.randomUUID())));
          // A return needs its reason in the grammar; an approval has none.
          for (String bad :
              List.of(
                  "Trop bref",
                  " Espace en tête du motif.",
                  "Voir https://exemple.test pour le détail",
                  "Balise <b>interdite</b> ici.",
                  "Retour\u0007 avec contrôle.",
                  "x".repeat(501))) {
            refused(
                db,
                "timesheet_action_shape",
                c -> act(c, w, t, decided(w, "RETURNED", 2, first, bad, LATER)));
          }
          refused(
              db,
              "timesheet_action_shape",
              c -> act(c, w, t, decided(w, "RETURNED", 2, first, null, LATER)));
          refused(
              db,
              "timesheet_action_shape",
              c -> act(c, w, t, decided(w, "APPROVED", 2, first, REASON, LATER)));
          // A return to anything but DRAFT.
          refused(
              db,
              "timesheet_guard",
              c -> {
                Act returned = decided(w, "RETURNED", 2, first, REASON, LATER);
                insert(c, w, t, returned);
                moveTo(c, w, t, "APPROVED", 1, returned);
              });

          // Returned: a DRAFT again in cycle 1; the generation and the draft refresh.
          Act returned = decided(w, "RETURNED", 2, first, REASON, LATER);
          committed(db, c -> act(c, w, t, returned));
          refused(
              db,
              "timesheet_action_decision_unique",
              c -> {
                // A second decision of cycle 1 (the transition would refuse it first while the
                // timesheet is a DRAFT; the unique index is the final word).
                exec(
                    c,
                    "ALTER TABLE people.timesheet_action DISABLE TRIGGER"
                        + " timesheet_action_transition");
                insert(c, w, t, decided(w, "APPROVED", 3, first, null, LATER));
              });
          committed(db, c -> refresh(c, w, g, t));
          // Cycle 2 binds the refreshed revision and generation version.
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
                          3,
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
          Act second =
              new Act(
                  UUID.randomUUID(),
                  3,
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
          Act approved = decided(w, "APPROVED", 4, second, null, LATER);
          committed(db, c -> act(c, w, t, approved));

          // Approved: final; nothing changes, nothing follows.
          refused(
              db,
              "timesheet_guard",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet SET status = 'DRAFT' WHERE tenant_id = ?",
                      w.tenant()));
          refused(
              db,
              "timesheet_action_transition",
              c -> act(c, w, t, decided(w, "RETURNED", 5, second, REASON, LATER)));
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
          assertThat(
                  db.jdbc()
                      .queryForList(
                          "SELECT action || ':' || cycle || ':' || revision"
                              + " FROM people.timesheet_action WHERE tenant_id = ?"
                              + " ORDER BY sequence",
                          String.class,
                          w.tenant()))
              .containsExactly("SUBMITTED:1:1", "RETURNED:1:1", "SUBMITTED:2:2", "APPROVED:2:2");
        });
  }

  @Test
  void onlyACleanRevisionWithOneEmploymentIsSubmitted() {
    withDatabase(
        db -> {
          migrate(db.url(), "33");
          World w = world(db);
          UUID g = UUID.randomUUID();
          UUID t = UUID.randomUUID();
          committed(db, c -> draft(c, w, g, t, false));
          // The revision has a missing-attendance row: its totals can never be submitted.
          refused(
              db,
              "timesheet_action_clean",
              c ->
                  exec(
                      c,
                      "INSERT INTO people.timesheet_action (tenant_id, id, timesheet_id, sequence,"
                          + " action, cycle, revision, generation_version, source_digest,"
                          + " row_count, ready_rows, missing_rows, open_rows, changed_rows,"
                          + " worked_minutes, employee_id, site_id, employment_id,"
                          + " manager_employee_id, actor_subject, occurred_at) VALUES (?, ?, ?,"
                          + " 1, 'SUBMITTED', 1, 1, 1, ?, 1, 0, 1, 0, 0, 0, ?, ?, ?, ?, 's', ?)",
                      w.tenant(),
                      UUID.randomUUID(),
                      t,
                      DIGEST,
                      w.employee(),
                      w.site(),
                      w.employment(),
                      w.manager(),
                      AT));
        });
  }

  // ------------------------------------------------------------------------------------------
  // Query plans
  // ------------------------------------------------------------------------------------------

  @Test
  void theQueueAndHistoryReadsUseTheirKeys() {
    withDatabase(
        db -> {
          migrate(db.url(), "33");
          UUID tenant = UUID.fromString("00000000-0000-4000-8000-000000000007");
          UUID id = UUID.randomUUID();
          List<String> plans =
              analyzedPlans(
                  db,
                  List.of(
                      // The submitted timesheets of a manager's reports.
                      "SELECT t.id FROM people.timesheet t WHERE t.tenant_id = ?"
                          + " AND t.employee_id = ANY (?) AND t.status = 'SUBMITTED'",
                      // A timesheet's history, newest first.
                      "SELECT a.id FROM people.timesheet_action a WHERE a.tenant_id = ?"
                          + " AND a.timesheet_id = ? ORDER BY a.sequence DESC",
                      // The current action.
                      "SELECT a.id FROM people.timesheet_action a WHERE a.tenant_id = ?"
                          + " AND a.id = ?"),
                  List.of(
                      new Object[] {tenant, new UUID[] {id, UUID.randomUUID()}},
                      new Object[] {tenant, id},
                      new Object[] {tenant, id}));
          assertThat(plans.get(0)).contains("timesheet_submitted");
          assertThat(plans.get(1)).contains("timesheet_action_sequence_unique");
          assertThat(plans.get(2)).containsAnyOf("timesheet_action_pkey", "timesheet_action_");
          for (String plan : plans) {
            assertThat(plan).doesNotContain("Seq Scan");
          }
        });
  }

  @Test
  void theManagerQueueStartsFromTheManagersReportsAndUsesKeysOnly() {
    withDatabase(
        db -> {
          migrate(db.url(), "33");
          UUID tenant = UUID.fromString("00000000-0000-4000-8000-000000000007");
          // Employee 407 (tenant 7) manages the twenty employees 7, 27, ..., 387 of its block.
          UUID manager = UUID.fromString("00000000-0000-4000-9000-000000000407");
          seedQueue(db);
          org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named =
              new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(db.jdbc());
          for (boolean filtered : List.of(false, true)) {
            org.springframework.jdbc.core.namedparam.MapSqlParameterSource params =
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                    .addValue("tenant", tenant)
                    .addValue("manager", manager)
                    .addValue("limit", 21)
                    .addValue("site", UUID.randomUUID())
                    .addValue("week", LocalDate.of(2024, 1, 1))
                    .addValue("afterAt", AT)
                    .addValue("afterId", UUID.randomUUID());
            String plan =
                String.join(
                    "\n",
                    named.queryForList(
                        "EXPLAIN "
                            + com.divalhr.core.people.schedule.internal
                                .JdbcTimesheetActionRepository.queueSql(
                                filtered, filtered, filtered),
                        params,
                        String.class));
            assertThat(plan)
                .contains("employment_assignment_reports")
                .contains("timesheet_submitted")
                .doesNotContain("Seq Scan");
          }
        });
  }

  /**
   * 4,000 employees of 20 tenants with one employment and one manager line each (200 managers of 20
   * reports), 40,000 timesheets (a twentieth submitted) with their generations and two actions
   * each, analyzed; foreign keys and triggers set aside while inserting.
   */
  private void seedQueue(Db db) {
    committed(
        db,
        c -> {
          exec(c, "SET LOCAL session_replication_role = replica");
          exec(
              c,
              "INSERT INTO people.employee (id, tenant_id, employee_number, given_names,"
                  + " family_name, search_key, created_at, created_by)"
                  + " SELECT ('00000000-0000-4000-9000-' || lpad(i::text, 12, '0'))::uuid,"
                  + " ('00000000-0000-4000-8000-' || lpad((i % 20)::text, 12, '0'))::uuid,"
                  + " 'E' || i, 'Given', 'Family', ' given family ', now(), 'test'"
                  + " FROM generate_series(0, 3999) i");
          exec(
              c,
              "INSERT INTO people.employment (id, tenant_id, employee_id, effective_from,"
                  + " created_at, created_by)"
                  + " SELECT ('00000000-0000-4000-a000-' || lpad(i::text, 12, '0'))::uuid,"
                  + " ('00000000-0000-4000-8000-' || lpad((i % 20)::text, 12, '0'))::uuid,"
                  + " ('00000000-0000-4000-9000-' || lpad(i::text, 12, '0'))::uuid,"
                  + " DATE '2023-01-01', now(), 'test' FROM generate_series(0, 3999) i");
          exec(
              c,
              "INSERT INTO people.employment_assignment (id, tenant_id, employee_id,"
                  + " employment_id, kind, effective_from, manager_employee_id,"
                  + " created_by_change_id, origin_change_id)"
                  + " SELECT gen_random_uuid(),"
                  + " ('00000000-0000-4000-8000-' || lpad((i % 20)::text, 12, '0'))::uuid,"
                  + " ('00000000-0000-4000-9000-' || lpad(i::text, 12, '0'))::uuid,"
                  + " ('00000000-0000-4000-a000-' || lpad(i::text, 12, '0'))::uuid, 'MANAGER',"
                  + " DATE '2023-01-01', ('00000000-0000-4000-9000-'"
                  + " || lpad(((i / 400) * 400 + 400 + i % 20)::text, 12, '0'))::uuid,"
                  + " gen_random_uuid(), gen_random_uuid()"
                  + " FROM generate_series(0, 3999) i");
          exec(
              c,
              "INSERT INTO people.timesheet (tenant_id, id, generation_id, employee_id, site_id,"
                  + " week_start, status, current_revision, row_count, ready_rows, missing_rows,"
                  + " open_rows, changed_rows, worked_minutes, created_at, updated_at,"
                  + " submission_cycle, current_action_id)"
                  + " SELECT ('00000000-0000-4000-8000-' || lpad((g % 20)::text, 12, '0'))::uuid,"
                  + " gen_random_uuid(), gen_random_uuid(), ('00000000-0000-4000-9000-'"
                  + " || lpad((g % 4000)::text, 12, '0'))::uuid, gen_random_uuid(),"
                  + " DATE '2024-01-01' + 7 * (g / 4000), CASE WHEN g % 20 = 7 THEN 'SUBMITTED'"
                  + " ELSE 'APPROVED' END, 1, 1, 1, 0, 0, 0, 480, now(), now(), 1,"
                  + " gen_random_uuid() FROM generate_series(0, 39999) g");
          exec(
              c,
              "INSERT INTO people.timesheet_generation (tenant_id, id, site_id, week_start,"
                  + " week_end, site_timezone, period_start_at, period_end_at, version, status,"
                  + " generated_at, generated_by) SELECT tenant_id, generation_id, site_id,"
                  + " week_start, week_start + 7, 'Africa/Kinshasa', now(),"
                  + " now() + interval '168 hours', 1, 'DRAFT', now(), 'test'"
                  + " FROM people.timesheet");
          exec(
              c,
              "INSERT INTO people.timesheet_action (tenant_id, id, timesheet_id, sequence,"
                  + " action, cycle, submission_action_id, revision, generation_version,"
                  + " source_digest, row_count, ready_rows, missing_rows, open_rows,"
                  + " changed_rows, worked_minutes, employee_id, site_id, employment_id,"
                  + " manager_employee_id, actor_subject, occurred_at)"
                  + " SELECT t.tenant_id, CASE WHEN k = 1 THEN t.current_action_id"
                  + " ELSE gen_random_uuid() END, t.id, k,"
                  + " CASE WHEN k = 1 THEN 'SUBMITTED' ELSE 'APPROVED' END, 1,"
                  + " CASE WHEN k = 1 THEN NULL ELSE gen_random_uuid() END, 1, 1,"
                  + " repeat('a', 64), 1, 1, 0, 0, 0, 480, t.employee_id, t.site_id,"
                  + " replace(t.employee_id::text, '-9000-', '-a000-')::uuid,"
                  + " gen_random_uuid(), 'test', now() - k * interval '1 minute'"
                  + " FROM people.timesheet t CROSS JOIN generate_series(1, 2) k");
          for (String table :
              List.of(
                  "employee",
                  "employment",
                  "employment_assignment",
                  "timesheet",
                  "timesheet_generation",
                  "timesheet_action")) {
            exec(c, "ANALYZE people." + table);
          }
        });
  }

  /**
   * The plans of {@code sqls} over 40,000 analyzed timesheets of 20 tenants and 4,000 employees (a
   * twentieth submitted) with two actions each (inserted with triggers and foreign keys set aside,
   * then rolled back).
   */
  private List<String> analyzedPlans(Db db, List<String> sqls, List<Object[]> args) {
    try (Connection c = connect(db)) {
      exec(c, "SET LOCAL session_replication_role = replica");
      exec(
          c,
          "INSERT INTO people.timesheet (tenant_id, id, generation_id, employee_id, site_id,"
              + " week_start, status, current_revision, row_count, ready_rows, missing_rows,"
              + " open_rows, changed_rows, worked_minutes, created_at, updated_at,"
              + " submission_cycle, current_action_id)"
              + " SELECT ('00000000-0000-4000-8000-' || lpad((g % 20)::text, 12, '0'))::uuid,"
              + " gen_random_uuid(), gen_random_uuid(), ('00000000-0000-4000-9000-'"
              + " || lpad((g % 4000)::text, 12, '0'))::uuid, gen_random_uuid(),"
              + " DATE '2024-01-01' + 7 * (g / 4000), CASE WHEN g % 20 = 0 THEN 'SUBMITTED'"
              + " ELSE 'APPROVED' END, 1, 1, 1, 0, 0, 0, 480, now(), now(), 1, gen_random_uuid()"
              + " FROM generate_series(1, 40000) g");
      exec(
          c,
          "INSERT INTO people.timesheet_action (tenant_id, id, timesheet_id, sequence, action,"
              + " cycle, submission_action_id, revision, generation_version, source_digest,"
              + " row_count, ready_rows, missing_rows, open_rows, changed_rows, worked_minutes,"
              + " employee_id, site_id, employment_id, manager_employee_id, actor_subject,"
              + " occurred_at) SELECT t.tenant_id, gen_random_uuid(), t.id, k,"
              + " CASE WHEN k = 1 THEN 'SUBMITTED' ELSE 'APPROVED' END, 1,"
              + " CASE WHEN k = 1 THEN NULL ELSE gen_random_uuid() END, 1, 1,"
              + " repeat('a', 64), 1, 1, 0, 0, 0, 480, t.employee_id, t.site_id,"
              + " gen_random_uuid(), gen_random_uuid(), 'test', now()"
              + " FROM people.timesheet t CROSS JOIN generate_series(1, 2) k");
      for (String table : List.of("timesheet", "timesheet_action")) {
        exec(c, "ANALYZE people." + table);
      }
      List<String> plans = new ArrayList<>();
      for (int i = 0; i < sqls.size(); i++) {
        StringBuilder plan = new StringBuilder();
        try (PreparedStatement explain = c.prepareStatement("EXPLAIN " + sqls.get(i))) {
          Object[] values = args.get(i);
          for (int k = 0; k < values.length; k++) {
            if (values[k] instanceof UUID[] array) {
              explain.setArray(k + 1, c.createArrayOf("uuid", array));
            } else {
              explain.setObject(k + 1, values[k]);
            }
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
