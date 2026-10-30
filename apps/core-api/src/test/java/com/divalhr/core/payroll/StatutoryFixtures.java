package com.divalhr.core.payroll;

import com.divalhr.core.payroll.domain.StatutoryRules;
import com.divalhr.core.payroll.domain.StatutoryRulesException;

/**
 * Synthetic statutory rule packs for the MVP-094B tests. {@link #SEED_JSON} is the rules document
 * of the DRAFT reference pack seeded by V45 (a test proves the two cannot drift); the other packs
 * are invented and carry no legal meaning.
 */
final class StatutoryFixtures {

  /** The rules of the seeded DRAFT pack CD-SALARY-PREVIEW version 1. */
  static final String SEED_JSON =
      """
      {
        "schemaVersion": 1,
        "components": [
          {"id": "CNSS_EMPLOYEE_PENSION", "party": "EMPLOYEE", "category": "SOCIAL_CONTRIBUTION",
           "order": 1, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
           "method": {"type": "RATE", "rateBp": 500}, "rounding": "HALF_UP"},
          {"id": "CNSS_EMPLOYER_PENSION", "party": "EMPLOYER", "category": "SOCIAL_CONTRIBUTION",
           "order": 2, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
           "method": {"type": "RATE", "rateBp": 500}, "rounding": "HALF_UP"},
          {"id": "CNSS_EMPLOYER_OCCUPATIONAL_RISK", "party": "EMPLOYER",
           "category": "SOCIAL_CONTRIBUTION",
           "order": 3, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
           "method": {"type": "RATE", "rateBp": 150}, "rounding": "HALF_UP"},
          {"id": "CNSS_EMPLOYER_FAMILY_BENEFITS", "party": "EMPLOYER",
           "category": "SOCIAL_CONTRIBUTION",
           "order": 4, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
           "method": {"type": "RATE", "rateBp": 650}, "rounding": "HALF_UP"},
          {"id": "IRPP_SALARY_WITHHOLDING", "party": "EMPLOYEE", "category": "INCOME_TAX",
           "order": 5, "requires": "IRPP_SUBJECT", "base": "GROSS", "sourceKey": "DGI_IRPP",
           "method": {"type": "PROGRESSIVE", "periodsPerYear": 12, "capBp": 3000,
                      "brackets": [{"upToAnnual": 1944000, "rateBp": 300},
                                   {"upToAnnual": 21600000, "rateBp": 1500},
                                   {"upToAnnual": 43200000, "rateBp": 3000},
                                   {"upToAnnual": null, "rateBp": 4000}]},
           "rounding": "HALF_UP"}
        ]
      }
      """;

  private StatutoryFixtures() {}

  /**
   * The seeded reference rules, parsed.
   *
   * @return the rules
   */
  static StatutoryRules seed() {
    return StatutoryRules.parse(SEED_JSON);
  }

  /**
   * A rules document with the given components.
   *
   * @param components the JSON of the components, comma separated
   * @return the document
   */
  static String document(String components) {
    return "{\"schemaVersion\": 1, \"components\": [" + components + "]}";
  }

  /**
   * One flat-rate component as JSON.
   *
   * @param id the component id
   * @param party EMPLOYEE or EMPLOYER
   * @param order the order
   * @param requires CNSS_SUBJECT or IRPP_SUBJECT
   * @param base GROSS or GROSS_LESS_EMPLOYEE_SOCIAL
   * @param rateBp the rate in basis points
   * @param rounding HALF_UP, DOWN or UP
   * @return the JSON
   */
  static String rate(
      String id,
      String party,
      int order,
      String requires,
      String base,
      int rateBp,
      String rounding) {
    return "{\"id\": \""
        + id
        + "\", \"party\": \""
        + party
        + "\", \"category\": \"SOCIAL_CONTRIBUTION\", \"order\": "
        + order
        + ", \"requires\": \""
        + requires
        + "\", \"base\": \""
        + base
        + "\", \"sourceKey\": \"CNSS_RATES\", \"method\": {\"type\": \"RATE\", \"rateBp\": "
        + rateBp
        + "}, \"rounding\": \""
        + rounding
        + "\"}";
  }

  /**
   * One progressive component as JSON (income tax, employee, requires IRPP_SUBJECT).
   *
   * @param id the component id
   * @param order the order
   * @param base GROSS or GROSS_LESS_EMPLOYEE_SOCIAL
   * @param periodsPerYear 1 or 12
   * @param capBp the cap, or {@code null} for none
   * @param brackets the JSON array of brackets
   * @param rounding HALF_UP, DOWN or UP
   * @return the JSON
   */
  static String progressive(
      String id,
      int order,
      String base,
      int periodsPerYear,
      Integer capBp,
      String brackets,
      String rounding) {
    return "{\"id\": \""
        + id
        + "\", \"party\": \"EMPLOYEE\", \"category\": \"INCOME_TAX\", \"order\": "
        + order
        + ", \"requires\": \"IRPP_SUBJECT\", \"base\": \""
        + base
        + "\", \"sourceKey\": \"DGI_IRPP\", \"method\": {\"type\": \"PROGRESSIVE\","
        + " \"periodsPerYear\": "
        + periodsPerYear
        + (capBp == null ? "" : ", \"capBp\": " + capBp)
        + ", \"brackets\": "
        + brackets
        + "}, \"rounding\": \""
        + rounding
        + "\"}";
  }

  /** The brackets of the reference schedule. */
  static final String SEED_BRACKETS =
      "[{\"upToAnnual\": 1944000, \"rateBp\": 300}, {\"upToAnnual\": 21600000, \"rateBp\": 1500},"
          + " {\"upToAnnual\": 43200000, \"rateBp\": 3000}, {\"upToAnnual\": null, \"rateBp\":"
          + " 4000}]";

  /**
   * The reason a parse refuses a document.
   *
   * @param json the document
   * @return the reason, or {@code null} when it parses
   */
  static StatutoryRulesException.Reason reasonOf(String json) {
    try {
      StatutoryRules.parse(json);
      return null;
    } catch (StatutoryRulesException e) {
      return e.reason();
    }
  }
}
