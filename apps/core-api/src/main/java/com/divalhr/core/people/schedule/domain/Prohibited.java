package com.divalhr.core.people.schedule.domain;

import java.util.List;

/**
 * Case-insensitive stems that must never appear in a rendered headline or highlight (MVP-101,
 * D101-6): no absence, fault, noncompliance, underperformance, fraud, discipline, termination,
 * deduction, sanction, legal, medical or lending conclusion. Identical to {@code
 * prohibited-vocabulary.json} in the shared evaluation set; a test keeps the two equal.
 */
final class Prohibited {

  private static final List<String> EN =
      List.of(
          "absent",
          "absence",
          "fault",
          "blame",
          "noncompl",
          "non-compl",
          "underperform",
          "poor performance",
          "fraud",
          "disciplin",
          "terminat",
          "dismiss",
          "fire ",
          "deduct",
          "sanction",
          "penal",
          "lawsuit",
          "legal action",
          "medical",
          "diagnos",
          "loan",
          "lending",
          "credit",
          "salary cut",
          "misconduct");

  private static final List<String> FR =
      List.of(
          "absent",
          "absence",
          "faute",
          "blâm",
          "blam",
          "non conforme",
          "non-conforme",
          "sous-perform",
          "mauvaise performance",
          "fraude",
          "disciplin",
          "licenci",
          "renvoi",
          "retenue",
          "sanction",
          "pénalit",
          "penalit",
          "poursuite",
          "action en justice",
          "médical",
          "medical",
          "diagnostic",
          "prêt",
          "pret ",
          "crédit",
          "credit",
          "inconduite");

  private Prohibited() {}

  /**
   * The stems of a language.
   *
   * @param language {@code en} or {@code fr}
   * @return the stems
   */
  static List<String> stems(String language) {
    return "fr".equals(language) ? FR : EN;
  }

  /**
   * English stems (for the parity test).
   *
   * @return the stems
   */
  static List<String> english() {
    return EN;
  }

  /**
   * French stems (for the parity test).
   *
   * @return the stems
   */
  static List<String> french() {
    return FR;
  }
}
