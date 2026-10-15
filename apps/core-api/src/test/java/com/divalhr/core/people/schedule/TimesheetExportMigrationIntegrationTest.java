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
 * MVP-070E V35 directly against PostgreSQL, bypassing the application (the database is the final
 * authority, T70E-8, T70E-11 items 10, 13 and 14): the refusing rollback that restores V34 exactly;
 * an export header bound to its generation's current version and its site's legal entity; rows
 * bound to exactly the READY rows of a timesheet that is APPROVED now by that action and cycle
 * (never a superseded approval or a draft); counts, totals, digests and the CSV order proved at
 * commit; append-only headers and rows; and the plans of the export reads at representative volume.
 */
@IntegrationTest
class TimesheetExportMigrationIntegrationTest {

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
  // V35 fixtures
  // ------------------------------------------------------------------------------------------

  /** An export header's facts (one timesheet, one employee, one row of 470 minutes). */
  private record Head(
      UUID id,
      UUID legalEntity,
      UUID generation,
      int generationVersion,
      int timesheets,
      int employees,
      int rows,
      int minutes,
      String evidence,
      String status,
      int schema) {}

  private static Head head(Db db, World w, UUID generation) {
    return new Head(
        UUID.randomUUID(), legalEntity(db, w), generation, 1, 1, 1, 1, 470, DIGEST, "READY", 1);
  }

  private static UUID legalEntity(Db db, World w) {
    return db.jdbc()
        .queryForObject(
            "SELECT legal_entity_id FROM tenant.site WHERE id = ?", UUID.class, w.site());
  }

  private static void header(Connection c, World w, Head h) throws SQLException {
    exec(
        c,
        "INSERT INTO people.timesheet_export (tenant_id, id, schema_version, status,"
            + " legal_entity_id, site_id, generation_id, generation_version, week_start, week_end,"
            + " site_timezone, period_start_at, period_end_at, timesheet_count, employee_count,"
            + " row_count, worked_minutes, evidence_digest, csv_sha256, created_at, created_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'hr')",
        w.tenant(),
        h.id(),
        h.schema(),
        h.status(),
        h.legalEntity(),
        w.site(),
        h.generation(),
        h.generationVersion(),
        MONDAY,
        MONDAY.plusDays(7),
        KINSHASA,
        ts(MONDAY.atStartOfDay()),
        ts(MONDAY.plusDays(7).atStartOfDay()),
        h.timesheets(),
        h.employees(),
        h.rows(),
        h.minutes(),
        h.evidence(),
        DIGEST2,
        LATER);
  }

  /** One exported row of Ana's timesheet (line, revision, approval, minutes and date given). */
  private static void line(
      Connection c,
      World w,
      UUID export,
      int line,
      UUID timesheet,
      int revision,
      Act approval,
      int minutes,
      LocalDate workDate)
      throws SQLException {
    exec(
        c,
        "INSERT INTO people.timesheet_export_row (tenant_id, export_id, line, timesheet_id,"
            + " timesheet_revision, approval_action_id, approval_cycle, employee_id,"
            + " employee_number, employment_id, cost_center_id, work_date, shift_id,"
            + " worked_minutes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'E-ANA', ?, NULL, ?, ?, ?)",
        w.tenant(),
        export,
        line,
        timesheet,
        revision,
        approval.id(),
        approval.cycle(),
        w.employee(),
        w.employment(),
        workDate,
        w.slot(),
        minutes);
  }

  /** A valid export of the approved timesheet. */
  private void exported(Db db, World w, UUID g, UUID t, Act approval, Head h) {
    committed(
        db,
        c -> {
          header(c, w, h);
          line(c, w, h.id(), 1, t, 1, approval, 470, MONDAY);
        });
  }

