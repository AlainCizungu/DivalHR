package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.operations.TaskWorld.Person;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-081 (D81-14 item 11): a failure while the audit record or the event is written leaves the
 * projection, the revision, the receipt, the actions, the audit trail and the outbox exactly as
 * they were, and the same key succeeds once the failure is gone.
 */
@IntegrationTest
class ExpenseRollbackIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @MockitoSpyBean private OutboxWriter outbox;
  @MockitoSpyBean private AuditRecorder audit;

  private ExpenseWorld x;
  private TaskWorld w;
  private Person ana;
  private Person mina;

  @BeforeEach
  void world() throws Exception {
    x = new ExpenseWorld(mvc, jdbc, lookups);
    w = x.w;
    ana = x.person("Ana", "Mukendi");
    mina = x.person("Mina", "Kabasele");
    x.manage(ana.employee(), mina.employee(), x.today.minusDays(30));
  }

  @AfterEach
  void resetSpies() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
    reset((AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
  }

  private static String key() {
    return Organizations.newKey();
  }

  private int receipts() {
    return w.count(
        "SELECT count(*) FROM operations.expense_receipt WHERE tenant_id = ?", w.tenant());
  }

  private void failOutbox() {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(any(EventEnvelope.class));
  }

  private void failAudit() {
    AuditRecorder target = AopTestUtils.getUltimateTargetObject(audit);
    doThrow(new IllegalStateException("simulated audit failure"))
        .when(target)
        .record(any(AuditEvent.class));
  }

  @Test
  void failureWhileWritingTheReceiptAuditRollsBackTheReceiptAndEverythingElse() throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    List<Integer> before = x.evidence();
    int receiptsBefore = receipts();
    String key = key();
    failAudit();
    var failed =
        w.call(
            ExpenseWorld.upload(
                ana, id, 1, "image/png", Receipts.png(1), ExpenseWorld.FILE_NAME, key));
    assertThat(failed.getStatus()).isGreaterThanOrEqualTo(500);
    resetSpies();
    assertThat(x.evidence()).isEqualTo(before);
    assertThat(receipts()).isEqualTo(receiptsBefore);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
    var detail = x.mine(ana, id);
    assertThat(detail.get("expense").get("version").asInt()).isEqualTo(1);
    assertThat(detail.get("expense").get("receipt").isNull()).isTrue();
    // The same key and bytes then succeed.
    w.expect(
        ExpenseWorld.upload(ana, id, 1, "image/png", Receipts.png(1), ExpenseWorld.FILE_NAME, key),
        200);
    assertThat(receipts()).isEqualTo(receiptsBefore + 1);
  }

  @Test
  void failureWhileWritingTheEventRollsBackSubmissionAndDecisions() throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    List<Integer> before = x.evidence();
    failOutbox();
    var submit =
        w.call(
            ExpenseWorld.post(
                ana, ExpenseWorld.MINE + "/" + id + "/submit", key(), ExpenseWorld.version(1)));
    assertThat(submit.getStatus()).isGreaterThanOrEqualTo(500);
    resetSpies();
    assertThat(x.evidence()).isEqualTo(before);
    assertThat(x.mine(ana, id).get("expense").get("state").asText()).isEqualTo("DRAFT");

    x.submit(ana, id, 1);
    List<Integer> submitted = x.evidence();
    failOutbox();
    var approve =
        w.call(
            ExpenseWorld.post(
                mina,
                ExpenseWorld.APPROVALS + "/" + id + "/approve",
                key(),
                ExpenseWorld.version(2)));
    var ret =
        w.call(
            ExpenseWorld.post(
                mina,
                ExpenseWorld.APPROVALS + "/" + id + "/return",
                key(),
                java.util.Map.of("expectedVersion", 2, "reason", ExpenseWorld.REASON)));
    var withdraw =
        w.call(
            ExpenseWorld.post(
                ana, ExpenseWorld.MINE + "/" + id + "/withdraw", key(), ExpenseWorld.version(2)));
    assertThat(List.of(approve.getStatus(), ret.getStatus(), withdraw.getStatus()))
        .allMatch(s -> s >= 500);
    resetSpies();
    assertThat(x.evidence()).isEqualTo(submitted);
    JsonNodeHolder.state(x.mine(ana, id), "SUBMITTED");
    // Nothing was decided: the manager can still decide it.
    assertThat(x.approve(mina, id, 2).get("state").asText()).isEqualTo("APPROVED");
  }

  /** Small assertion helper kept local to this test. */
  private static final class JsonNodeHolder {
    static void state(com.fasterxml.jackson.databind.JsonNode detail, String expected) {
      assertThat(detail.get("expense").get("state").asText()).isEqualTo(expected);
      assertThat(detail.get("expense").get("version").asInt()).isEqualTo(2);
    }
  }
}
