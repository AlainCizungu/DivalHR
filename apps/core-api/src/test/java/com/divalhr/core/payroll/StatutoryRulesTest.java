package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.StatutoryFixtures.SEED_JSON;
import static com.divalhr.core.payroll.StatutoryFixtures.reasonOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.StatutoryRules;
import com.divalhr.core.payroll.domain.StatutoryRules.Base;
import com.divalhr.core.payroll.domain.StatutoryRules.Category;
import com.divalhr.core.payroll.domain.StatutoryRules.Party;
import com.divalhr.core.payroll.domain.StatutoryRules.Progressive;
import com.divalhr.core.payroll.domain.StatutoryRules.Rate;
import com.divalhr.core.payroll.domain.StatutoryRules.Requirement;
import com.divalhr.core.payroll.domain.StatutoryRules.Rounding;
import com.divalhr.core.payroll.domain.StatutoryRulesException;
import com.divalhr.core.payroll.domain.StatutoryRulesException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * MVP-094B (ADR 0034 section 3): the strict rules parser accepts the reference pack, reads the
 * seeded pack out of the V45 migration so that the two cannot drift, and refuses every invalid
 * variant with a stable reason that never echoes the input.
 */
class StatutoryRulesTest {

  private static final String LESS = "GROSS_LESS_EMPLOYEE_SOCIAL";

  /** The seed JSON with the first occurrence of {@code from} replaced by {@code to}. */
  private static String variant(String from, String to) {
    assertThat(SEED_JSON).contains(from);
    return SEED_JSON.replaceFirst(Pattern.quote(from), Matcher.quoteReplacement(to));
  }

  private static void rejects(String from, String to, Reason reason) {
    refused(variant(from, to), reason);
  }

  private static void refused(String json, Reason reason) {
    assertThat(reasonOf(json)).isEqualTo(reason);
  }

  @Test
  void theReferencePackParsesToTheDocumentedComponents() {
    StatutoryRules rules = StatutoryRules.parse(SEED_JSON);
    assertThat(rules.components()).hasSize(5);
    assertThat(rules.components().stream().map(StatutoryRules.Component::id).toList())
        .containsExactly(
            "CNSS_EMPLOYEE_PENSION",
            "CNSS_EMPLOYER_PENSION",
            "CNSS_EMPLOYER_OCCUPATIONAL_RISK",
            "CNSS_EMPLOYER_FAMILY_BENEFITS",
            "IRPP_SALARY_WITHHOLDING");
    assertThat(rules.components().stream().map(StatutoryRules.Component::order).toList())
        .containsExactly(1, 2, 3, 4, 5);
    StatutoryRules.Component employee = rules.components().get(0);
    assertThat(employee.party()).isEqualTo(Party.EMPLOYEE);
    assertThat(employee.category()).isEqualTo(Category.SOCIAL_CONTRIBUTION);
    assertThat(employee.requires()).isEqualTo(Requirement.CNSS_SUBJECT);
    assertThat(employee.base()).isEqualTo(Base.GROSS);
    assertThat(employee.sourceKey()).isEqualTo("CNSS_RATES");
    assertThat(employee.method()).isEqualTo(new Rate(500));
    assertThat(employee.rounding()).isEqualTo(Rounding.HALF_UP);
    StatutoryRules.Component tax = rules.components().get(4);
    assertThat(tax.category()).isEqualTo(Category.INCOME_TAX);
    Progressive schedule = (Progressive) tax.method();
    assertThat(schedule.periodsPerYear()).isEqualTo(12);
    assertThat(schedule.capBp()).isEqualTo(3000);
    assertThat(schedule.brackets().stream().map(StatutoryRules.Bracket::upToAnnual).toList())
        .containsExactly(1_944_000L, 21_600_000L, 43_200_000L, null);
    assertThat(schedule.brackets().stream().map(StatutoryRules.Bracket::rateBp).toList())
        .containsExactly(300, 1500, 3000, 4000);
    assertThat(rules.citedSourceKeys()).containsExactlyInAnyOrder("CNSS_RATES", "DGI_IRPP");
  }

