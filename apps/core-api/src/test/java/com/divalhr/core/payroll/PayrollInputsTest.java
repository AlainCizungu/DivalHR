package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.PayrollInputs;
import com.divalhr.core.payroll.domain.PayrollInputs.Blocker;
import com.divalhr.core.payroll.domain.PayrollInputs.Evaluation;
import com.divalhr.core.payroll.domain.PayrollInputs.Exclusion;
import com.divalhr.core.payroll.domain.PayrollInputs.Scope;
import com.divalhr.core.platform.access.LockedApprovedTime.ApprovedRow;
import com.divalhr.core.platform.access.LockedApprovedTime.Generation;
import com.divalhr.core.platform.access.LockedApprovedTime.Lock;
import com.divalhr.core.platform.access.LockedApprovedTime.Week;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-090A domain rules on the port's weeks and rows: period boundaries, blockers that fail the
 * whole period closed (including the ones the database guards keep unreachable through the API:
 * changed lock evidence, an employment placed elsewhere), exclusions with stable codes, the row and
 * employee order, the reconciliation of totals and the sensitivity of the evidence digest.
 */
class PayrollInputsTest {

  private static final UUID ENTITY = UUID.fromString("00000000-0000-4000-8000-00000000000e");
  private static final UUID OTHER_ENTITY = UUID.fromString("00000000-0000-4000-8000-00000000000f");
  private static final UUID SITE = UUID.fromString("00000000-0000-4000-8000-00000000005e");
  private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
  private static final String EVIDENCE = "e".repeat(64);
  private static final Scope SCOPE =
      new Scope(ENTITY, MONDAY, MONDAY.plusDays(6), "Africa/Kinshasa");

  private static Lock lock(String evidence, int generationVersion) {
    return new Lock(true, 1, UUID.randomUUID(), generationVersion, evidence, 2, 3, 45);
  }

  private static Generation generation(int timesheets, String evidence, Lock lock) {
    return new Generation(
        UUID.randomUUID(),
        1,
        Instant.parse("2026-10-11T22:00:00Z"),
        Instant.parse("2026-10-18T22:00:00Z"),
        evidence,
        timesheets,
        timesheets,
        lock);
  }

  private static Week week(Generation generation, boolean scheduled) {
    return new Week(SITE, MONDAY, "Africa/Kinshasa", scheduled, generation);
  }

  private static ApprovedRow row(UUID employee, UUID timesheet, LocalDate day, int minutes) {
    return row(employee, timesheet, day, minutes, ENTITY);
  }

  private static ApprovedRow row(
      UUID employee, UUID timesheet, LocalDate day, int minutes, UUID placedIn) {
    return new ApprovedRow(
        timesheet,
        1,
        UUID.randomUUID(),
        1,
        employee,
        UUID.nameUUIDFromBytes(("employment-" + employee).getBytes()),
        placedIn,
        null,
        day,
        UUID.randomUUID(),
        minutes);
  }

