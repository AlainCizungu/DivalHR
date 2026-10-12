package com.divalhr.core.people.schedule.internal;

import com.divalhr.core.people.schedule.domain.Attendance;
import com.divalhr.core.people.schedule.domain.EmployeeFacts;
import com.divalhr.core.people.schedule.domain.Lifecycle;
import com.divalhr.core.people.schedule.domain.OwnShift;
import com.divalhr.core.people.schedule.domain.PlannedSlot;
import com.divalhr.core.people.schedule.domain.PublishedSlot;
import com.divalhr.core.people.schedule.domain.ScheduleView;
import com.divalhr.core.people.schedule.domain.Substitution;
import com.divalhr.core.people.schedule.domain.WorkSchedule;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Published work schedules and slots (MVP-050A, V25), their terminal evidence and operational
 * coverage (MVP-050B, V26), and the employee, employment, placement, separation and operational
 * assignment facts a publication or replacement is checked against. Every query names the verified
 * tenant. Runs in the caller's transaction; the caller holds the tenant's scheduling lock for every
 * write.
 */
@Repository
public class JdbcWorkScheduleRepository {

  private static final String SCHEDULE =
      "SELECT s.id, s.site_id, s.first_date, s.last_date, s.site_timezone, s.slot_count,"
          + " s.assigned_count, s.published_at, c.cancelled_at, c.reason AS cancel_reason,"
          + " r.replaced_at, r.reason AS replace_reason, r.replacement_schedule_id,"
          + " p.schedule_id AS predecessor_id FROM people.work_schedule s"
          + " LEFT JOIN people.work_schedule_cancellation c"
          + " ON c.tenant_id = s.tenant_id AND c.schedule_id = s.id"
          + " LEFT JOIN people.work_schedule_replacement r"
          + " ON r.tenant_id = s.tenant_id AND r.schedule_id = s.id"
          + " LEFT JOIN people.work_schedule_replacement p"
          + " ON p.tenant_id = s.tenant_id AND p.replacement_schedule_id = s.id";

  /** A schedule without terminal action (evidence, never the coverage projections). */
  private static final String OPERATIONAL =
      " NOT EXISTS (SELECT 1 FROM people.work_schedule_cancellation x"
          + " WHERE x.tenant_id = s.tenant_id AND x.schedule_id = s.id)"
          + " AND NOT EXISTS (SELECT 1 FROM people.work_schedule_replacement x"
          + " WHERE x.tenant_id = s.tenant_id AND x.schedule_id = s.id)";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcWorkScheduleRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------
  // Locks
  // ------------------------------------------------------------------------------------------

