package com.divalhr.core.people.domain.separation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.people.domain.history.Assignment;
import com.divalhr.core.people.domain.history.AssignmentKind;
import com.divalhr.core.people.domain.history.AssignmentValue;
import com.divalhr.core.people.domain.history.ContractClassification;
import com.divalhr.core.people.domain.history.NewAssignment;
import com.divalhr.core.people.domain.history.TimelinePlan;
import com.divalhr.core.people.domain.separation.SeparationPlanner.ReportIntervals;
import com.divalhr.core.people.domain.separation.SeparationPlanner.ReportRow;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-022 separation engine (A22-2, A22-3), without a database. */
class SeparationPlannerTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);
  private static final LocalDate D = TODAY.plusDays(10);
  private static final UUID HIRE = UUID.randomUUID();
  private static final UUID X = UUID.randomUUID();
  private static final UUID Y = UUID.randomUUID();
  private static final UUID Z = UUID.randomUUID();

  private static Assignment row(
      AssignmentKind kind, LocalDate from, LocalDate to, AssignmentValue value, UUID by) {
    return new Assignment(UUID.randomUUID(), kind, from, to, value, by, by, null, null, null);
  }

  private static Assignment contract(LocalDate from, LocalDate to, UUID by) {
    return row(
        AssignmentKind.CONTRACT,
        from,
        to,
        new AssignmentValue.Contract(ContractClassification.PERMANENT),
        by);
  }

  private static Assignment manager(LocalDate from, LocalDate to, UUID manager) {
    return row(
        AssignmentKind.MANAGER, from, to, new AssignmentValue.Manager(manager), UUID.randomUUID());
  }

  @Test
  void ownRowsAreUntouchedTruncatedEndedOrBlocking() {
    Assignment ended = contract(TODAY.minusDays(90), TODAY.minusDays(40), HIRE);
    Assignment covering = contract(TODAY.minusDays(39), D.plusDays(5), HIRE);
    Assignment future = contract(D.plusDays(6), null, UUID.randomUUID());
    SeparationPlanner.Own own =
        SeparationPlanner.own(List.of(ended, covering, future), D, TODAY, UUID::randomUUID);
    assertThat(own.plan().superseded()).containsExactly(covering);
    assertThat(own.plan().created())
        .singleElement()
        .satisfies(
            copy -> {
              assertThat(copy.from()).isEqualTo(covering.from());
              assertThat(copy.to()).isEqualTo(D);
              assertThat(copy.origin()).isEqualTo(covering.origin());
              assertThat(copy.value()).isEqualTo(covering.value());
            });
    // A22-3: whatever wrote it, an active row starting after D and after today blocks.
    assertThat(own.blockers()).containsExactly(future);
  }

  @Test
  void aRetroactiveSeparationEndsRowsAlreadyInForce() {
    LocalDate lastDay = TODAY.minusDays(10);
    Assignment covering = contract(TODAY.minusDays(60), TODAY.minusDays(5), HIRE);
    Assignment inForce = contract(TODAY.minusDays(4), null, UUID.randomUUID());
    SeparationPlanner.Own own =
        SeparationPlanner.own(List.of(covering, inForce), lastDay, TODAY, UUID::randomUUID);
    assertThat(own.blockers()).isEmpty();
    assertThat(own.plan().superseded()).containsExactlyInAnyOrder(covering, inForce);
    assertThat(own.plan().created())
        .singleElement()
        .extracting(NewAssignment::to)
        .isEqualTo(lastDay);
  }

  @Test
  void reportRowsFormMaximalContiguousIntervals() {
    UUID report = UUID.randomUUID();
    UUID employment = UUID.randomUUID();
    // X until the day before T+20 (crossing D), Y, then X again in two contiguous rows.
    Assignment first = manager(TODAY.minusDays(30), TODAY.plusDays(19), X);
    Assignment second = manager(TODAY.plusDays(40), TODAY.plusDays(59), X);
    Assignment third = manager(TODAY.plusDays(60), null, X);
    Assignment before = manager(TODAY.minusDays(90), TODAY.minusDays(31), X);
    List<ReportIntervals> grouped =
        SeparationPlanner.intervals(
            List.of(
                new ReportRow(report, employment, third),
                new ReportRow(report, employment, first),
                new ReportRow(report, employment, before),
                new ReportRow(report, employment, second)),
            D);
    assertThat(grouped).hasSize(1);
    assertThat(grouped.get(0).intervals()).containsExactly(List.of(first), List.of(second, third));
    assertThat(grouped.get(0).rows()).containsExactly(first, second, third);
  }

  @Test
  void theSafetyLimitCountsIntervalsAcrossReportsNotReports() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID employment = UUID.randomUUID();
    // Two reports: one with two disjoint X intervals (X, Y, X), one with a single interval.
    List<ReportIntervals> grouped =
        SeparationPlanner.intervals(
            List.of(
                new ReportRow(
                    first, employment, manager(TODAY.plusDays(20), TODAY.plusDays(29), X)),
                new ReportRow(first, employment, manager(TODAY.plusDays(40), null, X)),
                new ReportRow(second, UUID.randomUUID(), manager(TODAY.minusDays(5), null, X))),
            D);
    assertThat(grouped).hasSize(2);
    assertThat(SeparationPlanner.intervalCount(grouped)).isEqualTo(3);
    assertThat(SeparationPlanner.tooManyIntervals(grouped, 3)).isFalse();
    assertThat(SeparationPlanner.tooManyIntervals(grouped, 2)).isTrue();
    assertThat(SeparationPlanner.intervalCount(List.of())).isZero();
  }

  @Test
  void aCrossingRowIsSplitAtTheDayAfterAndALaterRowIsRewrittenInPlace() {
    UUID change = UUID.randomUUID();
    Assignment crossing = manager(TODAY.minusDays(30), TODAY.plusDays(19), X);
    TimelinePlan split = SeparationPlanner.reportRow(change, crossing, D, Z, UUID::randomUUID);
    assertThat(split.superseded()).containsExactly(crossing);
    assertThat(split.created())
        .extracting(NewAssignment::from, NewAssignment::to, NewAssignment::value)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(crossing.from(), D, new AssignmentValue.Manager(X)),
            org.assertj.core.groups.Tuple.tuple(
                D.plusDays(1), crossing.to(), new AssignmentValue.Manager(Z)));
    assertThat(SeparationPlanner.reportChangeDate(crossing, D)).isEqualTo(D.plusDays(1));

    Assignment later = manager(TODAY.plusDays(40), null, X);
    TimelinePlan cleared = SeparationPlanner.reportRow(change, later, D, null, UUID::randomUUID);
    assertThat(cleared.superseded()).containsExactly(later);
    assertThat(cleared.created()).isEmpty();
    assertThat(SeparationPlanner.reportChangeDate(later, D)).isEqualTo(later.from());
    TimelinePlan reassigned = SeparationPlanner.reportRow(change, later, D, Y, UUID::randomUUID);
    assertThat(reassigned.created())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.from()).isEqualTo(later.from());
              assertThat(row.to()).isNull();
              assertThat(row.origin()).isEqualTo(change);
            });
  }

  @Test
  void aReversalRestoresExactlyOrRefusesWhenHistoryChanged() {
    UUID change = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    Assignment replaced =
        new Assignment(
            UUID.randomUUID(),
            AssignmentKind.CONTRACT,
            TODAY.minusDays(30),
            null,
            new AssignmentValue.Contract(ContractClassification.DAILY),
            HIRE,
            HIRE,
            null,
            change,
            java.time.Instant.now());
    Assignment written =
        new Assignment(
            UUID.randomUUID(),
            AssignmentKind.CONTRACT,
            TODAY.minusDays(30),
            D,
            replaced.value(),
            change,
            HIRE,
            null,
            null,
            null);
    TimelinePlan plan =
        SeparationPlanner.reversal(change, List.of(replaced, written), UUID::randomUUID);
    assertThat(plan.superseded()).containsExactly(written);
    assertThat(plan.created())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.from()).isEqualTo(replaced.from());
              assertThat(row.to()).isNull();
              assertThat(row.origin()).isEqualTo(HIRE);
              assertThat(row.restores()).isEqualTo(replaced.id());
            });
    Assignment changedSince =
        new Assignment(
            written.id(),
            written.kind(),
            written.from(),
            written.to(),
            written.value(),
            change,
            HIRE,
            null,
            other,
            java.time.Instant.now());
    assertThatThrownBy(
            () ->
                SeparationPlanner.reversal(
                    change, List.of(replaced, changedSince), UUID::randomUUID))
        .isInstanceOfSatisfying(
            SeparationRuleException.class,
            e ->
                assertThat(e.rule()).isEqualTo(SeparationRuleException.Rule.HISTORY_CHANGED_SINCE));
  }
}
