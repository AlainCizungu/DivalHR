package com.divalhr.core.people.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-050A test fixtures over the public API: an organization with a tenant administrator, two
 * sites (the first with two departments), employees placed at the first site and linked to their
 * own employee memberships, placement changes, separations, and schedule proposals, previews and
 * publications.
 */
final class ScheduleWorld {

  static final ObjectMapper JSON = new ObjectMapper();
  static final String SCHEDULES = "/api/v1/work-schedules";
  static final String PREVIEW = SCHEDULES + "/preview";
  static final String MINE = "/api/v1/me/work-schedule";
  static final String EMPLOYEES = "/api/v1/employees";
  static final LocalDate HIRED = LocalDate.of(2026, 3, 1);

  final MockMvc mvc;
  final JdbcTemplate jdbc;
  final EmailLookup lookups;
  final Org org;
  final String adminSubject;
  final String admin;
  final LocalDate today;
  final UUID otherSite;
  final UUID otherSiteDepartment;
  final UUID secondDepartment;
  private int hires;
  private String hiringAdmin;

  /** An employee linked to their own membership. */
  record Person(UUID employee, String bearer, String subject, UUID membership, String number) {}

  ScheduleWorld(MockMvc mvc, JdbcTemplate jdbc, EmailLookup lookups) throws Exception {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.lookups = lookups;
    this.org = EmployeeImports.newOrg(mvc);
    this.adminSubject = "sub-sched50a-admin-" + UUID.randomUUID();
    this.admin = Hierarchy.bearer(org.tenant(), adminSubject, "tenant-admin");
    this.today = Employees.today(jdbc, org.tenant());
    this.otherSite =
        Hierarchy.newSite(
            mvc, org.tenant(), org.legalEntityId(), Hierarchy.code("SB"), "2026-01-01", null);
    this.otherSiteDepartment =
        Hierarchy.newSiteUnit(
            mvc,
            "/api/v1/departments",
            org.tenant(),
            otherSite,
            Hierarchy.code("DB"),
            "2026-01-01",
            null);
    this.secondDepartment =
        Hierarchy.newSiteUnit(
            mvc,
            "/api/v1/departments",
            org.tenant(),
            org.siteId(),
            Hierarchy.code("DC"),
            "2026-01-01",
            null);
  }

  UUID tenant() {
    return org.tenant();
  }

  UUID site() {
    return org.siteId();
  }

  // ------------------------------------------------------------------------------------------
  // People
  // ------------------------------------------------------------------------------------------

