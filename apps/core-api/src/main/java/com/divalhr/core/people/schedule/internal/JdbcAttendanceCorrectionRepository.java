package com.divalhr.core.people.schedule.internal;

import com.divalhr.core.people.schedule.domain.Attendance;
import com.divalhr.core.people.schedule.domain.Attendance.Record;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Entry;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Operation;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Reason;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Times;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * MVP-062 (V31): the append-only attendance corrections and the guarded projection updates they
 * drive. Every query names the verified tenant and is driven by a key: a record's history by {@code
 * attendance_correction_version_unique}, a record by its primary key, a slot's record by {@code
 * attendance_record_slot_employee_unique}. Runs in the caller's transaction; the caller holds the
 * tenant's scheduling lock, the schedule and the record for every write.
 */
@Repository
public class JdbcAttendanceCorrectionRepository {

  private static final String ENTRY_COLUMNS =
      "c.id, c.record_id, c.version, c.operation, c.before_checked_in_at,"
          + " c.before_checked_out_at, c.after_checked_in_at, c.after_checked_out_at,"
          + " c.reason_code, c.explanation, c.corrected_by, c.corrected_at";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcAttendanceCorrectionRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The slot's effective assignee at its end: the substitute approved before the scheduled end,
   * otherwise the published assignee (MVP-060C's missing check-in, C60C-2).
   *
   * @param tenant verified tenant
   * @param slotId slot
   * @return the employee, or empty for an unknown, foreign or unassigned slot
   */
  public Optional<UUID> effectiveAssignee(TenantId tenant, UUID slotId) {
    return jdbc
        .query(
            "SELECT COALESCE((SELECT d.replacement_employee_id"
                + " FROM people.work_schedule_substitution_decision d"
                + " WHERE d.tenant_id = l.tenant_id AND d.slot_id = l.id AND d.outcome = 'APPROVED'"
                + " AND d.decided_at < l.end_at), l.assigned_employee_id) AS employee_id"
                + " FROM people.work_schedule_slot l WHERE l.tenant_id = :tenant AND l.id = :slot",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("slot", slotId),
            (rs, n) -> rs.getObject("employee_id", UUID.class))
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * Records a correction (the guard checks its sequence or creation rule), before the projection
   * changes.
   *
   * @param tenant verified tenant
   * @param entry the correction
   * @param target the record's immutable binding (its after values are ignored)
   * @param previewDigest the reviewed preview's digest
   * @param idempotencyDigest digest of the idempotency reference
   */
  public void insert(
      TenantId tenant, Entry entry, Record target, String previewDigest, String idempotencyDigest) {
    jdbc.update(
        "INSERT INTO people.attendance_correction (tenant_id, id, record_id, version, operation,"
            + " schedule_id, slot_id, employee_id, employment_id, slot_start_at, slot_end_at,"
            + " before_checked_in_at, before_checked_out_at, after_checked_in_at,"
            + " after_checked_out_at, reason_code, explanation, corrected_by, preview_digest,"
            + " idempotency_digest, corrected_at) VALUES (:tenant, :id, :record, :version,"
            + " :operation, :schedule, :slot, :employee, :employment, :start, :end, :beforeIn,"
            + " :beforeOut, :afterIn, :afterOut, :reason, :explanation, :by, :preview, :digest,"
            + " :at)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", entry.id())
            .addValue("record", entry.recordId())
            .addValue("version", entry.version())
            .addValue("operation", entry.operation().name())
            .addValue("schedule", target.scheduleId())
            .addValue("slot", target.slotId())
            .addValue("employee", target.employeeId())
            .addValue("employment", target.employmentId())
            .addValue("start", Timestamp.from(target.slotStartAt()))
            .addValue("end", Timestamp.from(target.slotEndAt()))
            .addValue(
                "beforeIn", stamp(entry.before() == null ? null : entry.before().checkedInAt()))
            .addValue(
                "beforeOut", stamp(entry.before() == null ? null : entry.before().checkedOutAt()))
            .addValue("afterIn", stamp(entry.after().checkedInAt()))
            .addValue("afterOut", stamp(entry.after().checkedOutAt()))
            .addValue("reason", entry.reason().name())
            .addValue("explanation", entry.explanation())
            .addValue("by", entry.correctedBy())
            .addValue("preview", previewDigest)
            .addValue("digest", idempotencyDigest)
            .addValue("at", Timestamp.from(entry.correctedAt())));
  }

  /**
   * Inserts the record a CREATE_MISSING_RECORD correction created (version 1, no event).
   *
   * @param tenant verified tenant
   * @param record the record, with its after values, version 1 and correction instant
   */
  public void createRecord(TenantId tenant, Record record) {
    jdbc.update(
        "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id, employee_id,"
            + " employment_id, slot_start_at, slot_end_at, check_out_until, check_in_event_id,"
            + " checked_in_at, check_out_event_id, checked_out_at, correction_version,"
            + " corrected_at) VALUES (:tenant, :id, :schedule, :slot, :employee, :employment,"
            + " :start, :end, :until, NULL, :in, NULL, :out, 1, :at)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", record.id())
            .addValue("schedule", record.scheduleId())
            .addValue("slot", record.slotId())
            .addValue("employee", record.employeeId())
            .addValue("employment", record.employmentId())
            .addValue("start", Timestamp.from(record.slotStartAt()))
            .addValue("end", Timestamp.from(record.slotEndAt()))
            .addValue("until", Timestamp.from(Attendance.checkOutUntil(record.slotEndAt())))
            .addValue("in", Timestamp.from(record.checkedInAt()))
            .addValue("out", stamp(record.checkedOutAt()))
            .addValue("at", Timestamp.from(record.correctedAt())));
  }

