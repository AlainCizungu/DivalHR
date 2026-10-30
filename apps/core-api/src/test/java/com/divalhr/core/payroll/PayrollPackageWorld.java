package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * MVP-094C test fixtures over the public API, on top of {@link StatutoryWorld} (and so of {@link
 * PayrollRunWorld} and {@link CompensationWorld}): a synthetic VERIFIED rule pack and its
 * activation, hired and compensated employees with statutory profiles (one of them linked to its
 * own employee membership when asked), a frozen CDF run, its statutory calculation, and the
 * preview and commit calls of payroll approval, void, package reads and payslip views and PDFs. All
 * data is synthetic; family names are letters only (a digit makes an import row invalid).
 */
final class PayrollPackageWorld {

  /**
   * Raises every request limit the fixtures touch to 600 per minute, so that the many setup calls
   * of one test never meet a bucket. The rate-limit tests do not use it.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @TestPropertySource(
      properties = {
        "divalhr.request-limits.subject.statutory-read=600",
        "divalhr.request-limits.subject.statutory-calculation-preview=600",
        "divalhr.request-limits.subject.statutory-calculation-create=600",
        "divalhr.request-limits.subject.statutory-profile-preview=600",
        "divalhr.request-limits.subject.statutory-profile-write=600",
        "divalhr.request-limits.subject.statutory-activation-write=600",
        "divalhr.request-limits.subject.payroll-run-preview=600",
        "divalhr.request-limits.subject.payroll-run-create=600",
        "divalhr.request-limits.subject.payroll-run-read=600",
        "divalhr.request-limits.subject.payroll-policy-preview=600",
        "divalhr.request-limits.subject.payroll-policy-write=600",
        "divalhr.request-limits.subject.payroll-policy-read=600",
        "divalhr.request-limits.subject.payroll-package-read=600",
        "divalhr.request-limits.subject.payroll-approval-preview=600",
        "divalhr.request-limits.subject.payroll-approval-write=600",
        "divalhr.request-limits.subject.payslip-download=600"
      })
  @interface HighLimits {}

  static final String ME = "/api/v1/me/payslips";

  /** The reference gross: with the seeded rules deductions 12,960, employer 21,060. */
  static final String GROSS = StatutoryWorld.GROSS;

  final StatutoryWorld s;
  final PayrollRunWorld r;
  final CompensationWorld c;
  final MockMvc mvc;
  final JdbcTemplate jdbc;
  final EmailLookup lookups;
  final SettableClock clock;
  final String admin;
  final UUID tenant;
  final UUID entity;
  final YearMonth month;

  /** The activated pack, one standard employee and the frozen run and calculation. */
  record Chain(UUID pack, UUID employee, String run, String calculation) {}

  PayrollPackageWorld(MockMvc mvc, JdbcTemplate jdbc, EmailLookup lookups, SettableClock clock)
      throws Exception {
    this.s = new StatutoryWorld(mvc, jdbc, lookups, clock);
    this.r = s.r;
    this.c = s.c;
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.lookups = lookups;
    this.clock = clock;
    this.admin = s.admin;
    this.tenant = s.tenant;
    this.entity = s.entity;
    this.month = s.month;
  }

  // ------------------------------------------------------------------------------------------
  // Rule packs
  // ------------------------------------------------------------------------------------------

