package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-094B test fixtures over the public API, on top of {@link PayrollRunWorld}: a synthetic
 * VERIFIED rule pack, its activation, statutory profiles, a frozen CDF run and the preview, create
 * and read calls of statutory calculations. All data is synthetic.
 *
 * <p>A VERIFIED pack cannot be created through the API (no endpoint exists, by design). {@link
 * #verifiedPack} writes one through the test database connection the way the browser journeys do:
 * code {@code SYNTHETIC-TEST-*}, reviewer "Synthetic Test Reviewer" of "Test Only", fake hashes. It
 * has no legal meaning and never appears in a migration, a seed or a dev script.
 */
final class StatutoryWorld {

  /** Gross of the reference employee: with the seeded reference rules, 12,960 / 21,060. */
  static final String GROSS = "162000";

  static final String PACKS = "/api/v1/statutory-rule-packs";

  final PayrollRunWorld r;
  final MockMvc mvc;
  final JdbcTemplate jdbc;
  final CompensationWorld c;
  final String admin;
  final UUID tenant;
  final UUID entity;
  final YearMonth month;

  StatutoryWorld(MockMvc mvc, JdbcTemplate jdbc, EmailLookup lookups, SettableClock clock)
      throws Exception {
    this.r = new PayrollRunWorld(mvc, jdbc, lookups, clock);
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.c = r.c;
    this.admin = r.admin;
    this.tenant = r.tenant;
    this.entity = r.entity;
    this.month = r.month;
    r.policy("CALENDAR_DAYS");
  }

  // ------------------------------------------------------------------------------------------
  // Synthetic rule packs
  // ------------------------------------------------------------------------------------------

  /** A synthetic VERIFIED pack with the seeded reference rules, open-ended from 2020-01-01. */
  static UUID verifiedPack(JdbcTemplate jdbc) {
    return verifiedPack(jdbc, StatutoryFixtures.SEED_JSON, "2020-01-01", null);
  }

  /** A synthetic VERIFIED pack with the given rules and interval. */
  static UUID verifiedPack(JdbcTemplate jdbc, String rules, String from, String to) {
    UUID id = draftPack(jdbc, rules, from, to);
    jdbc.update(
        "UPDATE payroll.statutory_rule_pack SET status = 'VERIFIED',"
            + " reviewer_name = 'Synthetic Test Reviewer', reviewer_organization = 'Test Only',"
            + " reviewed_on = DATE '2026-10-01', attested = true, verified_at = now()"
            + " WHERE id = ?",
        id);
    return id;
  }

  /** A synthetic DRAFT pack (sources hashed, no reviewer): never activatable. */
  static UUID draftPack(JdbcTemplate jdbc, String rules, String from, String to) {
    UUID id = UUID.randomUUID();
    String code = "SYNTHETIC-TEST-" + id.toString().substring(0, 8).toUpperCase();
    jdbc.update(
        "INSERT INTO payroll.statutory_rule_pack (id, code, version, country, currency, status,"
            + " effective_from, effective_to, method_version, rules, created_at) VALUES (?, ?, 1,"
            + " 'CD', 'CDF', 'DRAFT', DATE '"
            + from
            + "', "
            + (to == null ? "NULL" : "DATE '" + to + "'")
            + ", '1', CAST(? AS jsonb), now())",
        id,
        code,
        rules);
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
    return id;
  }

  // ------------------------------------------------------------------------------------------
  // Paths and bodies
  // ------------------------------------------------------------------------------------------

  static String activationPath(Object entity) {
    return "/api/v1/legal-entities/" + entity + "/statutory-rule-pack";
  }

  static String profilePath(Object employee) {
    return "/api/v1/employees/" + employee + "/statutory-profile";
  }

  static String calculationsPath(Object entity, Object run) {
    return PayrollRunWorld.runsPath(entity) + "/" + run + "/statutory-calculations";
  }

  String activationPath() {
    return activationPath(entity);
  }

  String calculationsPath(Object run) {
    return calculationsPath(entity, run);
  }

  static Map<String, Object> activation(UUID pack, YearMonth from, int expectedVersion) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("packId", pack.toString());
    body.put("fromMonth", from.toString());
    body.put("expectedVersion", expectedVersion);
    body.put("acknowledged", true);
    return body;
  }

  static Map<String, Object> profileCommand(
      LocalDate from, String classification, boolean cnss, boolean irpp, String evidence) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("effectiveFrom", from.toString());
    body.put("classification", classification);
    body.put("cnssSubject", cnss);
    body.put("irppSubject", irpp);
    body.put("evidenceCode", evidence);
    return body;
  }

  static Map<String, Object> profileCommitting(Map<String, Object> command, JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("version").asInt());
    body.put("previewDigest", PayrollRunWorld.textOrForged(preview, "previewDigest"));
    body.put("expiresAt", PayrollRunWorld.textOrForged(preview, "expiresAt"));
    body.put("confirmed", true);
    return body;
  }

  static Map<String, Object> calculationCommitting(JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", preview.get("expectedVersion").asInt());
    body.put("sourceRunDigest", preview.get("sourceRunDigest").asText());
    body.put("profileDigest", preview.get("profileDigest").asText());
    body.put("rulePackDigest", PayrollRunWorld.textOrForged(preview, "rulePackDigest"));
    body.put("calculationDigest", PayrollRunWorld.textOrForged(preview, "calculationDigest"));
    body.put("expiresAt", PayrollRunWorld.textOrForged(preview, "expiresAt"));
    body.put("confirmed", true);
    return body;
  }

  // ------------------------------------------------------------------------------------------
  // Requests
  // ------------------------------------------------------------------------------------------

  MockHttpServletRequestBuilder activateRequest(String key, Map<String, Object> body)
      throws Exception {
    return CompensationWorld.json(activationPath() + "/activation", admin, key, body);
  }

  MockHttpServletRequestBuilder profilePreviewRequest(UUID employee, Map<String, Object> command)
      throws Exception {
    return CompensationWorld.json(profilePath(employee) + "/preview", admin, null, command);
  }

  MockHttpServletRequestBuilder profileCommitRequest(
      UUID employee, String key, Map<String, Object> body) throws Exception {
    return CompensationWorld.json(profilePath(employee), admin, key, body);
  }

  MockHttpServletRequestBuilder previewRequest(Object run) throws Exception {
    return CompensationWorld.json(calculationsPath(run) + "/preview", admin, null, Map.of());
  }

  MockHttpServletRequestBuilder createRequest(Object run, String key, Map<String, Object> body)
      throws Exception {
    return CompensationWorld.json(calculationsPath(run), admin, key, body);
  }

  // ------------------------------------------------------------------------------------------
  // Flows
  // ------------------------------------------------------------------------------------------

  /** Activates the pack from a month with the version the read returned. */
  JsonNode activate(UUID pack, YearMonth from) throws Exception {
    JsonNode view = c.expect(PayrollRunWorld.get(admin, activationPath() + "?month=" + from), 200);
    return c.expect(
        activateRequest(
            Organizations.newKey(), activation(pack, from, view.get("version").asInt())),
        201);
  }

  /** Previews and commits a profile entry; returns the receipt. */
  JsonNode profile(UUID employee, LocalDate from, String classification, boolean cnss, boolean irpp)
      throws Exception {
    Map<String, Object> command =
        profileCommand(from, classification, cnss, irpp, "EMPLOYMENT_CONTRACT");
    JsonNode preview = c.expect(profilePreviewRequest(employee, command), 200);
    assertThat(preview.get("blockers")).isEmpty();
    return c.expect(
        profileCommitRequest(employee, Organizations.newKey(), profileCommitting(command, preview)),
        201);
  }

  /** A standard resident subject to CNSS and IRPP, in force before the month. */
  JsonNode standard(UUID employee) throws Exception {
    return profile(employee, month.atDay(1).minusDays(30), "STANDARD_RESIDENT", true, true);
  }

  JsonNode preview(Object run) throws Exception {
    return c.expect(previewRequest(run), 200);
  }

  /** Previews and creates a calculation of the run; returns the receipt. */
  JsonNode create(Object run) throws Exception {
    JsonNode preview = preview(run);
    assertThat(preview.get("status").asText()).isEqualTo("READY");
    return c.expect(
        createRequest(run, Organizations.newKey(), calculationCommitting(preview)), 201);
  }

  /** A hired employee paid in CDF from before the month. */
  UUID paidInCdf(String family, String amount) throws Exception {
    return r.paid(family, month.atDay(1).minusDays(5), "CDF", amount);
  }

  /** A CDF run of the month; returns its identifier. */
  String cdfRun() throws Exception {
    return r.createRun("CDF").get("runId").asText();
  }

  JsonNode line(JsonNode preview, UUID employee) {
    return r.line(preview, employee);
  }

  // ------------------------------------------------------------------------------------------
  // Evidence
  // ------------------------------------------------------------------------------------------

  /** Activations, profiles, calculations, lines, audit rows and internal events of the tenant. */
  List<Integer> evidence() {
    return List.of(
        c.count("SELECT count(*) FROM payroll.statutory_activation WHERE tenant_id = ?", tenant),
        c.count("SELECT count(*) FROM payroll.statutory_profile WHERE tenant_id = ?", tenant),
        c.count("SELECT count(*) FROM payroll.statutory_calculation WHERE tenant_id = ?", tenant),
        c.count(
            "SELECT count(*) FROM payroll.statutory_calculation_line WHERE tenant_id = ?", tenant),
        c.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('statutory-activation.create', 'statutory-profile.change',"
                + " 'statutory-calculation.create')",
            tenant),
        c.count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.statutory-%'",
            tenant));
  }

  /** The audit metadata, outbox envelopes and idempotency bodies of the tenant, as one text. */
  String persistedEvidence() {
    List<String> rows =
        jdbc.queryForList(
            "SELECT metadata::text || coalesce(after_state_sha256, '')"
                + " FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action LIKE 'statutory-%'",
            String.class, tenant);
    rows.addAll(
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.statutory-%'",
            String.class, tenant));
    rows.addAll(
        jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation LIKE 'statutory-%' AND response_body IS NOT NULL",
            String.class));
    return String.join("\n", rows);
  }
}
