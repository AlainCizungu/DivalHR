package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.operations.application.TaskText;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.FieldErrors;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The restricted plain text of tasks (MVP-080, D80-2): accepted, refused and never echoed. */
class TaskTextTest {

  private static List<String> problems(Object raw, int min, int max, boolean required) {
    FieldErrors errors = new FieldErrors();
    TaskText.parse(errors, "title", raw, min, max, required);
    try {
      errors.throwIfAny();
      return List.of();
    } catch (ApiException invalid) {
      @SuppressWarnings("unchecked")
      List<Map<String, String>> fields = (List<Map<String, String>>) invalid.params().get("fields");
      return fields.stream().map(f -> f.get("field") + ":" + f.get("constraint")).toList();
    }
  }

  @Test
  void ordinaryBilingualTextIsAccepted() {
    for (String text :
        List.of(
            "Prepare the delivery",
            "Préparer la livraison — très tôt",
            "Équipe d'été : vérifier l'inventaire (étage 2)",
            "Dupont & Fils : 10 % de remise",
            "Réunion à 14:30, salle B/C et/ou D",
            "Note: bring the badge",
            "Rappel : 2026/27")) {
      assertThat(problems(text, 1, 120, true)).as(text).isEmpty();
    }
  }

  @Test
  void textIsNormalizedAndTrimmedBeforeItIsCounted() {
    FieldErrors errors = new FieldErrors();
    assertThat(TaskText.parse(errors, "title", "  e\u0301cole\u00A0 ", 1, 120, true))
        .isEqualTo("école");
    assertThat(errors.isEmpty()).isTrue();
  }

  @Test
  void aSupplementaryCharacterCountsOnce() {
    assertThat(problems("👍".repeat(120), 1, 120, true)).isEmpty();
    assertThat(problems("👍".repeat(121), 1, 120, true)).containsExactly("title:LENGTH");
  }

  @Test
  void lengthsFollowTheField() {
    assertThat(problems("x".repeat(120), 1, 120, true)).isEmpty();
    assertThat(problems("x".repeat(121), 1, 120, true)).containsExactly("title:LENGTH");
    assertThat(problems("x".repeat(9), 10, 500, true)).containsExactly("title:LENGTH");
    assertThat(problems("x".repeat(10), 10, 500, true)).isEmpty();
    assertThat(problems("x".repeat(501), 10, 500, true)).containsExactly("title:LENGTH");
  }

  @Test
  void absentIsRequiredOnlyWhenTheFieldIsRequired() {
    assertThat(problems(null, 1, 120, true)).containsExactly("title:REQUIRED");
    assertThat(problems("   ", 1, 120, true)).containsExactly("title:REQUIRED");
    assertThat(problems(null, 1, 1000, false)).isEmpty();
    assertThat(problems("   ", 1, 1000, false)).isEmpty();
    assertThat(problems(12, 1, 120, true)).containsExactly("title:FORMAT");
    assertThat(problems(List.of("a"), 1, 120, false)).containsExactly("title:FORMAT");
  }

  @Test
  void markupLinksAndEncodedContentAreRefused() {
    for (String text :
        List.of(
            "<b>bold</b>",
            "a < b",
            "a > b",
            "back\\slash",
            "https://example.org",
            "HTTP://EXAMPLE.ORG",
            "h t t p s : //x",
            "see www.example.org",
            "example.com/path",
            "a://b",
            "mailto:someone",
            "javascript:alert(1)",
            "data:text/html",
            "ws:abc",
            "%41",
            "&#65;",
            "&amp;",
            "ＨＴＴＰＳ：//x",
            "ｗｗｗ．example．org",
            "＜b＞",
            "c:/windows",
            "[x](y)",
            "onclick=1")) {
      assertThat(problems(text, 1, 120, true)).as(text).containsExactly("title:FORMAT");
    }
  }

  @Test
  void controlFormatAndPrivateUseCharactersAreRefused() {
    for (String text :
        List.of(
            "tab\there",
            "line\nbreak",
            "return\rhere",
            "nul\u0000x",
            "bell\u0007",
            "next\u0085line",
            "soft\u00ADhyphen",
            "zero\u200Bwidth",
            "bidi\u202Etext",
            "bom\uFEFFtext",
            "separator\u2028here",
            "private\uE000use",
            "lone\uD800surrogate",
            "non\uFFFEchar")) {
      assertThat(problems(text, 1, 120, true)).as(escaped(text)).containsExactly("title:FORMAT");
    }
  }

  @Test
  void refusedValuesAreNeverEchoedInTheProblem() {
    FieldErrors errors = new FieldErrors();
    TaskText.parse(errors, "title", "https://secret.example/token", 1, 120, true);
    try {
      errors.throwIfAny();
    } catch (ApiException invalid) {
      assertThat(invalid.params().toString()).doesNotContain("secret").doesNotContain("token");
      assertThat(invalid.getMessage() == null ? "" : invalid.getMessage()).doesNotContain("secret");
    }
  }

  @Test
  void theGrammarOnAStoredValueMatchesParsing() {
    assertThat(TaskText.isValid("Prepare the delivery", 1, 120)).isTrue();
    assertThat(TaskText.isValid("", 1, 120)).isFalse();
    assertThat(TaskText.isValid(null, 1, 120)).isFalse();
    assertThat(TaskText.isValid(" padded", 1, 120)).isFalse();
    assertThat(TaskText.isValid("e\u0301cole", 1, 120)).isFalse();
    assertThat(TaskText.isValid("a<b", 1, 120)).isFalse();
  }

  private static String escaped(String value) {
    StringBuilder text = new StringBuilder();
    value
        .codePoints()
        .forEach(
            cp -> text.append(cp < 128 ? Character.toString(cp) : "\\u" + Integer.toHexString(cp)));
    return text.toString();
  }
}
