package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-095B (Issue #187, ADR 0037): a failure while the audit record or the event of a submission
 * commit is written leaves submissions, evidence rows, audit, outbox and idempotency exactly as
 * they were and the same key then succeeds; and the manual V48 rollback script refuses while any
 * submission exists and restores nothing partially. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
@PaymentSubmissionWorld.HighLimits
class PaymentSubmissionRollbackIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @MockitoSpyBean private OutboxWriter outbox;
  @MockitoSpyBean private AuditRecorder audit;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private PaymentSubmissionWorld s;
  private String batch;
  private CompensationWorld.Person ana;
  private String pkg;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    s = new PaymentSubmissionWorld(p);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    pkg = w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    batch = p.prepare(pkg).get("batchId").asText();
  }

  @AfterEach
  void resetAll() {
    resetSpies();
    clock.reset();
  }

  private void resetSpies() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
    reset((AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
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

  private void submissionRollsBack(Runnable failure) throws Exception {
    Map<String, Object> body = PaymentSubmissionWorld.committing(s.preview(batch));
    String key = Organizations.newKey();
    List<Integer> before = s.evidence();

    failure.run();
    MockHttpServletResponse failed = w.c.call(s.commitRequest(w.admin, batch, key, body));
    assertThat(failed.getStatus()).isGreaterThanOrEqualTo(500);
    resetSpies();

    assertThat(s.evidence()).isEqualTo(before);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
    w.c.expect(s.commitRequest(w.admin, batch, key, body), 201);
    assertThat(s.evidence()).containsExactly(1, 1, 1, 1);
  }

  @Test
  void failureWhileWritingTheAuditRecordRollsBackASubmission() throws Exception {
    submissionRollsBack(this::failAudit);
  }

  @Test
  void failureWhileWritingTheEventRollsBackASubmission() throws Exception {
    submissionRollsBack(this::failOutbox);
  }

  private String script() throws Exception {
    return new String(
        new ClassPathResource("db/rollback/V48__rollback.sql").getInputStream().readAllBytes(),
        StandardCharsets.UTF_8);
  }

  private void rollbackRefused(String script) throws Exception {
    Connection connection = dataSource.getConnection();
    try {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        assertThatThrownBy(() -> statement.execute(script))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V48 rollback refused");
      }
    } finally {
      connection.rollback();
      connection.setAutoCommit(true);
      connection.close();
    }
  }

  @Test
  void theManualV48RollbackRefusesWhileASubmissionExistsAndChangesNothing() throws Exception {
    s.submit(batch);
    List<Integer> before = s.evidence();
    rollbackRefused(script());
    assertThat(s.evidence()).isEqualTo(before);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'payroll'"
                    + " AND table_name LIKE 'payment_submission%'"))
        .isEqualTo(2);
    // The guards still refuse a cancellation of the submitted batch.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE payroll.payment_batch SET status = 'CANCELLED' WHERE tenant_id = ?"
                        + " AND id = ?",
                    w.tenant,
                    UUID.fromString(batch)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
