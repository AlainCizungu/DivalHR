package com.divalhr.core.people.schedule.internal;

import com.divalhr.core.people.schedule.domain.Substitution.Person;
import com.divalhr.core.people.schedule.domain.Timesheet.Period;
import com.divalhr.core.people.schedule.domain.Timesheet.Row;
import com.divalhr.core.people.schedule.domain.Timesheet.Source;
import com.divalhr.core.people.schedule.domain.Timesheet.State;
import com.divalhr.core.people.schedule.domain.Timesheet.Totals;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * MVP-070A (V32): weekly draft timesheets. The candidate query reads, for one site and week, the
 * operational schedules (the site coverage projection), their slots starting inside the week, the
 * slots' operational coverage (the effective assignee: the published employee or the approved
 * substitute), the assignee's qualifying employments and dated placement (cost center), and the
 * effective attendance projection. Every query names the verified tenant and is driven by a key:
 * the site's week by {@code timesheet_generation_site_week_unique}, a generation's drafts by {@code
 * timesheet_generation_member}, an employee's drafts by {@code timesheet_employee_week}, a
 * revision's rows by the row primary key. Runs in the caller's transaction; the caller holds the
 * tenant's scheduling lock for every write.
 */
@Repository
public class JdbcTimesheetRepository {

  private static final String GENERATION_COLUMNS =
      "g.id, g.site_id, g.week_start, g.site_timezone, g.period_start_at, g.period_end_at,"
          + " g.version, g.status, g.generated_at, g.refreshed_at";

  private static final String TIMESHEET_COLUMNS =
      "t.id, t.generation_id, t.employee_id, t.site_id, t.week_start, t.status, t.submission_cycle,"
          + " t.current_action_id, t.current_revision, t.row_count, t.ready_rows, t.missing_rows,"
          + " t.open_rows, t.changed_rows, t.worked_minutes, t.created_at, t.updated_at,"
          + " g.site_timezone, g.period_start_at, g.period_end_at";

  /** A timesheet {@code t} with its generation {@code g} (the week's zone and boundaries). */
  private static final String TIMESHEET_FROM =
      " FROM people.timesheet t JOIN people.timesheet_generation g"
          + " ON g.tenant_id = t.tenant_id AND g.id = t.generation_id";

  private static final String ROW_COLUMNS =
      "r.slot_id, r.schedule_id, r.employee_id, r.employment_id, r.site_id, r.cost_center_id,"
          + " r.label_en, r.label_fr, r.scheduled_start_at, r.scheduled_end_at,"
          + " r.attendance_record_id, r.checked_in_at, r.checked_out_at, r.correction_version,"
          + " r.state, r.worked_minutes";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcTimesheetRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A generation of the tenant and site's week.
   *
   * @param id generation
   * @param period the week, its time zone and exact boundaries as generated
   * @param version run version
   * @param generatedAt generation instant
   * @param refreshedAt latest refresh, or {@code null}
   */
  public record Generation(
      UUID id, Period period, int version, Instant generatedAt, Instant refreshedAt) {}

  /**
   * A timesheet header with its current totals.
   *
   * @param id timesheet
   * @param generationId generation
   * @param employeeId employee
   * @param period the week, its zone and exact boundaries
   * @param status {@code DRAFT}, {@code SUBMITTED} or {@code APPROVED} (MVP-070B/C)
   * @param cycle the latest submission cycle (0 before the first submission)
   * @param currentActionId the latest action, or {@code null} before the first submission
   * @param revision current revision
   * @param totals current totals
   * @param createdAt first revision instant
   * @param updatedAt latest revision instant
   */
  public record Header(
      UUID id,
      UUID generationId,
      UUID employeeId,
      Period period,
      String status,
      int cycle,
      UUID currentActionId,
      int revision,
      Totals totals,
      Instant createdAt,
      Instant updatedAt) {

    /**
     * Whether the timesheet is a draft (never submitted, or returned).
     *
     * @return whether its status is {@code DRAFT}
     */
    public boolean draft() {
      return "DRAFT".equals(status);
    }
  }

  /**
   * A revision header.
   *
   * @param revision number
   * @param generationVersion the run version that wrote it
   * @param totals its totals
   * @param createdAt instant
   */
  public record Revision(int revision, int generationVersion, Totals totals, Instant createdAt) {}

