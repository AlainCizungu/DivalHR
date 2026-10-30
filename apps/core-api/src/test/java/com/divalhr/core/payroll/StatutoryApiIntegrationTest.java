package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.domain.StatutoryEngine;
import com.divalhr.core.payroll.domain.StatutoryLine;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
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
 * MVP-094B (Issue #179, ADR 0034) against real PostgreSQL through the public API only: a synthetic
 * VERIFIED rule pack is activated, profiles are recorded, a frozen CDF run is previewed and
 * calculated with exact amounts, every blocker refuses the creation with its own stable code, every
 * kind of input drift makes a preview stale, replays are idempotent and a changed recalculation
 * supersedes the draft. Synthetic data only: the rule packs are {@code SYNTHETIC-TEST-*} with no
 * legal meaning, and the clock is frozen on the 15th of the current month.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.statutory-read=600",
      "divalhr.request-limits.subject.statutory-calculation-preview=600",
      "divalhr.request-limits.subject.statutory-calculation-create=600",
      "divalhr.request-limits.subject.statutory-profile-preview=600",
      "divalhr.request-limits.subject.statutory-profile-write=600",
      "divalhr.request-limits.subject.statutory-activation-write=600",
      "divalhr.request-limits.subject.payroll-run-preview=600",
      "divalhr.request-limits.subject.payroll-run-create=600",
      "divalhr.request-limits.subject.payroll-policy-preview=600",
      "divalhr.request-limits.subject.payroll-policy-write=600",
      "divalhr.request-limits.subject.payroll-run-read=600",
      "divalhr.request-limits.subject.payroll-policy-read=600"
    })
class StatutoryApiIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private StatutoryWorld w;

  @BeforeEach
  void world() throws Exception {
    w = new StatutoryWorld(mvc, jdbc, lookups, clock);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String amount(JsonNode money) {
    assertThat(money.get("currency").asText()).isEqualTo("CDF");
    return money.get("amount").asText();
  }

  private static List<String> texts(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(v -> values.add(v.asText()));
    return values;
  }

  /** The activated pack, one standard employee paid 162,000 and a CDF run: the happy path. */
  private record Ready(UUID pack, UUID employee, String run) {}

  private Ready ready() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.activate(pack, w.month);
    UUID employee = w.paidInCdf("Mukendi", StatutoryWorld.GROSS);
    w.standard(employee);
    return new Ready(pack, employee, w.cdfRun());
  }

  // ------------------------------------------------------------------------------------------
  // The calculation
  // ------------------------------------------------------------------------------------------

  @Test
  void aStandardEmployeeGetsTheExactAmountsAndTheCalculationIsFrozen() throws Exception {
    Ready ready = ready();
    JsonNode preview = w.preview(ready.run());
    assertThat(preview.get("status").asText()).isEqualTo("READY");
    assertThat(preview.get("period").asText()).isEqualTo(w.month.toString());
    assertThat(preview.get("currency").asText()).isEqualTo("CDF");
    assertThat(preview.get("expectedVersion").asInt()).isZero();
    assertThat(preview.get("existingCalculation").isNull()).isTrue();
    assertThat(texts(preview.get("runBlockers"))).isEmpty();
    assertThat(preview.get("supportedCount").asInt()).isEqualTo(1);
    assertThat(preview.get("excludedCount").asInt()).isZero();
    assertThat(preview.get("blockedCount").asInt()).isZero();
    JsonNode totals = preview.get("totals");
    assertThat(amount(totals.get("contractualGross"))).isEqualTo("162000");
    assertThat(amount(totals.get("employeeDeductions"))).isEqualTo("12960");
    assertThat(amount(totals.get("employerContributions"))).isEqualTo("21060");
    assertThat(amount(totals.get("previewNet"))).isEqualTo("149040");
    assertThat(amount(totals.get("employerCost"))).isEqualTo("183060");
    JsonNode line = w.line(preview, ready.employee());
    assertThat(line.get("components")).hasSize(5);
    JsonNode irpp = line.get("components").get(4);
    assertThat(irpp.get("componentId").asText()).isEqualTo("IRPP_SALARY_WITHHOLDING");
    assertThat(amount(irpp.get("amount"))).isEqualTo("4860");
    assertThat(irpp.get("trace").get("annualBase").asText()).isEqualTo("1944000");
    assertThat(irpp.get("trace").get("capApplied").asBoolean()).isFalse();
    assertThat(preview.get("calculationDigest").asText()).hasSize(64);
    assertThat(preview.get("pack").get("status").asText()).isEqualTo("VERIFIED");
    // A preview writes nothing.
    assertThat(w.evidence().get(2)).isZero();

    JsonNode receipt = w.create(ready.run());
    assertThat(receipt.get("version").asInt()).isEqualTo(1);
    assertThat(receipt.get("status").asText()).isEqualTo("DRAFT");
    assertThat(receipt.get("supersededCalculationId").isNull()).isTrue();
    // The receipt is amount-free.
    assertThat(receipt.toString()).doesNotContain("12960").doesNotContain("162000");
    String calculation = receipt.get("calculationId").asText();

    JsonNode detail =
        w.c.expect(
            PayrollRunWorld.get(w.admin, w.calculationsPath(ready.run()) + "/" + calculation), 200);
    assertThat(detail.get("calculation").get("status").asText()).isEqualTo("DRAFT");
    assertThat(amount(detail.get("calculation").get("totals").get("previewNet")))
        .isEqualTo("149040");
    assertThat(detail.get("sources")).hasSize(2);
    JsonNode stored = detail.get("lines").get(0);
    assertThat(stored.get("employeeId").asText()).isEqualTo(ready.employee().toString());
    assertThat(amount(stored.get("employerCost"))).isEqualTo("183060");
    assertThat(stored.get("components").get(4).get("trace")).isEqualTo(irpp.get("trace"));

    // Later corrections never alter a frozen calculation: the profile changes, the stored values
    // stay, and the next preview offers a new version.
    w.profile(ready.employee(), w.month.atDay(1).minusDays(10), "STANDARD_RESIDENT", true, false);
    JsonNode again =
        w.c.expect(
            PayrollRunWorld.get(w.admin, w.calculationsPath(ready.run()) + "/" + calculation), 200);
    assertThat(again.get("lines")).isEqualTo(detail.get("lines"));
  }

  @Test
  void theApiMatchesTheEngineForSeveralGrossesIncludingTheCap() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(w.jdbc);
    w.activate(pack, w.month);
    Map<String, String> words =
        Map.of("12345", "Alpha", "162000", "Bravo", "5000000", "Charlie", "100000000", "Delta");
    Map<UUID, Long> employees = new LinkedHashMap<>();
    for (String gross : List.of("12345", "162000", "5000000", "100000000")) {
      UUID employee = w.paidInCdf("Gross" + words.get(gross), gross);
      w.standard(employee);
      employees.put(employee, Long.parseLong(gross));
    }
    JsonNode preview = w.preview(w.cdfRun());
    assertThat(preview.get("status").asText()).isEqualTo("READY");
    for (Map.Entry<UUID, Long> entry : employees.entrySet()) {
      StatutoryLine expected =
          StatutoryEngine.calculate(StatutoryFixtures.seed(), true, true, entry.getValue());
      JsonNode line = w.line(preview, entry.getKey());
      assertThat(amount(line.get("employeeDeductions")))
          .isEqualTo(Long.toString(expected.employeeDeductionsMinor()));
      assertThat(amount(line.get("employerContributions")))
          .isEqualTo(Long.toString(expected.employerContributionsMinor()));
      assertThat(amount(line.get("previewNet")))
          .isEqualTo(Long.toString(expected.previewNetMinor()));
      assertThat(amount(line.get("employerCost")))
          .isEqualTo(Long.toString(expected.employerCostMinor()));
    }
    // The highest gross reaches the cap.
    JsonNode capped = w.line(preview, new ArrayList<>(employees.keySet()).get(3));
    assertThat(capped.get("components").get(4).get("trace").get("capApplied").asBoolean()).isTrue();
  }

  @Test
  void anEmployeeSubjectToNothingIsKeptAsExcludedWithZeroComponents() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(w.jdbc);
    w.activate(pack, w.month);
    UUID employee = w.paidInCdf("Exempt", "100000");
    w.profile(employee, w.month.atDay(1).minusDays(30), "STANDARD_RESIDENT", false, false);
    String run = w.cdfRun();
    JsonNode preview = w.preview(run);
    assertThat(preview.get("status").asText()).isEqualTo("READY");
    assertThat(preview.get("supportedCount").asInt()).isZero();
    assertThat(preview.get("excludedCount").asInt()).isEqualTo(1);
    JsonNode line = w.line(preview, employee);
    assertThat(line.get("excluded").asBoolean()).isTrue();
    assertThat(line.get("components")).isEmpty();
    assertThat(amount(line.get("previewNet"))).isEqualTo("100000");
    assertThat(amount(line.get("employerCost"))).isEqualTo("100000");
    JsonNode receipt = w.create(run);
    assertThat(receipt.get("excludedCount").asInt()).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Blockers
  // ------------------------------------------------------------------------------------------

  @Test
  void withoutAnActivationTheRunIsBlockedAndTheTotalsAreHidden() throws Exception {
    UUID employee = w.paidInCdf("Mukendi", StatutoryWorld.GROSS);
    w.standard(employee);
    String run = w.cdfRun();
    JsonNode preview = w.preview(run);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(preview.get("runBlockers"))).containsExactly("RULE_PACK_MISSING");
    assertThat(preview.get("totals").isNull()).isTrue();
    assertThat(preview.get("pack").isNull()).isTrue();
    assertThat(preview.get("rulePackDigest").isNull()).isTrue();
    assertThat(preview.get("calculationDigest").isNull()).isTrue();
    assertThat(preview.get("expiresAt").isNull()).isTrue();
    JsonNode line = w.line(preview, employee);
    assertThat(amount(line.get("contractualGross"))).isEqualTo("162000");
    assertThat(line.get("employeeDeductions").isNull()).isTrue();
    assertThat(line.get("components")).isEmpty();
    w.c.problem(
        w.createRequest(run, Organizations.newKey(), StatutoryWorld.calculationCommitting(preview)),
        409,
        "STATUTORY_RULE_PACK_NOT_VERIFIED");
    assertThat(w.evidence().get(2)).isZero();
  }

  @Test
  void aMissingOrUnsupportedProfileBlocksWithItsOwnCodeAndStillShowsOtherBlockers()
      throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(w.jdbc);
    w.activate(pack, w.month);
    UUID missing = w.paidInCdf("Missing", "100000");
    String run = w.cdfRun();
    JsonNode preview = w.preview(run);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(preview.get("blockedCount").asInt()).isEqualTo(1);
    assertThat(texts(w.line(preview, missing).get("blockers"))).containsExactly("PROFILE_MISSING");
    w.c.problem(
        w.createRequest(run, Organizations.newKey(), StatutoryWorld.calculationCommitting(preview)),
        409,
        "STATUTORY_PROFILE_MISSING");

    w.profile(missing, w.month.atDay(1).minusDays(30), "UNSUPPORTED", false, false);
    JsonNode unsupported = w.preview(run);
    assertThat(texts(w.line(unsupported, missing).get("blockers")))
        .containsExactly("PROFILE_UNSUPPORTED");
    w.c.problem(
        w.createRequest(
            run, Organizations.newKey(), StatutoryWorld.calculationCommitting(unsupported)),
        409,
        "STATUTORY_PROFILE_UNSUPPORTED");
    assertThat(w.evidence().get(2)).isZero();
  }

  @Test
  void aProfileThatChangesInsideTheMonthBlocksTheCalculation() throws Exception {
    Ready ready = ready();
    w.profile(ready.employee(), w.month.atDay(15), "STANDARD_RESIDENT", true, false);
    JsonNode preview = w.preview(ready.run());
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(w.line(preview, ready.employee()).get("blockers")))
        .containsExactly("PROFILE_CHANGES_IN_MONTH");
    w.c.problem(
        w.createRequest(
            ready.run(), Organizations.newKey(), StatutoryWorld.calculationCommitting(preview)),
        409,
        "STATUTORY_CALCULATION_BLOCKED");
  }

  @Test
  void aUsdRunIsNotSupported() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(w.jdbc);
    w.activate(pack, w.month);
    UUID employee = w.r.paid("Dollar", w.month.atDay(1).minusDays(5), "USD", "1250.00");
    w.standard(employee);
    String run = w.r.createRun("USD").get("runId").asText();
    JsonNode preview = w.preview(run);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(preview.get("runBlockers"))).contains("CURRENCY_NOT_SUPPORTED");
    assertThat(preview.get("totals").isNull()).isTrue();
    w.c.problem(
        w.createRequest(run, Organizations.newKey(), StatutoryWorld.calculationCommitting(preview)),
        409,
        "STATUTORY_RULE_PACK_NOT_APPLICABLE");
  }

  @Test
  void anActivationOfAPackThatDoesNotCoverTheMonthIsNotApplicable() throws Exception {
    // The pack's interval ended the day before the month starts.
    UUID pack =
        StatutoryWorld.verifiedPack(
            w.jdbc,
            StatutoryFixtures.SEED_JSON,
            "2020-01-01",
            w.month.atDay(1).minusDays(1).toString());
    w.c.problem(
        w.activateRequest(Organizations.newKey(), StatutoryWorld.activation(pack, w.month, 0)),
        409,
        "STATUTORY_RULE_PACK_NOT_APPLICABLE");
    assertThat(w.evidence().get(0)).isZero();
  }

  // ------------------------------------------------------------------------------------------
  // Versions, no change, replays
  // ------------------------------------------------------------------------------------------

  @Test
  void anIdenticalRecalculationIsNoChangeAndNothingIsRecorded() throws Exception {
    Ready ready = ready();
    w.preview(ready.run());
    w.create(ready.run());
    List<Integer> before = w.evidence();

    JsonNode again = w.preview(ready.run());
    assertThat(again.get("status").asText()).isEqualTo("NO_CHANGE");
    assertThat(again.get("expectedVersion").asInt()).isEqualTo(1);
    assertThat(again.get("existingCalculation").get("version").asInt()).isEqualTo(1);
    assertThat(again.get("calculationDigest").isNull()).isTrue();
    assertThat(again.get("expiresAt").isNull()).isTrue();
    w.c.problem(
        w.createRequest(
            ready.run(), Organizations.newKey(), StatutoryWorld.calculationCommitting(again)),
        409,
        "STATUTORY_CALCULATION_NO_CHANGE");
    assertThat(w.evidence()).isEqualTo(before);
  }

  @Test
  void aChangedProfileCreatesVersionTwoAndSupersedesVersionOne() throws Exception {
    Ready ready = ready();
    JsonNode first = w.create(ready.run());
    w.profile(ready.employee(), w.month.atDay(1).minusDays(10), "STANDARD_RESIDENT", true, false);
    JsonNode preview = w.preview(ready.run());
    assertThat(preview.get("status").asText()).isEqualTo("READY");
    assertThat(preview.get("expectedVersion").asInt()).isEqualTo(1);
    assertThat(amount(preview.get("totals").get("employeeDeductions"))).isEqualTo("8100");

    JsonNode second =
        w.c.expect(
            w.createRequest(
                ready.run(), Organizations.newKey(), StatutoryWorld.calculationCommitting(preview)),
            201);
    assertThat(second.get("version").asInt()).isEqualTo(2);
    assertThat(second.get("supersededCalculationId").asText())
        .isEqualTo(first.get("calculationId").asText());

    JsonNode list = w.c.expect(PayrollRunWorld.get(w.admin, w.calculationsPath(ready.run())), 200);
    assertThat(list.get("items")).hasSize(2);
    assertThat(list.get("items").get(0).get("version").asInt()).isEqualTo(2);
    assertThat(list.get("items").get(0).get("status").asText()).isEqualTo("DRAFT");
    assertThat(list.get("items").get(1).get("status").asText()).isEqualTo("SUPERSEDED");
    assertThat(list.get("items").get(1).get("supersededById").asText())
        .isEqualTo(second.get("calculationId").asText());
    // The superseded version keeps its original amounts.
    JsonNode old =
        w.c.expect(
            PayrollRunWorld.get(
                w.admin,
                w.calculationsPath(ready.run()) + "/" + first.get("calculationId").asText()),
            200);
    assertThat(amount(old.get("calculation").get("totals").get("employeeDeductions")))
        .isEqualTo("12960");
  }

  @Test
  void aReplayReturnsTheOriginalReceiptAndRecordsNothingMore() throws Exception {
    Ready ready = ready();
    JsonNode preview = w.preview(ready.run());
    String key = Organizations.newKey();
    Map<String, Object> body = StatutoryWorld.calculationCommitting(preview);
    MockHttpServletResponse first = w.c.call(w.createRequest(ready.run(), key, body));
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(first.getHeader("Idempotent-Replayed")).isNull();
    assertThat(first.getHeader("Cache-Control")).isEqualTo("private, no-store");
    List<Integer> after = w.evidence();
    MockHttpServletResponse replay = w.c.call(w.createRequest(ready.run(), key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    assertThat(w.evidence()).isEqualTo(after);
    // The same key with another payload is refused.
    Map<String, Object> other = new LinkedHashMap<>(body);
    other.put("profileDigest", "c".repeat(64));
    w.c.problem(w.createRequest(ready.run(), key, other), 409, "IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void aDifferentChainVersionIsAVersionConflictBeforeAnyOtherCheck() throws Exception {
    Ready ready = ready();
    JsonNode preview = w.preview(ready.run());
    Map<String, Object> body = new LinkedHashMap<>(StatutoryWorld.calculationCommitting(preview));
    body.put("expectedVersion", 3);
    w.c.problem(
        w.createRequest(ready.run(), Organizations.newKey(), body),
        409,
        "STATUTORY_VERSION_CONFLICT");
  }

  // ------------------------------------------------------------------------------------------
  // Stale previews
  // ------------------------------------------------------------------------------------------

  @Test
  void everyKindOfDriftMakesThePreviewStale() throws Exception {
    Ready ready = ready();
    // Forged digests, each one on its own.
    JsonNode fresh = w.preview(ready.run());
    for (String field :
        List.of("sourceRunDigest", "profileDigest", "rulePackDigest", "calculationDigest")) {
      Map<String, Object> forged = new LinkedHashMap<>(StatutoryWorld.calculationCommitting(fresh));
      forged.put(field, "a".repeat(64));
      w.c.problem(
          w.createRequest(ready.run(), Organizations.newKey(), forged),
          409,
          "STATUTORY_PREVIEW_STALE");
    }
    Map<String, Object> later = new LinkedHashMap<>(StatutoryWorld.calculationCommitting(fresh));
    later.put(
        "expiresAt", Instant.parse(fresh.get("expiresAt").asText()).plusSeconds(86_400).toString());
    w.c.problem(
        w.createRequest(ready.run(), Organizations.newKey(), later),
        409,
        "STATUTORY_PREVIEW_STALE");

    // A profile change after the preview.
    w.profile(ready.employee(), w.month.atDay(1).minusDays(10), "STANDARD_RESIDENT", true, false);
    w.c.problem(
        w.createRequest(
            ready.run(), Organizations.newKey(), StatutoryWorld.calculationCommitting(fresh)),
        409,
        "STATUTORY_PREVIEW_STALE");
    assertThat(w.evidence().get(2)).isZero();

    // Expiry: the preview is current, but its window has closed.
    JsonNode expiring = w.preview(ready.run());
    clock.set(Instant.parse(expiring.get("expiresAt").asText()));
    w.c.problem(
        w.createRequest(
            ready.run(), Organizations.newKey(), StatutoryWorld.calculationCommitting(expiring)),
        409,
        "STATUTORY_PREVIEW_STALE");
    assertThat(w.evidence().get(2)).isZero();
  }

  @Test
  void aSupersededSourceRunMakesThePreviewStaleAndTheOldRunBlocked() throws Exception {
    Ready ready = ready();
    JsonNode preview = w.preview(ready.run());
    // A second employee changes the source run: version 2 of the month, version 1 superseded.
    UUID second = w.paidInCdf("Kalala", "90000");
    w.standard(second);
    w.cdfRun();
    JsonNode old = w.preview(ready.run());
    assertThat(old.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(old.get("runBlockers"))).contains("SOURCE_RUN_NOT_CURRENT");
    assertThat(old.get("totals").isNull()).isTrue();
    w.c.problem(
        w.createRequest(
            ready.run(), Organizations.newKey(), StatutoryWorld.calculationCommitting(preview)),
        409,
        "STATUTORY_PREVIEW_STALE");
  }

  // ------------------------------------------------------------------------------------------
  // Rule packs
  // ------------------------------------------------------------------------------------------

  @Test
  void theSeededReferencePackIsADraftAndNeverActivatable() throws Exception {
    JsonNode drafts =
        w.c.expect(PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "?status=DRAFT"), 200);
    JsonNode seeded = null;
    for (JsonNode item : drafts.get("items")) {
      if ("CD-SALARY-PREVIEW".equals(item.get("code").asText())) {
        seeded = item;
      }
    }
    assertThat(seeded).isNotNull();
    assertThat(seeded.get("status").asText()).isEqualTo("DRAFT");
    UUID id = UUID.fromString(seeded.get("packId").asText());
    JsonNode detail =
        w.c.expect(PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "/" + id), 200);
    assertThat(detail.get("verification").isNull()).isTrue();
    assertThat(detail.get("components")).hasSize(5);
    w.c.problem(
        w.activateRequest(Organizations.newKey(), StatutoryWorld.activation(id, w.month, 0)),
        409,
        "STATUTORY_RULE_PACK_NOT_VERIFIED");
    assertThat(w.evidence().get(0)).isZero();
  }

  @Test
  void aVerifiedPackShowsItsEvidenceAndASyntheticDraftNeverActivates() throws Exception {
    UUID verified = StatutoryWorld.verifiedPack(w.jdbc);
    UUID draft = StatutoryWorld.draftPack(w.jdbc, StatutoryFixtures.SEED_JSON, "2020-01-01", null);
    JsonNode detail =
        w.c.expect(PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "/" + verified), 200);
    assertThat(detail.get("pack").get("status").asText()).isEqualTo("VERIFIED");
    assertThat(detail.get("pack").get("rulesDigest").asText()).hasSize(64);
    assertThat(detail.get("verification").get("reviewerName").asText())
        .isEqualTo("Synthetic Test Reviewer");
    assertThat(detail.get("verification").get("attested").asBoolean()).isTrue();
    assertThat(detail.get("sources")).hasSize(2);
    assertThat(detail.get("components").get(0).get("method").get("type").asText())
        .isEqualTo("RATE");
    assertThat(detail.get("components").get(4).get("method").get("type").asText())
        .isEqualTo("PROGRESSIVE");
    w.c.problem(
        w.activateRequest(Organizations.newKey(), StatutoryWorld.activation(draft, w.month, 0)),
        409,
        "STATUTORY_RULE_PACK_NOT_VERIFIED");
  }

  @Test
  void packReadsAreSafeAndPaged() throws Exception {
    StatutoryWorld.verifiedPack(w.jdbc);
    StatutoryWorld.verifiedPack(w.jdbc);
    w.c.problem(
        PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "/" + UUID.randomUUID()),
        404,
        "STATUTORY_RULE_PACK_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "/not-a-uuid"),
        404,
        "STATUTORY_RULE_PACK_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "?status=ACTIVE"),
        400,
        "VALIDATION_FAILED");
    w.c.problem(
        PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "?limit=0"), 400, "VALIDATION_FAILED");
    w.c.problem(
        PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "?limit=101"),
        400,
        "VALIDATION_FAILED");
    w.c.problem(
        PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "?cursor=garbage"),
        400,
        "CURSOR_INVALID");
    MockHttpServletResponse response =
        w.c.call(PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "?status=VERIFIED&limit=1"));
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode page = CompensationWorld.JSON.readTree(response.getContentAsString());
    assertThat(page.get("items")).hasSize(1);
    String next = page.get("nextCursor").asText();
    JsonNode second =
        w.c.expect(
            PayrollRunWorld.get(
                w.admin, StatutoryWorld.PACKS + "?status=VERIFIED&limit=1&cursor=" + next),
            200);
    assertThat(second.get("items").get(0).get("packId"))
        .isNotEqualTo(page.get("items").get(0).get("packId"));
    // The cursor is bound to the filter it was issued for.
    w.c.problem(
        PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "?status=DRAFT&limit=1&cursor=" + next),
        400,
        "CURSOR_INVALID");
  }

  // ------------------------------------------------------------------------------------------
  // Activations
  // ------------------------------------------------------------------------------------------

  @Test
  void anActivationIsReadAppendedAndReplayedAndGuardedByTheVersion() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(w.jdbc);
    JsonNode before =
        w.c.expect(PayrollRunWorld.get(w.admin, w.activationPath() + "?month=" + w.month), 200);
    assertThat(before.get("version").asInt()).isZero();
    assertThat(before.get("verificationRequired").asBoolean()).isTrue();
    assertThat(before.get("inEffect").isNull()).isTrue();
    assertThat(before.get("month").asText()).isEqualTo(w.month.toString());

    String key = Organizations.newKey();
    Map<String, Object> body = StatutoryWorld.activation(pack, w.month, 0);
    MockHttpServletResponse first = w.c.call(w.activateRequest(key, body));
    assertThat(first.getStatus()).isEqualTo(201);
    JsonNode receipt = CompensationWorld.JSON.readTree(first.getContentAsString());
    assertThat(receipt.get("sequence").asInt()).isEqualTo(1);
    assertThat(receipt.get("fromMonth").asText()).isEqualTo(w.month.toString());
    assertThat(receipt.get("packId").asText()).isEqualTo(pack.toString());
    List<Integer> after = w.evidence();
    MockHttpServletResponse replay = w.c.call(w.activateRequest(key, body));
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(w.evidence()).isEqualTo(after);

    JsonNode view =
        w.c.expect(PayrollRunWorld.get(w.admin, w.activationPath() + "?month=" + w.month), 200);
    assertThat(view.get("version").asInt()).isEqualTo(1);
    assertThat(view.get("verificationRequired").asBoolean()).isFalse();
    assertThat(view.get("inEffect").get("pack").get("packId").asText()).isEqualTo(pack.toString());
    assertThat(view.get("activations")).hasSize(1);
    // An earlier month has no pack in effect.
    JsonNode earlier =
        w.c.expect(
            PayrollRunWorld.get(w.admin, w.activationPath() + "?month=" + w.month.minusMonths(1)),
            200);
    assertThat(earlier.get("inEffect").isNull()).isTrue();

    // A stale version, a month already activated, an unknown pack and a missing acknowledgement.
    w.c.problem(
        w.activateRequest(
            Organizations.newKey(), StatutoryWorld.activation(pack, w.month.plusMonths(1), 0)),
        409,
        "STATUTORY_VERSION_CONFLICT");
    w.c.problem(
        w.activateRequest(Organizations.newKey(), StatutoryWorld.activation(pack, w.month, 1)),
        409,
        "STATUTORY_VERSION_CONFLICT");
    w.c.problem(
        w.activateRequest(
            Organizations.newKey(),
            StatutoryWorld.activation(UUID.randomUUID(), w.month.plusMonths(1), 1)),
        404,
        "STATUTORY_RULE_PACK_NOT_FOUND");
    Map<String, Object> unacknowledged = StatutoryWorld.activation(pack, w.month.plusMonths(1), 1);
    unacknowledged.put("acknowledged", false);
    w.c.problem(
        w.activateRequest(Organizations.newKey(), unacknowledged), 400, "VALIDATION_FAILED");
    Map<String, Object> unknown = StatutoryWorld.activation(pack, w.month.plusMonths(1), 1);
    unknown.put("extra", "x");
    w.c.problem(w.activateRequest(Organizations.newKey(), unknown), 400, "VALIDATION_FAILED");
    assertThat(w.evidence().get(0)).isEqualTo(1);
  }

  @Test
  void theActivationReadNeedsAMonthAndAKnownLegalEntity() throws Exception {
    w.c.problem(PayrollRunWorld.get(w.admin, w.activationPath()), 400, "VALIDATION_FAILED");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.activationPath() + "?month=2026-13"),
        400,
        "VALIDATION_FAILED");
    w.c.problem(
        PayrollRunWorld.get(
            w.admin, StatutoryWorld.activationPath(UUID.randomUUID()) + "?month=" + w.month),
        404,
        "LEGAL_ENTITY_NOT_FOUND");
  }

  // ------------------------------------------------------------------------------------------
  // Profiles
  // ------------------------------------------------------------------------------------------

  @Test
  void aProfileIsPreviewedRecordedAppendedAndReplayed() throws Exception {
    UUID employee = w.paidInCdf("Mukendi", "100000");
    JsonNode empty =
        w.c.expect(PayrollRunWorld.get(w.admin, StatutoryWorld.profilePath(employee)), 200);
    assertThat(empty.get("version").asInt()).isZero();
    assertThat(empty.get("current").isNull()).isTrue();
    assertThat(empty.get("timeline")).isEmpty();

    LocalDate from = w.month.atDay(1).minusDays(30);
    Map<String, Object> command =
        StatutoryWorld.profileCommand(from, "STANDARD_RESIDENT", true, true, "EMPLOYMENT_CONTRACT");
    JsonNode preview = w.c.expect(w.profilePreviewRequest(employee, command), 200);
    assertThat(preview.get("blockers")).isEmpty();
    assertThat(preview.get("before")).isEmpty();
    assertThat(preview.get("after")).hasSize(1);
    assertThat(w.evidence().get(1)).isZero();

    String key = Organizations.newKey();
    Map<String, Object> body = StatutoryWorld.profileCommitting(command, preview);
    MockHttpServletResponse first = w.c.call(w.profileCommitRequest(employee, key, body));
    assertThat(first.getStatus()).isEqualTo(201);
    JsonNode receipt = CompensationWorld.JSON.readTree(first.getContentAsString());
    assertThat(receipt.get("sequence").asInt()).isEqualTo(1);
    assertThat(receipt.get("version").asInt()).isEqualTo(1);
    List<Integer> after = w.evidence();
    MockHttpServletResponse replay = w.c.call(w.profileCommitRequest(employee, key, body));
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(w.evidence()).isEqualTo(after);

    JsonNode timeline =
        w.c.expect(PayrollRunWorld.get(w.admin, StatutoryWorld.profilePath(employee)), 200);
    assertThat(timeline.get("version").asInt()).isEqualTo(1);
    assertThat(timeline.get("current").get("classification").asText())
        .isEqualTo("STANDARD_RESIDENT");
    assertThat(timeline.get("current").get("country").asText()).isEqualTo("CD");

    // The identical entry is a NO_CHANGE blocker; a stale version and a tampered digest are
    // refused.
    JsonNode same = w.c.expect(w.profilePreviewRequest(employee, command), 200);
    assertThat(texts(same.get("blockers"))).containsExactly("NO_CHANGE");
    assertThat(same.get("previewDigest").isNull()).isTrue();
    assertThat(same.get("expiresAt").isNull()).isTrue();
    w.c.problem(
        w.profileCommitRequest(
            employee, Organizations.newKey(), StatutoryWorld.profileCommitting(command, same)),
        409,
        "STATUTORY_PREVIEW_STALE");

    Map<String, Object> next =
        StatutoryWorld.profileCommand(
            from.plusDays(5), "STANDARD_RESIDENT", true, false, "PAYROLL_FILE_REVIEW");
    JsonNode nextPreview = w.c.expect(w.profilePreviewRequest(employee, next), 200);
    Map<String, Object> staleVersion = StatutoryWorld.profileCommitting(next, nextPreview);
    staleVersion.put("expectedVersion", 0);
    w.c.problem(
        w.profileCommitRequest(employee, Organizations.newKey(), staleVersion),
        409,
        "STATUTORY_VERSION_CONFLICT");
    Map<String, Object> tampered = StatutoryWorld.profileCommitting(next, nextPreview);
    tampered.put("cnssSubject", false);
    w.c.problem(
        w.profileCommitRequest(employee, Organizations.newKey(), tampered),
        409,
        "STATUTORY_PREVIEW_STALE");
    assertThat(w.evidence().get(1)).isEqualTo(1);
  }

  @Test
  void profileInputIsValidatedAndAnUnknownEmployeeIsOneSafe404() throws Exception {
    UUID employee = w.paidInCdf("Mukendi", "100000");
    LocalDate from = w.month.atDay(1).minusDays(30);
    w.c.problem(
        w.profilePreviewRequest(
            employee,
            StatutoryWorld.profileCommand(from, "UNSUPPORTED", true, false, "EMPLOYMENT_CONTRACT")),
        400,
        "VALIDATION_FAILED");
    w.c.problem(
        w.profilePreviewRequest(
            employee,
            StatutoryWorld.profileCommand(from, "OTHER", true, true, "EMPLOYMENT_CONTRACT")),
        400,
        "VALIDATION_FAILED");
    w.c.problem(
        w.profilePreviewRequest(
            employee,
            StatutoryWorld.profileCommand(from, "STANDARD_RESIDENT", true, true, "FREE_TEXT")),
        400,
        "VALIDATION_FAILED");
    JsonNode outOfRange =
        w.c.expect(
            w.profilePreviewRequest(
                employee,
                StatutoryWorld.profileCommand(
                    LocalDate.of(1999, 12, 31),
                    "STANDARD_RESIDENT",
                    true,
                    true,
                    "EMPLOYMENT_CONTRACT")),
            200);
    assertThat(texts(outOfRange.get("blockers"))).containsExactly("EFFECTIVE_DATE_OUT_OF_RANGE");
    UUID unknown = UUID.randomUUID();
    w.c.problem(
        PayrollRunWorld.get(w.admin, StatutoryWorld.profilePath(unknown)),
        404,
        "COMPENSATION_NOT_FOUND");
    w.c.problem(
        w.profilePreviewRequest(
            unknown,
            StatutoryWorld.profileCommand(
                from, "STANDARD_RESIDENT", true, true, "EMPLOYMENT_CONTRACT")),
        404,
        "COMPENSATION_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(w.admin, "/api/v1/employees/not-a-uuid/statutory-profile"),
        404,
        "COMPENSATION_NOT_FOUND");
  }

  // ------------------------------------------------------------------------------------------
  // Reads of calculations
  // ------------------------------------------------------------------------------------------

  @Test
  void linesArePagedByEmployeeNumberAndUnknownCalculationsAreOneSafe404() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(w.jdbc);
    w.activate(pack, w.month);
    for (int i = 0; i < 3; i++) {
      UUID employee = w.paidInCdf("Paged" + "ABC".charAt(i), "100000");
      w.standard(employee);
    }
    String run = w.cdfRun();
    String calculation = w.create(run).get("calculationId").asText();
    String path = w.calculationsPath(run) + "/" + calculation;
    JsonNode first = w.c.expect(PayrollRunWorld.get(w.admin, path + "?limit=2"), 200);
    assertThat(first.get("lines")).hasSize(2);
    String cursor = first.get("nextCursor").asText();
    JsonNode second =
        w.c.expect(PayrollRunWorld.get(w.admin, path + "?limit=2&cursor=" + cursor), 200);
    assertThat(second.get("lines")).hasSize(1);
    assertThat(second.get("nextCursor").isNull()).isTrue();
    List<String> numbers = new ArrayList<>();
    first.get("lines").forEach(l -> numbers.add(l.get("employeeNumber").asText()));
    second.get("lines").forEach(l -> numbers.add(l.get("employeeNumber").asText()));
    assertThat(numbers).doesNotHaveDuplicates().isSorted();
    // A cursor of another page size or another calculation is refused.
    w.c.problem(
        PayrollRunWorld.get(w.admin, path + "?limit=1&cursor=" + cursor), 400, "CURSOR_INVALID");

    w.c.problem(
        PayrollRunWorld.get(w.admin, w.calculationsPath(run) + "/" + UUID.randomUUID()),
        404,
        "STATUTORY_CALCULATION_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.calculationsPath(run) + "/not-a-uuid"),
        404,
        "STATUTORY_CALCULATION_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.calculationsPath(UUID.randomUUID()) + "/" + calculation),
        404,
        "STATUTORY_CALCULATION_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.calculationsPath(UUID.randomUUID())),
        404,
        "PAYROLL_RUN_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(w.admin, StatutoryWorld.calculationsPath(UUID.randomUUID(), run)),
        404,
        "LEGAL_ENTITY_NOT_FOUND");
  }
}
