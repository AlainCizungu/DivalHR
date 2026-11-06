package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.domain.AccountingCsv;
import com.divalhr.core.payroll.domain.AccountingDigests;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-096 (Issue #194, ADR 0039) against real PostgreSQL through the public API: the versioned
 * mapping, the balanced journal of an approved package, idempotent replay, the deterministic CSV
 * and manifest, the blockers, authority and the audit trail. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.accounting-preview=600",
      "divalhr.request-limits.subject.accounting-journal-write=600",
      "divalhr.request-limits.subject.accounting-mapping-write=600",
      "divalhr.request-limits.subject.accounting-read=600"
    })
class AccountingApiIntegrationTest {

  private static final Map<String, Object> ACCOUNTS =
      Map.of(
          "salaryExpense", "6100",
          "employeeDeductionLiability", "4310",
          "employerContributionExpense", "6450",
          "employerContributionLiability", "4320",
          "netSalaryPayable", "4210");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollPackageWorld w;
  private String pkg;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    Chain chain = w.ready();
    pkg = w.approve(chain).get("packageId").asText();
  }

  private int journalCount() {
    return w.c.count(
        "SELECT count(*) FROM payroll.accounting_journal WHERE tenant_id = ?", w.tenant);
  }

  private String mappings() {
    return "/api/v1/legal-entities/" + w.entity + "/accounting-mappings";
  }

  private String journals() {
    return PayrollPackageWorld.packagesPath(w.entity) + "/" + pkg + "/accounting-journals";
  }

  private String journalPath(String journal) {
    return "/api/v1/legal-entities/" + w.entity + "/accounting-journals/" + journal;
  }

  private Map<String, Object> mappingBody(String from) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", "CDF");
    body.put("effectiveFrom", from);
    body.put("accounts", ACCOUNTS);
    return body;
  }

  private JsonNode createMapping(String from) throws Exception {
    JsonNode preview =
        w.c.expect(
            CompensationWorld.json(mappings() + "/preview", w.admin, null, mappingBody(from)), 200);
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    Map<String, Object> body = mappingBody(from);
    body.put("expectedVersion", preview.get("nextVersion").asInt());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("understandExportOnly", true);
    return w.c.expect(
        CompensationWorld.json(mappings(), w.admin, Organizations.newKey(), body), 201);
  }

  private JsonNode journalPreview() throws Exception {
    return w.c.expect(
        CompensationWorld.json(journals() + "/preview", w.admin, null, Map.of()), 200);
  }

  private Map<String, Object> journalBody(JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedPackageVersion", preview.get("packageVersion").asInt());
    body.put("expectedMappingVersion", preview.get("mapping").get("version").asInt());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("understandExportOnly", true);
    return body;
  }

  private MockHttpServletRequestBuilder generate(String key, Map<String, Object> body)
      throws Exception {
    return CompensationWorld.json(journals(), w.admin, key, body);
  }

  @Test
  void aPackageWithoutAMappingIsBlockedAndNothingIsWritten() throws Exception {
    JsonNode preview = journalPreview();
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(preview.get("blockers").get(0).asText()).isEqualTo("MAPPING_NOT_FOUND");
    assertThat(journalCount()).isZero();
  }

  @Test
  void aMappingIsCreatedOnceAndVersionedPerEntityAndCurrency() throws Exception {
    JsonNode first = createMapping("2000-01-01");
    assertThat(first.get("version").asInt()).isEqualTo(1);
    JsonNode second = createMapping("2000-02-01");
    assertThat(second.get("version").asInt()).isEqualTo(2);
    JsonNode list = w.c.expect(PayrollPackageWorld.get(w.admin, mappings()), 200);
    assertThat(list.get("items")).hasSize(2);
    assertThat(w.audits("accounting-mapping.create")).isEqualTo(2);
  }

  @Test
  void aMappingThatIsNotAfterTheLatestOverlapsAndIsBlocked() throws Exception {
    createMapping("2000-02-01");
    JsonNode preview =
        w.c.expect(
            CompensationWorld.json(
                mappings() + "/preview", w.admin, null, mappingBody("2000-01-01")),
            200);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(preview.get("blockers").get(0).asText()).isEqualTo("OVERLAP");
  }

  @Test
  void aGeneratedJournalIsBalancedAndItsCsvMatchesItsManifest() throws Exception {
    createMapping("2000-01-01");
    JsonNode preview = journalPreview();
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    assertThat(preview.get("totalDebitsMinor").asLong())
        .isEqualTo(preview.get("totalCreditsMinor").asLong());
    JsonNode receipt = w.c.expect(generate(Organizations.newKey(), journalBody(preview)), 201);
    String journal = receipt.get("journalId").asText();
    assertThat(receipt.get("version").asInt()).isEqualTo(1);

    JsonNode view = w.c.expect(PayrollPackageWorld.get(w.admin, journalPath(journal)), 200);
    assertThat(view.get("lines")).hasSize(preview.get("lineCount").asInt());

    MockHttpServletResponse csv =
        w.c.call(PayrollPackageWorld.get(w.admin, journalPath(journal) + "/csv"));
    assertThat(csv.getStatus()).isEqualTo(200);
    byte[] bytes = csv.getContentAsByteArray();
    assertThat(new String(bytes, StandardCharsets.UTF_8)).startsWith("﻿" + AccountingCsv.HEADER);
    assertThat(csv.getContentType()).startsWith("text/csv");

    JsonNode manifest =
        w.c.expect(PayrollPackageWorld.get(w.admin, journalPath(journal) + "/manifest"), 200);
    assertThat(manifest.get("csvSha256").asText()).isEqualTo(AccountingDigests.sha256(bytes));
    assertThat(manifest.get("csvBytes").asInt()).isEqualTo(bytes.length);
    assertThat(w.audits("accounting-journal.generate")).isEqualTo(1);
  }

  @Test
  void theSameKeyReplaysAndANewKeyIsRefusedAsAlreadyExisting() throws Exception {
    createMapping("2000-01-01");
    JsonNode preview = journalPreview();
    String key = Organizations.newKey();
    JsonNode first = w.c.expect(generate(key, journalBody(preview)), 201);
    JsonNode replay = w.c.expect(generate(key, journalBody(preview)), 201);
    assertThat(replay.get("journalId").asText()).isEqualTo(first.get("journalId").asText());
    w.c.problem(
        generate(Organizations.newKey(), journalBody(preview)),
        409,
        "ACCOUNTING_JOURNAL_ALREADY_EXISTS");
    assertThat(journalCount()).isEqualTo(1);
  }

  @Test
  void aForgedPreviewDigestIsStale() throws Exception {
    createMapping("2000-01-01");
    JsonNode preview = journalPreview();
    Map<String, Object> body = journalBody(preview);
    body.put("previewDigest", "0".repeat(64));
    w.c.problem(generate(Organizations.newKey(), body), 409, "ACCOUNTING_JOURNAL_PREVIEW_STALE");
  }

  @Test
  void aVoidedPackageCannotBeExportedButItsJournalStaysReadable() throws Exception {
    createMapping("2000-01-01");
    JsonNode preview = journalPreview();
    String journal =
        w.c.expect(generate(Organizations.newKey(), journalBody(preview)), 201)
            .get("journalId")
            .asText();
    byte[] before =
        w.c.call(PayrollPackageWorld.get(w.admin, journalPath(journal) + "/csv"))
            .getContentAsByteArray();
    w.advance(5);
    w.voidPackage(pkg, "ADMINISTRATIVE_ERROR", null);
    JsonNode blocked = journalPreview();
    assertThat(blocked.get("status").asText()).isEqualTo("BLOCKED");
    byte[] after =
        w.c.call(PayrollPackageWorld.get(w.admin, journalPath(journal) + "/csv"))
            .getContentAsByteArray();
    assertThat(after).isEqualTo(before);
    JsonNode view = w.c.expect(PayrollPackageWorld.get(w.admin, journalPath(journal)), 200);
    assertThat(view.get("sourceVoided").asBoolean()).isTrue();
  }

  @Test
  void anUnknownJournalIsNotFoundAndTheEmployeeRoleIsRefused() throws Exception {
    w.c.problem(
        PayrollPackageWorld.get(w.admin, journalPath(UUID.randomUUID().toString())),
        404,
        "ACCOUNTING_JOURNAL_NOT_FOUND");
    String employee =
        PaymentReconciliationWorld.bearer(w.tenant, "someone", TestTokens.MFA_ACR, "employee");
    assertThat(w.c.call(PayrollPackageWorld.get(employee, mappings())).getStatus()).isEqualTo(403);
  }

  @Test
  void noAuditOrOutboxRowCarriesAnAmountOrAnAccountCode() throws Exception {
    createMapping("2000-01-01");
    JsonNode preview = journalPreview();
    w.c.expect(generate(Organizations.newKey(), journalBody(preview)), 201);
    String evidence =
        String.valueOf(
            jdbc.queryForObject(
                "SELECT coalesce(string_agg(metadata::text, ' '), '')"
                    + " FROM platform.audit_event"
                    + " WHERE tenant_id = ? AND action LIKE 'accounting-%'",
                String.class,
                w.tenant));
    for (String code : ACCOUNTS.values().stream().map(Object::toString).toList()) {
      assertThat(evidence).doesNotContain("\"" + code + "\"");
    }
    assertThat(evidence).doesNotContain("amountMinor").doesNotContain("totalDebitsMinor");
  }

  @Test
  void mappingsJournalsAndLinesAreImmutableInTheDatabase() throws Exception {
    createMapping("2000-01-01");
    JsonNode preview = journalPreview();
    w.c.expect(generate(Organizations.newKey(), journalBody(preview)), 201);
    for (String sql :
        new String[] {
          "UPDATE payroll.accounting_mapping SET version = version WHERE tenant_id = ?",
          "DELETE FROM payroll.accounting_mapping WHERE tenant_id = ?",
          "UPDATE payroll.accounting_journal SET version = version WHERE tenant_id = ?",
          "DELETE FROM payroll.accounting_journal WHERE tenant_id = ?",
          "UPDATE payroll.accounting_journal_line SET line_number = line_number"
              + " WHERE tenant_id = ?",
          "DELETE FROM payroll.accounting_journal_line WHERE tenant_id = ?"
        }) {
      assertThatThrownBy(() -> jdbc.update(sql, w.tenant))
          .as(sql)
          .isInstanceOf(DataAccessException.class);
    }
    assertThat(journalCount()).isEqualTo(1);
  }
}
