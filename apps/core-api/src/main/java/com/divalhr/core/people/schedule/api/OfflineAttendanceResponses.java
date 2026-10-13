package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.domain.Attendance;
import com.divalhr.core.people.schedule.domain.OfflineAttendance;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * MVP-061A response shapes: the result of one offline attendance submission. Never the tenant,
 * employee, employment, subject or device offset.
 */
public final class OfflineAttendanceResponses {

  private OfflineAttendanceResponses() {}

  /**
   * The result of a submission, applied now or replayed.
   *
   * @param outcome APPLIED (201) or ALREADY_APPLIED (200)
   * @param clientEventId the client's event identity
   * @param attendanceRecordId the record
   * @param attendanceEventId the attendance event
   * @param type CHECK_IN or CHECK_OUT
   * @param deviceOccurredAt the claimed device occurrence instant
   * @param serverReceivedAt the server receipt instant of the first application
   * @param recordingMethod always EMPLOYEE_OFFLINE_SYNC
   * @param recordStatus the record's current status
   */
  @Schema(name = "OfflineAttendanceSyncResult")
  public record Result(
      OfflineAttendance.Outcome outcome,
      UUID clientEventId,
      UUID attendanceRecordId,
      UUID attendanceEventId,
      OfflineAttendance.Type type,
      Instant deviceOccurredAt,
      Instant serverReceivedAt,
      @Schema(type = "string", allowableValues = "EMPLOYEE_OFFLINE_SYNC") String recordingMethod,
      Attendance.Status recordStatus) {

    /**
     * A result with the fixed recording method.
     *
     * @param outcome outcome
     * @param clientEventId client event
     * @param attendanceRecordId record
     * @param attendanceEventId event
     * @param type type
     * @param deviceOccurredAt claimed device time
     * @param serverReceivedAt receipt instant
     * @param recordStatus the record's current status
     * @return the result
     */
    public static Result of(
        OfflineAttendance.Outcome outcome,
        UUID clientEventId,
        UUID attendanceRecordId,
        UUID attendanceEventId,
        OfflineAttendance.Type type,
        Instant deviceOccurredAt,
        Instant serverReceivedAt,
        Attendance.Status recordStatus) {
      return new Result(
          outcome,
          clientEventId,
          attendanceRecordId,
          attendanceEventId,
          type,
          deviceOccurredAt,
          serverReceivedAt,
          Attendance.Method.EMPLOYEE_OFFLINE_SYNC.name(),
          recordStatus);
    }

    /**
     * Identifiers and instants never reach a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "OfflineAttendanceSyncResult[" + outcome + ", " + type + "]";
    }
  }
}
