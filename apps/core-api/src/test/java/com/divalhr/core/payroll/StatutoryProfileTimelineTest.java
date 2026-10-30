package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.StatutoryClassification;
import com.divalhr.core.payroll.domain.StatutoryEvidence;
import com.divalhr.core.payroll.domain.StatutoryLineBlocker;
import com.divalhr.core.payroll.domain.StatutoryProfile;
import com.divalhr.core.payroll.domain.StatutoryProfileTimeline;
import com.divalhr.core.payroll.domain.StatutoryProfileTimeline.Blocker;
import com.divalhr.core.payroll.domain.StatutoryProfileTimeline.PeriodProfile;
import com.divalhr.core.payroll.domain.StatutoryProfileTimeline.Plan;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-094B (ADR 0034 section 5 and 6): the effective-dated statutory profile timeline. */
class StatutoryProfileTimelineTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

  private static LocalDate d(String text) {
    return LocalDate.parse(text);
  }

  private static StatutoryProfile resident(int sequence, String from, boolean cnss, boolean irpp) {
    return new StatutoryProfile(
        UUID.randomUUID(),
        sequence,
        d(from),
        StatutoryClassification.STANDARD_RESIDENT,
        cnss,
        irpp,
        StatutoryEvidence.EMPLOYMENT_CONTRACT);
  }

  private static StatutoryProfile unsupported(int sequence, String from) {
    return new StatutoryProfile(
        UUID.randomUUID(),
        sequence,
        d(from),
        StatutoryClassification.UNSUPPORTED,
        false,
        false,
        StatutoryEvidence.PROFESSIONAL_REVIEW);
  }

  @Test
  void anUnsupportedProfileIsSubjectToNothing() {
    assertThatThrownBy(
            () ->
                new StatutoryProfile(
                    UUID.randomUUID(),
                    1,
                    d("2026-01-01"),
                    StatutoryClassification.UNSUPPORTED,
                    true,
                    false,
                    StatutoryEvidence.EMPLOYMENT_CONTRACT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StatutoryProfile(
                    UUID.randomUUID(),
                    1,
                    d("2026-01-01"),
                    StatutoryClassification.UNSUPPORTED,
                    false,
                    true,
                    StatutoryEvidence.EMPLOYMENT_CONTRACT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theProfileInForceIsTheGreatestEffectiveDateNotAfterTheDayThenTheGreatestSequence() {
    StatutoryProfile first = resident(1, "2026-01-01", true, true);
    StatutoryProfile second = resident(2, "2026-04-01", true, false);
    StatutoryProfile correction = resident(3, "2026-04-01", false, false);
    StatutoryProfile backdated = resident(4, "2025-07-01", false, true);
    List<StatutoryProfile> timeline = List.of(correction, first, backdated, second);
    assertThat(StatutoryProfileTimeline.inForce(timeline, d("2025-06-30"))).isEmpty();
    assertThat(StatutoryProfileTimeline.inForce(timeline, d("2025-07-01"))).contains(backdated);
    assertThat(StatutoryProfileTimeline.inForce(timeline, d("2025-12-31"))).contains(backdated);
    assertThat(StatutoryProfileTimeline.inForce(timeline, d("2026-01-01"))).contains(first);
    assertThat(StatutoryProfileTimeline.inForce(timeline, d("2026-03-31"))).contains(first);
    // Two rows on the same date: the later sequence is the correction and wins.
    assertThat(StatutoryProfileTimeline.inForce(timeline, d("2026-04-01"))).contains(correction);
    assertThat(StatutoryProfileTimeline.inForce(timeline, d("2030-01-01"))).contains(correction);
    assertThat(StatutoryProfileTimeline.inForce(List.of(), d("2026-04-01"))).isEmpty();
  }

  @Test
  void theVersionIsTheNumberOfRowsAndTheTimelineIsOrderedByDateThenSequence() {
    StatutoryProfile a = resident(1, "2026-04-01", true, true);
    StatutoryProfile b = resident(2, "2026-01-01", true, true);
    StatutoryProfile c = resident(3, "2026-04-01", false, false);
    assertThat(StatutoryProfileTimeline.version(List.of())).isEqualTo(0);
    assertThat(StatutoryProfileTimeline.version(List.of(a, b, c))).isEqualTo(3);
    assertThat(StatutoryProfileTimeline.chronological(List.of(c, a, b))).containsExactly(b, a, c);
  }

  @Test
  void aPeriodWithoutAProfileOnItsFirstDayIsMissing() {
    PeriodProfile none =
        StatutoryProfileTimeline.forPeriod(List.of(), d("2026-09-01"), d("2026-09-30"));
    assertThat(none.profile()).isEmpty();
    assertThat(none.blockers()).containsExactly(StatutoryLineBlocker.PROFILE_MISSING);
    assertThat(none.usable()).isFalse();
    PeriodProfile later =
        StatutoryProfileTimeline.forPeriod(
            List.of(resident(1, "2026-10-01", true, true)), d("2026-09-01"), d("2026-09-30"));
    assertThat(later.blockers()).containsExactly(StatutoryLineBlocker.PROFILE_MISSING);
  }

  @Test
  void anUnsupportedProfileBlocksAndAStableOneIsUsable() {
    PeriodProfile blocked =
        StatutoryProfileTimeline.forPeriod(
            List.of(unsupported(1, "2026-01-01")), d("2026-09-01"), d("2026-09-30"));
    assertThat(blocked.blockers()).containsExactly(StatutoryLineBlocker.PROFILE_UNSUPPORTED);
    assertThat(blocked.profile()).isPresent();
    StatutoryProfile stable = resident(1, "2026-09-01", true, true);
    PeriodProfile ok =
        StatutoryProfileTimeline.forPeriod(List.of(stable), d("2026-09-01"), d("2026-09-30"));
    assertThat(ok.usable()).isTrue();
    assertThat(ok.profile()).contains(stable);
  }

  @Test
  void aRowTakingEffectAfterTheFirstAndUpToTheLastEligibleDayChangesTheMonth() {
    StatutoryProfile base = resident(1, "2026-01-01", true, true);
    // After the first day, up to and including the last day: changes in the month.
    for (String change : new String[] {"2026-09-02", "2026-09-15", "2026-09-30"}) {
      PeriodProfile period =
          StatutoryProfileTimeline.forPeriod(
              List.of(base, resident(2, change, true, false)), d("2026-09-01"), d("2026-09-30"));
      assertThat(period.blockers())
          .as(change)
          .containsExactly(StatutoryLineBlocker.PROFILE_CHANGES_IN_MONTH);
      assertThat(period.profile()).contains(base);
    }
    // On the first day itself, or after the last day, the profile holds for the whole period.
    for (String fine : new String[] {"2026-09-01", "2026-10-01", "2026-08-31"}) {
      PeriodProfile period =
          StatutoryProfileTimeline.forPeriod(
              List.of(base, resident(2, fine, true, false)), d("2026-09-01"), d("2026-09-30"));
      assertThat(period.blockers()).as(fine).isEmpty();
    }
    // A shorter eligible period: the change on the 20th is after the 15th, the last eligible day.
    assertThat(
            StatutoryProfileTimeline.forPeriod(
                    List.of(base, resident(2, "2026-09-20", true, false)),
                    d("2026-09-05"),
                    d("2026-09-15"))
                .blockers())
        .isEmpty();
  }

  @Test
  void theBlockersAreReportedTogetherInTheirDeclarationOrder() {
    // Nothing on the first day and a row in the month: missing and changes.
    PeriodProfile missingAndChanging =
        StatutoryProfileTimeline.forPeriod(
            List.of(resident(1, "2026-09-10", true, true)), d("2026-09-01"), d("2026-09-30"));
    assertThat(missingAndChanging.blockers())
        .containsExactly(
            StatutoryLineBlocker.PROFILE_MISSING, StatutoryLineBlocker.PROFILE_CHANGES_IN_MONTH);
    PeriodProfile unsupportedAndChanging =
        StatutoryProfileTimeline.forPeriod(
            List.of(unsupported(1, "2026-01-01"), resident(2, "2026-09-10", true, true)),
            d("2026-09-01"),
            d("2026-09-30"));
    assertThat(unsupportedAndChanging.blockers())
        .containsExactly(
            StatutoryLineBlocker.PROFILE_UNSUPPORTED,
            StatutoryLineBlocker.PROFILE_CHANGES_IN_MONTH);
    assertThatThrownBy(
            () ->
                StatutoryProfileTimeline.forPeriod(List.of(), d("2026-09-30"), d("2026-09-01")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNewRowIsPlannedAndTheTimelineAfterItIsOrdered() {
    StatutoryProfile first = resident(1, "2026-01-01", true, true);
    StatutoryProfile candidate = resident(2, "2026-04-01", true, false);
    Plan plan = StatutoryProfileTimeline.plan(List.of(first), candidate, TODAY);
    assertThat(plan.committable()).isTrue();
    assertThat(plan.blockers()).isEmpty();
    assertThat(plan.after()).containsExactly(first, candidate);
    // A backdated row sorts before the existing one.
    StatutoryProfile backdated = resident(2, "2025-12-01", false, true);
    assertThat(StatutoryProfileTimeline.plan(List.of(first), backdated, TODAY).after())
        .containsExactly(backdated, first);
    // The first row of an employee.
    assertThat(StatutoryProfileTimeline.plan(List.of(), first, TODAY).committable()).isTrue();
  }

  @Test
  void theEffectiveDateIsFromYear2000ToTodayPlus366Days() {
    StatutoryProfile existing = resident(1, "2026-01-01", true, true);
    LocalDate latest = TODAY.plusDays(366);
    assertThat(
            StatutoryProfileTimeline.plan(
                    List.of(existing), resident(2, "2000-01-01", true, false), TODAY)
                .blockers())
        .isEmpty();
    assertThat(
            StatutoryProfileTimeline.plan(
                    List.of(existing), resident(2, "1999-12-31", true, false), TODAY)
                .blockers())
        .containsExactly(Blocker.EFFECTIVE_DATE_OUT_OF_RANGE);
    assertThat(
            StatutoryProfileTimeline.plan(
                    List.of(existing), resident(2, latest.toString(), true, false), TODAY)
                .blockers())
        .isEmpty();
    Plan tooFar =
        StatutoryProfileTimeline.plan(
            List.of(existing), resident(2, latest.plusDays(1).toString(), true, false), TODAY);
    assertThat(tooFar.blockers()).containsExactly(Blocker.EFFECTIVE_DATE_OUT_OF_RANGE);
    assertThat(tooFar.committable()).isFalse();
    // A blocked plan leaves the timeline unchanged.
    assertThat(tooFar.after()).containsExactly(existing);
  }

  @Test
  void aRowIdenticalToTheOneInForceOnItsDateIsNoChange() {
    StatutoryProfile first = resident(1, "2026-01-01", true, true);
    // Same classification, flags and evidence on a later date: no change.
    Plan same =
        StatutoryProfileTimeline.plan(List.of(first), resident(2, "2026-06-01", true, true), TODAY);
    assertThat(same.blockers()).containsExactly(Blocker.NO_CHANGE);
    // A different flag, classification or evidence is a change.
    assertThat(
            StatutoryProfileTimeline.plan(
                    List.of(first), resident(2, "2026-06-01", true, false), TODAY)
                .committable())
        .isTrue();
    assertThat(
            StatutoryProfileTimeline.plan(List.of(first), unsupported(2, "2026-06-01"), TODAY)
                .committable())
        .isTrue();
    StatutoryProfile otherEvidence =
        new StatutoryProfile(
            UUID.randomUUID(),
            2,
            d("2026-06-01"),
            StatutoryClassification.STANDARD_RESIDENT,
            true,
            true,
            StatutoryEvidence.PROFESSIONAL_REVIEW);
    assertThat(StatutoryProfileTimeline.plan(List.of(first), otherEvidence, TODAY).committable())
        .isTrue();
    // Compared with the row in force ON THAT DATE: a backdated copy of a later row differs from
    // the (absent) earlier state, an identical row after a different one is a change.
    StatutoryProfile later = resident(2, "2026-06-01", false, false);
    List<StatutoryProfile> two = List.of(first, later);
    assertThat(
            StatutoryProfileTimeline.plan(two, resident(3, "2026-03-01", true, true), TODAY)
                .blockers())
        .containsExactly(Blocker.NO_CHANGE);
    assertThat(
            StatutoryProfileTimeline.plan(two, resident(3, "2026-07-01", true, true), TODAY)
                .committable())
        .isTrue();
    assertThat(
            StatutoryProfileTimeline.plan(two, resident(3, "2025-12-01", true, true), TODAY)
                .committable())
        .isTrue();
  }

  @Test
  void anEmployeeHasAtMostOneHundredRows() {
    List<StatutoryProfile> rows = new ArrayList<>();
    for (int i = 1; i <= 99; i++) {
      rows.add(resident(i, "2026-01-01", i % 2 == 0, i % 2 == 1));
    }
    StatutoryProfile hundredth = resident(100, "2026-02-01", true, true);
    assertThat(StatutoryProfileTimeline.plan(rows, hundredth, TODAY).committable()).isTrue();
    rows.add(hundredth);
    Plan full =
        StatutoryProfileTimeline.plan(rows, resident(101, "2026-03-01", false, false), TODAY);
    assertThat(full.blockers()).containsExactly(Blocker.LIMIT_REACHED);
    assertThat(full.after()).hasSize(100);
    // The blockers come in declaration order when several apply.
    Plan several =
        StatutoryProfileTimeline.plan(rows, resident(101, "1990-01-01", false, false), TODAY);
    assertThat(several.blockers())
        .containsExactly(Blocker.EFFECTIVE_DATE_OUT_OF_RANGE, Blocker.LIMIT_REACHED);
    Plan all =
        StatutoryProfileTimeline.plan(rows, resident(101, "2026-03-01", true, true), TODAY);
    assertThat(all.blockers()).containsExactly(Blocker.NO_CHANGE, Blocker.LIMIT_REACHED);
  }
}
