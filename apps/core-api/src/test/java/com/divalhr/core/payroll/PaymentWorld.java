package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-095A test fixtures over the public API, on top of {@link PayrollPackageWorld}: destination
 * commands (bank account, mobile money, manual), their preview and commit calls, the batch preview,
 * prepare, cancel, list and download calls. Account numbers, phones and holder names are synthetic
 * sentinels so that scans for leaks are exact.
 */
final class PaymentWorld {

  /** Raises every payment bucket to 600 per minute so that setup never meets a limit. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @TestPropertySource(
      properties = {
        "divalhr.request-limits.subject.payment-read=600",
        "divalhr.request-limits.subject.payment-destination-preview=600",
        "divalhr.request-limits.subject.payment-destination-write=600",
        "divalhr.request-limits.subject.payment-batch-preview=600",
        "divalhr.request-limits.subject.payment-batch-write=600",
        "divalhr.request-limits.subject.payment-register-download=600"
      })
  @interface HighLimits {}

  /** Synthetic sentinels that must never appear in logs, audit, events or receipts. */
  static final String ACCOUNT = "SYNTHACCT0099887766";

  static final String ACCOUNT_2 = "SYNTHACCT0011223344";
  static final String PHONE = "+243990001122";
  static final String HOLDER = "Sentinel Holder Zed";

  final PayrollPackageWorld w;
  final CompensationWorld c;

  PaymentWorld(PayrollPackageWorld w) {
    this.w = w;
    this.c = w.c;
  }

  // ------------------------------------------------------------------------------------------
  // Paths
  // ------------------------------------------------------------------------------------------

  static String destinationPath(Object employee) {
    return "/api/v1/employees/" + employee + "/payment-destination";
  }

  static final String MINE = "/api/v1/me/payment-destination";

  String batchesPath() {
    return "/api/v1/legal-entities/" + w.entity + "/payment-batches";
  }

  String batchPath(Object batch) {
    return batchesPath() + "/" + batch;
  }

  String packageBatchesPath(Object pkg) {
    return w.packagePath(pkg) + "/payment-batches";
  }

  // ------------------------------------------------------------------------------------------
  // Destination commands
  // ------------------------------------------------------------------------------------------

  /** A day inside the valid window that is before the reference approval date. */
  LocalDate early() {
    return w.month.atDay(1).minusDays(20);
  }

  Map<String, Object> bank(String account, LocalDate from, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("method", "BANK_ACCOUNT");
    body.put("countryCode", "CD");
    body.put("providerCode", "RAWBANK");
    body.put("accountNumber", account);
    body.put("holderName", HOLDER);
    body.put("branchCode", "KIN-01");
    body.put("effectiveFrom", from.toString());
    body.put("reasonCode", reason);
    return body;
  }

  Map<String, Object> mobile(String phone, LocalDate from, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("method", "MOBILE_MONEY");
    body.put("countryCode", "CD");
    body.put("providerCode", "MPESA");
    body.put("phoneNumber", phone);
    body.put("holderName", HOLDER);
    body.put("effectiveFrom", from.toString());
    body.put("reasonCode", reason);
    return body;
  }

  Map<String, Object> manual(LocalDate from, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("method", "MANUAL");
    body.put("effectiveFrom", from.toString());
    body.put("reasonCode", reason);
    return body;
  }

  MockHttpServletRequestBuilder destinationPreview(
      String bearer, Object employee, Map<String, Object> command) throws Exception {
    return CompensationWorld.json(destinationPath(employee) + "/preview", bearer, null, command);
  }