  UUID member(String subject) {
    UUID id = UUID.randomUUID();
    String address = "sched-" + UUID.randomUUID().toString().substring(0, 8) + "@exemple.cd";
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

  /** Hires an employee placed at the first site from {@code start}. */
  UUID hire(String given, String family, LocalDate start) throws Exception {
    return Employees.hire(mvc, jdbc, org, hiring(), Employees.number(), given, family, start);
  }

  /** Rotates the importing administrator every four hires (per-subject import limit). */
  private String hiring() {
    if (hires < 4) {
      hires++;
      return admin;
    }
    if (hires % 4 == 0) {
      hiringAdmin =
          Hierarchy.bearer(org.tenant(), "sub-sched-hr-" + UUID.randomUUID(), "tenant-admin");
    }
    hires++;
    return hiringAdmin;
  }

  Person person(String given, String family) throws Exception {
    return person(given, family, HIRED);
  }

  Person person(String given, String family, LocalDate start) throws Exception {
    UUID employee = hire(given, family, start);
    String subject = UUID.randomUUID().toString();
    UUID membership = member(subject);
    expect(
        Employees.postJson(
                admin,
                EMPLOYEES + "/" + employee + "/access-link",
                "{\"membershipId\":\"" + membership + "\"}")
            .header("Idempotency-Key", Organizations.newKey()),
        201);
    String number =
        jdbc.queryForObject(
            "SELECT employee_number FROM people.employee WHERE id = ?", String.class, employee);
    return new Person(employee, employeeBearer(org.tenant(), subject), subject, membership, number);
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

  /** A placement change command from {@code from} on (scheduled when in the future). */
  Map<String, Object> placement(UUID siteId, UUID departmentId, LocalDate from) {
    Map<String, Object> placement = new LinkedHashMap<>();
    placement.put("legalEntityId", org.legalEntityId().toString());
    placement.put("siteId", siteId.toString());
    placement.put("departmentId", departmentId.toString());
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    command.put("placement", placement);
    if (from.isBefore(today)) {
      command.put("reasonCode", "LATE_NOTIFICATION");
    }
    return command;
  }

  /** The commit of a previewed employment change, as a builder (for races). */
  MockHttpServletRequestBuilder change(UUID employee, Map<String, Object> command)
      throws Exception {
    JsonNode preview =
        expect(
            Employees.postJson(
                admin,
                EMPLOYEES + "/" + employee + "/employment-changes/preview",
                JSON.writeValueAsString(command)),
            200);
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgeRetroactive", preview.get("requiresAcknowledgement").asBoolean());
    return Employees.postJson(
            admin,
            EMPLOYEES + "/" + employee + "/employment-changes",
            JSON.writeValueAsString(body))
        .header("Idempotency-Key", Organizations.newKey());
  }

  /** MVP-051: a CHANGE of the report's MANAGER line from {@code from} on (or none). */
  Map<String, Object> managerCommand(UUID manager, LocalDate from) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("employeeId", manager == null ? null : manager.toString());
    command.put("manager", line);
    if (from.isBefore(today)) {
      command.put("reasonCode", "LATE_NOTIFICATION");
    }
    return command;
  }

  /** MVP-051: makes {@code manager} the report's manager from {@code from} on. */
  void manage(UUID report, UUID manager, LocalDate from) throws Exception {
    expect(change(report, managerCommand(manager, from)), 201);
  }

  /** MVP-051: makes {@code manager} the manager of every report from today on. */
  void manageAll(Person manager, Person... reports) throws Exception {
    for (Person report : reports) {
      manage(report.employee(), manager.employee(), today);
    }
  }

  /** The commit of a previewed separation with the acknowledgements it required (for races). */
  MockHttpServletRequestBuilder separate(UUID employee, LocalDate lastDay) throws Exception {
    return separate(employee, lastDay, "END_OF_LAST_DAY");
  }

  /** As {@link #separate(UUID, LocalDate)}, with the access timing (IMMEDIATELY for a past day). */
  MockHttpServletRequestBuilder separate(UUID employee, LocalDate lastDay, String accessTiming)
      throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("lastDay", lastDay.toString());
    command.put("reasonCode", "RESIGNATION");
    command.put("accessTiming", accessTiming);
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
  // Proposals
  // ------------------------------------------------------------------------------------------

  /** One slot: local start and end ({@code YYYY-MM-DDTHH:MM}), labels and an optional employee. */
  static Map<String, Object> slot(
      String ref, String start, String end, String en, String fr, UUID employee) {
    Map<String, Object> slot = new LinkedHashMap<>();
    slot.put("clientRef", ref);
    slot.put("start", start);
    slot.put("end", end);
    slot.put("labels", Map.of("en", en, "fr", fr));
    if (employee != null) {
      slot.put("employeeId", employee.toString());
    }
    return slot;
  }

  /** A day-shift slot on the date from {@code from} to {@code to} o'clock. */
  static Map<String, Object> shift(String ref, LocalDate day, int from, int to, UUID employee) {
    return slot(
        ref,
        day + "T" + two(from) + ":00",
        day + "T" + two(to) + ":00",
        "Cashier",
        "Caissière",
        employee);
  }

  private static String two(int hour) {
    return hour < 10 ? "0" + hour : Integer.toString(hour);
  }

  static Map<String, Object> proposal(
      UUID site, LocalDate first, LocalDate last, List<Map<String, Object>> slots) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("siteId", site.toString());
    body.put("firstDate", first.toString());
    body.put("lastDate", last.toString());
    body.put("slots", slots);
    return body;
  }

  static MockHttpServletRequestBuilder preview(String bearer, Object body) throws Exception {
    return Employees.postJson(bearer, PREVIEW, JSON.writeValueAsString(body));
  }

