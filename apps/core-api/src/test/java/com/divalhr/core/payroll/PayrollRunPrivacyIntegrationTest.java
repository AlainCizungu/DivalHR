package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PayrollRunWorld.runCommand;
import static com.divalhr.core.payroll.PayrollRunWorld.runCommitting;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
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
 * MVP-094A (Issue #177, D94A-9): the audit metadata and the outbox events of payroll policies and
 * runs are an allow-list that never holds an amount; no amount, employee number, name, digest or
 * subject reaches the audit trail, the outbox, the idempotency store, a cursor, a log line or an
 * error. Sentinel amounts make a leak detectable. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.payroll-run-preview=600",
      "divalhr.request-limits.subject.payroll-run-create=600",
      "divalhr.request-limits.subject.payroll-run-read=600",
      "divalhr.request-limits.subject.payroll-policy-preview=600",
      "divalhr.request-limits.subject.payroll-policy-write=600",
      "divalhr.request-limits.subject.payroll-policy-read=600"
    })
class PayrollRunPrivacyIntegrationTest {

  private static final Set<String> RUN_KEYS =
      Set.of(
          "schemaVersion",
          "legalEntityId",
          "runId",
          "version",
          "period",
          "currency",
          "status",
          "policySequence",
          "employeeCount",
          "blockedCount",
          "supersededRunId");
  private static final Set<String> POLICY_KEYS =
      Set.of("schemaVersion", "legalEntityId", "version", "policySequence");
  private static final Set<String> DISCLOSURE_KEYS =
      Set.of("schemaVersion", "legalEntityId", "view", "page", "resultCount");

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

  private static Set<String> keys(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private JsonNode json(String text) throws Exception {
    return CompensationWorld.JSON.readTree(text);
  }

  @Test
  void auditMetadataAndEventsAreAnAllowListWithoutAmounts() throws Exception {
    w.policy("CALENDAR_DAYS");
    w.paid("Kapinga", w.month.atDay(11), "USD", "7391.64");
    w.runPreview("USD");
    JsonNode receipt = w.createRun("USD");
    w.c.expect(PayrollRunWorld.get(w.admin, w.runsPath()), 200);
    w.c.expect(
        PayrollRunWorld.get(w.admin, w.runsPath() + "/" + receipt.get("runId").asText()), 200);
    w.c.expect(PayrollRunWorld.get(w.admin, w.policyPath()), 200);

    List<String> actions =
        jdbc.queryForList(
            "SELECT DISTINCT action FROM platform.audit_event WHERE tenant_id = ?"
                + " AND (action LIKE 'payroll-policy.%' OR action LIKE 'payroll-run.%')"
                + " ORDER BY action",
            String.class, w.tenant);
    assertThat(actions)
        .containsExactly(
            "payroll-policy.change",
            "payroll-policy.read",
            "payroll-run.create",
            "payroll-run.read");
    List<String> metadata =
        jdbc.queryForList(
            "SELECT action || '|' || metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                + " AND (action LIKE 'payroll-policy.%' OR action LIKE 'payroll-run.%')",
            String.class, w.tenant);
    for (String row : metadata) {
      String action = row.substring(0, row.indexOf('|'));
      Set<String> names = keys(json(row.substring(row.indexOf('|') + 1)));
      switch (action) {
        case "payroll-run.create" -> assertThat(RUN_KEYS).containsAll(names);
        case "payroll-policy.change" -> assertThat(POLICY_KEYS).containsAll(names);
        default -> assertThat(DISCLOSURE_KEYS).containsAll(names);
      }
    }
    List<String> events =
        jdbc.queryForList(
            "SELECT event_type || '|' || envelope::text FROM platform.outbox_event"
                + " WHERE tenant_id = ? AND event_type LIKE 'payroll.payroll-%'",
            String.class, w.tenant);
    assertThat(events).hasSize(2);
    for (String row : events) {
      String type = row.substring(0, row.indexOf('|'));
      JsonNode data = json(row.substring(row.indexOf('|') + 1)).get("data");
      assertThat(type).isIn("payroll.payroll-policy.changed.v1", "payroll.payroll-run.created.v1");
      assertThat(type.equals("payroll.payroll-run.created.v1") ? RUN_KEYS : POLICY_KEYS)
          .containsAll(keys(data));
    }
  }

  @Test
  void noAmountNumberNameDigestOrSubjectReachesAnyStoreCursorLogOrError(CapturedOutput output)
      throws Exception {
    w.policy("CALENDAR_DAYS");
    UUID ana = w.paid("Kapinga-Sentinel", w.month.atDay(11), "USD", "7391.64");
    w.paid("Mbuyi-Sentinel", w.month.atDay(1).minusDays(5), "USD", "6203.17");
    JsonNode preview = w.runPreview("USD");
    String number = w.line(preview, ana).get("employeeNumber").asText();
    String partial = w.line(preview, ana).get("gross").get("amount").asText();
    String total = preview.get("total").get("amount").asText();
    Map<String, Object> command = runCommand(w.month, "USD");
    JsonNode receipt =
        w.c.expect(
            w.runCommitRequest(Organizations.newKey(), runCommitting(command, preview)), 201);
    // Refusals do not echo what they protect.
    JsonNode blocked =
        w.c.problem(
            w.runCommitRequest(Organizations.newKey(), runCommitting(command, preview)),
            409,
            "PAYROLL_RUN_VERSION_CONFLICT");
    JsonNode invalid =
        w.c.problem(w.runPreviewRequest(runCommand(w.month, "7391.64")), 400, "VALIDATION_FAILED");

    JsonNode page =
        w.c.expect(
            PayrollRunWorld.get(
                w.admin, w.runsPath() + "/" + receipt.get("runId").asText() + "?limit=1"),
            200);
    String cursor = page.get("nextCursor").asText();
    String payload =
        new String(Base64.getUrlDecoder().decode(cursor.substring(0, cursor.indexOf('.'))));

    List<String> secrets =
        new ArrayList<>(
            List.of(
                "7391.64",
                "739164",
                "6203.17",
                "620317",
                partial.replace(".", ""),
                partial,
                total,
                number,
                "Kapinga",
                "Mbuyi"));
    String evidence = w.persistedEvidence();
    assertThat(evidence).isNotBlank();
    for (String secret : secrets) {
      assertThat(evidence).as("persisted evidence " + secret.length()).doesNotContain(secret);
      assertThat(payload).as("cursor").doesNotContain(secret);
      assertThat(blocked.toString()).as("error").doesNotContain(secret);
      assertThat(invalid.toString()).as("error").doesNotContain(secret);
      assertThat(output.getAll()).as("logs").doesNotContain(secret);
    }
    assertThat(evidence).doesNotContain(preview.get("inputDigest").asText());
    assertThat(evidence).doesNotContain(preview.get("previewDigest").asText());
    assertThat(output.getAll()).doesNotContain(preview.get("inputDigest").asText());
    assertThat(output.getAll()).doesNotContain(preview.get("previewDigest").asText());
    assertThat(evidence).doesNotContain(w.admin.substring("Bearer ".length()));
  }
}
