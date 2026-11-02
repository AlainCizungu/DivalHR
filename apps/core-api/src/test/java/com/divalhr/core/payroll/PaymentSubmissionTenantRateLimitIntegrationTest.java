package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.platform.ratelimit.RequestLimitProperties;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import java.time.Duration;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-095B (Issue #187, ADR 0037): the three per-tenant submission buckets (previews, commits
 * and reads; 60, 20 and 300 per tenant and hour by default). The subject limits are raised and each
 * tenant limit is lowered to three so that the tenant bucket is the one that answers: the fourth
 * request of the hour is a stable 429 {@code RATE_LIMITED} with {@code Retry-After}, nothing is
 * written, the endpoints that share a bucket share it, another tenant is not affected. A last test
 * pins the five configured buckets and their hour window. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
@PaymentSubmissionWorld.HighLimits
@TestPropertySource(
    properties = {
      "divalhr.request-limits.tenant.payment-submission-preview.requests=3",
      "divalhr.request-limits.tenant.payment-submission-write.requests=3",
      "divalhr.request-limits.tenant.payment-submission-read.requests=3"
    })
class PaymentSubmissionTenantRateLimitIntegrationTest {

  private static final String ZEROS = "0".repeat(64);
  private static final int LIMIT = 3;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private RequestLimitProperties limits;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private PaymentSubmissionWorld s;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    s = new PaymentSubmissionWorld(p);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private interface Call {
    MockHttpServletRequestBuilder build() throws Exception;
  }

  /** The limiter's windows are fixed hours: never start counting in the last two minutes. */
  private static void awaitFreshWindow() throws InterruptedException {
    long left = 3_600_000 - (System.currentTimeMillis() % 3_600_000);
    if (left < 120_000) {
      Thread.sleep(left + 100);
    }
  }

  /** Three calls pass, the fourth is the stable refusal; nothing is written by any of them. */
  private void exhaust(Call... calls) throws Exception {
    awaitFreshWindow();
    List<Integer> before = p.evidence();
    for (int i = 0; i < LIMIT; i++) {
      MockHttpServletResponse ok = w.c.call(calls[i % calls.length].build());
      assertThat(ok.getStatus()).as("call " + (i + 1)).isNotEqualTo(429);
    }
    MockHttpServletResponse last = w.c.call(calls[LIMIT % calls.length].build());
    assertThat(last.getStatus()).as("call " + (LIMIT + 1)).isEqualTo(429);
    assertThat(CompensationWorld.JSON.readTree(last.getContentAsString()).get("code").asText())
        .isEqualTo("RATE_LIMITED");
    assertThat(Integer.parseInt(last.getHeader("Retry-After"))).isBetween(1, 3600);
    assertThat(last.getContentAsString()).doesNotContain(PaymentWorld.ACCOUNT);
    assertThat(p.evidence()).isEqualTo(before);
  }

  private Map<String, Object> forgedCommit() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedBatchVersion", 1);
    body.put("batchDigest", ZEROS);
    body.put("previewDigest", ZEROS);
    body.put("expiresAt", Instant.now().plusSeconds(600).toString());
    body.put("reviewedProviderAndTotals", true);
    body.put("understandAcceptedIsNotPaid", true);
    return body;
  }

  @Test
  void theFourthSubmissionPreviewOfAnHourIsRateLimitedForTheTenant() throws Exception {
    exhaust(() -> s.previewRequest(w.admin, UUID.randomUUID()));
  }

  @Test
  void theFourthSubmissionCommitOfAnHourIsRateLimitedForTheTenant() throws Exception {
    exhaust(() -> s.commitRequest(w.admin, UUID.randomUUID(), Organizations.newKey(), forgedCommit()));
  }

  @Test
  void theFourthSubmissionReadOfAnHourIsRateLimitedAcrossStatusAndHistory() throws Exception {
    UUID batch = UUID.randomUUID();
    exhaust(
        () -> PayrollRunWorld.get(w.admin, p.batchPath(batch) + "/submission"),
        () -> PayrollRunWorld.get(w.admin, p.batchPath(batch) + "/submission/attempts"));
  }

  @Test
  void anotherTenantAndTheOtherBucketsAreNotLimited() throws Exception {
    exhaust(() -> s.previewRequest(w.admin, UUID.randomUUID()));
    assertThat(
            w.c.call(PayrollRunWorld.get(w.admin, p.batchPath(UUID.randomUUID()) + "/submission"))
                .getStatus())
        .isNotEqualTo(429);
    PayrollPackageWorld other = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    PaymentSubmissionWorld otherSubmissions = new PaymentSubmissionWorld(new PaymentWorld(other));
    assertThat(
            other.c.call(otherSubmissions.previewRequest(other.admin, UUID.randomUUID())).getStatus())
        .isNotEqualTo(429);
  }

  @Test
  void theThreeTenantBucketsAreConfiguredWithAnHourWindowAndTheTestLimit() {
    for (String bucket :
        List.of(
            "payment-submission-preview", "payment-submission-write", "payment-submission-read")) {
      assertThat(limits.tenant()).containsKey(bucket);
      assertThat(limits.tenant().get(bucket).requests()).as(bucket).isEqualTo(LIMIT);
      assertThat(limits.tenant().get(bucket).window()).as(bucket).isEqualTo(Duration.ofHours(1));
    }
  }
}
