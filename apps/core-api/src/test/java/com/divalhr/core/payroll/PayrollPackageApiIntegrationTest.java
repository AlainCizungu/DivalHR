package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.platform.access.PayrollPaymentGuard;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-094C (Issue #181, ADR 0035) against real PostgreSQL through the public API only: approval
 * previews and commits freeze the exact payroll run, statutory calculation, rule pack and totals;
 * every kind of drift makes a preview stale or blocked with its own stable code; replays are
 * idempotent; a bound source cannot be superseded and can after a void; the void lifecycle, its
 * reason rules and the payment guard; a corrected chain approved after a void while the old package
 * and payslips stay immutable and VOIDED; the arithmetic of every payslip snapshot; EN and FR views
 * with identical amounts; the synthetic flag. Synthetic data only: the packs are {@code
 * SYNTHETIC-TEST-*} (or one {@code REVIEWED-TEST-*} for the non-synthetic assertion) and the clock
 * is frozen on the 15th of the current month.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
class PayrollPackageApiIntegrationTest {

  private static final String EXPLANATION = "The statutory profile was entered for the wrong month";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @MockitoSpyBean private PayrollPaymentGuard payments;

  private PayrollPackageWorld w;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String amount(JsonNode money) {
    return PayrollPackageWorld.amount(money);
  }

  private static List<String> texts(JsonNode array) {
    return PayrollPackageWorld.texts(array);
  }

  // ------------------------------------------------------------------------------------------
  // Preview and commit freeze the sources and the totals
  // ------------------------------------------------------------------------------------------

  @Test
  void approvalFreezesTheExactVersionsDigestsAndTotals() throws Exception {
    Chain chain = w.ready();
    JsonNode preview = w.preview(chain);
    assertThat(preview.get("status").asText()).isEqualTo("READY");
    assertThat(preview.get("period").asText()).isEqualTo(w.month.toString());
    assertThat(preview.get("currency").asText()).isEqualTo("CDF");
    assertThat(texts(preview.get("blockers"))).isEmpty();
    assertThat(texts(preview.get("warnings")))
        .containsExactlyInAnyOrder("SAME_PREPARER_AND_APPROVER", "SYNTHETIC_RULE_PACK");
    assertThat(preview.get("samePreparer").asBoolean()).isTrue();
    assertThat(preview.get("expectedVersion").asInt()).isZero();
    assertThat(preview.get("existingPackage").isNull()).isTrue();
    assertThat(preview.get("runVersion").asInt()).isEqualTo(1);
    assertThat(preview.get("calculationVersion").asInt()).isEqualTo(1);
    assertThat(preview.get("payslipCount").asInt()).isEqualTo(1);
    assertThat(preview.get("excludedCount").asInt()).isZero();
    assertThat(preview.get("pack").get("synthetic").asBoolean()).isTrue();
    assertThat(preview.get("pack").get("packId").asText()).isEqualTo(chain.pack().toString());
    JsonNode totals = preview.get("totals");
    assertThat(amount(totals.get("contractualGross"))).isEqualTo("162000");
    assertThat(amount(totals.get("employeeDeductions"))).isEqualTo("12960");
    assertThat(amount(totals.get("employerContributions"))).isEqualTo("21060");
    assertThat(amount(totals.get("approvedNet"))).isEqualTo("149040");
    assertThat(amount(totals.get("employerCost"))).isEqualTo("183060");
    assertThat(preview.get("lines")).hasSize(1);
    for (String digest :
        List.of(
            "sourceRunDigest",
            "calculationDigest",
            "profileDigest",
            "rulePackDigest",
            "previewDigest")) {
      assertThat(preview.get(digest).asText()).as(digest).matches("[0-9a-f]{64}");
    }
    Instant expiresAt = Instant.parse(preview.get("expiresAt").asText());
    assertThat(expiresAt).isEqualTo(clock.instant().plusSeconds(15 * 60));
    // A preview writes no package, source, payslip or event.
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);

    MockHttpServletResponse response =
        w.c.call(
            w.approveRequest(
                chain, Organizations.newKey(), PayrollPackageWorld.approving(preview)));
    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(response.getHeader("Idempotent-Replayed")).isNull();
    JsonNode receipt = CompensationWorld.JSON.readTree(response.getContentAsString());
    assertThat(receipt.get("version").asInt()).isEqualTo(1);
    assertThat(receipt.get("status").asText()).isEqualTo("APPROVED");
    assertThat(receipt.get("synthetic").asBoolean()).isTrue();
    assertThat(receipt.get("payslipCount").asInt()).isEqualTo(1);
    assertThat(receipt.get("samePreparer").asBoolean()).isTrue();
    assertThat(receipt.get("runId").asText()).isEqualTo(chain.run());
    assertThat(receipt.get("runVersion").asInt()).isEqualTo(1);
    assertThat(receipt.get("calculationId").asText()).isEqualTo(chain.calculation());
    assertThat(receipt.get("calculationVersion").asInt()).isEqualTo(1);
    assertThat(receipt.get("packId").asText()).isEqualTo(chain.pack().toString());
    assertThat(receipt.get("period").asText()).isEqualTo(w.month.toString());
    // The receipt is amount-free.
    assertThat(receipt.toString())
        .doesNotContain("162000")
        .doesNotContain("12960")
        .doesNotContain("149040");

    String id = receipt.get("packageId").asText();
    JsonNode detail = w.packageDetail(id);
    JsonNode summary = detail.get("package");
    assertThat(summary.get("status").asText()).isEqualTo("APPROVED");
    assertThat(summary.get("voidedAt").isNull()).isTrue();
    assertThat(amount(summary.get("totals").get("contractualGross"))).isEqualTo("162000");
    assertThat(amount(summary.get("totals").get("approvedNet"))).isEqualTo("149040");
    assertThat(amount(summary.get("totals").get("employerCost"))).isEqualTo("183060");
    JsonNode evidence = detail.get("evidence");
    assertThat(evidence.get("sourceRunDigest")).isEqualTo(preview.get("sourceRunDigest"));
    assertThat(evidence.get("calculationDigest")).isEqualTo(preview.get("calculationDigest"));
    assertThat(evidence.get("calculationProfileDigest")).isEqualTo(preview.get("profileDigest"));
    assertThat(evidence.get("calculationRulePackDigest")).isEqualTo(preview.get("rulePackDigest"));
    assertThat(evidence.get("approvalDigest").asText()).matches("[0-9a-f]{64}");
    assertThat(evidence.get("reviewedTotals").asBoolean()).isTrue();
    assertThat(evidence.get("reviewedExceptions").asBoolean()).isTrue();
    assertThat(evidence.get("understandsNoPayment").asBoolean()).isTrue();
    assertThat(detail.get("void").isNull()).isTrue();
    // The approver subject is restricted: it is stored and never returned.
    assertThat(detail.toString()).doesNotContain(PayrollPackageWorld.subjectOf(w.admin));
    assertThat(w.approver(id)).isEqualTo(PayrollPackageWorld.subjectOf(w.admin));
    assertThat(w.evidence()).containsExactly(1, 2, 0, 1, 1, 1);
    // Later changes never alter the approved package: a profile change leaves it as it was.
    w.s.profile(chain.employee(), w.month.atDay(1).minusDays(10), "STANDARD_RESIDENT", true, false);
    assertThat(w.packageDetail(id).get("package")).isEqualTo(summary);
  }

  @Test
  void aPreviewWritesNoPackageAndOnlyAnAmountFreeDisclosureAudit() throws Exception {
    Chain chain = w.ready();
    int before = w.audits("payroll-package.read");
    w.preview(chain);
    w.preview(chain);
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);
    assertThat(w.audits("payroll-package.read")).isEqualTo(before + 2);
    List<String> metadata =
        jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action = 'payroll-package.read'",
            String.class,
            w.tenant);
    for (String row : metadata) {
      assertThat(row).doesNotContain("162000").doesNotContain("12960").doesNotContain("digest");
    }
  }

  @Test
  void theApproverIsTheTokenSubjectAndNeverTheRequest() throws Exception {
    Chain chain = w.ready();
    JsonNode preview = w.preview(chain);
    Map<String, Object> body = PayrollPackageWorld.approving(preview);
    for (String field : List.of("approvedBy", "approverSubject", "approvedBySubject", "subject")) {
      w.c.problem(
          w.approveRequest(
              chain, Organizations.newKey(), PayrollPackageWorld.with(body, field, "someone-else")),
          400,
          "VALIDATION_FAILED");
    }
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);
    JsonNode receipt = w.c.expect(w.approveRequest(chain, Organizations.newKey(), body), 201);
    assertThat(w.approver(receipt.get("packageId").asText()))
        .isEqualTo(PayrollPackageWorld.subjectOf(w.admin));
  }

  @Test
  void theSamePreparerFactIsExposedAndAColleagueNeedsAPreviewOfTheirOwn() throws Exception {
    Chain chain = w.ready();
    String colleague = w.colleague();
    JsonNode mine = w.preview(chain);
    JsonNode theirs = w.c.expect(w.previewRequest(colleague, chain), 200);
    assertThat(mine.get("samePreparer").asBoolean()).isTrue();
    assertThat(theirs.get("samePreparer").asBoolean()).isFalse();
    assertThat(texts(theirs.get("warnings"))).doesNotContain("SAME_PREPARER_AND_APPROVER");
    assertThat(theirs.get("previewDigest")).isNotEqualTo(mine.get("previewDigest"));
    // The preview of another administrator, with another fact, is stale for the colleague.
    w.c.problem(
        w.approveRequest(
            colleague, chain, Organizations.newKey(), PayrollPackageWorld.approving(mine)),
        409,
        "PAYROLL_APPROVAL_PREVIEW_STALE");
    JsonNode receipt =
        w.c.expect(
            w.approveRequest(
                colleague, chain, Organizations.newKey(), PayrollPackageWorld.approving(theirs)),
            201);
    assertThat(receipt.get("samePreparer").asBoolean()).isFalse();
    assertThat(w.approver(receipt.get("packageId").asText()))
        .isEqualTo(PayrollPackageWorld.subjectOf(colleague));
    assertThat(
            w.c.count(
                "SELECT count(*) FROM payroll.payroll_package WHERE tenant_id = ?"
                    + " AND same_preparer",
                w.tenant))
        .isZero();
  }

  @Test
  void theThreeConfirmationsAreRequiredAndNothingIsWrittenWithoutThem() throws Exception {
    Chain chain = w.ready();
    Map<String, Object> body = PayrollPackageWorld.approving(w.preview(chain));
    for (String field : List.of("reviewedTotals", "reviewedExceptions", "understandsNoPayment")) {
      w.c.problem(
          w.approveRequest(
              chain, Organizations.newKey(), PayrollPackageWorld.with(body, field, false)),
          400,
          "VALIDATION_FAILED");
      w.c.problem(
          w.approveRequest(
              chain, Organizations.newKey(), PayrollPackageWorld.with(body, field, "true")),
          400,
          "VALIDATION_FAILED");
      Map<String, Object> missing = new java.util.LinkedHashMap<>(body);
      missing.remove(field);
      w.c.problem(
          w.approveRequest(chain, Organizations.newKey(), missing), 400, "VALIDATION_FAILED");
    }
    // A missing idempotency key and a malformed digest are validation failures too.
    w.c.problem(w.approveRequest(chain, null, body), 400, "VALIDATION_FAILED");
    w.c.problem(
        w.approveRequest(
            chain, Organizations.newKey(), PayrollPackageWorld.with(body, "previewDigest", "xyz")),
        400,
        "VALIDATION_FAILED");
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);
  }

  // ------------------------------------------------------------------------------------------
  // Stale previews, in the order of the contract
  // ------------------------------------------------------------------------------------------

  @Test
  void aForgedDigestAnExpiredOrLaterExpiryAndAStaleVersionAreRefusedInTheContractOrder()
      throws Exception {
    Chain chain = w.ready();
    JsonNode preview = w.preview(chain);
    Map<String, Object> body = PayrollPackageWorld.approving(preview);
    for (String field :
        List.of(
            "sourceRunDigest",
            "calculationDigest",
            "profileDigest",
            "rulePackDigest",
            "previewDigest")) {
      w.c.problem(
          w.approveRequest(
              chain, Organizations.newKey(), PayrollPackageWorld.with(body, field, "a".repeat(64))),
          409,
          "PAYROLL_APPROVAL_PREVIEW_STALE");
    }
    w.c.problem(
        w.approveRequest(
            chain,
            Organizations.newKey(),
            PayrollPackageWorld.with(body, "expiresAt", PayrollPackageWorld.later(preview))),
        409,
        "PAYROLL_APPROVAL_PREVIEW_STALE");
    // The chain version is judged before any digest.
    Map<String, Object> forged = PayrollPackageWorld.with(body, "expectedVersion", 3);
    forged = PayrollPackageWorld.with(forged, "previewDigest", "a".repeat(64));
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), forged),
        409,
        "PAYROLL_PACKAGE_VERSION_CONFLICT");
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);

    // Expiry: the preview is current, but its window has closed.
    clock.set(Instant.parse(preview.get("expiresAt").asText()));
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), body),
        409,
        "PAYROLL_APPROVAL_PREVIEW_STALE");
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);
  }

  @Test
  void aProfileChangeAfterThePreviewMakesItStale() throws Exception {
    Chain chain = w.ready();
    JsonNode preview = w.preview(chain);
    w.s.profile(chain.employee(), w.month.atDay(1).minusDays(10), "STANDARD_RESIDENT", true, false);
    JsonNode again = w.preview(chain);
    assertThat(again.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(again.get("blockers"))).contains("PROFILE_CHANGED");
    assertThat(again.get("previewDigest").isNull()).isTrue();
    assertThat(again.get("expiresAt").isNull()).isTrue();
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), PayrollPackageWorld.approving(preview)),
        409,
        "PAYROLL_APPROVAL_PREVIEW_STALE");
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);
  }

  @Test
  void aPolicyChangeAfterThePreviewMakesItStale() throws Exception {
    Chain chain = w.ready();
    JsonNode preview = w.preview(chain);
    w.r.policy(w.month.atDay(1), "LAST_DAY", null, "FIXED_30_DAYS");
    JsonNode again = w.preview(chain);
    assertThat(again.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(again.get("blockers"))).contains("POLICY_CHANGED");
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), PayrollPackageWorld.approving(preview)),
        409,
        "PAYROLL_APPROVAL_PREVIEW_STALE");
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);
  }

  @Test
  void aCompensationChangeAfterTheRunMakesTheSourceStale() throws Exception {
    Chain chain = w.ready();
    JsonNode preview = w.preview(chain);
    w.r.change(chain.employee(), w.month.atDay(16), "CDF", "170000");
    JsonNode again = w.preview(chain);
    assertThat(again.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(again.get("blockers"))).contains("SOURCE_CHANGED");
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), PayrollPackageWorld.approving(preview)),
        409,
        "PAYROLL_APPROVAL_PREVIEW_STALE");
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);
  }

  // ------------------------------------------------------------------------------------------
  // Blockers
  // ------------------------------------------------------------------------------------------

  @Test
  void aSupersededRunAndASupersededCalculationCannotBeApproved() throws Exception {
    Chain chain = w.ready();
    JsonNode stale = w.preview(chain);
    // A profile change and a recalculation supersede the calculation.
    w.s.profile(chain.employee(), w.month.atDay(1).minusDays(10), "STANDARD_RESIDENT", true, false);
    String second = w.recalculate(chain);
    JsonNode oldCalculation = w.preview(chain);
    assertThat(oldCalculation.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(oldCalculation.get("blockers"))).contains("CALCULATION_NOT_CURRENT");
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), PayrollPackageWorld.approving(stale)),
        409,
        "PAYROLL_PACKAGE_BLOCKED");
    // A new employee supersedes the run.
    UUID extra = w.s.paidInCdf("Kalala", "90000");
    w.s.standard(extra);
    w.s.cdfRun();
    JsonNode oldRun = w.preview(new Chain(chain.pack(), chain.employee(), chain.run(), second));
    assertThat(oldRun.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(oldRun.get("blockers"))).contains("SOURCE_RUN_NOT_CURRENT");
    w.c.problem(
        w.approveRequest(
            new Chain(chain.pack(), chain.employee(), chain.run(), second),
            Organizations.newKey(),
            PayrollPackageWorld.approving(oldRun)),
        409,
        "PAYROLL_PACKAGE_BLOCKED");
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);
  }

  @Test
  void aPackThatIsNoLongerVerifiedCannotApprove() throws Exception {
    Chain chain = w.ready();
    JsonNode preview = w.preview(chain);
    w.retire(chain.pack());
    JsonNode again = w.preview(chain);
    assertThat(again.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(again.get("blockers"))).contains("RULE_PACK_NOT_VERIFIED");
    assertThat(again.get("previewDigest").isNull()).isTrue();
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), PayrollPackageWorld.approving(preview)),
        409,
        "PAYROLL_PACKAGE_BLOCKED");
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), PayrollPackageWorld.approving(again)),
        409,
        "PAYROLL_PACKAGE_BLOCKED");
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);
  }

  @Test
  void aSyntheticFlagFollowsTheRulePackCode() throws Exception {
    Chain synthetic = w.ready();
    JsonNode receipt = w.approve(synthetic);
    assertThat(receipt.get("synthetic").asBoolean()).isTrue();
    String slip = w.slipOf(receipt.get("packageId").asText(), synthetic.employee());
    JsonNode view = w.slipView(receipt.get("packageId").asText(), slip, "en");
    assertThat(view.get("synthetic").asBoolean()).isTrue();
    assertThat(view.get("wording").get("syntheticNotice").asText()).contains("test");
    assertThat(view.get("snapshot").get("rulePack").get("code").asText())
        .startsWith("SYNTHETIC-TEST");

    PayrollPackageWorld other = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    Chain real = other.ready(PayrollPackageWorld.verifiedNonSyntheticPack(jdbc));
    JsonNode preview = other.preview(real);
    assertThat(texts(preview.get("warnings"))).doesNotContain("SYNTHETIC_RULE_PACK");
    assertThat(preview.get("pack").get("synthetic").asBoolean()).isFalse();
    JsonNode plain = other.approve(real);
    assertThat(plain.get("synthetic").asBoolean()).isFalse();
    String pkg = plain.get("packageId").asText();
    assertThat(other.packageDetail(pkg).get("package").get("synthetic").asBoolean()).isFalse();
    String realSlip = other.slipOf(pkg, real.employee());
    JsonNode realView = other.slipView(pkg, realSlip, "fr");
    assertThat(realView.get("synthetic").asBoolean()).isFalse();
    assertThat(realView.get("wording").get("syntheticNotice").isNull()).isTrue();
  }

  // ------------------------------------------------------------------------------------------
  // Replays, no change, unknown identifiers
  // ------------------------------------------------------------------------------------------

  @Test
  void aReplayReturnsTheOriginalReceiptAndANewKeyOfTheSameDataIsNoChange() throws Exception {
    Chain chain = w.ready();
    JsonNode preview = w.preview(chain);
    Map<String, Object> body = PayrollPackageWorld.approving(preview);
    String key = Organizations.newKey();
    MockHttpServletResponse first = w.c.call(w.approveRequest(chain, key, body));
    assertThat(first.getStatus()).isEqualTo(201);
    List<Integer> after = w.evidence();
    assertThat(after).containsExactly(1, 2, 0, 1, 1, 1);

    MockHttpServletResponse replay = w.c.call(w.approveRequest(chain, key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    assertThat(w.evidence()).isEqualTo(after);
    // The same key with another payload is refused.
    w.c.problem(
        w.approveRequest(
            chain, key, PayrollPackageWorld.with(body, "previewDigest", "c".repeat(64))),
        409,
        "IDEMPOTENCY_KEY_REUSED");

    // A new key with the same (now outdated) evidence is a version conflict, before anything else.
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), body),
        409,
        "PAYROLL_PACKAGE_VERSION_CONFLICT");
    // A fresh preview of the approved sources is NO_CHANGE and committing it is refused.
    JsonNode again = w.preview(chain);
    assertThat(again.get("status").asText()).isEqualTo("NO_CHANGE");
    assertThat(texts(again.get("blockers"))).isEmpty();
    assertThat(again.get("expectedVersion").asInt()).isEqualTo(1);
    assertThat(again.get("previewDigest").isNull()).isTrue();
    assertThat(again.get("expiresAt").isNull()).isTrue();
    assertThat(again.get("existingPackage").get("packageId").asText())
        .isEqualTo(
            CompensationWorld.JSON.readTree(first.getContentAsString()).get("packageId").asText());
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), PayrollPackageWorld.approving(again)),
        409,
        "PAYROLL_APPROVAL_NO_CHANGE");
    assertThat(w.evidence()).isEqualTo(after);
  }

  @Test
  void unknownAndMalformedIdentifiersAreOneSafeNotFoundEach() throws Exception {
    Chain chain = w.ready();
    JsonNode preview = w.preview(chain);
    Map<String, Object> body = PayrollPackageWorld.approving(preview);
    String base = "/api/v1/legal-entities/";
    String entity = w.entity.toString();
    String runs = "/payroll-runs/";
    String calculations = "/statutory-calculations/";
    for (String id : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
      w.c.problem(
          CompensationWorld.json(
              base
                  + id
                  + runs
                  + chain.run()
                  + calculations
                  + chain.calculation()
                  + "/approval/preview",
              w.admin,
              null,
              Map.of()),
          404,
          "LEGAL_ENTITY_NOT_FOUND");
      w.c.problem(
          CompensationWorld.json(
              base + entity + runs + id + calculations + chain.calculation() + "/approval/preview",
              w.admin,
              null,
              Map.of()),
          404,
          "PAYROLL_RUN_NOT_FOUND");
      w.c.problem(
          CompensationWorld.json(
              base + entity + runs + chain.run() + calculations + id + "/approval/preview",
              w.admin,
              null,
              Map.of()),
          404,
          "STATUTORY_CALCULATION_NOT_FOUND");
      w.c.problem(
          CompensationWorld.json(
              base + entity + runs + chain.run() + calculations + id + "/approval",
              w.admin,
              Organizations.newKey(),
              body),
          404,
          "STATUTORY_CALCULATION_NOT_FOUND");
      w.c.problem(
          PayrollRunWorld.get(w.admin, w.packagePath(id)), 404, "PAYROLL_PACKAGE_NOT_FOUND");
    }
    // A calculation of another run of the same entity is not found through this run.
    UUID extra = w.s.paidInCdf("Kalala", "90000");
    w.s.standard(extra);
    String secondRun = w.s.cdfRun();
    w.c.problem(
        CompensationWorld.json(
            base
                + entity
                + runs
                + secondRun
                + calculations
                + chain.calculation()
                + "/approval/preview",
            w.admin,
            null,
            Map.of()),
        404,
        "STATUTORY_CALCULATION_NOT_FOUND");
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);
  }

  // ------------------------------------------------------------------------------------------
  // The source lock
  // ------------------------------------------------------------------------------------------

  @Test
  void aBoundRunAndCalculationCannotBeSupersededUntilThePackageIsVoided() throws Exception {
    Chain chain = w.ready();
    JsonNode receipt = w.approve(chain);
    String pkg = receipt.get("packageId").asText();
    int runs = w.c.count("SELECT count(*) FROM payroll.pay_run WHERE tenant_id = ?", w.tenant);
    int calculations =
        w.c.count(
            "SELECT count(*) FROM payroll.statutory_calculation WHERE tenant_id = ?", w.tenant);

    // The calculation: a changed profile, then a recalculation.
    w.s.profile(chain.employee(), w.month.atDay(1).minusDays(10), "STANDARD_RESIDENT", true, false);
    JsonNode calculation = w.s.preview(chain.run());
    assertThat(calculation.get("status").asText()).isEqualTo("READY");
    w.c.problem(
        w.s.createRequest(
            chain.run(), Organizations.newKey(), StatutoryWorld.calculationCommitting(calculation)),
        409,
        "PAYROLL_PACKAGE_ALREADY_APPROVED");
    // The run: a new employee, then a new run version.
    UUID extra = w.s.paidInCdf("Kalala", "90000");
    w.s.standard(extra);
    Map<String, Object> command = PayrollRunWorld.runCommand(w.month, "CDF");
    JsonNode run = w.c.expect(w.r.runPreviewRequest(command), 200);
    w.c.problem(
        w.r.runCommitRequest(Organizations.newKey(), PayrollRunWorld.runCommitting(command, run)),
        409,
        "PAYROLL_PACKAGE_ALREADY_APPROVED");
    assertThat(w.c.count("SELECT count(*) FROM payroll.pay_run WHERE tenant_id = ?", w.tenant))
        .isEqualTo(runs);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM payroll.statutory_calculation WHERE tenant_id = ?", w.tenant))
        .isEqualTo(calculations);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM payroll.pay_run WHERE tenant_id = ? AND status = 'DRAFT'",
                w.tenant))
        .isEqualTo(1);
    assertThat(w.packageDetail(pkg).get("package").get("status").asText()).isEqualTo("APPROVED");

    // After the void both supersessions proceed (the binding rows stay as evidence).
    w.voidPackage(pkg, "SOURCE_CORRECTION", null);
    JsonNode freshCalculation = w.s.preview(chain.run());
    assertThat(freshCalculation.get("status").asText()).isEqualTo("READY");
    w.c.expect(
        w.s.createRequest(
            chain.run(),
            Organizations.newKey(),
            StatutoryWorld.calculationCommitting(freshCalculation)),
        201);
    JsonNode freshRun = w.c.expect(w.r.runPreviewRequest(command), 200);
    w.c.expect(
        w.r.runCommitRequest(
            Organizations.newKey(), PayrollRunWorld.runCommitting(command, freshRun)),
        201);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM payroll.payroll_package_source WHERE tenant_id = ?",
                w.tenant))
        .isEqualTo(2);
  }

  // ------------------------------------------------------------------------------------------
  // Void
  // ------------------------------------------------------------------------------------------

  @Test
  void theReasonRulesAreValidatedWithoutEchoingTheExplanation() throws Exception {
    JsonNode receipt = w.approve(w.ready());
    String pkg = receipt.get("packageId").asText();
    String secret = "Sentinel explanation of the administrator";
    // Required for OTHER_CONTROLLED only; 10 to 500 characters, trimmed, no control character.
    List<Map<String, Object>> invalid =
        List.of(
            PayrollPackageWorld.voidCommand("OTHER_CONTROLLED", null),
            PayrollPackageWorld.voidCommand("OTHER_CONTROLLED", "too short"),
            PayrollPackageWorld.voidCommand("OTHER_CONTROLLED", "x".repeat(501)),
            PayrollPackageWorld.voidCommand("OTHER_CONTROLLED", " " + secret),
            PayrollPackageWorld.voidCommand("OTHER_CONTROLLED", secret + "\n" + secret),
            PayrollPackageWorld.voidCommand("OTHER_CONTROLLED", 12),
            PayrollPackageWorld.voidCommand("SOURCE_CORRECTION", secret),
            PayrollPackageWorld.voidCommand("ADMINISTRATIVE_ERROR", secret),
            PayrollPackageWorld.voidCommand("MISTAKE", null),
            PayrollPackageWorld.voidCommand(null, null),
            PayrollPackageWorld.voidCommand("source_correction", null));
    for (Map<String, Object> command : invalid) {
      JsonNode problem = w.c.problem(w.voidPreviewRequest(pkg, command), 400, "VALIDATION_FAILED");
      assertThat(problem.toString()).doesNotContain(secret);
    }
    for (String reason :
        List.of(
            "SOURCE_CORRECTION",
            "PROFILE_CORRECTION",
            "RULE_PACK_CORRECTION",
            "ADMINISTRATIVE_ERROR")) {
      JsonNode preview = w.voidPreview(pkg, reason, null);
      assertThat(preview.get("status").asText()).isEqualTo("READY");
      assertThat(preview.get("reasonCode").asText()).isEqualTo(reason);
    }
    assertThat(w.voidPreview(pkg, "OTHER_CONTROLLED", EXPLANATION).get("status").asText())
        .isEqualTo("READY");
    // The explanation is not part of a preview response.
    assertThat(w.voidPreview(pkg, "OTHER_CONTROLLED", EXPLANATION).toString())
        .doesNotContain(EXPLANATION);
    // A preview writes nothing, and the acknowledgement and unknown fields are validated.
    JsonNode preview = w.voidPreview(pkg, "SOURCE_CORRECTION", null);
    Map<String, Object> body = PayrollPackageWorld.voiding(preview, "SOURCE_CORRECTION", null);
    w.c.problem(
        w.voidRequest(
            pkg,
            Organizations.newKey(),
            PayrollPackageWorld.with(body, "noPaymentAcknowledged", false)),
        400,
        "VALIDATION_FAILED");
    w.c.problem(
        w.voidRequest(
            pkg, Organizations.newKey(), PayrollPackageWorld.with(body, "voidedBy", "me")),
        400,
        "VALIDATION_FAILED");
    w.c.problem(w.voidRequest(pkg, null, body), 400, "VALIDATION_FAILED");
    assertThat(w.evidence().get(2)).isZero();
  }

  @Test
  void aVoidIsPreviewedStaleCheckedRecordedAndReplayed() throws Exception {
    Chain chain = w.ready();
    String pkg = w.approve(chain).get("packageId").asText();
    JsonNode before = w.packageDetail(pkg);
    JsonNode preview = w.voidPreview(pkg, "OTHER_CONTROLLED", EXPLANATION);
    assertThat(preview.get("packageVersion").asInt()).isEqualTo(1);
    assertThat(preview.get("packageStatus").asText()).isEqualTo("APPROVED");
    assertThat(texts(preview.get("blockers"))).isEmpty();
    assertThat(preview.get("approvalDigest"))
        .isEqualTo(before.get("evidence").get("approvalDigest"));
    assertThat(Instant.parse(preview.get("expiresAt").asText()))
        .isEqualTo(clock.instant().plusSeconds(15 * 60));
    Map<String, Object> body =
        PayrollPackageWorld.voiding(preview, "OTHER_CONTROLLED", EXPLANATION);

    // Precedence: a different version or approval digest first, then the preview itself.
    w.c.problem(
        w.voidRequest(
            pkg,
            Organizations.newKey(),
            PayrollPackageWorld.with(
                PayrollPackageWorld.with(body, "packageVersion", 2),
                "previewDigest",
                "a".repeat(64))),
        409,
        "PAYROLL_PACKAGE_VERSION_CONFLICT");
    w.c.problem(
        w.voidRequest(
            pkg,
            Organizations.newKey(),
            PayrollPackageWorld.with(body, "approvalDigest", "b".repeat(64))),
        409,
        "PAYROLL_PACKAGE_VERSION_CONFLICT");
    for (Map<String, Object> stale :
        List.of(
            PayrollPackageWorld.with(body, "previewDigest", "a".repeat(64)),
            PayrollPackageWorld.with(body, "explanation", EXPLANATION + " changed"),
            PayrollPackageWorld.with(body, "reasonCode", "SOURCE_CORRECTION"),
            PayrollPackageWorld.with(body, "expiresAt", PayrollPackageWorld.later(preview)))) {
      w.c.problem(
          w.voidRequest(pkg, Organizations.newKey(), stale), 409, "PAYROLL_VOID_PREVIEW_STALE");
    }
    assertThat(w.evidence().get(2)).isZero();
    assertThat(w.packageDetail(pkg).get("package").get("status").asText()).isEqualTo("APPROVED");

    String key = Organizations.newKey();
    MockHttpServletResponse first = w.c.call(w.voidRequest(pkg, key, body));
    assertThat(first.getStatus()).isEqualTo(201);
    JsonNode receipt = CompensationWorld.JSON.readTree(first.getContentAsString());
    assertThat(receipt.get("status").asText()).isEqualTo("VOIDED");
    assertThat(receipt.get("reasonCode").asText()).isEqualTo("OTHER_CONTROLLED");
    assertThat(receipt.get("payslipCount").asInt()).isEqualTo(1);
    assertThat(receipt.get("version").asInt()).isEqualTo(1);
    // The receipt carries neither the explanation nor an amount.
    assertThat(receipt.toString()).doesNotContain(EXPLANATION).doesNotContain("162000");
    List<Integer> after = w.evidence();
    assertThat(after).containsExactly(1, 2, 1, 1, 2, 2);

    MockHttpServletResponse replay = w.c.call(w.voidRequest(pkg, key, body));
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    w.c.problem(
        w.voidRequest(pkg, key, PayrollPackageWorld.with(body, "previewDigest", "d".repeat(64))),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    assertThat(w.evidence()).isEqualTo(after);

    // A new key is refused: the package is voided, and a preview says so.
    w.c.problem(w.voidRequest(pkg, Organizations.newKey(), body), 409, "PAYROLL_PACKAGE_VOIDED");
    JsonNode again = w.voidPreview(pkg, "OTHER_CONTROLLED", EXPLANATION);
    assertThat(again.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(again.get("blockers"))).containsExactly("ALREADY_VOIDED");
    assertThat(again.get("previewDigest").isNull()).isTrue();
    assertThat(w.evidence()).isEqualTo(after);

    // Only the administrator's package detail shows the explanation, and the lifecycle is kept.
    JsonNode detail = w.packageDetail(pkg);
    assertThat(detail.get("package").get("status").asText()).isEqualTo("VOIDED");
    assertThat(detail.get("package").get("voidReasonCode").asText()).isEqualTo("OTHER_CONTROLLED");
    assertThat(detail.get("package").get("voidedAt").isNull()).isFalse();
    assertThat(detail.get("void").get("reasonCode").asText()).isEqualTo("OTHER_CONTROLLED");
    assertThat(detail.get("void").get("explanation").asText()).isEqualTo(EXPLANATION);
    assertThat(detail.get("package").get("totals")).isEqualTo(before.get("package").get("totals"));
    assertThat(detail.get("evidence")).isEqualTo(before.get("evidence"));
    JsonNode list =
        w.c.expect(PayrollRunWorld.get(w.admin, w.packagesPath() + "?status=VOIDED"), 200);
    assertThat(list.get("items")).hasSize(1);
    assertThat(list.toString()).doesNotContain(EXPLANATION);
    assertThat(
            w.c
                .expect(PayrollRunWorld.get(w.admin, w.packagesPath() + "?status=APPROVED"), 200)
                .get("items"))
        .isEmpty();
  }

  @Test
  void aVoidKeepsEveryPayslipSnapshotAndDigestAndShowsThemVoided() throws Exception {
    Chain chain = w.ready();
    String pkg = w.approve(chain).get("packageId").asText();
    String slip = w.slipOf(pkg, chain.employee());
    JsonNode approved = w.slipView(pkg, slip, "en");
    String text =
        jdbc.queryForObject(
            "SELECT snapshot_text FROM payroll.payroll_payslip WHERE id = ?",
            String.class,
            UUID.fromString(slip));
    assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
    assertThat(approved.get("voidReasonCode").isNull()).isTrue();
    assertThat(approved.get("wording").get("voidedNotice").isNull()).isTrue();

    w.voidPackage(pkg, "OTHER_CONTROLLED", EXPLANATION);

    assertThat(
            jdbc.queryForObject(
                "SELECT snapshot_text FROM payroll.payroll_payslip WHERE id = ?",
                String.class,
                UUID.fromString(slip)))
        .isEqualTo(text);
    for (String locale : List.of("en", "fr")) {
      JsonNode voided = w.slipView(pkg, slip, locale);
      assertThat(voided.get("status").asText()).isEqualTo("VOIDED");
      assertThat(voided.get("voidReasonCode").asText()).isEqualTo("OTHER_CONTROLLED");
      assertThat(voided.get("voidedAt").isNull()).isFalse();
      assertThat(voided.get("snapshotDigest")).isEqualTo(approved.get("snapshotDigest"));
      // The snapshot keeps its status at publication; the amounts are untouched.
      assertThat(voided.get("snapshot").get("status").asText()).isEqualTo("APPROVED");
      assertThat(voided.get("snapshot").get("approvedAt"))
          .isEqualTo(approved.get("snapshot").get("approvedAt"));
      assertThat(amount(voided.get("snapshot").get("approvedNet"))).isEqualTo("149040");
      assertThat(voided.get("wording").get("voidedNotice").asText()).isNotBlank();
      assertThat(voided.toString()).doesNotContain(EXPLANATION);
    }
    assertThat(w.slipView(pkg, slip, "en").get("wording").get("statusLabel").asText())
        .isEqualTo("VOIDED");
    assertThat(w.slipView(pkg, slip, "fr").get("wording").get("statusLabel").asText())
        .isEqualTo("ANNULÉ");
  }

  @Test
  void theFuturePaymentGuardBlocksAVoidWithoutChangingAnything() throws Exception {
    String pkg = w.approve(w.ready()).get("packageId").asText();
    doReturn(true).when(payments).paymentExists(any(), any(), any());
    JsonNode preview = w.voidPreview(pkg, "ADMINISTRATIVE_ERROR", null);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(preview.get("blockers"))).containsExactly("PAYMENT_EXISTS");
    assertThat(preview.get("previewDigest").isNull()).isTrue();
    List<Integer> before = w.evidence();
    w.c.problem(
        w.voidRequest(
            pkg,
            Organizations.newKey(),
            PayrollPackageWorld.voiding(preview, "ADMINISTRATIVE_ERROR", null)),
        409,
        "PAYROLL_VOID_NOT_ALLOWED");
    assertThat(w.evidence()).isEqualTo(before);
    assertThat(w.packageDetail(pkg).get("package").get("status").asText()).isEqualTo("APPROVED");
    // The port is the only reason: without a payment record the same package voids.
    doReturn(false).when(payments).paymentExists(any(), any(), any());
    w.voidPackage(pkg, "ADMINISTRATIVE_ERROR", null);
    assertThat(w.packageDetail(pkg).get("package").get("status").asText()).isEqualTo("VOIDED");
  }

  // ------------------------------------------------------------------------------------------
  // A corrected chain after a void
  // ------------------------------------------------------------------------------------------

  @Test
  void aCorrectedChainIsApprovedAfterAVoidWhileTheOldPackageStaysImmutableAndVoided()
      throws Exception {
    Chain chain = w.ready();
    JsonNode first = w.approve(chain);
    String oldPackage = first.get("packageId").asText();
    String oldSlip = w.slipOf(oldPackage, chain.employee());
    JsonNode oldView = w.slipView(oldPackage, oldSlip, "en");
    JsonNode oldDetail = w.packageDetail(oldPackage);
    String oldText =
        jdbc.queryForObject(
            "SELECT snapshot_text FROM payroll.payroll_payslip WHERE id = ?",
            String.class,
            UUID.fromString(oldSlip));

    w.voidPackage(oldPackage, "PROFILE_CORRECTION", null);
    // The voided (run, calculation) pair can never be approved again.
    JsonNode voidedPair = w.preview(chain);
    assertThat(voidedPair.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(voidedPair.get("blockers"))).contains("SOURCE_ALREADY_VOIDED");
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), PayrollPackageWorld.approving(voidedPair)),
        409,
        "PAYROLL_PACKAGE_BLOCKED");

    // The corrected chain: the profile is corrected and the run is calculated again.
    w.advance(3600);
    w.s.profile(chain.employee(), w.month.atDay(1).minusDays(10), "STANDARD_RESIDENT", true, false);
    Chain corrected = new Chain(chain.pack(), chain.employee(), chain.run(), w.recalculate(chain));
    JsonNode preview = w.preview(corrected);
    assertThat(preview.get("status").asText()).isEqualTo("READY");
    assertThat(preview.get("expectedVersion").asInt()).isEqualTo(1);
    assertThat(amount(preview.get("totals").get("employeeDeductions"))).isEqualTo("8100");
    JsonNode second =
        w.c.expect(
            w.approveRequest(
                corrected, Organizations.newKey(), PayrollPackageWorld.approving(preview)),
            201);
    assertThat(second.get("version").asInt()).isEqualTo(2);
    assertThat(second.get("status").asText()).isEqualTo("APPROVED");
    String newPackage = second.get("packageId").asText();

    // The old package and its payslip are exactly as they were, and now visibly VOIDED.
    JsonNode oldAfter = w.packageDetail(oldPackage);
    assertThat(oldAfter.get("package").get("status").asText()).isEqualTo("VOIDED");
    assertThat(oldAfter.get("package").get("totals"))
        .isEqualTo(oldDetail.get("package").get("totals"));
    assertThat(oldAfter.get("evidence")).isEqualTo(oldDetail.get("evidence"));
    assertThat(
            jdbc.queryForObject(
                "SELECT snapshot_text FROM payroll.payroll_payslip WHERE id = ?",
                String.class,
                UUID.fromString(oldSlip)))
        .isEqualTo(oldText);
    JsonNode oldVoided = w.slipView(oldPackage, oldSlip, "en");
    assertThat(oldVoided.get("status").asText()).isEqualTo("VOIDED");
    assertThat(oldVoided.get("snapshotDigest")).isEqualTo(oldView.get("snapshotDigest"));
    assertThat(amount(oldVoided.get("snapshot").get("employeeDeductions"))).isEqualTo("12960");

    // The new payslip has the corrected amounts and its own digest.
    String newSlip = w.slipOf(newPackage, chain.employee());
    JsonNode newView = w.slipView(newPackage, newSlip, "en");
    assertThat(newView.get("status").asText()).isEqualTo("APPROVED");
    assertThat(amount(newView.get("snapshot").get("employeeDeductions"))).isEqualTo("8100");
    assertThat(newView.get("snapshotDigest")).isNotEqualTo(oldView.get("snapshotDigest"));

    // Newest package first, both kept; exactly one is approved.
    JsonNode list = w.c.expect(PayrollRunWorld.get(w.admin, w.packagesPath()), 200);
    assertThat(list.get("items")).hasSize(2);
    assertThat(list.get("items").get(0).get("version").asInt()).isEqualTo(2);
    assertThat(list.get("items").get(0).get("status").asText()).isEqualTo("APPROVED");
    assertThat(list.get("items").get(1).get("status").asText()).isEqualTo("VOIDED");

    // The old pair stays refused, and now another package of the month is approved.
    JsonNode oldPair = w.preview(chain);
    assertThat(oldPair.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(texts(oldPair.get("blockers")))
        .contains("SOURCE_ALREADY_VOIDED", "ALREADY_APPROVED");
    w.c.problem(
        w.approveRequest(chain, Organizations.newKey(), PayrollPackageWorld.approving(oldPair)),
        409,
        "PAYROLL_PACKAGE_ALREADY_APPROVED");
    assertThat(w.evidence().get(0)).isEqualTo(2);
  }

  // ------------------------------------------------------------------------------------------
  // Snapshots
  // ------------------------------------------------------------------------------------------

  @Test
  void everySnapshotEqualsItsSourceLineAndEmployerContributionsNeverReduceTheNet()
      throws Exception {
    Chain chain = w.readyWithThree();
    JsonNode detail = w.calculationDetail(chain);
    JsonNode receipt = w.approve(chain);
    String pkg = receipt.get("packageId").asText();
    assertThat(receipt.get("payslipCount").asInt()).isEqualTo(3);
    JsonNode page = w.c.expect(PayrollRunWorld.get(w.admin, w.slipsPath(pkg)), 200);
    assertThat(page.get("items")).hasSize(3);
    long gross = 0;
    long deductions = 0;
    long employer = 0;
    long net = 0;
    int excluded = 0;
    for (JsonNode item : page.get("items")) {
      UUID employee = UUID.fromString(item.get("employeeId").asText());
      JsonNode source = PayrollPackageWorld.lineOf(detail, employee);
      JsonNode view = w.slipView(pkg, item.get("payslipId").asText(), "en");
      JsonNode snapshot = view.get("snapshot");
      assertThat(snapshot.get("employeeNumber")).isEqualTo(source.get("employeeNumber"));
      assertThat(snapshot.get("excluded")).isEqualTo(source.get("excluded"));
      assertThat(amount(snapshot.get("contractualGross")))
          .isEqualTo(amount(source.get("contractualGross")));
      assertThat(amount(snapshot.get("employeeDeductions")))
          .isEqualTo(amount(source.get("employeeDeductions")));
      assertThat(amount(snapshot.get("employerContributions")))
          .isEqualTo(amount(source.get("employerContributions")));
      assertThat(amount(snapshot.get("approvedNet"))).isEqualTo(amount(source.get("previewNet")));
      long g = Long.parseLong(amount(snapshot.get("contractualGross")));
      long cnss = Long.parseLong(amount(snapshot.get("employeeCnss")));
      long irpp = Long.parseLong(amount(snapshot.get("irppWithholding")));
      long d = Long.parseLong(amount(snapshot.get("employeeDeductions")));
      long e = Long.parseLong(amount(snapshot.get("employerContributions")));
      long n = Long.parseLong(amount(snapshot.get("approvedNet")));
      assertThat(cnss + irpp).isEqualTo(d);
      // The net is the gross less the employee deductions only: employer contributions are
      // shown separately and never reduce it.
      assertThat(n).isEqualTo(g - d);
      long employerComponents = 0;
      for (JsonNode component : snapshot.get("components")) {
        if ("EMPLOYER".equals(component.get("party").asText())) {
          employerComponents += Long.parseLong(amount(component.get("amount")));
        }
      }
      assertThat(employerComponents).isEqualTo(e);
      assertThat(snapshot.get("components")).hasSameSizeAs(source.get("components"));
      if (snapshot.get("excluded").asBoolean()) {
        excluded++;
        assertThat(d).isZero();
        assertThat(e).isZero();
        assertThat(snapshot.get("components")).isEmpty();
      }
      gross += g;
      deductions += d;
      employer += e;
      net += n;
    }
    assertThat(excluded).isEqualTo(1);
    JsonNode totals = w.packageDetail(pkg).get("package").get("totals");
    assertThat(amount(totals.get("contractualGross"))).isEqualTo(Long.toString(gross));
    assertThat(amount(totals.get("employeeDeductions"))).isEqualTo(Long.toString(deductions));
    assertThat(amount(totals.get("employerContributions"))).isEqualTo(Long.toString(employer));
    assertThat(amount(totals.get("approvedNet"))).isEqualTo(Long.toString(net));
    assertThat(amount(totals.get("employerCost"))).isEqualTo(Long.toString(gross + employer));
    // The exact vector of the reference employee.
    JsonNode reference = w.slipView(pkg, w.slipOf(pkg, chain.employee()), "en").get("snapshot");
    assertThat(amount(reference.get("employeeCnss"))).isEqualTo("8100");
    assertThat(amount(reference.get("irppWithholding"))).isEqualTo("4860");
    assertThat(amount(reference.get("employeeDeductions"))).isEqualTo("12960");
    assertThat(amount(reference.get("employerContributions"))).isEqualTo("21060");
    assertThat(amount(reference.get("approvedNet"))).isEqualTo("149040");
    assertThat(reference.get("explanationCodes")).isNotEmpty();
  }

  @Test
  void theEnglishAndFrenchViewsShowIdenticalAmountsAndTheSameDigest() throws Exception {
    Chain chain = w.ready();
    String pkg = w.approve(chain).get("packageId").asText();
    String slip = w.slipOf(pkg, chain.employee());
    JsonNode en = w.slipView(pkg, slip, "en");
    JsonNode fr = w.slipView(pkg, slip, "fr");
    assertThat(en.get("wording").get("locale").asText()).isEqualTo("en");
    assertThat(fr.get("wording").get("locale").asText()).isEqualTo("fr");
    assertThat(en.get("snapshot")).isEqualTo(fr.get("snapshot"));
    assertThat(en.get("snapshotDigest")).isEqualTo(fr.get("snapshotDigest"));
    assertThat(en.get("snapshotDigest").asText()).matches("[0-9a-f]{64}");
    assertThat(en.get("wording").get("title").asText()).isEqualTo("Payslip");
    assertThat(fr.get("wording").get("title").asText()).isEqualTo("Bulletin de paie");
    assertThat(en.get("wording").get("statusLabel").asText()).isEqualTo("Approved payroll");
    assertThat(fr.get("wording").get("statusLabel").asText()).isEqualTo("Paie approuvée");
    assertThat(en.get("wording").get("notProofOfPayment").asText())
        .isEqualTo("Not proof of payment");
    assertThat(fr.get("wording").get("notProofOfPayment").asText())
        .isEqualTo("Ne constitue pas une preuve de paiement");
    assertThat(en.get("wording").get("employerContributionsNote").asText())
        .contains("do not reduce the employee's net");
    assertThat(fr.get("wording").get("employerContributionsNote").asText())
        .contains("ne réduisent pas le net du salarié");
    String pack = en.get("snapshot").get("rulePack").get("code").asText();
    assertThat(en.get("wording").get("rulePackStatement").asText())
        .isEqualTo("Statutory calculation based on rule pack " + pack + " v1");
    assertThat(fr.get("wording").get("rulePackStatement").asText())
        .isEqualTo("Calcul statutaire fondé sur le jeu de règles " + pack + " v1");
    for (JsonNode view : List.of(en, fr)) {
      String words = view.get("wording").toString().toLowerCase(java.util.Locale.ROOT);
      assertThat(words)
          .doesNotContain("paid")
          .doesNotContain("payé")
          .doesNotContain("filed")
          .doesNotContain("compliant")
          .doesNotContain("conforme");
    }
    // The locale is chosen by the query, else by Accept-Language, else English; others are refused.
    String path = w.slipPath(pkg, slip);
    assertThat(
            w.c
                .expect(
                    PayrollRunWorld.get(w.admin, path).header("Accept-Language", "fr-CA,fr;q=0.9"),
                    200)
                .get("wording")
                .get("locale")
                .asText())
        .isEqualTo("fr");
    assertThat(
            w.c
                .expect(
                    PayrollRunWorld.get(w.admin, path).header("Accept-Language", "de, en;q=0.5"),
                    200)
                .get("wording")
                .get("locale")
                .asText())
        .isEqualTo("en");
    assertThat(
            w.c
                .expect(PayrollRunWorld.get(w.admin, path), 200)
                .get("wording")
                .get("locale")
                .asText())
        .isEqualTo("en");
    assertThat(
            w.c
                .expect(
                    PayrollRunWorld.get(w.admin, path + "?locale=fr")
                        .header("Accept-Language", "en"),
                    200)
                .get("wording")
                .get("locale")
                .asText())
        .isEqualTo("fr");
    w.c.problem(PayrollRunWorld.get(w.admin, path + "?locale=de"), 400, "LOCALE_NOT_SUPPORTED");
    w.c.problem(PayrollRunWorld.get(w.admin, path + "?locale=EN"), 400, "LOCALE_NOT_SUPPORTED");
  }

  // ------------------------------------------------------------------------------------------
  // Package reads
  // ------------------------------------------------------------------------------------------

  @Test
  void packagesAndPayslipsArePagedByKeysetAndValidated() throws Exception {
    Chain chain = w.readyWithThree();
    String pkg = w.approve(chain).get("packageId").asText();
    // Payslips by employee number in byte order, two at a time.
    JsonNode all = w.c.expect(PayrollRunWorld.get(w.admin, w.slipsPath(pkg)), 200);
    List<String> numbers = new java.util.ArrayList<>();
    all.get("items").forEach(item -> numbers.add(item.get("employeeNumber").asText()));
    assertThat(numbers).isSorted().hasSize(3);
    MockHttpServletResponse response =
        w.c.call(PayrollRunWorld.get(w.admin, w.slipsPath(pkg) + "?limit=2"));
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode page = CompensationWorld.JSON.readTree(response.getContentAsString());
    assertThat(page.get("items")).hasSize(2);
    String cursor = page.get("nextCursor").asText();
    JsonNode second =
        w.c.expect(
            PayrollRunWorld.get(w.admin, w.slipsPath(pkg) + "?limit=2&cursor=" + cursor), 200);
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("items").get(0).get("employeeNumber").asText()).isEqualTo(numbers.get(2));
    assertThat(second.get("nextCursor").isNull()).isTrue();
    // The cursor is bound to its page size and to its package.
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.slipsPath(pkg) + "?limit=1&cursor=" + cursor),
        400,
        "CURSOR_INVALID");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.slipsPath(pkg) + "?cursor=garbage"), 400, "CURSOR_INVALID");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.slipsPath(pkg) + "?limit=0"), 400, "VALIDATION_FAILED");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.slipsPath(pkg) + "?limit=201"), 400, "VALIDATION_FAILED");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.slipPath(pkg, UUID.randomUUID())), 404, "PAYSLIP_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.slipPath(pkg, "not-a-uuid")), 404, "PAYSLIP_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.slipsPath(UUID.randomUUID())),
        404,
        "PAYROLL_PACKAGE_NOT_FOUND");

    // The package list: filters, limits and the cursor bound to the filter.
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.packagesPath() + "?status=PAID"), 400, "VALIDATION_FAILED");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.packagesPath() + "?limit=101"), 400, "VALIDATION_FAILED");
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.packagesPath() + "?cursor=garbage"), 400, "CURSOR_INVALID");
    w.voidPackage(pkg, "ADMINISTRATIVE_ERROR", null);
    w.advance(3600);
    w.s.profile(chain.employee(), w.month.atDay(1).minusDays(10), "STANDARD_RESIDENT", true, false);
    Chain corrected = new Chain(chain.pack(), chain.employee(), chain.run(), w.recalculate(chain));
    w.approve(corrected);
    JsonNode firstPage =
        w.c.expect(PayrollRunWorld.get(w.admin, w.packagesPath() + "?limit=1"), 200);
    assertThat(firstPage.get("items")).hasSize(1);
    assertThat(firstPage.get("items").get(0).get("version").asInt()).isEqualTo(2);
    String next = firstPage.get("nextCursor").asText();
    JsonNode secondPage =
        w.c.expect(PayrollRunWorld.get(w.admin, w.packagesPath() + "?limit=1&cursor=" + next), 200);
    assertThat(secondPage.get("items").get(0).get("version").asInt()).isEqualTo(1);
    assertThat(secondPage.get("nextCursor").isNull()).isTrue();
    w.c.problem(
        PayrollRunWorld.get(w.admin, w.packagesPath() + "?status=VOIDED&limit=1&cursor=" + next),
        400,
        "CURSOR_INVALID");
  }
}
