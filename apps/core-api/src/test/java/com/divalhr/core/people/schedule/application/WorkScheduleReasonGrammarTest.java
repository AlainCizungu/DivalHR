package com.divalhr.core.people.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.people.leave.application.LeaveReasonGrammar;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MVP-050B: the schedule package's copy of the decision-reason grammar decides exactly as the leave
 * grammar (and so as {@code people.leave_reason_valid}, which both mirror) for every code point, in
 * the middle and at either end of a reason, and normalizes identically.
 */
class WorkScheduleReasonGrammarTest {

  @Test
  void everyCodePointIsDecidedAsTheLeaveGrammarDecidesIt() {
    List<Integer> differences = new ArrayList<>();
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      String c = new String(Character.toChars(cp));
      for (String text : new String[] {"a" + c + "b", c + "ab", "ab" + c}) {
        if (LeaveReasonGrammar.isValid(text) != WorkScheduleReasonGrammar.isValid(text)) {
          differences.add(cp);
          break;
        }
      }
      String spaced = "  " + c + "x　 ";
      if (!LeaveReasonGrammar.normalize(spaced)
          .equals(WorkScheduleReasonGrammar.normalize(spaced))) {
        differences.add(cp);
      }
    }
    assertThat(differences).isEmpty();
  }

  @Test
  void lengthAndNormalizationMatch() {
    for (String text :
        new String[] {
          "",
          "a",
          "ab",
          "a".repeat(500),
          "a".repeat(501),
          "😀".repeat(500),
          "😀".repeat(501),
          "Café fermé",
          "Café fermé",
          "\uD800x"
        }) {
      assertThat(WorkScheduleReasonGrammar.isValid(text))
          .as(text.length() + " chars")
          .isEqualTo(LeaveReasonGrammar.isValid(text));
      assertThat(WorkScheduleReasonGrammar.normalize(text))
          .isEqualTo(LeaveReasonGrammar.normalize(text));
    }
    assertThat(WorkScheduleReasonGrammar.MIN).isEqualTo(LeaveReasonGrammar.MIN);
    assertThat(WorkScheduleReasonGrammar.MAX).isEqualTo(LeaveReasonGrammar.MAX);
  }
}
