package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.support.EmployeeImports;
import com.divalhr.core.support.EmployeeImports.Org;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-093A test fixtures over the public API: an organization with a tenant administrator and
 * employees linked to their own employee memberships, and the preview, commit, cancellation and
 * read calls of monthly compensation.
 */
final class CompensationWorld {

  static final ObjectMapper JSON = new ObjectMapper();
  static final String EMPLOYEES = "/api/v1/employees";
  static final String MINE = "/api/v1/me/compensation";
  static final LocalDate HIRED = LocalDate.of(2026, 3, 1);
  static final String EXPLANATION = "Montant saisi par erreur lors de la creation";

  final MockMvc mvc;
  final JdbcTemplate jdbc;
  final EmailLookup lookups;
  final Org org;
  final String admin;
  final LocalDate today;

  /** An employee linked to their own membership. */
  record Person(UUID employee, String bearer, String subject) {}

  CompensationWorld(MockMvc mvc, JdbcTemplate jdbc, EmailLookup lookups) throws Exception {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.lookups = lookups;
    this.org = EmployeeImports.newOrg(mvc);
    this.admin =
        Hierarchy.bearer(org.tenant(), "sub-comp93-admin-" + UUID.randomUUID(), "tenant-admin");
    this.today = Employees.today(jdbc, org.tenant());
  }

  UUID tenant() {
    return org.tenant();
  }

  // ------------------------------------------------------------------------------------------
  // People
  // ------------------------------------------------------------------------------------------

  static String employeeBearer(UUID tenant, String subject) {
    return "Bearer "
        + TestTokens.token().tenant(tenant).subject(subject).roles(List.of("employee")).build();
  }

  /** A hired employee with no access link. */
  UUID hire(String given, String family, LocalDate start) throws Exception {
    return Employees.hire(mvc, jdbc, org, admin, Employees.number(), given, family, start);
  }

