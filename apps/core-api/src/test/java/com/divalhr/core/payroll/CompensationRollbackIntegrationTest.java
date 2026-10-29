package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.CompensationWorld.command;
import static com.divalhr.core.payroll.CompensationWorld.commitRequest;
import static com.divalhr.core.payroll.CompensationWorld.committing;
import static com.divalhr.core.payroll.CompensationWorld.readRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.CompensationWorld.Person;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-093A (Issue #175, D93-8): a failure while the audit record or the event of a commit is
 * written leaves the changes, the facts, the audit trail, the outbox and the idempotency receipt
 * exactly as they were, and the same key then succeeds once the failure is gone.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class CompensationRollbackIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @MockitoSpyBean private OutboxWriter outbox;
  @MockitoSpyBean private AuditRecorder audit;

  private CompensationWorld w;
  private Person ana;

  @BeforeEach
  void world() throws Exception {
    w = new CompensationWorld(mvc, jdbc, lookups);
    ana = w.person("Ana", "Mukendi");
  }

  @AfterEach
  void resetSpies() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
    reset((AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
    clock.reset();
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

  private void rollsBackAndThenSucceeds(Runnable failure) throws Exception {
    Map<String, Object> establish = command("ESTABLISHED", w.today, "USD", "1250.00");
    JsonNode preview = w.preview(ana.employee(), establish);
    Map<String, Object> body = committing(establish, preview, false);
    String key = Organizations.newKey();
    List<Integer> before = w.evidence();

    failure.run();
    MockHttpServletResponse failed = w.call(commitRequest(w.admin, ana.employee(), key, body));
    assertThat(failed.getStatus()).isGreaterThanOrEqualTo(500);
    resetSpies();

    assertThat(w.evidence()).isEqualTo(before);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
    assertThat(w.expect(readRequest(w.admin, ana.employee(), null), 200).get("version").asInt())
        .isZero();

    // The same key and payload succeed once the failure is gone.
    w.expect(commitRequest(w.admin, ana.employee(), key, body), 201);
    assertThat(w.evidence().get(0)).isEqualTo(before.get(0) + 1);
  }

  @Test
  void failureWhileWritingTheAuditRecordRollsBackTheWholeCommit() throws Exception {
    rollsBackAndThenSucceeds(this::failAudit);
  }

  @Test
  void failureWhileWritingTheEventRollsBackTheWholeCommit() throws Exception {
    rollsBackAndThenSucceeds(this::failOutbox);
  }
}
