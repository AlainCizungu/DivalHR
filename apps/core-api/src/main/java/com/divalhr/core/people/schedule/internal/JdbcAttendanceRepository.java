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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Attendance evidence and its guarded projection (MVP-060A, V28; ADR 0016) and the supervisor
 * evidence of MVP-060B (V29; ADR 0017). Every query names the verified tenant. Runs in the caller's
 * transaction; the caller holds the tenant's scheduling lock for every write (A60A-6) and, for a
 * supervisor's write, the manager-graph lock before it (B60B-6).
 */
@Repository
public class JdbcAttendanceRepository {

  private static final String RECORD_COLUMNS =
      " a.id AS record_id, a.schedule_id AS record_schedule_id, a.slot_id AS record_slot_id,"
          + " a.employee_id AS record_employee_id, a.employment_id AS record_employment_id,"
          + " a.slot_start_at, a.slot_end_at, a.check_in_event_id, a.checked_in_at,"
          + " a.check_out_event_id, a.checked_out_at";

  static final String SHIFT_COLUMNS =
      " l.id AS slot_id, l.schedule_id, s.site_id, s.site_timezone, l.start_local, l.end_local,"
          + " l.start_at, l.end_at, l.label_en, l.label_fr, l.assigned_employee_id,"
          + " (NOT people.work_schedule_terminal(s.tenant_id, s.id)) AS operational";

  /**
   * How the check-in and check-out of record {@code a} were recorded (MVP-060B): the method and a
   * supervisor's reason; never the supervisor.
   */
  static final String RECORDING_JOINS =
      " LEFT JOIN people.attendance_event ie ON ie.tenant_id = a.tenant_id"
          + " AND ie.id = a.check_in_event_id"
          + " LEFT JOIN people.attendance_supervisor_action isa ON isa.tenant_id = ie.tenant_id"
          + " AND isa.event_id = ie.id"
          + " LEFT JOIN people.attendance_event oe ON oe.tenant_id = a.tenant_id"
          + " AND oe.id = a.check_out_event_id"
          + " LEFT JOIN people.attendance_supervisor_action osa ON osa.tenant_id = oe.tenant_id"
          + " AND osa.event_id = oe.id";

  /** The columns of {@link #RECORDING_JOINS}. */
  static final String RECORDING_COLUMNS =
      " ie.recording_method AS in_method, isa.reason_locale AS in_reason_locale,"
          + " isa.reason AS in_reason, oe.recording_method AS out_method,"
          + " osa.reason_locale AS out_reason_locale, osa.reason AS out_reason";

  /**
   * An employment {@code e} that covers the shift's dates and is placed at its site on the first
   * date (the qualifying employment, MVP-060A), for a slot {@code l} of schedule {@code s}.
   */
  static final String QUALIFIES =
      " people.attendance_employment_qualifies(e.tenant_id, e.id, e.employee_id, s.site_id,"
          + " l.start_local::date, people.work_schedule_slot_last_date(l.start_local,"
          + " l.end_local))";

