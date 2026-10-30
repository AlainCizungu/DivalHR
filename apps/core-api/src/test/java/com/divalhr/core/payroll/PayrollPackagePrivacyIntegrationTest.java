package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.application.PayrollPackageRecorder;
import com.divalhr.core.payroll.domain.StatutoryEngine;
import com.divalhr.core.payroll.domain.StatutoryLine;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-094C (Issue #181, ADR 0035 section 14): the audit metadata and the outbox events of approval,
 * void, package reads and payslip views are an allow-list that never holds an amount, an employee
 * number, a name, a digest, an approver, an explanation or a trace; no sentinel amount, employee
 * number, name, digest, approver subject or void explanation reaches the audit trail, the outbox,
 * the idempotency store, a cursor, a log line or an error, and the approver and the explanation are
 * never returned by a list, a preview or a receipt. Sentinel values make a leak detectable.
 * Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
@PayrollPackageWorld.HighLimits
class PayrollPackagePrivacyIntegrationTest {

  private static final String SENTINEL_GROSS = "7391640";
  private static final String SECOND_GROSS = "6203170";
  private static final String EXPLANATION = "Sentinel explanation zq4 about the statutory profile";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollPackageWorld w;
  private Chain chain;
  private UUID second;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    UUID first = w.s.paidInCdf("Kapinga", SENTINEL_GROSS);
    w.s.standard(first);
    second = w.s.paidInCdf("Mbuyi", SECOND_GROSS);
    w.s.standard(second);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    chain = new Chain(pack, first, run, calculation);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static Set<String> keys(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static JsonNode json(String text) throws Exception {
    return CompensationWorld.JSON.readTree(text);
  }

  @Test
  void auditMetadataAndEventsAreAnAllowListWithoutAmountsNumbersDigestsOrExplanations()
      throws Exception {
    JsonNode receipt = w.approve(chain);
    String pkg = receipt.get("packageId").asText();
    w.c.expect(PayrollRunWorld.get(w.admin, w.packagesPath()), 200);
    w.packageDetail(pkg);
    String slip = w.slipOf(pkg, chain.employee());
    w.slipView(pkg, slip, "fr");
    w.pdf(w.admin, w.slipPdfPath(pkg, slip));
    w.voidPackage(pkg, "OTHER_CONTROLLED", EXPLANATION);

    List<String> actions =
        jdbc.queryForList(
            "SELECT DISTINCT action FROM platform.audit_event WHERE tenant_id = ?"
                + " AND (action LIKE 'payroll-package.%' OR action LIKE 'payslip.%')"
                + " ORDER BY action",
            String.class,
            w.tenant);
    assertThat(actions)
        .containsExactly(
            "payroll-package.approve",
            "payroll-package.read",
            "payroll-package.void",
            "payslip.download",
            "payslip.read");
    List<String> metadata =
        jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                + " AND (action LIKE 'payroll-package.%' OR action LIKE 'payslip.%')",
            String.class,
            w.tenant);
    assertThat(metadata).hasSizeGreaterThanOrEqualTo(7);
    for (String row : metadata) {
      assertThat(PayrollPackageRecorder.ALLOWED).containsAll(keys(json(row)));
    }
    List<String> events =
        jdbc.queryForList(
            "SELECT event_type || '|' || envelope::text FROM platform.outbox_event"
                + " WHERE tenant_id = ? AND event_type LIKE 'payroll.package.%'",
            String.class,
            w.tenant);
    assertThat(events).hasSize(2);
    List<String> types = new ArrayList<>();
    for (String row : events) {
      types.add(row.substring(0, row.indexOf('|')));
      JsonNode data = json(row.substring(row.indexOf('|') + 1)).get("data");
      assertThat(PayrollPackageRecorder.ALLOWED).containsAll(keys(data));
    }
    assertThat(types)
        .containsExactlyInAnyOrder(
            PayrollPackageRecorder.APPROVED_EVENT, PayrollPackageRecorder.VOIDED_EVENT);
    // The allow-list itself names no amount, number, digest, reviewer, approver or explanation.
    for (String key : PayrollPackageRecorder.ALLOWED) {
      assertThat(key.toLowerCase())
          .doesNotContain("amount", "gross", "net", "cost", "digest", "number", "total")
          .doesNotContain("reviewer", "approver", "subject", "explanation", "reason", "trace")
          .doesNotContain("employee", "name", "email", "token");
    }
  }

  @Test
  void noSentinelNumberNameDigestApproverOrExplanationReachesAnyStoreCursorLogOrError(
      CapturedOutput output) throws Exception {
    JsonNode preview = w.preview(chain);
    JsonNode receipt = w.c.expect(w.approveRequest(chain, Organizations.newKey(),
        PayrollPackageWorld.approving(preview)), 201);
    String pkg = receipt.get("packageId").asText();
    // Refusals do not echo what they protect.
    JsonNode refused =
        w.c.problem(
            w.approveRequest(
                chain, Organizations.newKey(), PayrollPackageWorld.approving(preview)),
            409,
            "PAYROLL_PACKAGE_VERSION_CONFLICT");
    JsonNode invalid =
        w.c.problem(
            w.approveRequest(
                chain,
                Organizations.newKey(),
                PayrollPackageWorld.with(PayrollPackageWorld.approving(preview), "reviewedTotals",
                    false)),
            400,
            "VALIDATION_FAILED");

    JsonNode slips = w.c.expect(PayrollRunWorld.get(w.admin, w.slipsPath(pkg) + "?limit=1"), 200);
    String number = slips.get("items").get(0).get("employeeNumber").asText();
    String cursor = slips.get("nextCursor").asText();
    String payload =
        new String(Base64.getUrlDecoder().decode(cursor.substring(0, cursor.indexOf('.'))));
    String slip = w.slipOf(pkg, chain.employee());
    JsonNode view = w.slipView(pkg, slip, "en");
    w.pdf(w.admin, w.slipPdfPath(pkg, slip));
    JsonNode detail = w.packageDetail(pkg);
    JsonNode voidPreview = w.voidPreview(pkg, "OTHER_CONTROLLED", EXPLANATION);
    JsonNode voided =
        w.c.expect(
            w.voidRequest(
                pkg,
                Organizations.newKey(),
                PayrollPackageWorld.voiding(voidPreview, "OTHER_CONTROLLED", EXPLANATION)),
            201);
    JsonNode list = w.c.expect(PayrollRunWorld.get(w.admin, w.packagesPath()), 200);

    StatutoryLine one =
        StatutoryEngine.calculate(
            StatutoryFixtures.seed(), true, true, Long.parseLong(SENTINEL_GROSS));
    StatutoryLine two =
        StatutoryEngine.calculate(
            StatutoryFixtures.seed(), true, true, Long.parseLong(SECOND_GROSS));
    List<String> secrets =
        new ArrayList<>(
            List.of(
                SENTINEL_GROSS,
                SECOND_GROSS,
                Long.toString(one.employeeDeductionsMinor()),
                Long.toString(one.employerContributionsMinor()),
                Long.toString(one.previewNetMinor()),
                Long.toString(two.employeeDeductionsMinor()),
                Long.toString(two.previewNetMinor()),
                Long.toString(one.employeeDeductionsMinor() + two.employeeDeductionsMinor()),
                number,
                "Kapinga",
                "Mbuyi",
                EXPLANATION,
                "zq4",
                "STANDARD_RESIDENT"));
    List<String> digests = new ArrayList<>();
    for (String name :
        List.of(
            "sourceRunDigest",
            "profileDigest",
            "rulePackDigest",
            "calculationDigest",
            "previewDigest")) {
      digests.add(preview.get(name).asText());
    }
    detail.get("evidence").forEach(v -> addDigest(digests, v));
    digests.add(view.get("snapshotDigest").asText());
    digests.add(voidPreview.get("previewDigest").asText());

    String evidence = w.persistedEvidence();
    assertThat(evidence).isNotBlank();
    for (String secret : secrets) {
      assertThat(evidence).as("persisted evidence " + secret.length()).doesNotContain(secret);
      assertThat(payload).as("cursor").doesNotContain(secret);
      assertThat(refused.toString()).as("conflict").doesNotContain(secret);
      assertThat(invalid.toString()).as("invalid").doesNotContain(secret);
      if (secret.length() >= 7) {
        // Shorter numbers could appear by chance among the identifiers and timestamps of the log.
        assertThat(output.getAll()).as("logs").doesNotContain(secret);
      }
    }
    for (String digest : digests) {
      assertThat(digest).hasSize(64);
      assertThat(evidence).as("digest in persisted evidence").doesNotContain(digest);
      assertThat(output.getAll()).as("digest in logs").doesNotContain(digest);
      assertThat(refused.toString()).as("digest in conflict").doesNotContain(digest);
      assertThat(invalid.toString()).as("digest in invalid").doesNotContain(digest);
      assertThat(payload).as("digest in cursor").doesNotContain(digest);
    }
    String subject = PayrollPackageWorld.subjectOf(w.admin);
    assertThat(output.getAll()).doesNotContain(w.admin.substring("Bearer ".length()));
    assertThat(output.getAll()).doesNotContain(EXPLANATION);
    assertThat(evidence).doesNotContain(w.admin.substring("Bearer ".length()));

    // The approver and the explanation are never returned by a receipt, a preview or a list.
    for (JsonNode shown : List.of(receipt, voidPreview, voided, list, preview)) {
      assertThat(shown.toString()).as("approver").doesNotContain(subject);
      assertThat(shown.toString()).as("explanation").doesNotContain(EXPLANATION);
    }
    assertThat(slips.toString()).doesNotContain(subject);
    assertThat(view.toString()).doesNotContain(subject).doesNotContain(EXPLANATION);
    assertThat(detail.toString()).doesNotContain(subject);
    // The restricted explanation of OTHER_CONTROLLED is returned by the detail alone.
    JsonNode after = w.packageDetail(pkg);
    assertThat(after.get("void").get("explanation").asText()).isEqualTo(EXPLANATION);
    assertThat(after.get("package").toString()).doesNotContain(EXPLANATION).doesNotContain(subject);
  }

  private static void addDigest(List<String> digests, JsonNode value) {
    if (value.isTextual() && value.asText().matches("[0-9a-f]{64}")) {
      digests.add(value.asText());
    }
  }

  @Test
  void aCommittedApprovalRecordsNoTraceOrAmountInItsIdempotencyBodyBeyondTheReceipt()
      throws Exception {
    JsonNode receipt = w.approve(chain);
    List<String> bodies =
        jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation = 'payroll-package.approve' AND response_body IS NOT NULL",
            String.class);
    assertThat(bodies).isNotEmpty();
    assertThat(receipt.get("packageId").asText()).isNotBlank();
    for (String body : bodies) {
      assertThat(body)
          .doesNotContain(SENTINEL_GROSS)
          .doesNotContain(SECOND_GROSS)
          .doesNotContain("Kapinga")
          .doesNotContain("Mbuyi")
          .doesNotContain(PayrollPackageWorld.subjectOf(w.admin));
    }
  }
}
