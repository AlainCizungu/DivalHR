package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.application.AccountingRecorder;
import com.divalhr.core.payroll.domain.AccountingCsv;
import com.divalhr.core.payroll.domain.StatutoryEngine;
import com.divalhr.core.payroll.domain.StatutoryLine;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-096 (Issue #194, ADR 0039 sections 6, 9 and 10): the audit metadata and the two outbox events
 * are an allow-list that never holds an amount, a total, an account code, a name or an employee
 * identifier; no sentinel employee name, employee number, employee or payslip identifier,
 * per-employee amount, account code or aggregated amount reaches the audit trail, the outbox, the
 * idempotency store, an error body or a log line; and the CSV, the manifest and the stored rows
 * never carry an employee name, number or identifier, nor the source status (so the downloads are
 * byte-identical after the package is voided). Sentinel values make a leak detectable. Synthetic
 * data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
@PayrollPackageWorld.HighLimits
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.accounting-preview=600",
      "divalhr.request-limits.subject.accounting-journal-write=600",
      "divalhr.request-limits.subject.accounting-mapping-write=600",
      "divalhr.request-limits.subject.accounting-read=600"
    })
class AccountingPrivacyIntegrationTest {

  private static final String SENTINEL_GROSS = "7391640";
  private static final String SECOND_GROSS = "6203170";
  private static final String INVALID_CODE = "-qx7-invalid";

  private static final Map<String, Object> SENTINEL_ACCOUNTS =
      Map.of(
          "salaryExpense", "QX7SAL61",
          "employeeDeductionLiability", "QX7DED43",
          "employerContributionExpense", "QX7EXP64",
          "employerContributionLiability", "QX7LIA32",
          "netSalaryPayable", "QX7NET42");

  private static final Set<String> MANIFEST_KEYS =
      Set.of(
          "schemaVersion",
          "journalId",
          "journalVersion",
          "packageId",
          "packageVersion",
          "calculationId",
          "calculationVersion",
          "legalEntityId",
          "legalEntityCode",
          "period",
          "currency",
          "mappingId",
          "mappingVersion",
          "mappingDigest",
          "lineCount",
          "totalDebitsMinor",
          "totalCreditsMinor",
          "generatedAt",
          "csvFileName",
          "csvBytes",
          "csvSha256");

  private static final List<String> STATUS_WORDS =
      List.of("APPROVED", "VOIDED", "SOURCE_VOIDED", "sourceVoided", "status", "STATUS", "void");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollPackageWorld w;
  private AccountingWorld a;
  private final List<String> names = List.of("Kapinga", "Mbuyi");
  private final List<String> identifiers = new ArrayList<>();
  private final List<Long> individual = new ArrayList<>();

  /** What one pass over the whole accounting API produced. */
  private record Run(
      JsonNode preview,
      List<String> reads,
      List<String> errors,
      byte[] csv,
      byte[] manifest,
      byte[] csvAfterVoid,
      byte[] manifestAfterVoid,
      boolean voidedBefore,
      boolean voidedAfter) {}

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    UUID first = w.s.paidInCdf("Kapinga", SENTINEL_GROSS);
    w.s.standard(first);
    UUID second = w.s.paidInCdf("Mbuyi", SECOND_GROSS);
    w.s.standard(second);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    String pkg =
        w.approve(new Chain(pack, first, run, calculation)).get("packageId").asText();
    a = new AccountingWorld(w, pkg);

    String slipsPath = w.slipsPath(pkg) + "?limit=200";
    JsonNode slips = w.c.expect(PayrollRunWorld.get(w.admin, slipsPath), 200);
    for (JsonNode item : slips.get("items")) {
      identifiers.add(item.get("employeeNumber").asText());
      identifiers.add(item.get("employeeId").asText());
      identifiers.add(item.get("payslipId").asText());
    }
    assertThat(identifiers).hasSize(6);
    for (String gross : List.of(SENTINEL_GROSS, SECOND_GROSS)) {
      long value = Long.parseLong(gross);
      StatutoryLine line = StatutoryEngine.calculate(StatutoryFixtures.seed(), true, true, value);
      individual.add(value);
      individual.add(line.employeeDeductionsMinor());
      individual.add(line.employerContributionsMinor());
      individual.add(line.previewNetMinor());
    }
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String token(long amount) {
    return "(?<![0-9A-Za-z.\\-])" + amount + "(?![0-9A-Za-z.\\-])";
  }

