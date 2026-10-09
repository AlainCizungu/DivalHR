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
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-041A (Issue #87) end to end against PostgreSQL with a settable clock: the requestable policy
 * catalogue, submitting and listing one's own requests, the business-date boundary, policy period,
 * employment period and minimum service, overlap, validation, idempotent replay, tenant, employee
 * and subject isolation of rows and cursors, the employee authorization model, the fail-closed
 * disclosure audit, audit and event contents, and log privacy.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class MyLeaveIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String POLICIES = "/api/v1/me/leave-policies";
  private static final String REQUESTS = "/api/v1/me/leave-requests";
  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");
  private static final LocalDate HIRED = LocalDate.of(2026, 3, 1);

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SettableClock clock;
  @Autowired private EmailLookup lookups;

  @AfterEach
  void realTime() {
    clock.reset();
  }

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

  private record World(Org org, String admin, LocalDate today) {}

  private World world() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    return new World(
        org,
        Hierarchy.bearer(org.tenant(), "sub-leave41-admin-" + UUID.randomUUID(), "tenant-admin"),
        Employees.today(jdbc, org.tenant()));
  }

  private record Person(UUID employee, String bearer, String subject, UUID membership) {}

  private UUID member(World w, String subject, String role) {
    UUID id = UUID.randomUUID();
    String address = "leave-" + UUID.randomUUID().toString().substring(0, 8) + "@exemple.cd";
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, ?, ?, now())",
        id,
        w.org().tenant(),
        subject,
        role,
        lookups.of(EmailAddress.parse(address).orElseThrow()));
    return id;
  }

  private static String employeeBearer(UUID tenant, String subject) {
    return "Bearer "
        + TestTokens.token().tenant(tenant).subject(subject).roles(List.of("employee")).build();
  }

  /** An employee hired on {@code hired}, with an employee membership linked to them. */
  private Person person(World w, LocalDate hired) throws Exception {
    UUID employee =
        Employees.hire(
            mvc, jdbc, w.org(), w.admin(), Employees.number(), "Bénédicte", "Mbuyi", hired);
    String subject = UUID.randomUUID().toString();
    UUID membership = member(w, subject, "employee");
    linkTo(w, employee, membership);
    return new Person(employee, employeeBearer(w.org().tenant(), subject), subject, membership);
  }

  /** Links the employee to the membership (administrator). */
  private void linkTo(World w, UUID employee, UUID membership) throws Exception {
    expect(
        Employees.postJson(
                w.admin(),
                "/api/v1/employees/" + employee + "/access-link",
                "{\"membershipId\":\"" + membership + "\"}")
            .header("Idempotency-Key", Organizations.newKey()),
        201);
  }

  /** Removes the employee's active link (administrator). */
  private void unlink(World w, UUID employee) throws Exception {
    JsonNode link =
        expect(
                get("/api/v1/employees/" + employee + "/access-link")
                    .header("Authorization", w.admin()),
                200)
            .get("link");
    expect(
        Employees.postJson(
                w.admin(),
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

  /** Rows a creation writes: requests of the tenant, create audits and created events. */
  private List<Integer> written(World w) {
    return List.of(
        count("SELECT count(*) FROM people.leave_request WHERE tenant_id = ?", w.org().tenant()),
        count(
            "SELECT count(*) FROM platform.audit_event WHERE action = 'leave-request.create'"
                + " AND tenant_id = ?",
            w.org().tenant()),
        count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'eventType' ="
                + " 'people.leave-request.created.v1' AND envelope ->> 'subject' IN"
                + " (SELECT id::text FROM people.leave_request WHERE tenant_id = ?)",
            w.org().tenant()));
  }

  /** A policy created by the administrator; returns its id. */
  private String policy(World w, String code, int minimumService, String from, String to)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("names", Map.of("en", "Annual leave " + code, "fr", "Congé annuel " + code));
    body.put("unit", "DAYS");
    body.put("balanceMode", "UNTRACKED");
    body.put("minimumServiceDays", minimumService);
    body.put("approvalRoute", "MANAGER");
    body.put("payrollEffect", "PAID");
    body.put("effectiveFrom", from);
    if (to != null) {
      body.put("effectiveTo", to);
    }
    return expect(
            post("/api/v1/leave-policies")
                .header("Authorization", w.admin())
                .header("Idempotency-Key", Organizations.newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)),
            201)
        .get("policy")
        .get("id")
        .asText();
  }

  private static Map<String, Object> leave(String policyId, LocalDate start, LocalDate end) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("policyId", policyId);
    body.put("startDate", start.toString());
    body.put("endDate", end.toString());
    body.put("amount", 2.5);
    return body;
  }

  private static MockHttpServletRequestBuilder submit(String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post(REQUESTS)
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String text ? text : JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
    MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    return response;
  }

  private JsonNode expect(MockHttpServletRequestBuilder request, int status) throws Exception {
    MockHttpServletResponse response = call(request);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    return JSON.readTree(response.getContentAsString());
  }

  private JsonNode problem(MockHttpServletRequestBuilder request, int status, String code)
      throws Exception {
    JsonNode body = expect(request, status);
    assertThat(body.get("code").asText()).isEqualTo(code);
    return body;
  }

  private static List<String> fields(JsonNode problem) {
    List<String> fields = new ArrayList<>();
    problem
        .path("params")
        .path("fields")
        .forEach(f -> fields.add(f.get("field").asText() + ":" + f.get("constraint").asText()));
    return fields;
  }

  private static List<String> texts(JsonNode items, String field) {
    List<String> values = new ArrayList<>();
    items.forEach(item -> values.add(item.get(field).asText()));
    return values;
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  /** The application's structured log lines (MockMvc's own failure dump is not a log). */
  private static String logs(CapturedOutput output) {
    return String.join(
        "\n", output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp\"")).toList());
  }

  // ------------------------------------------------------------------------------------------
  // Catalogue, create, history, audit and event (AC1, AC2, AC7, AC8)
  // ------------------------------------------------------------------------------------------

  @Test
  void anEmployeeSeesRequestablePoliciesSubmitsAndSeesTheRequestPending(CapturedOutput output)
      throws Exception {
    World w = world();
    String open = policy(w, "OPEN", 0, "2026-01-01", null);
    policy(w, "ENDED", 0, "2026-01-01", w.today().minusDays(1).toString());
    policy(w, "PLANNED", 0, w.today().plusDays(30).toString(), null);
    Person p = person(w, HIRED);

    MockHttpServletResponse catalogue = call(get(POLICIES).header("Authorization", p.bearer()));
    assertThat(catalogue.getStatus()).isEqualTo(200);
    assertThat(catalogue.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode policies = JSON.readTree(catalogue.getContentAsString());
    assertThat(texts(policies.get("items"), "code")).containsExactly("OPEN", "PLANNED");
    assertThat(texts(policies.get("items"), "status")).containsExactly("ACTIVE", "PLANNED");
    assertThat(policies.get("items").get(0).get("names").get("fr").asText())
        .isEqualTo("Congé annuel OPEN");
    assertThat(policies.get("items").get(0).get("versionId").asText()).hasSize(36);
    assertThat(policies.get("asOf").asText()).isEqualTo(w.today().toString());
    assertThat(policies.get("timezone").asText()).isEqualTo("Africa/Kinshasa");

    String key = Organizations.newKey();
    Map<String, Object> body = leave(open, w.today().plusDays(10), w.today().plusDays(12));
    MockHttpServletResponse created = call(submit(p.bearer(), key, body));
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    assertThat(created.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(created.getContentAsString()).contains("\"amount\":2.50,");
    JsonNode request = JSON.readTree(created.getContentAsString());
    assertThat(request.get("state").asText()).isEqualTo("PENDING");
    assertThat(request.get("policyId").asText()).isEqualTo(open);
    assertThat(request.get("policyCode").asText()).isEqualTo("OPEN");
    assertThat(request.get("policyNames").get("fr").asText()).isEqualTo("Congé annuel OPEN");
    assertThat(request.get("unit").asText()).isEqualTo("DAYS");
    assertThat(request.get("startDate").asText()).isEqualTo(w.today().plusDays(10).toString());

    // Replay: the stored response; a changed payload with the same key conflicts.
    MockHttpServletResponse replay = call(submit(p.bearer(), key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(request);
    Map<String, Object> changed = new LinkedHashMap<>(body);
    changed.put("amount", 3);
    problem(submit(p.bearer(), key, changed), 409, "IDEMPOTENCY_KEY_REUSED");

    JsonNode history = expect(get(REQUESTS).header("Authorization", p.bearer()), 200);
    assertThat(history.get("items")).hasSize(1);
    assertThat(history.get("items").get(0)).isEqualTo(request);
    assertThat(history.get("nextCursor").isNull()).isTrue();
    assertThat(
            count(
                "SELECT count(*) FROM people.leave_request WHERE tenant_id = ? AND employee_id = ?",
                w.org().tenant(),
                p.employee()))
        .isEqualTo(1);

    // Audit: identifiers, unit and state only.
    UUID id = UUID.fromString(request.get("id").asText());
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT action, resource_type, result, metadata::text AS metadata"
                + " FROM platform.audit_event WHERE resource_id = ?",
            id);
    assertThat(audit.get("action")).isEqualTo("leave-request.create");
    assertThat(audit.get("resource_type")).isEqualTo("leave-request");
    JsonNode metadata = JSON.readTree((String) audit.get("metadata"));
    assertThat(fieldNames(metadata))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "requestId",
            "employeeId",
            "employmentId",
            "policyId",
            "policyVersionId",
            "unit",
            "state");
    // Event: identifiers, dates, amount, unit and state; never names or subjects.
    JsonNode envelope =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
                String.class,
                id.toString()));
    assertThat(envelope.get("eventType").asText()).isEqualTo("people.leave-request.created.v1");
    assertThat(fieldNames(envelope.get("data")))
        .containsExactlyInAnyOrder(
            "requestId",
            "employeeId",
            "employmentId",
            "policyId",
            "policyVersionId",
            "unit",
            "state",
            "startDate",
            "endDate",
            "amount");
    assertThat(envelope.toString()).doesNotContain("Congé").doesNotContain(p.subject());
    // Disclosure audit of the history: the employee and the count.
    JsonNode disclosure =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE action ="
                    + " 'leave-request.self-list' AND resource_id = ?",
                String.class,
                p.employee()));
    assertThat(disclosure.get("resultCount").asInt()).isEqualTo(1);
    assertThat(fieldNames(disclosure))
        .containsExactlyInAnyOrder("schemaVersion", "view", "page", "resultCount");

    assertThat(logs(output))
        .contains("leave_request_created")
        .contains("leave_request_self_listed")
        .doesNotContain("Congé")
        .doesNotContain(w.today().plusDays(10).toString())
        .doesNotContain(p.subject())
        .doesNotContain(key);
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  // ------------------------------------------------------------------------------------------
  // Business date boundary (D41A-3 rule 1)
  // ------------------------------------------------------------------------------------------

  @Test
  void theStartMayBeTheOrganizationsBusinessDateButNotEarlier() throws Exception {
    World w = world();
    String open = policy(w, "OPEN", 0, "2026-01-01", null);
    Person p = person(w, HIRED);
    LocalDate day = w.today().plusDays(5);
    // 22:59Z on the eve of `day` is 23:59 in Kinshasa: the business date is still day - 1.
    clock.set(day.minusDays(1).atTime(22, 59).toInstant(ZoneOffset.UTC));
    JsonNode early =
        problem(
            submit(p.bearer(), Organizations.newKey(), leave(open, day.minusDays(2), day)),
            400,
            "VALIDATION_FAILED");
    assertThat(fields(early)).containsExactly("startDate:RANGE");
    expect(submit(p.bearer(), Organizations.newKey(), leave(open, day.minusDays(1), day)), 201);
    // 23:00Z is local midnight: day - 1 is now in the past.
    clock.set(day.minusDays(1).atTime(23, 0).toInstant(ZoneOffset.UTC));
    JsonNode late =
        problem(
            submit(
                p.bearer(),
                Organizations.newKey(),
                leave(open, day.minusDays(1), day.minusDays(1))),
            400,
            "VALIDATION_FAILED");
    assertThat(fields(late)).containsExactly("startDate:RANGE");
    JsonNode catalogue = expect(get(POLICIES).header("Authorization", p.bearer()), 200);
    assertThat(catalogue.get("asOf").asText()).isEqualTo(day.toString());
    assertThat(
            LocalDate.ofInstant(day.minusDays(1).atTime(23, 0).toInstant(ZoneOffset.UTC), KINSHASA))
        .isEqualTo(day);
  }

  // ------------------------------------------------------------------------------------------
  // Replay (R88-1, R88-2)
  // ------------------------------------------------------------------------------------------

  @Test
  void anExactRetryIsReplayedAfterTheBusinessDatePassedItsStart() throws Exception {
    World w = world();
    String open = policy(w, "OPEN", 0, "2026-01-01", null);
    Person p = person(w, HIRED);
    LocalDate day = w.today().plusDays(5);
    clock.set(day.atTime(9, 0).toInstant(ZoneOffset.UTC));
    String key = Organizations.newKey();
    Map<String, Object> body = leave(open, day, day.plusDays(1));
    JsonNode created = expect(submit(p.bearer(), key, body), 201);
    assertThat(written(w)).containsExactly(1, 1, 1);

    // Two days later the first day has passed; the identical retry is still the stored 201.
    clock.set(day.plusDays(2).atTime(9, 0).toInstant(ZoneOffset.UTC));
    MockHttpServletResponse replay = call(submit(p.bearer(), key, body));
    assertThat(replay.getStatus()).as(replay.getContentAsString()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(created);
    // A changed body on that key is the key-reuse conflict, not a date validation.
    Map<String, Object> changed = new LinkedHashMap<>(body);
    changed.put("amount", 3);
    JsonNode reused = problem(submit(p.bearer(), key, changed), 409, "IDEMPOTENCY_KEY_REUSED");
    assertThat(reused.path("params").size()).isZero();
    // The same body under a new key is a new request, and the date rule applies to it.
    JsonNode late =
        problem(submit(p.bearer(), Organizations.newKey(), body), 400, "VALIDATION_FAILED");
    assertThat(fields(late)).containsExactly("startDate:RANGE");
    assertThat(written(w)).containsExactly(1, 1, 1);
  }

  @Test
  void aReplayNeedsTheCurrentLinkToTheSameEmployee() throws Exception {
    World w = world();
    String open = policy(w, "OPEN", 0, "2026-01-01", null);
    Person p = person(w, HIRED);
    UUID other =
        Employees.hire(mvc, jdbc, w.org(), w.admin(), Employees.number(), "Josué", "Kabila", HIRED);
    String key = Organizations.newKey();
    Map<String, Object> body = leave(open, w.today().plusDays(3), w.today().plusDays(4));
    JsonNode created = expect(submit(p.bearer(), key, body), 201);
    String requestId = created.get("id").asText();
    assertThat(written(w)).containsExactly(1, 1, 1);

    // (a) Unlinked: the established link denial, and nothing of the stored response.
    unlink(w, p.employee());
    MockHttpServletResponse unlinked = call(submit(p.bearer(), key, body));
    assertThat(unlinked.getStatus()).isEqualTo(403);
    assertThat(JSON.readTree(unlinked.getContentAsString()).get("code").asText())
        .isEqualTo("EMPLOYEE_LINK_REQUIRED");
    assertThat(unlinked.getHeader("Idempotent-Replayed")).isNull();
    assertThat(unlinked.getContentAsString()).doesNotContain(requestId).doesNotContain(open);

    // (b) The same subject now linked to another employee: no replay of the former employee's
    // request, no identifiers, no new rows.
    linkTo(w, other, p.membership());
    MockHttpServletResponse relinked = call(submit(p.bearer(), key, body));
    assertThat(relinked.getStatus()).isEqualTo(409);
    JsonNode refused = JSON.readTree(relinked.getContentAsString());
    assertThat(refused.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(refused.path("params").size()).isZero();
    assertThat(relinked.getHeader("Idempotent-Replayed")).isNull();
    assertThat(relinked.getContentAsString())
        .doesNotContain(requestId)
        .doesNotContain(open)
        .doesNotContain(p.employee().toString());
    assertThat(written(w)).containsExactly(1, 1, 1);
    assertThat(
            count(
                "SELECT count(*) FROM people.leave_request WHERE tenant_id = ? AND employee_id = ?",
                w.org().tenant(),
                other))
        .isZero();
    // The other employee's own history stays empty.
    assertThat(expect(get(REQUESTS).header("Authorization", p.bearer()), 200).get("items"))
        .isEmpty();

    // Linked back to the original employee, the stored response is theirs again.
    unlink(w, other);
    linkTo(w, p.employee(), p.membership());
    MockHttpServletResponse back = call(submit(p.bearer(), key, body));
    assertThat(back.getStatus()).as(back.getContentAsString()).isEqualTo(201);
    assertThat(back.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(back.getContentAsString())).isEqualTo(created);
    assertThat(written(w)).containsExactly(1, 1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // Policy, employment, minimum service, overlap and validation (AC4, AC5)
  // ------------------------------------------------------------------------------------------

  @Test
  void theServerEnforcesThePolicyEmploymentServiceAndOverlapRules() throws Exception {
    World w = world();
    World other = world();
    LocalDate t = w.today();
    String open = policy(w, "OPEN", 0, "2026-01-01", null);
    String ends = policy(w, "ENDS", 0, "2026-01-01", t.plusDays(20).toString());
    String senior = policy(w, "SENIOR", 400, "2026-01-01", null);
    String foreign = policy(other, "FOREIGN", 0, "2026-01-01", null);
    Person p = person(w, HIRED);

    for (String id : List.of(UUID.randomUUID().toString(), foreign)) {
      JsonNode refused =
          problem(
              submit(p.bearer(), Organizations.newKey(), leave(id, t.plusDays(1), t.plusDays(2))),
              409,
              "LEAVE_POLICY_NOT_REQUESTABLE");
      assertThat(refused.get("params").size()).isZero();
    }
    // The policy ends before the interval does.
    problem(
        submit(p.bearer(), Organizations.newKey(), leave(ends, t.plusDays(19), t.plusDays(21))),
        409,
        "LEAVE_POLICY_NOT_REQUESTABLE");
    // Minimum service: 400 calendar days after the hire (2026-03-01).
    JsonNode service =
        problem(
            submit(p.bearer(), Organizations.newKey(), leave(senior, t.plusDays(1), t.plusDays(1))),
            409,
            "LEAVE_REQUEST_NOT_ELIGIBLE");
    assertThat(service.get("params").get("reason").asText()).isEqualTo("MINIMUM_SERVICE");
    LocalDate eligible = HIRED.plusDays(400);
    expect(submit(p.bearer(), Organizations.newKey(), leave(senior, eligible, eligible)), 201);
    problem(
        submit(
            p.bearer(),
            Organizations.newKey(),
            leave(senior, eligible.minusDays(1), eligible.minusDays(1))),
        409,
        "LEAVE_REQUEST_NOT_ELIGIBLE");
    // Overlap with any pending request of the employee, whatever the policy.
    expect(
        submit(p.bearer(), Organizations.newKey(), leave(open, t.plusDays(3), t.plusDays(5))), 201);
    JsonNode overlap =
        problem(
            submit(p.bearer(), Organizations.newKey(), leave(ends, t.plusDays(5), t.plusDays(6))),
            409,
            "LEAVE_REQUEST_OVERLAP");
    assertThat(overlap.get("params").size()).isZero();
    expect(
        submit(p.bearer(), Organizations.newKey(), leave(open, t.plusDays(6), t.plusDays(6))), 201);

    // Employment period: a colleague hired in the future cannot request leave before starting.
    Person future = person(w, t.plusDays(30));
    JsonNode period =
        problem(
            submit(
                future.bearer(),
                Organizations.newKey(),
                leave(open, t.plusDays(29), t.plusDays(31))),
            409,
            "LEAVE_REQUEST_NOT_ELIGIBLE");
    assertThat(period.get("params").get("reason").asText()).isEqualTo("EMPLOYMENT_PERIOD");
    expect(
        submit(
            future.bearer(), Organizations.newKey(), leave(open, t.plusDays(30), t.plusDays(31))),
        201);
    assertThat(
            count(
                "SELECT count(*) FROM people.leave_request WHERE tenant_id = ?", w.org().tenant()))
        .isEqualTo(4);
  }

  @Test
  void invalidRequestsFailSafelyWithoutEchoingValues(CapturedOutput output) throws Exception {
    World w = world();
    String open = policy(w, "OPEN", 0, "2026-01-01", null);
    Person p = person(w, HIRED);
    LocalDate t = w.today();
    Map<String, Object> body = leave("not-a-uuid", t.plusDays(3), t.plusDays(1));
    body.put("amount", 1.005);
    body.put("reason", "Rendez-vous privé");
    JsonNode invalid = problem(submit(p.bearer(), null, body), 400, "VALIDATION_FAILED");
    assertThat(fields(invalid))
        .containsExactlyInAnyOrder(
            "Idempotency-Key:REQUIRED",
            "body:UNKNOWN_PROPERTY",
            "policyId:FORMAT",
            "endDate:RANGE",
            "amount:FORMAT");
    assertThat(invalid.toString()).doesNotContain("privé").doesNotContain("not-a-uuid");
    Map<String, Object> tooLong = leave(open, t.plusDays(1), t.plusDays(367));
    tooLong.put("amount", 0);
    assertThat(
            fields(
                problem(
                    submit(p.bearer(), Organizations.newKey(), tooLong), 400, "VALIDATION_FAILED")))
        .containsExactlyInAnyOrder("endDate:RANGE", "amount:RANGE");
    Map<String, Object> types = new LinkedHashMap<>();
    types.put("policyId", 7);
    types.put("startDate", "2026-02-30");
    types.put("endDate", null);
    types.put("amount", "2");
    assertThat(
            fields(
                problem(
                    submit(p.bearer(), Organizations.newKey(), types), 400, "VALIDATION_FAILED")))
        .containsExactlyInAnyOrder(
            "policyId:FORMAT", "startDate:FORMAT", "endDate:REQUIRED", "amount:FORMAT");
    // 366 days in all is the most.
    Map<String, Object> year = leave(open, t.plusDays(1), t.plusDays(366));
    year.put("amount", 10000);
    expect(submit(p.bearer(), Organizations.newKey(), year), 201);
    assertThat(logs(output)).doesNotContain("privé").doesNotContain("not-a-uuid");
  }

  // ------------------------------------------------------------------------------------------
  // Isolation, cursors and paging (AC6)
  // ------------------------------------------------------------------------------------------

  @Test
  void employeesSeeOnlyTheirOwnRequestsAndCursorsNeverCrossCallers() throws Exception {
    World w = world();
    String open = policy(w, "OPEN", 0, "2026-01-01", null);
    Person a = person(w, HIRED);
    Person b = person(w, HIRED);
    LocalDate t = w.today();
    for (int i = 0; i < 3; i++) {
      expect(
          submit(
              a.bearer(),
              Organizations.newKey(),
              leave(open, t.plusDays(10L * i + 1), t.plusDays(10L * i + 2))),
          201);
    }
    expect(
        submit(b.bearer(), Organizations.newKey(), leave(open, t.plusDays(1), t.plusDays(2))), 201);

    JsonNode first = expect(get(REQUESTS + "?limit=2").header("Authorization", a.bearer()), 200);
    assertThat(first.get("items")).hasSize(2);
    String cursor = first.get("nextCursor").asText();
    JsonNode second =
        expect(
            get(REQUESTS + "?limit=2&cursor=" + cursor).header("Authorization", a.bearer()), 200);
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("nextCursor").isNull()).isTrue();
    List<String> seen = new ArrayList<>(texts(first.get("items"), "id"));
    seen.addAll(texts(second.get("items"), "id"));
    assertThat(seen).doesNotHaveDuplicates().hasSize(3);
    // Newest first.
    assertThat(texts(first.get("items"), "startDate").get(0)).isEqualTo(t.plusDays(21).toString());

    JsonNode own = expect(get(REQUESTS).header("Authorization", b.bearer()), 200);
    assertThat(own.get("items")).hasSize(1);
    assertThat(texts(own.get("items"), "id")).doesNotContainAnyElementsOf(seen);
    // A's cursor is bound to A, the operation and the page size.
    problem(
        get(REQUESTS + "?limit=2&cursor=" + cursor).header("Authorization", b.bearer()),
        400,
        "CURSOR_INVALID");
    problem(
        get(REQUESTS + "?cursor=" + cursor).header("Authorization", a.bearer()),
        400,
        "CURSOR_INVALID");
    problem(
        get(POLICIES + "?limit=2&cursor=" + cursor).header("Authorization", a.bearer()),
        400,
        "CURSOR_INVALID");
    problem(
        get(REQUESTS + "?limit=51").header("Authorization", a.bearer()), 400, "VALIDATION_FAILED");

    // Another tenant's employee sees nothing of this one.
    World other = world();
    Person c = person(other, HIRED);
    assertThat(expect(get(REQUESTS).header("Authorization", c.bearer()), 200).get("items"))
        .isEmpty();
    assertThat(expect(get(POLICIES).header("Authorization", c.bearer()), 200).get("items"))
        .isEmpty();
  }

  // ------------------------------------------------------------------------------------------
  // Authorization (D41A-6)
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyALinkedEmployeeIsServedAndNoOneElse() throws Exception {
    World w = world();
    String open = policy(w, "OPEN", 0, "2026-01-01", null);
    LocalDate t = w.today();
    // An employee membership without a link.
    String unlinked = UUID.randomUUID().toString();
    member(w, unlinked, "employee");
    String noLink = employeeBearer(w.org().tenant(), unlinked);
    for (MockHttpServletRequestBuilder request :
        List.of(
            get(POLICIES).header("Authorization", noLink),
            get(REQUESTS).header("Authorization", noLink),
            submit(noLink, Organizations.newKey(), leave(open, t.plusDays(1), t.plusDays(1))))) {
      problem(request, 403, "EMPLOYEE_LINK_REQUIRED");
    }
    // No membership, the tenant-admin role, no token.
    String stranger = employeeBearer(w.org().tenant(), UUID.randomUUID().toString());
    for (String bearer : List.of(stranger, w.admin())) {
      for (MockHttpServletRequestBuilder request :
          List.of(
              get(POLICIES).header("Authorization", bearer),
              get(REQUESTS).header("Authorization", bearer),
              submit(bearer, Organizations.newKey(), leave(open, t.plusDays(1), t.plusDays(1))))) {
        problem(request, 403, "ACCESS_DENIED");
      }
    }
    assertThat(call(get(REQUESTS)).getStatus()).isEqualTo(401);
    // A linked employee cannot use the administrator's catalogue.
    Person p = person(w, HIRED);
    problem(
        get("/api/v1/leave-policies").header("Authorization", p.bearer()), 403, "ACCESS_DENIED");
    assertThat(
            count(
                "SELECT count(*) FROM people.leave_request WHERE tenant_id = ?", w.org().tenant()))
        .isZero();
  }

  // ------------------------------------------------------------------------------------------
  // Fail-closed disclosure (D41A-4)
  // ------------------------------------------------------------------------------------------

  @Test
  void noHistoryIsReturnedWhenItsDisclosureAuditCannotBeWritten() throws Exception {
    World w = world();
    String open = policy(w, "OPEN", 0, "2026-01-01", null);
    Person p = person(w, HIRED);
    expect(
        submit(
            p.bearer(),
            Organizations.newKey(),
            leave(open, w.today().plusDays(1), w.today().plusDays(1))),
        201);
    String function = "no_leave_disclosure_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "CREATE FUNCTION public."
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action ="
            + " 'leave-request.self-list' AND NEW.tenant_id = '"
            + w.org().tenant()
            + "' THEN RAISE EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + function
            + " BEFORE INSERT ON platform.audit_event FOR EACH ROW EXECUTE FUNCTION public."
            + function
            + "()");
    try {
      MockHttpServletResponse refused = call(get(REQUESTS).header("Authorization", p.bearer()));
      assertThat(refused.getStatus()).isEqualTo(500);
      assertThat(refused.getContentAsString()).doesNotContain(open).doesNotContain("PENDING");
    } finally {
      jdbc.execute("DROP TRIGGER " + function + " ON platform.audit_event");
      jdbc.execute("DROP FUNCTION public." + function + "()");
    }
    assertThat(expect(get(REQUESTS).header("Authorization", p.bearer()), 200).get("items"))
        .hasSize(1);
  }
}
