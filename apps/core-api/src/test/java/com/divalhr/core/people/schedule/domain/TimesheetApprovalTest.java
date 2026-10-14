package com.divalhr.core.people.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.people.schedule.domain.Timesheet.Totals;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Action;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Blocker;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Kind;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Status;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-070B/C (T70BC-1 to T70BC-3, T70BC-11) and MVP-070D (T70D-1, T70D-3): each action's resulting
 * state, a reopening's binding to the approval it supersedes, the row blockers in their fixed
 * order, the route date (the site-local Sunday) and the bounded worked-minute band.
 */
class TimesheetApprovalTest {

  @Test
  void eachActionLeavesExactlyOneState() {
    assertThat(Kind.SUBMITTED.state()).isEqualTo(Status.SUBMITTED);
    assertThat(Kind.APPROVED.state()).isEqualTo(Status.APPROVED);
    assertThat(Kind.RETURNED.state()).isEqualTo(Status.DRAFT);
    assertThat(Kind.REOPENED.state()).isEqualTo(Status.REOPENED);
  }

  private static Action action(Kind kind, String digest) {
    return new Action(
        UUID.randomUUID(),
        UUID.randomUUID(),
        2,
        kind,
        1,
        kind == Kind.SUBMITTED ? null : UUID.randomUUID(),
        1,
        1,
        digest,
        new Totals(1, 1, 0, 0, 0, 470),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        Instant.parse("2026-11-08T10:00:00Z"));
  }

  @Test
  void aReopeningBindsTheApprovalItSupersedesAndOnlyAMaterialChange() {
    Action approval = action(Kind.APPROVED, "a".repeat(64));
    UUID correction = UUID.randomUUID();
    Instant at = Instant.parse("2026-11-09T08:00:00Z");
    Action reopening = Action.reopening(approval, "b".repeat(64), correction, at);
    assertThat(reopening.kind()).isEqualTo(Kind.REOPENED);
    assertThat(reopening.sequence()).isEqualTo(approval.sequence() + 1);
    assertThat(reopening.supersededActionId()).isEqualTo(approval.id());
    assertThat(reopening.submissionActionId()).isNull();
    assertThat(reopening.returnReason()).isNull();
    assertThat(reopening.correctionId()).isEqualTo(correction);
    assertThat(reopening.afterSourceDigest()).isEqualTo("b".repeat(64));
    assertThat(reopening.occurredAt()).isEqualTo(at);
    // The superseded approval's exact evidence, employee, employment, manager and cycle.
    assertThat(
            List.of(
                reopening.timesheetId(),
                reopening.cycle(),
                reopening.revision(),
                reopening.generationVersion(),
                reopening.sourceDigest(),
                reopening.totals(),
                reopening.employeeId(),
                reopening.employmentId(),
                reopening.managerEmployeeId()))
        .isEqualTo(
            List.of(
                approval.timesheetId(),
                approval.cycle(),
                approval.revision(),
                approval.generationVersion(),
                approval.sourceDigest(),
                approval.totals(),
                approval.employeeId(),
                approval.employmentId(),
                approval.managerEmployeeId()));
    // Never an immaterial change, never anything but an approval.
    assertThatThrownBy(() -> Action.reopening(approval, "a".repeat(64), correction, at))
        .isInstanceOf(IllegalArgumentException.class);
    for (Kind kind : List.of(Kind.SUBMITTED, Kind.RETURNED, Kind.REOPENED)) {
      assertThatThrownBy(
              () -> Action.reopening(action(kind, "a".repeat(64)), "b".repeat(64), correction, at))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(reopening.toString()).doesNotContain(correction.toString());
  }

  @Test
  void onlyACleanRevisionWithAReadyRowHasNoRowBlocker() {
    assertThat(TimesheetApproval.rowBlockers(new Totals(2, 2, 0, 0, 0, 900))).isEmpty();
    assertThat(TimesheetApproval.rowBlockers(new Totals(4, 1, 1, 1, 1, 60)))
        .containsExactly(
            Blocker.MISSING_ATTENDANCE, Blocker.OPEN_ATTENDANCE, Blocker.SOURCE_CHANGED);
    assertThat(TimesheetApproval.rowBlockers(new Totals(1, 0, 1, 0, 0, 0)))
        .containsExactly(Blocker.MISSING_ATTENDANCE, Blocker.NO_READY_ROWS);
    assertThat(TimesheetApproval.rowBlockers(new Totals(1, 0, 0, 0, 1, 0)))
        .containsExactly(Blocker.SOURCE_CHANGED, Blocker.NO_READY_ROWS);
  }

  @Test
  void theRouteIsResolvedOnTheWeeksSundayAndWorkedTimeOnlyAsABand() {
    assertThat(TimesheetApproval.routeDate(LocalDate.of(2026, 10, 19)))
        .isEqualTo(LocalDate.of(2026, 10, 25));
    assertThat(TimesheetApproval.workedMinuteBand(0)).isEqualTo("0");
    assertThat(TimesheetApproval.workedMinuteBand(1)).isEqualTo("1-480");
    assertThat(TimesheetApproval.workedMinuteBand(480)).isEqualTo("1-480");
    assertThat(TimesheetApproval.workedMinuteBand(481)).isEqualTo("481-2400");
    assertThat(TimesheetApproval.workedMinuteBand(2400)).isEqualTo("481-2400");
    assertThat(TimesheetApproval.workedMinuteBand(3600)).isEqualTo("2401-3600");
    assertThat(TimesheetApproval.workedMinuteBand(10_000)).isEqualTo("3601+");
  }
}
