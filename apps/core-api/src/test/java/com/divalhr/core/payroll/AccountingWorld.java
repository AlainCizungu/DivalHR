package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-096 (Issue #194, ADR 0039) test fixtures over the public API, on top of {@link
 * PayrollPackageWorld}: the reference mapping, the mapping and journal preview and commit calls of
 * one approved package, and the paths of the accounting resources. Synthetic data only.
 */
final class AccountingWorld {

  /** The reference mapping: five distinct synthetic account codes. */
  static final Map<String, Object> ACCOUNTS =
      Map.of(
          "salaryExpense", "6100",
          "employeeDeductionLiability", "4310",
          "employerContributionExpense", "6450",
          "employerContributionLiability", "4320",
          "netSalaryPayable", "4210");

  final PayrollPackageWorld w;
  final String pkg;

  AccountingWorld(PayrollPackageWorld w, String pkg) {
    this.w = w;
    this.pkg = pkg;
  }

  String mappings() {
    return "/api/v1/legal-entities/" + w.entity + "/accounting-mappings";
  }

  String journals() {
    return PayrollPackageWorld.packagesPath(w.entity) + "/" + pkg + "/accounting-journals";
  }

  String journalPath(String journal) {
    return "/api/v1/legal-entities/" + w.entity + "/accounting-journals/" + journal;
  }

  static Map<String, Object> mappingBody(String from, Map<String, Object> accounts) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", "CDF");
    body.put("effectiveFrom", from);
    body.put("accounts", accounts);
    return body;
  }

  MockHttpServletRequestBuilder mappingPreviewRequest(String from, Map<String, Object> accounts)
      throws Exception {
    return CompensationWorld.json(
        mappings() + "/preview", w.admin, null, mappingBody(from, accounts));
  }

  /** The committing body of a mapping preview. */
  static Map<String, Object> mappingCommitting(
      String from, Map<String, Object> accounts, JsonNode preview) {
    Map<String, Object> body = mappingBody(from, accounts);
    body.put("expectedVersion", preview.get("nextVersion").asInt());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("understandExportOnly", true);
    return body;
  }

  /** Previews (READY) and commits a mapping version with a fresh key; returns the receipt. */
  JsonNode createMapping(String from, Map<String, Object> accounts) throws Exception {
    JsonNode preview = w.c.expect(mappingPreviewRequest(from, accounts), 200);
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    return w.c.expect(
        CompensationWorld.json(
            mappings(),
            w.admin,
            Organizations.newKey(),
            mappingCommitting(from, accounts, preview)),
        201);
  }

  JsonNode createMapping(String from) throws Exception {
    return createMapping(from, ACCOUNTS);
  }

  JsonNode journalPreview() throws Exception {
    return w.c.expect(
        CompensationWorld.json(journals() + "/preview", w.admin, null, Map.of()), 200);
  }

  /** The committing body of a journal preview. */
  static Map<String, Object> journalBody(JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedPackageVersion", preview.get("packageVersion").asInt());
    body.put("expectedMappingVersion", preview.get("mapping").get("version").asInt());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("understandExportOnly", true);
    return body;
  }

  MockHttpServletRequestBuilder generate(String key, Map<String, Object> body) throws Exception {
    return CompensationWorld.json(journals(), w.admin, key, body);
  }

  /** Previews (READY) and generates the journal with a fresh key; returns the receipt. */
  JsonNode generated() throws Exception {
    JsonNode preview = journalPreview();
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    return w.c.expect(generate(Organizations.newKey(), journalBody(preview)), 201);
  }

  int journalCount() {
    return w.c.count(
        "SELECT count(*) FROM payroll.accounting_journal WHERE tenant_id = ?", w.tenant);
  }

  int lineCount() {
    return w.c.count(
        "SELECT count(*) FROM payroll.accounting_journal_line WHERE tenant_id = ?", w.tenant);
  }

  int mappingCount() {
    return w.c.count(
        "SELECT count(*) FROM payroll.accounting_mapping WHERE tenant_id = ?", w.tenant);
  }

  /** Outbox events of one type of the tenant. */
  int events(String type) {
    return w.c.count(
        "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ? AND event_type = ?",
        w.tenant,
        type);
  }
}
