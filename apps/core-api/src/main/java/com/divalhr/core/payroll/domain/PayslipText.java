package com.divalhr.core.payroll.domain;

import java.util.Map;

/**
 * The repository-owned wording of a payslip in English and French (MVP-094C, ADR 0035 section 12).
 * The payslip document renders from the server in the locale requested; the locale selects wording
 * only. The words "paid", "payment completed", "filed", "tax compliant" and "government approved"
 * (and their French equivalents) never appear: a payslip is not proof of payment, and the
 * statutory figures are a calculation based on a rule pack, not a legal-compliance claim.
 */
public final class PayslipText {

  private static final String RULE_PACK_EN = "Statutory calculation based on rule pack %s v%d";
  private static final String RULE_PACK_FR =
      "Calcul statutaire fondé sur le jeu de règles %s v%d";

  private static final Map<String, String[]> COMPONENTS =
      Map.of(
          "CNSS_EMPLOYEE_PENSION",
              new String[] {"CNSS pension (employee)", "Pension CNSS (salarié)"},
          "CNSS_EMPLOYER_PENSION",
              new String[] {"CNSS pension (employer)", "Pension CNSS (employeur)"},
          "CNSS_EMPLOYER_OCCUPATIONAL_RISK",
              new String[] {
                "CNSS occupational risk (employer)", "Risques professionnels CNSS (employeur)"
              },
          "CNSS_EMPLOYER_FAMILY_BENEFITS",
              new String[] {
                "CNSS family benefits (employer)", "Prestations familiales CNSS (employeur)"
              },
          "IRPP_SALARY_WITHHOLDING",
              new String[] {"IRPP salary withholding", "Retenue d'IRPP sur salaire"});

  private static final Map<String, String[]> EXPLANATIONS =
      Map.of(
          "RATE_APPLIED", new String[] {"Flat rate applied", "Taux fixe appliqué"},
          "PROGRESSIVE_APPLIED",
              new String[] {"Progressive schedule applied", "Barème progressif appliqué"},
          "CAP_APPLIED", new String[] {"Cap applied", "Plafond appliqué"},
          "NOT_SUBJECT", new String[] {"Not subject", "Non assujetti"},
          "BASE_GROSS",
              new String[] {"Base: contractual gross", "Base : salaire brut contractuel"},
          "BASE_GROSS_LESS_EMPLOYEE_SOCIAL",
              new String[] {
                "Base: gross less employee social contributions",
                "Base : brut diminué des cotisations sociales du salarié"
              });

  private PayslipText() {}

  /** The row and column labels of the payslip. */
  public enum Label {
    /** Payroll period. */
    PERIOD("Period", "Période"),
    /** Employee number. */
    EMPLOYEE_NUMBER("Employee number", "Numéro d'employé"),
    /** Document status. */
    STATUS("Status", "Statut"),
    /** Employee amounts section. */
    EMPLOYEE_AMOUNTS("Employee amounts", "Montants du salarié"),
    /** Contractual gross. */
    GROSS("Contractual gross", "Salaire brut contractuel"),
    /** Employee CNSS contribution. */
    EMPLOYEE_CNSS("Employee CNSS contribution", "Cotisation CNSS du salarié"),
    /** IRPP withholding. */
    IRPP("IRPP withholding", "Retenue d'IRPP"),
    /** Total employee deductions. */
    DEDUCTIONS("Total employee deductions", "Total des retenues du salarié"),
    /** Approved net. */
    NET("Approved net", "Net approuvé"),
    /** Employer contributions section. */
    EMPLOYER_SECTION(
        "Employer contributions (shown separately)",
        "Cotisations patronales (indiquées séparément)"),
    /** Total employer contributions. */
    EMPLOYER_TOTAL("Total employer contributions", "Total des cotisations patronales"),
    /** Component column. */
    COMPONENT("Component", "Composante"),
    /** Base column. */
    BASE("Base", "Base"),
    /** Amount column. */
    AMOUNT("Amount", "Montant"),
    /** Party column. */
    PARTY("Party", "Partie"),
    /** Explanation codes. */
    EXPLANATIONS("Explanations", "Explications"),
    /** Excluded employee. */
    EXCLUDED(
        "This employee is subject to neither CNSS nor IRPP.",
        "Ce salarié n'est soumis ni à la CNSS ni à l'IRPP."),
    /** Digest label. */
    DIGEST("Snapshot digest (SHA-256)", "Empreinte du bulletin (SHA-256)"),
    /** Employee party. */
    EMPLOYEE("Employee", "Salarié"),
    /** Employer party. */
    EMPLOYER("Employer", "Employeur"),
    /** Currency name. */
    CURRENCY("CDF", "CDF");

    private final String en;
    private final String fr;

    Label(String en, String fr) {
      this.en = en;
      this.fr = fr;
    }

    /**
     * The label in a locale.
     *
     * @param locale the locale
     * @return the text
     */
    public String in(PayslipLocale locale) {
      return locale == PayslipLocale.FR ? fr : en;
    }
  }

  private static String pick(PayslipLocale locale, String en, String fr) {
    return locale == PayslipLocale.FR ? fr : en;
  }

  /**
   * The document title.
   *
   * @param locale the locale
   * @return {@code Payslip} or {@code Bulletin de paie}
   */
  public static String title(PayslipLocale locale) {
    return pick(locale, "Payslip", "Bulletin de paie");
  }

