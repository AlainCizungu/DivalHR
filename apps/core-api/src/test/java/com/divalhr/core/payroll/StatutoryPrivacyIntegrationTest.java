package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.application.StatutoryRecorder;
import com.divalhr.core.payroll.domain.StatutoryEngine;
import com.divalhr.core.payroll.domain.StatutoryLine;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-094B (Issue #179, ADR 0034 section 8): the audit metadata and the outbox events of the
 * statutory preview are an allow-list that never holds an amount, a classification, a flag, a
 * reviewer, a digest or a trace; no amount, employee number, name, flag, reviewer or digest reaches
 * the audit trail, the outbox, the idempotency store, a cursor, a log line or an error. Sentinel
 * amounts make a leak detectable. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
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
      "divalhr.request-limits.subject.payroll-run-read=600",
      "divalhr.request-limits.subject.payroll-policy-preview=600",
      "divalhr.request-limits.subject.payroll-policy-write=600",
      "divalhr.request-limits.subject.payroll-policy-read=600"
    })
class StatutoryPrivacyIntegrationTest {

  private static final String SENTINEL_GROSS = "7391640";

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

  private static Set<String> keys(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private JsonNode json(String text) throws Exception {
    return CompensationWorld.JSON.readTree(text);
  }

  @Test
  void auditMetadataAndEventsAreAnAllowListWithoutAmountsFlagsOrReviewers() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.activate(pack, w.month);
    UUID employee = w.paidInCdf("Kapinga", SENTINEL_GROSS);
    w.standard(employee);
    String run = w.cdfRun();
    w.preview(run);
    JsonNode receipt = w.create(run);
    w.c.expect(PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS), 200);
    w.c.expect(PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "/" + pack), 200);
    w.c.expect(PayrollRunWorld.get(w.admin, w.activationPath() + "?month=" + w.month), 200);
    w.c.expect(PayrollRunWorld.get(w.admin, StatutoryWorld.profilePath(employee)), 200);
    w.c.expect(PayrollRunWorld.get(w.admin, w.calculationsPath(run)), 200);
    w.c.expect(
        PayrollRunWorld.get(
            w.admin, w.calculationsPath(run) + "/" + receipt.get("calculationId").asText()),
        200);

    List<String> actions =
        jdbc.queryForList(
            "SELECT DISTINCT action FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action LIKE 'statutory-%' ORDER BY action",
            String.class, w.tenant);
    assertThat(actions)
        .containsExactly(
            "statutory-activation.create",
            "statutory-activation.read",
            "statutory-calculation.create",
            "statutory-calculation.read",
            "statutory-profile.change",
            "statutory-profile.read",
            "statutory-rule-pack.read");
    List<String> metadata =
        jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action LIKE 'statutory-%'",
            String.class, w.tenant);
    for (String row : metadata) {
      assertThat(StatutoryRecorder.ALLOWED).containsAll(keys(json(row)));
    }
    List<String> events =
        jdbc.queryForList(
            "SELECT event_type || '|' || envelope::text FROM platform.outbox_event"
                + " WHERE tenant_id = ? AND event_type LIKE 'payroll.statutory-%'",
            String.class, w.tenant);
    assertThat(events).hasSize(3);
    for (String row : events) {
      String type = row.substring(0, row.indexOf('|'));
      JsonNode data = json(row.substring(row.indexOf('|') + 1)).get("data");
      assertThat(type)
          .isIn(
              "payroll.statutory-activation.created.v1",
              "payroll.statutory-profile.changed.v1",
              "payroll.statutory-calculation.created.v1");
      assertThat(StatutoryRecorder.ALLOWED).containsAll(keys(data));
    }
    // The allow-list itself names no amount, number, flag, classification, reviewer or digest.
    for (String key : StatutoryRecorder.ALLOWED) {
      assertThat(key.toLowerCase())
          .doesNotContain("amount", "gross", "net", "cost", "digest", "number", "classification")
          .doesNotContain("cnss", "irpp", "reviewer", "trace", "evidence", "flag");
    }
  }

  @Test
  void noAmountNumberNameFlagReviewerOrDigestReachesAnyStoreCursorLogOrError(CapturedOutput output)
      throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.activate(pack, w.month);
    UUID ana = w.paidInCdf("Kapinga-Sentinel", SENTINEL_GROSS);
    w.standard(ana);
    UUID second = w.paidInCdf("Mbuyi-Sentinel", "6203170");
    w.standard(second);
    String run = w.cdfRun();
    JsonNode preview = w.preview(run);
    String number = w.line(preview, ana).get("employeeNumber").asText();
    StatutoryLine expected =
        StatutoryEngine.calculate(
            StatutoryFixtures.seed(), true, true, Long.parseLong(SENTINEL_GROSS));
    JsonNode receipt =
        w.c.expect(
            w.createRequest(
                run, Organizations.newKey(), StatutoryWorld.calculationCommitting(preview)),
            201);
    // Refusals do not echo what they protect.
    JsonNode refused =
        w.c.problem(
            w.createRequest(
                run, Organizations.newKey(), StatutoryWorld.calculationCommitting(preview)),
            409,
            "STATUTORY_VERSION_CONFLICT");
    Map<String, Object> invalid =
        StatutoryWorld.profileCommand(
            w.month.atDay(1), "UNSUPPORTED", true, true, "EMPLOYMENT_CONTRACT");
    JsonNode invalidProfile =
        w.c.problem(w.profilePreviewRequest(ana, invalid), 400, "VALIDATION_FAILED");

    JsonNode page =
        w.c.expect(
            PayrollRunWorld.get(
                w.admin,
                w.calculationsPath(run) + "/" + receipt.get("calculationId").asText() + "?limit=1"),
            200);
    String cursor = page.get("nextCursor").asText();
    String payload =
        new String(Base64.getUrlDecoder().decode(cursor.substring(0, cursor.indexOf('.'))));

    List<String> secrets =
        new ArrayList<>(
            List.of(
                SENTINEL_GROSS,
                "6203170",
                Long.toString(expected.employeeDeductionsMinor()),
                Long.toString(expected.employerContributionsMinor()),
                Long.toString(expected.previewNetMinor()),
                Long.toString(expected.employerCostMinor()),
                number,
                "Kapinga",
                "Mbuyi",
                "Synthetic Test Reviewer",
                "STANDARD_RESIDENT",
                "EMPLOYMENT_CONTRACT"));
    String evidence = w.persistedEvidence();
    assertThat(evidence).isNotBlank();
    for (String secret : secrets) {
      assertThat(evidence).as("persisted evidence " + secret.length()).doesNotContain(secret);
      assertThat(payload).as("cursor").doesNotContain(secret);
      assertThat(refused.toString()).as("error").doesNotContain(secret);
      assertThat(invalidProfile.toString()).as("error").doesNotContain(secret);
      if (secret.length() >= 7) {
        // Shorter numbers could appear by chance among the identifiers and timestamps of the log.
        assertThat(output.getAll()).as("logs").doesNotContain(secret);
      }
    }
    for (String digest :
        List.of("sourceRunDigest", "profileDigest", "rulePackDigest", "calculationDigest")) {
      String value = preview.get(digest).asText();
      assertThat(evidence).as(digest).doesNotContain(value);
      assertThat(output.getAll()).as(digest).doesNotContain(value);
      assertThat(refused.toString()).as(digest).doesNotContain(value);
    }
    assertThat(evidence).doesNotContain(w.admin.substring("Bearer ".length()));
  }
}