  /** Employment {@code :alias} reports to {@code :manager} on slot {@code l}'s first date. */
  static String reports(String employment) {
    return " EXISTS (SELECT 1 FROM people.employment_assignment ma"
        + " WHERE ma.tenant_id = :tenant AND ma.employment_id = "
        + employment
        + " AND ma.kind = 'MANAGER' AND ma.superseded_by_change_id IS NULL"
        + " AND ma.manager_employee_id = :manager AND ma.effective_from <= l.start_local::date"
        + " AND (ma.effective_to IS NULL OR ma.effective_to >= l.start_local::date))";
  }

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
    insertCheckIn(tenant, record, by, idempotencyDigest, null);
  }

  /**
   * Records a check-in, self-service ({@code supervision == null}) or by the target's manager
   * (MVP-060B): the event first, then the open record, then the supervisor action (it references
   * the event).
   *
   * @param tenant verified tenant
   * @param record the target's open record
   * @param by verified subject (evidence only)
   * @param idempotencyDigest digest of the idempotency reference
   * @param supervision the manager's evidence, or {@code null} for a self-service check-in
   */
  public void insertCheckIn(
      TenantId tenant,
      Record record,
      String by,
      String idempotencyDigest,
      Supervision supervision) {
    MapSqlParameterSource params =
        recordParams(tenant, record)
            .addValue("event", record.checkInEventId())
            .addValue("type", Attendance.EventType.CHECKED_IN.name())
            .addValue("at", Timestamp.from(record.checkedInAt()))
            .addValue("by", by)
            .addValue("digest", idempotencyDigest)
            .addValue("method", method(supervision));
    insertEvent(params);
    jdbc.update(
        "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id, employee_id,"
            + " employment_id, slot_start_at, slot_end_at, check_out_until, check_in_event_id,"
            + " checked_in_at) VALUES (:tenant, :id, :schedule, :slot, :employee, :employment,"
            + " :start, :end, :until, :event, :at)",
        params);
    insertSupervision(params, supervision);
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
    return insertCheckOut(tenant, record, eventId, at, by, idempotencyDigest, null);
  }

  /**
   * Records a check-out, self-service ({@code supervision == null}) or by the target's manager
   * (MVP-060B): the event first, then the supervisor action, then the record's single update.
   *
   * @param tenant verified tenant
   * @param record the target's open record
   * @param eventId the check-out event
   * @param at the server instant
   * @param by verified subject (evidence only)
   * @param idempotencyDigest digest of the idempotency reference
   * @param supervision the manager's evidence, or {@code null} for a self-service check-out
   * @return rows updated (1)
   */
  public int insertCheckOut(
      TenantId tenant,
      Record record,
      UUID eventId,
      Instant at,
      String by,
      String idempotencyDigest,
      Supervision supervision) {
    MapSqlParameterSource params =
        recordParams(tenant, record)
            .addValue("event", eventId)
            .addValue("type", Attendance.EventType.CHECKED_OUT.name())
            .addValue("at", Timestamp.from(at))
            .addValue("by", by)
            .addValue("digest", idempotencyDigest)
            .addValue("method", method(supervision));
    insertEvent(params);
    insertSupervision(params, supervision);
    return jdbc.update(
        "UPDATE people.attendance_record SET check_out_event_id = :event, checked_out_at = :at"
            + " WHERE tenant_id = :tenant AND id = :id AND check_out_event_id IS NULL",
        params);
  }

  /**
   * The manager's evidence of a supervisor-assisted event (MVP-060B).
   *
   * @param actionId the supervisor action
   * @param managerEmployeeId the caller's linked employee, the target's effective manager
   * @param managerEmploymentId the manager's employment on the shift's start date
   * @param reasonLocale the reason's language
   * @param reason the reason as submitted (normalized by the grammar)
   */
  public record Supervision(
      UUID actionId,
      UUID managerEmployeeId,
      UUID managerEmploymentId,
      String reasonLocale,
      String reason) {

    /**
     * Identifiers only: the reason never reaches a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Supervision[action=" + actionId + "]";
    }
  }

  private static String method(Supervision supervision) {
    return (supervision == null ? Attendance.Method.SELF : Attendance.Method.SUPERVISOR).name();
  }

  private void insertEvent(MapSqlParameterSource params) {
    if (!params.hasValue("client")) {
      params.addValue("client", null).addValue("offset", null).addValue("received", null);
    }
    jdbc.update(
        "INSERT INTO people.attendance_event (tenant_id, id, record_id, schedule_id, slot_id,"
            + " employee_id, employment_id, event_type, recorded_at, recorded_by,"
            + " idempotency_digest, recording_method, client_event_id, device_offset_minutes,"
            + " received_at) VALUES (:tenant, :event, :id, :schedule, :slot, :employee,"
            + " :employment, :type, :at, :by, :digest, :method, :client, :offset, :received)",
        params);
  }

  // ------------------------------------------------------------------------------------------
  // MVP-061A: offline synchronization (V30)
  // ------------------------------------------------------------------------------------------

  /**
   * The offline provenance of an EMPLOYEE_OFFLINE_SYNC event (MVP-061A): the client's event
   * identity, the device's UTC offset at capture and the server receipt instant. The event's own
   * instant is the claimed device time.
   *
   * @param clientEventId the client's immutable event identity
   * @param offsetMinutes the device UTC offset in minutes
   * @param receivedAt the server receipt instant
   */
  public record Offline(UUID clientEventId, int offsetMinutes, Instant receivedAt) {

    /**
     * Never an identifier or instant in a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Offline[]";
    }
  }

  /**
   * An applied offline event of the employee, found by its client identity (the replay lookup),
   * with its record's current status.
   *
   * @param eventId the attendance event
   * @param recordId its record
   * @param slotId the slot
   * @param type the event type
   * @param occurredAt the claimed device time (the event's instant)
   * @param offsetMinutes the device UTC offset in minutes
   * @param receivedAt the server receipt instant
   * @param recordStatus the record's current status
   */
  public record OfflineEvent(
      UUID eventId,
      UUID recordId,
      UUID slotId,
      Attendance.EventType type,
      Instant occurredAt,
      int offsetMinutes,
      Instant receivedAt,
      Attendance.Status recordStatus) {

    /**
     * Never an identifier or instant in a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "OfflineEvent[" + type + "]";
    }
  }

  /**
   * The employee's offline event with this client identity, if any: one probe of {@code
   * attendance_event_client_event_unique}.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's linked employee
   * @param clientEventId the client's event identity
   * @return the applied event
   */
  public Optional<OfflineEvent> offlineEvent(TenantId tenant, UUID employeeId, UUID clientEventId) {
    return jdbc
        .query(
            "SELECT e.id, e.record_id, e.slot_id, e.event_type, e.recorded_at,"
                + " e.device_offset_minutes, e.received_at, r.check_out_event_id"
                + " FROM people.attendance_event e JOIN people.attendance_record r"
                + " ON r.tenant_id = e.tenant_id AND r.id = e.record_id"
                + " WHERE e.tenant_id = :tenant AND e.client_event_id = :client"
                + " AND e.employee_id = :employee",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("client", clientEventId)
                .addValue("employee", employeeId),
            (rs, n) ->
                new OfflineEvent(
                    rs.getObject("id", UUID.class),
                    rs.getObject("record_id", UUID.class),
                    rs.getObject("slot_id", UUID.class),
                    Attendance.EventType.valueOf(rs.getString("event_type")),
                    rs.getTimestamp("recorded_at").toInstant(),
                    rs.getInt("device_offset_minutes"),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getObject("check_out_event_id") == null
                        ? Attendance.Status.OPEN
                        : Attendance.Status.COMPLETED))
        .stream()
        .findFirst();
  }

  /**
   * Every employee's record on a slot (unlocked; at most a few rows, through {@code
   * attendance_record_slot_employee_unique}).
   *
   * @param tenant verified tenant
   * @param slotId slot
   * @return the records
   */
  public List<Record> slotRecords(TenantId tenant, UUID slotId) {
    return jdbc.query(
        "SELECT"
            + RECORD_COLUMNS
            + " FROM people.attendance_record a WHERE a.tenant_id = :tenant"
            + " AND a.slot_id = :slot ORDER BY a.employee_id",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("slot", slotId),
        (rs, n) -> record(rs));
  }

  /**
   * Locks {@code FOR UPDATE} the employee's open records whose range {@code [checked_in_at,
   * check_out_until]} meets {@code [from, until]}: exactly the records {@code
   * attendance_record_one_open} would refuse a new open record against.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param from the new record's check-in instant
   * @param until the new record's check-out deadline
   * @return the overlapping open records
   */
  public List<UUID> lockOpenOverlapping(
      TenantId tenant, UUID employeeId, Instant from, Instant until) {
    return jdbc.query(
        "SELECT id FROM people.attendance_record WHERE tenant_id = :tenant"
            + " AND employee_id = :employee AND check_out_event_id IS NULL"
            + " AND checked_in_at <= :until AND check_out_until >= :from ORDER BY id FOR UPDATE",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("from", Timestamp.from(from))
            .addValue("until", Timestamp.from(until)),
        (rs, n) -> rs.getObject("id", UUID.class));
  }

  /**
   * Records an offline check-in at the claimed device time: the EMPLOYEE_OFFLINE_SYNC event with
   * its provenance, then the open record.
   *
   * @param tenant verified tenant
   * @param record the open record ({@code checkedInAt} is the claimed device time)
   * @param by verified subject (evidence only)
   * @param idempotencyDigest digest of the client event reference
   * @param offline the offline provenance
   */
  public void insertOfflineCheckIn(
      TenantId tenant, Record record, String by, String idempotencyDigest, Offline offline) {
    MapSqlParameterSource params =
        offline(
            recordParams(tenant, record)
                .addValue("event", record.checkInEventId())
                .addValue("type", Attendance.EventType.CHECKED_IN.name())
                .addValue("at", Timestamp.from(record.checkedInAt()))
                .addValue("by", by)
                .addValue("digest", idempotencyDigest),
            offline);
    insertEvent(params);
    jdbc.update(
        "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id, employee_id,"
            + " employment_id, slot_start_at, slot_end_at, check_out_until, check_in_event_id,"
            + " checked_in_at) VALUES (:tenant, :id, :schedule, :slot, :employee, :employment,"
            + " :start, :end, :until, :event, :at)",
        params);
  }

  /**
   * Records an offline check-out at the claimed device time: the EMPLOYEE_OFFLINE_SYNC event with
   * its provenance, then the record's single permitted update.
   *
   * @param tenant verified tenant
   * @param record the open record
   * @param eventId the check-out event
   * @param at the claimed device time
   * @param by verified subject (evidence only)
   * @param idempotencyDigest digest of the client event reference
   * @param offline the offline provenance
   * @return rows updated (1)
   */
  public int insertOfflineCheckOut(
      TenantId tenant,
      Record record,
      UUID eventId,
      Instant at,
      String by,
      String idempotencyDigest,
      Offline offline) {
    MapSqlParameterSource params =
        offline(
            recordParams(tenant, record)
                .addValue("event", eventId)
                .addValue("type", Attendance.EventType.CHECKED_OUT.name())
                .addValue("at", Timestamp.from(at))
                .addValue("by", by)
                .addValue("digest", idempotencyDigest),
            offline);
    insertEvent(params);
    return jdbc.update(
        "UPDATE people.attendance_record SET check_out_event_id = :event, checked_out_at = :at"
            + " WHERE tenant_id = :tenant AND id = :id AND check_out_event_id IS NULL",
        params);
  }

  private static MapSqlParameterSource offline(MapSqlParameterSource params, Offline offline) {
    return params
        .addValue("method", Attendance.Method.EMPLOYEE_OFFLINE_SYNC.name())
        .addValue("client", offline.clientEventId())
        .addValue("offset", offline.offsetMinutes())
        .addValue("received", Timestamp.from(offline.receivedAt()));
  }

  private void insertSupervision(MapSqlParameterSource params, Supervision supervision) {
    if (supervision == null) {
      return;
    }
    params
        .addValue("action", supervision.actionId())
        .addValue("manager", supervision.managerEmployeeId())
        .addValue("managerEmployment", supervision.managerEmploymentId())
        .addValue("locale", supervision.reasonLocale())
        .addValue("reason", supervision.reason());
    jdbc.update(
        "INSERT INTO people.attendance_supervisor_action (tenant_id, id, event_id, record_id,"
            + " schedule_id, slot_id, employee_id, employment_id, event_type, recorded_at,"
            + " manager_employee_id, manager_employment_id, reason_locale, reason, recorded_by,"
            + " idempotency_digest) VALUES (:tenant, :action, :event, :id, :schedule, :slot,"
            + " :employee, :employment, :type, :at, :manager, :managerEmployment, :locale,"
            + " :reason, :by, :digest)",
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
                + " people.attendance_record_exact, people.attendance_event_supervisor_exact,"
                + " people.attendance_supervisor_action_exact IMMEDIATE");
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
                + ","
                + RECORDING_COLUMNS
                + " FROM people.attendance_record a"
                + " JOIN people.work_schedule_slot l ON l.tenant_id = a.tenant_id"
                + " AND l.id = a.slot_id"
                + " JOIN people.work_schedule s ON s.tenant_id = l.tenant_id"
                + " AND s.id = l.schedule_id"
                + RECORDING_JOINS
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
    return jdbc.query(
        sql.toString(),
        params,
        (rs, n) ->
            new HistoryItem(record(rs), shift(rs), recording(rs, "in"), recording(rs, "out")));
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

  /**
   * How one event of a row read with {@link #RECORDING_JOINS} was recorded.
   *
   * @param rs the row
   * @param prefix {@code in} or {@code out}
   * @return the recording, or {@code null} when the event does not exist
   * @throws SQLException on a read failure
   */
  static Attendance.Recording recording(ResultSet rs, String prefix) throws SQLException {
    String method = rs.getString(prefix + "_method");
    if (method == null) {
      return null;
    }
    return new Attendance.Recording(
        Attendance.Method.valueOf(method),
        rs.getString(prefix + "_reason_locale"),
        rs.getString(prefix + "_reason"));
  }

  // ------------------------------------------------------------------------------------------
  // MVP-060B: the manager's authority and queue
  // ------------------------------------------------------------------------------------------

  /**
   * The employee named by the employment's active, non-superseded MANAGER line on the date (at most
   * one: MANAGER lines of an employment never overlap).
   *
   * @param tenant verified tenant
   * @param employmentId the target's employment
   * @param date the shift's site-local start date
   * @return the effective manager, or empty when the employment has none on the date
   */
  public Optional<UUID> managerOf(TenantId tenant, UUID employmentId, LocalDate date) {
    return jdbc
        .query(
            "SELECT manager_employee_id FROM people.employment_assignment WHERE tenant_id = :tenant"
                + " AND employment_id = :employment AND kind = 'MANAGER' AND"
                + " superseded_by_change_id IS NULL AND manager_employee_id IS NOT NULL AND"
                + " effective_from <= :date AND (effective_to IS NULL OR effective_to >= :date)",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("employment", employmentId)
                .addValue("date", date),
            (rs, n) -> rs.getObject("manager_employee_id", UUID.class))
        .stream()
        .findFirst();
  }

  /**
   * The employee's employment covering the date (at most one: an employee's employments never
   * overlap).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param date the date
   * @return the employment
   */
  public Optional<UUID> employmentOn(TenantId tenant, UUID employeeId, LocalDate date) {
    return jdbc
        .query(
            "SELECT id FROM people.employment WHERE tenant_id = :tenant AND employee_id = :employee"
                + " AND effective_from <= :date AND (effective_to IS NULL OR effective_to >="
                + " :date)",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("employee", employeeId)
                .addValue("date", date),
            (rs, n) -> rs.getObject("id", UUID.class))
        .stream()
        .findFirst();
  }

  /**
   * Locks {@code FOR SHARE}, in UUID order, the listed employments and the employments of the
   * employees overlapping the dates (B60B-6 step 4): employment changes, corrections and
   * separations, which lock an employment {@code FOR UPDATE} (after the manager-graph lock the
   * caller already holds), cannot change them until this commits.
   *
   * @param tenant verified tenant
   * @param employmentIds employments to lock (may be empty)
   * @param employeeIds employees whose overlapping employments are locked too (may be empty)
   * @param from first date
   * @param to last date
   */
  public void shareEmployments(
      TenantId tenant,
      Collection<UUID> employmentIds,
      Collection<UUID> employeeIds,
      LocalDate from,
      LocalDate to) {
    UUID none = new UUID(0L, 0L);
    jdbc.query(
        "SELECT id FROM people.employment WHERE tenant_id = :tenant AND (id IN (:ids)"
            + " OR (employee_id IN (:employees) AND effective_from <= :to"
            + " AND (effective_to IS NULL OR effective_to >= :from))) ORDER BY id FOR SHARE",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("ids", employmentIds.isEmpty() ? List.of(none) : List.copyOf(employmentIds))
            .addValue("employees", employeeIds.isEmpty() ? List.of(none) : List.copyOf(employeeIds))
            .addValue("from", from)
            .addValue("to", to),
        (rs, n) -> rs.getObject("id", UUID.class));
  }

  /**
   * Whether the manager has an active, non-superseded MANAGER line on any date between the two
   * (B60B-5: {@code TEAM_ATTENDANCE_MANAGER_REQUIRED} otherwise).
   *
   * @param tenant verified tenant
   * @param managerId the caller's linked employee
   * @param from first date
   * @param to last date
   * @return true when the caller manages someone in the range
   */
  public boolean managesBetween(TenantId tenant, UUID managerId, LocalDate from, LocalDate to) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM people.employment_assignment"
                + " WHERE tenant_id = :tenant AND kind = 'MANAGER'"
                + " AND superseded_by_change_id IS NULL AND manager_employee_id = :manager"
                + " AND employment_id IN (SELECT id FROM people.employment"
                + " WHERE tenant_id = :tenant AND employee_id <> :manager)"
                + " AND effective_from <= :to AND (effective_to IS NULL OR effective_to >= :from))",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("manager", managerId)
                .addValue("from", from)
                .addValue("to", to),
            Boolean.class));
  }

  /**
   * One keyset page of the actions the manager's direct reports need at the instant (B60B-4):
   * not-recorded shifts of operational schedules covered by a report, inside the check-in window,
   * the report having no other actionable open record; and the reports' open records inside their
   * check-out window. A report's one qualifying employment (the record's employment for a
   * check-out) names the manager on the shift's site-local start date; never the manager. Ordered
   * by the deadline, the shift start, the slot and the action.
   *
   * @param tenant verified tenant
   * @param managerId the caller's linked employee
   * @param at the page's server instant
   * @param after the previous page's last position, or {@code null}
   * @param limit rows to read
   * @return the rows
   */
  public List<Attendance.TeamAction> teamActions(
      TenantId tenant, UUID managerId, Instant at, TeamPosition after, int limit) {
    String checkIns =
        "SELECT 'CHECK_IN' AS action, 0 AS action_rank, l.end_at AS deadline, c.employee_id,"
            + " NULL::uuid AS record_id, NULL::timestamptz AS action_checked_in_at,"
            + SHIFT_COLUMNS
            + " FROM people.work_schedule_slot_coverage c"
            + " JOIN people.work_schedule_slot l ON l.tenant_id = c.tenant_id AND l.id = c.slot_id"
            + " JOIN people.work_schedule s ON s.tenant_id = l.tenant_id AND s.id = l.schedule_id"
            + " WHERE c.tenant_id = :tenant AND c.employee_id <> :manager"
            + " AND l.start_at <= :opensBefore AND l.end_at > :at"
            + " AND NOT people.work_schedule_terminal(s.tenant_id, s.id)"
            + " AND NOT EXISTS (SELECT 1 FROM people.attendance_record r"
            + " WHERE r.tenant_id = c.tenant_id AND r.slot_id = c.slot_id"
            + " AND r.employee_id = c.employee_id)"
            + " AND NOT EXISTS (SELECT 1 FROM people.attendance_record r"
            + " WHERE r.tenant_id = c.tenant_id AND r.employee_id = c.employee_id"
            + " AND r.check_out_event_id IS NULL AND r.check_out_until >= :at)"
            + " AND (SELECT count(*) FROM people.employment e WHERE e.tenant_id = c.tenant_id"
            + " AND e.employee_id = c.employee_id AND"
            + QUALIFIES
            + ") = 1"
            + " AND EXISTS (SELECT 1 FROM people.employment e WHERE e.tenant_id = c.tenant_id"
            + " AND e.employee_id = c.employee_id AND"
            + QUALIFIES
            + " AND"
            + reports("e.id")
            + ")";
    String checkOuts =
        "SELECT 'CHECK_OUT' AS action, 1 AS action_rank, a.check_out_until AS deadline,"
            + " a.employee_id, a.id AS record_id, a.checked_in_at AS action_checked_in_at,"
            + SHIFT_COLUMNS
            + " FROM people.attendance_record a"
            + " JOIN people.work_schedule_slot l ON l.tenant_id = a.tenant_id AND l.id = a.slot_id"
            + " JOIN people.work_schedule s ON s.tenant_id = l.tenant_id AND s.id = l.schedule_id"
            + " WHERE a.tenant_id = :tenant AND a.employee_id <> :manager"
            + " AND a.check_out_event_id IS NULL AND a.check_out_until >= :at"
            + " AND a.checked_in_at < :at AND"
            + reports("a.employment_id");
    StringBuilder sql =
        new StringBuilder(
            "SELECT x.*, p.employee_number, p.given_names, p.family_name FROM ("
                + checkIns
                + " UNION ALL "
                + checkOuts
                + ") x JOIN people.employee p ON p.tenant_id = :tenant AND p.id = x.employee_id");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("manager", managerId)
            .addValue("at", Timestamp.from(at))
            .addValue(
                "opensBefore", Timestamp.from(at.plus(Attendance.CHECK_IN_OPENS_BEFORE_START)))
            .addValue("limit", limit);
    if (after != null) {
      sql.append(
          " WHERE (x.deadline, x.start_at, x.slot_id, x.action_rank)"
              + " > (:afterDeadline, :afterStart, :afterSlot, :afterRank)");
      params
          .addValue("afterDeadline", Timestamp.from(after.deadline()))
          .addValue("afterStart", Timestamp.from(after.startAt()))
          .addValue("afterSlot", after.slotId())
          .addValue("afterRank", after.action() == Attendance.RequiredAction.CHECK_IN ? 0 : 1);
    }
    sql.append(" ORDER BY x.deadline, x.start_at, x.slot_id, x.action_rank LIMIT :limit");
    return jdbc.query(
        sql.toString(),
        params,
        (rs, n) -> {
          Timestamp in = rs.getTimestamp("action_checked_in_at");
          return new Attendance.TeamAction(
              Attendance.RequiredAction.valueOf(rs.getString("action")),
              rs.getTimestamp("deadline").toInstant(),
              shift(rs),
              rs.getObject("employee_id", UUID.class),
              rs.getString("employee_number"),
              rs.getString("given_names"),
              rs.getString("family_name"),
              rs.getObject("record_id", UUID.class),
              in == null ? null : in.toInstant());
        });
  }

  /**
   * A position in the manager's queue (a cursor), recomputed from the slot so a cursor carries no
   * instant besides the page's.
   *
   * @param action the row's action
   * @param deadline its deadline
   * @param startAt its shift start
   * @param slotId its slot
   */
  public record TeamPosition(
      Attendance.RequiredAction action, Instant deadline, Instant startAt, UUID slotId) {}

  /**
   * A slot's scheduled instants.
   *
   * @param startAt scheduled start
   * @param endAt scheduled end
   */
  public record SlotInstants(Instant startAt, Instant endAt) {}

  /**
   * A slot's scheduled instants (for a cursor position).
   *
   * @param tenant verified tenant
   * @param slotId slot
   * @return start and end, or empty for an unknown or foreign slot
   */
  public Optional<SlotInstants> slotInstants(TenantId tenant, UUID slotId) {
    return jdbc
        .query(
            "SELECT start_at, end_at FROM people.work_schedule_slot"
                + " WHERE tenant_id = :tenant AND id = :slot",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("slot", slotId),
            (rs, n) ->
                new SlotInstants(
                    rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant()))
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

  static Shift shift(ResultSet rs) throws SQLException {
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
