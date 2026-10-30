package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * MVP-094C (Issue #181, ADR 0035) against real PostgreSQL: approvals that race for one calculation,
 * each with its own Idempotency-Key and the same preview, record exactly one package, its sources,
 * payslips, audit row and event, and one stable conflict for every other caller; the same key
 * racing records one package; voids that race record one void; an approval racing a profile change
 * ends in one of the two legal orders and never in a package that disagrees with its sources; PDFs
 * rendered in parallel are byte-identical. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
class PayrollPackageConcurrencyIntegrationTest {

  private static final String EXPLANATION = "The statutory profile was entered for the wrong month";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollPackageWorld w;
  private Chain chain;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    chain = w.ready();
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private List<String> parallel(List<MockHttpServletRequestBuilder> calls) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (MockHttpServletRequestBuilder call : calls) {
        Callable<MockHttpServletResponse> task =
            () -> {
              start.await();
              return w.c.call(call);
            };
        futures.add(pool.submit(task));
      }
      start.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        MockHttpServletResponse response = future.get(60, TimeUnit.SECONDS);
        JsonNode body = CompensationWorld.JSON.readTree(response.getContentAsString());
        outcomes.add(
            response.getStatus() >= 300
                ? response.getStatus() + " " + body.get("code").asText()
                : String.valueOf(response.getStatus()));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }

  private static long created(List<String> outcomes) {
    return outcomes.stream().filter("201"::equals).count();
  }

  @Test
  void threeApprovalsOfTheSamePreviewRecordOnePackageAndTheOthersAreStableConflicts()
      throws Exception {
    Map<String, Object> body = PayrollPackageWorld.approving(w.preview(chain));
    List<Integer> before = w.evidence();

    List<String> outcomes =
        parallel(
            List.of(
                w.approveRequest(chain, Organizations.newKey(), body),
                w.approveRequest(chain, Organizations.newKey(), body),
                w.approveRequest(chain, Organizations.newKey(), body)));

    assertThat(created(outcomes)).as(outcomes.toString()).isEqualTo(1);
    assertThat(outcomes)
        .filteredOn(outcome -> !outcome.equals("201"))
        .hasSize(2)
        .allSatisfy(
            outcome ->
                assertThat(outcome)
                    .isIn(
                        "409 PAYROLL_PACKAGE_VERSION_CONFLICT",
                        "409 PAYROLL_PACKAGE_ALREADY_APPROVED"));
    List<Integer> after = w.evidence();
    // One package, its two sources, one payslip, one audit row and one event.
    assertThat(after.get(0)).isEqualTo(before.get(0) + 1);
    assertThat(after.get(1)).isEqualTo(before.get(1) + 2);
    assertThat(after.get(2)).isEqualTo(before.get(2));
    assertThat(after.get(3)).isEqualTo(before.get(3) + 1);
    assertThat(after.get(4)).isEqualTo(before.get(4) + 1);
    assertThat(after.get(5)).isEqualTo(before.get(5) + 1);
  }

  @Test
  void theSameKeyAndPayloadRacingRecordsExactlyOnePackage() throws Exception {
    Map<String, Object> body = PayrollPackageWorld.approving(w.preview(chain));
    String key = Organizations.newKey();
    int packages = w.evidence().get(0);

    List<String> outcomes =
        parallel(
            List.of(w.approveRequest(chain, key, body), w.approveRequest(chain, key, body)));

    assertThat(outcomes).contains("201").noneMatch(outcome -> outcome.startsWith("5"));
    assertThat(w.evidence().get(0)).isEqualTo(packages + 1);
    assertThat(w.audits("payroll-package.approve")).isEqualTo(1);
  }

  @Test
  void twoVoidsOfTheSamePreviewRecordOneVoidAndTheOtherIsAStableConflict() throws Exception {
    String pkg = w.approve(chain).get("packageId").asText();
    JsonNode preview = w.voidPreview(pkg, "OTHER_CONTROLLED", EXPLANATION);
    Map<String, Object> body =
        PayrollPackageWorld.voiding(preview, "OTHER_CONTROLLED", EXPLANATION);
    List<Integer> before = w.evidence();

    List<String> outcomes =
        parallel(
            List.of(
                w.voidRequest(pkg, Organizations.newKey(), body),
                w.voidRequest(pkg, Organizations.newKey(), body)));

    assertThat(created(outcomes)).as(outcomes.toString()).isEqualTo(1);
    assertThat(outcomes)
        .filteredOn(outcome -> !outcome.equals("201"))
        .hasSize(1)
        .allSatisfy(
            outcome ->
                assertThat(outcome)
                    .isIn("409 PAYROLL_PACKAGE_VOIDED", "409 PAYROLL_PACKAGE_VERSION_CONFLICT"));
    List<Integer> after = w.evidence();
    assertThat(after.get(2)).isEqualTo(before.get(2) + 1);
    assertThat(after.get(4)).isEqualTo(before.get(4) + 1);
    assertThat(after.get(5)).isEqualTo(before.get(5) + 1);
    assertThat(w.packageDetail(pkg).get("package").get("status").asText()).isEqualTo("VOIDED");
  }

  @Test
  void anApprovalRacingAProfileChangeEndsInOneOfTheTwoLegalOrders() throws Exception {
    Map<String, Object> approval = PayrollPackageWorld.approving(w.preview(chain));
    Map<String, Object> command =
        StatutoryWorld.profileCommand(
            w.month.atDay(1).minusDays(10), "STANDARD_RESIDENT", true, false,
                "EMPLOYMENT_CONTRACT");
    JsonNode profilePreview = w.c.expect(w.s.profilePreviewRequest(chain.employee(), command), 200);
    Map<String, Object> profile = StatutoryWorld.profileCommitting(command, profilePreview);
    int packages = w.evidence().get(0);

    List<String> outcomes =
        parallel(
            List.of(
                w.approveRequest(chain, Organizations.newKey(), approval),
                w.s.profileCommitRequest(chain.employee(), Organizations.newKey(), profile)));

    // The profile change is independent of the approval and always lands; the approval either
    // landed first or is stale because the profile changed first.
    assertThat(outcomes.get(1)).isEqualTo("201");
    assertThat(outcomes.get(0)).isIn("201", "409 PAYROLL_APPROVAL_PREVIEW_STALE");
    assertThat(w.evidence().get(0)).isEqualTo(packages + (outcomes.get(0).equals("201") ? 1 : 0));
    if (outcomes.get(0).equals("201")) {
      // Approved first: a later preview is NO_CHANGE or BLOCKED by the new profile, never READY.
      assertThat(w.preview(chain).get("status").asText()).isIn("NO_CHANGE", "BLOCKED");
    } else {
      assertThat(w.preview(chain).get("status").asText()).isEqualTo("BLOCKED");
    }
  }

  @Test
  void pdfsRenderedInParallelAreByteIdentical() throws Exception {
    String pkg = w.approve(chain).get("packageId").asText();
    String slip = w.slipOf(pkg, chain.employee());
    String path = w.slipPdfPath(pkg, slip);
    byte[] reference = w.pdf(w.admin, path).getContentAsByteArray();
    int threads = 6;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<byte[]>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<byte[]> task =
            () -> {
              start.await();
              return w.pdf(w.admin, path).getContentAsByteArray();
            };
        futures.add(pool.submit(task));
      }
      start.countDown();
      for (Future<byte[]> future : futures) {
        assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo(reference);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(w.audits("payslip.download")).isEqualTo(1 + threads);
  }
}
