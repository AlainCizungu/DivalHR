package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.StatutoryFixtures.document;
import static com.divalhr.core.payroll.StatutoryFixtures.rate;
import static com.divalhr.core.payroll.StatutoryFixtures.seed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.StatutoryCalculator;
import com.divalhr.core.payroll.domain.StatutoryCalculator.LineResult;
import com.divalhr.core.payroll.domain.StatutoryCalculator.Pack;
import com.divalhr.core.payroll.domain.StatutoryCalculator.PackStatus;
import com.divalhr.core.payroll.domain.StatutoryCalculator.Result;
import com.divalhr.core.payroll.domain.StatutoryCalculator.Run;
import com.divalhr.core.payroll.domain.StatutoryCalculator.SourceLine;
import com.divalhr.core.payroll.domain.StatutoryClassification;
import com.divalhr.core.payroll.domain.StatutoryEvidence;
import com.divalhr.core.payroll.domain.StatutoryLineBlocker;
import com.divalhr.core.payroll.domain.StatutoryProfile;
import com.divalhr.core.payroll.domain.StatutoryRules;
import com.divalhr.core.payroll.domain.StatutoryRunBlocker;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-094B (ADR 0034 section 6): the whole-run statutory preview in memory: run blockers, line
 * blockers, counts, totals, and that no amount exists without a VERIFIED applicable pack.
 */
class StatutoryCalculatorTest {

