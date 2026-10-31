package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.PayrollVoidReason;
import com.divalhr.core.payroll.domain.PayslipLocale;
import com.divalhr.core.payroll.domain.PayslipText;
import com.divalhr.core.payroll.domain.PayslipText.Label;
import com.divalhr.core.payroll.domain.StatutoryExplanation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** MVP-094C: the English and French payslip wording is complete and obeys the product language. */
class PayslipTextTest {

  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

  // The product-language rules of Issue #181: never "paid", "payment completed", "filed", "tax
  // compliant" or "government approved", and never their French equivalents. "Approved payroll",
  // "Paie approuvée" and "Not proof of payment" are required wording and must not be flagged.
  private static final List<Pattern> FORBIDDEN_EN =
      List.of(
          Pattern.compile("(?<!\\p{L})paid(?!\\p{L})", FLAGS),
          Pattern.compile("payment\\s+completed", FLAGS),
          Pattern.compile("(?<!\\p{L})filed(?!\\p{L})", FLAGS),
          Pattern.compile("tax[\\s-]+compliant", FLAGS),
          Pattern.compile("government[\\s-]+approved", FLAGS));

  private static final List<Pattern> FORBIDDEN_FR =
      List.of(
          Pattern.compile("(?<!\\p{L})pay[ée]e?s?(?!\\p{L})", FLAGS),
          Pattern.compile("paiement\\s+effectu[ée]", FLAGS),
          Pattern.compile("(?<!\\p{L})d[ée]clar[ée]e?s?(?!\\p{L})", FLAGS),
          Pattern.compile("conforme\\s+fiscalement", FLAGS),
          Pattern.compile("approuv[ée]e?s?\\s+par\\s+(le\\s+gouvernement|l['’]État)", FLAGS));

  private static final List<String> COMPONENT_IDS =
      List.of(
          "CNSS_EMPLOYEE_PENSION",
          "CNSS_EMPLOYER_PENSION",
          "CNSS_EMPLOYER_OCCUPATIONAL_RISK",
          "CNSS_EMPLOYER_FAMILY_BENEFITS",
          "IRPP_SALARY_WITHHOLDING");

  /** Every sentence and label a payslip can print in a locale. */
  private static List<String> everything(PayslipLocale locale) {
    List<String> all = new ArrayList<>();
    for (Label label : Label.values()) {
      all.add(label.in(locale));
    }
    all.add(PayslipText.title(locale));
    all.add(PayslipText.documentTitle(locale, false));
    all.add(PayslipText.documentTitle(locale, true));
    all.add(PayslipText.approvedPayroll(locale));
    all.add(PayslipText.voidedBanner(locale));
    all.add(PayslipText.statusLabel(locale, false));
    all.add(PayslipText.statusLabel(locale, true));
    all.add(PayslipText.notProofOfPayment(locale));
    all.add(PayslipText.employerContributionsNote(locale));
    all.add(PayslipText.rulePackStatement(locale, "SYNTHETIC-TEST-DRC", 3));
    all.add(PayslipText.syntheticNotice(locale));
    all.add(PayslipText.voidedNotice(locale, null));
    for (PayrollVoidReason reason : PayrollVoidReason.values()) {
      all.add(PayslipText.voidedNotice(locale, reason));
      all.add(PayslipText.reasonLabel(locale, reason));
    }
    for (String id : COMPONENT_IDS) {
      all.add(PayslipText.componentLabel(locale, id));
    }
    for (StatutoryExplanation code : StatutoryExplanation.values()) {
      all.add(PayslipText.explanationLabel(locale, code.name()));
    }
    all.add(PayslipText.partyLabel(locale, "EMPLOYEE"));
    all.add(PayslipText.partyLabel(locale, "EMPLOYER"));
    return all;
  }

  @Test
  void everyLabelExistsAndIsNotBlankInBothLanguages() {
    assertThat(Label.values().length).isGreaterThan(20);
    for (Label label : Label.values()) {
      assertThat(label.in(PayslipLocale.EN)).as(label + " EN").isNotBlank();
      assertThat(label.in(PayslipLocale.FR)).as(label + " FR").isNotBlank();
      for (PayslipLocale locale : PayslipLocale.values()) {
        String text = label.in(locale);
        assertThat(text).as(label + " trimmed " + locale).isEqualTo(text.strip());
      }
    }
  }

