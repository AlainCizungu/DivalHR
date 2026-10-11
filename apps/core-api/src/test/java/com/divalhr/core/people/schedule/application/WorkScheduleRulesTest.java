package com.divalhr.core.people.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.people.schedule.domain.EmployeeFacts;
import com.divalhr.core.people.schedule.domain.SlotDraft;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-050A pure rules: the label grammar (the same cases as V25's {@code
 * people.work_schedule_label_valid}), local-time conversion that never guesses, the last date a
 * slot occupies and employment coverage across adjacent employments.
 */
class WorkScheduleRulesTest {

  @Test
  void labelsFollowTheClosedGrammarAfterNormalization() {
    assertThat(WorkScheduleLabelGrammar.normalize("  Agent d'accueil   (nuit) "))
        .isEqualTo("Agent d'accueil (nuit)");
    for (String good :
        List.of("Caissière n° 2", "Équipe A.", "Cashier - desk", "Night desk (lead)", "B2")) {
      assertThat(WorkScheduleLabelGrammar.normalize(good)).as(good).isEqualTo(good);
    }
    // Decomposed accents are composed (NFC).
    assertThat(WorkScheduleLabelGrammar.normalize("Équipe")).isEqualTo("Équipe");
    for (Object bad :
        new Object[] {
          "x",
          "<b>Lead</b>",
          "a@b.cd",
          "www.evil.com",
          "http://x",
          "Go: now",
          "Tab\tlabel",
          "x".repeat(61),
          "Line\nbreak",
          "Zero​width",
          "日本",
          "Desk|1",
          42,
          null
        }) {
      assertThat(WorkScheduleLabelGrammar.normalize(bad)).as(String.valueOf(bad)).isNull();
    }
  }

  @Test
  void localTimesAreNeverGuessed() {
    ZoneId paris = ZoneId.of("Europe/Paris");
    assertThat(WorkSchedulePlanner.instant(LocalDateTime.of(2027, 3, 28, 2, 30), paris)).isNull();
    assertThat(WorkSchedulePlanner.instant(LocalDateTime.of(2027, 10, 31, 2, 30), paris)).isNull();
    assertThat(WorkSchedulePlanner.instant(LocalDateTime.of(2027, 10, 31, 3, 0), paris))
        .hasToString("2027-10-31T02:00:00Z");
    assertThat(
            WorkSchedulePlanner.instant(
                LocalDateTime.of(2026, 11, 2, 8, 0), ZoneId.of("Africa/Kinshasa")))
        .hasToString("2026-11-02T07:00:00Z");
  }

  @Test
  void aSlotEndingAtMidnightOccupiesOnlyItsStartDate() {
    LocalDate d = LocalDate.of(2026, 11, 2);
    assertThat(
            WorkSchedulePlanner.lastOccupied(draft(d.atTime(16, 0), d.plusDays(1).atStartOfDay())))
        .isEqualTo(d);
    assertThat(WorkSchedulePlanner.lastOccupied(draft(d.atTime(22, 0), d.plusDays(1).atTime(6, 0))))
        .isEqualTo(d.plusDays(1));
  }

  @Test
  void adjacentEmploymentsCoverTheDates() {
    LocalDate d = LocalDate.of(2026, 11, 2);
    EmployeeFacts.Employment first =
        new EmployeeFacts.Employment(UUID.randomUUID(), d.minusDays(10), d, 1);
    EmployeeFacts.Employment next =
        new EmployeeFacts.Employment(UUID.randomUUID(), d.plusDays(1), null, 0);
    EmployeeFacts.Employment gap =
        new EmployeeFacts.Employment(UUID.randomUUID(), d.plusDays(2), null, 0);
    assertThat(WorkSchedulePlanner.employed(List.of(next, first), d, d.plusDays(1))).isTrue();
    assertThat(WorkSchedulePlanner.employed(List.of(first, gap), d, d.plusDays(1))).isFalse();
    assertThat(WorkSchedulePlanner.employed(List.of(first), d, d)).isTrue();
    assertThat(WorkSchedulePlanner.employed(List.of(), d, d)).isFalse();
  }

  private static SlotDraft draft(LocalDateTime start, LocalDateTime end) {
    return new SlotDraft("a", 1, start, end, "Cashier", "Caissière", null);
  }
}
