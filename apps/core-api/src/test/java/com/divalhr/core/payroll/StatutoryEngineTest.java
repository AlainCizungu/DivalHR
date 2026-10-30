package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.StatutoryFixtures.SEED_BRACKETS;
import static com.divalhr.core.payroll.StatutoryFixtures.document;
import static com.divalhr.core.payroll.StatutoryFixtures.progressive;
import static com.divalhr.core.payroll.StatutoryFixtures.rate;
import static com.divalhr.core.payroll.StatutoryFixtures.seed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.StatutoryEngine;
import com.divalhr.core.payroll.domain.StatutoryExplanation;
import com.divalhr.core.payroll.domain.StatutoryLine;
import com.divalhr.core.payroll.domain.StatutoryLineBlocker;
import com.divalhr.core.payroll.domain.StatutoryRules;
import com.divalhr.core.payroll.domain.StatutoryRules.Category;
import com.divalhr.core.payroll.domain.StatutoryRules.Party;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MVP-094B (D94B-5, ADR 0034 section 3 and 6): the exact statutory calculation of one line.
 *
 * <p>Every expected number below was computed by hand and re-checked with an independent {@code
 * fractions.Fraction} implementation in Python: the comments show the arithmetic. The seed rules
 * are the DRAFT reference pack; the other packs are synthetic. Money is whole CDF francs.
 */
class StatutoryEngineTest {

  private static final String LESS = "GROSS_LESS_EMPLOYEE_SOCIAL";
  private static final String TWO_BRACKETS =
      "[{\"upToAnnual\": 500, \"rateBp\": 1000}, {\"upToAnnual\": null, \"rateBp\": 2000}]";

  private static StatutoryLine seeded(boolean cnss, boolean irpp, long gross) {
    return StatutoryEngine.calculate(seed(), cnss, irpp, gross);
  }

