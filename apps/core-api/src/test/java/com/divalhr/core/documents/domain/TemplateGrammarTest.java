package com.divalhr.core.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.documents.domain.ContractRenderer.Rendering;
import com.divalhr.core.documents.domain.TemplateGrammar.BlockType;
import com.divalhr.core.documents.domain.TemplateGrammar.Parsed;
import com.divalhr.core.documents.domain.TemplateProblem.Reason;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Grammar v1, its limits, and renderer v1 (parse before substitution, values as literal text). */
class TemplateGrammarTest {

  private static final String BODY =
      """
      # Contrat de travail

      Entre {{organization.name}} et {{employee.fullName}},
      matricule {{employee.number}}.

      ## Article 1 : Durée
      - Début : {{contract.startDate}}
      - Type : {{contract.type}}

      Fait le {{issue.date}}.
      """;

  @Test
  void parsesHeadingsParagraphsAndListsWithPlaceholders() {
    Parsed parsed = TemplateGrammar.parse("Contrat", BODY);
    assertThat(parsed.problems()).isEmpty();
    assertThat(parsed.blocks())
        .extracting(TemplateGrammar.Block::type)
        .containsExactly(
            BlockType.H1, BlockType.P, BlockType.H2, BlockType.LI, BlockType.LI, BlockType.P);
    assertThat(parsed.placeholders())
        .containsExactlyInAnyOrder(
            ContractPlaceholder.ORGANIZATION_NAME,
            ContractPlaceholder.EMPLOYEE_FULL_NAME,
            ContractPlaceholder.EMPLOYEE_NUMBER,
            ContractPlaceholder.CONTRACT_START_DATE,
            ContractPlaceholder.CONTRACT_TYPE,
            ContractPlaceholder.ISSUE_DATE);
  }

  @Test
  void rendersValuesAsLiteralTextAfterParsing() {
    Parsed parsed = TemplateGrammar.parse("Contrat", BODY);
    Map<ContractPlaceholder, String> values = new EnumMap<>(ContractPlaceholder.class);
    values.put(ContractPlaceholder.ORGANIZATION_NAME, "Société\n# Kinshasa");
    values.put(ContractPlaceholder.EMPLOYEE_FULL_NAME, "Élodie N’Kanza");
    values.put(ContractPlaceholder.EMPLOYEE_NUMBER, "<b>E-001</b>");
    values.put(
        ContractPlaceholder.CONTRACT_START_DATE,
        ContractValueFormats.date("fr", LocalDate.of(2026, 11, 1)));
    values.put(ContractPlaceholder.CONTRACT_TYPE, ContractValueFormats.type("fr", "PERMANENT"));
    values.put(
        ContractPlaceholder.ISSUE_DATE, ContractValueFormats.date("fr", LocalDate.of(2026, 10, 4)));
    Rendering rendering = ContractRenderer.render("fr", parsed, values);
    assertThat(rendering.missing()).isEmpty();
    assertThat(rendering.snapshot().blocks())
        .extracting(RenderedSnapshot.Block::text)
        .containsExactly(
            "Contrat de travail",
            // A value with a line break and a heading marker stays inline, literal text.
            "Entre Société # Kinshasa et Élodie N’Kanza, matricule <b>E-001</b>.",
            "Article 1 : Durée",
            "Début : 1er novembre 2026",
            "Type : Durée indéterminée",
            "Fait le 4 octobre 2026.");
  }

  @Test
  void aMissingValueBlocksRendering() {
    Parsed parsed =
        TemplateGrammar.parse("Contrat", "Site : {{site.name}}, fin {{contract.endDate}}");
    Rendering rendering =
        ContractRenderer.render("en", parsed, Map.of(ContractPlaceholder.SITE_NAME, "Lubumbashi"));
    assertThat(rendering.snapshot()).isNull();
    assertThat(rendering.missing()).containsExactly(ContractPlaceholder.CONTRACT_END_DATE);
  }

