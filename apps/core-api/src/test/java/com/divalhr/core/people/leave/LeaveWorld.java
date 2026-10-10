package com.divalhr.core.people.leave;

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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * MVP-041B test fixtures over the public API: an organization with a tenant administrator,
 * employees linked to their own employee memberships, policies by route, manager lines, requests,
 * decisions, unlinks and separations; MVP-041C cancellations; MVP-041D amendments and MVP-041E
 * routing exceptions.
 */
final class LeaveWorld {

  static final ObjectMapper JSON = new ObjectMapper();
  static final String MY_REQUESTS = "/api/v1/me/leave-requests";
  static final String MY_APPROVALS = "/api/v1/me/leave-approvals";
  static final String ADMIN_APPROVALS = "/api/v1/leave-approvals";
  static final String EXCEPTIONS = "/api/v1/leave-routing-exceptions";
  static final LocalDate HIRED = LocalDate.of(2026, 3, 1);
  static final String EMPLOYEES = "/api/v1/employees";

  final MockMvc mvc;
  final JdbcTemplate jdbc;
  final EmailLookup lookups;
  final Org org;
  final String adminSubject;
  final String admin;
  final LocalDate today;

  /** An employee linked to their own membership. */
  record Person(UUID employee, String bearer, String subject, UUID membership, String number) {}

  LeaveWorld(MockMvc mvc, JdbcTemplate jdbc, EmailLookup lookups) throws Exception {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.lookups = lookups;
    this.org = EmployeeImports.newOrg(mvc);
    this.adminSubject = "sub-leave41b-admin-" + UUID.randomUUID();
    this.admin = Hierarchy.bearer(org.tenant(), adminSubject, "tenant-admin");
    this.today = Employees.today(jdbc, org.tenant());
  }

  UUID tenant() {
    return org.tenant();
  }

  // ------------------------------------------------------------------------------------------
  // People
  // ------------------------------------------------------------------------------------------