  @Test
  void thePeriodBoundariesAreOrderedAtMostThirtyOneDaysAndNotBefore2026() {
    assertThat(PayrollInputs.supported(MONDAY, MONDAY)).isTrue();
    assertThat(PayrollInputs.supported(MONDAY, MONDAY.plusDays(30))).isTrue();
    assertThat(PayrollInputs.supported(MONDAY, MONDAY.plusDays(31))).isFalse();
    assertThat(PayrollInputs.supported(MONDAY.plusDays(1), MONDAY)).isFalse();
    assertThat(PayrollInputs.supported(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)))
        .isFalse();
    assertThat(PayrollInputs.supported(null, MONDAY)).isFalse();
    assertThatThrownBy(() -> new Scope(ENTITY, MONDAY.plusDays(1), MONDAY, "Africa/Kinshasa"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aLockedWeekWithUnchangedEvidenceYieldsOrderedRowsAndReconciledTotals() {
    Generation generation = generation(2, EVIDENCE, lock(EVIDENCE, 1));
    UUID ana = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    UUID eve = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
    UUID anaSheet = UUID.randomUUID();
    UUID eveSheet = UUID.randomUUID();
    Evaluation evaluation =
        PayrollInputs.evaluate(
            SCOPE,
            List.of(week(generation, true)),
            Map.of(
                generation.id(),
                List.of(
                    row(eve, eveSheet, MONDAY, 10),
                    row(ana, anaSheet, MONDAY.plusDays(1), 20),
                    row(ana, anaSheet, MONDAY, 15))));
    assertThat(evaluation.ready()).isTrue();
    assertThat(evaluation.blockers()).isEmpty();
    assertThat(evaluation.rows()).hasSize(3);
    // Employee, then work date.
    assertThat(evaluation.rows().stream().map(r -> r.employeeId() + "|" + r.workDate()).toList())
        .containsExactly(ana + "|" + MONDAY, ana + "|" + MONDAY.plusDays(1), eve + "|" + MONDAY);
    assertThat(evaluation.totals().timesheets()).isEqualTo(2);
    assertThat(evaluation.totals().employees()).isEqualTo(2);
    assertThat(evaluation.totals().rows()).isEqualTo(3);
    assertThat(evaluation.totals().workedMinutes()).isEqualTo(45);
    assertThat(evaluation.employees()).hasSize(2);
    assertThat(evaluation.employees().get(0).employeeId()).isEqualTo(ana);
    assertThat(evaluation.employees().get(0).firstWorkDate()).isEqualTo(MONDAY);
    assertThat(evaluation.employees().get(0).lastWorkDate()).isEqualTo(MONDAY.plusDays(1));
    assertThat(evaluation.employees().get(0).totals().workedMinutes()).isEqualTo(35);
    assertThat(evaluation.sourceWeeks()).hasSize(1);
    assertThat(evaluation.sourceWeeks().get(0).lockEvidenceDigest()).isEqualTo(EVIDENCE);
    assertThat(evaluation.sourceWeeks().get(0).totals().workedMinutes()).isEqualTo(45);
    assertThat(evaluation.exclusions())
        .containsEntry(Exclusion.ROW_OUTSIDE_PERIOD, 0)
        .containsEntry(Exclusion.TIMESHEET_WITHOUT_TIME, 0);
    assertThat(evaluation.evidenceDigest()).matches("[0-9a-f]{64}");
    assertThat(evaluation.sourceVersions()).contains(generation.id().toString()).contains(":1:");
    // The same rows in another order give the same digest; one minute more gives another.
    Evaluation same =
        PayrollInputs.evaluate(
            SCOPE,
            List.of(week(generation, true)),
            Map.of(
                generation.id(),
                List.of(
                    row(ana, anaSheet, MONDAY, 15),
                    row(ana, anaSheet, MONDAY.plusDays(1), 20),
                    row(eve, eveSheet, MONDAY, 10))));
    assertThat(same.evidenceDigest()).isNotEqualTo(evaluation.evidenceDigest()); // new shift IDs
    Evaluation withinOrder =
        PayrollInputs.evaluate(
            SCOPE,
            List.of(week(generation, true)),
            Map.of(generation.id(), List.copyOf(reorder(evaluation))));
    assertThat(withinOrder.evidenceDigest()).isEqualTo(evaluation.evidenceDigest());
  }

  /** The evaluation's rows as approved rows again, reversed. */
  private static List<ApprovedRow> reorder(Evaluation evaluation) {
    return evaluation.rows().reversed().stream()
        .map(
            r ->
                new ApprovedRow(
                    r.timesheetId(),
                    r.timesheetRevision(),
                    r.approvalActionId(),
                    r.approvalCycle(),
                    r.employeeId(),
                    r.employmentId(),
                    ENTITY,
                    r.costCenterId(),
                    r.workDate(),
                    r.shiftId(),
                    r.workedMinutes()))
        .toList();
  }

  @Test
  void rowsOutsideThePeriodAndTimesheetsLeftWithoutTimeAreExcludedWithStableCodes() {
    Generation generation = generation(2, EVIDENCE, lock(EVIDENCE, 1));
    UUID ana = UUID.randomUUID();
    UUID eve = UUID.randomUUID();
    UUID anaSheet = UUID.randomUUID();
    UUID eveSheet = UUID.randomUUID();
    Scope cut = new Scope(ENTITY, MONDAY.plusDays(1), MONDAY.plusDays(6), "Africa/Kinshasa");
    Evaluation evaluation =
        PayrollInputs.evaluate(
            cut,
            List.of(week(generation, true)),
            Map.of(
                generation.id(),
                List.of(
                    row(eve, eveSheet, MONDAY, 10),
                    row(ana, anaSheet, MONDAY, 15),
                    row(ana, anaSheet, MONDAY.plusDays(1), 20))));
    assertThat(evaluation.ready()).isTrue();
    assertThat(evaluation.rows()).hasSize(1);
    assertThat(evaluation.totals().workedMinutes()).isEqualTo(20);
    assertThat(evaluation.exclusions())
        .containsEntry(Exclusion.ROW_OUTSIDE_PERIOD, 2)
        .containsEntry(Exclusion.TIMESHEET_WITHOUT_TIME, 1);
    assertThat(evaluation.employees()).hasSize(1);
  }

  @Test
  void everyMissingOpenChangedOrMisplacedSourceBlocksTheWholePeriod() {
    Generation never = generation(2, EVIDENCE, null);
    Generation unlocked =
        generation(2, EVIDENCE, new Lock(false, 2, UUID.randomUUID(), 1, EVIDENCE, 2, 3, 45));
    Generation changed = generation(2, "f".repeat(64), lock(EVIDENCE, 1));
    Generation refreshed = generation(2, EVIDENCE, lock(EVIDENCE, 2));
    Generation locked = generation(1, EVIDENCE, lock(EVIDENCE, 1));
    Generation empty = generation(0, EVIDENCE, null);
    UUID zoe = UUID.randomUUID();
    Evaluation evaluation =
        PayrollInputs.evaluate(
            SCOPE,
            List.of(
                week(null, true),
                week(null, false),
                week(never, true),
                week(unlocked, true),
                week(changed, true),
                week(refreshed, true),
                week(empty, true),
                week(locked, true)),
            Map.of(
                locked.id(),
                List.of(row(zoe, UUID.randomUUID(), MONDAY, 30, OTHER_ENTITY)),
                changed.id(),
                List.of(row(zoe, UUID.randomUUID(), MONDAY, 30))));
    assertThat(evaluation.ready()).isFalse();
    assertThat(evaluation.blockers())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                Blocker.WEEK_NOT_GENERATED, 1,
                Blocker.WEEK_NOT_LOCKED, 2,
                Blocker.EVIDENCE_CHANGED, 2,
                Blocker.ATTRIBUTION_AMBIGUOUS, 1));
    // The week without scheduled work and the empty generation block nothing; the changed week's
    // rows are never read into the inputs; the misplaced row is not an input.
    assertThat(evaluation.rows()).isEmpty();
    assertThat(evaluation.sourceWeeks()).isEmpty();
    // A period with nothing at all has no approved time.
    Evaluation nothing = PayrollInputs.evaluate(SCOPE, List.of(week(empty, false)), Map.of());
    assertThat(nothing.blockers()).containsExactlyEntriesOf(Map.of(Blocker.NO_APPROVED_TIME, 1));
    // A placed row and a misplaced row of the same locked week: the period is blocked, not partial.
    Evaluation mixed =
        PayrollInputs.evaluate(
            SCOPE,
            List.of(week(locked, true)),
            Map.of(
                locked.id(),
                List.of(
                    row(zoe, UUID.randomUUID(), MONDAY, 30, OTHER_ENTITY),
                    row(UUID.randomUUID(), UUID.randomUUID(), MONDAY, 30))));
    assertThat(mixed.ready()).isFalse();
    assertThat(mixed.blockers()).containsExactlyEntriesOf(Map.of(Blocker.ATTRIBUTION_AMBIGUOUS, 1));
    assertThat(mixed.rows()).hasSize(1);
  }

  @Test
  void theBandsNeverExposeExactValues() {
    assertThat(PayrollInputs.minuteBand(0)).isEqualTo("0");
    assertThat(PayrollInputs.minuteBand(45)).isEqualTo("1-2400");
    assertThat(PayrollInputs.minuteBand(2400)).isEqualTo("1-2400");
    assertThat(PayrollInputs.minuteBand(2401)).isEqualTo("2401-24000");
    assertThat(PayrollInputs.minuteBand(240001)).isEqualTo("240001+");
    assertThat(PayrollInputs.countBand(0)).isEqualTo("0");
    assertThat(PayrollInputs.countBand(3)).isEqualTo("1-10");
    assertThat(PayrollInputs.countBand(11)).isEqualTo("11-100");
    assertThat(PayrollInputs.countBand(5000)).isEqualTo("1001+");
  }
}