  /**
   * Applies a REPLACE_TIMES correction to its record: the next version, its after values and its
   * instant, from exactly the previewed version.
   *
   * @param tenant verified tenant
   * @param recordId record
   * @param fromVersion the record's current version
   * @param after the after values
   * @param at the correction instant
   * @return rows updated (1)
   */
  public int replace(TenantId tenant, UUID recordId, int fromVersion, Times after, Instant at) {
    return jdbc.update(
        "UPDATE people.attendance_record SET checked_in_at = :in, checked_out_at = :out,"
            + " correction_version = correction_version + 1, corrected_at = :at"
            + " WHERE tenant_id = :tenant AND id = :id AND correction_version = :version",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", recordId)
            .addValue("version", fromVersion)
            .addValue("in", Timestamp.from(after.checkedInAt()))
            .addValue("out", stamp(after.checkedOutAt()))
            .addValue("at", Timestamp.from(at)));
  }

  /**
   * A record's corrections, newest first (one probe of {@code
   * attendance_correction_version_unique}, read backwards).
   *
   * @param tenant verified tenant
   * @param recordId record
   * @return the history
   */
  public List<Entry> history(TenantId tenant, UUID recordId) {
    return jdbc.query(
        "SELECT "
            + ENTRY_COLUMNS
            + " FROM people.attendance_correction c WHERE c.tenant_id = :tenant"
            + " AND c.record_id = :record ORDER BY c.version DESC",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("record", recordId),
        (rs, n) -> entry(rs));
  }

  /**
   * A record's original events, unchanged (one probe of {@code
   * attendance_event_record_type_unique}): check-in first.
   *
   * @param tenant verified tenant
   * @param recordId record
   * @return the events
   */
  public List<OriginalEvent> originalEvents(TenantId tenant, UUID recordId) {
    return jdbc.query(
        "SELECT e.event_type, e.recorded_at, e.recording_method FROM people.attendance_event e"
            + " WHERE e.tenant_id = :tenant AND e.record_id = :record ORDER BY e.event_type",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("record", recordId),
        (rs, n) ->
            new OriginalEvent(
                Attendance.EventType.valueOf(rs.getString("event_type")),
                rs.getTimestamp("recorded_at").toInstant(),
                Attendance.Method.valueOf(rs.getString("recording_method"))));
  }

  /**
   * One original event.
   *
   * @param type the type
   * @param recordedAt its instant
   * @param method how it was recorded
   */
  public record OriginalEvent(
      Attendance.EventType type, Instant recordedAt, Attendance.Method method) {
    /**
     * The type and method only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "OriginalEvent[" + type + ", " + method + "]";
    }
  }

  private static Entry entry(ResultSet rs) throws SQLException {
    Instant beforeIn = instant(rs, "before_checked_in_at");
    return new Entry(
        rs.getObject("id", UUID.class),
        rs.getObject("record_id", UUID.class),
        rs.getInt("version"),
        Operation.valueOf(rs.getString("operation")),
        beforeIn == null ? null : new Times(beforeIn, instant(rs, "before_checked_out_at")),
        new Times(instant(rs, "after_checked_in_at"), instant(rs, "after_checked_out_at")),
        Reason.valueOf(rs.getString("reason_code")),
        rs.getString("explanation"),
        rs.getString("corrected_by"),
        instant(rs, "corrected_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp stamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