  /**
   * A VERIFIED pack with the seeded reference rules whose code does not start with {@code
   * SYNTHETIC-TEST}: the sources are committed before the VERIFIED update (a source guard refuses a
   * source on a non-DRAFT pack). Test setup only; it has no legal meaning.
   */
  static UUID verifiedNonSyntheticPack(JdbcTemplate jdbc) {
    UUID id = UUID.randomUUID();
    String code = "REVIEWED-TEST-" + id.toString().substring(0, 8).toUpperCase();
    jdbc.update(
        "INSERT INTO payroll.statutory_rule_pack (id, code, version, country, currency, status,"
            + " effective_from, effective_to, method_version, rules, created_at) VALUES (?, ?, 1,"
            + " 'CD', 'CDF', 'DRAFT', DATE '2020-01-01', NULL, '1', CAST(? AS jsonb), now())",
        id,
        code,
        StatutoryFixtures.SEED_JSON);
    jdbc.update(
        "INSERT INTO payroll.statutory_rule_pack_source (pack_id, seq, source_key, title, url,"
            + " sha256, retrieved_on) VALUES (?, 1, 'DGI_IRPP', 'Synthetic test source: IRPP',"
            + " 'https://dgi.gouv.cd/impot-sur-le-revenu-des-personnes-physiques-irpp/', ?,"
            + " DATE '2026-10-29'), (?, 2, 'CNSS_RATES', 'Synthetic test source: CNSS',"
            + " 'https://cnss.cd/?page_id=1388', ?, DATE '2026-10-29')",
        id,
        "a".repeat(64),
        id,
        "b".repeat(64));
    jdbc.update(
        "UPDATE payroll.statutory_rule_pack SET status = 'VERIFIED',"
            + " reviewer_name = 'Synthetic Test Reviewer', reviewer_organization = 'Test Only',"
            + " reviewed_on = DATE '2026-10-01', attested = true, verified_at = now()"
            + " WHERE id = ?",
        id);
    return id;
  }

  /** Retires a VERIFIED pack through the one legal forward step. */
  void retire(UUID pack) {
    jdbc.update(
        "UPDATE payroll.statutory_rule_pack SET status = 'RETIRED', retired_at = now()"
            + " WHERE id = ?",
        pack);
  }

  // ------------------------------------------------------------------------------------------
  // Chains
  // ------------------------------------------------------------------------------------------

  /** A synthetic VERIFIED pack, activated, one standard employee paid 162,000, run, calculation. */
  Chain ready() throws Exception {
    return ready(StatutoryWorld.verifiedPack(jdbc));
  }

  Chain ready(UUID pack) throws Exception {
    s.activate(pack, month);
    UUID employee = s.paidInCdf("Mukendi", GROSS);
    s.standard(employee);
    String run = s.cdfRun();
    String calculation = s.create(run).get("calculationId").asText();
    return new Chain(pack, employee, run, calculation);
  }

