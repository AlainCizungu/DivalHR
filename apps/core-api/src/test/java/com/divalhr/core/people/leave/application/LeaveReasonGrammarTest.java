package com.divalhr.core.people.leave.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The decision-reason grammar, version 1 (R90-2), in the application. The same values are checked
 * against PostgreSQL in {@code LeaveDecisionMigrationIntegrationTest}, which also compares both
 * layers on every code point.
 */
class LeaveReasonGrammarTest {

  @Test
  void normalizationIsNfcThenTheTrimSetAtBothEnds() {
    assertThat(LeaveReasonGrammar.normalize("\u3000\u00A0 Cafe\u0301 \t\u2028")).isEqualTo("Café");
    // Inner spaces of the trim set stay; a format character is not trimmed (it is refused).
    assertThat(LeaveReasonGrammar.normalize("a\u00A0b")).isEqualTo("a\u00A0b");
    assertThat(LeaveReasonGrammar.normalize("\u200Bab")).isEqualTo("\u200Bab");
  }

  @Test
  void representativeValues() {
    for (String accepted :
        new String[] {
          "Accordé.",
          "ok",
          "Bon congé 😀",
          "a\u00A0b",
          "😀".repeat(500),
          "ab\u0378", // unassigned in Unicode 15.0: not refused (version-independent grammar)
        }) {
      assertThat(LeaveReasonGrammar.isValid(accepted)).as(accepted).isTrue();
    }
    for (String refused :
        new String[] {
          "x",
          "😀".repeat(501),
          " ab",
          "ab\u00A0",
          "e\u0301x", // not NFC
          "Ac\u200Bcord", // zero-width space (Cf)
          "a\u200Db", // zero-width joiner (Cf)
          "\u202Eabc", // right-to-left override (Cf)
          "ab\u00AD", // soft hyphen (Cf)
          "ab\uDB40\uDC20", // tag space U+E0020 (Cf)
          "ab\u0007",
          "a\nb",
          "a\u2028b",
          "ab\uE000", // private use
          "ab\uDB80\uDC00", // private use U+F0000
          "ab﷐", // noncharacter
          "ab￿", // noncharacter
          "ab\uD800", // unpaired surrogate
        }) {
      assertThat(LeaveReasonGrammar.isValid(refused)).as(refused).isFalse();
    }
  }
}
