package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.PayrollPackageWorld.Linked;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-094C (Issue #181, ADR 0035 section 14) with the default buckets: five approval or void
 * previews, two approvals or voids (one shared bucket), 30 PDF downloads and 60 reads per minute
 * and subject, then a stable 429 that discloses nothing and writes nothing; the buckets are
 * independent of each other and of the other subjects. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class PayrollPackageRateLimitIntegrationTest {

  private static final String EXPLANATION = "The statutory profile was entered for the wrong month";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollPackageWorld w;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private void limited(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(CompensationWorld.JSON.readTree(response.getContentAsString()).get("code").asText())
        .isEqualTo("RATE_LIMITED");
    assertThat(Integer.parseInt(response.getHeader("Retry-After"))).isBetween(1, 60);
    assertThat(response.getContentAsString()).doesNotContain("149040");
  }

  @Test
  void theSixthApprovalOrVoidPreviewOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    Chain chain = w.ready();
    // One approval preview, the approval, then four void previews share the preview bucket.
    String pkg = w.approve(chain).get("packageId").asText();
    for (int i = 0; i < 4; i++) {
      w.c.expect(
          w.voidPreviewRequest(
              pkg, PayrollPackageWorld.voidCommand("OTHER_CONTROLLED", EXPLANATION)),
          200);
    }
    List<Integer> before = w.evidence();
    limited(
        w.c.call(
            w.voidPreviewRequest(
                pkg, PayrollPackageWorld.voidCommand("OTHER_CONTROLLED", EXPLANATION))));
    limited(w.c.call(w.previewRequest(chain)));
    assertThat(w.evidence()).isEqualTo(before);
  }

  @Test
  void theThirdApprovalOrVoidOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    Chain chain = w.ready();
    JsonNode preview = w.preview(chain);
    // The approval (first write) and a refused void (second) spend the shared write bucket.
    String pkg =
        w.c.expect(
                w.approveRequest(
                    chain, Organizations.newKey(), PayrollPackageWorld.approving(preview)),
                201)
            .get("packageId")
            .asText();
    Map<String, Object> forged = PayrollPackageWorld.voidCommand("OTHER_CONTROLLED",
        EXPLANATION);
    forged.put("packageVersion", 99);
    forged.put("approvalDigest", "a".repeat(64));
    forged.put("previewDigest", "b".repeat(64));
    forged.put("expiresAt", "2999-01-01T00:00:00Z");
    forged.put("noPaymentAcknowledged", true);
    MockHttpServletResponse refused =
        w.c.call(w.voidRequest(pkg, Organizations.newKey(), forged));
    assertThat(refused.getStatus()).isEqualTo(409);
    List<Integer> before = w.evidence();

    limited(w.c.call(w.voidRequest(pkg, Organizations.newKey(), forged)));
    limited(
        w.c.call(
            w.approveRequest(
                chain, Organizations.newKey(), PayrollPackageWorld.approving(preview))));
    assertThat(w.evidence()).isEqualTo(before);
    // Reads and downloads have their own buckets.
    w.c.expect(PayrollRunWorld.get(w.admin, w.packagesPath()), 200);
    w.pdf(w.admin, w.slipPdfPath(pkg, w.slipOf(pkg, chain.employee())));
  }

  @Test
  void theThirtyFirstDownloadOfAMinuteIsRateLimitedAndAnotherSubjectIsNot() throws Exception {
    Linked linked = w.readyLinked();
    String pkg = w.approve(linked.chain()).get("packageId").asText();
    String slip = w.slipOf(pkg, linked.chain().employee());
    String path = w.slipPdfPath(pkg, slip);
    for (int i = 0; i < 30; i++) {
      w.pdf(w.admin, path);
    }
    int downloads = w.audits("payslip.download");
    assertThat(downloads).isEqualTo(30);
    limited(w.c.call(PayrollRunWorld.get(w.admin, path)));
    limited(w.c.call(PayrollRunWorld.get(w.admin, path + "?locale=fr")));
    assertThat(w.audits("payslip.download")).isEqualTo(downloads);
    // The employee has a bucket of their own.
    w.pdf(linked.person().bearer(), PayrollPackageWorld.minePdfPath(slip));
    assertThat(w.audits("payslip.download")).isEqualTo(downloads + 1);
    // Reads are not downloads.
    w.c.expect(PayrollRunWorld.get(w.admin, w.slipPath(pkg, slip)), 200);
  }

  @Test
  void theSixtyFirstReadOfAMinuteIsRateLimited() throws Exception {
    Chain chain = w.ready();
    String pkg = w.approve(chain).get("packageId").asText();
    for (int i = 0; i < 60; i++) {
      w.c.expect(PayrollRunWorld.get(w.admin, w.packagesPath() + "?limit=1"), 200);
    }
    limited(w.c.call(PayrollRunWorld.get(w.admin, w.packagesPath() + "?limit=1")));
    limited(w.c.call(PayrollRunWorld.get(w.admin, w.packagePath(pkg))));
    limited(w.c.call(PayrollRunWorld.get(w.admin, w.slipsPath(pkg))));
  }

  @Test
  void theEmployeeReadsHaveTheirOwnBucketPerSubject() throws Exception {
    Linked linked = w.readyLinked();
    w.approve(linked.chain());
    String mine = linked.person().bearer();
    for (int i = 0; i < 60; i++) {
      w.c.expect(PayrollRunWorld.get(mine, PayrollPackageWorld.ME + "?limit=1"), 200);
    }
    limited(w.c.call(PayrollRunWorld.get(mine, PayrollPackageWorld.ME + "?limit=1")));
    // The administrator, another subject of the same tenant, is not affected.
    w.c.expect(PayrollRunWorld.get(w.admin, w.packagesPath()), 200);
  }
}