  // ------------------------------------------------------------------------------------------
  // 13: rollback and upgrade
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV34ExactlyAndRefusesExportEvidence() {
    withDatabase(
        db -> {
          migrate(db.url(), "34");
          World w = world(db);
          UUID g = UUID.randomUUID();
          UUID t = UUID.randomUUID();
          Act approval = approved(db, w, g, t);
          String v34 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          migrate(db.url(), "35");
          String v35 = WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc());
          assertThat(v35)
              .isNotEqualTo(v34)
              .contains("timesheet_export_evidence_unique")
              .contains("timesheet_export_row_approval")
              .contains("timesheet_export_exact");
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V35__rollback.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v34);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.timesheet_action")).isEqualTo(2);

          db.jdbc().execute(resource("db/migration/V35__timesheet_export.sql"));
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v35);
          // Once anything was exported, the rollback refuses and changes nothing.
          exported(db, w, g, t, approval, head(db, w, g));
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V35__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V35 rollback refused: timesheet export evidence exists");
          assertThat(WorkScheduleSubstitutionMigrationIntegrationTest.signature(db.jdbc()))
              .isEqualTo(v35);
          assertThat(count(db, "SELECT count(*) FROM people.timesheet_export_row")).isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // 10: binding, exactness and immutability by direct SQL
  // ------------------------------------------------------------------------------------------