  /** An employee membership with no access link; returns its subject. */
  private UUID member(String subject) {
    UUID membership = UUID.randomUUID();
    String address = "comp-" + UUID.randomUUID().toString().substring(0, 8) + "@exemple.cd";
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, 'employee', ?, now())",
        membership,
        org.tenant(),
        subject,
        lookups.of(EmailAddress.parse(address).orElseThrow()));
    return membership;
  }

  /** An employee member of the organization who has no active access link. */
  String unlinkedBearer() {
    String subject = UUID.randomUUID().toString();
    member(subject);
    return employeeBearer(org.tenant(), subject);
  }

  /** A hired employee (hired {@link #HIRED}) linked to their own employee membership. */
  Person person(String given, String family) throws Exception {
    UUID employee = hire(given, family, HIRED);
    String subject = UUID.randomUUID().toString();
    UUID membership = member(subject);
    expect(
        Employees.postJson(
                admin,
                EMPLOYEES + "/" + employee + "/access-link",
                "{\"membershipId\":\"" + membership + "\"}")
            .header("Idempotency-Key", Organizations.newKey()),
        201);
    return new Person(employee, employeeBearer(org.tenant(), subject), subject);
  }

  // ------------------------------------------------------------------------------------------
  // Requests
  // ------------------------------------------------------------------------------------------

  static Map<String, Object> command(
      String action, LocalDate from, String currency, String amount) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("action", action);
    body.put("effectiveFrom", from.toString());
    body.put("currency", currency);
    body.put("amount", amount);
    return body;
  }

  static Map<String, Object> correction(
      LocalDate from, String currency, String amount, UUID target) {
    Map<String, Object> body = command("CORRECTED", from, currency, amount);
    body.put("targetChangeId", target.toString());
    body.put("reasonCode", "DATA_ENTRY_ERROR");
    body.put("explanation", EXPLANATION);
    return body;
  }

  static Map<String, Object> committing(
      Map<String, Object> command, JsonNode preview, boolean acknowledged) {
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("version").asInt());
    body.put("employmentDigest", preview.get("employmentDigest").asText());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("acknowledgeRetroactive", acknowledged);
    body.put("confirmed", true);
    return body;
  }

  static Map<String, Object> cancelling(JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", preview.get("version").asInt());
    body.put("employmentDigest", preview.get("employmentDigest").asText());
    body.put("cancellationDigest", preview.get("cancellationDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("confirmed", true);
    return body;
  }

  static MockHttpServletRequestBuilder json(String path, String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post(path)
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String text ? text : JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder previewRequest(String bearer, Object employee, Object body)
      throws Exception {
    return json(EMPLOYEES + "/" + employee + "/compensation/preview", bearer, null, body);
  }

  static MockHttpServletRequestBuilder commitRequest(
      String bearer, Object employee, String key, Object body) throws Exception {
    return json(EMPLOYEES + "/" + employee + "/compensation/commit", bearer, key, body);
  }

  static MockHttpServletRequestBuilder cancelPreviewRequest(
      String bearer, Object employee, Object change) {
    return post(EMPLOYEES + "/" + employee + "/compensation/" + change + "/cancel-preview")
        .header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder cancelRequest(
      String bearer, Object employee, Object change, String key, Object body) throws Exception {
    return json(
        EMPLOYEES + "/" + employee + "/compensation/" + change + "/cancel", bearer, key, body);
  }

  static MockHttpServletRequestBuilder readRequest(
      String bearer, Object employee, String query) {
    return get(EMPLOYEES + "/" + employee + "/compensation" + (query == null ? "" : "?" + query))
        .header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder mineRequest(String bearer, String query) {
    return get(MINE + (query == null ? "" : "?" + query)).header("Authorization", bearer);
  }

  // ------------------------------------------------------------------------------------------
  // Flows
  // ------------------------------------------------------------------------------------------

  JsonNode preview(UUID employee, Map<String, Object> command) throws Exception {
    return expect(previewRequest(admin, employee, command), 200);
  }

  /** Previews and commits a command; returns the receipt. */
  JsonNode record(UUID employee, Map<String, Object> command, boolean acknowledged)
      throws Exception {
    JsonNode preview = preview(employee, command);
    assertThat(preview.get("blockers")).isEmpty();
    return expect(
        commitRequest(
            admin, employee, Organizations.newKey(), committing(command, preview, acknowledged)),
        201);
  }

  JsonNode read(UUID employee) throws Exception {
    return expect(readRequest(admin, employee, null), 200);
  }

  // ------------------------------------------------------------------------------------------
  // Calls
  // ------------------------------------------------------------------------------------------

  MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse();
  }

  JsonNode expect(MockHttpServletRequestBuilder request, int status) throws Exception {
    MockHttpServletResponse response = call(request);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    return JSON.readTree(response.getContentAsString());
  }

  JsonNode problem(MockHttpServletRequestBuilder request, int status, String code)
      throws Exception {
    JsonNode body = expect(request, status);
    assertThat(body.get("code").asText()).isEqualTo(code);
    return body;
  }

  int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  /** Changes, facts, audit rows of the commands and internal events of the tenant. */
  List<Integer> evidence() {
    return List.of(
        count("SELECT count(*) FROM payroll.compensation_change WHERE tenant_id = ?", tenant()),
        count("SELECT count(*) FROM payroll.compensation WHERE tenant_id = ?", tenant()),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('compensation.establish', 'compensation.change',"
                + " 'compensation.correct', 'compensation.scheduled-change-cancel')",
            tenant()),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.compensation.%'",
            tenant()));
  }

  /** The audit metadata, outbox envelopes and idempotency bodies of the tenant, as one text. */
  String persistedEvidence() {
    List<String> rows =
        jdbc.queryForList(
            "SELECT metadata::text || coalesce(after_state_sha256, '')"
                + " FROM platform.audit_event WHERE tenant_id = ? AND action LIKE 'compensation.%'",
            String.class,
            tenant());
    rows.addAll(
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.compensation.%'",
            String.class,
            tenant()));
    rows.addAll(
        jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation LIKE 'compensation.%' AND response_body IS NOT NULL",
            String.class));
    return String.join("\n", rows);
  }
}
