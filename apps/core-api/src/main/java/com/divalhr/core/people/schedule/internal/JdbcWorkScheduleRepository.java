package com.divalhr.core.people.schedule.internal;

import com.divalhr.core.people.schedule.domain.EmployeeFacts;
import com.divalhr.core.people.schedule.domain.OwnShift;
import com.divalhr.core.people.schedule.domain.PlannedSlot;
import com.divalhr.core.people.schedule.domain.PublishedSlot;
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
 * Published work schedules and slots (MVP-050A, V25), and the employee, employment, placement,
 * separation and published-assignment facts a publication is checked against. Every query names the
 * verified tenant. Runs in the caller's transaction; the caller holds the tenant's scheduling lock
 * for every write.
 */
@Repository
public class JdbcWorkScheduleRepository {

  private static final String SCHEDULE =
      "SELECT s.id, s.site_id, s.first_date, s.last_date, s.timezone, s.slot_count,"
          + " s.assigned_count, s.published_at FROM people.work_schedule s";

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
   * Whether a published schedule of the site covers any of the dates.
   *
   * @param tenant verified tenant
   * @param siteId site
   * @param from first date
   * @param to last date
   * @return whether one overlaps
   */
  public boolean siteCovered(TenantId tenant, UUID siteId, LocalDate from, LocalDate to) {
    Boolean found =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM people.work_schedule s WHERE s.tenant_id = :tenant"
                + " AND s.site_id = :site AND daterange(s.first_date, s.last_date, '[]')"
                + " && daterange(:from, :to, '[]'))",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("site", siteId)
                .addValue("from", from)
                .addValue("to", to),
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
   * @return facts by employee
   */
  public Map<UUID, EmployeeFacts> facts(
      TenantId tenant,
      Collection<UUID> employees,
      LocalDate from,
      LocalDate to,
      Instant startAt,
      Instant endAt) {
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
            .addValue("endAt", Timestamp.from(endAt));
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
        "SELECT id, assigned_employee_id, start_at, end_at FROM people.work_schedule_slot"
            + " WHERE tenant_id = :tenant AND assigned_employee_id IN (:ids)"
            + " AND tstzrange(start_at, end_at, '[)') && tstzrange(:startAt, :endAt, '[)')"
            + " ORDER BY id",
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
   * Inserts the schedule and all its slots, in the caller's transaction.
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
    jdbc.update(
        "INSERT INTO people.work_schedule (id, tenant_id, site_id, first_date, last_date,"
            + " timezone, slot_count, assigned_count, published_at, published_by) VALUES (:id,"
            + " :tenant, :site, :first, :last, :zone, :slots, :assigned, :at, :by)",
        new MapSqlParameterSource()
            .addValue("id", schedule.id())
            .addValue("tenant", tenant.value())
            .addValue("site", schedule.siteId())
            .addValue("first", schedule.firstDate())
            .addValue("last", schedule.lastDate())
            .addValue("zone", schedule.timezone())
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
   * Runs the deferred completeness checks now, so a violation fails the publication before its
   * audit record and outbox event are written.
   *
   * @param tenant verified tenant (the check is transaction-wide)
   */
  public void checkComplete(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    jdbc.getJdbcOperations()
        .execute(
            "SET CONSTRAINTS people.work_schedule_complete, people.work_schedule_slot_complete"
                + " IMMEDIATE");
  }

  // ------------------------------------------------------------------------------------------
  // Administrators' reads
  // ------------------------------------------------------------------------------------------

  /**
   * One keyset page of the tenant's schedules, latest period first, optionally of one site and
   * overlapping the dates.
   *
   * @param tenant verified tenant
   * @param siteId site filter, or {@code null}
   * @param from schedules ending on or after it, or {@code null}
   * @param to schedules starting on or before it, or {@code null}
   * @param afterFirstDate first date of the previous page's last row, or {@code null}
   * @param afterId that row's id, or {@code null}
   * @param limit rows to read
   * @return the rows
   */
  public List<WorkSchedule> page(
      TenantId tenant,
      UUID siteId,
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
    return jdbc.query(sql.toString(), params, (rs, n) -> schedule(rs));
  }

  /**
   * A schedule of the tenant.
   *
   * @param tenant verified tenant
   * @param id schedule
   * @return the schedule
   */
  public Optional<WorkSchedule> find(TenantId tenant, UUID id) {
    return jdbc
        .query(
            SCHEDULE + " WHERE s.tenant_id = :tenant AND s.id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> schedule(rs))
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
            + " e.given_names, e.family_name FROM people.work_schedule_slot l"
            + " LEFT JOIN people.employee e"
            + " ON e.tenant_id = l.tenant_id AND e.id = l.assigned_employee_id"
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
                rs.getString("family_name")));
  }

  // ------------------------------------------------------------------------------------------
  // The employee's own shifts
  // ------------------------------------------------------------------------------------------

  /**
   * One keyset page of the employee's own published shifts starting on the dates, in start order.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's linked employee
   * @param from first local start date
   * @param to last local start date, or {@code null}
   * @param afterStartAt start instant of the previous page's last row, or {@code null}
   * @param afterId that row's id, or {@code null}
   * @param limit rows to read
   * @return the shifts
   */
  public List<OwnShift> ownShifts(
      TenantId tenant,
      UUID employeeId,
      LocalDate from,
      LocalDate to,
      Instant afterStartAt,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT l.id, l.schedule_id, s.site_id, s.timezone, l.start_local, l.end_local,"
                + " l.start_at, l.label_en, l.label_fr FROM people.work_schedule_slot l"
                + " JOIN people.work_schedule s"
                + " ON s.tenant_id = l.tenant_id AND s.id = l.schedule_id"
                + " WHERE l.tenant_id = :tenant AND l.assigned_employee_id = :employee"
                + " AND l.start_local >= :from");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("from", from.atStartOfDay())
            .addValue("limit", limit);
    if (to != null) {
      sql.append(" AND l.start_local < :until");
      params.addValue("until", to.plusDays(1).atStartOfDay());
    }
    if (afterId != null) {
      sql.append(" AND (l.start_at, l.id) > (:afterAt, :afterId)");
      params.addValue("afterAt", Timestamp.from(afterStartAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY l.start_at, l.id LIMIT :limit");
    return jdbc.query(
        sql.toString(),
        params,
        (rs, n) ->
            new OwnShift(
                rs.getObject("id", UUID.class),
                rs.getObject("schedule_id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getString("timezone"),
                rs.getObject("start_local", LocalDateTime.class),
                rs.getObject("end_local", LocalDateTime.class),
                rs.getTimestamp("start_at").toInstant(),
                rs.getString("label_en"),
                rs.getString("label_fr")));
  }

  /**
   * The start instant of one of the employee's own slots (a cursor position).
   *
   * @param tenant verified tenant
   * @param employeeId the caller's linked employee
   * @param slotId slot
   * @return its start instant
   */
  public Optional<Instant> ownStart(TenantId tenant, UUID employeeId, UUID slotId) {
    return jdbc
        .query(
            "SELECT start_at FROM people.work_schedule_slot WHERE tenant_id = :tenant"
                + " AND assigned_employee_id = :employee AND id = :id",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("employee", employeeId)
                .addValue("id", slotId),
            (rs, n) -> rs.getTimestamp("start_at").toInstant())
        .stream()
        .findFirst();
  }

  private static WorkSchedule schedule(ResultSet rs) throws SQLException {
    return new WorkSchedule(
        rs.getObject("id", UUID.class),
        rs.getObject("site_id", UUID.class),
        rs.getObject("first_date", LocalDate.class),
        rs.getObject("last_date", LocalDate.class),
        rs.getString("timezone"),
        rs.getInt("slot_count"),
        rs.getInt("assigned_count"),
        rs.getTimestamp("published_at").toInstant());
  }
}