  /**
   * A candidate slot with the number of its assignee's qualifying employments (exactly one is
   * included; zero or several are excluded, never chosen arbitrarily).
   *
   * @param source the facts (employment and cost center from the lowest qualifying employment)
   * @param qualifying the number of qualifying employments
   */
  public record Candidate(Source source, int qualifying) {}

  // ------------------------------------------------------------------------------------------
  // Candidates
  // ------------------------------------------------------------------------------------------

  /**
   * The slots of the site's operational schedules that start inside the week, with their effective
   * assignee, the assignee's qualifying employments (covering every occupied local date, placed at
   * the site on the first date) and that placement's cost center, and the effective attendance
   * record of that assignee; ordered by employee, start and slot; at most {@code limit} rows.
   *
   * @param tenant verified tenant
   * @param period the week
   * @param limit maximum rows read
   * @return the candidates
   */
  public List<Candidate> candidates(TenantId tenant, Period period, int limit) {
    return jdbc.query(
        "SELECT l.id AS slot_id, l.schedule_id, c.employee_id, l.label_en, l.label_fr,"
            + " l.start_at, l.end_at, q.n, q.employment_id, q.cost_center_id,"
            + " a.id AS record_id, a.checked_in_at, a.checked_out_at, a.correction_version"
            + " FROM people.work_schedule_site_coverage sc"
            + " JOIN people.work_schedule_slot l"
            + " ON l.tenant_id = sc.tenant_id AND l.schedule_id = sc.schedule_id"
            + " JOIN people.work_schedule_slot_coverage c"
            + " ON c.tenant_id = l.tenant_id AND c.slot_id = l.id"
            + " CROSS JOIN LATERAL (SELECT l.start_local::date AS first, CASE"
            + " WHEN l.end_local::time = time '00:00' AND l.end_local::date > l.start_local::date"
            + " THEN l.end_local::date - 1 ELSE l.end_local::date END AS last) d"
            + " CROSS JOIN LATERAL (SELECT count(DISTINCT e.id) AS n,"
            + " min(e.id::text)::uuid AS employment_id,"
            + " min(pa.cost_center_id::text)::uuid AS cost_center_id"
            + " FROM people.employment e JOIN people.employment_assignment pa"
            + " ON pa.tenant_id = e.tenant_id AND pa.employment_id = e.id"
            + " AND pa.kind = 'PLACEMENT' AND pa.superseded_by_change_id IS NULL"
            + " AND pa.site_id = sc.site_id AND pa.effective_from <= d.first"
            + " AND (pa.effective_to IS NULL OR pa.effective_to >= d.first)"
            + " WHERE e.tenant_id = l.tenant_id AND e.employee_id = c.employee_id"
            + " AND e.effective_from <= d.first"
            + " AND (e.effective_to IS NULL OR e.effective_to >= d.last)) q"
            + " LEFT JOIN people.attendance_record a ON a.tenant_id = l.tenant_id"
            + " AND a.slot_id = l.id AND a.employee_id = c.employee_id"
            + " WHERE sc.tenant_id = :tenant AND sc.site_id = :site"
            + " AND daterange(sc.first_date, sc.last_date, '[]')"
            + " && daterange(:monday, :sunday, '[]')"
            + " AND l.start_at >= :from AND l.start_at < :to"
            + " ORDER BY c.employee_id, l.start_at, l.id LIMIT :limit",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("site", period.siteId())
            .addValue("monday", period.weekStart())
            .addValue("sunday", period.lastDay())
            .addValue("from", Timestamp.from(period.startAt()))
            .addValue("to", Timestamp.from(period.endAt()))
            .addValue("limit", limit),
        (rs, n) ->
            new Candidate(
                new Source(
                    rs.getObject("slot_id", UUID.class),
                    rs.getObject("schedule_id", UUID.class),
                    rs.getObject("employee_id", UUID.class),
                    rs.getObject("employment_id", UUID.class),
                    period.siteId(),
                    rs.getObject("cost_center_id", UUID.class),
                    rs.getString("label_en"),
                    rs.getString("label_fr"),
                    instant(rs, "start_at"),
                    instant(rs, "end_at"),
                    rs.getObject("record_id", UUID.class),
                    instant(rs, "checked_in_at"),
                    instant(rs, "checked_out_at"),
                    (Integer) rs.getObject("correction_version")),
                rs.getInt("n")));
  }