  /**
   * Like {@link #ready()} with two more employees: a standard one paid 90,000 and one subject to
   * neither CNSS nor IRPP paid 100,000 (an excluded line).
   */
  Chain readyWithThree() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    s.activate(pack, month);
    UUID employee = s.paidInCdf("Mukendi", GROSS);
    s.standard(employee);
    UUID second = s.paidInCdf("Kalala", "90000");
    s.standard(second);
    UUID exempt = s.paidInCdf("Exempt", "100000");
    s.profile(exempt, month.atDay(1).minusDays(30), "STANDARD_RESIDENT", false, false);
    String run = s.cdfRun();
    String calculation = s.create(run).get("calculationId").asText();
    return new Chain(pack, employee, run, calculation);
  }

  /** A chain whose only employee has an access link, and that employee. */
  record Linked(Chain chain, CompensationWorld.Person person) {}

  /** Like {@link #ready()}, with the employee linked to its own employee membership. */
  Linked readyLinked() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    s.activate(pack, month);
    CompensationWorld.Person person = linkedEmployee("Mukendi", GROSS);
    String run = s.cdfRun();
    String calculation = s.create(run).get("calculationId").asText();
    return new Linked(new Chain(pack, person.employee(), run, calculation), person);
  }

  /** A new calculation of the chain's run (after a change of a profile); returns its id. */
  String recalculate(Chain chain) throws Exception {
    return s.create(chain.run()).get("calculationId").asText();
  }

  // ------------------------------------------------------------------------------------------
  // Paths
  // ------------------------------------------------------------------------------------------

  static String approvalPath(Object entity, Object run, Object calculation) {
    return StatutoryWorld.calculationsPath(entity, run) + "/" + calculation + "/approval";
  }

  String approvalPath(Chain chain) {
    return approvalPath(entity, chain.run(), chain.calculation());
  }

  static String packagesPath(Object entity) {
    return "/api/v1/legal-entities/" + entity + "/payroll-packages";
  }

  String packagesPath() {
    return packagesPath(entity);
  }

  String packagePath(Object pkg) {
    return packagesPath() + "/" + pkg;
  }

  String slipsPath(Object pkg) {
    return packagePath(pkg) + "/payslips";
  }

  String slipPath(Object pkg, Object slip) {
    return slipsPath(pkg) + "/" + slip;
  }

  String slipPdfPath(Object pkg, Object slip) {
    return slipPath(pkg, slip) + ".pdf";
  }

  static String minePath(Object slip) {
    return ME + "/" + slip;
  }

  static String minePdfPath(Object slip) {
    return ME + "/" + slip + ".pdf";
  }

  // ------------------------------------------------------------------------------------------
  // Approval requests and bodies
  // ------------------------------------------------------------------------------------------

  MockHttpServletRequestBuilder previewRequest(Chain chain) throws Exception {
    return previewRequest(admin, chain);
  }

  MockHttpServletRequestBuilder previewRequest(String bearer, Chain chain) throws Exception {
    return CompensationWorld.json(approvalPath(chain) + "/preview", bearer, null, Map.of());
  }

  MockHttpServletRequestBuilder approveRequest(Chain chain, String key, Map<String, Object> body)
      throws Exception {
    return approveRequest(admin, chain, key, body);
  }

  MockHttpServletRequestBuilder approveRequest(
      String bearer, Chain chain, String key, Map<String, Object> body) throws Exception {
    return CompensationWorld.json(approvalPath(chain), bearer, key, body);
  }

  /** The committing body of a preview: its evidence and the three confirmations. */
  static Map<String, Object> approving(JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", preview.get("expectedVersion").asInt());
    body.put("sourceRunDigest", preview.get("sourceRunDigest").asText());
    body.put("calculationDigest", preview.get("calculationDigest").asText());
    body.put("profileDigest", preview.get("profileDigest").asText());
    body.put("rulePackDigest", PayrollRunWorld.textOrForged(preview, "rulePackDigest"));
    body.put("previewDigest", PayrollRunWorld.textOrForged(preview, "previewDigest"));
    body.put("expiresAt", PayrollRunWorld.textOrForged(preview, "expiresAt"));
    body.put("reviewedTotals", true);
    body.put("reviewedExceptions", true);
    body.put("understandsNoPayment", true);
    return body;
  }

  /** The body with one field replaced. */
  static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
    Map<String, Object> changed = new LinkedHashMap<>(body);
    changed.put(field, value);
    return changed;
  }

  JsonNode preview(Chain chain) throws Exception {
    return c.expect(previewRequest(chain), 200);
  }

  /** Previews (READY) and commits with a fresh key; returns the receipt. */
  JsonNode approve(Chain chain) throws Exception {
    JsonNode preview = preview(chain);
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    return c.expect(approveRequest(chain, Organizations.newKey(), approving(preview)), 201);
  }

  // ------------------------------------------------------------------------------------------
  // Void requests and bodies
  // ------------------------------------------------------------------------------------------

  static Map<String, Object> voidCommand(String reason, Object explanation) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reasonCode", reason);
    body.put("explanation", explanation);
    return body;
  }

  MockHttpServletRequestBuilder voidPreviewRequest(Object pkg, Map<String, Object> command)
      throws Exception {
    return CompensationWorld.json(packagePath(pkg) + "/void/preview", admin, null, command);
  }

  MockHttpServletRequestBuilder voidRequest(Object pkg, String key, Map<String, Object> body)
      throws Exception {
    return CompensationWorld.json(packagePath(pkg) + "/void", admin, key, body);
  }

  /** The committing body of a void preview. */
  static Map<String, Object> voiding(JsonNode preview, String reason, String explanation) {
    Map<String, Object> body = voidCommand(reason, explanation);
    body.put("packageVersion", preview.get("packageVersion").asInt());
    body.put("approvalDigest", preview.get("approvalDigest").asText());
    body.put("previewDigest", PayrollRunWorld.textOrForged(preview, "previewDigest"));
    body.put("expiresAt", PayrollRunWorld.textOrForged(preview, "expiresAt"));
    body.put("noPaymentAcknowledged", true);
    return body;
  }

  JsonNode voidPreview(Object pkg, String reason, String explanation) throws Exception {
    return c.expect(voidPreviewRequest(pkg, voidCommand(reason, explanation)), 200);
  }

  /** Previews (READY) and commits a void with a fresh key; returns the receipt. */
  JsonNode voidPackage(Object pkg, String reason, String explanation) throws Exception {
    JsonNode preview = voidPreview(pkg, reason, explanation);
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    return c.expect(
        voidRequest(pkg, Organizations.newKey(), voiding(preview, reason, explanation)), 201);
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  JsonNode packageDetail(Object pkg) throws Exception {
    return c.expect(PayrollRunWorld.get(admin, packagePath(pkg)), 200);
  }

  /** The payslip identifier of an employee in a package (through the administrator's list). */
  String slipOf(Object pkg, UUID employee) throws Exception {
    JsonNode page = c.expect(PayrollRunWorld.get(admin, slipsPath(pkg) + "?limit=200"), 200);
    for (JsonNode item : page.get("items")) {
      if (item.get("employeeId").asText().equals(employee.toString())) {
        return item.get("payslipId").asText();
      }
    }
    throw new AssertionError("no payslip for the employee");
  }

  JsonNode slipView(Object pkg, Object slip, String locale) throws Exception {
    String query = locale == null ? "" : "?locale=" + locale;
    return c.expect(PayrollRunWorld.get(admin, slipPath(pkg, slip) + query), 200);
  }

  /** The PDF of an administrator download; the response is asserted to be 200. */
  MockHttpServletResponse pdf(String bearer, String path) throws Exception {
    MockHttpServletResponse response = c.call(PayrollRunWorld.get(bearer, path));
    assertThat(response.getStatus()).as(path).isEqualTo(200);
    return response;
  }

  /** The detail of the calculation the package was approved from. */
  JsonNode calculationDetail(Chain chain) throws Exception {
    return c.expect(
        PayrollRunWorld.get(admin, s.calculationsPath(chain.run()) + "/" + chain.calculation()),
        200);
  }

  // ------------------------------------------------------------------------------------------
  // People
  // ------------------------------------------------------------------------------------------

  /**
   * A hired employee paid in CDF with a standard statutory profile and an active access link to
   * its own employee membership (a verified link).
   */
  CompensationWorld.Person linkedEmployee(String family, String gross) throws Exception {
    LocalDate start = month.atDay(1).minusDays(5);
    UUID employee = r.paid(family, start, "CDF", gross);
    s.standard(employee);
    return link(employee);
  }

  /** Links an existing employee to a new employee membership. */
  CompensationWorld.Person link(UUID employee) throws Exception {
    String subject = UUID.randomUUID().toString();
    UUID membership = UUID.randomUUID();
    String address = "pkg-" + UUID.randomUUID().toString().substring(0, 8) + "@exemple.cd";
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, 'employee', ?, now())",
        membership,
        tenant,
        subject,
        lookups.of(EmailAddress.parse(address).orElseThrow()));
    c.expect(
        Employees.postJson(
                admin,
                CompensationWorld.EMPLOYEES + "/" + employee + "/access-link",
                "{\"membershipId\":\"" + membership + "\"}")
            .header("Idempotency-Key", Organizations.newKey()),
        201);
    return new CompensationWorld.Person(
        employee, CompensationWorld.employeeBearer(tenant, subject), subject);
  }

  /** A second tenant administrator of the same tenant. */
  String colleague() {
    return Hierarchy.bearer(tenant, "sub-pkg-colleague-" + UUID.randomUUID(), "tenant-admin");
  }

  /** The {@code sub} claim of a bearer header (the token is signed, not encrypted). */
  static String subjectOf(String bearer) throws Exception {
    String token = bearer.substring("Bearer ".length());
    String payload = token.split("\\.")[1];
    String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
    return CompensationWorld.JSON.readTree(json).get("sub").asText();
  }

  /** Moves the frozen clock forward, so that two approvals never share an instant. */
  void advance(long seconds) {
    clock.set(clock.instant().plusSeconds(seconds));
  }

  // ------------------------------------------------------------------------------------------
  // Evidence
  // ------------------------------------------------------------------------------------------

  /** Packages, sources, voids, payslips, approval and void audit rows and events of the tenant. */
  List<Integer> evidence() {
    return List.of(
        c.count("SELECT count(*) FROM payroll.payroll_package WHERE tenant_id = ?", tenant),
        c.count("SELECT count(*) FROM payroll.payroll_package_source WHERE tenant_id = ?", tenant),
        c.count("SELECT count(*) FROM payroll.payroll_package_void WHERE tenant_id = ?", tenant),
        c.count("SELECT count(*) FROM payroll.payroll_payslip WHERE tenant_id = ?", tenant),
        c.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('payroll-package.approve', 'payroll-package.void')",
            tenant),
        c.count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.package.%'",
            tenant));
  }

  /** Audit rows of the tenant by action. */
  int audits(String action) {
    return c.count(
        "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action = ?",
        tenant,
        action);
  }

  /** The audit metadata, outbox envelopes and idempotency bodies of the tenant, as one text. */
  String persistedEvidence() {
    List<String> rows =
        new ArrayList<>(
            jdbc.queryForList(
                "SELECT metadata::text || coalesce(after_state_sha256, '') || actor_subject"
                    + " FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND (action LIKE 'payroll-package.%' OR action LIKE 'payslip.%')",
                String.class,
                tenant));
    rows.addAll(
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.package.%'",
            String.class,
            tenant));
    rows.addAll(
        jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation IN ('payroll-package.approve', 'payroll-package.void')"
                + " AND response_body IS NOT NULL",
            String.class));
    return String.join("\n", rows);
  }

  /** The approver subject stored on a package (restricted; read by the owner connection). */
  String approver(Object pkg) {
    return jdbc.queryForObject(
        "SELECT approved_by_subject FROM payroll.payroll_package WHERE id = ?",
        String.class,
        UUID.fromString(pkg.toString()));
  }

  /** An instant a day after a preview's expiry, for forged expiries. */
  static String later(JsonNode preview) {
    return Instant.parse(preview.get("expiresAt").asText()).plusSeconds(86_400).toString();
  }

  /** Monthly gross of a calculation line of the detail, by employee. */
  static JsonNode lineOf(JsonNode detail, UUID employee) {
    for (JsonNode line : detail.get("lines")) {
      if (line.get("employeeId").asText().equals(employee.toString())) {
        return line;
      }
    }
    throw new AssertionError("no calculation line for the employee");
  }

  /** A list of text values of an array node. */
  static List<String> texts(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(v -> values.add(v.asText()));
    return values;
  }

  /** The amount text of a money view, asserting the CDF currency. */
  static String amount(JsonNode money) {
    assertThat(money.get("currency").asText()).isEqualTo("CDF");
    return money.get("amount").asText();
  }

  /** A plain GET (kept here so tests read like the other worlds). */
  static MockHttpServletRequestBuilder get(String bearer, String path) {
    return MockMvcRequestBuilders.get(path).header("Authorization", bearer);
  }
}
