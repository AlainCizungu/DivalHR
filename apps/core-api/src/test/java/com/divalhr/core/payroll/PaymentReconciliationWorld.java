package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.application.PaymentReconciliationWorker;
import com.divalhr.core.payroll.application.PaymentSubmissionWorker;
import com.divalhr.core.payroll.domain.PaymentSubmissionOutcome;
import com.divalhr.core.support.FakePaymentProviderGateway;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-095C fixtures over the public API on top of {@link PaymentSubmissionWorld}: reconciliation
 * preview, creation, current-state and inquiry-history calls, the drive of a submission into an
 * uncertain state through the real submission worker and the scripted connector, and direct reads
 * of the reconciliation rows for state assertions. Synthetic data only; no provider is contacted
 * (the shared test context uses a scripted connector).
 */
final class PaymentReconciliationWorld {

  /** Raises the three reconciliation buckets so that setup never meets a limit. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @TestPropertySource(
      properties = {
        "divalhr.request-limits.subject.payment-reconciliation-preview=600",
        "divalhr.request-limits.subject.payment-reconciliation-write=600",
        "divalhr.request-limits.subject.payment-reconciliation-read=600"
      })
  @interface HighLimits {}

  /** The two states of a submission that may be investigated. */
  static final List<String> UNCERTAIN = List.of("DELIVERY_UNKNOWN", "MANUAL_INTERVENTION");

  final PaymentSubmissionWorld s;
  final PaymentWorld p;
  final PayrollPackageWorld w;
  final CompensationWorld c;

  PaymentReconciliationWorld(PaymentSubmissionWorld s) {
    this.s = s;
    this.p = s.p;
    this.w = s.w;
    this.c = s.c;
  }

  // ------------------------------------------------------------------------------------------
  // Drain and fixtures
  // ------------------------------------------------------------------------------------------

  /**
   * Settles submissions and reconciliations left by other tests (they would consume scripted
   * results), then forgets every call and script.
   */
  static void drain(
      SettableClock clock,
      FakePaymentProviderGateway provider,
      PaymentSubmissionWorker submissions,
      PaymentReconciliationWorker reconciliations) {
    provider.reset();
    clock.set(Instant.now().plus(Duration.ofDays(3650)));
    for (int i = 0; i < 50 && submissions.runOnce() > 0; i++) {
      // drain submissions of other tests
    }
    for (int i = 0; i < 50 && reconciliations.runOnce() > 0; i++) {
      // drain reconciliations of other tests
    }
    clock.reset();
    provider.reset();
  }

  /**
   * Submits the batch and lets the real worker meet a timeout after the write, which is an unknown
   * delivery that is never blindly resent.
   */
  void deliveryUnknown(
      Object batch, FakePaymentProviderGateway provider, PaymentSubmissionWorker worker)
      throws Exception {
    provider.then(PaymentSubmissionOutcome.UNKNOWN_TIMEOUT, null);
    s.submit(batch);
    worker.runOnce();
    assertThat(s.state(batch)).isEqualTo("DELIVERY_UNKNOWN");
  }