  private static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static Set<String> keys(JsonNode node) {
    Set<String> found = new TreeSet<>();
    node.fieldNames().forEachRemaining(found::add);
    return found;
  }

  private static JsonNode json(String text) throws Exception {
    return CompensationWorld.JSON.readTree(text);
  }

  private byte[] download(String path) throws Exception {
    MockHttpServletResponse response = w.c.call(PayrollPackageWorld.get(w.admin, path));
    assertThat(response.getStatus()).as(path).isEqualTo(200);
    return response.getContentAsByteArray();
  }

  /** The aggregated line amounts and totals of a journal preview. */
  private static List<Long> aggregated(JsonNode preview) {
    List<Long> amounts = new ArrayList<>();
    preview.get("lines").forEach(line -> amounts.add(line.get("amountMinor").asLong()));
    amounts.add(preview.get("totalDebitsMinor").asLong());
    amounts.add(preview.get("totalCreditsMinor").asLong());
    return amounts;
  }

  private Run run() throws Exception {
    List<String> reads = new ArrayList<>();
    List<String> errors = new ArrayList<>();

    Map<String, Object> invalid = new LinkedHashMap<>(SENTINEL_ACCOUNTS);
    invalid.put("salaryExpense", INVALID_CODE);
    errors.add(
        w.c
            .problem(
                a.mappingPreviewRequest("2000-01-01", invalid), 422, "ACCOUNTING_MAPPING_INVALID")
            .toString());
    JsonNode mappingPreview =
        w.c.expect(a.mappingPreviewRequest("2000-01-01", SENTINEL_ACCOUNTS), 200);
    reads.add(mappingPreview.toString());
    reads.add(
        w.c
            .expect(
                CompensationWorld.json(
                    a.mappings(),
                    w.admin,
                    Organizations.newKey(),
                    AccountingWorld.mappingCommitting(
                        "2000-01-01", SENTINEL_ACCOUNTS, mappingPreview)),
                201)
            .toString());
    reads.add(w.c.expect(PayrollPackageWorld.get(w.admin, a.mappings()), 200).toString());

    JsonNode preview = a.journalPreview();
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    reads.add(preview.toString());
    Map<String, Object> body = AccountingWorld.journalBody(preview);
    Map<String, Object> forged = new LinkedHashMap<>(body);
    forged.put("previewDigest", "0".repeat(64));
    errors.add(
        w.c
            .problem(
                a.generate(Organizations.newKey(), forged),
                409,
                "ACCOUNTING_JOURNAL_PREVIEW_STALE")
            .toString());
    String key = Organizations.newKey();
    JsonNode receipt = w.c.expect(a.generate(key, body), 201);
    reads.add(receipt.toString());
    reads.add(w.c.expect(a.generate(key, body), 201).toString());
    errors.add(
        w.c
            .problem(
                a.generate(Organizations.newKey(), body), 409, "ACCOUNTING_JOURNAL_ALREADY_EXISTS")
            .toString());
    errors.add(
        w.c
            .problem(
                PayrollPackageWorld.get(w.admin, a.journalPath(UUID.randomUUID().toString())),
                404,
                "ACCOUNTING_JOURNAL_NOT_FOUND")
            .toString());

    String journal = receipt.get("journalId").asText();
    JsonNode view = w.c.expect(PayrollPackageWorld.get(w.admin, a.journalPath(journal)), 200);
    reads.add(view.toString());
    reads.add(w.c.expect(PayrollPackageWorld.get(w.admin, a.journals()), 200).toString());
    byte[] csv = download(a.journalPath(journal) + "/csv");
    byte[] manifest = download(a.journalPath(journal) + "/manifest");

    w.advance(5);
    w.voidPackage(a.pkg, "ADMINISTRATIVE_ERROR", null);
    reads.add(a.journalPreview().toString());
    JsonNode after = w.c.expect(PayrollPackageWorld.get(w.admin, a.journalPath(journal)), 200);
    reads.add(after.toString());
    byte[] csvAfter = download(a.journalPath(journal) + "/csv");
    byte[] manifestAfter = download(a.journalPath(journal) + "/manifest");
    return new Run(
        preview,
        reads,
        errors,
        csv,
        manifest,
        csvAfter,
        manifestAfter,
        view.get("sourceVoided").asBoolean(),
        after.get("sourceVoided").asBoolean());
  }

