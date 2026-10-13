package com.divalhr.core.people.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.people.schedule.api.SyncOfflineAttendanceRequest;
import com.divalhr.core.people.schedule.domain.OfflineAttendance;
import com.divalhr.core.people.schedule.domain.OfflineAttendance.Safeguard;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-061A (A61A-3, A61A-9 items 3 to 5): the strict, fail-closed reading of an offline submission
 * and the fixed safeguards of the claimed device time, each exact at its bound and refused one
 * microsecond beyond, including the post-window bound, which the existing windows make unreachable
 * through the API (it is implied by the age bound).
 */
class OfflineAttendanceCommandTest {

  private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);
  private static final String CLIENT = "0f0e0d0c-0b0a-4908-8706-050403020100";
  private static final String SLOT = "11111111-2222-4333-8444-555555555555";

  private static SyncOfflineAttendanceRequest request(
      Object client, Object slot, Object type, Object at, Object offset) {
    SyncOfflineAttendanceRequest request = new SyncOfflineAttendanceRequest();
    request.setClientEventId(client);
    request.setSlotId(slot);
    request.setType(type);
    request.setDeviceOccurredAt(at);
    request.setDeviceOffsetMinutes(offset);
    return request;
  }

  private static OfflineAttendanceCommand.Sync valid(String at, Object offset) {
    return OfflineAttendanceCommand.sync(request(CLIENT, SLOT, "CHECK_IN", at, offset));
  }

  private static List<Object> fields(Runnable call) {
    try {
      call.run();
    } catch (ApiException e) {
      assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
      return List.copyOf((List<?>) e.params().get("fields"));
    }
    throw new AssertionError("expected VALIDATION_FAILED");
  }

  private static ErrorCode code(Runnable call) {
    try {
      call.run();
    } catch (ApiException e) {
      assertThat(e.params()).isEmpty();
      return e.code();
    }
    throw new AssertionError("expected a refusal");
  }

  @Test
  void anExactSubmissionIsReadToTheMicrosecond() {
    OfflineAttendanceCommand.Sync sync =
        OfflineAttendanceCommand.sync(
            request(
                CLIENT.toUpperCase(java.util.Locale.ROOT),
                SLOT,
                "CHECK_OUT",
                "2026-11-02T08:15:30.123456+01:00",
                60));
    assertThat(sync.clientEventId()).isEqualTo(UUID.fromString(CLIENT));
    assertThat(sync.slotId()).isEqualTo(UUID.fromString(SLOT));
    assertThat(sync.type()).isEqualTo(OfflineAttendance.Type.CHECK_OUT);
    assertThat(sync.occurredAt()).isEqualTo(Instant.parse("2026-11-02T07:15:30.123456Z"));
    assertThat(sync.offsetMinutes()).isEqualTo(60);
    assertThat(sync.toString()).doesNotContain(CLIENT).doesNotContain(SLOT).doesNotContain("07:15");
    assertThat(valid("2026-11-02T07:15:30Z", 0).occurredAt())
        .isEqualTo(Instant.parse("2026-11-02T07:15:30Z"));
    // Both offset bounds, exactly.
    assertThat(valid("2026-11-02T21:15:30+14:00", 840).occurredAt())
        .isEqualTo(Instant.parse("2026-11-02T07:15:30Z"));
    assertThat(valid("2026-11-01T17:15:30-14:00", -840).occurredAt())
        .isEqualTo(Instant.parse("2026-11-02T07:15:30Z"));
    assertThat(valid("2026-11-02T03:00:00.5-04:30", -270).offsetMinutes()).isEqualTo(-270);
  }

  @Test
  void malformedImpossibleAndOutOfBoundTimestampsFailClosed() {
    for (Object at :
        List.of(
            "2026-11-02T08:15:30",
            "2026-11-02 08:15:30+01:00",
            "2026-11-02t08:15:30+01:00",
            "2026-11-02T08:15:30z",
            "2026-11-02T08:15+01:00",
            "2026-11-02T08:15:30.1234567+01:00",
            "2026-11-02T08:15:30+0100",
            "2026-11-02T08:15:30+01",
            "2026-11-02T08:15:30+01:00:00",
            "2026-11-02T08:15:30+14:01",
            "2026-11-02T08:15:30-14:01",
            "2026-11-02T08:15:30+15:00",
            "2026-11-02T08:15:30+01:60",
            "2026-11-02T08:15:30-00:00",
            "2026-02-30T08:15:30+01:00",
            "2026-11-02T24:00:00+01:00",
            "2026-12-31T23:59:60Z",
            "2026-11-02T08:15:30+01:00[Africa/Kinshasa]",
            " 2026-11-02T08:15:30+01:00",
            "",
            1_793_000_000L,
            List.of("2026-11-02T08:15:30+01:00"))) {
      assertThat(
              fields(
                  () -> OfflineAttendanceCommand.sync(request(CLIENT, SLOT, "CHECK_IN", at, 60))))
          .as("%s", at)
          .containsExactly(Map.of("field", "deviceOccurredAt", "constraint", "FORMAT"));
    }
  }

  @Test
  void theOffsetIsAnIntegerInRangeAndEqualToTheTimestampsOffset() {
    for (Object offset : List.of("60", 60.0, 60.5, true, List.of(60))) {
      assertThat(
              fields(
                  () ->
                      OfflineAttendanceCommand.sync(
                          request(CLIENT, SLOT, "CHECK_IN", "2026-11-02T08:15:30Z", offset))))
          .as("%s", offset)
          .containsExactly(Map.of("field", "deviceOffsetMinutes", "constraint", "FORMAT"));
    }
    for (Object offset :
        List.of(841, -841, 4_000_000_000L, new BigInteger("99999999999999999999"))) {
      assertThat(
              fields(
                  () ->
                      OfflineAttendanceCommand.sync(
                          request(CLIENT, SLOT, "CHECK_IN", "2026-11-02T08:15:30Z", offset))))
          .as("%s", offset)
          .containsExactly(Map.of("field", "deviceOffsetMinutes", "constraint", "RANGE"));
    }
    // Same instant, another representation: the offsets must agree exactly.
    assertThat(code(() -> valid("2026-11-02T08:15:30+01:00", 0)))
        .isEqualTo(ErrorCode.OFFLINE_ATTENDANCE_OFFSET_MISMATCH);
    assertThat(code(() -> valid("2026-11-02T07:15:30Z", 60)))
        .isEqualTo(ErrorCode.OFFLINE_ATTENDANCE_OFFSET_MISMATCH);
    assertThat(code(() -> valid("2026-11-02T08:15:30+01:00", -60)))
        .isEqualTo(ErrorCode.OFFLINE_ATTENDANCE_OFFSET_MISMATCH);
  }

  @Test
  void everyFieldIsRequiredShapedAndNeverEchoed() {
    assertThat(fields(() -> OfflineAttendanceCommand.sync(null)))
        .containsExactly(Map.of("field", "body", "constraint", "REQUIRED"));
    assertThat(fields(() -> OfflineAttendanceCommand.sync(request(null, null, null, null, null))))
        .containsExactly(
            Map.of("field", "clientEventId", "constraint", "REQUIRED"),
            Map.of("field", "slotId", "constraint", "REQUIRED"),
            Map.of("field", "type", "constraint", "REQUIRED"),
            Map.of("field", "deviceOccurredAt", "constraint", "REQUIRED"),
            Map.of("field", "deviceOffsetMinutes", "constraint", "REQUIRED"));
    assertThat(
            fields(
                () ->
                    OfflineAttendanceCommand.sync(
                        request("nope", SLOT, "check_in", "2026-11-02T08:15:30Z", 0))))
        .containsExactly(
            Map.of("field", "clientEventId", "constraint", "FORMAT"),
            Map.of("field", "type", "constraint", "FORMAT"));
    // A malformed slot is the same 404 as an unknown one, after the field checks.
    assertThat(
            code(
                () ->
                    OfflineAttendanceCommand.sync(
                        request(CLIENT, "nope", "CHECK_IN", "2026-11-02T08:15:30Z", 0))))
        .isEqualTo(ErrorCode.ATTENDANCE_SHIFT_NOT_FOUND);
    assertThat(
            code(
                () ->
                    OfflineAttendanceCommand.sync(
                        request(CLIENT, 42, "CHECK_OUT", "2026-11-02T08:15:30Z", 0))))
        .isEqualTo(ErrorCode.ATTENDANCE_RECORD_NOT_FOUND);
  }

  @Test
  void theSafeguardsAreExactAtEveryBound() {
    Instant received = Instant.parse("2026-11-05T12:00:00Z");
    Instant farClose = received.plus(Duration.ofDays(30));
    // At most 5 minutes in the future.
    assertThat(OfflineAttendance.refusal(received.plus(Duration.ofMinutes(5)), received, farClose))
        .isEmpty();
    assertThat(
            OfflineAttendance.refusal(
                received.plus(Duration.ofMinutes(5)).plus(MICRO), received, farClose))
        .contains(Safeguard.FUTURE_TIME);
    // At most 72 hours old.
    assertThat(OfflineAttendance.refusal(received.minus(Duration.ofHours(72)), received, farClose))
        .isEmpty();
    assertThat(
            OfflineAttendance.refusal(
                received.minus(Duration.ofHours(72)).minus(MICRO), received, farClose))
        .contains(Safeguard.TOO_OLD);
    // At most 72 hours after the window closed.
    Instant occurred = received.minus(Duration.ofHours(1));
    assertThat(OfflineAttendance.refusal(occurred, received, received.minus(Duration.ofHours(72))))
        .isEmpty();
    assertThat(
            OfflineAttendance.refusal(
                occurred, received, received.minus(Duration.ofHours(72)).minus(MICRO)))
        .contains(Safeguard.TOO_OLD);
    assertThat(OfflineAttendance.MAX_OFFSET_MINUTES).isEqualTo(840);
  }
}