  /** The publication of a proposal with the digest and the confirmation. */
  static MockHttpServletRequestBuilder publish(
      String bearer, String key, Map<String, Object> proposal, String digest) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>(proposal);
    body.put("previewDigest", digest);
    body.put("confirm", true);
    MockHttpServletRequestBuilder request =
        Employees.postJson(bearer, SCHEDULES, JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  /** The cancellation of a schedule (MVP-050B). */
  static MockHttpServletRequestBuilder cancel(String bearer, String key, String id, String reason)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", reason);
    body.put("confirm", true);
    MockHttpServletRequestBuilder request =
        Employees.postJson(
            bearer, SCHEDULES + "/" + id + "/cancellation", JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  /** The replacement preview of a schedule (MVP-050B). */
  static MockHttpServletRequestBuilder replacementPreview(
      String bearer, String id, List<Map<String, Object>> slots, String reason) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slots", slots);
    body.put("reason", reason);
    return Employees.postJson(
        bearer, SCHEDULES + "/" + id + "/replacement-preview", JSON.writeValueAsString(body));
  }

  /** The replacement of a schedule by a previewed proposal (MVP-050B). */
  static MockHttpServletRequestBuilder replace(
      String bearer,
      String key,
      String id,
      List<Map<String, Object>> slots,
      String reason,
      String digest)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slots", slots);
    body.put("reason", reason);
    body.put("previewDigest", digest);
    body.put("confirm", true);
    MockHttpServletRequestBuilder request =
        Employees.postJson(
            bearer, SCHEDULES + "/" + id + "/replacement", JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  /** Previews a replacement (expecting it publishable) and returns its digest. */
  String replacementDigest(String id, List<Map<String, Object>> slots, String reason)
      throws Exception {
    JsonNode preview = expect(replacementPreview(admin, id, slots, reason), 200);
    assertThat(preview.get("publishable").asBoolean()).as("publishable").isTrue();
    return preview.get("previewDigest").asText();
  }

  /** Previews and replaces; returns the successor's id. */
  String replaced(String id, List<Map<String, Object>> slots, String reason) throws Exception {
    String digest = replacementDigest(id, slots, reason);
    return expect(replace(admin, Organizations.newKey(), id, slots, reason, digest), 201)
        .get("replacementScheduleId")
        .asText();
  }

  /** The derived state of a schedule (administrators' detail). */
  String status(String id) throws Exception {
    return expect(get(SCHEDULES + "/" + id).header("Authorization", admin), 200)
        .get("status")
        .asText();
  }

  /** Previews (expecting a publishable preview) and returns its digest. */
  String digest(Map<String, Object> proposal) throws Exception {
    JsonNode preview = expect(preview(admin, proposal), 200);
    assertThat(preview.get("publishable").asBoolean()).as("publishable").isTrue();
    return preview.get("previewDigest").asText();
  }

  /** Previews and publishes; returns the schedule id. */
  String published(Map<String, Object> proposal) throws Exception {
    String digest = digest(proposal);
    return expect(publish(admin, Organizations.newKey(), proposal, digest), 201)
        .get("scheduleId")
        .asText();
  }

  // ------------------------------------------------------------------------------------------
  // Calls and counts
  // ------------------------------------------------------------------------------------------

  MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse();
  }

  JsonNode expect(MockHttpServletRequestBuilder request, int status) throws Exception {
    MockHttpServletResponse response = call(request);
    String body = response.getContentAsString(StandardCharsets.UTF_8);
    assertThat(response.getStatus()).as(body).isEqualTo(status);
    return body.isEmpty() ? JSON.nullNode() : JSON.readTree(body);
  }

  JsonNode problem(MockHttpServletRequestBuilder request, int status, String code)
      throws Exception {
    JsonNode problem = expect(request, status);
    assertThat(problem.get("code").asText()).isEqualTo(code);
    return problem;
  }

  int count(String sql, Object... args) {
    Integer found = jdbc.queryForObject(sql, Integer.class, args);
    return found == null ? 0 : found;
  }

  /** Schedules and slots of this tenant. */
  List<Integer> rows() {
    return List.of(
        count("SELECT count(*) FROM people.work_schedule WHERE tenant_id = ?", tenant()),
        count("SELECT count(*) FROM people.work_schedule_slot WHERE tenant_id = ?", tenant()));
  }
}
