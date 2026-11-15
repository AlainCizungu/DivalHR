package com.divalhr.core.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.StatutoryPackValidator.Check;
import com.divalhr.core.payroll.domain.StatutoryPackValidator.ReferenceCase;
import com.divalhr.core.payroll.domain.StatutoryPackValidator.Report;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** MVP-097A: the offline validator proves the mechanism with SYNTHETIC rules and cases only. */
class StatutoryPackValidatorTest {

  private static final Set<String> SOURCES = Set.of("CNSS_RATES", "DGI_IRPP");
  private static final LocalDate FROM = LocalDate.of(2020, 1, 1);

  // SYNTHETIC mechanism fixture: not a statement of any law.
  private static final String RULES =
      """
      {"schemaVersion":1,"components":[
       {"id":"SYN_EMPLOYEE_SOCIAL","party":"EMPLOYEE","category":"SOCIAL_CONTRIBUTION","order":1,
        "requires":"CNSS_SUBJECT","base":"GROSS","sourceKey":"CNSS_RATES",
        "method":{"type":"RATE","rateBp":500},"rounding":"HALF_UP"},
       {"id":"SYN_EMPLOYER_SOCIAL","party":"EMPLOYER","category":"SOCIAL_CONTRIBUTION","order":2,
        "requires":"CNSS_SUBJECT","base":"GROSS","sourceKey":"CNSS_RATES",
        "method":{"type":"RATE","rateBp":500},"rounding":"HALF_UP"},
       {"id":"SYN_TAX","party":"EMPLOYEE","category":"INCOME_TAX","order":3,
        "requires":"IRPP_SUBJECT","base":"GROSS","sourceKey":"DGI_IRPP",
        "method":{"type":"PROGRESSIVE","periodsPerYear":12,"capBp":3000,
                  "brackets":[{"upToAnnual":1944000,"rateBp":300},{"upToAnnual":null,"rateBp":1500}]},
        "rounding":"HALF_UP"}]}
      """;

  private static List<ReferenceCase> cases() {
    return List.of(
        new ReferenceCase(
            "BOTH",
            "{\"cnssSubject\":true,\"irppSubject\":true,\"grossMinor\":100000}",
            "{\"employeeDeductionsMinor\":8000,\"employerContributionsMinor\":5000,"
                + "\"components\":{\"SYN_EMPLOYEE_SOCIAL\":5000,\"SYN_EMPLOYER_SOCIAL\":5000,\"SYN_TAX\":3000}}"),
        new ReferenceCase(
            "NEITHER",
            "{\"cnssSubject\":false,\"irppSubject\":false,\"grossMinor\":100000}",
            "{\"employeeDeductionsMinor\":0,\"employerContributionsMinor\":0,\"components\":{}}"),
        new ReferenceCase(
            "SOCIAL_ONLY",
            "{\"cnssSubject\":true,\"irppSubject\":false,\"grossMinor\":200000}",
            "{\"employeeDeductionsMinor\":10000,\"employerContributionsMinor\":10000,"
                + "\"components\":{\"SYN_EMPLOYEE_SOCIAL\":10000,\"SYN_EMPLOYER_SOCIAL\":10000,\"SYN_TAX\":0}}"));
  }

  private static Report run(String rules, List<ReferenceCase> cases, LocalDate to) {
    return StatutoryPackValidator.validate(rules, SOURCES, FROM, to, cases);
  }

  private static boolean failed(Report report, String code) {
    return report.checks().stream().anyMatch(c -> c.code().equals(code) && !c.passed());
  }

  @Test
  void aSyntheticPackValidatesAndTheReportIsByteIdentical() {
    Report first = run(RULES, cases(), null);
    Report second = run(RULES, cases(), null);
    assertThat(first.passed()).as(first.checks().toString()).isTrue();
    assertThat(first).isEqualTo(second);
    assertThat(first.fixtureSha256()).matches("[0-9a-f]{64}");
    assertThat(first.engineVersion()).isEqualTo(StatutoryPackValidator.ENGINE_VERSION);
    assertThat(first.checks()).extracting(Check::code).contains("CASE:BOTH", "DETERMINISTIC");
  }