  /**
   * The generic title stored in the PDF metadata (no name, number or period).
   *
   * @param locale the locale
   * @param voided whether the package is voided
   * @return the title
   */
  public static String documentTitle(PayslipLocale locale, boolean voided) {
    String title = title(locale);
    return voided ? title + " - " + voidedBanner(locale) : title;
  }

  /**
   * The status wording of an approved package.
   *
   * @param locale the locale
   * @return {@code Approved payroll} or {@code Paie approuvée}
   */
  public static String approvedPayroll(PayslipLocale locale) {
    return pick(locale, "Approved payroll", "Paie approuvée");
  }

  /**
   * The prominent status of a voided package.
   *
   * @param locale the locale
   * @return {@code VOIDED} or {@code ANNULÉ}
   */
  public static String voidedBanner(PayslipLocale locale) {
    return pick(locale, "VOIDED", "ANNULÉ");
  }

  /**
   * The status wording for a package status.
   *
   * @param locale the locale
   * @param voided whether the package is voided
   * @return the label
   */
  public static String statusLabel(PayslipLocale locale, boolean voided) {
    return voided ? voidedBanner(locale) : approvedPayroll(locale);
  }

  /**
   * The mandatory statement that a payslip is not proof of payment.
   *
   * @param locale the locale
   * @return the sentence
   */
  public static String notProofOfPayment(PayslipLocale locale) {
    return pick(locale, "Not proof of payment", "Ne constitue pas une preuve de paiement");
  }

  /**
   * The statement that employer contributions do not reduce the employee's net.
   *
   * @param locale the locale
   * @return the sentence
   */
  public static String employerContributionsNote(PayslipLocale locale) {
    return pick(
        locale,
        "Employer contributions are shown separately and do not reduce the employee's net.",
        "Les cotisations patronales sont indiquées séparément et ne réduisent"
            + " pas le net du salarié.");
  }

  /**
   * The rule pack statement.
   *
   * @param locale the locale
   * @param code the pack code
   * @param version the pack version
   * @return the sentence
   */
  public static String rulePackStatement(PayslipLocale locale, String code, int version) {
    String pattern = locale == PayslipLocale.FR ? RULE_PACK_FR : RULE_PACK_EN;
    return String.format(java.util.Locale.ROOT, pattern, code, version);
  }

  /**
   * The notice of a synthetic test pack.
   *
   * @param locale the locale
   * @return the sentence
   */
  public static String syntheticNotice(PayslipLocale locale) {
    return pick(
        locale,
        "Synthetic test data. This rule pack is a test pack, not an official DRC rule pack.",
        "Données de test synthétiques. Ce jeu de règles est un jeu d'essai, pas un jeu"
            + " de règles officiel de la RDC.");
  }

  /**
   * The notice of a voided package, with the closed reason label and never the explanation.
   *
   * @param locale the locale
   * @param reason the closed reason, or {@code null} when unknown
   * @return the sentence
   */
  public static String voidedNotice(PayslipLocale locale, PayrollVoidReason reason) {
    String base =
        pick(
            locale,
            "This payslip belongs to a payroll package that was voided. It is kept as evidence"
                + " and is no longer valid.",
            "Ce bulletin appartient à un lot de paie annulé. Il est conservé comme"
                + " preuve et n'est plus valide.");
    if (reason == null) {
      return base;
    }
    return base + " " + pick(locale, "Reason: ", "Motif : ") + reasonLabel(locale, reason) + ".";
  }

  /**
   * The label of a closed void reason.
   *
   * @param locale the locale
   * @param reason the reason
   * @return the label (never the administrator's explanation)
   */
  public static String reasonLabel(PayslipLocale locale, PayrollVoidReason reason) {
    return switch (reason) {
      case SOURCE_CORRECTION ->
          pick(locale, "Correction of the payroll run", "Correction du calcul de paie");
      case PROFILE_CORRECTION ->
          pick(
              locale,
              "Correction of statutory profiles",
              "Correction des profils statutaires");
      case RULE_PACK_CORRECTION ->
          pick(locale, "Correction of the rule pack", "Correction du jeu de règles");
      case ADMINISTRATIVE_ERROR ->
          pick(locale, "Administrative error", "Erreur administrative");
      case OTHER_CONTROLLED ->
          pick(
              locale,
              "Other reason recorded by the administrator",
              "Autre motif consigné par l'administrateur");
    };
  }

  /**
   * The label of a statutory component; an unknown id is shown as its stable code.
   *
   * @param locale the locale
   * @param id the component id
   * @return the label
   */
  public static String componentLabel(PayslipLocale locale, String id) {
    String[] labels = COMPONENTS.get(id);
    return labels == null ? id : pick(locale, labels[0], labels[1]);
  }

  /**
   * The label of an explanation code; an unknown code is shown as itself.
   *
   * @param locale the locale
   * @param code the explanation code
   * @return the label
   */
  public static String explanationLabel(PayslipLocale locale, String code) {
    String[] labels = EXPLANATIONS.get(code);
    return labels == null ? code : pick(locale, labels[0], labels[1]);
  }

  /**
   * The label of a party.
   *
   * @param locale the locale
   * @param party {@code EMPLOYEE} or {@code EMPLOYER}
   * @return the label
   */
  public static String partyLabel(PayslipLocale locale, String party) {
    return "EMPLOYER".equals(party) ? Label.EMPLOYER.in(locale) : Label.EMPLOYEE.in(locale);
  }
}
