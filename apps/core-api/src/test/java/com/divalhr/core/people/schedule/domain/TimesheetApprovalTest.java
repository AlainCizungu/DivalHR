package com.divalhr.core.people.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.people.schedule.domain.Timesheet.Totals;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Blocker;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Kind;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Status;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * MVP-070B/C (T70BC-1 to T70BC-3, T70BC-11): each action's resulting state, the row blockers in
 * their fixed order, the route date (the site-local Sunday) and the bounded worked-minute band.
 */
class TimesheetApprovalTest {

  @Test
  void eachActionLeavesExactlyOneState() {
    assertThat(Kind.SUBMITTED.state()).isEqualTo(Status.SUBMITTED);
    assertThat(Kind.APPROVED.state()).isEqualTo(Status.APPROVED);
    assertThat(Kind.RETURNED.state()).isEqualTo(Status.DRAFT);
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
