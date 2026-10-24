package com.divalhr.core.integrations.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** MVP-111 (D111-7): Meta template names follow one closed grammar. */
class TemplateNamesTest {

  @Test
  void acceptsLowerCaseLettersDigitsAndUnderscores() {
    for (String ok : new String[] {"a", "leave_decision", "divalhr_leave_decision_fr_1", "a1_b2"}) {
      assertThat(TemplateNames.valid(ok)).as(ok).isTrue();
    }
    assertThat(TemplateNames.valid("a".repeat(512))).isTrue();
  }

  @Test
  void refusesEverythingElse() {
    for (Object bad :
        new Object[] {
          null,
          7,
          "",
          "1abc",
          "_abc",
          "Abc",
          "a-b",
          "a b",
          "a.b",
          "a/b",
          "a\n",
          "é",
          "a?name=x",
          "a".repeat(513)
        }) {
      assertThat(TemplateNames.valid(bad)).as(String.valueOf(bad)).isFalse();
    }
  }
}