  /**
   * Takes the tenant's scheduling lock (lock order step 1, ADR 0014): every publication of the
   * tenant serializes on it, so two publications never both see a site date or an employee instant
   * free.
   *
   * @param tenant verified tenant
   */
  public void lock(TenantId tenant) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended('people.work-schedule:' || :tenant, 0))",
        new MapSqlParameterSource().addValue("tenant", tenant.value().toString()),
        (rs, n) -> 0);
  }

  /**
   * Locks {@code FOR SHARE}, in UUID order, every employment of the employees that overlaps the
   * dates (lock order step 2): employment changes, corrections and separations, which lock an
   * employment {@code FOR UPDATE}, wait until the publication commits, and a publication waiting on
   * one reads the changed facts.
   *
   * @param tenant verified tenant
   * @param employees assigned employees
   * @param from first date
   * @param to last date
   */
  public void shareEmployments(
      TenantId tenant, Collection<UUID> employees, LocalDate from, LocalDate to) {
    if (employees.isEmpty()) {
      return;
    }
    jdbc.query(
        "SELECT id FROM people.employment WHERE tenant_id = :tenant AND employee_id IN (:ids)"
            + " AND effective_from <= :to AND (effective_to IS NULL OR effective_to >= :from)"
            + " ORDER BY id FOR SHARE",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("ids", employees)
            .addValue("from", from)
            .addValue("to", to),
        (rs, n) -> rs.getObject("id", UUID.class));
  }

  // ------------------------------------------------------------------------------------------
  // Facts
  // ------------------------------------------------------------------------------------------

  /**
   * Whether an operational schedule of the site (its coverage, V26) covers any of the dates, other
   * than the excluded one (the schedule being replaced).
   *
   * @param tenant verified tenant
   * @param siteId site
   * @param from first date
   * @param to last date
   * @param excluding a schedule whose coverage is ignored, or {@code null}
   * @return whether one overlaps
   */
  public boolean siteCovered(
      TenantId tenant, UUID siteId, LocalDate from, LocalDate to, UUID excluding) {
    Boolean found =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM people.work_schedule_site_coverage c"
                + " WHERE c.tenant_id = :tenant AND c.site_id = :site"
                + " AND daterange(c.first_date, c.last_date, '[]') && daterange(:from, :to, '[]')"
                + " AND c.schedule_id IS DISTINCT FROM CAST(:excluding AS uuid))",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("site", siteId)
                .addValue("from", from)
                .addValue("to", to)
                .addValue("excluding", excluding),
            Boolean.class);
    return Boolean.TRUE.equals(found);
  }

  /**
   * The facts of the tenant's employees among the IDs: employees of another tenant and unknown IDs
   * are simply absent.
   *
   * @param tenant verified tenant
   * @param employees assigned employee IDs
   * @param from first date of the window
   * @param to last date of the window
   * @param startAt first instant of the window
   * @param endAt last instant of the window
   * @param excluding a schedule whose operational assignments are ignored (the schedule being
   *     replaced), or {@code null}
   * @return facts by employee
   */
  public Map<UUID, EmployeeFacts> facts(
      TenantId tenant,
      Collection<UUID> employees,
      LocalDate from,
      LocalDate to,
      Instant startAt,
      Instant endAt,
      UUID excluding) {
    Map<UUID, EmployeeFacts> facts = new LinkedHashMap<>();
    if (employees.isEmpty()) {
      return facts;
    }
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("ids", employees)
            .addValue("from", from)
            .addValue("to", to)
            .addValue("startAt", Timestamp.from(startAt))
            .addValue("endAt", Timestamp.from(endAt))
            .addValue("excluding", excluding);
    record Names(UUID id, String number, String given, String family) {}
    List<Names> names =
        jdbc.query(
            "SELECT id, employee_number, given_names, family_name FROM people.employee"
                + " WHERE tenant_id = :tenant AND id IN (:ids) ORDER BY id",
            params,
            (rs, n) ->
                new Names(
                    rs.getObject("id", UUID.class),
                    rs.getString("employee_number"),
                    rs.getString("given_names"),
                    rs.getString("family_name")));
    Map<UUID, List<EmployeeFacts.Employment>> employments = new HashMap<>();
    jdbc.query(
        "SELECT id, employee_id, effective_from, effective_to, version FROM people.employment"
            + " WHERE tenant_id = :tenant AND employee_id IN (:ids) AND effective_from <= :to"
            + " AND (effective_to IS NULL OR effective_to >= :from) ORDER BY id",
        params,
        (ResultSet rs) -> {
          employments
              .computeIfAbsent(rs.getObject("employee_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new EmployeeFacts.Employment(
                      rs.getObject("id", UUID.class),
                      rs.getObject("effective_from", LocalDate.class),
                      rs.getObject("effective_to", LocalDate.class),
                      rs.getLong("version")));
        });
    Map<UUID, List<EmployeeFacts.Placement>> placements = new HashMap<>();
    jdbc.query(
        "SELECT id, employee_id, site_id, effective_from, effective_to"
            + " FROM people.employment_assignment WHERE tenant_id = :tenant"
            + " AND employee_id IN (:ids) AND kind = 'PLACEMENT'"
            + " AND superseded_by_change_id IS NULL AND period && daterange(:from, :to, '[]')"
            + " ORDER BY id",
        params,
        (ResultSet rs) -> {
          placements
              .computeIfAbsent(rs.getObject("employee_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new EmployeeFacts.Placement(
                      rs.getObject("id", UUID.class),
                      rs.getObject("site_id", UUID.class),
                      rs.getObject("effective_from", LocalDate.class),
                      rs.getObject("effective_to", LocalDate.class)));
        });
    Map<UUID, List<EmployeeFacts.Separation>> separations = new HashMap<>();
    jdbc.query(
        "SELECT id, employee_id, state, effective_at FROM people.employment_separation"
            + " WHERE tenant_id = :tenant AND employee_id IN (:ids)"
            + " AND state IN ('SCHEDULED', 'EFFECTIVE') ORDER BY id",
        params,
        (ResultSet rs) -> {
          separations
              .computeIfAbsent(rs.getObject("employee_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new EmployeeFacts.Separation(
                      rs.getObject("id", UUID.class),
                      rs.getString("state"),
                      rs.getTimestamp("effective_at").toInstant()));
        });
    Map<UUID, List<EmployeeFacts.Interval>> published = new HashMap<>();
    jdbc.query(
        "SELECT slot_id AS id, employee_id AS assigned_employee_id, start_at, end_at"
            + " FROM people.work_schedule_slot_coverage WHERE tenant_id = :tenant"
            + " AND employee_id IN (:ids)"
            + " AND tstzrange(start_at, end_at, '[)') && tstzrange(:startAt, :endAt, '[)')"
            + " AND schedule_id IS DISTINCT FROM CAST(:excluding AS uuid) ORDER BY slot_id",
        params,
        (ResultSet rs) -> {
          published
              .computeIfAbsent(
                  rs.getObject("assigned_employee_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new EmployeeFacts.Interval(
                      rs.getObject("id", UUID.class),
                      rs.getTimestamp("start_at").toInstant(),
                      rs.getTimestamp("end_at").toInstant()));
        });
    for (Names n : names) {
      facts.put(
          n.id(),
          new EmployeeFacts(
              n.id(),
              n.number(),
              n.given(),
              n.family(),
              employments.getOrDefault(n.id(), List.of()),
              placements.getOrDefault(n.id(), List.of()),
              separations.getOrDefault(n.id(), List.of()),
              published.getOrDefault(n.id(), List.of())));
    }
    return facts;
  }

  // ------------------------------------------------------------------------------------------
  // Publication
  // ------------------------------------------------------------------------------------------

  /**
   * Inserts the schedule, all its slots and its operational coverage, in the caller's transaction.
   *
   * @param tenant verified tenant
   * @param schedule the schedule
   * @param slots the slots in position order, each with its instants
   * @param slotIds the slot IDs, by position
   * @param publishedBy verified subject
   * @throws org.springframework.dao.DataIntegrityViolationException when a constraint rejects it
   */
  public void insert(
      TenantId tenant,
      WorkSchedule schedule,
      List<PlannedSlot> slots,
      List<UUID> slotIds,
      String publishedBy) {
    insertEvidence(tenant, schedule, slots, slotIds, publishedBy);
    insertCoverage(tenant, schedule, slots, slotIds);
  }

  /**
   * Inserts the schedule and all its slots (the immutable evidence), without coverage.
   *
   * @param tenant verified tenant
   * @param schedule the schedule
   * @param slots the slots in position order, each with its instants
   * @param slotIds the slot IDs, by position
   * @param publishedBy verified subject
   */
  public void insertEvidence(
      TenantId tenant,
      WorkSchedule schedule,
      List<PlannedSlot> slots,
      List<UUID> slotIds,
      String publishedBy) {
    jdbc.update(
        "INSERT INTO people.work_schedule (id, tenant_id, site_id, first_date, last_date,"
            + " site_timezone, slot_count, assigned_count, published_at, published_by) VALUES (:id,"
            + " :tenant, :site, :first, :last, :zone, :slots, :assigned, :at, :by)",
        new MapSqlParameterSource()
            .addValue("id", schedule.id())
            .addValue("tenant", tenant.value())
            .addValue("site", schedule.siteId())
            .addValue("first", schedule.firstDate())
            .addValue("last", schedule.lastDate())
            .addValue("zone", schedule.siteTimezone())
            .addValue("slots", schedule.slotCount())
            .addValue("assigned", schedule.assignedCount())
            .addValue("at", Timestamp.from(schedule.publishedAt()))
            .addValue("by", publishedBy));
    SqlParameterSource[] rows = new SqlParameterSource[slots.size()];
    for (int i = 0; i < slots.size(); i++) {
      PlannedSlot slot = slots.get(i);
      rows[i] =
          new MapSqlParameterSource()
              .addValue("id", slotIds.get(i))
              .addValue("tenant", tenant.value())
              .addValue("schedule", schedule.id())
              .addValue("position", slot.draft().position())
              .addValue("startLocal", slot.draft().start())
              .addValue("endLocal", slot.draft().end())
              .addValue("startAt", Timestamp.from(Objects.requireNonNull(slot.startAt())))
              .addValue("endAt", Timestamp.from(Objects.requireNonNull(slot.endAt())))
              .addValue("en", slot.draft().labelEn())
              .addValue("fr", slot.draft().labelFr())
              .addValue("employee", slot.draft().employeeId());
    }
    jdbc.batchUpdate(
        "INSERT INTO people.work_schedule_slot (id, tenant_id, schedule_id, position,"
            + " start_local, end_local, start_at, end_at, label_en, label_fr,"
            + " assigned_employee_id) VALUES (:id, :tenant, :schedule, :position, :startLocal,"
            + " :endLocal, :startAt, :endAt, :en, :fr, :employee)",
        rows);
  }

  /**
   * Inserts the operational coverage of a schedule without terminal action: its site row and one
   * row per assigned slot. The exclusions of the projections refuse a conflict.
   *
   * @param tenant verified tenant
   * @param schedule the schedule
   * @param slots the slots in position order
   * @param slotIds the slot IDs, by position
   */
  public void insertCoverage(
      TenantId tenant, WorkSchedule schedule, List<PlannedSlot> slots, List<UUID> slotIds) {
    jdbc.update(
        "INSERT INTO people.work_schedule_site_coverage (tenant_id, schedule_id, site_id,"
            + " first_date, last_date) VALUES (:tenant, :id, :site, :first, :last)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", schedule.id())
            .addValue("site", schedule.siteId())
            .addValue("first", schedule.firstDate())
            .addValue("last", schedule.lastDate()));
    List<SqlParameterSource> rows = new ArrayList<>();
    for (int i = 0; i < slots.size(); i++) {
      PlannedSlot slot = slots.get(i);
      if (slot.draft().employeeId() == null) {
        continue;
      }
      rows.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("slot", slotIds.get(i))
              .addValue("schedule", schedule.id())
              .addValue("employee", slot.draft().employeeId())
              .addValue("startAt", Timestamp.from(Objects.requireNonNull(slot.startAt())))
              .addValue("endAt", Timestamp.from(Objects.requireNonNull(slot.endAt()))));
    }
    if (!rows.isEmpty()) {
      jdbc.batchUpdate(
          "INSERT INTO people.work_schedule_slot_coverage (tenant_id, slot_id, schedule_id,"
              + " employee_id, start_at, end_at) VALUES (:tenant, :slot, :schedule, :employee,"
              + " :startAt, :endAt)",
          rows.toArray(SqlParameterSource[]::new));
    }
  }

  // ------------------------------------------------------------------------------------------
  // Terminal actions (MVP-050B)
  // ------------------------------------------------------------------------------------------

  /**
   * Locks a schedule of the tenant {@code FOR UPDATE} (lock order step 3) and reads it with its
   * derived lifecycle.
   *
   * @param tenant verified tenant
   * @param id schedule
   * @return the schedule, or empty for an unknown or foreign one
   */
  public Optional<ScheduleView> lockSchedule(TenantId tenant, UUID id) {
    List<UUID> locked =
        jdbc.query(
            "SELECT id FROM people.work_schedule WHERE tenant_id = :tenant AND id = :id"
                + " FOR UPDATE",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> rs.getObject("id", UUID.class));
    return locked.isEmpty() ? Optional.empty() : find(tenant, id);
  }

  /**
   * The earliest start instant of a schedule's slots.
   *
   * @param tenant verified tenant
   * @param id schedule
   * @return the earliest start
   */
  public Instant earliestStart(TenantId tenant, UUID id) {
    Timestamp at =
        jdbc.queryForObject(
            "SELECT min(start_at) FROM people.work_schedule_slot"
                + " WHERE tenant_id = :tenant AND schedule_id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            Timestamp.class);
    return Objects.requireNonNull(at, "a schedule has slots").toInstant();
  }

  /**
   * Records a cancellation (append-only evidence).
   *
   * @param tenant verified tenant
   * @param id schedule
   * @param reason the reason (decision-reason grammar)
   * @param at the action's instant
   * @param by verified subject
   */
  public void insertCancellation(TenantId tenant, UUID id, String reason, Instant at, String by) {
    jdbc.update(
        "INSERT INTO people.work_schedule_cancellation (tenant_id, schedule_id, reason,"
            + " cancelled_at, cancelled_by) VALUES (:tenant, :id, :reason, :at, :by)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", id)
            .addValue("reason", reason)
            .addValue("at", Timestamp.from(at))
            .addValue("by", by));
  }

  /**
   * Records a replacement (append-only evidence) linking the original to its successor.
   *
   * @param tenant verified tenant
   * @param id the original schedule
   * @param successorId the replacement schedule
   * @param reason the reason (decision-reason grammar)
   * @param at the action's instant (the successor's publication instant)
   * @param by verified subject
   */
  public void insertReplacement(
      TenantId tenant, UUID id, UUID successorId, String reason, Instant at, String by) {
    jdbc.update(
        "INSERT INTO people.work_schedule_replacement (tenant_id, schedule_id,"
            + " replacement_schedule_id, reason, replaced_at, replaced_by) VALUES (:tenant, :id,"
            + " :successor, :reason, :at, :by)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", id)
            .addValue("successor", successorId)
            .addValue("reason", reason)
            .addValue("at", Timestamp.from(at))
            .addValue("by", by));
  }

  /**
   * Removes a schedule's operational coverage, original and substituted alike, and its pending
   * substitution requests (MVP-051), whose evidence stays and then reads as {@code VOIDED}. Its
   * terminal evidence must already exist.
   *
   * @param tenant verified tenant
   * @param id schedule
   */
  public void releaseCoverage(TenantId tenant, UUID id) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id);
    jdbc.update(
        "DELETE FROM people.work_schedule_substitution_pending"
            + " WHERE tenant_id = :tenant AND schedule_id = :id",
        params);
    jdbc.update(
        "DELETE FROM people.work_schedule_slot_coverage"
            + " WHERE tenant_id = :tenant AND schedule_id = :id",
        params);
    jdbc.update(
        "DELETE FROM people.work_schedule_site_coverage"
            + " WHERE tenant_id = :tenant AND schedule_id = :id",
        params);
  }

  /**
   * Runs the deferred completeness and coverage-exactness checks now, so a violation fails the
   * publication, cancellation or replacement before its audit record and outbox event are written.
   *
   * @param tenant verified tenant (the check is transaction-wide)
   */
  public void checkComplete(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    jdbc.getJdbcOperations()
        .execute(
            "SET CONSTRAINTS people.work_schedule_complete, people.work_schedule_slot_complete,"
                + " people.work_schedule_covered, people.work_schedule_cancellation_uncovered,"
                + " people.work_schedule_replacement_uncovered,"
                + " people.work_schedule_site_coverage_exact,"
                + " people.work_schedule_slot_coverage_exact,"
                + " people.work_schedule_slot_coverage_bound,"
                + " people.work_schedule_cancellation_unpending,"
                + " people.work_schedule_replacement_unpending,"
                + " people.work_schedule_substitution_pending_exact IMMEDIATE");
  }

  // ------------------------------------------------------------------------------------------
  // Administrators' reads
  // ------------------------------------------------------------------------------------------

  /**
   * One keyset page of the tenant's schedules, latest period first, optionally of one site, of one
   * derived state and overlapping the dates.
   *
   * @param tenant verified tenant
   * @param siteId site filter, or {@code null}
   * @param status derived state filter, or {@code null}
   * @param from schedules ending on or after it, or {@code null}
   * @param to schedules starting on or before it, or {@code null}
   * @param afterFirstDate first date of the previous page's last row, or {@code null}
   * @param afterId that row's id, or {@code null}
   * @param limit rows to read
   * @return the rows
   */
  public List<ScheduleView> page(
      TenantId tenant,
      UUID siteId,
      Lifecycle.Status status,
      LocalDate from,
      LocalDate to,
      LocalDate afterFirstDate,
      UUID afterId,
      int limit) {
    StringBuilder sql = new StringBuilder(SCHEDULE).append(" WHERE s.tenant_id = :tenant");
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("limit", limit);
    if (siteId != null) {
      sql.append(" AND s.site_id = :site");
      params.addValue("site", siteId);
    }
    if (status == Lifecycle.Status.PUBLISHED) {
      sql.append(" AND c.schedule_id IS NULL AND r.schedule_id IS NULL");
    } else if (status == Lifecycle.Status.CANCELLED) {
      sql.append(" AND c.schedule_id IS NOT NULL");
    } else if (status == Lifecycle.Status.REPLACED) {
      sql.append(" AND r.schedule_id IS NOT NULL");
    }
    if (from != null) {
      sql.append(" AND s.last_date >= :from");
      params.addValue("from", from);
    }
    if (to != null) {
      sql.append(" AND s.first_date <= :to");
      params.addValue("to", to);
    }
    if (afterId != null) {
      sql.append(" AND (s.first_date, s.id) < (:afterDate, :afterId)");
      params.addValue("afterDate", afterFirstDate).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY s.first_date DESC, s.id DESC LIMIT :limit");
    return jdbc.query(sql.toString(), params, (rs, n) -> view(rs));
  }

  /**
   * A schedule of the tenant with its derived lifecycle.
   *
   * @param tenant verified tenant
   * @param id schedule
   * @return the schedule
   */
  public Optional<ScheduleView> find(TenantId tenant, UUID id) {
    return jdbc
        .query(
            SCHEDULE + " WHERE s.tenant_id = :tenant AND s.id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> view(rs))
        .stream()
        .findFirst();
  }

  /**
   * A schedule's slots in publication order, with the assigned employees' current names.
   *
   * @param tenant verified tenant
   * @param scheduleId schedule
   * @return the slots
   */
  public List<PublishedSlot> slots(TenantId tenant, UUID scheduleId) {
    return jdbc.query(
        "SELECT l.id, l.schedule_id, l.position, l.start_local, l.end_local, l.start_at,"
            + " l.end_at, l.label_en, l.label_fr, l.assigned_employee_id, e.employee_number,"
            + " e.given_names, e.family_name, c.employee_id AS effective_id,"
            + " f.employee_number AS effective_number, f.given_names AS effective_given,"
            + " f.family_name AS effective_family, d.id AS decision_id, d.request_id,"
            + " d.decided_at FROM people.work_schedule_slot l"
            + " LEFT JOIN people.employee e"
            + " ON e.tenant_id = l.tenant_id AND e.id = l.assigned_employee_id"
            + " LEFT JOIN people.work_schedule_slot_coverage c"
            + " ON c.tenant_id = l.tenant_id AND c.slot_id = l.id"
            + " LEFT JOIN people.employee f ON f.tenant_id = c.tenant_id AND f.id = c.employee_id"
            + " LEFT JOIN people.work_schedule_substitution_decision d"
            + " ON d.tenant_id = l.tenant_id AND d.slot_id = l.id AND d.outcome = 'APPROVED'"
            + " WHERE l.tenant_id = :tenant AND l.schedule_id = :schedule ORDER BY l.position",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("schedule", scheduleId),
        (rs, n) ->
            new PublishedSlot(
                rs.getObject("id", UUID.class),
                rs.getObject("schedule_id", UUID.class),
                rs.getInt("position"),
                rs.getObject("start_local", LocalDateTime.class),
                rs.getObject("end_local", LocalDateTime.class),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant(),
                rs.getString("label_en"),
                rs.getString("label_fr"),
                rs.getObject("assigned_employee_id", UUID.class),
                rs.getString("employee_number"),
                rs.getString("given_names"),
                rs.getString("family_name"),
                rs.getObject("effective_id", UUID.class) == null
                    ? null
                    : new Substitution.Person(
                        rs.getObject("effective_id", UUID.class),
                        rs.getString("effective_number"),
                        rs.getString("effective_given"),
                        rs.getString("effective_family")),
                rs.getObject("decision_id", UUID.class) == null
                    ? null
                    : new PublishedSlot.ApprovedSubstitution(
                        rs.getObject("request_id", UUID.class),
                        rs.getObject("decision_id", UUID.class),
                        rs.getTimestamp("decided_at").toInstant())));
  }

  // ------------------------------------------------------------------------------------------
  // The employee's own shifts
  // ------------------------------------------------------------------------------------------

  /**
   * One keyset page of the employee's own operational shifts, read from the slot coverage (MVP-051:
   * the published employee's, or the approved substitute's), in start order: either those not yet
   * ended at an instant ({@code endAfter}, the default view, R100-2) or those starting on local
   * dates ({@code from}, {@code to}: each slot's local start in its own site time zone). Coverage
   * exists exactly while its schedule has no terminal action (V26, V27).
   *
   * @param tenant verified tenant
   * @param employeeId the caller's linked employee
   * @param endAfter only shifts ending after this instant, or {@code null}
   * @param from first local start date, or {@code null}
   * @param to last local start date, or {@code null}
   * @param afterStartAt start instant of the previous page's last row, or {@code null}
   * @param afterId that row's id, or {@code null}
   * @param limit rows to read
   * @return the shifts
   */
  public List<OwnShift> ownShifts(
      TenantId tenant,
      UUID employeeId,
      Instant endAfter,
      LocalDate from,
      LocalDate to,
      Instant afterStartAt,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT l.id, l.schedule_id, s.site_id, s.site_timezone, l.start_local, l.end_local,"
                + " l.start_at, l.end_at, l.label_en, l.label_fr, c.decision_id,"
                + " a.id AS attendance_id, a.checked_in_at, a.checked_out_at,"
                + JdbcAttendanceRepository.RECORDING_COLUMNS
                + " FROM people.work_schedule_slot_coverage c"
                + " JOIN people.work_schedule_slot l"
                + " ON l.tenant_id = c.tenant_id AND l.id = c.slot_id"
                + " JOIN people.work_schedule s"
                + " ON s.tenant_id = c.tenant_id AND s.id = c.schedule_id"
                + " LEFT JOIN people.attendance_record a ON a.tenant_id = c.tenant_id"
                + " AND a.slot_id = c.slot_id AND a.employee_id = c.employee_id"
                + JdbcAttendanceRepository.RECORDING_JOINS
                + " WHERE c.tenant_id = :tenant AND c.employee_id = :employee");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("limit", limit);
    if (endAfter != null) {
      sql.append(" AND c.end_at > :endAfter");
      params.addValue("endAfter", Timestamp.from(endAfter));
    }
    if (from != null) {
      sql.append(" AND l.start_local >= :from");
      params.addValue("from", from.atStartOfDay());
    }
    if (to != null) {
      sql.append(" AND l.start_local < :until");
      params.addValue("until", to.plusDays(1).atStartOfDay());
    }
    if (afterId != null) {
      sql.append(" AND (c.start_at, c.slot_id) > (:afterAt, :afterId)");
      params.addValue("afterAt", Timestamp.from(afterStartAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY c.start_at, c.slot_id LIMIT :limit");
    return jdbc.query(
        sql.toString(),
        params,
        (rs, n) ->
            new OwnShift(
                rs.getObject("id", UUID.class),
                rs.getObject("schedule_id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getString("site_timezone"),
                rs.getObject("start_local", LocalDateTime.class),
                rs.getObject("end_local", LocalDateTime.class),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant(),
                rs.getString("label_en"),
                rs.getString("label_fr"),
                rs.getObject("decision_id", UUID.class) == null
                    ? Substitution.Source.PUBLISHED
                    : Substitution.Source.SUBSTITUTION,
                mark(rs)));
  }

  /** MVP-060A: the caller's own attendance on the shift, if recorded. */
  private static Attendance.Mark mark(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("attendance_id", UUID.class);
    if (id == null) {
      return Attendance.Mark.NONE;
    }
    Timestamp out = rs.getTimestamp("checked_out_at");
    return new Attendance.Mark(
        id,
        rs.getTimestamp("checked_in_at").toInstant(),
        out == null ? null : out.toInstant(),
        JdbcAttendanceRepository.recording(rs, "in"),
        JdbcAttendanceRepository.recording(rs, "out"));
  }

  /**
   * The start instant of one of the employee's own slots (a cursor position): one published to the
   * employee or substituted to them (evidence, so a cursor survives a later coverage change).
   *
   * @param tenant verified tenant
   * @param employeeId the caller's linked employee
   * @param slotId slot
   * @return its start instant
   */
  public Optional<Instant> ownStart(TenantId tenant, UUID employeeId, UUID slotId) {
    return jdbc
        .query(
            "SELECT l.start_at FROM people.work_schedule_slot l WHERE l.tenant_id = :tenant"
                + " AND l.id = :id AND (l.assigned_employee_id = :employee"
                + " OR EXISTS (SELECT 1 FROM people.work_schedule_substitution_decision d"
                + " WHERE d.tenant_id = l.tenant_id AND d.slot_id = l.id"
                + " AND d.replacement_employee_id = :employee))",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("employee", employeeId)
                .addValue("id", slotId),
            (rs, n) -> rs.getTimestamp("start_at").toInstant())
        .stream()
        .findFirst();
  }

  private static ScheduleView view(ResultSet rs) throws SQLException {
    WorkSchedule schedule = schedule(rs);
    UUID predecessor = rs.getObject("predecessor_id", UUID.class);
    Timestamp cancelled = rs.getTimestamp("cancelled_at");
    Timestamp replaced = rs.getTimestamp("replaced_at");
    Lifecycle lifecycle;
    if (cancelled != null) {
      lifecycle =
          new Lifecycle(
              Lifecycle.Status.CANCELLED,
              cancelled.toInstant(),
              rs.getString("cancel_reason"),
              predecessor,
              null);
    } else if (replaced != null) {
      lifecycle =
          new Lifecycle(
              Lifecycle.Status.REPLACED,
              replaced.toInstant(),
              rs.getString("replace_reason"),
              predecessor,
              rs.getObject("replacement_schedule_id", UUID.class));
    } else {
      lifecycle = Lifecycle.published(predecessor);
    }
    return new ScheduleView(schedule, lifecycle);
  }

  private static WorkSchedule schedule(ResultSet rs) throws SQLException {
    return new WorkSchedule(
        rs.getObject("id", UUID.class),
        rs.getObject("site_id", UUID.class),
        rs.getObject("first_date", LocalDate.class),
        rs.getObject("last_date", LocalDate.class),
        rs.getString("site_timezone"),
        rs.getInt("slot_count"),
        rs.getInt("assigned_count"),
        rs.getTimestamp("published_at").toInstant());
  }
}
