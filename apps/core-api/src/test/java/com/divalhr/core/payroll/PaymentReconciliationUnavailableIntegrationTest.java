package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.application.PaymentReconciliationWorker;
import com.divalhr.core.payroll.application.PaymentSubmissionProfiles;
import com.divalhr.core.payroll.application.PaymentSubmissionSecrets;
import com.divalhr.core.payroll.application.PaymentSubmissionWorker;
import com.divalhr.core.support.FakePaymentProviderGateway;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-095C (Issue #190, ADR 0038): a provider profile or signing key that disappears after a
 * submission was made. The preview names the blocker, the creation is the stable refusal and writes
 * nothing, and the worker never contacts the provider without a key; without a profile it records a
 * retryable failure and still sends nothing. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
@PaymentSubmissionWorld.HighLimits
@PaymentReconciliationWorld.HighLimits
class PaymentReconciliationUnavailableIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private FakePaymentProviderGateway provider;
  @Autowired private PaymentSubmissionWorker submissions;
  @Autowired private PaymentReconciliationWorker worker;
  @MockitoSpyBean private PaymentSubmissionProfiles profiles;
  @MockitoSpyBean private PaymentSubmissionSecrets keys;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private PaymentSubmissionWorld s;
  private PaymentReconciliationWorld r;
  private String batch;

  @BeforeEach
  void world() throws Exception {
    PaymentReconciliationWorld.drain(clock, provider, submissions, worker);
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    s = new PaymentSubmissionWorld(p);
    r = new PaymentReconciliationWorld(s);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    CompensationWorld.Person ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    String pkg =
        w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    batch = p.prepare(pkg).get("batchId").asText();
    r.deliveryUnknown(batch, provider, submissions);
    provider.reset();
  }

  @AfterEach
  void resetAll() {
    reset((PaymentSubmissionProfiles) AopTestUtils.getUltimateTargetObject(profiles));
    reset((PaymentSubmissionSecrets) AopTestUtils.getUltimateTargetObject(keys));
    clock.reset();
    provider.reset();
  }

  private void withoutProfile() {
    PaymentSubmissionProfiles target = AopTestUtils.getUltimateTargetObject(profiles);
    doReturn(Optional.empty()).when(target).byCode(anyString());
  }

  private void withoutKey() {
    PaymentSubmissionSecrets target = AopTestUtils.getUltimateTargetObject(keys);
    doReturn(false).when(target).available();
  }

  private void nothingLeft() {
    assertThat(provider.inquiryCalls()).isEmpty();
    assertThat(provider.calls()).isEmpty();
  }

  @Test
  void aMissingProfileBlocksThePreviewAndRefusesTheCreationWithoutWritingAnything()
      throws Exception {
    JsonNode ready = r.preview(batch);
    assertThat(ready.get("status").asText()).isEqualTo("READY");
    Map<String, Object> body = PaymentReconciliationWorld.committing(ready);

    withoutProfile();
    JsonNode blocked = r.preview(batch);
    assertThat(blocked.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(PayrollPackageWorld.texts(blocked.get("blockers")))
        .containsExactly("PROVIDER_NOT_AVAILABLE");
    assertThat(blocked.get("provider").isNull()).isTrue();
    assertThat(blocked.get("previewDigest").isNull()).isTrue();
    w.c.problem(
        r.commitRequest(w.admin, batch, Organizations.newKey(), body),
        409,
        "PAYMENT_PROVIDER_UNAVAILABLE");
    assertThat(r.evidence()).containsExactly(0, 0, 0, 0);
    nothingLeft();
  }

  @Test
  void aMissingKeyBlocksThePreviewAndRefusesTheCreationWithoutWritingAnything() throws Exception {
    JsonNode ready = r.preview(batch);
    Map<String, Object> body = PaymentReconciliationWorld.committing(ready);

    withoutKey();
    JsonNode blocked = r.preview(batch);
    assertThat(blocked.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(PayrollPackageWorld.texts(blocked.get("blockers")))
        .containsExactly("PROVIDER_KEY_UNAVAILABLE");
    assertThat(blocked.get("provider").get("code").asText()).isEqualTo("SYNTHETIC-TEST");
    assertThat(blocked.get("previewDigest").isNull()).isTrue();
    w.c.problem(
        r.commitRequest(w.admin, batch, Organizations.newKey(), body),
        503,
        "PAYMENT_SUBMISSION_KEY_UNAVAILABLE");
    assertThat(r.evidence()).containsExactly(0, 0, 0, 0);
    nothingLeft();
  }

  @Test
  void withoutAKeyTheWorkerAsksNothingAndLeavesTheInvestigationOpen() throws Exception {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    clock.set(base);
    r.open(batch);
    withoutKey();
    advance(Duration.ofHours(1));
    assertThat(worker.runOnce()).isZero();
    JsonNode view = r.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("OPEN");
    assertThat(view.get("attemptCount").asInt()).isZero();
    assertThat(view.get("version").asInt()).isEqualTo(1);
    nothingLeft();
  }

  @Test
  void withoutAProfileTheWorkerRecordsARetryableFailureAndSendsNothing() throws Exception {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    clock.set(base);
    r.open(batch);
    withoutProfile();
    assertThat(worker.runOnce()).isEqualTo(1);
    JsonNode view = r.view(batch);
    assertThat(view.get("state").asText()).isEqualTo("OPEN");
    assertThat(view.get("outcomeCode").asText()).isEqualTo("TRANSIENT_CONNECT");
    assertThat(view.get("attemptCount").asInt()).isEqualTo(1);
    assertThat(List.of(Instant.parse(view.get("nextAttemptAt").asText())))
        .containsExactly(base.plusSeconds(30));
    nothingLeft();
  }

  private void advance(Duration by) {
    clock.set(clock.instant().plus(by));
  }
}