  @Test
  void exportsBindTheCurrentApprovalExactlyAndAreAppendOnly() {
    withDatabase(
        db -> {
          migrate(db.url(), "35");
          World w = world(db);
          UUID g = UUID.randomUUID();
          UUID t = UUID.randomUUID();
          Act approval = approved(db, w, g, t);
          Head ok = head(db, w, g);

          // The header: its generation's current version, its site's legal entity, the shape.
          refused(
              db,
              "timesheet_export_bind",
              c ->
                  header(
                      c,
                      w,
                      new Head(
                          UUID.randomUUID(),
                          ok.legalEntity(),
                          g,
                          2,
                          1,
                          1,
                          1,
                          470,
                          DIGEST,
                          "READY",
                          1)));
          refused(
              db,
              "timesheet_export_bind",
              c ->
                  header(
                      c,
                      w,
                      new Head(
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          g,
                          1,
                          1,
                          1,
                          1,
                          470,
                          DIGEST,
                          "READY",
                          1)));
          refused(
              db,
              "timesheet_export_status",
              c ->
                  header(
                      c,
                      w,
                      new Head(
                          UUID.randomUUID(),
                          ok.legalEntity(),
                          g,
                          1,
                          1,
                          1,
                          1,
                          470,
                          DIGEST,
                          "VOID",
                          1)));
          refused(
              db,
              "timesheet_export_schema",
              c ->
                  header(
                      c,
                      w,
                      new Head(
                          UUID.randomUUID(),
                          ok.legalEntity(),
                          g,
                          1,
                          1,
                          1,
                          1,
                          470,
                          DIGEST,
                          "READY",
                          2)));
          refused(
              db,
              "timesheet_export_digests",
              c ->
                  header(
                      c,
                      w,
                      new Head(
                          UUID.randomUUID(),
                          ok.legalEntity(),
                          g,
                          1,
                          1,
                          1,
                          1,
                          470,
                          "x",
                          "READY",
                          1)));
          refused(
              db,
              "timesheet_export_counts",
              c ->
                  header(
                      c,
                      w,
                      new Head(
                          UUID.randomUUID(),
                          ok.legalEntity(),
                          g,
                          1,
                          1,
                          2,
                          1,
                          470,
                          DIGEST,
                          "READY",
                          1)));
          // A header without its rows, rows that do not add up, or out of order, never commit.
          refused(db, "timesheet_export_exact", c -> header(c, w, ok));
          refused(
              db,
              "timesheet_export_exact",
              c -> {
                Head h =
                    new Head(
                        UUID.randomUUID(),
                        ok.legalEntity(),
                        g,
                        1,
                        1,
                        1,
                        1,
                        471,
                        DIGEST,
                        "READY",
                        1);
                header(c, w, h);
                line(c, w, h.id(), 1, t, 1, approval, 470, MONDAY);
              });
          refused(
              db,
              "timesheet_export_exact",
              c -> {
                header(c, w, ok);
                line(c, w, ok.id(), 2, t, 1, approval, 470, MONDAY);
              });
          // A row: exactly the READY row, its minutes, its local date, of the current approval.
          refused(
              db,
              "timesheet_export_row_binding",
              c -> {
                header(c, w, ok);
                line(c, w, ok.id(), 1, t, 1, approval, 469, MONDAY);
              });
          refused(
              db,
              "timesheet_export_row_binding",
              c -> {
                header(c, w, ok);
                line(c, w, ok.id(), 1, t, 1, approval, 470, MONDAY.plusDays(1));
              });
          Act submission = submitted(w, UUID.randomUUID(), 1, 1, 1);
          refused(
              db,
              "timesheet_export_row_binding",
              c -> {
                header(c, w, ok);
                line(c, w, ok.id(), 1, t, 1, submission, 470, MONDAY);
              });

          // Valid: one export of the approval; identical evidence is unique.
          exported(db, w, g, t, approval, ok);
          refused(
              db,
              "timesheet_export_evidence_unique",
              c -> {
                Head again = head(db, w, g);
                header(c, w, again);
                line(c, w, again.id(), 1, t, 1, approval, 470, MONDAY);
              });
          // No later row can join an export (the same shift again: the row key refuses it;
          // any other row breaks the export's exact counts, checked above).
          refused(
              db,
              "timesheet_export_row_unique",
              c -> line(c, w, ok.id(), 2, t, 1, approval, 470, MONDAY));
          // Append-only: no update, delete or truncate of headers or rows.
          refused(
              db,
              "timesheet_export_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_export SET created_by = 'x' WHERE id = ?",
                      ok.id()));
          refused(
              db,
              "timesheet_export_immutable",
              c -> exec(c, "DELETE FROM people.timesheet_export WHERE id = ?", ok.id()));
          refused(
              db,
              "timesheet_export_row_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE people.timesheet_export_row SET worked_minutes = 1 WHERE export_id"
                          + " = ?",
                      ok.id()));
          refused(
              db,
              "timesheet_export_row_immutable",
              c -> exec(c, "DELETE FROM people.timesheet_export_row WHERE export_id = ?", ok.id()));
          refused(
              db,
              "timesheet_export_no_truncate",
              c -> exec(c, "TRUNCATE people.timesheet_export CASCADE"));
          refused(
              db,
              "timesheet_export_row_no_truncate",
              c -> exec(c, "TRUNCATE people.timesheet_export_row"));

          // Once the approval is superseded by a reopening, it can no longer be exported.
          committed(
              db,
              c -> {
                UUID fix = correction(c, w, w.employee(), 1);
                reopen(c, w, t, approval, 3, approval.id(), 1, w.manager(), DIGEST2, fix);
                reopenedMove(c, w, t, "REOPENED");
              });
          refused(
              db,
              "timesheet_export_row_binding",
              c -> {
                Head later =
                    new Head(
                        UUID.randomUUID(),
                        ok.legalEntity(),
                        g,
                        1,
                        1,
                        1,
                        1,
                        470,
                        DIGEST3,
                        "READY",
                        1);
                header(c, w, later);
                line(c, w, later.id(), 1, t, 1, approval, 470, MONDAY);
              });
          // The earlier export is unchanged evidence.
          assertThat(count(db, "SELECT count(*) FROM people.timesheet_export_row")).isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // 14: the export reads use their keys at representative volume
  // ------------------------------------------------------------------------------------------

  @Test
  void theExportReadsUseTheirKeys() {
    withDatabase(
        db -> {
          migrate(db.url(), "35");
          try (Connection c = connect(db)) {
            exec(c, "SET LOCAL session_replication_role = replica");
            // 40,000 timesheets of 20 tenants (a third APPROVED with an action and a row).
            exec(
                c,
                "INSERT INTO people.timesheet (tenant_id, id, generation_id, employee_id,"
                    + " site_id, week_start, status, current_revision, row_count, ready_rows,"
                    + " missing_rows, open_rows, changed_rows, worked_minutes, created_at,"
                    + " updated_at, submission_cycle, current_action_id)"
                    + " SELECT ('00000000-0000-4000-8000-' || lpad((g % 20)::text, 12,"
                    + " '0'))::uuid, gen_random_uuid(),"
                    + " ('00000000-0000-4000-a000-' || lpad((g / 25)::text, 12, '0'))::uuid,"
                    + " gen_random_uuid(), gen_random_uuid(), DATE '2024-01-01' + 7 * (g / 4000),"
                    + " CASE WHEN g % 3 = 0 THEN 'APPROVED' ELSE 'DRAFT' END, 1, 1, 1, 0, 0, 0,"
                    + " 480, now(), now(), CASE WHEN g % 3 = 0 THEN 1 ELSE 0 END, CASE WHEN g % 3"
                    + " = 0 THEN gen_random_uuid() END FROM generate_series(1, 40000) g");
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
                    + " FROM people.timesheet t WHERE t.status = 'APPROVED'");
            exec(
                c,
                "INSERT INTO people.timesheet_row (tenant_id, timesheet_id, revision, slot_id,"
                    + " schedule_id, employee_id, employment_id, site_id, label_en, label_fr,"
                    + " scheduled_start_at, scheduled_end_at, attendance_record_id,"
                    + " checked_in_at, checked_out_at, correction_version, state, worked_minutes)"
                    + " SELECT t.tenant_id, t.id, 1, gen_random_uuid(), gen_random_uuid(),"
                    + " t.employee_id, gen_random_uuid(), t.site_id, 'x', 'x', now(),"
                    + " now() + interval '8 hours', gen_random_uuid(), now(),"
                    + " now() + interval '8 hours', 0, 'READY', 480 FROM people.timesheet t");
            // 20,000 exports of 20 tenants with 5 rows each.
            exec(
                c,
                "INSERT INTO people.timesheet_export (tenant_id, id, schema_version, status,"
                    + " legal_entity_id, site_id, generation_id, generation_version, week_start,"
                    + " week_end, site_timezone, period_start_at, period_end_at, timesheet_count,"
                    + " employee_count, row_count, worked_minutes, evidence_digest, csv_sha256,"
                    + " created_at, created_by) SELECT ('00000000-0000-4000-8000-' || lpad((g %"
                    + " 20)::text, 12, '0'))::uuid, gen_random_uuid(), 1, 'READY',"
                    + " gen_random_uuid(), ('00000000-0000-4000-b000-' || lpad((g % 200)::text,"
                    + " 12, '0'))::uuid, gen_random_uuid(), 1, DATE '2024-01-01' + 7 * (g %"
                    + " 100), DATE '2024-01-08' + 7 * (g % 100), 'Africa/Kinshasa', now(),"
                    + " now() + interval '168 hours', 5, 5, 5, 2400, md5(g::text) || md5(g::text),"
                    + " repeat('c', 64), now() - g * interval '1 minute', 'test'"
                    + " FROM generate_series(1, 20000) g");
            exec(
                c,
                "INSERT INTO people.timesheet_export_row (tenant_id, export_id, line,"
                    + " timesheet_id, timesheet_revision, approval_action_id, approval_cycle,"
                    + " employee_id, employee_number, employment_id, work_date, shift_id,"
                    + " worked_minutes) SELECT e.tenant_id, e.id, k, gen_random_uuid(), 1,"
                    + " gen_random_uuid(), 1, gen_random_uuid(), 'E' || k, gen_random_uuid(),"
                    + " e.week_start, gen_random_uuid(), 480 FROM people.timesheet_export e"
                    + " CROSS JOIN generate_series(1, 5) k");
            for (String table :
                List.of(
                    "timesheet",
                    "timesheet_generation",
                    "timesheet_action",
                    "timesheet_row",
                    "timesheet_export",
                    "timesheet_export_row")) {
              exec(c, "ANALYZE people." + table);
            }
            UUID tenant = UUID.fromString("00000000-0000-4000-8000-000000000007");
            UUID site = UUID.fromString("00000000-0000-4000-b000-000000000007");
            UUID generation = UUID.fromString("00000000-0000-4000-a000-000000000107");
            List<String> plans = new ArrayList<>();
            List<Object[]> queries =
                List.of(
                    // The eligible lines of a generation (JdbcTimesheetExportRepository#eligible).
                    new Object[] {
                      "SELECT t.id, r.slot_id FROM people.timesheet t JOIN"
                          + " people.timesheet_generation g ON g.tenant_id = t.tenant_id AND g.id"
                          + " = t.generation_id JOIN people.timesheet_action a ON a.tenant_id ="
                          + " t.tenant_id AND a.id = t.current_action_id AND a.timesheet_id = t.id"
                          + " AND a.action = 'APPROVED' AND a.revision = t.current_revision AND"
                          + " a.cycle = t.submission_cycle JOIN people.timesheet_row r ON"
                          + " r.tenant_id = t.tenant_id AND r.timesheet_id = t.id AND r.revision"
                          + " = t.current_revision AND r.state = 'READY' WHERE t.tenant_id = ?"
                          + " AND t.generation_id = ? AND t.status = 'APPROVED'",
                      tenant,
                      generation
                    },
                    // The history, newest first.
                    new Object[] {
                      "SELECT e.id FROM people.timesheet_export e WHERE e.tenant_id = ? ORDER BY"
                          + " e.created_at DESC, e.id DESC LIMIT 21",
                      tenant
                    },
                    // A site's week, newest first.
                    new Object[] {
                      "SELECT e.id FROM people.timesheet_export e WHERE e.tenant_id = ? AND"
                          + " e.site_id = ? AND e.week_start = DATE '2024-01-01' ORDER BY"
                          + " e.created_at DESC, e.id DESC LIMIT 21",
                      tenant,
                      site
                    },
                    // The export of identical evidence.
                    new Object[] {
                      "SELECT e.id FROM people.timesheet_export e WHERE e.tenant_id = ? AND"
                          + " e.site_id = ? AND e.week_start = DATE '2024-01-01' AND"
                          + " e.evidence_digest = repeat('d', 64)",
                      tenant,
                      site
                    },
                    // An export's rows in CSV order.
                    new Object[] {
                      "SELECT x.line FROM people.timesheet_export_row x WHERE x.tenant_id = ?"
                          + " AND x.export_id = ? ORDER BY x.line",
                      tenant,
                      UUID.randomUUID()
                    });
            for (Object[] query : queries) {
              StringBuilder plan = new StringBuilder();
              try (PreparedStatement explain = c.prepareStatement("EXPLAIN " + query[0])) {
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
            assertThat(plans.get(0)).contains("timesheet_generation_member");
            assertThat(plans.get(1)).contains("timesheet_export_history");
            assertThat(plans.get(2)).contains("timesheet_export_site_week");
            // The export of identical evidence: by its unique key or the site's week.
            assertThat(plans.get(3))
                .containsAnyOf("timesheet_export_evidence_unique", "timesheet_export_site_week");
            assertThat(plans.get(4)).contains("timesheet_export_row_pkey");
            for (String plan : plans) {
              assertThat(plan)
                  .doesNotContain("Seq Scan on timesheet ")
                  .doesNotContain("Seq Scan on timesheet_export")
                  .doesNotContain("Seq Scan on timesheet_row");
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
