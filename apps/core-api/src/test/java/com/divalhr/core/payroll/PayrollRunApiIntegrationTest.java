package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PayrollRunWorld.runCommand;
import static com.divalhr.core.payroll.PayrollRunWorld.runCommitting;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
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
 * MVP-094A (Issue #177) against real PostgreSQL through the public API only: a policy is recorded,
 * a month is previewed and created as a DRAFT with the contractual gross of every employee,
 * blockers refuse the creation, every kind of input drift makes a preview stale, replays are
 * idempotent and a new version supersedes the draft. Synthetic data only; the clock is frozen on
 * the 15th of the current month so the previous month is always a closed, runnable month.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.payroll-run-preview=600",
      "divalhr.request-limits.subject.payroll-run-create=600",
      "divalhr.request-limits.subject.payroll-run-read=600",
      "divalhr.request-limits.subject.payroll-policy-preview=600",
      "divalhr.request-limits.subject.payroll-policy-write=600",
      "divalhr.request-limits.subject.payroll-policy-read=600"
    })
class PayrollRunApiIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollRunWorld w;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollRunWorld(mvc, jdbc, lookups, clock);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private LocalDate beforeMonth() {
    return w.month.atDay(1).minusDays(5);
  }

  /** The reference rounding: floor((2n + d) / (2d)) on integers. */
  private static long half(long monthly, long days, long denominator) {
    BigInteger n = BigInteger.valueOf(monthly).multiply(BigInteger.valueOf(days));
    BigInteger d = BigInteger.valueOf(denominator);
    return n.multiply(BigInteger.TWO).add(d).divide(d.multiply(BigInteger.TWO)).longValueExact();
  }

  private static String usd(long minor) {
    return (minor / 100) + "." + String.format("%02d", minor % 100);
  }

  private static List<String> texts(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(v -> values.add(v.asText()));
    return values;
  }

  // ------------------------------------------------------------------------------------------
  // Contractual gross
  // ------------------------------------------------------------------------------------------

  @Test
  void aFullMonthIsTheExactAmountInUsdAndCdfAndCreatesADraft() throws Exception {
    w.policy("CALENDAR_DAYS");
    UUID ana = w.paid("Mukendi", beforeMonth(), "USD", "1250.00");

    JsonNode preview = w.runPreview("USD");
    assertThat(preview.get("period").asText()).isEqualTo(w.month.toString());
    assertThat(preview.get("expectedVersion").asInt()).isZero();
    assertThat(preview.get("existingRun").isNull()).isTrue();
    assertThat(preview.get("employeeCount").asInt()).isEqualTo(1);
    assertThat(preview.get("blockedEmployeeCount").asInt()).isZero();
    assertThat(preview.get("total").get("amount").asText()).isEqualTo("1250.00");
    JsonNode line = w.line(preview, ana);
    assertThat(line.get("fullMonth").asBoolean()).isTrue();
    assertThat(line.get("denominator").isNull()).isTrue();
    assertThat(line.get("eligibleDays").asInt()).isEqualTo(w.month.lengthOfMonth());
    assertThat(line.get("gross").get("amount").asText()).isEqualTo("1250.00");
    assertThat(texts(line.get("explanationCodes"))).contains("FULL_MONTH");
    // A preview writes nothing.
    assertThat(w.evidence().get(0)).isZero();

    JsonNode receipt = w.createRun("USD");
    assertThat(receipt.get("version").asInt()).isEqualTo(1);
    assertThat(receipt.get("status").asText()).isEqualTo("DRAFT");
    assertThat(receipt.get("employeeCount").asInt()).isEqualTo(1);
    assertThat(receipt.get("supersededRunId").isNull()).isTrue();

    JsonNode list = w.c.expect(PayrollRunWorld.get(w.admin, w.runsPath()), 200);
    assertThat(list.get("items")).hasSize(1);
    assertThat(list.get("items").get(0).get("total").get("amount").asText()).isEqualTo("1250.00");
    JsonNode detail =
        w.c.expect(
            PayrollRunWorld.get(w.admin, w.runsPath() + "/" + receipt.get("runId").asText()), 200);
    assertThat(detail.get("lines")).hasSize(1);
    assertThat(detail.get("lines").get(0).get("sources")).isNotEmpty();
    assertThat(detail.get("inputDigest").asText()).hasSize(64);
  }

  @Test
  void aFullMonthInCongoleseFrancsIsTheExactWholeAmount() throws Exception {
    w.policy("CALENDAR_DAYS");
    UUID bob = w.paid("Kalala", beforeMonth(), "CDF", "450000");
    JsonNode preview = w.runPreview("CDF");
    assertThat(w.line(preview, bob).get("gross").get("amount").asText()).isEqualTo("450000");
    assertThat(preview.get("total").get("currency").asText()).isEqualTo("CDF");
  }

  @Test
  void aPartialMonthIsProratedOnCalendarDaysWithOneRounding() throws Exception {
    w.policy("CALENDAR_DAYS");
    int dim = w.month.lengthOfMonth();
    UUID ana = w.paid("Mukendi", w.month.atDay(11), "USD", "1250.00");
    JsonNode line = w.line(w.runPreview("USD"), ana);
    int days = dim - 10;
    assertThat(line.get("fullMonth").asBoolean()).isFalse();
    assertThat(line.get("eligibleDays").asInt()).isEqualTo(days);
    assertThat(line.get("denominator").asInt()).isEqualTo(dim);
    assertThat(line.get("gross").get("amount").asText()).isEqualTo(usd(half(125_000, days, dim)));
    assertThat(texts(line.get("explanationCodes")))
        .contains("PRORATED_CALENDAR_DAYS", "STARTED_IN_MONTH");
    assertThat(line.get("ranges")).hasSize(1);
    assertThat(line.get("ranges").get(0).get("from").asText())
        .isEqualTo(w.month.atDay(11).toString());
  }

  @Test
  void aPartialMonthOnTheFixedThirtyDayBasisUsesTheCappedDays() throws Exception {
    w.policy("FIXED_30_DAYS");
    int dim = w.month.lengthOfMonth();
    UUID ana = w.paid("Mukendi", w.month.atDay(11), "USD", "1250.00");
    JsonNode line = w.line(w.runPreview("USD"), ana);
    int days = Math.min(dim - 10, 30);
    assertThat(line.get("denominator").asInt()).isEqualTo(30);
    assertThat(line.get("gross").get("amount").asText()).isEqualTo(usd(half(125_000, days, 30)));
    assertThat(texts(line.get("explanationCodes"))).contains("PRORATED_FIXED_30_DAYS");
  }

  @Test
  void aPartialMonthWithNoProrationMethodIsABlocker() throws Exception {
    w.policy("NONE");
    UUID ana = w.paid("Mukendi", w.month.atDay(11), "USD", "1250.00");
    JsonNode preview = w.runPreview("USD");
    assertThat(texts(w.line(preview, ana).get("blockers"))).contains("PARTIAL_MONTH_NOT_ALLOWED");
    assertThat(preview.get("blockedEmployeeCount").asInt()).isEqualTo(1);
  }

  @Test
  void aSeparationInTheMonthCapsTheDaysAtTheLastDay() throws Exception {
    w.policy("CALENDAR_DAYS");
    int dim = w.month.lengthOfMonth();
    UUID ana = w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    LocalDate last = w.month.atDay(20);
    w.separate(ana, last);
    JsonNode line = w.line(w.runPreview("USD"), ana);
    assertThat(line.get("eligibleDays").asInt()).isEqualTo(20);
    assertThat(line.get("gross").get("amount").asText()).isEqualTo(usd(half(125_000, 20, dim)));
    assertThat(texts(line.get("explanationCodes"))).contains("ENDED_IN_MONTH");
  }

  // ------------------------------------------------------------------------------------------
  // Blockers and the policy
  // ------------------------------------------------------------------------------------------

  @Test
  void aMissingCompensationBlocksTheRunAndNothingIsWritten() throws Exception {
    w.policy("CALENDAR_DAYS");
    UUID ana = w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    UUID bob = w.hire("Kalala", beforeMonth());
    JsonNode preview = w.runPreview("USD");
    assertThat(texts(w.line(preview, bob).get("blockers"))).contains("COMPENSATION_MISSING");
    assertThat(w.line(preview, ana).get("blockers")).isEmpty();
    assertThat(preview.get("blockedEmployeeCount").asInt()).isEqualTo(1);

    Map<String, Object> command = runCommand(w.month, "USD");
    List<Integer> before = w.evidence();
    w.c.problem(
        w.runCommitRequest(Organizations.newKey(), runCommitting(command, preview)),
        409,
        "PAYROLL_RUN_BLOCKED");
    assertThat(w.evidence()).isEqualTo(before);
  }

  @Test
  void aGapInTheCompensationIsABlocker() throws Exception {
    w.policy("CALENDAR_DAYS");
    UUID ana = w.hire("Mukendi", beforeMonth());
    w.compensate(ana, w.month.atDay(6), "USD", "1250.00");
    JsonNode line = w.line(w.runPreview("USD"), ana);
    assertThat(texts(line.get("blockers"))).contains("COMPENSATION_MISSING");
  }

  @Test
  void aCompensationInAnotherCurrencyIsABlocker() throws Exception {
    w.policy("CALENDAR_DAYS");
    UUID ana = w.paid("Mukendi", beforeMonth(), "CDF", "450000");
    JsonNode line = w.line(w.runPreview("USD"), ana);
    assertThat(texts(line.get("blockers"))).contains("CURRENCY_MISMATCH");
  }

  @Test
  void aPlacementInAnotherLegalEntityIsABlocker() throws Exception {
    w.policy("CALENDAR_DAYS");
    UUID ana = w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    w.moveElsewhere(ana, w.month.atDay(16));
    JsonNode preview = w.runPreview("USD");
    JsonNode line = w.line(preview, ana);
    assertThat(texts(line.get("blockers"))).contains("FOREIGN_PLACEMENT");
  }

  @Test
  void aRunWithoutAPolicyInForceIsAStableConflict() throws Exception {
    w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    w.c.problem(w.runPreviewRequest(runCommand(w.month, "USD")), 409, "PAYROLL_POLICY_NOT_FOUND");
  }

  @Test
  void aRunWithNoEmployeeWithAGrossIsRefused() throws Exception {
    w.policy("CALENDAR_DAYS");
    JsonNode preview = w.runPreview("USD");
    assertThat(preview.get("employeeCount").asInt()).isZero();
    w.c.problem(
        w.runCommitRequest(
            Organizations.newKey(), runCommitting(runCommand(w.month, "USD"), preview)),
        409,
        "PAYROLL_RUN_BLOCKED");
  }

  // ------------------------------------------------------------------------------------------
  // Period validity
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyAClosedRecentMonthCanBePreviewedOrCreated() throws Exception {
    w.policy(YearMonth.from(w.today).minusMonths(24).atDay(1), "LAST_DAY", null, "CALENDAR_DAYS");
    YearMonth current = YearMonth.from(w.today);
    for (YearMonth invalid :
        List.of(current, current.plusMonths(1), current.minusMonths(25), current.plusMonths(30))) {
      w.c.problem(w.runPreviewRequest(runCommand(invalid, "USD")), 422, "PAYROLL_PERIOD_INVALID");
    }
    // The 24th month back is the earliest runnable month, the 25th is not.
    JsonNode preview =
        w.c.expect(w.runPreviewRequest(runCommand(current.minusMonths(24), "USD")), 200);
    assertThat(preview.get("period").asText()).isEqualTo(current.minusMonths(24).toString());
    w.c.problem(
        w.runCommitRequest(
            Organizations.newKey(), runCommitting(runCommand(current, "USD"), preview)),
        422,
        "PAYROLL_PERIOD_INVALID");
  }

  @Test
  void malformedRequestsAreValidationFailures() throws Exception {
    w.policy("CALENDAR_DAYS");
    Map<String, Object> bad = new LinkedHashMap<>();
    bad.put("period", "2026-13");
    bad.put("currency", "USD");
    w.c.problem(w.runPreviewRequest(bad), 400, "VALIDATION_FAILED");
    Map<String, Object> unknown = runCommand(w.month, "USD");
    unknown.put("extra", "x");
    w.c.problem(w.runPreviewRequest(unknown), 400, "VALIDATION_FAILED");
    Map<String, Object> eur = runCommand(w.month, "EUR");
    w.c.problem(w.runPreviewRequest(eur), 400, "VALIDATION_FAILED");
  }

  // ------------------------------------------------------------------------------------------
  // Stale previews
  // ------------------------------------------------------------------------------------------

  private void assertStaleAfter(ThrowingRunnable change) throws Exception {
    Map<String, Object> command = runCommand(w.month, "USD");
    JsonNode preview = w.c.expect(w.runPreviewRequest(command), 200);
    change.run();
    List<Integer> before = w.evidence();
    w.c.problem(
        w.runCommitRequest(Organizations.newKey(), runCommitting(command, preview)),
        409,
        "PAYROLL_RUN_PREVIEW_STALE");
    assertThat(w.evidence()).isEqualTo(before);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  void aPolicyChangeMakesThePreviewStale() throws Exception {
    w.policy("CALENDAR_DAYS");
    w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    assertStaleAfter(() -> w.policy(w.month.atDay(1), "LAST_DAY", null, "FIXED_30_DAYS"));
  }

  @Test
  void aNewEmployeeMakesThePreviewStale() throws Exception {
    w.policy("CALENDAR_DAYS");
    w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    assertStaleAfter(() -> w.paid("Kalala", beforeMonth(), "USD", "900.00"));
  }

  @Test
  void anEmploymentChangeMakesThePreviewStale() throws Exception {
    w.policy("CALENDAR_DAYS");
    UUID ana = w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    assertStaleAfter(() -> w.separate(ana, w.month.atDay(25)));
  }

  @Test
  void aPlacementChangeMakesThePreviewStale() throws Exception {
    w.policy("CALENDAR_DAYS");
    w.paid("Kalala", beforeMonth(), "USD", "900.00");
    UUID ana = w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    assertStaleAfter(() -> w.regroup(ana, w.month.atDay(26)));
  }

  @Test
  void aCompensationCorrectionMakesThePreviewStale() throws Exception {
    w.policy("CALENDAR_DAYS");
    UUID ana = w.hire("Mukendi", beforeMonth());
    JsonNode established = w.compensate(ana, beforeMonth(), "USD", "1250.00");
    assertStaleAfter(
        () ->
            w.correct(ana, established.get("changeId").asText(), beforeMonth(), "USD", "1300.00"));
  }

  @Test
  void anExpiredPreviewIsStale() throws Exception {
    w.policy("CALENDAR_DAYS");
    w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    Map<String, Object> command = runCommand(w.month, "USD");
    JsonNode preview = w.c.expect(w.runPreviewRequest(command), 200);
    clock.set(Instant.parse(preview.get("expiresAt").asText()));
    w.c.problem(
        w.runCommitRequest(Organizations.newKey(), runCommitting(command, preview)),
        409,
        "PAYROLL_RUN_PREVIEW_STALE");
  }

  @Test
  void aForgedDigestOrExpiryIsStale() throws Exception {
    w.policy("CALENDAR_DAYS");
    w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    Map<String, Object> command = runCommand(w.month, "USD");
    JsonNode preview = w.c.expect(w.runPreviewRequest(command), 200);
    Map<String, Object> forged = new LinkedHashMap<>(runCommitting(command, preview));
    forged.put("previewDigest", "a".repeat(64));
    w.c.problem(
        w.runCommitRequest(Organizations.newKey(), forged), 409, "PAYROLL_RUN_PREVIEW_STALE");
    Map<String, Object> later = new LinkedHashMap<>(runCommitting(command, preview));
    later.put(
        "expiresAt",
        Instant.parse(preview.get("expiresAt").asText()).plusSeconds(86_400).toString());
    w.c.problem(
        w.runCommitRequest(Organizations.newKey(), later), 409, "PAYROLL_RUN_PREVIEW_STALE");
    assertThat(w.evidence().get(0)).isZero();
  }

  // ------------------------------------------------------------------------------------------
  // Idempotency and versions
  // ------------------------------------------------------------------------------------------

  @Test
  void aReplayReturnsTheSameReceiptAndADifferentBodyIsAKeyReuse() throws Exception {
    w.policy("CALENDAR_DAYS");
    w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    Map<String, Object> command = runCommand(w.month, "USD");
    JsonNode preview = w.c.expect(w.runPreviewRequest(command), 200);
    Map<String, Object> body = runCommitting(command, preview);
    String key = Organizations.newKey();
    MockHttpServletResponse first = w.c.call(w.runCommitRequest(key, body));
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(first.getHeader("Idempotent-Replayed")).isNull();
    List<Integer> after = w.evidence();
    MockHttpServletResponse replay = w.c.call(w.runCommitRequest(key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    assertThat(w.evidence()).isEqualTo(after);

    Map<String, Object> different = new LinkedHashMap<>(body);
    different.put("currency", "CDF");
    w.c.problem(w.runCommitRequest(key, different), 409, "IDEMPOTENCY_KEY_REUSED");
    // The same preview under a new key is now a version conflict, not a second run.
    w.c.problem(
        w.runCommitRequest(Organizations.newKey(), body), 409, "PAYROLL_RUN_VERSION_CONFLICT");
    assertThat(w.evidence()).isEqualTo(after);
  }

  @Test
  void aRecalculationSupersedesTheDraftAndAnIdenticalOneIsRefused() throws Exception {
    w.policy("CALENDAR_DAYS");
    w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    JsonNode first = w.createRun("USD");

    // Nothing changed: the preview says so and the creation records no new version.
    JsonNode same = w.runPreview("USD");
    assertThat(same.get("identicalToCurrent").asBoolean()).isTrue();
    assertThat(same.get("expectedVersion").asInt()).isEqualTo(1);
    List<Integer> before = w.evidence();
    w.c.problem(
        w.runCommitRequest(Organizations.newKey(), runCommitting(runCommand(w.month, "USD"), same)),
        409,
        "PAYROLL_RUN_PREVIEW_STALE");
    assertThat(w.evidence()).isEqualTo(before);

    w.paid("Kalala", beforeMonth(), "USD", "900.00");
    JsonNode second = w.createRun("USD");
    assertThat(second.get("version").asInt()).isEqualTo(2);
    assertThat(second.get("supersededRunId").asText()).isEqualTo(first.get("runId").asText());
    assertThat(second.get("employeeCount").asInt()).isEqualTo(2);

    JsonNode old =
        w.c.expect(
            PayrollRunWorld.get(w.admin, w.runsPath() + "/" + first.get("runId").asText()), 200);
    assertThat(old.get("run").get("status").asText()).isEqualTo("SUPERSEDED");
    assertThat(old.get("run").get("supersededByRunId").asText())
        .isEqualTo(second.get("runId").asText());
    JsonNode list = w.c.expect(PayrollRunWorld.get(w.admin, w.runsPath()), 200);
    assertThat(list.get("items")).hasSize(2);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM payroll.pay_run WHERE tenant_id = ? AND status = 'DRAFT'",
                w.tenant))
        .isEqualTo(1);
  }

  @Test
  void aStaleExpectedVersionIsAVersionConflict() throws Exception {
    w.policy("CALENDAR_DAYS");
    w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    Map<String, Object> command = runCommand(w.month, "USD");
    JsonNode preview = w.c.expect(w.runPreviewRequest(command), 200);
    w.createRun("USD");
    w.c.problem(
        w.runCommitRequest(Organizations.newKey(), runCommitting(command, preview)),
        409,
        "PAYROLL_RUN_VERSION_CONFLICT");
  }

  @Test
  void aSecondCurrencyIsItsOwnRunSeries() throws Exception {
    w.policy("CALENDAR_DAYS");
    UUID ana = w.paid("Mukendi", beforeMonth(), "USD", "1250.00");
    JsonNode usd = w.createRun("USD");
    assertThat(usd.get("version").asInt()).isEqualTo(1);
    // The other currency is its own chain (version 0, nothing shared) and, because the employee is paid
    // in USD, it is blocked rather than converted or combined.
    JsonNode cdf = w.runPreview("CDF");
    assertThat(cdf.get("expectedVersion").asInt()).isZero();
    assertThat(cdf.get("existingRun").isNull()).isTrue();
    assertThat(texts(w.line(cdf, ana).get("blockers"))).contains("CURRENCY_MISMATCH");
    assertThat(cdf.get("previewDigest").isNull()).isTrue();
    assertThat(w.runPreview("USD").get("identicalToCurrent").asBoolean()).isTrue();
  }

  // ------------------------------------------------------------------------------------------
  // Pages
  // ------------------------------------------------------------------------------------------

  @Test
  void runsAndLinesPageWithBoundCursors() throws Exception {
    w.policy("CALENDAR_DAYS");
    for (int i = 0; i < 2; i++) {
      w.paid("Employe" + i, beforeMonth(), "USD", "100" + i + ".00");
    }
    JsonNode first = w.createRun("USD");
    w.paid("Extra", beforeMonth(), "USD", "700.00");
    JsonNode second = w.createRun("USD");
    String detailPath = w.runsPath() + "/" + second.get("runId").asText();

    JsonNode page = w.c.expect(PayrollRunWorld.get(w.admin, detailPath + "?limit=2"), 200);
    assertThat(page.get("lines")).hasSize(2);
    String cursor = page.get("nextCursor").asText();
    JsonNode rest =
        w.c.expect(PayrollRunWorld.get(w.admin, detailPath + "?limit=2&cursor=" + cursor), 200);
    assertThat(rest.get("lines")).hasSize(1);
    assertThat(rest.get("nextCursor").isNull()).isTrue();
    // The cursor is bound to its size and run.
    w.c.problem(
        PayrollRunWorld.get(w.admin, detailPath + "?limit=1&cursor=" + cursor),
        400,
        "CURSOR_INVALID");
    w.c.problem(
        PayrollRunWorld.get(
            w.admin,
            w.runsPath() + "/" + first.get("runId").asText() + "?limit=2&cursor=" + cursor),
        400,
        "CURSOR_INVALID");
    w.c.problem(
        PayrollRunWorld.get(w.admin, detailPath + "?cursor=garbage"), 400, "CURSOR_INVALID");

    JsonNode runs = w.c.expect(PayrollRunWorld.get(w.admin, w.runsPath() + "?limit=1"), 200);
    assertThat(runs.get("items")).hasSize(1);
    String next = runs.get("nextCursor").asText();
    JsonNode more =
        w.c.expect(PayrollRunWorld.get(w.admin, w.runsPath() + "?limit=1&cursor=" + next), 200);
    assertThat(more.get("items")).hasSize(1);
    w.c.problem(PayrollRunWorld.get(w.admin, w.runsPath() + "?limit=0"), 400, "VALIDATION_FAILED");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.runsPath() + "?limit=101"), 400, "VALIDATION_FAILED");
  }

  @Test
  void aMissingOrMalformedRunIsOneSafeNotFound() throws Exception {
    w.policy("CALENDAR_DAYS");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.runsPath() + "/" + UUID.randomUUID()),
        404,
        "PAYROLL_RUN_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.runsPath() + "/not-a-uuid"), 404, "PAYROLL_RUN_NOT_FOUND");
  }
}