  private String persisted() {
    List<String> rows =
        new ArrayList<>(
            jdbc.queryForList(
                "SELECT action || ' ' || metadata::text || coalesce(after_state_sha256, '')"
                    + " || actor_subject"
                    + " FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action LIKE 'accounting-%'",
                String.class,
                w.tenant));
    rows.addAll(
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.accounting-%'",
            String.class,
            w.tenant));
    rows.addAll(
        jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation IN ('accounting-journal.generate', 'accounting-mapping.create')"
                + " AND response_body IS NOT NULL",
            String.class));
    return String.join("\n", rows);
  }

  /** The accounting rows of the tenant as text. */
  private String rows() {
    List<String> rows =
        new ArrayList<>(
            jdbc.queryForList(
                "SELECT to_jsonb(m)::text FROM payroll.accounting_mapping m WHERE tenant_id = ?",
                String.class,
                w.tenant));
    rows.addAll(
        jdbc.queryForList(
            "SELECT to_jsonb(j)::text FROM payroll.accounting_journal j WHERE tenant_id = ?",
            String.class,
            w.tenant));
    rows.addAll(
        jdbc.queryForList(
            "SELECT to_jsonb(l)::text FROM payroll.accounting_journal_line l WHERE tenant_id = ?",
            String.class,
            w.tenant));
    return String.join("\n", rows);
  }

  private void assertNoEmployeeData(String evidence, String label) {
    for (String name : names) {
      assertThat(evidence).as(label + " leaks the name " + name).doesNotContain(name);
    }
    for (String identifier : identifiers) {
      assertThat(evidence).as(label + " leaks " + identifier.length()).doesNotContain(identifier);
    }
    for (long amount : individual) {
      assertThat(evidence)
          .as(label + " leaks a per-employee amount")
          .doesNotContainPattern(token(amount));
    }
  }

  @Test
  void auditMetadataAndEventsAreAnAllowListWithoutAmountsTotalsNamesOrCodes() throws Exception {
    run();

    List<String> actions =
        jdbc.queryForList(
            "SELECT DISTINCT action FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action LIKE 'accounting-%'",
            String.class,
            w.tenant);
    assertThat(actions)
        .containsExactlyInAnyOrder(
            "accounting-journal.download",
            "accounting-journal.generate",
            "accounting-journal.preview",
            "accounting-journal.read",
            "accounting-mapping.create",
            "accounting-mapping.preview",
            "accounting-mapping.read");
    List<String> metadata =
        jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action LIKE 'accounting-%'",
            String.class,
            w.tenant);
    assertThat(metadata).hasSizeGreaterThanOrEqualTo(10);
    for (String row : metadata) {
      assertThat(AccountingRecorder.ALLOWED).containsAll(keys(json(row)));
    }
    assertThat(
            jdbc.queryForList(
                "SELECT CAST(metadata AS jsonb) ->> 'view' FROM platform.audit_event"
                    + " WHERE tenant_id = ? AND action = 'accounting-journal.download'",
                String.class,
                w.tenant))
        .containsExactlyInAnyOrder("csv", "manifest", "csv", "manifest");

    List<String> events =
        jdbc.queryForList(
            "SELECT event_type || '|' || envelope::text FROM platform.outbox_event"
                + " WHERE tenant_id = ? AND event_type LIKE 'payroll.accounting-%'",
            String.class,
            w.tenant);
    assertThat(events).hasSize(2);
    List<String> types = new ArrayList<>();
    for (String row : events) {
      types.add(row.substring(0, row.indexOf('|')));
      JsonNode data = json(row.substring(row.indexOf('|') + 1)).get("data");
      assertThat(AccountingRecorder.ALLOWED).containsAll(keys(data));
    }
    assertThat(types)
        .containsExactlyInAnyOrder(
            AccountingRecorder.MAPPING_CREATED_EVENT, AccountingRecorder.JOURNAL_GENERATED_EVENT);
    // The allow-list itself names no amount, total, account, digest, employee, name or status.
    for (String key : AccountingRecorder.ALLOWED) {
      assertThat(key.toLowerCase())
          .doesNotContain("amount", "total", "debit", "credit", "gross", "account", "code")
          .doesNotContain("digest", "employee", "name", "number", "status", "email", "subject");
    }
  }

  @Test
  void noSentinelEmployeeDataAmountOrCodeReachesAnyStoreErrorOrLog(CapturedOutput output)
      throws Exception {
    Run run = run();
    List<Long> aggregated = aggregated(run.preview());
    String evidence = persisted();
    assertThat(evidence).isNotBlank().contains("accounting-journal.generate");

    assertNoEmployeeData(evidence, "persisted evidence");
    for (String code : SENTINEL_ACCOUNTS.values().stream().map(Object::toString).toList()) {
      assertThat(evidence).as("persisted evidence leaks a code").doesNotContain(code);
    }
    for (long amount : aggregated) {
      assertThat(evidence)
          .as("persisted evidence leaks an amount")
          .doesNotContainPattern(token(amount));
    }

    // Refusals do not echo what they protect.
    for (String error : run.errors()) {
      assertNoEmployeeData(error, "error");
      assertThat(error).doesNotContain(INVALID_CODE).doesNotContain("qx7");
      for (String code : SENTINEL_ACCOUNTS.values().stream().map(Object::toString).toList()) {
        assertThat(error).doesNotContain(code);
      }
      for (long amount : aggregated) {
        assertThat(error).doesNotContainPattern(token(amount));
      }
    }

    // Public reads show the aggregated lines and never an employee or a per-employee amount.
    for (String read : run.reads()) {
      assertNoEmployeeData(read, "read");
    }
    assertNoEmployeeData(rows(), "stored rows");

    // Logs: the long numbers only, shorter ones could appear by chance among identifiers.
    String logs = output.getAll();
    for (String name : names) {
      assertThat(logs).as("logs leak " + name).doesNotContain(name);
    }
    for (String identifier : identifiers) {
      assertThat(logs).as("logs leak an identifier").doesNotContain(identifier);
    }
    for (String code : SENTINEL_ACCOUNTS.values().stream().map(Object::toString).toList()) {
      assertThat(logs).as("logs leak a code").doesNotContain(code);
    }
    assertThat(logs).doesNotContain(INVALID_CODE);
    List<Long> every = new ArrayList<>(aggregated);
    every.addAll(individual);
    for (long amount : every) {
      if (Long.toString(amount).length() >= 7) {
        assertThat(logs).as("logs leak an amount").doesNotContainPattern(token(amount));
      }
    }

    // Controls: the scan can see what is legitimately there.
    String csv = text(run.csv());
    for (String code : SENTINEL_ACCOUNTS.values().stream().map(Object::toString).toList()) {
      assertThat(csv).contains(code);
    }
    for (long amount : aggregated.subList(0, run.preview().get("lines").size())) {
      assertThat(csv).containsPattern(token(amount));
    }
  }

  @Test
  void theCsvAndTheManifestNeverCarryEmployeeIdentifiersOrTheSourceStatus() throws Exception {
    Run run = run();
    String csv = text(run.csv());
    String manifest = text(run.manifest());

    assertThat(csv).startsWith("﻿" + AccountingCsv.HEADER + "\r\n").endsWith("\r\n");
    String[] rows = csv.substring(1).split("\r\n", -1);
    assertThat(rows[rows.length - 1]).isEmpty();
    assertThat(rows.length - 2).isEqualTo(run.preview().get("lineCount").asInt());
    for (int i = 0; i < rows.length - 1; i++) {
      assertThat(rows[i].split(",", -1)).as(rows[i]).hasSize(10);
    }
    assertThat(keys(json(manifest))).isEqualTo(new TreeSet<>(MANIFEST_KEYS));

    assertNoEmployeeData(csv, "csv");
    assertNoEmployeeData(manifest, "manifest");
    for (String word : STATUS_WORDS) {
      assertThat(csv).as("csv status word " + word).doesNotContain(word);
      assertThat(manifest).as("manifest status word " + word).doesNotContain(word);
    }

    // The source status lives in the read model only: the files do not change when it does.
    assertThat(run.voidedBefore()).isFalse();
    assertThat(run.voidedAfter()).isTrue();
    assertThat(run.csvAfterVoid()).isEqualTo(run.csv());
    assertThat(run.manifestAfterVoid()).isEqualTo(run.manifest());
  }
}
