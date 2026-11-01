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
import com.fasterxml.jackson.databind.JsonNode;
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
 * MVP-095A (Issue #183, ADR 0036): a failure while the audit record or the event of a commit is
 * written leaves destinations, batches, lines, audit, outbox and idempotency exactly as they were
 * and the same key then succeeds; and the manual V47 rollback script refuses while any evidence
 * exists. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
class PaymentRollbackIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @MockitoSpyBean private OutboxWriter outbox;
  @MockitoSpyBean private AuditRecorder audit;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private CompensationWorld.Person ana;
  private String pkg;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    pkg = w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
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

  private void destinationRollsBack(Runnable failure) throws Exception {
    Map<String, Object> command = p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP");
    JsonNode preview = p.previewDestination(ana.employee(), command);
    Map<String, Object> body = PaymentWorld.committing(command, preview);
    String key = Organizations.newKey();
    List<Integer> before = p.evidence();

    failure.run();
    MockHttpServletResponse failed =
        w.c.call(p.destinationCommit(w.admin, ana.employee(), key, body));
    assertThat(failed.getStatus()).isGreaterThanOrEqualTo(500);
    resetSpies();

    assertThat(p.evidence()).isEqualTo(before);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
    w.c.expect(p.destinationCommit(w.admin, ana.employee(), key, body), 201);
    assertThat(p.evidence().get(0)).isEqualTo(before.get(0) + 1);
  }

  private void batchRollsBack(Runnable failure) throws Exception {
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    Map<String, Object> body = PaymentWorld.preparing(p.previewBatch(pkg));
    String key = Organizations.newKey();
    List<Integer> before = p.evidence();

    failure.run();
    MockHttpServletResponse failed = w.c.call(p.prepareRequest(w.admin, pkg, key, body));
    assertThat(failed.getStatus()).isGreaterThanOrEqualTo(500);
    resetSpies();

    assertThat(p.evidence()).isEqualTo(before);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key))
        .isZero();
    w.c.expect(p.prepareRequest(w.admin, pkg, key, body), 201);
    assertThat(p.evidence().get(1)).isEqualTo(before.get(1) + 1);
  }

  @Test
  void failureWhileWritingTheAuditRecordRollsBackADestinationCommit() throws Exception {
    destinationRollsBack(this::failAudit);
  }

  @Test
  void failureWhileWritingTheEventRollsBackADestinationCommit() throws Exception {
    destinationRollsBack(this::failOutbox);
  }

  @Test
  void failureWhileWritingTheAuditRecordRollsBackABatchPreparation() throws Exception {
    batchRollsBack(this::failAudit);
  }

  @Test
  void failureWhileWritingTheEventRollsBackABatchPreparation() throws Exception {
    batchRollsBack(this::failOutbox);
  }

  @Test
  void theManualV47RollbackRefusesWhileEvidenceExistsAndChangesNothing() throws Exception {
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    String script =
        new String(
            new ClassPathResource("db/rollback/V47__rollback.sql").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
    List<Integer> before = p.evidence();
    Connection connection = dataSource.getConnection();
    try {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        assertThatThrownBy(() -> statement.execute(script))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V47 rollback refused");
      }
    } finally {
      connection.rollback();
      connection.setAutoCommit(true);
      connection.close();
    }
    assertThat(p.evidence()).isEqualTo(before);
    assertThat(
            w.c.count(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'payroll'"
                    + " AND table_name LIKE 'payment_%'"))
        .isEqualTo(4);
  }
}