  @Test
  void aMutatedRateFailsValidation() {
    Report report = run(RULES.replace("\"rateBp\":500", "\"rateBp\":501"), cases(), null);
    assertThat(report.passed()).isFalse();
    assertThat(failed(report, "CASE:BOTH")).isTrue();
  }

  @Test
  void aMutatedThresholdFailsValidation() {
    Report report = run(RULES.replace("1944000", "1000000"), cases(), null);
    assertThat(failed(report, "CASE:BOTH")).isTrue();
  }

  @Test
  void aMutatedRoundingRuleFailsValidationWhereItChangesAnAmount() {
    List<ReferenceCase> odd =
        List.of(
            new ReferenceCase(
                "ODD_BOTH",
                "{\"cnssSubject\":true,\"irppSubject\":true,\"grossMinor\":100010}",
                "{\"employeeDeductionsMinor\":8001,\"employerContributionsMinor\":5001,"
                    + "\"components\":{\"SYN_EMPLOYEE_SOCIAL\":5001,\"SYN_EMPLOYER_SOCIAL\":5001,\"SYN_TAX\":3000}}"),
            cases().get(1),
            cases().get(2));
    assertThat(run(RULES, odd, null).passed()).isTrue();
    String down = RULES.replace("\"HALF_UP\"", "\"DOWN\"");
    assertThat(run(down, odd, null).passed()).isFalse();
  }

  @Test
  void aMutatedFixtureInputOrExpectedResultFailsAndChangesTheDigest() {
    Report base = run(RULES, cases(), null);
    List<ReferenceCase> input = new java.util.ArrayList<>(cases());
    input.set(0, new ReferenceCase("BOTH", input.get(0).inputsJson().replace("100000", "100100"), input.get(0).expectedJson()));
    Report mutatedInput = run(RULES, input, null);
    assertThat(failed(mutatedInput, "CASE:BOTH")).isTrue();
    assertThat(mutatedInput.fixtureSha256()).isNotEqualTo(base.fixtureSha256());
    List<ReferenceCase> expected = new java.util.ArrayList<>(cases());
    expected.set(0, new ReferenceCase("BOTH", expected.get(0).inputsJson(), expected.get(0).expectedJson().replace("8000", "8001")));
    assertThat(failed(run(RULES, expected, null), "CASE:BOTH")).isTrue();
  }

  @Test
  void overlappingOrUnorderedBracketsAndMissingSourcesAreRefused() {
    String unordered = RULES.replace("\"upToAnnual\":1944000", "\"upToAnnual\":0");
    assertThat(failed(run(unordered, cases(), null), "PACK_PARSES")).isTrue();
    assertThat(
            failed(
                StatutoryPackValidator.validate(RULES, Set.of("CNSS_RATES"), FROM, null, cases()),
                "SOURCES_DECLARED"))
        .isTrue();
  }

  @Test
  void anInvalidEffectivePeriodAndMissingCasesAreRefused() {
    assertThat(failed(run(RULES, cases(), LocalDate.of(2019, 12, 31)), "EFFECTIVE_PERIOD_VALID")).isTrue();
    Report none = run(RULES, List.of(), null);
    assertThat(failed(none, "CASES_PRESENT")).isTrue();
    assertThat(none.fixtureSha256()).isNull();
  }

  @Test
  void aCaseSetThatDoesNotExerciseEveryRuleFails() {
    Report report = run(RULES, List.of(cases().get(0)), null);
    assertThat(failed(report, "CASES_COVER_RULES")).isTrue();
  }

  @Test
  void aFractionalMoneyValueIsRefused() {
    String fractional = RULES.replace("\"upToAnnual\":1944000", "\"upToAnnual\":1944000.5");
    Report report = run(fractional, cases(), null);
    assertThat(report.passed()).isFalse();
    assertThat(failed(report, "PACK_PARSES")).isTrue();
  }
}