  private static StatutoryLine.Component component(StatutoryLine line, String id) {
    return line.components().stream()
        .filter(c -> c.componentId().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static long amount(StatutoryLine line, String id) {
    return component(line, id).amountMinor();
  }

  private static StatutoryLine calculate(String json, boolean cnss, boolean irpp, long gross) {
    return StatutoryEngine.calculate(StatutoryRules.parse(json), cnss, irpp, gross);
  }

  private static BigInteger big(String digits) {
    return new BigInteger(digits);
  }

  // ------------------------------------------------------------------------------------------
  // CNSS: rate components
  // ------------------------------------------------------------------------------------------

  @Test
  void cnssAmountsAreTheRatesOfTheRulesRoundedHalfUpOnceAtSeveralGrosses() {
    // gross, employee 5%, employer pension 5%, occupational risk 1.5%, family benefits 6.5%
    long[][] vectors = {
      // 12345: 617.25 -> 617 | 617.25 -> 617 | 185.175 -> 185 | 802.425 -> 802
      {12345, 617, 617, 185, 802},
      // 162000: 8100 | 8100 | 2430 | 10530 (exact)
      {162000, 8100, 8100, 2430, 10530},
      // 10: 0.5 -> 1 (tie rounds up) | 0.5 -> 1 | 0.15 -> 0 | 0.65 -> 1
      {10, 1, 1, 0, 1},
      // 30: 1.5 -> 2 | 1.5 -> 2 | 0.45 -> 0 | 1.95 -> 2
      {30, 2, 2, 0, 2},
      // 100: 5 | 5 | 1.5 -> 2 | 6.5 -> 7
      {100, 5, 5, 2, 7},
      // 1: 0.05 -> 0 | 0.05 -> 0 | 0.015 -> 0 | 0.065 -> 0
      {1, 0, 0, 0, 0},
      {0, 0, 0, 0, 0}
    };
    for (long[] v : vectors) {
      StatutoryLine line = seeded(true, false, v[0]);
      assertThat(amount(line, "CNSS_EMPLOYEE_PENSION")).as("employee " + v[0]).isEqualTo(v[1]);
      assertThat(amount(line, "CNSS_EMPLOYER_PENSION")).as("pension " + v[0]).isEqualTo(v[2]);
      assertThat(amount(line, "CNSS_EMPLOYER_OCCUPATIONAL_RISK"))
          .as("risk " + v[0])
          .isEqualTo(v[3]);
      assertThat(amount(line, "CNSS_EMPLOYER_FAMILY_BENEFITS"))
          .as("family " + v[0])
          .isEqualTo(v[4]);
      for (String id :
          List.of(
              "CNSS_EMPLOYEE_PENSION",
              "CNSS_EMPLOYER_PENSION",
              "CNSS_EMPLOYER_OCCUPATIONAL_RISK",
              "CNSS_EMPLOYER_FAMILY_BENEFITS")) {
        assertThat(component(line, id).baseMinor()).isEqualTo(v[0]);
        assertThat(component(line, id).trace()).isNull();
        assertThat(component(line, id).explanations())
            .containsExactly(StatutoryExplanation.RATE_APPLIED, StatutoryExplanation.BASE_GROSS);
      }
    }
  }

  @Test
  void aLineAtGross12345SeparatesEmployeeDeductionsFromEmployerContributions() {
    StatutoryLine line = seeded(true, false, 12345);
    // Employee deductions: 617. Employer contributions: 617 + 185 + 802 = 1604.
    assertThat(line.employeeDeductionsMinor()).isEqualTo(617L);
    assertThat(line.employerContributionsMinor()).isEqualTo(1604L);
    // Preview net = 12345 - 617 = 11728. Employer cost = 12345 + 1604 = 13949.
    assertThat(line.previewNetMinor()).isEqualTo(11728L);
    assertThat(line.employerCostMinor()).isEqualTo(13949L);
    assertThat(line.grossMinor()).isEqualTo(12345L);
    assertThat(line.excluded()).isFalse();
    assertThat(line.blocked()).isFalse();
    assertThat(line.components()).hasSize(5);
    StatutoryLine.Component tax = component(line, "IRPP_SALARY_WITHHOLDING");
    assertThat(tax.amountMinor()).isEqualTo(0L);
    assertThat(tax.baseMinor()).isEqualTo(12345L);
    assertThat(tax.explanations())
        .containsExactly(StatutoryExplanation.NOT_SUBJECT, StatutoryExplanation.BASE_GROSS);
    assertThat(tax.trace()).isNull();
    assertThat(tax.party()).isEqualTo(Party.EMPLOYEE);
    assertThat(tax.category()).isEqualTo(Category.INCOME_TAX);
  }

  @Test
  void aFullSeedLineAt162000HasTheDocumentedAmounts() {
    StatutoryLine line = seeded(true, true, 162000);
    // Employee: CNSS 8100 + IRPP 4860 = 12960. Employer: 8100 + 2430 + 10530 = 21060.
    assertThat(line.employeeDeductionsMinor()).isEqualTo(12960L);
    assertThat(line.employerContributionsMinor()).isEqualTo(21060L);
    // Net = 162000 - 12960 = 149040. Cost = 162000 + 21060 = 183060.
    assertThat(line.previewNetMinor()).isEqualTo(149040L);
    assertThat(line.employerCostMinor()).isEqualTo(183060L);
    assertThat(line.components().stream().map(StatutoryLine.Component::componentId).toList())
        .containsExactly(
            "CNSS_EMPLOYEE_PENSION",
            "CNSS_EMPLOYER_PENSION",
            "CNSS_EMPLOYER_OCCUPATIONAL_RISK",
            "CNSS_EMPLOYER_FAMILY_BENEFITS",
            "IRPP_SALARY_WITHHOLDING");
  }

  // ------------------------------------------------------------------------------------------
  // IRPP: progressive schedule
  // ------------------------------------------------------------------------------------------

  @Test
  void irppIsExactAtEveryBracketBoundaryOfTheSeedAndOneFrancEitherSide() {
    // gross -> monthly tax. A = 12 * gross; tax = N / 120000 with N = sum(slice * rateBp).
    long[][] vectors = {
      // 161999: A = 1,943,988; N = 1,943,988 * 300 = 583,196,400; /120000 = 4859.97 -> 4860
      {161999, 4860},
      // 162000: A = 1,944,000 exactly; N = 583,200,000; /120000 = 4860 exactly
      {162000, 4860},
      // 162001: A = 1,944,012; N = 583,200,000 + 12*1500 = 583,218,000; /120000 = 4860.15 -> 4860
      {162001, 4860},
      // 1,800,000: A = 21,600,000; N = 583,200,000 + 19,656,000*1500 = 30,067,200,000; -> 250,560
      {1_800_000, 250_560},
      // 1,800,001: N = 30,067,200,000 + 12*3000 = 30,067,236,000; /120000 = 250560.3 -> 250,560
      {1_800_001, 250_560},
      // 3,600,000: A = 43,200,000; N = 30,067,200,000 + 21,600,000*3000 = 94,867,200,000 -> 790,560
      {3_600_000, 790_560},
      // 3,600,001: N = 94,867,200,000 + 12*4000 = 94,867,248,000; /120000 = 790560.4 -> 790,560
      {3_600_001, 790_560},
      // 12345: A = 148,140; N = 148,140 * 300 = 44,442,000; /120000 = 370.35 -> 370
      {12345, 370},
      // 1: A = 12; N = 3600; /120000 = 0.03 -> 0
      {1, 0},
      {0, 0}
    };
    for (long[] v : vectors) {
      StatutoryLine line = seeded(false, true, v[0]);
      StatutoryLine.Component tax = component(line, "IRPP_SALARY_WITHHOLDING");
      assertThat(tax.amountMinor()).as("irpp at " + v[0]).isEqualTo(v[1]);
      assertThat(tax.baseMinor()).isEqualTo(v[0]);
      assertThat(tax.trace()).isNotNull();
      assertThat(tax.trace().capApplied()).isFalse();
      assertThat(line.employeeDeductionsMinor()).isEqualTo(v[1]);
    }
  }

  @Test
  void theTraceShowsTheAnnualBaseEverySliceAndTheNumerator() {
    StatutoryLine.Trace atBoundary =
        component(seeded(false, true, 162000), "IRPP_SALARY_WITHHOLDING").trace();
    assertThat(atBoundary.annualBase()).isEqualTo(BigInteger.valueOf(1_944_000L));
    assertThat(atBoundary.periodsPerYear()).isEqualTo(12);
    assertThat(atBoundary.numerator()).isEqualTo(BigInteger.valueOf(583_200_000L));
    assertThat(atBoundary.slices())
        .containsExactly(
            new StatutoryLine.Slice(0, 1_944_000L, 300, BigInteger.valueOf(1_944_000L)));

    StatutoryLine.Trace pastBoundary =
        component(seeded(false, true, 162001), "IRPP_SALARY_WITHHOLDING").trace();
    assertThat(pastBoundary.annualBase()).isEqualTo(BigInteger.valueOf(1_944_012L));
    assertThat(pastBoundary.numerator()).isEqualTo(BigInteger.valueOf(583_218_000L));
    assertThat(pastBoundary.slices())
        .containsExactly(
            new StatutoryLine.Slice(0, 1_944_000L, 300, BigInteger.valueOf(1_944_000L)),
            new StatutoryLine.Slice(1_944_000L, 21_600_000L, 1500, BigInteger.valueOf(12L)));

    // 3,600,001: A = 43,200,012 = 1,944,000 + 19,656,000 + 21,600,000 + 12.
    StatutoryLine.Trace top =
        component(seeded(false, true, 3_600_001), "IRPP_SALARY_WITHHOLDING").trace();
    assertThat(top.annualBase()).isEqualTo(BigInteger.valueOf(43_200_012L));
    assertThat(top.numerator()).isEqualTo(BigInteger.valueOf(94_867_248_000L));
    assertThat(top.slices())
        .containsExactly(
            new StatutoryLine.Slice(0, 1_944_000L, 300, BigInteger.valueOf(1_944_000L)),
            new StatutoryLine.Slice(1_944_000L, 21_600_000L, 1500, BigInteger.valueOf(19_656_000L)),
            new StatutoryLine.Slice(
                21_600_000L, 43_200_000L, 3000, BigInteger.valueOf(21_600_000L)),
            new StatutoryLine.Slice(43_200_000L, null, 4000, BigInteger.valueOf(12L)));

    // A zero base has no slice at all.
    StatutoryLine.Trace zero = component(seeded(false, true, 0), "IRPP_SALARY_WITHHOLDING").trace();
    assertThat(zero.annualBase()).isEqualTo(BigInteger.ZERO);
    assertThat(zero.slices()).isEmpty();
    assertThat(zero.numerator()).isEqualTo(BigInteger.ZERO);
  }

  @Test
  void theCapBindsOnlyWhenTheExactTaxExceedsThirtyPercentOfTheBase() {
    // In the top bracket N = 94,867,200,000 + (12g - 43,200,000) * 4000 = 48000g - 77,932,800,000
    // and the cap comparison is g * 3000 * 12 = 36000g, so they cross at 12000g = 77,932,800,000,
    // g = 6,494,400 exactly, where N equals the cap comparison: not greater, not applied.
    StatutoryLine.Component below =
        component(seeded(false, true, 6_494_399), "IRPP_SALARY_WITHHOLDING");
    // 6,494,399: N = 233,798,352,000 < 233,798,364,000 (= 36000 * 6,494,399 = 233,798,364,000)
    // so the exact tax 233,798,352,000 / 120000 = 1,948,319.6 -> 1,948,320.
    assertThat(below.amountMinor()).isEqualTo(1_948_320L);
    assertThat(below.trace().capApplied()).isFalse();
    assertThat(below.explanations())
        .containsExactly(StatutoryExplanation.PROGRESSIVE_APPLIED, StatutoryExplanation.BASE_GROSS);

    StatutoryLine.Component equal =
        component(seeded(false, true, 6_494_400), "IRPP_SALARY_WITHHOLDING");
    // N = 233,798,400,000 = 36000 * 6,494,400: equal, so the cap does not apply; 1,948,320 exactly.
    assertThat(equal.amountMinor()).isEqualTo(1_948_320L);
    assertThat(equal.trace().capApplied()).isFalse();

    StatutoryLine.Component above =
        component(seeded(false, true, 6_494_401), "IRPP_SALARY_WITHHOLDING");
    // N = 233,798,448,000 > 233,798,436,000: the cap applies; 6,494,401 * 3000 / 10000 =
    // 1,948,320.3 -> 1,948,320.
    assertThat(above.amountMinor()).isEqualTo(1_948_320L);
    assertThat(above.trace().capApplied()).isTrue();
    assertThat(above.explanations())
        .containsExactly(
            StatutoryExplanation.PROGRESSIVE_APPLIED,
            StatutoryExplanation.CAP_APPLIED,
            StatutoryExplanation.BASE_GROSS);

    // 100,000,000: N = 4,722,067,200,000 -> exact tax 39,350,560 but the cap is 30,000,000.
    StatutoryLine.Component far =
        component(seeded(false, true, 100_000_000), "IRPP_SALARY_WITHHOLDING");
    assertThat(far.amountMinor()).isEqualTo(30_000_000L);
    assertThat(far.trace().capApplied()).isTrue();
    assertThat(far.trace().numerator()).isEqualTo(BigInteger.valueOf(4_722_067_200_000L));

    // 10,000,000: exact tax 402,067,200,000 / 120000 = 3,350,560 > cap 3,000,000: capped.
    assertThat(component(seeded(false, true, 10_000_000), "IRPP_SALARY_WITHHOLDING").amountMinor())
        .isEqualTo(3_000_000L);
    // 3,600,000: exact tax 790,560 < cap 1,080,000: not capped.
    assertThat(
            component(seeded(false, true, 3_600_000), "IRPP_SALARY_WITHHOLDING")
                .trace()
                .capApplied())
        .isFalse();
  }

  @Test
  void aPackWithoutACapNeverCaps() {
    String json =
        document(progressive("PLAIN_TAX", 1, "GROSS", 12, null, SEED_BRACKETS, "HALF_UP"));
    StatutoryLine line = calculate(json, false, true, 100_000_000);
    // The uncapped exact tax: 4,722,067,200,000 / 120000 = 39,350,560.
    assertThat(line.components().get(0).amountMinor()).isEqualTo(39_350_560L);
    assertThat(line.components().get(0).trace().capApplied()).isFalse();
    // A cap of 0 caps the tax to nothing; a cap of 10000 (100%) never binds here.
    assertThat(
            calculate(
                    document(progressive("ZERO_CAP", 1, "GROSS", 12, 0, SEED_BRACKETS, "HALF_UP")),
                    false,
                    true,
                    162000)
                .components()
                .get(0)
                .amountMinor())
        .isEqualTo(0L);
    assertThat(
            calculate(
                    document(
                        progressive("FULL_CAP", 1, "GROSS", 12, 10000, SEED_BRACKETS, "HALF_UP")),
                    false,
                    true,
                    162000)
                .components()
                .get(0)
                .amountMinor())
        .isEqualTo(4860L);
  }

  // ------------------------------------------------------------------------------------------
  // Rounding
  // ------------------------------------------------------------------------------------------

  @Test
  void aRateRoundsOnceHalfUpDownOrUpAsThePackSays() {
    // 12345 * 500 / 10000 = 617.25 and 10 * 500 / 10000 = 0.5 (a tie).
    for (Object[] v :
        new Object[][] {
          {"HALF_UP", 12345L, 617L},
          {"DOWN", 12345L, 617L},
          {"UP", 12345L, 618L},
          {"HALF_UP", 10L, 1L},
          {"DOWN", 10L, 0L},
          {"UP", 10L, 1L},
          {"UP", 0L, 0L},
          {"UP", 20L, 1L}
        }) {
      String json =
          document(rate("FEE_A", "EMPLOYEE", 1, "CNSS_SUBJECT", "GROSS", 500, (String) v[0]));
      StatutoryLine line = calculate(json, true, false, (Long) v[1]);
      assertThat(line.components().get(0).amountMinor()).as(Arrays.toString(v)).isEqualTo(v[2]);
    }
  }

  @Test
  void aProgressiveTaxRoundsOnceAtTheEndInEveryMode() {
    // One open bracket at 300 bp, 12 periods, no cap: tax = 12 * g * 300 / 120000 = 0.03 * g.
    String open = "[{\"upToAnnual\": null, \"rateBp\": 300}]";
    Object[][] vectors = {
      // g = 50: 1.5 (tie) -> HALF_UP 2, DOWN 1, UP 2
      {"HALF_UP", 50L, 2L},
      {"DOWN", 50L, 1L},
      {"UP", 50L, 2L},
      // g = 150: 4.5 (tie) -> HALF_UP 5, DOWN 4, UP 5
      {"HALF_UP", 150L, 5L},
      {"DOWN", 150L, 4L},
      {"UP", 150L, 5L},
      // g = 10: 0.3 -> 0, 0, 1
      {"HALF_UP", 10L, 0L},
      {"DOWN", 10L, 0L},
      {"UP", 10L, 1L}
    };
    for (Object[] v : vectors) {
      String json = document(progressive("ROUNDED_TAX", 1, "GROSS", 12, null, open, (String) v[0]));
      assertThat(calculate(json, false, true, (Long) v[1]).components().get(0).amountMinor())
          .as(Arrays.toString(v))
          .isEqualTo(v[2]);
    }
    // The reference schedule at 161999 (exact 4859.97), 162001 (4860.15) and 12345 (370.35).
    Object[][] reference = {
      {"HALF_UP", 161999L, 4860L},
      {"DOWN", 161999L, 4859L},
      {"UP", 161999L, 4860L},
      {"HALF_UP", 162001L, 4860L},
      {"DOWN", 162001L, 4860L},
      {"UP", 162001L, 4861L},
      {"HALF_UP", 12345L, 370L},
      {"DOWN", 12345L, 370L},
      {"UP", 12345L, 371L}
    };
    for (Object[] v : reference) {
      String json =
          document(progressive("ROUNDED_TAX", 1, "GROSS", 12, 3000, SEED_BRACKETS, (String) v[0]));
      assertThat(calculate(json, false, true, (Long) v[1]).components().get(0).amountMinor())
          .as(Arrays.toString(v))
          .isEqualTo(v[2]);
    }
  }

  // ------------------------------------------------------------------------------------------
  // Bases, flags, blockers
  // ------------------------------------------------------------------------------------------

  @Test
  void theNetBaseSubtractsTheEmployeeSocialContributionsOfALowerOrder() {
    String json =
        document(
            rate("EE_PENSION", "EMPLOYEE", 1, "CNSS_SUBJECT", "GROSS", 500, "HALF_UP")
                + ","
                + progressive("TAX_NET", 2, LESS, 12, 3000, SEED_BRACKETS, "HALF_UP"));
    StatutoryLine both = calculate(json, true, true, 162000);
    // CNSS = 8100; the tax base = 162000 - 8100 = 153900; A = 1,846,800; N = 1,846,800 * 300
    // = 554,040,000; /120000 = 4617.
    StatutoryLine.Component tax = component(both, "TAX_NET");
    assertThat(tax.baseMinor()).isEqualTo(153_900L);
    assertThat(tax.amountMinor()).isEqualTo(4617L);
    assertThat(tax.explanations())
        .containsExactly(
            StatutoryExplanation.PROGRESSIVE_APPLIED,
            StatutoryExplanation.BASE_GROSS_LESS_EMPLOYEE_SOCIAL);
    // Deductions 8100 + 4617 = 12717; net 162000 - 12717 = 149283.
    assertThat(both.employeeDeductionsMinor()).isEqualTo(12_717L);
    assertThat(both.previewNetMinor()).isEqualTo(149_283L);
    // Not subject to CNSS: the contribution is 0, so the base is the whole gross: 4860.
    StatutoryLine taxOnly = calculate(json, false, true, 162000);
    assertThat(component(taxOnly, "TAX_NET").baseMinor()).isEqualTo(162_000L);
    assertThat(component(taxOnly, "TAX_NET").amountMinor()).isEqualTo(4860L);
  }

  @Test
  void theNetBaseIsNeverNegative() {
    // Two employee contributions of 100% each: the second is on gross less the first = 0.
    String json =
        document(
            rate("EE_FIRST", "EMPLOYEE", 1, "CNSS_SUBJECT", "GROSS", 10000, "HALF_UP")
                + ","
                + rate("EE_SECOND", "EMPLOYEE", 2, "CNSS_SUBJECT", LESS, 10000, "UP")
                + ","
                + rate("EE_THIRD", "EMPLOYEE", 3, "CNSS_SUBJECT", LESS, 10000, "UP"));
    StatutoryLine line = calculate(json, true, false, 1000);
    assertThat(component(line, "EE_FIRST").amountMinor()).isEqualTo(1000L);
    assertThat(component(line, "EE_SECOND").baseMinor()).isEqualTo(0L);
    assertThat(component(line, "EE_SECOND").amountMinor()).isEqualTo(0L);
    assertThat(component(line, "EE_THIRD").baseMinor()).isEqualTo(0L);
    // Deductions equal the gross exactly: not above it, so no blocker; net 0.
    assertThat(line.employeeDeductionsMinor()).isEqualTo(1000L);
    assertThat(line.previewNetMinor()).isEqualTo(0L);
    assertThat(line.blocked()).isFalse();
  }

  @Test
  void aComponentWhoseFlagIsFalseIsZeroNotSubjectAndTheLineIsExcludedWhenNothingApplies() {
    // CNSS and IRPP: 8100 + 4860 = 12960 | 8100 + 2430 + 10530 = 21060.
    StatutoryLine none = seeded(false, false, 162000);
    assertThat(none.excluded()).isTrue();
    assertThat(none.components()).isEmpty();
    assertThat(none.employeeDeductionsMinor()).isEqualTo(0L);
    assertThat(none.employerContributionsMinor()).isEqualTo(0L);
    assertThat(none.previewNetMinor()).isEqualTo(162_000L);
    assertThat(none.employerCostMinor()).isEqualTo(162_000L);
    assertThat(none.blocked()).isFalse();

    StatutoryLine cnssOnly = seeded(true, false, 162000);
    assertThat(cnssOnly.excluded()).isFalse();
    assertThat(cnssOnly.employeeDeductionsMinor()).isEqualTo(8100L);
    assertThat(cnssOnly.employerContributionsMinor()).isEqualTo(21_060L);
    assertThat(cnssOnly.previewNetMinor()).isEqualTo(153_900L);
    assertThat(cnssOnly.employerCostMinor()).isEqualTo(183_060L);
    assertThat(component(cnssOnly, "IRPP_SALARY_WITHHOLDING").amountMinor()).isEqualTo(0L);
    assertThat(component(cnssOnly, "IRPP_SALARY_WITHHOLDING").explanations())
        .contains(StatutoryExplanation.NOT_SUBJECT);

    StatutoryLine irppOnly = seeded(false, true, 162000);
    assertThat(irppOnly.excluded()).isFalse();
    assertThat(irppOnly.employeeDeductionsMinor()).isEqualTo(4860L);
    assertThat(irppOnly.employerContributionsMinor()).isEqualTo(0L);
    assertThat(irppOnly.previewNetMinor()).isEqualTo(157_140L);
    assertThat(irppOnly.employerCostMinor()).isEqualTo(162_000L);
    for (String id :
        List.of(
            "CNSS_EMPLOYEE_PENSION",
            "CNSS_EMPLOYER_PENSION",
            "CNSS_EMPLOYER_OCCUPATIONAL_RISK",
            "CNSS_EMPLOYER_FAMILY_BENEFITS")) {
      assertThat(component(irppOnly, id).amountMinor()).isEqualTo(0L);
      assertThat(component(irppOnly, id).baseMinor()).isEqualTo(162_000L);
      assertThat(component(irppOnly, id).explanations())
          .containsExactly(StatutoryExplanation.NOT_SUBJECT, StatutoryExplanation.BASE_GROSS);
    }
  }

  @Test
  void deductionsAboveTheGrossBlockTheLine() {
    // 60% of 1000 = 600 and 50% of 1000 = 500: deductions 1100 > 1000, net -100.
    String json =
        document(
            rate("EE_SIXTY", "EMPLOYEE", 1, "CNSS_SUBJECT", "GROSS", 6000, "HALF_UP")
                + ","
                + rate("EE_FIFTY", "EMPLOYEE", 2, "CNSS_SUBJECT", "GROSS", 5000, "HALF_UP"));
    StatutoryLine line = calculate(json, true, false, 1000);
    assertThat(line.blocked()).isTrue();
    assertThat(line.blockers()).containsExactly(StatutoryLineBlocker.DEDUCTIONS_EXCEED_GROSS);
    assertThat(line.employeeDeductionsMinor()).isEqualTo(1100L);
    assertThat(line.previewNetMinor()).isEqualTo(-100L);
    // 50% + 50% = 100%: equal to the gross is not above it.
    String equal =
        document(
            rate("EE_HALF_A", "EMPLOYEE", 1, "CNSS_SUBJECT", "GROSS", 5000, "HALF_UP")
                + ","
                + rate("EE_HALF_B", "EMPLOYEE", 2, "CNSS_SUBJECT", "GROSS", 5000, "HALF_UP"));
    assertThat(calculate(equal, true, false, 1000).blocked()).isFalse();
    // Rounding up can tip a line over: 2 * round_up(1 * 5000 / 10000) = 2 > 1.
    String tipped =
        document(
            rate("EE_UP_A", "EMPLOYEE", 1, "CNSS_SUBJECT", "GROSS", 5000, "UP")
                + ","
                + rate("EE_UP_B", "EMPLOYEE", 2, "CNSS_SUBJECT", "GROSS", 5000, "UP"));
    assertThat(calculate(tipped, true, false, 1).blockers())
        .containsExactly(StatutoryLineBlocker.DEDUCTIONS_EXCEED_GROSS);
  }

  @Test
  void employerContributionsNeverReduceTheNetAndTheCostIsGrossPlusThem() {
    long[] grosses = {0, 1, 99, 12345, 162000, 1_800_000, 6_494_401, 100_000_000, 1_000_000_000};
    for (long gross : grosses) {
      StatutoryLine line = seeded(true, true, gross);
      long employee = 0;
      long employer = 0;
      for (StatutoryLine.Component component : line.components()) {
        if (component.party() == Party.EMPLOYEE) {
          employee += component.amountMinor();
        } else {
          employer += component.amountMinor();
        }
      }
      assertThat(line.employeeDeductionsMinor()).as("employee " + gross).isEqualTo(employee);
      assertThat(line.employerContributionsMinor()).as("employer " + gross).isEqualTo(employer);
      assertThat(line.previewNetMinor()).as("net " + gross).isEqualTo(gross - employee);
      assertThat(line.employerCostMinor()).as("cost " + gross).isEqualTo(gross + employer);
    }
    // A pack with employer components only leaves the employee whole.
    String employerOnly =
        document(
            rate("ER_ONE", "EMPLOYER", 1, "CNSS_SUBJECT", "GROSS", 1000, "HALF_UP")
                + ","
                + rate("ER_TWO", "EMPLOYER", 2, "CNSS_SUBJECT", "GROSS", 2000, "HALF_UP"));
    StatutoryLine line = calculate(employerOnly, true, false, 5000);
    assertThat(line.employeeDeductionsMinor()).isEqualTo(0L);
    assertThat(line.previewNetMinor()).isEqualTo(5000L);
    // 10% + 20% of 5000 = 500 + 1000 = 1500; cost 6500.
    assertThat(line.employerContributionsMinor()).isEqualTo(1500L);
    assertThat(line.employerCostMinor()).isEqualTo(6500L);
  }

  // ------------------------------------------------------------------------------------------
  // Periodization, size, and the data-driven engine
  // ------------------------------------------------------------------------------------------

  @Test
  void annualAndMonthlyPeriodizationGiveTheSameAnnualTaxFromPackData() {
    // Twelve monthly periods of 1,800,000: 250,560 per month, 3,006,720 for the year.
    String monthly = document(progressive("TAX_M", 1, "GROSS", 12, null, SEED_BRACKETS, "HALF_UP"));
    // One period: the base is the year, 21,600,000: N = 30,067,200,000; /10000 = 3,006,720.
    String yearly = document(progressive("TAX_Y", 1, "GROSS", 1, null, SEED_BRACKETS, "HALF_UP"));
    long month = calculate(monthly, false, true, 1_800_000).components().get(0).amountMinor();
    long year = calculate(yearly, false, true, 21_600_000).components().get(0).amountMinor();
    assertThat(month).isEqualTo(250_560L);
    assertThat(year).isEqualTo(3_006_720L);
    assertThat(month * 12).isEqualTo(year);
    // 162000 a month and 1,944,000 a year: 4860 a month, 58,320 for the year.
    assertThat(calculate(monthly, false, true, 162000).components().get(0).amountMinor())
        .isEqualTo(4860L);
    assertThat(calculate(yearly, false, true, 1_944_000).components().get(0).amountMinor())
        .isEqualTo(58_320L);
    // The cap too: 100,000,000 a month is capped at 30% = 30,000,000; 1,200,000,000 a year at
    // 30% = 360,000,000.
    String monthlyCap =
        document(progressive("TAX_M", 1, "GROSS", 12, 3000, SEED_BRACKETS, "HALF_UP"));
    String yearlyCap =
        document(progressive("TAX_Y", 1, "GROSS", 1, 3000, SEED_BRACKETS, "HALF_UP"));
    assertThat(calculate(monthlyCap, false, true, 100_000_000).components().get(0).amountMinor())
        .isEqualTo(30_000_000L);
    assertThat(calculate(yearlyCap, false, true, 1_200_000_000).components().get(0).amountMinor())
        .isEqualTo(360_000_000L);
    StatutoryLine capped = calculate(yearlyCap, false, true, 1_200_000_000);
    assertThat(capped.components().get(0).trace().periodsPerYear()).isEqualTo(1);
  }

  @Test
  void theLargestGrossIsExactAndATotalThatOverflowsIsRefusedDeliberately() {
    long max = Long.MAX_VALUE;
    // Employee-only pack: CNSS 5% HALF_UP and the capped schedule.
    String json =
        document(
            rate("EE_PENSION", "EMPLOYEE", 1, "CNSS_SUBJECT", "GROSS", 500, "HALF_UP")
                + ","
                + progressive("TAX_BIG", 2, "GROSS", 12, 3000, SEED_BRACKETS, "HALF_UP"));
    StatutoryLine line = calculate(json, true, true, max);
    // 9,223,372,036,854,775,807 * 500 / 10000 = 461,168,601,842,738,790.35 -> ...790
    assertThat(component(line, "EE_PENSION").amountMinor()).isEqualTo(461_168_601_842_738_790L);
    StatutoryLine.Component tax = component(line, "TAX_BIG");
    // A = 12 * max = 110,680,464,442,257,309,684 ; N = 442,721,857,768,951,305,936,000 (exact)
    // and N > max * 3000 * 12, so the cap applies: max * 3000 / 10000 = 2,767,011,611,056,432,742.1
    assertThat(tax.trace().annualBase()).isEqualTo(big("110680464442257309684"));
    assertThat(tax.trace().numerator()).isEqualTo(big("442721857768951305936000"));
    assertThat(tax.trace().capApplied()).isTrue();
    assertThat(tax.trace().slices().get(3).amount()).isEqualTo(big("110680464442214109684"));
    assertThat(tax.amountMinor()).isEqualTo(2_767_011_611_056_432_742L);
    // Deductions 461,168,601,842,738,790 + 2,767,011,611,056,432,742 = 3,228,180,212,899,171,532.
    assertThat(line.employeeDeductionsMinor()).isEqualTo(3_228_180_212_899_171_532L);
    assertThat(line.previewNetMinor()).isEqualTo(5_995_191_823_955_604_275L);
    assertThat(line.employerCostMinor()).isEqualTo(max);

    // With employer contributions the cost is above Long.MAX_VALUE: refused, never wrapped.
    assertThatThrownBy(() -> seeded(true, false, max)).isInstanceOf(ArithmeticException.class);
    // The same gross with nothing applicable is exact: excluded, net = cost = gross.
    StatutoryLine excluded = seeded(false, false, max);
    assertThat(excluded.previewNetMinor()).isEqualTo(max);
    assertThat(excluded.employerCostMinor()).isEqualTo(max);
  }

  @Test
  void aNegativeGrossIsRefused() {
    assertThatThrownBy(() -> seeded(true, true, -1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyNumberComesFromTheRulesNotFromTheEngine() {
    // The employee pension rate 500 -> 1234: 162000 * 1234 / 10000 = 19,990.8 -> 19,991.
    String changedRate =
        StatutoryFixtures.SEED_JSON.replaceFirst("\"rateBp\": 500", "\"rateBp\": 1234");
    StatutoryLine changed = calculate(changedRate, true, false, 162000);
    assertThat(component(changed, "CNSS_EMPLOYEE_PENSION").amountMinor()).isEqualTo(19_991L);
    assertThat(component(changed, "CNSS_EMPLOYER_PENSION").amountMinor()).isEqualTo(8100L);
    // The cap 3000 -> 100 (1%): the tax at 162000 is capped at 1620 instead of 4860.
    String changedCap = StatutoryFixtures.SEED_JSON.replace("\"capBp\": 3000", "\"capBp\": 100");
    StatutoryLine.Component capped =
        component(calculate(changedCap, false, true, 162000), "IRPP_SALARY_WITHHOLDING");
    assertThat(capped.amountMinor()).isEqualTo(1620L);
    assertThat(capped.trace().capApplied()).isTrue();
    // The periods 12 -> 1: the monthly base is taken as the year; 1,800,000 * 3% = 54,000.
    String changedPeriods =
        StatutoryFixtures.SEED_JSON.replace("\"periodsPerYear\": 12", "\"periodsPerYear\": 1");
    assertThat(
            component(calculate(changedPeriods, false, true, 1_800_000), "IRPP_SALARY_WITHHOLDING")
                .amountMinor())
        .isEqualTo(54_000L);
    // A bracket threshold 1,944,000 -> 1,000,000 moves the boundary: at 162000 (A = 1,944,000):
    // 1,000,000 * 3% + 944,000 * 15% = 30,000 + 141,600 = 171,600 a year; /12 = 14,300.
    String changedBracket =
        StatutoryFixtures.SEED_JSON.replace("\"upToAnnual\": 1944000", "\"upToAnnual\": 1000000");
    assertThat(
            component(calculate(changedBracket, false, true, 162000), "IRPP_SALARY_WITHHOLDING")
                .amountMinor())
        .isEqualTo(14_300L);
    // The rounding HALF_UP -> UP on the tax: 162001 gives 4860.15 -> 4861 instead of 4860.
    String rounded =
        StatutoryFixtures.SEED_JSON.replace(
            "\"rounding\": \"HALF_UP\"}\n  ]", "\"rounding\": \"UP\"}\n  ]");
    assertThat(rounded).isNotEqualTo(StatutoryFixtures.SEED_JSON);
    assertThat(
            component(calculate(rounded, false, true, 162001), "IRPP_SALARY_WITHHOLDING")
                .amountMinor())
        .isEqualTo(4861L);
  }

  @Test
  void aTotallyDifferentPackGivesItsOwnResultsWithNoTraceOfTheSeed() {
    // Invented rules: an employer levy of 77.77% (order 3) and a two-bracket annual schedule.
    String json =
        document(
            progressive("BETA_LEVY", 1, "GROSS", 1, null, TWO_BRACKETS, "DOWN")
                + ","
                + rate("ALPHA_FEE", "EMPLOYER", 3, "IRPP_SUBJECT", "GROSS", 7777, "HALF_UP"));
    StatutoryLine line = calculate(json, false, true, 700);
    // 700: A = 700; N = 500 * 1000 + 200 * 2000 = 900,000; /10000 = 90 exactly.
    assertThat(component(line, "BETA_LEVY").amountMinor()).isEqualTo(90L);
    // 1000 * 7777 / 10000 = 777.7 -> 778 for gross 1000; for 700: 544.39 -> 544.
    assertThat(component(line, "ALPHA_FEE").amountMinor()).isEqualTo(544L);
    assertThat(line.employeeDeductionsMinor()).isEqualTo(90L);
    assertThat(line.employerContributionsMinor()).isEqualTo(544L);
    assertThat(line.previewNetMinor()).isEqualTo(610L);
    assertThat(line.employerCostMinor()).isEqualTo(1244L);
    assertThat(component(calculate(json, false, true, 1000), "ALPHA_FEE").amountMinor())
        .isEqualTo(778L);
    // The flag a component requires is the pack's: both components here need IRPP_SUBJECT.
    assertThat(calculate(json, true, false, 700).excluded()).isTrue();
  }

  @Test
  void theEngineHoldsNoRateThresholdOrFormulaConstant() {
    int numeric = 0;
    for (Field field : StatutoryEngine.class.getDeclaredFields()) {
      assertThat(field.getType().isPrimitive()).as(field.getName()).isFalse();
      assertThat(Modifier.isStatic(field.getModifiers())).as(field.getName()).isTrue();
      assertThat(field.getType()).as(field.getName()).isEqualTo(BigInteger.class);
      numeric++;
    }
    // The single field is the basis-point unit of the rules schema.
    assertThat(numeric).isEqualTo(1);
    assertThat(StatutoryRules.BASIS_POINTS).isEqualTo(10_000);
  }
}