  @Test
  void aFrenchLabelDiffersFromTheEnglishOneUnlessItIsASharedTerm() {
    List<Label> shared = List.of(Label.BASE, Label.CURRENCY);
    for (Label label : Label.values()) {
      if (shared.contains(label)) {
        continue;
      }
      assertThat(label.in(PayslipLocale.FR))
          .as(label.name())
          .isNotEqualTo(label.in(PayslipLocale.EN));
    }
  }

  @Test
  void everyStatutoryComponentAndExplanationHasALabelInBothLanguages() {
    for (PayslipLocale locale : PayslipLocale.values()) {
      for (String id : COMPONENT_IDS) {
        assertThat(PayslipText.componentLabel(locale, id)).as(id + " " + locale).isNotEqualTo(id);
      }
      for (StatutoryExplanation code : StatutoryExplanation.values()) {
        assertThat(PayslipText.explanationLabel(locale, code.name()))
            .as(code + " " + locale)
            .isNotEqualTo(code.name())
            .isNotBlank();
      }
    }
    assertThat(PayslipText.componentLabel(PayslipLocale.EN, "UNKNOWN_RULE"))
        .isEqualTo("UNKNOWN_RULE");
    assertThat(PayslipText.explanationLabel(PayslipLocale.FR, "UNKNOWN_CODE"))
        .isEqualTo("UNKNOWN_CODE");
  }

  @Test
  void everyVoidReasonHasADistinctLabelInBothLanguagesAndNeverTheExplanation() {
    for (PayslipLocale locale : PayslipLocale.values()) {
      List<String> labels = new ArrayList<>();
      for (PayrollVoidReason reason : PayrollVoidReason.values()) {
        String label = PayslipText.reasonLabel(locale, reason);
        assertThat(label).as(reason + " " + locale).isNotBlank();
        assertThat(labels).doesNotContain(label);
        labels.add(label);
        assertThat(PayslipText.voidedNotice(locale, reason)).contains(label);
      }
    }
  }

  @Test
  void theNotProofOfPaymentSentenceIsExactInBothLanguages() {
    assertThat(PayslipText.notProofOfPayment(PayslipLocale.EN)).isEqualTo("Not proof of payment");
    assertThat(PayslipText.notProofOfPayment(PayslipLocale.FR))
        .isEqualTo("Ne constitue pas une preuve de paiement");
  }

  @Test
  void theRulePackStatementNamesTheCodeAndVersionInBothLanguages() {
    assertThat(PayslipText.rulePackStatement(PayslipLocale.EN, "SYNTHETIC-TEST-DRC", 3))
        .isEqualTo("Statutory calculation based on rule pack SYNTHETIC-TEST-DRC v3");
    assertThat(PayslipText.rulePackStatement(PayslipLocale.FR, "SYNTHETIC-TEST-DRC", 3))
        .isEqualTo("Calcul statutaire fondé sur le jeu de règles SYNTHETIC-TEST-DRC v3");
    // The version is a plain integer: no grouping separator whatever the JVM locale.
    assertThat(PayslipText.rulePackStatement(PayslipLocale.EN, "P", 1234)).endsWith("P v1234");
  }

  @Test
  void theProductLanguageOfTheIssueIsUsedForStatusAndTitle() {
    assertThat(PayslipText.approvedPayroll(PayslipLocale.EN)).isEqualTo("Approved payroll");
    assertThat(PayslipText.approvedPayroll(PayslipLocale.FR)).isEqualTo("Paie approuvée");
    assertThat(PayslipText.title(PayslipLocale.EN)).isEqualTo("Payslip");
    assertThat(PayslipText.title(PayslipLocale.FR)).isEqualTo("Bulletin de paie");
    assertThat(PayslipText.voidedBanner(PayslipLocale.EN)).isEqualTo("VOIDED");
    assertThat(PayslipText.voidedBanner(PayslipLocale.FR)).isEqualTo("ANNULÉ");
    assertThat(PayslipText.statusLabel(PayslipLocale.EN, false)).isEqualTo("Approved payroll");
    assertThat(PayslipText.statusLabel(PayslipLocale.EN, true)).isEqualTo("VOIDED");
    assertThat(PayslipText.statusLabel(PayslipLocale.FR, true)).isEqualTo("ANNULÉ");
  }