  /**
   * Submits the batch, damages the sealed destination (bypassing the triggers, as a fault would)
   * and lets the real worker hand it to a person: MANUAL_INTERVENTION without any send.
   */
  void manualIntervention(
      Object batch,
      UUID employee,
      PlatformTransactionManager transactions,
      PaymentSubmissionWorker worker)
      throws Exception {
    s.submit(batch);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              w.jdbc.execute("SET LOCAL session_replication_role = replica");
              w.jdbc.update(
                  "UPDATE payroll.payment_destination SET sealed = decode(repeat('ab', 40), 'hex')"
                      + " WHERE tenant_id = ? AND employee_id = ?",
                  w.tenant,
                  employee);
            });
    worker.runOnce();
    assertThat(s.state(batch)).isEqualTo("MANUAL_INTERVENTION");
  }

  // ------------------------------------------------------------------------------------------
  // Requests
  // ------------------------------------------------------------------------------------------

  String path(Object batch) {
    return s.path(batch) + "/submission/reconciliation";
  }

  MockHttpServletRequestBuilder previewRequest(String bearer, Object batch) throws Exception {
    return CompensationWorld.json(path(batch) + "/preview", bearer, null, Map.of());
  }

  /**
   * The committing body of a preview. A blocked preview carries no digest or expiry, so a
   * well-formed forged one is sent; a non-uncertain submission state is replaced by an eligible
   * one, because the contract only accepts the two uncertain states.
   */
  static Map<String, Object> committing(JsonNode preview) {
    String state = preview.get("submissionState").asText();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedSubmissionVersion", preview.get("submissionVersion").asInt());
    body.put("expectedSubmissionState", UNCERTAIN.contains(state) ? state : "DELIVERY_UNKNOWN");
    body.put("previewDigest", PayrollRunWorld.textOrForged(preview, "previewDigest"));
    body.put("expiresAt", PayrollRunWorld.textOrForged(preview, "expiresAt"));
    body.put("understandInquiryDoesNotResend", true);
    body.put("understandAcceptedIsNotPaid", true);
    return body;
  }

  MockHttpServletRequestBuilder commitRequest(String bearer, Object batch, String key, Object body)
      throws Exception {
    return CompensationWorld.json(path(batch), bearer, key, body);
  }

  MockHttpServletRequestBuilder getRequest(String bearer, Object batch) {
    return PayrollRunWorld.get(bearer, path(batch));
  }

  MockHttpServletRequestBuilder inquiriesRequest(String bearer, Object batch, String query) {
    return PayrollRunWorld.get(
        bearer, path(batch) + "/inquiries" + (query == null ? "" : "?" + query));
  }

  JsonNode preview(Object batch) throws Exception {
    return c.expect(previewRequest(w.admin, batch), 200);
  }

  /** Previews (READY) and opens an investigation with a fresh key; returns the receipt. */
  JsonNode open(Object batch) throws Exception {
    JsonNode preview = preview(batch);
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    return c.expect(
        commitRequest(
            w.admin, batch, com.divalhr.core.support.Organizations.newKey(), committing(preview)),
        201);
  }

  JsonNode view(Object batch) throws Exception {
    return c.expect(getRequest(w.admin, batch), 200);
  }

  JsonNode inquiries(Object batch) throws Exception {
    return c.expect(inquiriesRequest(w.admin, batch, null), 200);
  }

  JsonNode inquiries(Object batch, String query) throws Exception {
    return c.expect(inquiriesRequest(w.admin, batch, query), 200);
  }

  /** A bearer for a subject with the given claimed roles and assurance (memberships granted). */
  static String bearer(UUID tenant, String subject, Object acr, String... roles) {
    List<String> claimed = List.of(roles);
    if (claimed.contains("tenant-admin")) {
      Memberships.grant(tenant, subject, "tenant-admin");
    } else if (claimed.contains("employee")) {
      Memberships.grant(tenant, subject, "employee");
    }
    return "Bearer "
        + TestTokens.token().tenant(tenant).subject(subject).roles(claimed).acr(acr).build();
  }

  // ------------------------------------------------------------------------------------------
  // Direct reads
  // ------------------------------------------------------------------------------------------

  UUID reconciliationId(Object batch) {
    return w.jdbc.queryForObject(
        "SELECT id FROM payroll.payment_reconciliation WHERE tenant_id = ? AND batch_id = ?",
        UUID.class,
        w.tenant,
        UUID.fromString(batch.toString()));
  }

  UUID inquiryId(Object batch) {
    return w.jdbc.queryForObject(
        "SELECT inquiry_id FROM payroll.payment_reconciliation WHERE tenant_id = ?"
            + " AND batch_id = ?",
        UUID.class,
        w.tenant,
        UUID.fromString(batch.toString()));
  }

  String state(Object batch) {
    return w.jdbc.queryForObject(
        "SELECT state FROM payroll.payment_reconciliation WHERE tenant_id = ? AND batch_id = ?",
        String.class,
        w.tenant,
        UUID.fromString(batch.toString()));
  }

  int submissionVersion(Object batch) {
    Integer version =
        w.jdbc.queryForObject(
            "SELECT version FROM payroll.payment_submission WHERE tenant_id = ? AND batch_id = ?",
            Integer.class,
            w.tenant,
            UUID.fromString(batch.toString()));
    return version == null ? 0 : version;
  }

  /** Every submission and attempt row of the tenant, as one text (compared row for row). */
  String submissionSnapshot() {
    List<String> rows =
        new ArrayList<>(
            w.jdbc.queryForList(
                "SELECT to_jsonb(t)::text FROM payroll.payment_submission t WHERE tenant_id = ?"
                    + " ORDER BY 1",
                String.class,
                w.tenant));
    rows.addAll(
        w.jdbc.queryForList(
            "SELECT to_jsonb(t)::text FROM payroll.payment_submission_attempt t"
                + " WHERE tenant_id = ? ORDER BY 1",
            String.class,
            w.tenant));
    return String.join("\n", rows);
  }

  /** Every reconciliation and inquiry row of the tenant, as one text. */
  String rows() {
    List<String> rows =
        new ArrayList<>(
            w.jdbc.queryForList(
                "SELECT to_jsonb(t)::text FROM payroll.payment_reconciliation t"
                    + " WHERE tenant_id = ? ORDER BY 1",
                String.class,
                w.tenant));
    rows.addAll(
        w.jdbc.queryForList(
            "SELECT to_jsonb(t)::text FROM payroll.payment_reconciliation_inquiry t"
                + " WHERE tenant_id = ? ORDER BY 1",
            String.class,
            w.tenant));
    return String.join("\n", rows);
  }

  /** Reconciliations, evidence rows, audit rows and events of the tenant. */
  List<Integer> evidence() {
    return List.of(
        c.count(
            "SELECT count(*) FROM payroll.payment_reconciliation WHERE tenant_id = ?", w.tenant),
        c.count(
            "SELECT count(*) FROM payroll.payment_reconciliation_inquiry WHERE tenant_id = ?",
            w.tenant),
        c.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('payment-reconciliation.create',"
                + " 'payment-reconciliation.inquiry')",
            w.tenant),
        c.count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.payment-reconciliation.%'",
            w.tenant));
  }

  int audits(String action) {
    return w.audits(action);
  }

  /** The audit metadata, outbox envelopes and idempotency bodies of reconciliations, as text. */
  String persistedEvidence() {
    List<String> rows =
        new ArrayList<>(
            w.jdbc.queryForList(
                "SELECT metadata::text || coalesce(after_state_sha256, '') || actor_subject"
                    + " FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action LIKE 'payment-reconciliation.%'",
                String.class, w.tenant));
    rows.addAll(
        w.jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.payment-reconciliation.%'",
            String.class, w.tenant));
    rows.addAll(
        w.jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation LIKE 'payment-reconciliation%' AND response_body IS NOT NULL",
            String.class));
    return String.join("\n", rows);
  }

  /** A problem body without the parts that legitimately differ between two refusals. */
  static List<String> refusal(JsonNode problem) {
    return List.of(
        problem.get("status").asText(),
        problem.get("title").asText(),
        problem.get("code").asText(),
        problem.get("params").toString());
  }
}