  private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 1);
  private static final LocalDate FIRST = LocalDate.of(2026, 9, 1);
  private static final LocalDate LAST = LocalDate.of(2026, 9, 30);

  private final UUID full = UUID.randomUUID();
  private final UUID none = UUID.randomUUID();
  private final UUID cnssOnly = UUID.randomUUID();
  private final Map<UUID, List<StatutoryProfile>> profiles = new HashMap<>();

  private static LocalDate d(String text) {
    return LocalDate.parse(text);
  }

  private static StatutoryProfile profile(int sequence, String from, boolean cnss, boolean irpp) {
    return new StatutoryProfile(
        UUID.randomUUID(),
        sequence,
        d(from),
        StatutoryClassification.STANDARD_RESIDENT,
        cnss,
        irpp,
        StatutoryEvidence.EMPLOYER_DECLARATION);
  }

  private static Pack verified(StatutoryRules rules) {
    return new Pack(PackStatus.VERIFIED, d("2026-01-01"), d("2026-12-31"), rules);
  }

  private static Run run() {
    return new Run(true, "CDF", SEPTEMBER);
  }

  private static SourceLine line(UUID employee, String number, long gross) {
    return new SourceLine(UUID.randomUUID(), employee, number, gross, FIRST, LAST);
  }

  private List<SourceLine> threeLines() {
    profiles.put(full, List.of(profile(1, "2026-01-01", true, true)));
    profiles.put(none, List.of(profile(1, "2026-01-01", false, false)));
    profiles.put(cnssOnly, List.of(profile(1, "2026-01-01", true, false)));
    return List.of(
        line(full, "E-1", 162_000), line(none, "E-2", 500_000), line(cnssOnly, "E-3", 100_000));
  }

  @Test
  void aVerifiedPackCalculatesEveryLineAndTheAggregates() {
    Result result =
        StatutoryCalculator.calculate(verified(seed()), run(), threeLines(), profiles, Set.of());
    assertThat(result.runBlockers()).isEmpty();
    assertThat(result.committable()).isTrue();
    // E-1: 162000 -> employee 8100 + 4860 = 12960, employer 8100 + 2430 + 10530 = 21060.
    // E-2: not subject to anything: excluded, net = gross = 500000, no components.
    // E-3: 100000 CNSS only -> employee 5000, employer 5000 + 1500 + 6500 = 13000.
    assertThat(result.supportedCount()).isEqualTo(2);
    assertThat(result.excludedCount()).isEqualTo(1);
    assertThat(result.blockedCount()).isEqualTo(0);
    assertThat(result.grossTotal()).isEqualTo(762_000L);
    assertThat(result.employeeDeductionsTotal()).isEqualTo(17_960L);
    assertThat(result.employerContributionsTotal()).isEqualTo(34_060L);
    // Net 762000 - 17960 = 744040. Cost 762000 + 34060 = 796060.
    assertThat(result.previewNetTotal()).isEqualTo(744_040L);
    assertThat(result.employerCostTotal()).isEqualTo(796_060L);
    assertThat(result.lines().stream().map(LineResult::employeeNumber).toList())
        .containsExactly("E-1", "E-2", "E-3");
    assertThat(result.lines().get(0).line().employeeDeductionsMinor()).isEqualTo(12_960L);
    assertThat(result.lines().get(1).line().excluded()).isTrue();
    assertThat(result.lines().get(1).line().components()).isEmpty();
    assertThat(result.lines().get(2).line().employerContributionsMinor()).isEqualTo(13_000L);
    assertThat(result.lines().get(0).grossMinor()).isEqualTo(162_000L);
    assertThat(result.lines().get(0).blockers()).isEmpty();
  }

  @Test
  void aDraftOrRetiredPackNeverProducesAnAmount() {
    for (PackStatus status : new PackStatus[] {PackStatus.DRAFT, PackStatus.RETIRED}) {
      Pack pack = new Pack(status, d("2026-01-01"), d("2026-12-31"), seed());
      Result result = StatutoryCalculator.calculate(pack, run(), threeLines(), profiles, Set.of());
      assertThat(result.runBlockers()).containsExactly(StatutoryRunBlocker.RULE_PACK_NOT_VERIFIED);
      assertThat(result.committable()).isFalse();
      assertThat(result.lines()).hasSize(3);
      for (LineResult line : result.lines()) {
        assertThat(line.line()).isNull();
        assertThat(line.blockers()).isEmpty();
      }
      assertThat(result.supportedCount()).isEqualTo(0);
      assertThat(result.excludedCount()).isEqualTo(0);
      assertThat(result.blockedCount()).isEqualTo(0);
      assertThat(result.grossTotal()).isEqualTo(0L);
      assertThat(result.employeeDeductionsTotal()).isEqualTo(0L);
      assertThat(result.employerContributionsTotal()).isEqualTo(0L);
      assertThat(result.previewNetTotal()).isEqualTo(0L);
      assertThat(result.employerCostTotal()).isEqualTo(0L);
    }
  }

  @Test
  void aPackThatDoesNotParseOrDoesNotCoverTheWholeMonthIsNotVerified() {
    Result unparsed =
        StatutoryCalculator.calculate(
            new Pack(PackStatus.VERIFIED, d("2026-01-01"), d("2026-12-31"), null),
            run(),
            threeLines(),
            profiles,
            Set.of());
    assertThat(unparsed.runBlockers()).containsExactly(StatutoryRunBlocker.RULE_PACK_NOT_VERIFIED);
    // The pack must cover the first and the last day of September.
    assertThat(blockersFor(d("2026-01-01"), d("2026-09-29")))
        .containsExactly(StatutoryRunBlocker.RULE_PACK_NOT_VERIFIED);
    assertThat(blockersFor(d("2026-09-02"), null))
        .containsExactly(StatutoryRunBlocker.RULE_PACK_NOT_VERIFIED);
    assertThat(blockersFor(d("2026-10-01"), null))
        .containsExactly(StatutoryRunBlocker.RULE_PACK_NOT_VERIFIED);
    assertThat(blockersFor(d("2025-01-01"), d("2026-08-31")))
        .containsExactly(StatutoryRunBlocker.RULE_PACK_NOT_VERIFIED);
    assertThat(blockersFor(d("2026-09-01"), d("2026-09-30"))).isEmpty();
    assertThat(blockersFor(d("2026-09-01"), null)).isEmpty();
    assertThat(blockersFor(d("2000-01-01"), d("2999-12-31"))).isEmpty();
  }

  private List<StatutoryRunBlocker> blockersFor(LocalDate from, LocalDate to) {
    Pack pack = new Pack(PackStatus.VERIFIED, from, to, seed());
    return StatutoryCalculator.calculate(pack, run(), threeLines(), profiles, Set.of())
        .runBlockers();
  }

  @Test
  void aMissingPackANonCurrentRunAndAnUnsupportedCurrencyAreReportedTogether() {
    Result missing = StatutoryCalculator.calculate(null, run(), threeLines(), profiles, Set.of());
    assertThat(missing.runBlockers()).containsExactly(StatutoryRunBlocker.RULE_PACK_MISSING);
    assertThat(missing.lines().get(0).line()).isNull();

    Result usd =
        StatutoryCalculator.calculate(
            verified(seed()), new Run(true, "USD", SEPTEMBER), threeLines(), profiles, Set.of());
    assertThat(usd.runBlockers()).containsExactly(StatutoryRunBlocker.CURRENCY_NOT_SUPPORTED);
    assertThat(usd.committable()).isFalse();
    assertThat(usd.lines().get(0).line()).isNull();
    assertThat(usd.grossTotal()).isEqualTo(0L);

    Result superseded =
        StatutoryCalculator.calculate(
            verified(seed()), new Run(false, "CDF", SEPTEMBER), threeLines(), profiles, Set.of());
    assertThat(superseded.runBlockers())
        .containsExactly(StatutoryRunBlocker.SOURCE_RUN_NOT_CURRENT);
    assertThat(superseded.lines().get(1).line()).isNull();

    Result everything =
        StatutoryCalculator.calculate(
            null, new Run(false, "USD", SEPTEMBER), threeLines(), profiles, Set.of());
    assertThat(everything.runBlockers())
        .containsExactly(
            StatutoryRunBlocker.SOURCE_RUN_NOT_CURRENT,
            StatutoryRunBlocker.CURRENCY_NOT_SUPPORTED,
            StatutoryRunBlocker.RULE_PACK_MISSING);
  }

  @Test
  void employeeBlockersAreReportedAndKeepTheOtherLinesCalculatedButNotCommittable() {
    UUID missing = UUID.randomUUID();
    UUID unsupported = UUID.randomUUID();
    UUID unmodeled = UUID.randomUUID();
    UUID changing = UUID.randomUUID();
    profiles.put(
        unsupported,
        List.of(
            new StatutoryProfile(
                UUID.randomUUID(),
                1,
                d("2026-01-01"),
                StatutoryClassification.UNSUPPORTED,
                false,
                false,
                StatutoryEvidence.PROFESSIONAL_REVIEW)));
    profiles.put(unmodeled, List.of(profile(1, "2026-01-01", true, true)));
    profiles.put(
        changing,
        List.of(profile(1, "2026-01-01", true, true), profile(2, "2026-09-16", true, false)));
    List<SourceLine> lines = new ArrayList<>(threeLines());
    lines.add(line(missing, "E-4", 10_000));
    lines.add(line(unsupported, "E-5", 20_000));
    lines.add(line(unmodeled, "E-6", 30_000));
    lines.add(line(changing, "E-7", 40_000));
    Result result =
        StatutoryCalculator.calculate(verified(seed()), run(), lines, profiles, Set.of(unmodeled));
    assertThat(result.runBlockers()).isEmpty();
    assertThat(result.committable()).isFalse();
    assertThat(result.blockedCount()).isEqualTo(4);
    assertThat(result.supportedCount()).isEqualTo(2);
    assertThat(result.excludedCount()).isEqualTo(1);
    assertThat(result.lines().get(3).blockers())
        .containsExactly(StatutoryLineBlocker.PROFILE_MISSING);
    assertThat(result.lines().get(4).blockers())
        .containsExactly(StatutoryLineBlocker.PROFILE_UNSUPPORTED);
    assertThat(result.lines().get(5).blockers())
        .containsExactly(StatutoryLineBlocker.UNMODELED_EARNINGS);
    assertThat(result.lines().get(6).blockers())
        .containsExactly(StatutoryLineBlocker.PROFILE_CHANGES_IN_MONTH);
    for (int i = 3; i < 7; i++) {
      assertThat(result.lines().get(i).line()).isNull();
    }
    // The totals cover the three calculated lines only.
    assertThat(result.grossTotal()).isEqualTo(762_000L);
    assertThat(result.employeeDeductionsTotal()).isEqualTo(17_960L);
  }

  @Test
  void profileBlockersAreStillReportedWhenTheRunIsBlocked() {
    UUID missing = UUID.randomUUID();
    UUID unmodeled = UUID.randomUUID();
    profiles.put(unmodeled, List.of(profile(1, "2026-01-01", true, true)));
    List<SourceLine> lines = List.of(line(missing, "E-4", 10_000), line(unmodeled, "E-6", 30_000));
    Pack draft = new Pack(PackStatus.DRAFT, d("2026-01-01"), d("2026-12-31"), seed());
    Result result = StatutoryCalculator.calculate(draft, run(), lines, profiles, Set.of(unmodeled));
    assertThat(result.runBlockers()).containsExactly(StatutoryRunBlocker.RULE_PACK_NOT_VERIFIED);
    assertThat(result.lines().get(0).blockers())
        .containsExactly(StatutoryLineBlocker.PROFILE_MISSING);
    assertThat(result.lines().get(1).blockers())
        .containsExactly(StatutoryLineBlocker.UNMODELED_EARNINGS);
    assertThat(result.blockedCount()).isEqualTo(2);
    assertThat(result.lines().get(0).line()).isNull();
    assertThat(result.committable()).isFalse();
    assertThat(result.grossTotal()).isEqualTo(0L);
  }

  @Test
  void aMidMonthProfileChangeBlocksButAChangeOnTheFirstEligibleDayDoesNot() {
    UUID employee = UUID.randomUUID();
    profiles.put(
        employee,
        List.of(profile(1, "2026-01-01", true, true), profile(2, "2026-09-10", true, false)));
    // Eligible from the 10th: the profile of the 10th is in force from the first eligible day.
    SourceLine fromTenth =
        new SourceLine(UUID.randomUUID(), employee, "E-8", 100_000, d("2026-09-10"), LAST);
    Result fine =
        StatutoryCalculator.calculate(
            verified(seed()), run(), List.of(fromTenth), profiles, Set.of());
    assertThat(fine.blockedCount()).isEqualTo(0);
    assertThat(fine.lines().get(0).line().employeeDeductionsMinor()).isEqualTo(5000L);
    // Eligible all month: the row of the 10th changes the month.
    Result blocked =
        StatutoryCalculator.calculate(
            verified(seed()), run(), List.of(line(employee, "E-8", 100_000)), profiles, Set.of());
    assertThat(blocked.lines().get(0).blockers())
        .containsExactly(StatutoryLineBlocker.PROFILE_CHANGES_IN_MONTH);
  }

  @Test
  void deductionsAboveTheGrossBlockTheLineAndLeaveItWithoutAmounts() {
    String json =
        document(
            rate("EE_SIXTY", "EMPLOYEE", 1, "CNSS_SUBJECT", "GROSS", 6000, "HALF_UP")
                + ","
                + rate("EE_FIFTY", "EMPLOYEE", 2, "CNSS_SUBJECT", "GROSS", 5000, "HALF_UP"));
    Result result =
        StatutoryCalculator.calculate(
            verified(StatutoryRules.parse(json)),
            run(),
            List.of(line(cnssOnly, "E-3", 1000)),
            Map.of(cnssOnly, List.of(profile(1, "2026-01-01", true, false))),
            Set.of());
    assertThat(result.lines().get(0).blockers())
        .containsExactly(StatutoryLineBlocker.DEDUCTIONS_EXCEED_GROSS);
    assertThat(result.lines().get(0).line()).isNull();
    assertThat(result.blockedCount()).isEqualTo(1);
    assertThat(result.committable()).isFalse();
    assertThat(result.grossTotal()).isEqualTo(0L);
  }

  @Test
  void anEmptyRunIsNeverCommittableAndTheEmployerNeverPaysThroughTheEmployee() {
    Result empty =
        StatutoryCalculator.calculate(verified(seed()), run(), List.of(), Map.of(), Set.of());
    assertThat(empty.committable()).isFalse();
    assertThat(empty.lines()).isEmpty();
    // For every calculated line the cost exceeds the gross by the employer contributions only.
    Result result =
        StatutoryCalculator.calculate(verified(seed()), run(), threeLines(), profiles, Set.of());
    assertThat(result.employerCostTotal() - result.grossTotal())
        .isEqualTo(result.employerContributionsTotal());
    assertThat(result.grossTotal() - result.previewNetTotal())
        .isEqualTo(result.employeeDeductionsTotal());
  }

  @Test
  void aSourceLineNeedsOrderedDaysAndANonNegativeGross() {
    UUID employee = UUID.randomUUID();
    assertThatThrownBy(
            () -> new SourceLine(UUID.randomUUID(), employee, "E-9", -1, FIRST, LAST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SourceLine(UUID.randomUUID(), employee, "E-9", 1, LAST, FIRST))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
