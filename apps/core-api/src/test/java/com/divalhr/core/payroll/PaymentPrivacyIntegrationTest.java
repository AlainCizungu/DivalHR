package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-095A (Issue #183, ADR 0036): no sentinel account number, phone number or holder name reaches
 * the audit trail, the outbox, the idempotency store, a log line, an error or any read, and the
 * register downloads carry masked values only. Sentinel values make a leak detectable. Synthetic
 * data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
class PaymentPrivacyIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollPackageWorld w;
  private PaymentWorld p;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  @Test
  void noSentinelReachesAnyStoreLogErrorOrReadAndTheRegisterIsMasked(CapturedOutput output)
      throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    CompensationWorld.Person ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    String pkg =
        w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();

    // A bank destination, then a mobile-money one, so the history holds both kinds.
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    p.record(
        ana.employee(), p.mobile(PaymentWorld.PHONE, p.early().plusDays(1), "PROVIDER_CHANGE"));
    String batch = p.prepare(pkg).get("batchId").asText();

    // Refusals do not echo what they protect.
    Map<String, Object> invalid = p.bank("bad", p.early().plusDays(2), "OTHER_CONTROLLED");
    MockHttpServletResponse refused =
        w.c.call(p.destinationPreview(w.admin, ana.employee(), invalid));
    String reads =
        String.join(
            "\n",
            refused.getContentAsString(),
            w.c
                .call(PayrollRunWorld.get(w.admin, PaymentWorld.destinationPath(ana.employee())))
                .getContentAsString(),
            w.c
                .call(
                    PayrollRunWorld.get(
                        w.admin, PaymentWorld.destinationPath(ana.employee()) + "/history"))
                .getContentAsString(),
            w.c.call(PayrollRunWorld.get(ana.bearer(), PaymentWorld.MINE)).getContentAsString(),
            w.c.call(PayrollRunWorld.get(w.admin, p.batchesPath())).getContentAsString(),
            w.c.call(PayrollRunWorld.get(w.admin, p.batchPath(batch))).getContentAsString(),
            w.c
                .call(PayrollRunWorld.get(w.admin, p.batchPath(batch) + "/lines"))
                .getContentAsString(),
            p.download(w.admin, batch, "register.csv").getContentAsString(),
            p.download(w.admin, batch, "manifest.json").getContentAsString(),
            p.cancel(batch, "PREPARED_IN_ERROR").toString());

    List<String> secrets = List.of(PaymentWorld.ACCOUNT, PaymentWorld.PHONE);
    for (String secret : secrets) {
      assertThat(reads).as("reads leak " + secret).doesNotContain(secret);
      assertThat(p.persistedEvidence()).as("stores leak " + secret).doesNotContain(secret);
      assertThat(output.getAll()).as("logs leak " + secret).doesNotContain(secret);
    }
    assertThat(p.persistedEvidence()).doesNotContain(PaymentWorld.HOLDER);
    assertThat(output.getAll()).doesNotContain(PaymentWorld.HOLDER);
    // Masked values are what readers see.
    assertThat(reads).contains("****7766");
  }

  @Test
  void aCommittedDestinationLeavesNoPlaintextInItsIdempotencyBodyOrAuditMetadata()
      throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    CompensationWorld.Person ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    Map<String, Object> command = p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP");
    JsonNode preview = p.previewDestination(ana.employee(), command);
    assertThat(preview.toString())
        .doesNotContain(PaymentWorld.ACCOUNT)
        .doesNotContain(PaymentWorld.HOLDER);
    String key = Organizations.newKey();
    JsonNode receipt =
        w.c.expect(
            p.destinationCommit(
                w.admin, ana.employee(), key, PaymentWorld.committing(command, preview)),
            201);
    assertThat(receipt.toString()).doesNotContain(PaymentWorld.ACCOUNT);
    Integer audits =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action = 'payment-destination.record'",
            Integer.class,
            w.tenant);
    assertThat(audits).isEqualTo(1);
    assertThat(p.persistedEvidence())
        .doesNotContain(PaymentWorld.ACCOUNT)
        .doesNotContain(PaymentWorld.HOLDER);
  }
}