  /** The committing body of a destination preview: the command plus the evidence. */
  static Map<String, Object> committing(Map<String, Object> command, JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asInt());
    body.put("previewDigest", PayrollRunWorld.textOrForged(preview, "previewDigest"));
    body.put("expiresAt", PayrollRunWorld.textOrForged(preview, "expiresAt"));
    body.put("reviewedDetails", true);
    return body;
  }

  MockHttpServletRequestBuilder destinationCommit(
      String bearer, Object employee, String key, Map<String, Object> body) throws Exception {
    return CompensationWorld.json(destinationPath(employee) + "/commit", bearer, key, body);
  }

  JsonNode previewDestination(Object employee, Map<String, Object> command) throws Exception {
    return c.expect(destinationPreview(w.admin, employee, command), 200);
  }

  /** Previews (READY) and commits a destination with a fresh key; returns the receipt. */
  JsonNode record(Object employee, Map<String, Object> command) throws Exception {
    JsonNode preview = previewDestination(employee, command);
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    return c.expect(
        destinationCommit(w.admin, employee, Organizations.newKey(), committing(command, preview)),
        201);
  }

  JsonNode current(Object employee) throws Exception {
    return c.expect(PayrollRunWorld.get(w.admin, destinationPath(employee)), 200);
  }

  // ------------------------------------------------------------------------------------------
  // Batch commands
  // ------------------------------------------------------------------------------------------

  MockHttpServletRequestBuilder batchPreview(String bearer, Object pkg) throws Exception {
    return CompensationWorld.json(packageBatchesPath(pkg) + "/preview", bearer, null, Map.of());
  }

  static Map<String, Object> preparing(JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", preview.get("expectedVersion").asInt());
    body.put("packageVersion", preview.get("packageVersion").asInt());
    body.put("previewDigest", PayrollRunWorld.textOrForged(preview, "previewDigest"));
    body.put("expiresAt", PayrollRunWorld.textOrForged(preview, "expiresAt"));
    body.put("reviewedTotalsAndDestinations", true);
    body.put("noMoneySentAcknowledged", true);
    return body;
  }

  MockHttpServletRequestBuilder prepareRequest(
      String bearer, Object pkg, String key, Map<String, Object> body) throws Exception {
    return CompensationWorld.json(packageBatchesPath(pkg), bearer, key, body);
  }

  JsonNode previewBatch(Object pkg) throws Exception {
    return c.expect(batchPreview(w.admin, pkg), 200);
  }

  /** Previews (READY) and prepares a batch with a fresh key; returns the receipt. */
  JsonNode prepare(Object pkg) throws Exception {
    JsonNode preview = previewBatch(pkg);
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    return c.expect(prepareRequest(w.admin, pkg, Organizations.newKey(), preparing(preview)), 201);
  }

  static Map<String, Object> cancelCommand(String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reasonCode", reason);
    return body;
  }

  MockHttpServletRequestBuilder cancelPreview(String bearer, Object batch, String reason)
      throws Exception {
    return CompensationWorld.json(
        batchPath(batch) + "/cancel/preview", bearer, null, cancelCommand(reason));
  }

  static Map<String, Object> cancelling(JsonNode preview, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reasonCode", reason);
    body.put("batchVersion", preview.get("batchVersion").asInt());
    body.put("batchDigest", preview.get("batchDigest").asText());
    body.put("previewDigest", PayrollRunWorld.textOrForged(preview, "previewDigest"));
    body.put("expiresAt", PayrollRunWorld.textOrForged(preview, "expiresAt"));
    body.put("noMoneySentAcknowledged", true);
    return body;
  }

  MockHttpServletRequestBuilder cancelRequest(
      String bearer, Object batch, String key, Map<String, Object> body) throws Exception {
    return CompensationWorld.json(batchPath(batch) + "/cancel", bearer, key, body);
  }

  /** Previews (READY) and cancels a batch; returns the receipt. */
  JsonNode cancel(Object batch, String reason) throws Exception {
    JsonNode preview = c.expect(cancelPreview(w.admin, batch, reason), 200);
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    return c.expect(
        cancelRequest(w.admin, batch, Organizations.newKey(), cancelling(preview, reason)), 201);
  }

  MockHttpServletResponse download(String bearer, Object batch, String file) throws Exception {
    return c.call(PayrollRunWorld.get(bearer, batchPath(batch) + "/" + file));
  }

  // ------------------------------------------------------------------------------------------
  // Evidence
  // ------------------------------------------------------------------------------------------

  /** Destinations, batches, lines, cancellations, audit rows and events of the tenant. */
  List<Integer> evidence() {
    return List.of(
        c.count("SELECT count(*) FROM payroll.payment_destination WHERE tenant_id = ?", w.tenant),
        c.count("SELECT count(*) FROM payroll.payment_batch WHERE tenant_id = ?", w.tenant),
        c.count("SELECT count(*) FROM payroll.payment_batch_line WHERE tenant_id = ?", w.tenant),
        c.count("SELECT count(*) FROM payroll.payment_batch_cancel WHERE tenant_id = ?", w.tenant),
        c.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('payment-destination.record', 'payment-batch.prepare',"
                + " 'payment-batch.cancel')",
            w.tenant),
        c.count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.payment-%'",
            w.tenant));
  }

  /** The audit metadata, outbox envelopes and idempotency bodies of the tenant, as one text. */
  String persistedEvidence() {
    List<String> rows =
        new java.util.ArrayList<>(
            w.jdbc.queryForList(
                "SELECT metadata::text || coalesce(after_state_sha256, '') || actor_subject"
                    + " FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action LIKE 'payment-%'",
                String.class, w.tenant));
    rows.addAll(
        w.jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.payment-%'",
            String.class, w.tenant));
    rows.addAll(
        w.jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation LIKE 'payment-%' AND response_body IS NOT NULL",
            String.class));
    return String.join("\n", rows);
  }

  /** The id of the single destination version row with the given sequence. */
  UUID destinationRow(Object employee, int sequence) {
    return w.jdbc.queryForObject(
        "SELECT id FROM payroll.payment_destination WHERE tenant_id = ? AND employee_id = ?"
            + " AND sequence = ?",
        UUID.class,
        w.tenant,
        UUID.fromString(employee.toString()),
        sequence);
  }
}
