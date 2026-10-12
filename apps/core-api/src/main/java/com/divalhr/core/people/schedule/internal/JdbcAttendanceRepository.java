package com.divalhr.core.people.schedule.internal;

import com.divalhr.core.people.schedule.domain.Attendance;
import com.divalhr.core.people.schedule.domain.Attendance.HistoryItem;
import com.divalhr.core.people.schedule.domain.Attendance.Record;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Attendance evidence and its guarded projection (MVP-060A, V28; ADR 0016). Every query names the
 * verified tenant. Runs in the caller's transaction; the caller holds the tenant's scheduling lock
 * for every write (A60A-6).
 */
@Repository
public class JdbcAttendanceRepository {

  private static final String RECORD_COLUMNS =
      " a.id AS record_id, a.schedule_id AS record_schedule_id, a.slot_id AS record_slot_id,"
          + " a.employee_id AS record_employee_id, a.employment_id AS record_employment_id,"
          + " a.slot_start_at, a.slot_end_at, a.check_in_event_id, a.checked_in_at,"
          + " a.check_out_event_id, a.checked_out_at";

  private static final String SHIFT_COLUMNS =
      " l.id AS slot_id, l.schedule_id, s.site_id, s.site_timezone, l.start_local, l.end_local,"
          + " l.start_at, l.end_at, l.label_en, l.label_fr, l.assigned_employee_id,"
          + " (NOT people.work_schedule_terminal(s.tenant_id, s.id)) AS operational";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcAttendanceRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One record of the tenant (unlocked).
   *
   * @param tenant verified tenant
   * @param id record
   * @return the record, or empty for an unknown or foreign one
   */
  public Optional<Record> record(TenantId tenant, UUID id) {
    return recordRow(tenant, id, "");
  }

  /**
   * Locks one record {@code FOR UPDATE} (lock order step 6): two check-outs of a record serialize.
   *
   * @param tenant verified tenant
   * @param id record
   * @return the record, or empty for an unknown or foreign one
   */
  public Optional<Record> lockRecord(TenantId tenant, UUID id) {
    return recordRow(tenant, id, " FOR UPDATE");
  }

  private Optional<Record> recordRow(TenantId tenant, UUID id, String lock) {
    return jdbc
        .query(
            "SELECT"
                + RECORD_COLUMNS
                + " FROM people.attendance_record a WHERE a.tenant_id = :tenant AND a.id = :id"
                + lock,
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> record(rs))
        .stream()
        .findFirst();
  }

  /**
   * The employee's record on a slot, if any.
   *
   * @param tenant verified tenant
   * @param slotId slot
   * @param employeeId employee
   * @return the record
   */
  public Optional<Record> forSlot(TenantId tenant, UUID slotId, UUID employeeId) {
    return jdbc
        .query(
            "SELECT"
                + RECORD_COLUMNS
                + " FROM people.attendance_record a WHERE a.tenant_id = :tenant"
                + " AND a.slot_id = :slot AND a.employee_id = :employee",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("slot", slotId)
                .addValue("employee", employeeId),
            (rs, n) -> record(rs))
        .stream()
        .findFirst();
  }

  /**
   * Locks {@code FOR UPDATE} the employee's open records whose check-out window still includes the
   * instant (the open-attendance guard, lock order step 6).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param at the server instant
   * @return the actionable open records
   */
  public List<UUID> lockActionableOpen(TenantId tenant, UUID employeeId, Instant at) {
    return jdbc.query(
        "SELECT id FROM people.attendance_record WHERE tenant_id = :tenant"
            + " AND employee_id = :employee AND check_out_event_id IS NULL"
            + " AND check_out_until >= :at ORDER BY id FOR UPDATE",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("at", Timestamp.from(at)),
        (rs, n) -> rs.getObject("id", UUID.class));
  }

  /**
   * Records a check-in: the CHECKED_IN event first (the record references it), then the open
   * record.
   *
   * @param tenant verified tenant
   * @param record the open record
   * @param by verified subject (evidence only)
   * @param idempotencyDigest digest of the idempotency reference
   */
  public void insertCheckIn(TenantId tenant, Record record, String by, String idempotencyDigest) {
    MapSqlParameterSource params =
        recordParams(tenant, record)
            .addValue("event", record.checkInEventId())
            .addValue("type", Attendance.EventType.CHECKED_IN.name())
            .addValue("at", Timestamp.from(record.checkedInAt()))
            .addValue("by", by)
            .addValue("digest", idempotencyDigest);
    insertEvent(params);
    jdbc.update(
        "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id, employee_id,"
            + " employment_id, slot_start_at, slot_end_at, check_out_until, check_in_event_id,"
            + " checked_in_at) VALUES (:tenant, :id, :schedule, :slot, :employee, :employment,"
            + " :start, :end, :until, :event, :at)",
        params);
  }

