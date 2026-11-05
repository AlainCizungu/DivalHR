package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.platform.ratelimit.RequestLimitProperties;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * MVP-095A (Issue #183, ADR 0036, D95A-13, R184-1): every configured payment rate-limit path. The
 * six subject buckets (60 reads, 10 destination previews, 5 destination commits, 5 batch previews,
 * 2 batch commits and 30 register downloads per minute and subject by default) each answer exactly
 * their limit and then a stable 429 {@code RATE_LIMITED} with {@code Retry-After}, disclose nothing
 * and write nothing; the endpoints that share a bucket do share it; one subject's or bucket's
 * refusal does not touch another's. A reflection test ties the executable cases to the 18 annotated
 * handlers and to the configured buckets, so a new or re-bucketed endpoint fails here until it is
 * covered. The five per-tenant buckets are covered by {@link
 * PaymentTenantRateLimitIntegrationTest}; the executable cases of the four reconciliation handlers
 * (MVP-095C) are in {@link PaymentReconciliationRateLimitIntegrationTest} and {@link
 * PaymentReconciliationTenantRateLimitIntegrationTest}. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
class PaymentRateLimitIntegrationTest {

  /** Every payment handler and its subject and tenant bucket ("-" when not tenant limited). */
  private static final Map<String, String> HANDLERS =
      new TreeMap<>(
          Map.ofEntries(
              Map.entry("EmployeePaymentDestinationController.get", "payment-read/-"),
              Map.entry("EmployeePaymentDestinationController.history", "payment-read/-"),
              Map.entry(
                  "EmployeePaymentDestinationController.preview",
                  "payment-destination-preview/payment-destination-preview"),
              Map.entry(
                  "EmployeePaymentDestinationController.commit",
                  "payment-destination-write/payment-destination-write"),
              Map.entry("MyPaymentDestinationController.get", "payment-read/-"),
              Map.entry(
                  "PaymentBatchController.preview", "payment-batch-preview/payment-batch-preview"),
              Map.entry(
                  "PaymentBatchController.prepare", "payment-batch-write/payment-batch-write"),
              Map.entry("PaymentBatchController.list", "payment-read/-"),
              Map.entry("PaymentBatchController.get", "payment-read/-"),
              Map.entry("PaymentBatchController.lines", "payment-read/-"),
              Map.entry(
                  "PaymentBatchController.previewCancel",
                  "payment-batch-preview/payment-batch-preview"),
              Map.entry("PaymentBatchController.cancel", "payment-batch-write/payment-batch-write"),
              Map.entry(
                  "PaymentSubmissionController.preview",
                  "payment-submission-preview/payment-submission-preview"),
              Map.entry(
                  "PaymentSubmissionController.create",
                  "payment-submission-write/payment-submission-write"),
              Map.entry(
                  "PaymentSubmissionController.get",
                  "payment-submission-read/payment-submission-read"),
              Map.entry(
                  "PaymentSubmissionController.attempts",
                  "payment-submission-read/payment-submission-read"),
              Map.entry(
                  "PaymentReconciliationController.preview",
                  "payment-reconciliation-preview/payment-reconciliation-preview"),
              Map.entry(
                  "PaymentReconciliationController.create",
                  "payment-reconciliation-write/payment-reconciliation-write"),
              Map.entry(
                  "PaymentReconciliationController.get",
                  "payment-reconciliation-read/payment-reconciliation-read"),
              Map.entry(
                  "PaymentReconciliationController.inquiries",
                  "payment-reconciliation-read/payment-reconciliation-read"),
              Map.entry(
                  "PaymentRegisterController.csv",
                  "payment-register-download/payment-register-download"),
              Map.entry(
                  "PaymentRegisterController.manifest",
                  "payment-register-download/payment-register-download")));

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private RequestLimitProperties limits;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private CompensationWorld.Person ana;
  private UUID employee;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    employee = ana.employee();
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private interface Call {
    MockHttpServletRequestBuilder build() throws Exception;
  }

  private static final String ZEROS = "0".repeat(64);

  /**
   * Cycles the calls {@code limit} times (none may be limited), then makes one more: that one must
   * be the stable 429, and nothing may have been written by any of them.
   */
  private void exhaust(int limit, Call... calls) throws Exception {
    awaitFreshWindow();
    List<Integer> before = p.evidence();
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
  }

  /** The limiter's windows are fixed minutes: never start counting in the last 20 seconds. */
  private void awaitFreshWindow() throws InterruptedException {
    long left = 60_000 - (System.currentTimeMillis() % 60_000);
    if (left < 20_000) {
      Thread.sleep(left + 100);
    }
  }

  private Call adminGet(String path) {
    return () -> PayrollRunWorld.get(w.admin, path);
  }

  private Call destinationPreview() {
    return () ->
        p.destinationPreview(
            w.admin, employee, p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
  }

  /** A commit whose evidence is forged: refused, never written, but it passes the limiter. */
  private Call destinationCommit(Map<String, Object> forged) {
    return () -> p.destinationCommit(w.admin, employee, Organizations.newKey(), forged);
  }

  private Map<String, Object> forgedDestinationCommit() throws Exception {
    Map<String, Object> command = p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP");
    JsonNode preview = p.previewDestination(employee, command);
    Map<String, Object> body = new LinkedHashMap<>(PaymentWorld.committing(command, preview));
    body.put("previewDigest", ZEROS);
    return body;
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

  // ------------------------------------------------------------------------------------------
  // Subject buckets
  // ------------------------------------------------------------------------------------------

  @Test
  void theSixtyFirstReadOfAMinuteIsRateLimitedAcrossEveryAdministratorReadEndpoint()
      throws Exception {
    UUID batch = UUID.randomUUID();
    exhaust(
        60,
        adminGet(PaymentWorld.destinationPath(employee)),
        adminGet(PaymentWorld.destinationPath(employee) + "/history"),
        adminGet(p.batchesPath()),
        adminGet(p.batchPath(batch)),
        adminGet(p.batchPath(batch) + "/lines"));
  }

  @Test
  void theEmployeesOwnDestinationReadHasItsOwnSixtyPerMinuteAndSubject() throws Exception {
    exhaust(60, () -> PayrollRunWorld.get(ana.bearer(), PaymentWorld.MINE));
    // The administrator is another subject: not limited by the employee's refusal.
    assertThat(
            w.c
                .call(PayrollRunWorld.get(w.admin, PaymentWorld.destinationPath(employee)))
                .getStatus())
        .isNotEqualTo(429);
  }

  @Test
  void theEleventhDestinationPreviewOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    exhaust(10, destinationPreview());
  }

  @Test
  void theSixthDestinationCommitOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    exhaust(5, destinationCommit(forgedDestinationCommit()));
  }

  @Test
  void theSixthBatchPreviewOfAMinuteIsRateLimitedAcrossPrepareAndCancelPreviews() throws Exception {
    UUID pkg = UUID.randomUUID();
    UUID batch = UUID.randomUUID();
    exhaust(
        5,
        () -> p.batchPreview(w.admin, pkg),
        () -> p.cancelPreview(w.admin, batch, "PREPARED_IN_ERROR"));
  }

  @Test
  void theThirdBatchCommitOfAMinuteIsRateLimitedAcrossPrepareAndCancel() throws Exception {
    UUID pkg = UUID.randomUUID();
    UUID batch = UUID.randomUUID();
    exhaust(
        2,
        () -> p.prepareRequest(w.admin, pkg, Organizations.newKey(), forgedPrepare()),
        () -> p.cancelRequest(w.admin, batch, Organizations.newKey(), forgedCancel()));
  }

  @Test
  void theThirtyFirstRegisterDownloadOfAMinuteIsRateLimitedAcrossTheCsvAndTheManifest()
      throws Exception {
    UUID batch = UUID.randomUUID();
    exhaust(
        30,
        adminGet(p.batchPath(batch) + "/register.csv"),
        adminGet(p.batchPath(batch) + "/manifest.json"));
  }

  @Test
  void theSixthSubmissionPreviewOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    PaymentSubmissionWorld s = new PaymentSubmissionWorld(p);
    exhaust(5, () -> s.previewRequest(w.admin, UUID.randomUUID()));
  }

  @Test
  void theThirdSubmissionCommitOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    PaymentSubmissionWorld s = new PaymentSubmissionWorld(p);
    Map<String, Object> forged = new LinkedHashMap<>();
    forged.put("expectedBatchVersion", 1);
    forged.put("batchDigest", ZEROS);
    forged.put("previewDigest", ZEROS);
    forged.put("expiresAt", Instant.now().plusSeconds(600).toString());
    forged.put("reviewedProviderAndTotals", true);
    forged.put("understandAcceptedIsNotPaid", true);
    exhaust(2, () -> s.commitRequest(w.admin, UUID.randomUUID(), Organizations.newKey(), forged));
  }

  @Test
  void theSixtyFirstSubmissionReadOfAMinuteIsRateLimitedAcrossStatusAndHistory() throws Exception {
    UUID batch = UUID.randomUUID();
    exhaust(
        60,
        adminGet(p.batchPath(batch) + "/submission"),
        adminGet(p.batchPath(batch) + "/submission/attempts"));
  }

  @Test
  void aRefusedBucketLeavesTheOtherBucketsOfTheSameSubjectAvailable() throws Exception {
    exhaust(10, destinationPreview());
    assertThat(
            w.c
                .call(PayrollRunWorld.get(w.admin, PaymentWorld.destinationPath(employee)))
                .getStatus())
        .isNotEqualTo(429);
    assertThat(w.c.call(p.batchPreview(w.admin, UUID.randomUUID())).getStatus()).isNotEqualTo(429);
    assertThat(
            w.c
                .call(
                    PayrollRunWorld.get(w.admin, p.batchPath(UUID.randomUUID()) + "/register.csv"))
                .getStatus())
        .isNotEqualTo(429);
  }

  // ------------------------------------------------------------------------------------------
  // Coverage: handlers and configured buckets
  // ------------------------------------------------------------------------------------------

  @Test
  void everyPaymentHandlerAndEveryConfiguredPaymentBucketIsCoveredByTheCasesAbove() {
    Map<String, String> found = new TreeMap<>();
    for (Class<?> controller :
        List.of(
            com.divalhr.core.payroll.api.EmployeePaymentDestinationController.class,
            com.divalhr.core.payroll.api.MyPaymentDestinationController.class,
            com.divalhr.core.payroll.api.PaymentBatchController.class,
            com.divalhr.core.payroll.api.PaymentSubmissionController.class,
            com.divalhr.core.payroll.api.PaymentReconciliationController.class,
            com.divalhr.core.payroll.api.PaymentRegisterController.class)) {
      for (Method method : controller.getDeclaredMethods()) {
        SubjectRateLimited subject = method.getAnnotation(SubjectRateLimited.class);
        TenantRateLimited tenant = method.getAnnotation(TenantRateLimited.class);
        if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
            && !method.isSynthetic()
            && (subject != null || tenant != null || isHandler(method))) {
          assertThat(subject).as(method + " has no subject bucket").isNotNull();
          found.put(
              controller.getSimpleName() + "." + method.getName(),
              subject.bucket() + "/" + (tenant == null ? "-" : tenant.bucket()));
        }
      }
    }
    assertThat(found).isEqualTo(HANDLERS);

    // The configured payment buckets are exactly the ones the handlers use.
    assertThat(new TreeSet<>(subjectBuckets(limits)))
        .isEqualTo(
            new TreeSet<>(
                HANDLERS.values().stream().map(v -> v.substring(0, v.indexOf('/'))).toList()));
    assertThat(new TreeSet<>(tenantBuckets(limits)))
        .isEqualTo(
            new TreeSet<>(
                HANDLERS.values().stream()
                    .map(v -> v.substring(v.indexOf('/') + 1))
                    .filter(b -> !b.equals("-"))
                    .toList()));
  }

  private static boolean isHandler(Method method) {
    return java.util.Arrays.stream(method.getAnnotations())
        .anyMatch(a -> a.annotationType().getSimpleName().endsWith("Mapping"));
  }

  private static List<String> subjectBuckets(RequestLimitProperties limits) {
    return new ArrayList<>(
        limits.subject().keySet().stream().filter(k -> k.startsWith("payment-")).toList());
  }

  private static List<String> tenantBuckets(RequestLimitProperties limits) {
    return new ArrayList<>(
        limits.tenant().keySet().stream().filter(k -> k.startsWith("payment-")).toList());
  }
}