  @Test
  void datesAndTypesUseFixedTables() {
    assertThat(ContractValueFormats.date("fr", LocalDate.of(2026, 8, 15)))
        .isEqualTo("15 août 2026");
    assertThat(ContractValueFormats.date("en", LocalDate.of(2026, 3, 1)))
        .isEqualTo("March 1, 2026");
    assertThat(ContractValueFormats.type("en", "FIXED_TERM")).isEqualTo("Fixed term");
  }

  @Test
  void refusesWithAClosedReasonAndALineNumberOnly() {
    assertThat(problems("", "Texte")).containsExactly(new TemplateProblem(Reason.EMPTY, 0));
    assertThat(problems("T", " \n ")).containsExactly(new TemplateProblem(Reason.EMPTY, 1));
    assertThat(problems("x".repeat(161), "Texte"))
        .contains(new TemplateProblem(Reason.TOO_LONG, 0));
    assertThat(problems("T", "x".repeat(40_001)))
        .containsExactly(new TemplateProblem(Reason.TOO_LONG, 1));
    assertThat(problems("Titre {{employee.number}}", "Texte"))
        .containsExactly(new TemplateProblem(Reason.BRACES, 0));
    assertThat(problems("T", "Un\nDeux {{employee.salary}}"))
        .containsExactly(new TemplateProblem(Reason.UNKNOWN_PLACEHOLDER, 2));
    assertThat(problems("T", "{{ employee.number }}"))
        .containsExactly(new TemplateProblem(Reason.MALFORMED_PLACEHOLDER, 1));
    assertThat(problems("T", "{{employee.number}"))
        .containsExactly(new TemplateProblem(Reason.BRACES, 1));
    assertThat(problems("T", "Texte }} fin"))
        .containsExactly(new TemplateProblem(Reason.BRACES, 1));
    assertThat(problems("T", "Tab\there"))
        .containsExactly(new TemplateProblem(Reason.CONTROL_CHARACTER, 1));
    assertThat(problems("T", "Inverse ‮txet"))
        .containsExactly(new TemplateProblem(Reason.UNSAFE_CHARACTER, 1));
    assertThat(problems("T", "Privé "))
        .containsExactly(new TemplateProblem(Reason.UNSAFE_CHARACTER, 1));
    assertThat(problems("T", "#\n## \n- "))
        .containsExactly(
            new TemplateProblem(Reason.HEADING_EMPTY, 1),
            new TemplateProblem(Reason.HEADING_EMPTY, 2),
            new TemplateProblem(Reason.EMPTY, 3));
    assertThat(problems("T", "Voir https://x\n<p>x</p>"))
        .containsExactly(
            new TemplateProblem(Reason.URI_SCHEME, 1), new TemplateProblem(Reason.HTML_MARKUP, 2));
    assertThat(problems("www.exemple.cd", "Texte"))
        .containsExactly(new TemplateProblem(Reason.WEB_ADDRESS, 0));
    assertThat(problems("T", "{{employee.number}} ".repeat(201)))
        .containsExactly(new TemplateProblem(Reason.TOO_MANY_PLACEHOLDERS, 1));
  }

  @Test
  void normalizesToNfcWithLineFeeds() {
    String decomposed = "Élodie\r\nN’Kanza\rFin";
    assertThat(TemplateGrammar.normalize(decomposed)).isEqualTo("Élodie\nN’Kanza\nFin");
    // The same text however it was typed: one digest.
    assertThat(ContractDigests.templateBody("fr", "T", TemplateGrammar.normalize(decomposed)))
        .isEqualTo(ContractDigests.templateBody("fr", "T", "Élodie\nN’Kanza\nFin"));
  }

  @Test
  void anyAcceptedTextIsDataNeverMarkup() {
    // Comparisons, ampersands and percents are accepted text.
    Parsed parsed = TemplateGrammar.parse("T", "Si x < 6 mois & 10 % : voir l’article 2.");
    assertThat(parsed.problems()).isEmpty();
    assertThat(parsed.blocks()).hasSize(1);
  }

  private static List<TemplateProblem> problems(String title, String body) {
    return TemplateGrammar.parse(title, body).problems();
  }
}