  // ------------------------------------------------------------------------------------------
  // Generations
  // ------------------------------------------------------------------------------------------

  /**
   * The generation of the site's week, if any.
   *
   * @param tenant verified tenant
   * @param siteId site
   * @param weekStart local Monday
   * @return the generation
   */
  public Optional<Generation> generationFor(TenantId tenant, UUID siteId, LocalDate weekStart) {
    return jdbc
        .query(
            "SELECT "
                + GENERATION_COLUMNS
                + " FROM people.timesheet_generation g WHERE g.tenant_id = :tenant"
                + " AND g.site_id = :site AND g.week_start = :week",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("site", siteId)
                .addValue("week", weekStart),
            (rs, n) -> generation(rs))
        .stream()
        .findFirst();
  }

  /**
   * A generation by ID.
   *
   * @param tenant verified tenant
   * @param id generation
   * @param lock {@code true} to lock it {@code FOR UPDATE}
   * @return the generation, or empty for an unknown or foreign one
   */
  public Optional<Generation> generation(TenantId tenant, UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT "
                + GENERATION_COLUMNS
                + " FROM people.timesheet_generation g WHERE g.tenant_id = :tenant AND g.id = :id"
                + (lock ? " FOR UPDATE" : ""),
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> generation(rs))
        .stream()
        .findFirst();
  }

  /**
   * Records a generation at version 1.
   *
   * @param tenant verified tenant
   * @param id generation
   * @param period the week
   * @param at generation instant
   * @param by verified subject
   */
  public void insertGeneration(TenantId tenant, UUID id, Period period, Instant at, String by) {
    jdbc.update(
        "INSERT INTO people.timesheet_generation (tenant_id, id, site_id, week_start, week_end,"
            + " site_timezone, period_start_at, period_end_at, version, status, generated_at,"
            + " generated_by) VALUES (:tenant, :id, :site, :week, :weekEnd, :zone, :from, :to, 1,"
            + " 'DRAFT', :at, :by)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", id)
            .addValue("site", period.siteId())
            .addValue("week", period.weekStart())
            .addValue("weekEnd", period.weekEnd())
            .addValue("zone", period.timezone())
            .addValue("from", Timestamp.from(period.startAt()))
            .addValue("to", Timestamp.from(period.endAt()))
            .addValue("at", Timestamp.from(at))
            .addValue("by", by));
  }

  /**
   * Records a refresh: the next version (the guard refuses anything else).
   *
   * @param tenant verified tenant
   * @param id generation
   * @param fromVersion the reviewed version
   * @param at refresh instant
   * @param by verified subject
   * @return rows updated (1, or 0 when the version moved)
   */
  public int refreshGeneration(TenantId tenant, UUID id, int fromVersion, Instant at, String by) {
    return jdbc.update(
        "UPDATE people.timesheet_generation SET version = version + 1, refreshed_at = :at,"
            + " refreshed_by = :by WHERE tenant_id = :tenant AND id = :id AND version = :version",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", id)
            .addValue("version", fromVersion)
            .addValue("at", Timestamp.from(at))
            .addValue("by", by));
  }

  // ------------------------------------------------------------------------------------------
  // Timesheets, revisions and rows
  // ------------------------------------------------------------------------------------------

  /**
   * A generation's drafts with their employees, by family name, given names and number.
   *
   * @param tenant verified tenant
   * @param generationId generation
   * @param lock {@code true} to lock the drafts {@code FOR UPDATE} (in ID order)
   * @return the headers and identities
   */
  public List<Member> members(TenantId tenant, UUID generationId, boolean lock) {
    if (lock) {
      jdbc.query(
          "SELECT id FROM people.timesheet WHERE tenant_id = :tenant"
              + " AND generation_id = :generation ORDER BY id FOR UPDATE",
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("generation", generationId),
          (rs, n) -> 0);
    }
    return jdbc.query(
        "SELECT "
            + TIMESHEET_COLUMNS
            + ", p.employee_number, p.given_names, p.family_name"
            + TIMESHEET_FROM
            + " JOIN people.employee p ON p.tenant_id = t.tenant_id AND p.id = t.employee_id"
            + " WHERE t.tenant_id = :tenant AND t.generation_id = :generation"
            + " ORDER BY p.family_name, p.given_names, p.employee_number, t.id",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("generation", generationId),
        (rs, n) ->
            new Member(
                header(rs),
                new Person(
                    rs.getObject("employee_id", UUID.class),
                    rs.getString("employee_number"),
                    rs.getString("given_names"),
                    rs.getString("family_name"))));
  }

  /**
   * A draft and its employee.
   *
   * @param header the header
   * @param employee the employee
   */
  public record Member(Header header, Person employee) {}

  /**
   * A timesheet by ID.
   *
   * @param tenant verified tenant
   * @param id timesheet
   * @return the header, or empty for an unknown or foreign one
   */
  public Optional<Header> timesheet(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT "
                + TIMESHEET_COLUMNS
                + TIMESHEET_FROM
                + " WHERE t.tenant_id = :tenant AND t.id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> header(rs))
        .stream()
        .findFirst();
  }

  /**
   * A timesheet by ID, locked {@code FOR UPDATE} (submission and decisions, MVP-070B/C).
   *
   * @param tenant verified tenant
   * @param id timesheet
   * @return the header, or empty for an unknown or foreign one
   */
  public Optional<Header> lockTimesheet(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT "
                + TIMESHEET_COLUMNS
                + TIMESHEET_FROM
                + " WHERE t.tenant_id = :tenant AND t.id = :id FOR UPDATE OF t",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> header(rs))
        .stream()
        .findFirst();
  }

  /**
   * One page of an employee's drafts, newest week first, after a keyset position.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param afterWeek the previous page's last week, or {@code null}
   * @param afterId the previous page's last timesheet, or {@code null}
   * @param limit rows to read
   * @return the headers
   */
  public List<Header> own(
      TenantId tenant, UUID employeeId, LocalDate afterWeek, UUID afterId, int limit) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("limit", limit);
    String after = "";
    if (afterWeek != null) {
      after = " AND (t.week_start, t.id) < (:week, :after)";
      params.addValue("week", afterWeek).addValue("after", afterId);
    }
    return jdbc.query(
        "SELECT "
            + TIMESHEET_COLUMNS
            + TIMESHEET_FROM
            + " WHERE t.tenant_id = :tenant AND t.employee_id = :employee"
            + after
            + " ORDER BY t.week_start DESC, t.id DESC LIMIT :limit",
        params,
        (rs, n) -> header(rs));
  }

  /**
   * A timesheet's revisions, newest first.
   *
   * @param tenant verified tenant
   * @param timesheetId timesheet
   * @return the revision headers
   */
  public List<Revision> revisions(TenantId tenant, UUID timesheetId) {
    return jdbc.query(
        "SELECT revision, generation_version, row_count, ready_rows, missing_rows, open_rows,"
            + " changed_rows, worked_minutes, created_at FROM people.timesheet_revision"
            + " WHERE tenant_id = :tenant AND timesheet_id = :timesheet ORDER BY revision DESC",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("timesheet", timesheetId),
        (rs, n) ->
            new Revision(
                rs.getInt("revision"),
                rs.getInt("generation_version"),
                totals(rs),
                instant(rs, "created_at")));
  }

  /**
   * A revision's source digest.
   *
   * @param tenant verified tenant
   * @param timesheetId timesheet
   * @param revision revision
   * @return the digest
   */
  public String revisionDigest(TenantId tenant, UUID timesheetId, int revision) {
    return jdbc.queryForObject(
        "SELECT source_digest FROM people.timesheet_revision WHERE tenant_id = :tenant"
            + " AND timesheet_id = :timesheet AND revision = :revision",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("timesheet", timesheetId)
            .addValue("revision", revision),
        String.class);
  }

  /**
   * A revision's rows, by scheduled start and slot.
   *
   * @param tenant verified tenant
   * @param timesheetId timesheet
   * @param revision revision
   * @return the rows
   */
  public List<Row> rows(TenantId tenant, UUID timesheetId, int revision) {
    return jdbc.query(
        "SELECT "
            + ROW_COLUMNS
            + " FROM people.timesheet_row r WHERE r.tenant_id = :tenant"
            + " AND r.timesheet_id = :timesheet AND r.revision = :revision"
            + " ORDER BY r.scheduled_start_at, r.slot_id",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("timesheet", timesheetId)
            .addValue("revision", revision),
        (rs, n) ->
            new Row(
                new Source(
                    rs.getObject("slot_id", UUID.class),
                    rs.getObject("schedule_id", UUID.class),
                    rs.getObject("employee_id", UUID.class),
                    rs.getObject("employment_id", UUID.class),
                    rs.getObject("site_id", UUID.class),
                    rs.getObject("cost_center_id", UUID.class),
                    rs.getString("label_en"),
                    rs.getString("label_fr"),
                    instant(rs, "scheduled_start_at"),
                    instant(rs, "scheduled_end_at"),
                    rs.getObject("attendance_record_id", UUID.class),
                    instant(rs, "checked_in_at"),
                    instant(rs, "checked_out_at"),
                    (Integer) rs.getObject("correction_version")),
                State.valueOf(rs.getString("state")),
                (Integer) rs.getObject("worked_minutes")));
  }

  /**
   * Records a new draft at revision 1 (its revision and rows follow in the same transaction).
   *
   * @param tenant verified tenant
   * @param id timesheet
   * @param generationId generation
   * @param employeeId employee
   * @param period the week
   * @param totals the first revision's totals
   * @param at instant
   */
  public void insertTimesheet(
      TenantId tenant,
      UUID id,
      UUID generationId,
      UUID employeeId,
      Period period,
      Totals totals,
      Instant at) {
    jdbc.update(
        "INSERT INTO people.timesheet (tenant_id, id, generation_id, employee_id, site_id,"
            + " week_start, status, current_revision, row_count, ready_rows, missing_rows,"
            + " open_rows, changed_rows, worked_minutes, created_at, updated_at) VALUES (:tenant,"
            + " :id, :generation, :employee, :site, :week, 'DRAFT', 1, :rows, :ready, :missing,"
            + " :open, :changed, :worked, :at, :at)",
        totals(totals)
            .addValue("tenant", tenant.value())
            .addValue("id", id)
            .addValue("generation", generationId)
            .addValue("employee", employeeId)
            .addValue("site", period.siteId())
            .addValue("week", period.weekStart())
            .addValue("at", Timestamp.from(at)));
  }

  /**
   * Moves a draft to its next revision with that revision's totals.
   *
   * @param tenant verified tenant
   * @param id timesheet
   * @param fromRevision its current revision
   * @param totals the next revision's totals
   * @param at instant
   * @return rows updated (1, or 0 when the revision moved)
   */
  public int advance(TenantId tenant, UUID id, int fromRevision, Totals totals, Instant at) {
    return jdbc.update(
        "UPDATE people.timesheet SET current_revision = current_revision + 1, row_count = :rows,"
            + " ready_rows = :ready, missing_rows = :missing, open_rows = :open,"
            + " changed_rows = :changed, worked_minutes = :worked, updated_at = :at"
            + " WHERE tenant_id = :tenant AND id = :id AND current_revision = :revision",
        totals(totals)
            .addValue("tenant", tenant.value())
            .addValue("id", id)
            .addValue("revision", fromRevision)
            .addValue("at", Timestamp.from(at)));
  }

  /**
   * Records a revision header and its rows (append-only; the guard requires the previous revision,
   * the deferred check the exact totals).
   *
   * @param tenant verified tenant
   * @param timesheetId timesheet
   * @param revision the revision number
   * @param generationVersion the run version writing it
   * @param digest the source digest
   * @param rows the ordered rows
   * @param at instant
   */
  public void insertRevision(
      TenantId tenant,
      UUID timesheetId,
      int revision,
      int generationVersion,
      String digest,
      List<Row> rows,
      Instant at) {
    Totals totals = Totals.of(rows);
    jdbc.update(
        "INSERT INTO people.timesheet_revision (tenant_id, timesheet_id, revision,"
            + " generation_version, source_digest, row_count, ready_rows, missing_rows,"
            + " open_rows, changed_rows, worked_minutes, created_at) VALUES (:tenant, :timesheet,"
            + " :revision, :version, :digest, :rows, :ready, :missing, :open, :changed, :worked,"
            + " :at)",
        totals(totals)
            .addValue("tenant", tenant.value())
            .addValue("timesheet", timesheetId)
            .addValue("revision", revision)
            .addValue("version", generationVersion)
            .addValue("digest", digest)
            .addValue("at", Timestamp.from(at)));
    List<SqlParameterSource> batch = new ArrayList<>(rows.size());
    for (Row row : rows) {
      Source s = row.source();
      batch.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("timesheet", timesheetId)
              .addValue("revision", revision)
              .addValue("slot", s.slotId())
              .addValue("schedule", s.scheduleId())
              .addValue("employee", s.employeeId())
              .addValue("employment", s.employmentId())
              .addValue("site", s.siteId())
              .addValue("costCenter", s.costCenterId())
              .addValue("labelEn", s.labelEn())
              .addValue("labelFr", s.labelFr())
              .addValue("start", Timestamp.from(s.scheduledStartAt()))
              .addValue("end", Timestamp.from(s.scheduledEndAt()))
              .addValue("record", s.recordId())
              .addValue("in", stamp(s.checkedInAt()))
              .addValue("out", stamp(s.checkedOutAt()))
              .addValue("correction", s.correctionVersion())
              .addValue("state", row.state().name())
              .addValue("worked", row.workedMinutes()));
    }
    jdbc.batchUpdate(
        "INSERT INTO people.timesheet_row (tenant_id, timesheet_id, revision, slot_id,"
            + " schedule_id, employee_id, employment_id, site_id, cost_center_id, label_en,"
            + " label_fr, scheduled_start_at, scheduled_end_at, attendance_record_id,"
            + " checked_in_at, checked_out_at, correction_version, state, worked_minutes) VALUES"
            + " (:tenant, :timesheet, :revision, :slot, :schedule, :employee, :employment, :site,"
            + " CAST(:costCenter AS uuid), :labelEn, :labelFr, :start, :end,"
            + " CAST(:record AS uuid), CAST(:in AS timestamptz), CAST(:out AS timestamptz),"
            + " CAST(:correction AS integer), :state, CAST(:worked AS integer))",
        batch.toArray(new SqlParameterSource[0]));
  }

  /**
   * Checks the deferred timesheet constraints now, so a violation surfaces inside the command.
   *
   * @param tenant verified tenant (the constraints are tenant-wide by construction)
   */
  public void checkConsistent(TenantId tenant) {
    jdbc.getJdbcTemplate()
        .execute(
            "SET CONSTRAINTS people.timesheet_current, people.timesheet_revision_exact,"
                + " people.timesheet_row_exact, people.timesheet_current_action,"
                + " people.timesheet_action_current IMMEDIATE");
  }

  // ------------------------------------------------------------------------------------------
  // Mapping
  // ------------------------------------------------------------------------------------------

  private static Generation generation(ResultSet rs) throws SQLException {
    return new Generation(
        rs.getObject("id", UUID.class),
        new Period(
            rs.getObject("site_id", UUID.class),
            rs.getString("site_timezone"),
            rs.getObject("week_start", LocalDate.class),
            instant(rs, "period_start_at"),
            instant(rs, "period_end_at")),
        rs.getInt("version"),
        instant(rs, "generated_at"),
        instant(rs, "refreshed_at"));
  }

  private static Header header(ResultSet rs) throws SQLException {
    return new Header(
        rs.getObject("id", UUID.class),
        rs.getObject("generation_id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        new Period(
            rs.getObject("site_id", UUID.class),
            rs.getString("site_timezone"),
            rs.getObject("week_start", LocalDate.class),
            instant(rs, "period_start_at"),
            instant(rs, "period_end_at")),
        rs.getString("status"),
        rs.getInt("submission_cycle"),
        rs.getObject("current_action_id", UUID.class),
        rs.getInt("current_revision"),
        totals(rs),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Totals totals(ResultSet rs) throws SQLException {
    return new Totals(
        rs.getInt("row_count"),
        rs.getInt("ready_rows"),
        rs.getInt("missing_rows"),
        rs.getInt("open_rows"),
        rs.getInt("changed_rows"),
        rs.getInt("worked_minutes"));
  }

  private static MapSqlParameterSource totals(Totals totals) {
    return new MapSqlParameterSource()
        .addValue("rows", totals.rows())
        .addValue("ready", totals.ready())
        .addValue("missing", totals.missing())
        .addValue("open", totals.open())
        .addValue("changed", totals.changed())
        .addValue("worked", totals.workedMinutes());
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp stamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