  @Test
  void theDocumentTitleIsGenericAndCarriesNoPersonOrPeriod() {
    assertThat(PayslipText.documentTitle(PayslipLocale.EN, false)).isEqualTo("Payslip");
    assertThat(PayslipText.documentTitle(PayslipLocale.EN, true)).isEqualTo("Payslip - VOIDED");
    assertThat(PayslipText.documentTitle(PayslipLocale.FR, false)).isEqualTo("Bulletin de paie");
    assertThat(PayslipText.documentTitle(PayslipLocale.FR, true))
        .isEqualTo("Bulletin de paie - ANNULÉ");
  }

  @Test
  void theEmployerNoteStatesThatEmployerContributionsDoNotReduceTheNet() {
    assertThat(PayslipText.employerContributionsNote(PayslipLocale.EN))
        .contains("shown separately")
        .contains("do not reduce");
    assertThat(PayslipText.employerContributionsNote(PayslipLocale.FR))
        .contains("indiquées séparément")
        .contains("ne réduisent pas");
  }

  @Test
  void noForbiddenPhraseAppearsInEitherLanguage() {
    for (String text : everything(PayslipLocale.EN)) {
      for (Pattern forbidden : FORBIDDEN_EN) {
        assertThat(forbidden.matcher(text).find()).as(text + " ~ " + forbidden).isFalse();
      }
    }
    for (String text : everything(PayslipLocale.FR)) {
      for (Pattern forbidden : FORBIDDEN_FR) {
        assertThat(forbidden.matcher(text).find()).as(text + " ~ " + forbidden).isFalse();
      }
    }
  }

  @Test
  void theForbiddenPhraseScanFlagsTheForbiddenWordsAndSparesTheRequiredWording() {
    for (String bad :
        List.of(
            "Payroll paid",
            "PAID",
            "The payment completed",
            "Payment  completed",
            "filed",
            "Tax compliant",
            "tax-compliant",
            "Government approved")) {
      assertThat(FORBIDDEN_EN.stream().anyMatch(p -> p.matcher(bad).find())).as(bad).isTrue();
    }
    for (String bad :
        List.of(
            "Salaire payé",
            "Paie payée",
            "paiement effectué",
            "Déclaré",
            "conforme fiscalement",
            "approuvé par le gouvernement",
            "approuvé par l'État",
            "approuvé par l’État")) {
      assertThat(FORBIDDEN_FR.stream().anyMatch(p -> p.matcher(bad).find())).as(bad).isTrue();
    }
    for (String allowed :
        List.of("Approved payroll", "Not proof of payment", "Approved net", "Payslip", "Payroll")) {
      assertThat(FORBIDDEN_EN.stream().anyMatch(p -> p.matcher(allowed).find()))
          .as(allowed)
          .isFalse();
    }
    for (String allowed :
        List.of(
            "Paie approuvée",
            "Ne constitue pas une preuve de paiement",
            "Net approuvé",
            "Bulletin de paie",
            "Calcul statutaire fondé")) {
      assertThat(FORBIDDEN_FR.stream().anyMatch(p -> p.matcher(allowed).find()))
          .as(allowed)
          .isFalse();
    }
  }

  @Test
  void aNegativeFormOfPaymentIsTheOnlyPaymentWordingTheDocumentUses() {
    for (PayslipLocale locale : PayslipLocale.values()) {
      for (String text : everything(locale)) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("payment") || lower.contains("paiement")) {
          assertThat(text).as(text).isEqualTo(PayslipText.notProofOfPayment(locale));
        }
      }
    }
  }
}
