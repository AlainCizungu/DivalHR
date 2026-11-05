package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.application.PaymentReconciliationWorker;
import com.divalhr.core.payroll.application.PaymentSubmissionWorker;
import com.divalhr.core.platform.ratelimit.RequestLimitProperties;
import com.divalhr.core.support.FakePaymentProviderGateway;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-095C (Issue #190, ADR 0038): the three per-subject reconciliation buckets (5 previews, 2
 * investigations and 60 reads or history pages per minute and subject by default). Each answers
 * exactly its limit and then a stable 429 {@code RATE_LIMITED} with {@code Retry-After}, discloses
 * nothing, writes nothing and never contacts the provider; the endpoints that share a bucket do
 * share it; one subject's or bucket's refusal does not touch another's. The limiter reads the
 * system clock, so the next window is covered deterministically by {@code
 * PaymentRateLimitWindowTest} (stepped clock); here a test that would start in the last 20 seconds
 * of a minute is skipped instead of slept. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
@PaymentSubmissionWorld.HighLimits
class PaymentReconciliationRateLimitIntegrationTest {

  private static final String ZEROS = "0".repeat(64);

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private RequestLimitProperties limits;
  @Autowired private FakePaymentProviderGateway provider;
  @Autowired private PaymentSubmissionWorker submissions;
  @Autowired private PaymentReconciliationWorker worker;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private PaymentSubmissionWorld s;
  private PaymentReconciliationWorld r;

  @BeforeEach
  void world() throws Exception {
    PaymentReconciliationWorld.drain(clock, provider, submissions, worker);
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    s = new PaymentSubmissionWorld(p);
    r = new PaymentReconciliationWorld(s);
  }

  @AfterEach
  void reset() {
    clock.reset();
    provider.reset();
  }

  private interface Call {
    MockHttpServletRequestBuilder build() throws Exception;
  }

  /** The limiter's windows are fixed minutes: a test never starts in the last 20 seconds. */
  private static void inFreshWindow() {
    long left = 60_000 - (System.currentTimeMillis() % 60_000);
    assumeTrue(left >= 20_000, "too close to the end of the limiter's minute window");
  }

  /**
   * Cycles the calls {@code limit} times (none may be limited), then makes one more: that one must
   * be the stable 429, and nothing may have been written or sent by any of them.
   */
  private void exhaust(int limit, Call... calls) throws Exception {
    inFreshWindow();
    List<Integer> before = p.evidence();
    List<Integer> reconBefore = r.evidence();
    for (int i = 0; i < limit; i++) {
      MockHttpServletResponse ok = w.c.call(calls[i % calls.length].build());
      assertThat(ok.getStatus()).as("call " + (i + 1) + " of " + limit).isNotEqualTo(429);
    }
    MockHttpServletResponse last = w.c.call(calls[limit % calls.length].build());
    assertThat(last.getStatus()).as("call " + (limit + 1)).isEqualTo(429);
    assertThat(CompensationWorld.JSON.readTree(last.getContentAsString()).get("code").asText())
        .isEqualTo("RATE_LIMITED");
    assertThat(Integer.parseInt(last.getHeader("Retry-After"))).isBetween(1, 60);
    assertThat(last.getContentAsString()).doesNotContain(PaymentWorld.ACCOUNT);
    assertThat(p.evidence()).isEqualTo(before);
    assertThat(r.evidence()).isEqualTo(reconBefore);
    assertThat(provider.inquiryCalls()).isEmpty();
    assertThat(provider.calls()).isEmpty();
  }

  private Map<String, Object> forgedCommit() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedSubmissionVersion", 1);
    body.put("expectedSubmissionState", "DELIVERY_UNKNOWN");
    body.put("previewDigest", ZEROS);
    body.put("expiresAt", Instant.now().plusSeconds(600).toString());
    body.put("understandInquiryDoesNotResend", true);
    body.put("understandAcceptedIsNotPaid", true);
    return body;
  }

  private String batchOfAnUnknownDelivery() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    CompensationWorld.Person ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    String pkg =
        w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    String batch = p.prepare(pkg).get("batchId").asText();
    r.deliveryUnknown(batch, provider, submissions);
    provider.reset();
    return batch;
  }

  @Test
  void theSixthReconciliationPreviewOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    exhaust(5, () -> r.previewRequest(w.admin, UUID.randomUUID()));
  }

  @Test
  void theThirdReconciliationCreationOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    Map<String, Object> forged = forgedCommit();
    exhaust(2, () -> r.commitRequest(w.admin, UUID.randomUUID(), Organizations.newKey(), forged));
  }

  @Test
  void theSixtyFirstReconciliationReadOfAMinuteIsRateLimitedAcrossStatusAndHistory()
      throws Exception {
    UUID batch = UUID.randomUUID();
    exhaust(60, () -> r.getRequest(w.admin, batch), () -> r.inquiriesRequest(w.admin, batch, null));
  }

  @Test
  void aRealInvestigationIsCreatedWithinTheLimitAndTheThirdCreationIsRefused() throws Exception {
    String batch = batchOfAnUnknownDelivery();
    inFreshWindow();
    Map<String, Object> body = PaymentReconciliationWorld.committing(r.preview(batch));
    String key = Organizations.newKey();
    assertThat(w.c.call(r.commitRequest(w.admin, batch, key, body)).getStatus()).isEqualTo(201);
    assertThat(w.c.call(r.commitRequest(w.admin, batch, key, body)).getStatus()).isEqualTo(201);
    MockHttpServletResponse limited =
        w.c.call(r.commitRequest(w.admin, batch, Organizations.newKey(), body));
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(r.evidence()).containsExactly(1, 1, 1, 1);
    assertThat(provider.inquiryCalls()).isEmpty();
    assertThat(provider.calls()).isEmpty();
  }

  @Test
  void aRefusedBucketLeavesTheOtherBucketsSubjectsAndSubmissionBucketsAvailable() throws Exception {
    exhaust(5, () -> r.previewRequest(w.admin, UUID.randomUUID()));
    assertThat(w.c.call(r.getRequest(w.admin, UUID.randomUUID())).getStatus()).isNotEqualTo(429);
    assertThat(
            w.c
                .call(
                    r.commitRequest(
                        w.admin, UUID.randomUUID(), Organizations.newKey(), forgedCommit()))
                .getStatus())
        .isNotEqualTo(429);
    assertThat(w.c.call(s.previewRequest(w.admin, UUID.randomUUID())).getStatus())
        .isNotEqualTo(429);
    // Another administrator of the same tenant is another subject.
    String colleague =
        PaymentReconciliationWorld.bearer(
            w.tenant, "sub-rec-rate-colleague", TestTokens.MFA_ACR, "tenant-admin");
    assertThat(w.c.call(r.previewRequest(colleague, UUID.randomUUID())).getStatus())
        .isNotEqualTo(429);
    // Another tenant is not affected.
    PayrollPackageWorld other = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    PaymentReconciliationWorld otherWorld =
        new PaymentReconciliationWorld(new PaymentSubmissionWorld(new PaymentWorld(other)));
    assertThat(other.c.call(otherWorld.previewRequest(other.admin, UUID.randomUUID())).getStatus())
        .isNotEqualTo(429);
  }

  @Test
  void theThreeSubjectBucketsAreConfiguredWithTheDocumentedDefaults() {
    assertThat(limits.subject().get("payment-reconciliation-preview")).isEqualTo(5);
    assertThat(limits.subject().get("payment-reconciliation-write")).isEqualTo(2);
    assertThat(limits.subject().get("payment-reconciliation-read")).isEqualTo(60);
  }
}
