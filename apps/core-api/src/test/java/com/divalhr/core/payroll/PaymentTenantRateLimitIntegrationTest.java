package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.platform.ratelimit.RequestLimitProperties;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
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
 * MVP-095A (Issue #183, ADR 0036, D95A-13, R184-1): the five per-tenant payment buckets
 * (destination previews, destination commits, batch previews, batch commits and register downloads;
 * 120, 60, 60, 20 and 300 per tenant and hour by default). The subject limits are raised and each
 * tenant limit is lowered to three so that the tenant bucket is the one that answers: the fourth
 * request of the hour is a stable 429 {@code RATE_LIMITED} with {@code Retry-After}, nothing is
 * written, the endpoints that share a bucket share it, another tenant is not affected. A last test pins the five configured buckets and their hour window.
 * Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
@TestPropertySource(
    properties = {
      "divalhr.request-limits.tenant.payment-destination-preview.requests=3",
      "divalhr.request-limits.tenant.payment-destination-write.requests=3",
      "divalhr.request-limits.tenant.payment-batch-preview.requests=3",
      "divalhr.request-limits.tenant.payment-batch-write.requests=3",
      "divalhr.request-limits.tenant.payment-register-download.requests=3"
    })
class PaymentTenantRateLimitIntegrationTest {

  private static final String ZEROS = "0".repeat(64);
  private static final int LIMIT = 3;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private RequestLimitProperties limits;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private UUID employee;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    employee = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS).employee();
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

  private Call destinationPreview() {
    return () ->
        p.destinationPreview(
            w.admin, employee, p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
  }

  private Map<String, Object> forgedPrepare() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", 0);
    body.put("packageVersion", 1);
    body.put("previewDigest", ZEROS);
    body.put("expiresAt", Instant.now().plusSeconds(600).toString());
    body.put("reviewedTotalsAndDestinations", true);
    body.put("noMoneySentAcknowledged", true);
    return body;
  }

  private Map<String, Object> forgedCancel() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reasonCode", "PREPARED_IN_ERROR");
    body.put("batchVersion", 1);
    body.put("batchDigest", ZEROS);
    body.put("previewDigest", ZEROS);
    body.put("expiresAt", Instant.now().plusSeconds(600).toString());
    body.put("noMoneySentAcknowledged", true);
    return body;
  }

  @Test
  void theFourthDestinationPreviewOfAnHourIsRateLimitedForTheTenant() throws Exception {
    exhaust(destinationPreview());
  }

  @Test
  void theFourthDestinationCommitOfAnHourIsRateLimitedForTheTenant() throws Exception {
    Map<String, Object> command = p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP");
    JsonNode preview = p.previewDestination(employee, command);
    Map<String, Object> forged = new LinkedHashMap<>(PaymentWorld.committing(command, preview));
    forged.put("previewDigest", ZEROS);
    exhaust(() -> p.destinationCommit(w.admin, employee, Organizations.newKey(), forged));
  }

  @Test
  void theFourthBatchPreviewOfAnHourIsRateLimitedAcrossPrepareAndCancelPreviews() throws Exception {
    exhaust(
        () -> p.batchPreview(w.admin, UUID.randomUUID()),
        () -> p.cancelPreview(w.admin, UUID.randomUUID(), "PREPARED_IN_ERROR"));
  }

  @Test
  void theFourthBatchCommitOfAnHourIsRateLimitedAcrossPrepareAndCancel() throws Exception {
    exhaust(
        () -> p.prepareRequest(w.admin, UUID.randomUUID(), Organizations.newKey(), forgedPrepare()),
        () -> p.cancelRequest(w.admin, UUID.randomUUID(), Organizations.newKey(), forgedCancel()));
  }

  @Test
  void theFourthRegisterDownloadOfAnHourIsRateLimitedAcrossTheCsvAndTheManifest() throws Exception {
    UUID batch = UUID.randomUUID();
    exhaust(
        () -> PayrollRunWorld.get(w.admin, p.batchPath(batch) + "/register.csv"),
        () -> PayrollRunWorld.get(w.admin, p.batchPath(batch) + "/manifest.json"));
  }

  @Test
  void anotherTenantIsNotLimited() throws Exception {
    exhaust(destinationPreview());

    // Another tenant has its own count.
    PayrollPackageWorld other = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    PaymentWorld otherPayments = new PaymentWorld(other);
    UUID otherEmployee = other.linkedEmployee("Kabila", PayrollPackageWorld.GROSS).employee();
    assertThat(
            other
                .c
                .call(
                    otherPayments.destinationPreview(
                        other.admin,
                        otherEmployee,
                        otherPayments.bank(
                            PaymentWorld.ACCOUNT, otherPayments.early(), "INITIAL_SETUP")))
                .getStatus())
        .isNotEqualTo(429);

    // The same tenant's other tenant bucket is not touched either.
    assertThat(w.c.call(p.batchPreview(w.admin, UUID.randomUUID())).getStatus()).isNotEqualTo(429);
  }

  @Test
  void theFiveTenantBucketsAreConfiguredWithAnHourWindowAndTheTestLimit() {
    assertThat(limits.tenant().keySet())
        .contains(
            "payment-destination-preview",
            "payment-destination-write",
            "payment-batch-preview",
            "payment-batch-write",
            "payment-register-download");
    for (String bucket :
        List.of(
            "payment-destination-preview",
            "payment-destination-write",
            "payment-batch-preview",
            "payment-batch-write",
            "payment-register-download")) {
      assertThat(limits.tenant().get(bucket).requests()).as(bucket).isEqualTo(LIMIT);
      assertThat(limits.tenant().get(bucket).window()).as(bucket).isEqualTo(Duration.ofHours(1));
    }
  }
}
