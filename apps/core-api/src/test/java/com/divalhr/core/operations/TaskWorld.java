package com.divalhr.core.operations;

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
 * MVP-080 test fixtures over the public API: an organization with a tenant administrator and
 * employees linked to their own employee memberships; tasks created, started, completed and
 * cancelled through the real endpoints.
 */
final class TaskWorld {

  static final ObjectMapper JSON = new ObjectMapper();
  static final String TASKS = "/api/v1/tasks";
  static final String MY_TASKS = "/api/v1/me/tasks";
  static final LocalDate HIRED = LocalDate.of(2026, 3, 1);

  final MockMvc mvc;
  final JdbcTemplate jdbc;
  final EmailLookup lookups;
  final Org org;
  final String admin;
  final String adminSubject;

  /** An employee linked to their own membership. */
  record Person(UUID employee, String bearer, String subject, UUID membership, String number) {}

  TaskWorld(MockMvc mvc, JdbcTemplate jdbc, EmailLookup lookups) throws Exception {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.lookups = lookups;
    this.org = EmployeeImports.newOrg(mvc);
    this.adminSubject = "sub-task80-admin-" + UUID.randomUUID();
    this.admin = Hierarchy.bearer(org.tenant(), adminSubject, "tenant-admin");
  }

  UUID tenant() {
    return org.tenant();
  }

  // ------------------------------------------------------------------------------------------
  // People
  // ------------------------------------------------------------------------------------------

  UUID member(String subject) {
    UUID id = UUID.randomUUID();
    String address = "task-" + UUID.randomUUID().toString().substring(0, 8) + "@exemple.cd";
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

  /** A hired employee with no access link. */
  UUID hire(String given, String family) throws Exception {
    return Employees.hire(mvc, jdbc, org, admin, Employees.number(), given, family, HIRED);
  }

  /** A hired employee linked to their own employee membership. */
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
                "/api/v1/employees/" + employee + "/access-link",
                "{\"membershipId\":\"" + membership + "\"}")
            .header("Idempotency-Key", Organizations.newKey()),
        201);
  }

  void unlink(UUID employee) throws Exception {
    JsonNode link =
        expect(
                get("/api/v1/employees/" + employee + "/access-link")
                    .header("Authorization", admin),
                200)
            .get("link");
    expect(
        Employees.postJson(
                admin,
                "/api/v1/employees/" + employee + "/access-link/remove",
                JSON.writeValueAsString(
                    Map.of(
                        "linkId",
                        link.get("id").asText(),
                        "expectedVersion",
                        link.get("version").asLong())))
            .header("Idempotency-Key", Organizations.newKey()),
        200);
  }

  // ------------------------------------------------------------------------------------------
  // Tasks
  // ------------------------------------------------------------------------------------------

  static Map<String, Object> newTask(UUID assignee, String title) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("assigneeEmployeeId", assignee.toString());
    body.put("title", title);
    body.put("priority", "NORMAL");
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

  MockHttpServletRequestBuilder createRequest(String key, Map<String, Object> body)
      throws Exception {
    return json(TASKS, admin, key, body);
  }

  /** Assigns a task; returns the receipt. */
  JsonNode create(UUID assignee, String title) throws Exception {
    return expect(createRequest(Organizations.newKey(), newTask(assignee, title)), 201);
  }

  MockHttpServletRequestBuilder cancelRequest(
      String id, String key, int version, String reason, String explanation) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", version);
    body.put("reason", reason);
    if (explanation != null) {
      body.put("explanation", explanation);
    }
    return json(TASKS + "/" + id + "/cancel", admin, key, body);
  }

  static MockHttpServletRequestBuilder startRequest(
      String bearer, String id, String key, int version) throws Exception {
    return json(MY_TASKS + "/" + id + "/start", bearer, key, Map.of("expectedVersion", version));
  }

  static MockHttpServletRequestBuilder completeRequest(
      String bearer, String id, String key, int version, String note) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", version);
    if (note != null) {
      body.put("note", note);
    }
    return json(MY_TASKS + "/" + id + "/complete", bearer, key, body);
  }

  JsonNode start(Person p, String id, int version) throws Exception {
    return expect(startRequest(p.bearer(), id, Organizations.newKey(), version), 200);
  }

  JsonNode complete(Person p, String id, int version, String note) throws Exception {
    return expect(completeRequest(p.bearer(), id, Organizations.newKey(), version, note), 200);
  }

  JsonNode adminTask(String id) throws Exception {
    return expect(get(TASKS + "/" + id).header("Authorization", admin), 200);
  }

  JsonNode myTask(Person p, String id) throws Exception {
    return expect(get(MY_TASKS + "/" + id).header("Authorization", p.bearer()), 200);
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

  /** Tasks, actions, audit rows and events of the tenant. */
  List<Integer> evidence() {
    return List.of(
        count("SELECT count(*) FROM operations.task WHERE tenant_id = ?", tenant()),
        count("SELECT count(*) FROM operations.task_action WHERE tenant_id = ?", tenant()),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('task.create', 'task.start', 'task.complete', 'task.cancel')",
            tenant()),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'operations.task.%'",
            tenant()));
  }

  /** The audit metadata, outbox envelopes and idempotency bodies of the tenant, as one text. */
  String persistedEvidence() {
    List<String> rows =
        jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action LIKE 'task.%'",
            String.class, tenant());
    rows.addAll(
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'operations.task.%'",
            String.class, tenant()));
    rows.addAll(
        jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation LIKE 'task.%' AND response_body IS NOT NULL",
            String.class));
    return String.join("\n", rows);
  }
}
