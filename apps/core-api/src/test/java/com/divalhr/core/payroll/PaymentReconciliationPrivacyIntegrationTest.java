package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.application.PaymentReconciliationWorker;
import com.divalhr.core.payroll.application.PaymentSubmissionWorker;
import com.divalhr.core.payroll.domain.PaymentInquiryOutcome;
import com.divalhr.core.payroll.domain.PaymentReconciliationDigests;
import com.divalhr.core.support.FakePaymentProviderGateway;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-095C (Issue #190, ADR 0038): no sentinel account number, phone number, holder name, amount,
 * provider reference, signature or raw payment request ID reaches the audit trail, the outbox, the
 * idempotency store, a log line, an error, a cursor or any read, and the reconciliation rows hold
 * no payment data at all. Public responses show only the 12-hex request fingerprint, never the
 * request ID or the inquiry ID. Sentinel values make a leak detectable. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
@PaymentSubmissionWorld.HighLimits
@PaymentReconciliationWorld.HighLimits
class PaymentReconciliationPrivacyIntegrationTest {

  private static final String SIGNING_KEY = "test-only-payment-submission-signing-key-001";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private FakePaymentProviderGateway provider;
  @Autowired private PaymentSubmissionWorker submissions;
  @Autowired private PaymentReconciliationWorker worker;

  private PayrollPackageWorld w;
  private PaymentWorld p;

  @BeforeEach
  void world() {
    PaymentReconciliationWorld.drain(clock, provider, submissions, worker);
  }

  @AfterEach
  void reset() {
    clock.reset();
    provider.reset();
  }

  private static String decoded(String cursor) {
    try {
      return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.ISO_8859_1);
    } catch (IllegalArgumentException notBase64) {
      return "";
    }
  }

  @Test
  void noSentinelOrRawIdentifierReachesAnyStoreLogErrorCursorOrRead(CapturedOutput output)
      throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    PaymentSubmissionWorld s = new PaymentSubmissionWorld(p);
    PaymentReconciliationWorld r = new PaymentReconciliationWorld(s);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    CompensationWorld.Person ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    String pkg =
        w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    String batch = p.prepare(pkg).get("batchId").asText();
    long amount =
        w.c
            .expect(PayrollRunWorld.get(w.admin, p.batchPath(batch)), 200)
            .get("batch")
            .get("totalNetMinor")
            .asLong();

    r.deliveryUnknown(batch, provider, submissions);
    String paymentSignature = provider.calls().get(0).signature();
    provider.reset();
    UUID request = s.requestId(batch);

    clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    List<String> reads = new ArrayList<>();
    List<String> cursors = new ArrayList<>();
    JsonNode preview = r.preview(batch);
    reads.add(preview.toString());

    // Refusals do not echo what they protect.
    Map<String, Object> forged = PaymentReconciliationWorld.committing(preview);
    forged.put("previewDigest", "0".repeat(64));
    Map<String, Object> invalid = new LinkedHashMap<>(forged);
    invalid.put("expectedSubmissionVersion", "x");
    invalid.put("extra", PaymentWorld.ACCOUNT);
    for (MockHttpServletRequestBuilder refused :
        List.of(
            r.commitRequest(w.admin, batch, Organizations.newKey(), forged),
            r.commitRequest(w.admin, batch, Organizations.newKey(), invalid),
            r.getRequest(w.admin, batch),
            r.inquiriesRequest(w.admin, UUID.randomUUID(), null),
            r.previewRequest(w.admin, "not-a-uuid"))) {
      reads.add(w.c.call(refused).getContentAsString());
    }

    reads.add(
        w.c
            .call(
                r.commitRequest(
                    w.admin,
                    batch,
                    Organizations.newKey(),
                    PaymentReconciliationWorld.committing(preview)))
            .getContentAsString());
    reads.add(r.preview(batch).toString());

    provider.thenInquiry(PaymentInquiryOutcome.TRANSIENT_TIMEOUT);
    provider.thenInquiry(PaymentInquiryOutcome.ACCEPTED_FOR_PROCESSING);
    worker.runOnce();
    clock.set(clock.instant().plus(Duration.ofSeconds(31)));
    worker.runOnce();

    JsonNode view = r.view(batch);
    reads.add(view.toString());
    JsonNode page = r.inquiries(batch, "limit=2");
    reads.add(page.toString());
    String cursor = page.get("nextCursor").asText();
    cursors.add(cursor);
    cursors.add(decoded(cursor));
    reads.add(r.inquiries(batch, "limit=2&cursor=" + cursor).toString());
    MockHttpServletResponse bad =
        w.c.call(r.inquiriesRequest(w.admin, batch, "limit=2&cursor=" + cursor + "x"));
    reads.add(bad.getContentAsString());

    UUID inquiry = r.inquiryId(batch);
    String inquirySignature = provider.inquiryCalls().get(0).signature();
    assertThat(provider.inquiryCalls()).hasSize(2);
    assertThat(provider.calls()).isEmpty();

    // Public responses show only the short fingerprint.
    assertThat(view.get("requestFingerprint").asText())
        .matches("[0-9a-f]{12}")
        .isEqualTo(PaymentReconciliationDigests.requestFingerprint(request))
        .isEqualTo(preview.get("requestFingerprint").asText());
    assertThat(view.has("requestId")).isFalse();
    assertThat(view.has("inquiryId")).isFalse();

    String publicText = String.join("\n", reads) + "\n" + String.join("\n", cursors);
    String persisted = r.persistedEvidence();
    String rows = r.rows();
    String logs = output.getAll();
    List<String> ids =
        List.of(
            request.toString(),
            request.toString().replace("-", ""),
            inquiry.toString(),
            inquiry.toString().replace("-", ""));
    List<String> secrets =
        List.of(
            PaymentWorld.ACCOUNT,
            PaymentWorld.PHONE,
            PaymentWorld.HOLDER,
            "KIN-01",
            paymentSignature,
            inquirySignature,
            SIGNING_KEY);
    String amountToken = "(?<![0-9A-Za-z.\\-])" + amount + "(?![0-9A-Za-z.\\-])";

    for (String secret : secrets) {
      assertThat(publicText).as("reads leak " + secret).doesNotContain(secret);
      assertThat(persisted).as("stores leak " + secret).doesNotContain(secret);
      assertThat(rows).as("rows leak " + secret).doesNotContain(secret);
      assertThat(logs).as("logs leak " + secret).doesNotContain(secret);
    }
    for (String id : ids) {
      assertThat(publicText).as("reads leak " + id).doesNotContain(id);
      assertThat(persisted).as("stores leak " + id).doesNotContain(id);
      assertThat(logs).as("logs leak " + id).doesNotContain(id);
    }
    assertThat(publicText).doesNotContainPattern(amountToken);
    assertThat(persisted).doesNotContainPattern(amountToken);
    assertThat(rows).doesNotContainPattern(amountToken);
    assertThat(publicText + persisted + rows)
        .doesNotContainPattern(
            "(?i)\"?(amount|totalMinor|totalNetMinor|accountNumber|providerReference)\"?\\s*:");
    assertThat(persisted).contains("payment-reconciliation").isNotBlank();
    // The rows hold only the identifiers the submission already had.
    assertThat(rows).contains(request.toString());
  }
}