  @Test
  void theSeededPackOfTheMigrationIsTheReferencePack() throws IOException {
    String sql;
    try (InputStream in =
        StatutoryRulesTest.class.getResourceAsStream(
            "/db/migration/V45__payroll_statutory_preview.sql")) {
      assertThat(in).isNotNull();
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    int start = sql.indexOf("$rules$");
    int end = sql.indexOf("$rules$", start + 1);
    assertThat(start).isGreaterThanOrEqualTo(0);
    assertThat(end).isGreaterThan(start);
    assertThat(sql.indexOf("$rules$", end + 1)).isEqualTo(-1);
    String seeded = sql.substring(start + "$rules$".length(), end);
    // The same rules whatever the whitespace: structurally equal once parsed.
    assertThat(StatutoryRules.parse(seeded)).isEqualTo(StatutoryRules.parse(SEED_JSON));
    // The pack is seeded DRAFT, never verified, with no hash and no reviewer.
    assertThat(sql).contains("'CD-SALARY-PREVIEW', 1, 'CD', 'CDF', 'DRAFT'");
    assertThat(sql).doesNotContain("VERIFIED', DATE");
    assertThat(sql).doesNotContain("reviewer_name,");
  }

  @Test
  void theSeededSourcesCoverEverySourceTheRulesCite() throws IOException {
    String sql;
    try (InputStream in =
        StatutoryRulesTest.class.getResourceAsStream(
            "/db/migration/V45__payroll_statutory_preview.sql")) {
      assertThat(in).isNotNull();
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    for (String key : StatutoryRules.SOURCE_KEYS) {
      assertThat(sql).contains("'" + key + "'");
    }
    StatutoryRules.parse(SEED_JSON).requireSources(StatutoryRules.SOURCE_KEYS);
  }

  @Test
  void aRatePackWithoutACapAndWithOnePeriodAndOneOpenBracketIsAccepted() {
    String json =
        StatutoryFixtures.document(
            StatutoryFixtures.progressive(
                "FLAT_TAX", 1, "GROSS", 1, null, "[{\"upToAnnual\": null, \"rateBp\": 0}]", "UP"));
    StatutoryRules rules = StatutoryRules.parse(json);
    Progressive method = (Progressive) rules.components().get(0).method();
    assertThat(method.capBp()).isNull();
    assertThat(method.periodsPerYear()).isEqualTo(1);
    assertThat(rules.components().get(0).rounding()).isEqualTo(Rounding.UP);
  }

  @Test
  void anAscendingOrderWithGapsIsAcceptedAndStringEscapesAreRead() {
    String json =
        StatutoryFixtures.document(
            StatutoryFixtures.rate("A_\\u0042C", "EMPLOYEE", 10, "CNSS_SUBJECT", "GROSS", 0, "DOWN")
                + ","
                + StatutoryFixtures.rate(
                    "ABD", "EMPLOYER", 999, "CNSS_SUBJECT", LESS, 10000, "UP"));
    StatutoryRules rules = StatutoryRules.parse(json);
    assertThat(rules.components().get(0).id()).isEqualTo("A_BC");
    assertThat(rules.components().get(1).base()).isEqualTo(Base.GROSS_LESS_EMPLOYEE_SOCIAL);
  }

  @Test
  void unknownAndMissingFieldsAreRefused() {
    rejects("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"extra\": 1,", Reason.UNKNOWN_FIELD);
    rejects("\"order\": 1,", "\"order\": 1, \"note\": \"x\",", Reason.UNKNOWN_FIELD);
    rejects(
        "\"type\": \"RATE\", \"rateBp\": 500",
        "\"type\": \"RATE\", \"rateBp\": 500, \"x\": 1",
        Reason.UNKNOWN_FIELD);
    rejects("\"capBp\": 3000,", "\"capBp\": 3000, \"floorBp\": 1,", Reason.UNKNOWN_FIELD);
    rejects(
        "{\"upToAnnual\": 1944000, \"rateBp\": 300}",
        "{\"upToAnnual\": 1944000, \"rateBp\": 300, \"k\": 1}",
        Reason.UNKNOWN_FIELD);
    rejects("\"schemaVersion\": 1,", "", Reason.MISSING_FIELD);
    rejects(", \"rounding\": \"HALF_UP\"}", "}", Reason.MISSING_FIELD);
    rejects("\"sourceKey\": \"CNSS_RATES\",", "", Reason.MISSING_FIELD);
    rejects("\"type\": \"RATE\", \"rateBp\": 500", "\"type\": \"RATE\"", Reason.MISSING_FIELD);
    rejects("{\"type\": \"RATE\", \"rateBp\": 500}", "{\"rateBp\": 500}", Reason.MISSING_FIELD);
    rejects("\"periodsPerYear\": 12,", "", Reason.MISSING_FIELD);
    rejects("{\"upToAnnual\": null, \"rateBp\": 4000}", "{\"rateBp\": 4000}", Reason.MISSING_FIELD);
  }

  @Test
  void aWrongSchemaVersionOrTypeIsRefused() {
    rejects("\"schemaVersion\": 1", "\"schemaVersion\": 2", Reason.UNSUPPORTED_SCHEMA_VERSION);
    rejects("\"schemaVersion\": 1", "\"schemaVersion\": 0", Reason.UNSUPPORTED_SCHEMA_VERSION);
    rejects("\"schemaVersion\": 1", "\"schemaVersion\": \"1\"", Reason.WRONG_TYPE);
    rejects("\"schemaVersion\": 1", "\"schemaVersion\": true", Reason.WRONG_TYPE);
    refused("[]", Reason.WRONG_TYPE);
    refused("\"text\"", Reason.WRONG_TYPE);
    refused("{\"schemaVersion\": 1, \"components\": {}}", Reason.WRONG_TYPE);
    refused("{\"schemaVersion\": 1, \"components\": [1]}", Reason.WRONG_TYPE);
    rejects(
        "\"method\": {\"type\": \"RATE\", \"rateBp\": 500}", "\"method\": 5", Reason.WRONG_TYPE);
    rejects("\"id\": \"CNSS_EMPLOYEE_PENSION\"", "\"id\": 7", Reason.WRONG_TYPE);
    rejects("\"order\": 1,", "\"order\": \"1\",", Reason.WRONG_TYPE);
    rejects("\"rateBp\": 500}", "\"rateBp\": null}", Reason.WRONG_TYPE);
    rejects("\"upToAnnual\": 1944000", "\"upToAnnual\": \"1944000\"", Reason.WRONG_TYPE);
    refused(
        StatutoryFixtures.document(
            StatutoryFixtures.progressive("ODD_TAX", 1, "GROSS", 12, null, "{}", "UP")),
        Reason.WRONG_TYPE);
  }

  @Test
  void theNumberOfComponentsIsOneToTwelve() {
    refused("{\"schemaVersion\": 1, \"components\": []}", Reason.INVALID_COMPONENT_COUNT);
    List<String> thirteen = new ArrayList<>();
    for (int i = 1; i <= 13; i++) {
      thirteen.add(
          StatutoryFixtures.rate(
              "RULE_" + (char) ('A' + i), "EMPLOYER", i, "CNSS_SUBJECT", "GROSS", 100, "HALF_UP"));
    }
    refused(StatutoryFixtures.document(String.join(",", thirteen)), Reason.INVALID_COMPONENT_COUNT);
    StatutoryRules.parse(StatutoryFixtures.document(String.join(",", thirteen.subList(0, 12))));
  }

  @Test
  void idsAreFromTheClosedPatternAndUnique() {
    rejects("CNSS_EMPLOYEE_PENSION", "cnss_employee_pension", Reason.INVALID_COMPONENT_ID);
    rejects("CNSS_EMPLOYEE_PENSION", "AB", Reason.INVALID_COMPONENT_ID);
    rejects("CNSS_EMPLOYEE_PENSION", "A".repeat(49), Reason.INVALID_COMPONENT_ID);
    rejects("CNSS_EMPLOYEE_PENSION", "CNSS-EMPLOYEE", Reason.INVALID_COMPONENT_ID);
    rejects("CNSS_EMPLOYEE_PENSION", "CNSS_1", Reason.INVALID_COMPONENT_ID);
    rejects("CNSS_EMPLOYEE_PENSION", "", Reason.INVALID_COMPONENT_ID);
    refused(
        variant("\"id\": \"CNSS_EMPLOYER_PENSION\"", "\"id\": \"CNSS_EMPLOYEE_PENSION\""),
        Reason.DUPLICATE_COMPONENT_ID);
    StatutoryRules.parse(variant("CNSS_EMPLOYEE_PENSION", "A".repeat(48)));
    StatutoryRules.parse(variant("CNSS_EMPLOYEE_PENSION", "ABC"));
  }

  @Test
  void ordersAreUniqueAndAscending() {
    rejects("\"order\": 2,", "\"order\": 1,", Reason.INVALID_ORDER);
    rejects("\"order\": 4,", "\"order\": 2,", Reason.INVALID_ORDER);
    rejects("\"order\": 1,", "\"order\": 0,", Reason.INVALID_ORDER);
    rejects("\"order\": 1,", "\"order\": -3,", Reason.INVALID_ORDER);
    rejects("\"order\": 5,", "\"order\": 1001,", Reason.INVALID_ORDER);
    rejects("\"order\": 5,", "\"order\": 4,", Reason.INVALID_ORDER);
    StatutoryRules.parse(variant("\"order\": 5,", "\"order\": 1000,"));
  }

  @Test
  void everyVocabularyIsClosedAndCaseSensitive() {
    rejects("\"EMPLOYEE\"", "\"WORKER\"", Reason.UNKNOWN_VALUE);
    rejects("\"EMPLOYEE\"", "\"employee\"", Reason.UNKNOWN_VALUE);
    rejects("\"SOCIAL_CONTRIBUTION\"", "\"SOCIAL\"", Reason.UNKNOWN_VALUE);
    rejects("\"INCOME_TAX\"", "\"VAT\"", Reason.UNKNOWN_VALUE);
    rejects("\"CNSS_SUBJECT\"", "\"ALWAYS\"", Reason.UNKNOWN_VALUE);
    rejects("\"base\": \"GROSS\"", "\"base\": \"NET\"", Reason.UNKNOWN_VALUE);
    rejects("\"base\": \"GROSS\"", "\"base\": \"gross\"", Reason.UNKNOWN_VALUE);
    rejects("\"HALF_UP\"", "\"HALF_EVEN\"", Reason.UNKNOWN_VALUE);
    rejects("\"HALF_UP\"", "\"half_up\"", Reason.UNKNOWN_VALUE);
    rejects("\"type\": \"RATE\"", "\"type\": \"FORMULA\"", Reason.UNKNOWN_VALUE);
    rejects("\"type\": \"RATE\"", "\"type\": \"rate\"", Reason.UNKNOWN_VALUE);
    rejects("\"sourceKey\": \"CNSS_RATES\"", "\"sourceKey\": \"BLOG\"", Reason.UNKNOWN_SOURCE_KEY);
    rejects("\"sourceKey\": \"CNSS_RATES\"", "\"sourceKey\": \"\"", Reason.UNKNOWN_SOURCE_KEY);
  }

  @Test
  void ratesAndTheCapAreZeroToTenThousandBasisPoints() {
    rejects("\"rateBp\": 500}", "\"rateBp\": 10001}", Reason.INVALID_RATE);
    rejects("\"rateBp\": 500}", "\"rateBp\": -1}", Reason.INVALID_RATE);
    rejects("\"rateBp\": 300}", "\"rateBp\": 10001}", Reason.INVALID_RATE);
    rejects("\"rateBp\": 4000}", "\"rateBp\": -5}", Reason.INVALID_RATE);
    rejects("\"capBp\": 3000", "\"capBp\": 10001", Reason.INVALID_CAP);
    rejects("\"capBp\": 3000", "\"capBp\": -1", Reason.INVALID_CAP);
    rejects("\"capBp\": 3000", "\"capBp\": null", Reason.WRONG_TYPE);
    StatutoryRules.parse(variant("\"rateBp\": 500}", "\"rateBp\": 10000}"));
    StatutoryRules.parse(variant("\"rateBp\": 500}", "\"rateBp\": 0}"));
    StatutoryRules.parse(variant("\"capBp\": 3000", "\"capBp\": 0"));
    StatutoryRules.parse(variant("\"capBp\": 3000", "\"capBp\": 10000"));
  }

  @Test
  void numbersAreIntegersOfASupportedSize() {
    rejects("\"rateBp\": 500}", "\"rateBp\": 5.5}", Reason.INVALID_NUMBER);
    rejects("\"rateBp\": 500}", "\"rateBp\": 5e2}", Reason.INVALID_NUMBER);
    rejects("\"rateBp\": 500}", "\"rateBp\": 5E2}", Reason.INVALID_NUMBER);
    rejects("\"rateBp\": 500}", "\"rateBp\": 99999999999999999999}", Reason.INVALID_NUMBER);
    rejects("\"rateBp\": 500}", "\"rateBp\": 4294967296}", Reason.INVALID_NUMBER);
    rejects("\"rateBp\": 500}", "\"rateBp\": 0500}", Reason.MALFORMED_JSON);
    rejects("\"rateBp\": 500}", "\"rateBp\": +500}", Reason.MALFORMED_JSON);
    rejects("\"rateBp\": 500}", "\"rateBp\": -}", Reason.MALFORMED_JSON);
    rejects("\"rateBp\": 500}", "\"rateBp\": NaN}", Reason.MALFORMED_JSON);
  }

  @Test
  void periodsPerYearIsOneOrTwelve() {
    for (String bad : new String[] {"0", "2", "4", "13", "-12"}) {
      rejects("\"periodsPerYear\": 12", "\"periodsPerYear\": " + bad, Reason.INVALID_PERIODS);
    }
    StatutoryRules.parse(variant("\"periodsPerYear\": 12", "\"periodsPerYear\": 1"));
  }

  @Test
  void bracketsAscendAndTheLastOneIsOpen() {
    // The last bracket must be open.
    rejects("\"upToAnnual\": null", "\"upToAnnual\": 99999999", Reason.INVALID_BRACKETS);
    // Only the last bracket is open.
    rejects("\"upToAnnual\": 21600000", "\"upToAnnual\": null", Reason.INVALID_BRACKETS);
    // Strictly ascending thresholds.
    rejects("\"upToAnnual\": 21600000", "\"upToAnnual\": 1000000", Reason.INVALID_BRACKETS);
    rejects("\"upToAnnual\": 21600000", "\"upToAnnual\": 1944000", Reason.INVALID_BRACKETS);
    rejects("\"upToAnnual\": 1944000", "\"upToAnnual\": 0", Reason.INVALID_BRACKETS);
    rejects("\"upToAnnual\": 1944000", "\"upToAnnual\": -1", Reason.INVALID_BRACKETS);
    refused(
        variant("\"upToAnnual\": 1944000", "\"upToAnnual\": 1000000000000001"),
        Reason.INVALID_BRACKETS);
    // At least one bracket, at most twelve.
    String none =
        StatutoryFixtures.document(
            StatutoryFixtures.progressive("EMPTY_TAX", 1, "GROSS", 12, null, "[]", "HALF_UP"));
    refused(none, Reason.INVALID_BRACKETS);
    StringBuilder many = new StringBuilder("[");
    for (int i = 1; i <= 13; i++) {
      many.append("{\"upToAnnual\": ").append(i * 1000).append(", \"rateBp\": 1},");
    }
    many.append("{\"upToAnnual\": null, \"rateBp\": 1}]");
    refused(
        StatutoryFixtures.document(
            StatutoryFixtures.progressive("MANY_TAX", 1, "GROSS", 12, null, many.toString(), "UP")),
        Reason.INVALID_BRACKETS);
  }

  @Test
  void aBaseDependsOnlyOnAnEmployeeSocialContributionOfALowerOrder() {
    // The first component cannot be net of anything.
    refused(
        variant("\"base\": \"GROSS\"", "\"base\": \"GROSS_LESS_EMPLOYEE_SOCIAL\""),
        Reason.INVALID_BASE_DEPENDENCY);
    // An employer contribution is not an employee social contribution.
    String employerOnly =
        StatutoryFixtures.document(
            StatutoryFixtures.rate("ER_PENSION", "EMPLOYER", 1, "CNSS_SUBJECT", "GROSS", 500, "UP")
                + ","
                + StatutoryFixtures.progressive(
                    "TAX_AFTER",
                    2,
                    LESS,
                    12,
                    null,
                    "[{\"upToAnnual\": null, \"rateBp\": 100}]",
                    "UP"));
    refused(employerOnly, Reason.INVALID_BASE_DEPENDENCY);
    // A higher order does not count.
    String later =
        StatutoryFixtures.document(
            StatutoryFixtures.rate("EE_LESS", "EMPLOYER", 1, "CNSS_SUBJECT", LESS, 1, "UP")
                + ","
                + StatutoryFixtures.rate(
                    "EE_PENSION", "EMPLOYEE", 2, "CNSS_SUBJECT", "GROSS", 500, "UP"));
    refused(later, Reason.INVALID_BASE_DEPENDENCY);
    String ok =
        StatutoryFixtures.document(
            StatutoryFixtures.rate("EE_PENSION", "EMPLOYEE", 1, "CNSS_SUBJECT", "GROSS", 500, "UP")
                + ","
                + StatutoryFixtures.progressive(
                    "TAX_AFTER",
                    2,
                    LESS,
                    12,
                    null,
                    "[{\"upToAnnual\": null, \"rateBp\": 100}]",
                    "UP"));
    StatutoryRules.parse(ok);
  }

  @Test
  void everyCitedSourceMustBeAmongThePacksSources() {
    StatutoryRules rules = StatutoryRules.parse(SEED_JSON);
    rules.requireSources(Set.of("CNSS_RATES", "DGI_IRPP", "DGI_PORTAL"));
    assertThatThrownBy(() -> rules.requireSources(Set.of("CNSS_RATES")))
        .isInstanceOfSatisfying(
            StatutoryRulesException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.UNKNOWN_SOURCE_KEY));
    assertThatThrownBy(() -> rules.requireSources(Set.of()))
        .isInstanceOf(StatutoryRulesException.class);
  }

  @Test
  void malformedDuplicateOversizedAndDeepDocumentsAreRefused() {
    refused(null, Reason.MALFORMED_JSON);
    refused("", Reason.MALFORMED_JSON);
    refused("   ", Reason.MALFORMED_JSON);
    refused("{", Reason.MALFORMED_JSON);
    refused("{\"a\":}", Reason.MALFORMED_JSON);
    refused("{\"a\" 1}", Reason.MALFORMED_JSON);
    refused("{'a': 1}", Reason.MALFORMED_JSON);
    refused("{\"a\": 1,}", Reason.MALFORMED_JSON);
    refused("{\"a\": [1,]}", Reason.MALFORMED_JSON);
    refused("{\"a\": tru}", Reason.MALFORMED_JSON);
    refused(SEED_JSON + " x", Reason.MALFORMED_JSON);
    refused(SEED_JSON + SEED_JSON, Reason.MALFORMED_JSON);
    refused("\uFEFF" + SEED_JSON, Reason.MALFORMED_JSON);
    refused("{/* c */ \"a\": 1}", Reason.MALFORMED_JSON);
    refused("{\"a\": \"line\nbreak\"}", Reason.MALFORMED_JSON);
    refused("{\"a\": \"\\q\"}", Reason.MALFORMED_JSON);
    refused("{\"a\": \"\\u12G4\"}", Reason.MALFORMED_JSON);
    refused("{\"a\": \"unterminated}", Reason.MALFORMED_JSON);
    refused(
        variant("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"schemaVersion\": 1,"),
        Reason.DUPLICATE_FIELD);
    rejects("\"order\": 1,", "\"order\": 1, \"order\": 1,", Reason.DUPLICATE_FIELD);
    refused(" ".repeat(65_537), Reason.TOO_LARGE);
    refused("[".repeat(9) + "]".repeat(9), Reason.TOO_DEEP);
    refused("{\"a\":".repeat(9) + "1" + "}".repeat(9), Reason.TOO_DEEP);
  }

  @Test
  void aRefusalNamesAReasonButNeverEchoesTheInput() {
    String secret = "SECRET-SALARY-7777";
    for (String json :
        new String[] {
          "{\"" + secret + "\": 1}",
          variant("\"EMPLOYEE\"", "\"" + secret + "\""),
          variant("CNSS_EMPLOYEE_PENSION", secret.toLowerCase(java.util.Locale.ROOT)),
          "{\"schemaVersion\": 1, \"components\": [\"" + secret + "\"]}",
          "{\"a\": \"" + secret + "\n\"}",
          variant("\"rateBp\": 500}", "\"rateBp\": " + secret + "}")
        }) {
      assertThatThrownBy(() -> StatutoryRules.parse(json))
          .isInstanceOfSatisfying(
              StatutoryRulesException.class,
              e -> {
                assertThat(e.getMessage()).isEqualTo(e.reason().name());
                assertThat(e.toString()).doesNotContainIgnoringCase(secret);
                assertThat(e.getCause()).isNull();
              });
    }
  }
}
