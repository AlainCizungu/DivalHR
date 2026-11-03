package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-095B fixtures over the public API on top of {@link PaymentWorld}: submission preview, commit,
 * read and history calls, and direct reads of the submission rows for state assertions. Synthetic
 * data only; no provider is contacted (the shared test context uses a scripted connector).
 */
final class PaymentSubmissionWorld {

  /** Raises the three submission buckets so that setup never meets a limit. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @TestPropertySource(
      properties = {
        "divalhr.request-limits.subject.payment-submission-preview=600",
        "divalhr.request-limits.subject.payment-submission-write=600",
        "divalhr.request-limits.subject.payment-submission-read=600"
      })
  @interface HighLimits {}

  final PaymentWorld p;
  final PayrollPackageWorld w;
  final CompensationWorld c;

  PaymentSubmissionWorld(PaymentWorld p) {
    this.p = p;
    this.w = p.w;
    this.c = p.c;
  }

  String path(Object batch) {
    return p.batchPath(batch);
  }

  MockHttpServletRequestBuilder previewRequest(String bearer, Object batch) throws Exception {
    return CompensationWorld.json(path(batch) + "/submissions/preview", bearer, null, Map.of());
  }

  static Map<String, Object> committing(JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedBatchVersion", preview.get("batchVersion").asInt());
    body.put("batchDigest", preview.get("batchDigest").asText());
    body.put("previewDigest", PayrollRunWorld.textOrForged(preview, "previewDigest"));
    body.put("expiresAt", PayrollRunWorld.textOrForged(preview, "expiresAt"));
    body.put("reviewedProviderAndTotals", true);
    body.put("understandAcceptedIsNotPaid", true);
    return body;
  }

  MockHttpServletRequestBuilder commitRequest(
      String bearer, Object batch, String key, Map<String, Object> body) throws Exception {
    return CompensationWorld.json(path(batch) + "/submissions", bearer, key, body);
  }

  JsonNode preview(Object batch) throws Exception {
    return c.expect(previewRequest(w.admin, batch), 200);
  }

  /** Previews (READY) and commits with a fresh key; returns the receipt. */
  JsonNode submit(Object batch) throws Exception {
    JsonNode preview = preview(batch);
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    return c.expect(
        commitRequest(w.admin, batch, Organizations.newKey(), committing(preview)), 201);
  }

  JsonNode view(Object batch) throws Exception {
    return c.expect(PayrollRunWorld.get(w.admin, path(batch) + "/submission"), 200);
  }

  JsonNode attempts(Object batch) throws Exception {
    return c.expect(PayrollRunWorld.get(w.admin, path(batch) + "/submission/attempts"), 200);
  }

  UUID requestId(Object batch) {
    return w.jdbc.queryForObject(
        "SELECT request_id FROM payroll.payment_submission WHERE tenant_id = ? AND batch_id = ?",
        UUID.class,
        w.tenant,
        UUID.fromString(batch.toString()));
  }

  String state(Object batch) {
    return w.jdbc.queryForObject(
        "SELECT state FROM payroll.payment_submission WHERE tenant_id = ? AND batch_id = ?",
        String.class,
        w.tenant,
        UUID.fromString(batch.toString()));
  }

  /** Submissions, evidence rows, audit rows and events of the tenant. */
  List<Integer> evidence() {
    return List.of(
        c.count("SELECT count(*) FROM payroll.payment_submission WHERE tenant_id = ?", w.tenant),
        c.count(
            "SELECT count(*) FROM payroll.payment_submission_attempt WHERE tenant_id = ?",
            w.tenant),
        c.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('payment-submission.create', 'payment-submission.attempt')",
            w.tenant),
        c.count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.payment-submission.%'",
            w.tenant));
  }

  /** The audit metadata, outbox envelopes and idempotency bodies of submissions, as one text. */
  String persistedEvidence() {
    List<String> rows =
        new java.util.ArrayList<>(
            w.jdbc.queryForList(
                "SELECT metadata::text || coalesce(after_state_sha256, '') || actor_subject"
                    + " FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action LIKE 'payment-submission.%'",
                String.class, w.tenant));
    rows.addAll(
        w.jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.payment-submission.%'",
            String.class, w.tenant));
    rows.addAll(
        w.jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation LIKE 'payment-submission%' AND response_body IS NOT NULL",
            String.class));
    rows.addAll(
        w.jdbc.queryForList(
            "SELECT coalesce(outcome_code, '') || coalesce(provider_reference, '') || state"
                + " || lease_owner FROM payroll.payment_submission WHERE tenant_id = ?"
                + " AND lease_owner IS NOT NULL",
            String.class,
            w.tenant));
    return String.join("\n", rows);
  }
}