  /**
   * Records a check-out: the CHECKED_OUT event first, then the record's single permitted update.
   *
   * @param tenant verified tenant
   * @param record the open record
   * @param eventId the check-out event
   * @param at the server instant
   * @param by verified subject (evidence only)
   * @param idempotencyDigest digest of the idempotency reference
   * @return rows updated (1)
   */
  public int insertCheckOut(
      TenantId tenant,
      Record record,
      UUID eventId,
      Instant at,
      String by,
      String idempotencyDigest) {
    MapSqlParameterSource params =
        recordParams(tenant, record)
            .addValue("event", eventId)
            .addValue("type", Attendance.EventType.CHECKED_OUT.name())
            .addValue("at", Timestamp.from(at))
            .addValue("by", by)
            .addValue("digest", idempotencyDigest);
    insertEvent(params);
    return jdbc.update(
        "UPDATE people.attendance_record SET check_out_event_id = :event, checked_out_at = :at"
            + " WHERE tenant_id = :tenant AND id = :id AND check_out_event_id IS NULL",
        params);
  }

  private void insertEvent(MapSqlParameterSource params) {
    jdbc.update(
        "INSERT INTO people.attendance_event (tenant_id, id, record_id, schedule_id, slot_id,"
            + " employee_id, employment_id, event_type, recorded_at, recorded_by,"
            + " idempotency_digest) VALUES (:tenant, :event, :id, :schedule, :slot, :employee,"
            + " :employment, :type, :at, :by, :digest)",
        params);
  }

  private static MapSqlParameterSource recordParams(TenantId tenant, Record record) {
    return new MapSqlParameterSource()
        .addValue("tenant", tenant.value())
        .addValue("id", record.id())
        .addValue("schedule", record.scheduleId())
        .addValue("slot", record.slotId())
        .addValue("employee", record.employeeId())
        .addValue("employment", record.employmentId())
        .addValue("start", Timestamp.from(record.slotStartAt()))
        .addValue("end", Timestamp.from(record.slotEndAt()))
        .addValue("until", Timestamp.from(Attendance.checkOutUntil(record.slotEndAt())));
  }

  /**
   * Runs the deferred attendance checks now, so a violation fails the command before its audit
   * record and outbox event are written.
   *
   * @param tenant verified tenant (the check is transaction-wide)
   */
  public void checkConsistent(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    jdbc.getJdbcOperations()
        .execute(
            "SET CONSTRAINTS people.attendance_event_record, people.attendance_event_exact,"
                + " people.attendance_record_exact IMMEDIATE");
  }

  /**
   * One keyset page of the employee's own records, newest shift first.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's linked employee
   * @param afterStartAt the previous page's last shift start, or {@code null}
   * @param afterId its record ID, or {@code null}
   * @param limit rows to read
   * @return the rows
   */
  public List<HistoryItem> history(
      TenantId tenant, UUID employeeId, Instant afterStartAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT"
                + RECORD_COLUMNS
                + ","
                + SHIFT_COLUMNS
                + " FROM people.attendance_record a"
                + " JOIN people.work_schedule_slot l ON l.tenant_id = a.tenant_id"
                + " AND l.id = a.slot_id"
                + " JOIN people.work_schedule s ON s.tenant_id = l.tenant_id"
                + " AND s.id = l.schedule_id"
                + " WHERE a.tenant_id = :tenant AND a.employee_id = :employee");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("limit", limit);
    if (afterId != null) {
      sql.append(" AND (a.slot_start_at, a.id) < (:afterAt, :afterId)");
      params.addValue("afterAt", Timestamp.from(afterStartAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY a.slot_start_at DESC, a.id DESC LIMIT :limit");
    return jdbc.query(sql.toString(), params, (rs, n) -> new HistoryItem(record(rs), shift(rs)));
  }

  /**
   * One of the employee's own records' shift start (a cursor position).
   *
   * @param tenant verified tenant
   * @param employeeId the caller's linked employee
   * @param recordId record
   * @return its shift start
   */
  public Optional<Instant> ownStart(TenantId tenant, UUID employeeId, UUID recordId) {
    return jdbc
        .query(
            "SELECT slot_start_at FROM people.attendance_record WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND id = :id",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("employee", employeeId)
                .addValue("id", recordId),
            (rs, n) -> rs.getTimestamp("slot_start_at").toInstant())
        .stream()
        .findFirst();
  }

  private static Record record(ResultSet rs) throws SQLException {
    Timestamp out = rs.getTimestamp("checked_out_at");
    return new Record(
        rs.getObject("record_id", UUID.class),
        rs.getObject("record_schedule_id", UUID.class),
        rs.getObject("record_slot_id", UUID.class),
        rs.getObject("record_employee_id", UUID.class),
        rs.getObject("record_employment_id", UUID.class),
        rs.getTimestamp("slot_start_at").toInstant(),
        rs.getTimestamp("slot_end_at").toInstant(),
        rs.getObject("check_in_event_id", UUID.class),
        rs.getTimestamp("checked_in_at").toInstant(),
        rs.getObject("check_out_event_id", UUID.class),
        out == null ? null : out.toInstant());
  }

  private static Shift shift(ResultSet rs) throws SQLException {
    return new Shift(
        rs.getObject("slot_id", UUID.class),
        rs.getObject("schedule_id", UUID.class),
        rs.getObject("site_id", UUID.class),
        rs.getString("site_timezone"),
        rs.getObject("start_local", LocalDateTime.class),
        rs.getObject("end_local", LocalDateTime.class),
        rs.getTimestamp("start_at").toInstant(),
        rs.getTimestamp("end_at").toInstant(),
        rs.getString("label_en"),
        rs.getString("label_fr"),
        rs.getObject("assigned_employee_id", UUID.class),
        rs.getBoolean("operational"));
  }
}
