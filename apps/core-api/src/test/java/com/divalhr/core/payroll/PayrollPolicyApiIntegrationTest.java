package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PayrollRunWorld.policyCommand;
import static com.divalhr.core.payroll.PayrollRunWorld.policyCommitting;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-094A (Issue #177) payroll policy timeline against real PostgreSQL through the public API: a
 * policy is previewed and recorded append-only, the timeline derives each end date, a repeated rule
 * or a used date is no change, stale versions and previews are stable conflicts, and dates outside
 * the allowed window are refused. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.payroll-policy-preview=600",
      "divalhr.request-limits.subject.payroll-policy-write=600",
      "divalhr.request-limits.subject.payroll-policy-read=600"
    })
class PayrollPolicyApiIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollRunWorld w;
  private LocalDate first;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollRunWorld(mvc, jdbc, lookups, clock);
    first = w.today.minusMonths(3).withDayOfMonth(1);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private JsonNode read() throws Exception {
    return w.c.expect(PayrollRunWorld.get(w.admin, w.policyPath()), 200);
  }

  @Test
  void anEntityWithoutAPolicyHasAnEmptyTimeline() throws Exception {
    JsonNode timeline = read();
    assertThat(timeline.get("version").asInt()).isZero();
    assertThat(timeline.get("current").isNull()).isTrue();
    assertThat(timeline.get("policies")).isEmpty();
    assertThat(timeline.get("legalEntityId").asText()).isEqualTo(w.entity.toString());
  }

  @Test
  void policiesAreRecordedAppendOnlyAndTheTimelineDerivesTheEndDates() throws Exception {
    Map<String, Object> command = policyCommand(first, "LAST_DAY", null, "CALENDAR_DAYS");
    JsonNode preview = w.c.expect(w.policyPreviewRequest(command), 200);
    assertThat(preview.get("blockers")).isEmpty();
    assertThat(preview.get("version").asInt()).isZero();
    assertThat(preview.get("before")).isEmpty();
    assertThat(preview.get("after")).hasSize(1);
    assertThat(w.evidence().get(2)).isZero();

    JsonNode receipt =
        w.c.expect(
            w.policyCommitRequest(Organizations.newKey(), policyCommitting(command, preview)),
            201);
    assertThat(receipt.get("sequence").asInt()).isEqualTo(1);
    assertThat(receipt.get("version").asInt()).isEqualTo(1);
    assertThat(receipt.get("effectiveFrom").asText()).isEqualTo(first.toString());

    LocalDate later = w.today.plusDays(30);
    w.policy(later, "DAY", 25, "FIXED_30_DAYS");
    JsonNode timeline = read();
    assertThat(timeline.get("version").asInt()).isEqualTo(2);
    assertThat(timeline.get("policies")).hasSize(2);
    JsonNode newest = timeline.get("policies").get(0);
    assertThat(newest.get("sequence").asInt()).isEqualTo(2);
    assertThat(newest.get("effectiveTo").isNull()).isTrue();
    assertThat(newest.get("payDay").get("kind").asText()).isEqualTo("DAY");
    assertThat(newest.get("payDay").get("day").asInt()).isEqualTo(25);
    assertThat(newest.get("prorationMethod").asText()).isEqualTo("FIXED_30_DAYS");
    JsonNode oldest = timeline.get("policies").get(1);
    assertThat(oldest.get("effectiveTo").asText()).isEqualTo(later.minusDays(1).toString());
    assertThat(timeline.get("current").get("sequence").asInt()).isEqualTo(1);
    assertThat(w.evidence().get(2)).isEqualTo(2);
  }

  @Test
  void aRepeatedRuleOrAUsedDateIsNoChangeAndCannotBeRecorded() throws Exception {
    w.policy(first, "LAST_DAY", null, "CALENDAR_DAYS");
    // The same rule at a later date, and a different rule on a used date.
    for (Map<String, Object> command :
        List.of(
            policyCommand(first.plusMonths(1), "LAST_DAY", null, "CALENDAR_DAYS"),
            policyCommand(first, "LAST_DAY", null, "FIXED_30_DAYS"))) {
      JsonNode preview = w.c.expect(w.policyPreviewRequest(command), 200);
      assertThat(preview.get("blockers").get(0).asText()).isEqualTo("NO_CHANGE");
      List<Integer> before = w.evidence();
      w.c.problem(
          w.policyCommitRequest(Organizations.newKey(), policyCommitting(command, preview)),
          409,
          "PAYROLL_POLICY_CONFLICT");
      assertThat(w.evidence()).isEqualTo(before);
    }
  }

  @Test
  void aStaleVersionIsAPolicyConflictAndAStalePreviewIsStale() throws Exception {
    Map<String, Object> command = policyCommand(first, "LAST_DAY", null, "CALENDAR_DAYS");
    JsonNode preview = w.c.expect(w.policyPreviewRequest(command), 200);
    w.policy(first.plusMonths(1), "DAY", 5, "NONE");
    w.c.problem(
        w.policyCommitRequest(Organizations.newKey(), policyCommitting(command, preview)),
        409,
        "PAYROLL_POLICY_CONFLICT");

    Map<String, Object> next = policyCommand(first.plusMonths(2), "DAY", 10, "NONE");
    JsonNode fresh = w.c.expect(w.policyPreviewRequest(next), 200);
    Map<String, Object> forged = new LinkedHashMap<>(policyCommitting(next, fresh));
    forged.put("previewDigest", "b".repeat(64));
    w.c.problem(
        w.policyCommitRequest(Organizations.newKey(), forged), 409, "PAYROLL_RUN_PREVIEW_STALE");
    clock.set(Instant.parse(fresh.get("expiresAt").asText()));
    w.c.problem(
        w.policyCommitRequest(Organizations.newKey(), policyCommitting(next, fresh)),
        409,
        "PAYROLL_RUN_PREVIEW_STALE");
    assertThat(read().get("version").asInt()).isEqualTo(1);
  }

  @Test
  void datesOutsideTheWindowAreRefused() throws Exception {
    LocalDate earliest = YearMonth.from(w.today).minusMonths(24).atDay(1);
    LocalDate latest = w.today.plusDays(366);
    for (LocalDate invalid : List.of(earliest.minusDays(1), latest.plusDays(1))) {
      w.c.problem(
          w.policyPreviewRequest(policyCommand(invalid, "LAST_DAY", null, "CALENDAR_DAYS")),
          422,
          "PAYROLL_PERIOD_INVALID");
    }
    w.c.expect(w.policyPreviewRequest(policyCommand(earliest, "LAST_DAY", null, "NONE")), 200);
    w.c.expect(w.policyPreviewRequest(policyCommand(latest, "LAST_DAY", null, "NONE")), 200);
  }

  @Test
  void invalidShapesAreValidationFailures() throws Exception {
    for (Map<String, Object> bad :
        List.of(
            policyCommand(first, "DAY", 29, "NONE"),
            policyCommand(first, "DAY", 0, "NONE"),
            policyCommand(first, "LAST_DAY", 28, "NONE"),
            policyCommand(first, "WEEKLY", null, "NONE"),
            policyCommand(first, "DAY", null, "NONE"),
            policyCommand(first, "LAST_DAY", null, "HOURLY"))) {
      w.c.problem(w.policyPreviewRequest(bad), 400, "VALIDATION_FAILED");
    }
    Map<String, Object> unknown = policyCommand(first, "LAST_DAY", null, "NONE");
    unknown.put("amount", "1");
    w.c.problem(w.policyPreviewRequest(unknown), 400, "VALIDATION_FAILED");
    Map<String, Object> text = policyCommand(first, "LAST_DAY", null, "NONE");
    text.put("effectiveFrom", "not-a-date");
    w.c.problem(w.policyPreviewRequest(text), 400, "VALIDATION_FAILED");
  }

  @Test
  void aReplayIsIdempotentAndADifferentBodyIsAKeyReuse() throws Exception {
    Map<String, Object> command = policyCommand(first, "LAST_DAY", null, "CALENDAR_DAYS");
    JsonNode preview = w.c.expect(w.policyPreviewRequest(command), 200);
    Map<String, Object> body = policyCommitting(command, preview);
    String key = Organizations.newKey();
    MockHttpServletResponse created = w.c.call(w.policyCommitRequest(key, body));
    assertThat(created.getStatus()).isEqualTo(201);
    MockHttpServletResponse replay = w.c.call(w.policyCommitRequest(key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(created.getContentAsString());
    assertThat(w.evidence().get(2)).isEqualTo(1);
    Map<String, Object> different = new LinkedHashMap<>(body);
    different.put("prorationMethod", "NONE");
    w.c.problem(w.policyCommitRequest(key, different), 409, "IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void anUnknownOrMalformedLegalEntityIsOneSafeNotFound() throws Exception {
    for (String entity : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
      w.c.problem(
          PayrollRunWorld.get(w.admin, PayrollRunWorld.policyPath(entity)),
          404,
          "LEGAL_ENTITY_NOT_FOUND");
    }
  }
}