  UUID member(String subject) {
    UUID id = UUID.randomUUID();
    String address = "leave-" + UUID.randomUUID().toString().substring(0, 8) + "@exemple.cd";
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, 'employee', ?, now())",
        id,
        org.tenant(),
        subject,
        lookups.of(EmailAddress.parse(address).orElseThrow()));
    return id;
  }

  static String employeeBearer(UUID tenant, String subject) {
    return "Bearer "
        + TestTokens.token().tenant(tenant).subject(subject).roles(List.of("employee")).build();
  }

  UUID hire(String given, String family) throws Exception {
    return Employees.hire(mvc, jdbc, org, admin, Employees.number(), given, family, HIRED);
  }

  Person person(String given, String family) throws Exception {
    UUID employee = hire(given, family);
    String subject = UUID.randomUUID().toString();
    UUID membership = member(subject);
    linkTo(employee, membership);
    String number =
        jdbc.queryForObject(
            "SELECT employee_number FROM people.employee WHERE id = ?", String.class, employee);
    return new Person(employee, employeeBearer(org.tenant(), subject), subject, membership, number);
  }

  void linkTo(UUID employee, UUID membership) throws Exception {
    expect(
        Employees.postJson(
                admin,
                EMPLOYEES + "/" + employee + "/access-link",
                "{\"membershipId\":\"" + membership + "\"}")
            .header("Idempotency-Key", Organizations.newKey()),
        201);
  }

  void unlink(UUID employee) throws Exception {
    JsonNode link =
        expect(get(EMPLOYEES + "/" + employee + "/access-link").header("Authorization", admin), 200)
            .get("link");
    expect(
        Employees.postJson(
                admin,
                EMPLOYEES + "/" + employee + "/access-link/remove",
                JSON.writeValueAsString(
                    Map.of(
                        "linkId",
                        link.get("id").asText(),
                        "expectedVersion",
                        link.get("version").asLong())))
            .header("Idempotency-Key", Organizations.newKey()),
        200);
  }

  /** The report's MANAGER line from {@code from} on, through the employment-change API. */
  void manage(UUID report, UUID manager, LocalDate from) throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    command.put("manager", Map.of("employeeId", manager.toString()));
    if (from.isBefore(today)) {
      command.put("reasonCode", "LATE_NOTIFICATION");
    }
    MockHttpServletRequestBuilder preview =
        Employees.postJson(
            admin,
            EMPLOYEES + "/" + report + "/employment-changes/preview",
            JSON.writeValueAsString(command));
    JsonNode previewed = expect(preview, 200);
    expect(managerChange(report, command, previewed), 201);
  }

  /** Clears the report's MANAGER line from {@code from} on (a CHANGE with no manager). */
  void unmanage(UUID report, LocalDate from) throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    Map<String, Object> none = new LinkedHashMap<>();
    none.put("employeeId", null);
    command.put("manager", none);
    if (from.isBefore(today)) {
      command.put("reasonCode", "LATE_NOTIFICATION");
    }
    changeManager(report, command);
  }

  /** Corrects the report's active MANAGER row naming {@code from} to name {@code to} instead. */
  void correctManager(UUID report, UUID from, UUID to) throws Exception {
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT id::text AS id, effective_from FROM people.employment_assignment"
                + " WHERE employee_id = ? AND kind = 'MANAGER' AND superseded_by_change_id IS NULL"
                + " AND manager_employee_id = ?",
            report,
            from);
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CORRECTION");
    command.put("effectiveFrom", row.get("effective_from").toString());
    command.put("manager", Map.of("employeeId", to.toString()));
    command.put("reasonCode", "DATA_ENTRY_ERROR");
    command.put("correctsAssignmentId", row.get("id"));
    changeManager(report, command);
  }

  /** Previews and commits an employment change of the report's manager. */
  void changeManager(UUID report, Map<String, Object> command) throws Exception {
    JsonNode previewed =
        expect(
            Employees.postJson(
                admin,
                EMPLOYEES + "/" + report + "/employment-changes/preview",
                JSON.writeValueAsString(command)),
            200);
    expect(managerChange(report, command, previewed), 201);
  }

  /** The commit of a previewed manager change (for races). */
  MockHttpServletRequestBuilder managerChange(
      UUID report, Map<String, Object> command, JsonNode preview) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgeRetroactive", preview.get("requiresAcknowledgement").asBoolean());
    return Employees.postJson(
            admin, EMPLOYEES + "/" + report + "/employment-changes", JSON.writeValueAsString(body))
        .header("Idempotency-Key", Organizations.newKey());
  }

  /** A separation command (last day, end-of-last-day access) and its required preview. */
  Map<String, Object> separation(LocalDate lastDay) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("lastDay", lastDay.toString());
    command.put("reasonCode", "RESIGNATION");
    command.put("accessTiming", "END_OF_LAST_DAY");
    return command;
  }

  /** The commit of a previewed separation with exactly the acknowledgements it required. */
  MockHttpServletRequestBuilder separate(UUID employee, Map<String, Object> command)
      throws Exception {
    JsonNode preview =
        expect(
            Employees.postJson(
                admin,
                EMPLOYEES + "/" + employee + "/separations/preview",
                JSON.writeValueAsString(command)),
            200);
    List<String> acks = new ArrayList<>();
    preview.get("requiredAcknowledgements").forEach(a -> acks.add(a.asText()));
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgements", acks);
    return Employees.postJson(
            admin, EMPLOYEES + "/" + employee + "/separations", JSON.writeValueAsString(body))
        .header("Idempotency-Key", Organizations.newKey());
  }

  // ------------------------------------------------------------------------------------------
  // Leave
  // ------------------------------------------------------------------------------------------

  /** A policy with the route; returns its id. */
  String policy(String code, String route) throws Exception {
    return policy(code, route, 0);
  }

  /** A policy with the route and a minimum service; returns its id. */
  String policy(String code, String route, int minimumServiceDays) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("names", Map.of("en", "Annual leave " + code, "fr", "Congé annuel " + code));
    body.put("unit", "DAYS");
    body.put("balanceMode", "UNTRACKED");
    body.put("minimumServiceDays", minimumServiceDays);
    body.put("approvalRoute", route);
    body.put("payrollEffect", "PAID");
    body.put("effectiveFrom", "2026-01-01");
    return expect(
            post("/api/v1/leave-policies")
                .header("Authorization", admin)
                .header("Idempotency-Key", Organizations.newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)),
            201)
        .get("policy")
        .get("id")
        .asText();
  }

  static MockHttpServletRequestBuilder submit(
      String bearer, String policyId, LocalDate start, LocalDate end) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("policyId", policyId);
    body.put("startDate", start.toString());
    body.put("endDate", end.toString());
    body.put("amount", 2.5);
    return post(MY_REQUESTS)
        .header("Authorization", bearer)
        .header("Idempotency-Key", Organizations.newKey())
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(body));
  }

  /** Submits a request; returns its id. */
  String request(Person p, String policyId, LocalDate start, LocalDate end) throws Exception {
    return expect(submit(p.bearer(), policyId, start, end), 201).get("id").asText();
  }

  static Map<String, Object> decision(String outcome, String locale, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decision", outcome);
    body.put("reasonLocale", locale);
    body.put("reason", reason);
    return body;
  }

  static MockHttpServletRequestBuilder decide(
      String base, String bearer, String requestId, String key, Object body) throws Exception {
    MockHttpServletRequestBuilder request =
        post(base + "/" + requestId + "/decision")
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String text ? text : JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static Map<String, Object> cancellation(String locale, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reasonLocale", locale);
    body.put("reason", reason);
    return body;
  }

  /** The caller's own cancellation of a request (MVP-041C). */
  static MockHttpServletRequestBuilder cancel(
      String bearer, String requestId, String key, Object body) throws Exception {
    MockHttpServletRequestBuilder request =
        post(MY_REQUESTS + "/" + requestId + "/cancellation")
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String text ? text : JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  /** An amendment body: the replacement's fields and the reason (MVP-041D). */
  static Map<String, Object> amendment(
      String policyId,
      LocalDate start,
      LocalDate end,
      Object amount,
      String locale,
      String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("policyId", policyId);
    body.put("startDate", start == null ? null : start.toString());
    body.put("endDate", end == null ? null : end.toString());
    body.put("amount", amount);
    body.put("reasonLocale", locale);
    body.put("reason", reason);
    return body;
  }

  /** The caller's own amendment of a request (MVP-041D). */
  static MockHttpServletRequestBuilder amend(
      String bearer, String requestId, String key, Object body) throws Exception {
    MockHttpServletRequestBuilder request =
        post(MY_REQUESTS + "/" + requestId + "/amendment")
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String text ? text : JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  // ------------------------------------------------------------------------------------------
  // HTTP and rows
  // ------------------------------------------------------------------------------------------

  MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
    MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    return response;
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

  /** Decisions, decision audits and decision events of the tenant. */
  List<Integer> decisions() {
    return List.of(
        count("SELECT count(*) FROM people.leave_request_decision WHERE tenant_id = ?", tenant()),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action IN"
                + " ('leave-request.approve', 'leave-request.reject')",
            tenant()),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                + " envelope ->> 'eventType' IN ('people.leave-request.approved.v1',"
                + " 'people.leave-request.rejected.v1')",
            tenant().toString()));
  }

  /** Cancellations, cancellation audits and cancellation events of the tenant (MVP-041C). */
  List<Integer> cancellations() {
    return List.of(
        count(
            "SELECT count(*) FROM people.leave_request_cancellation WHERE tenant_id = ?", tenant()),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action ="
                + " 'leave-request.cancel'",
            tenant()),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                + " envelope ->> 'eventType' = 'people.leave-request.cancelled.v1'",
            tenant().toString()));
  }

  /** Amendments, amendment audits and amendment events of the tenant (MVP-041D). */
  List<Integer> amendments() {
    return List.of(
        count("SELECT count(*) FROM people.leave_request_amendment WHERE tenant_id = ?", tenant()),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action ="
                + " 'leave-request.amend'",
            tenant()),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                + " envelope ->> 'eventType' = 'people.leave-request.amended.v1'",
            tenant().toString()));
  }

  /** Override decisions, override audits and override events of the tenant (MVP-041E). */
  List<Integer> overrides() {
    return List.of(
        count(
            "SELECT count(*) FROM people.leave_request_decision WHERE tenant_id = ?"
                + " AND decision_authority = 'TENANT_ADMIN_OVERRIDE'",
            tenant()),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action IN"
                + " ('leave-request.routing-exception.approve',"
                + " 'leave-request.routing-exception.reject')",
            tenant()),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                + " envelope ->> 'eventType' IN"
                + " ('people.leave-request.routing-exception-approved.v1',"
                + " 'people.leave-request.routing-exception-rejected.v1')",
            tenant().toString()));
  }

  String state(String requestId) {
    return jdbc.queryForObject(
        "SELECT state FROM people.leave_request WHERE id = ?::uuid", String.class, requestId);
  }
}
