package com.divalhr.core.people;

import static com.divalhr.core.support.Employees.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.divalhr.core.identity.application.AccessRevocationJobs;
import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.people.application.SeparationJobs;
import com.divalhr.core.support.EmployeeImports;
import com.divalhr.core.support.EmployeeImports.Org;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.FakeIdentityDirectory;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MVP-022 (Issue #49) end to end against PostgreSQL: the access link, scheduled and immediate
 * separations, the membership gate, the revocation worker (A22-5), blockers (A22-3), direct-report
 * intervals (A22-2), protected principals, checklist tasks, retry, cancellation and privacy
 * (A22-6). Markers (names, the address, the last day, the reason) must never reach audit metadata,
 * outbox data or logs.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SeparationIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String BASE = "/api/v1/employees";
  private static final String GIVEN = "Élodie Séparée";
  private static final String FAMILY = "N’Kanza-Départ";

  /** Audit metadata keys any MVP-022 record may carry (A22-6). */
  private static final Set<String> METADATA_KEYS =
      Set.of(
          "schemaVersion",
          "state",
          "reportCount",
          "intervalCount",
          "rowsSuperseded",
          "rowsCreated",
          "employmentVersion",
          "fromState",
          "toState",
          "fromStatus",
          "toStatus",
          "version",
          "linkVersion",
          "attempt",
          "outcomeCode",
          "resultCount",
          "view",
          "page");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private FakeIdentityDirectory provider;
  @Autowired private AccessRevocationJobs revocations;
  @Autowired private SeparationJobs separationJobs;

  @BeforeEach
  void reset() {
    provider.reset();
  }

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

  private record World(Org org, String admin, String adminSubject, LocalDate today) {}

  private World world() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String subject = "sub-sep-admin-" + UUID.randomUUID();
    return new World(
        org,
        Hierarchy.bearer(org.tenant(), subject, "tenant-admin"),
        subject,
        Employees.today(jdbc, org.tenant()));
  }

  private UUID hire(World w, String given, String family) throws Exception {
    return Employees.hire(
        mvc, jdbc, w.org(), w.admin(), Employees.number(), given, family, w.today().minusDays(100));
  }

  /** A member of the tenant with a known address (and a subject) of the given role. */
  private record Member(UUID id, String subject, String address) {}

  private Member member(World w, String role) {
    UUID id = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    String address = "sep-" + UUID.randomUUID().toString().substring(0, 8) + "@exemple.cd";
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, ?, ?, now())",
        id,
        w.org().tenant(),
        subject,
        role,
        lookups.of(EmailAddress.parse(address).orElseThrow()));
    return new Member(id, subject, address);
  }

  private String bearerOf(World w, Member member, String role) {
    return "Bearer "
        + com.divalhr.core.support.TestTokens.token()
            .tenant(w.org().tenant())
            .subject(member.subject())
            .roles(List.of(role))
            .build();
  }

  private MvcResult call(org.springframework.test.web.servlet.RequestBuilder request)
      throws Exception {
    return mvc.perform(request).andReturn();
  }

  private static JsonNode json(MvcResult result) throws Exception {
    return Employees.json(result.getResponse().getContentAsByteArray());
  }

  private static JsonNode expect(MvcResult result, int status) throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(status);
    return result.getResponse().getContentAsByteArray().length == 0 ? null : json(result);
  }

  private static void problem(MvcResult result, int status, String code) throws Exception {
    JsonNode body = expect(result, status);
    assertThat(body.get("code").asText()).isEqualTo(code);
  }

  private MvcResult link(World w, UUID employee, Member member) throws Exception {
    return call(
        postJson(
                w.admin(),
                BASE + "/" + employee + "/access-link",
                "{\"membershipId\":\"" + member.id() + "\"}")
            .header("Idempotency-Key", Organizations.newKey()));
  }

  private static Map<String, Object> command(
      LocalDate lastDay, String timing, Map<String, Object> plan) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("lastDay", lastDay.toString());
    command.put("reasonCode", "RESIGNATION");
    command.put("accessTiming", timing);
    if (plan != null) {
      command.put("reportPlan", plan);
    }
    return command;
  }

  private MvcResult preview(World w, UUID employee, Map<String, Object> command) throws Exception {
    return call(
        postJson(
            w.admin(),
            BASE + "/" + employee + "/separations/preview",
            JSON.writeValueAsString(command)));
  }

  private MvcResult commit(
      World w, UUID employee, Map<String, Object> command, JsonNode preview, List<String> acks)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgements", acks);
    return call(
        postJson(w.admin(), BASE + "/" + employee + "/separations", JSON.writeValueAsString(body))
            .header("Idempotency-Key", Organizations.newKey()));
  }

  /** Previews and records with exactly the acknowledgements the preview required. */
  private JsonNode separate(World w, UUID employee, Map<String, Object> command) throws Exception {
    JsonNode preview = expect(preview(w, employee, command), 200);
    List<String> acks = new ArrayList<>();
    preview.get("requiredAcknowledgements").forEach(a -> acks.add(a.asText()));
    return expect(commit(w, employee, command, preview, acks), 201).get("separation");
  }

  private JsonNode cancel(World w, UUID employee, String separationId, int status)
      throws Exception {
    MvcResult previewed =
        call(
            postJson(
                w.admin(),
                BASE + "/" + employee + "/separations/" + separationId + "/cancel/preview",
                ""));
    if (status != 200) {
      return expect(previewed, status);
    }
    JsonNode preview = expect(previewed, 200);
    return expect(
            call(
                postJson(
                        w.admin(),
                        BASE + "/" + employee + "/separations/" + separationId + "/cancel",
                        "{\"expectedVersion\":"
                            + preview.get("expectedVersion").asLong()
                            + ",\"cancellationDigest\":\""
                            + preview.get("cancellationDigest").asText()
                            + "\"}")
                    .header("Idempotency-Key", Organizations.newKey())),
            200)
        .get("separation");
  }

  private int probe(World w, String bearer) throws Exception {
    return call(post("/test-support/probes")
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"tenantId\":\"" + w.org().tenant() + "\"}"))
        .getResponse()
        .getStatus();
  }

  private String revocationState(UUID separationId) {
    return jdbc.queryForObject(
        "SELECT state FROM identity.access_revocation WHERE separation_id = ?"
            + " ORDER BY requested_at DESC LIMIT 1",
        String.class,
        separationId);
  }

  /** Active rows of one kind of an employee: from;to;value. */
  private List<String> rows(UUID employee, String kind) {
    return jdbc.queryForList(
        "SELECT effective_from || ';' || coalesce(effective_to::text, '-') || ';'"
            + " || coalesce(manager_employee_id::text, contract_code, site_id::text)"
            + " FROM people.employment_assignment WHERE employee_id = ? AND kind = ?"
            + " AND superseded_by_change_id IS NULL ORDER BY effective_from",
        String.class,
        employee,
        kind);
  }

  private JsonNode change(World w, UUID employee, Map<String, Object> command) throws Exception {
    JsonNode preview =
        expect(
            call(
                postJson(
                    w.admin(),
                    BASE + "/" + employee + "/employment-changes/preview",
                    JSON.writeValueAsString(command))),
            200);
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgeRetroactive", preview.get("requiresAcknowledgement").asBoolean());
    return expect(
            call(
                postJson(
                        w.admin(),
                        BASE + "/" + employee + "/employment-changes",
                        JSON.writeValueAsString(body))
                    .header("Idempotency-Key", Organizations.newKey())),
            201)
        .get("change");
  }

  private Map<String, Object> managerChange(LocalDate from, UUID manager) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    command.put("manager", Map.of("employeeId", manager.toString()));
    if (from.isBefore(LocalDate.now(ZoneId.of("Africa/Kinshasa")))) {
      command.put("reasonCode", "LATE_NOTIFICATION");
    }
    return command;
  }

  private static Map<String, Object> contractChange(LocalDate from, String code) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    command.put("contractClassification", code);
    return command;
  }

  // ------------------------------------------------------------------------------------------
  // The access link
  // ------------------------------------------------------------------------------------------

  @Test
  void anAdministratorLinksAnEmployeeOnlyThroughAnExactAddressOfTheTenant() throws Exception {
    World w = world();
    World other = world();
    UUID employee = hire(w, GIVEN, FAMILY);
    UUID second = hire(w, "Jean", "Mukendi");
    Member member = member(w, "employee");
    Member foreign = member(other, "employee");
    String lookup = BASE + "/" + employee + "/access-link/lookup";

    // Unknown and foreign addresses look alike; the address is never echoed.
    problem(
        call(postJson(w.admin(), lookup, "{\"email\":\"nobody@exemple.cd\"}")),
        404,
        "MEMBERSHIP_NOT_FOUND");
    problem(
        call(postJson(w.admin(), lookup, "{\"email\":\"" + foreign.address() + "\"}")),
        404,
        "MEMBERSHIP_NOT_FOUND");
    MvcResult found =
        call(postJson(w.admin(), lookup, "{\"email\":\"" + member.address().toUpperCase() + "\"}"));
    JsonNode candidate = expect(found, 200);
    assertThat(found.getResponse().getContentAsString()).doesNotContain(member.address());
    assertThat(found.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(candidate.get("membershipId").asText()).isEqualTo(member.id().toString());
    assertThat(candidate.get("linkable").asBoolean()).isTrue();

    // A foreign membership cannot be linked by ID either.
    problem(link(w, employee, foreign), 404, "MEMBERSHIP_NOT_FOUND");
    JsonNode linked = expect(link(w, employee, member), 201);
    assertThat(linked.get("state").asText()).isEqualTo("ACTIVE");
    assertThat(linked.get("link").get("role").asText()).isEqualTo("employee");
    String linkId = linked.get("link").get("id").asText();
    // One link per employee and per membership.
    JsonNode again = expect(link(w, employee, member(w, "employee")), 409);
    assertThat(again.get("params").get("reason").asText()).isEqualTo("EMPLOYEE_LINKED");
    JsonNode taken = expect(link(w, second, member), 409);
    assertThat(taken.get("params").get("reason").asText()).isEqualTo("ALREADY_LINKED");

    JsonNode read =
        expect(
            call(get(BASE + "/" + employee + "/access-link").header("Authorization", w.admin())),
            200);
    assertThat(read.get("link").get("membershipId").asText()).isEqualTo(member.id().toString());
    assertThat(read.toString()).doesNotContain(member.subject()).doesNotContain(member.address());

    // Removal at the read version; a stale version is refused.
    problem(
        call(
            postJson(
                    w.admin(),
                    BASE + "/" + employee + "/access-link/remove",
                    "{\"linkId\":\"" + linkId + "\",\"expectedVersion\":7}")
                .header("Idempotency-Key", Organizations.newKey())),
        409,
        "ACCESS_LINK_VERSION_CONFLICT");
    JsonNode removed =
        expect(
            call(
                postJson(
                        w.admin(),
                        BASE + "/" + employee + "/access-link/remove",
                        "{\"linkId\":\"" + linkId + "\",\"expectedVersion\":0}")
                    .header("Idempotency-Key", Organizations.newKey())),
            200);
    assertThat(removed.get("state").asText()).isEqualTo("NOT_LINKED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.employee_access_link WHERE id = ?::uuid",
                Integer.class,
                linkId))
        .as("the row is kept")
        .isEqualTo(1);
    assertPrivate(w);
  }

  // ------------------------------------------------------------------------------------------
  // Scheduled separation and cancellation
  // ------------------------------------------------------------------------------------------

  @Test
  void aScheduledSeparationClosesTheTimelineKeepsAccessUntilTheEndAndCanBeCancelled()
      throws Exception {
    World w = world();
    UUID employee = hire(w, GIVEN, FAMILY);
    Member member = member(w, "employee");
    expect(link(w, employee, member), 201);
    String employeeBearer = bearerOf(w, member, "employee");
    LocalDate lastDay = w.today().plusDays(10);

    Map<String, Object> command = command(lastDay, "END_OF_LAST_DAY", null);
    JsonNode preview = expect(preview(w, employee, command), 200);
    assertThat(preview.get("timing").asText()).isEqualTo("SCHEDULED");
    assertThat(preview.get("access").get("status").asText()).isEqualTo("LINKED");
    assertThat(preview.get("access").get("accessEndsAt").asText())
        .isEqualTo(
            lastDay.plusDays(1).atStartOfDay(ZoneId.of("Africa/Kinshasa")).toInstant().toString());
    assertThat(preview.get("requiredAcknowledgements")).isEmpty();
    assertThat(preview.get("blockers")).isEmpty();
    assertThat(preview.get("checklist")).hasSize(2);

    JsonNode separation =
        expect(commit(w, employee, command, preview, List.of()), 201).get("separation");
    String sid = separation.get("id").asText();
    assertThat(separation.get("state").asText()).isEqualTo("SCHEDULED");
    assertThat(separation.get("access").asText()).isEqualTo("SCHEDULED");
    assertThat(separation.get("cancellable").asBoolean()).isTrue();
    assertThat(separation.get("tasks")).hasSize(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT effective_to FROM people.employment WHERE employee_id = ?",
                LocalDate.class,
                employee))
        .isEqualTo(lastDay);
    assertThat(rows(employee, "PLACEMENT")).allMatch(r -> r.contains(";" + lastDay + ";"));
    // Access is untouched until the instant; the link cannot be removed meanwhile.
    assertThat(probe(w, employeeBearer)).isEqualTo(200);
    assertThat(revocationState(UUID.fromString(sid))).isEqualTo("SCHEDULED");
    String linkId =
        jdbc.queryForObject(
            "SELECT id::text FROM identity.employee_access_link WHERE employee_id = ?"
                + " AND unlinked_at IS NULL",
            String.class,
            employee);
    problem(
        call(
            postJson(
                    w.admin(),
                    BASE + "/" + employee + "/access-link/remove",
                    "{\"linkId\":\"" + linkId + "\",\"expectedVersion\":0}")
                .header("Idempotency-Key", Organizations.newKey())),
        409,
        "ACCESS_LINK_LOCKED");
    // A second separation is refused; a change after the last day is outside the employment.
    problem(
        preview(w, employee, command(lastDay, "END_OF_LAST_DAY", null)), 409, "SEPARATION_EXISTS");
    problem(
        call(
            postJson(
                w.admin(),
                BASE + "/" + employee + "/employment-changes/preview",
                JSON.writeValueAsString(contractChange(lastDay.plusDays(3), "DAILY")))),
        422,
        "EMPLOYMENT_DATE_OUTSIDE_EMPLOYMENT");

    JsonNode cancelled = cancel(w, employee, sid, 200);
    assertThat(cancelled.get("state").asText()).isEqualTo("CANCELLED");
    assertThat(cancelled.get("access").asText()).isEqualTo("CANCELLED");
    assertThat(cancelled.get("tasks")).allMatch(t -> t.get("status").asText().equals("CANCELLED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT effective_to FROM people.employment WHERE employee_id = ?",
                LocalDate.class,
                employee))
        .isNull();
    assertThat(rows(employee, "PLACEMENT")).hasSize(1).allMatch(r -> r.contains(";-;"));
    assertThat(probe(w, employeeBearer)).isEqualTo(200);
    // A new separation may follow a cancelled one.
    separate(w, employee, command(lastDay.plusDays(2), "END_OF_LAST_DAY", null));
    assertPrivate(w);
  }

  // ------------------------------------------------------------------------------------------
  // Immediate separation, the membership gate and the worker
  // ------------------------------------------------------------------------------------------

  @Test
  void immediateRemovalDeniesAccessAtOnceWhateverTheProviderDoes() throws Exception {
    World w = world();
    UUID employee = hire(w, GIVEN, FAMILY);
    Member member = member(w, "employee");
    expect(link(w, employee, member), 201);
    String employeeBearer = bearerOf(w, member, "employee");
    assertThat(probe(w, employeeBearer)).isEqualTo(200);

    Map<String, Object> command = command(w.today(), "IMMEDIATELY", null);
    JsonNode preview = expect(preview(w, employee, command), 200);
    assertThat(preview.get("requiredAcknowledgements").toString())
        .contains("IMMEDIATE_ACCESS_REMOVAL");
    // A22-1: the acknowledgement is required.
    JsonNode missing = expect(commit(w, employee, command, preview, List.of()), 422);
    assertThat(missing.get("code").asText()).isEqualTo("SEPARATION_ACKNOWLEDGEMENT_REQUIRED");
    assertThat(missing.get("params").get("acknowledgement").asText())
        .isEqualTo("IMMEDIATE_ACCESS_REMOVAL");

    provider.mode(FakeIdentityDirectory.Mode.DOWN);
    JsonNode separation =
        expect(commit(w, employee, command, preview, List.of("IMMEDIATE_ACCESS_REMOVAL")), 201)
            .get("separation");
    UUID sid = UUID.fromString(separation.get("id").asText());
    assertThat(separation.get("access").asText()).isEqualTo("SIGN_OUT_PENDING");
    // DivalHR access ends with the commit, while the provider is down.
    assertThat(probe(w, employeeBearer)).isEqualTo(403);
    JsonNode session =
        expect(call(get("/api/v1/session").header("Authorization", employeeBearer)), 200);
    assertThat(session.get("roles")).as("no effective tenant role").isEmpty();

    revocations.revokePending();
    assertThat(revocationState(sid)).isEqualTo("IDP_PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM identity.access_revocation WHERE separation_id = ?",
                Integer.class,
                sid))
        .isEqualTo(1);
    assertThat(probe(w, employeeBearer)).isEqualTo(403);
    // Not cancellable once access ended.
    JsonNode refused = cancel(w, employee, sid.toString(), 409);
    assertThat(refused.get("params").get("reason").asText()).isEqualTo("ACCESS_ALREADY_REVOKED");

    // The next attempt is due; the provider is back.
    provider.mode(FakeIdentityDirectory.Mode.UP);
    jdbc.execute("SET session_replication_role = replica");
    try {
      jdbc.update(
          "UPDATE identity.access_revocation SET next_attempt_at = now() WHERE separation_id = ?",
          sid);
    } finally {
      jdbc.execute("SET session_replication_role = DEFAULT");
    }
    revocations.revokePending();
    assertThat(revocationState(sid)).isEqualTo("COMPLETED");
    assertThat(
            provider.revocations().stream()
                .filter(r -> r.tenant().equals(w.org().tenant().toString()))
                .toList())
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.subject()).isEqualTo(member.subject());
              assertThat(call.tenant()).isEqualTo(w.org().tenant().toString());
            });
    JsonNode listed =
        expect(
            call(get(BASE + "/" + employee + "/separations").header("Authorization", w.admin())),
            200);
    assertThat(listed.get("items").get(0).get("access").asText()).isEqualTo("COMPLETED");
    // The access review labels the membership REVOKED and no longer counts it.
    JsonNode review =
        expect(
            call(
                postJson(
                    w.admin(),
                    "/api/v1/access-review/lookup",
                    "{\"email\":\"" + member.address() + "\"}")),
            200);
    assertThat(review.get("data").get(0).get("accessState").asText()).isEqualTo("REVOKED");
    // A revoked membership is never linked again.
    JsonNode relink = expect(link(w, hire(w, "Autre", "Personne"), member), 409);
    assertThat(relink.get("params").get("reason").asText()).isEqualTo("ACCESS_REVOKED");
    assertPrivate(w);
  }

  @Test
  void accessTimingFollowsTheLastDay() throws Exception {
    World w = world();
    UUID employee = hire(w, GIVEN, FAMILY);
    // A22-1: no immediate removal while HR history still shows the employee employed.
    problem(
        preview(w, employee, command(w.today().plusDays(5), "IMMEDIATELY", null)),
        422,
        "SEPARATION_ACCESS_TIMING_INVALID");
    // A retroactive separation removes access explicitly.
    problem(
        preview(w, employee, command(w.today().minusDays(5), "END_OF_LAST_DAY", null)),
        422,
        "SEPARATION_ACCESS_TIMING_INVALID");
    problem(
        preview(w, employee, command(w.today().plusDays(181), "END_OF_LAST_DAY", null)),
        422,
        "SEPARATION_DATE_OUT_OF_RANGE");
    problem(
        preview(w, employee, command(w.today().minusDays(61), "IMMEDIATELY", null)),
        422,
        "SEPARATION_DATE_OUT_OF_RANGE");
    JsonNode retro =
        expect(preview(w, employee, command(w.today().minusDays(5), "IMMEDIATELY", null)), 200);
    assertThat(retro.get("timing").asText()).isEqualTo("RETROACTIVE");
    assertThat(retro.get("access").get("status").asText()).isEqualTo("NOT_LINKED");
    List<String> acks = new ArrayList<>();
    retro.get("requiredAcknowledgements").forEach(a -> acks.add(a.asText()));
    assertThat(acks).containsExactlyInAnyOrder("RETROACTIVE", "NO_LINKED_ACCESS");
    JsonNode separation =
        expect(
                commit(
                    w, employee, command(w.today().minusDays(5), "IMMEDIATELY", null), retro, acks),
                201)
            .get("separation");
    assertThat(separation.get("state").asText()).isEqualTo("EFFECTIVE");
    assertThat(separation.get("access").asText()).isEqualTo("NOT_LINKED");
  }

  // ------------------------------------------------------------------------------------------
  // Blockers (A22-3)
  // ------------------------------------------------------------------------------------------

  @Test
  void futureEffectsAfterTheLastDayBlockUntilCancelledWhateverWroteThem() throws Exception {
    World w = world();
    UUID employee = hire(w, GIVEN, FAMILY);
    LocalDate lastDay = w.today().plusDays(10);
    JsonNode scheduled = change(w, employee, contractChange(w.today().plusDays(20), "FIXED_TERM"));

    Map<String, Object> command = command(lastDay, "END_OF_LAST_DAY", null);
    JsonNode preview = expect(preview(w, employee, command), 200);
    assertThat(preview.get("blockers")).hasSize(1);
    JsonNode blocker = preview.get("blockers").get(0);
    assertThat(blocker.get("kind").asText()).isEqualTo("CONTRACT");
    assertThat(blocker.get("resolution").asText()).isEqualTo("CANCEL_CHANGE");
    assertThat(blocker.get("changeId").asText()).isEqualTo(scheduled.get("id").asText());
    JsonNode refused =
        expect(commit(w, employee, command, preview, List.of("NO_LINKED_ACCESS")), 409);
    assertThat(refused.get("code").asText()).isEqualTo("SEPARATION_FUTURE_CHANGES");
    assertThat(refused.get("params").get("count").asInt()).isEqualTo(1);

    // A correction of that future row is detected from the active timeline (it is not a CHANGE).
    String row =
        jdbc.queryForObject(
            "SELECT id::text FROM people.employment_assignment WHERE employee_id = ?"
                + " AND kind = 'CONTRACT' AND superseded_by_change_id IS NULL",
            String.class,
            employee);
    Map<String, Object> correction = new LinkedHashMap<>();
    correction.put("type", "CORRECTION");
    correction.put("effectiveFrom", w.today().plusDays(20).toString());
    correction.put("contractClassification", "PERMANENT");
    correction.put("reasonCode", "DATA_ENTRY_ERROR");
    correction.put("correctsAssignmentId", row);
    change(w, employee, correction);
    JsonNode corrected = expect(preview(w, employee, command), 200);
    assertThat(corrected.get("blockers")).hasSize(1);
    assertThat(corrected.get("blockers").get(0).get("resolution").asText())
        .isEqualTo("NOT_CANCELLABLE");
  }

  @Test
  void cancellingTheBlockerLetsTheSeparationProceed() throws Exception {
    World w = world();
    UUID employee = hire(w, GIVEN, FAMILY);
    LocalDate lastDay = w.today().plusDays(10);
    JsonNode scheduled = change(w, employee, contractChange(w.today().plusDays(20), "DAILY"));
    // A scheduled change before the last day survives, truncated at it.
    change(w, employee, contractChange(w.today().plusDays(5), "APPRENTICESHIP"));
    JsonNode cancelPreview =
        expect(
            call(
                postJson(
                    w.admin(),
                    BASE
                        + "/"
                        + employee
                        + "/employment-changes/"
                        + scheduled.get("id").asText()
                        + "/cancel/preview",
                    "")),
            200);
    expect(
        call(
            postJson(
                    w.admin(),
                    BASE
                        + "/"
                        + employee
                        + "/employment-changes/"
                        + scheduled.get("id").asText()
                        + "/cancel",
                    "{\"expectedVersion\":"
                        + cancelPreview.get("expectedVersion").asLong()
                        + ",\"cancellationDigest\":\""
                        + cancelPreview.get("cancellationDigest").asText()
                        + "\"}")
                .header("Idempotency-Key", Organizations.newKey())),
        200);
    separate(w, employee, command(lastDay, "END_OF_LAST_DAY", null));
    assertThat(rows(employee, "CONTRACT"))
        .containsExactly(w.today().plusDays(5) + ";" + lastDay + ";APPRENTICESHIP");
  }

  // ------------------------------------------------------------------------------------------
  // Direct reports (A22-2)
  // ------------------------------------------------------------------------------------------

  @Test
  void everyAffectedReportIntervalIsRewrittenAndRestoredExactly() throws Exception {
    World w = world();
    UUID manager = hire(w, GIVEN, FAMILY);
    UUID other = hire(w, "Yves", "Kabeya");
    UUID replacement = hire(w, "Zoé", "Ilunga");
    UUID report = hire(w, "Rachel", "Mwamba");
    LocalDate t = w.today();
    LocalDate lastDay = t.plusDays(10);
    // X from t-50, Y from t+20, X again from t+40: two disjoint intervals after D.
    change(w, report, managerChange(t.minusDays(50), manager));
    change(w, report, managerChange(t.plusDays(20), other));
    change(w, report, managerChange(t.plusDays(40), manager));
    List<String> before = rows(report, "MANAGER");

    Map<String, Object> noPlan = command(lastDay, "END_OF_LAST_DAY", null);
    JsonNode preview = expect(preview(w, manager, noPlan), 200);
    assertThat(preview.get("reportPlanRequired").asBoolean()).isTrue();
    assertThat(preview.get("reports")).hasSize(1);
    JsonNode intervals = preview.get("reports").get(0).get("intervals");
    assertThat(intervals).hasSize(2);
    assertThat(intervals.get(0).get("effectiveFrom").asText())
        .isEqualTo(lastDay.plusDays(1).toString());
    assertThat(intervals.get(0).get("effectiveTo").asText()).isEqualTo(t.plusDays(19).toString());
    assertThat(intervals.get(1).get("effectiveFrom").asText()).isEqualTo(t.plusDays(40).toString());
    problem(
        commit(w, manager, noPlan, preview, List.of("NO_LINKED_ACCESS")),
        422,
        "SEPARATION_REPORT_PLAN_REQUIRED");
    // The separated manager cannot replace themself.
    problem(
        preview(
            w,
            manager,
            command(
                lastDay,
                "END_OF_LAST_DAY",
                Map.of("action", "REASSIGN", "managerEmployeeId", manager.toString()))),
        422,
        "MANAGER_INVALID");

    Map<String, Object> plan =
        Map.of("action", "REASSIGN", "managerEmployeeId", replacement.toString());
    JsonNode separation = separate(w, manager, command(lastDay, "END_OF_LAST_DAY", plan));
    assertThat(separation.get("reportCount").asInt()).isEqualTo(1);
    assertThat(separation.get("intervalCount").asInt()).isEqualTo(2);
    assertThat(rows(report, "MANAGER"))
        .containsExactly(
            t.minusDays(50) + ";" + lastDay + ";" + manager,
            lastDay.plusDays(1) + ";" + t.plusDays(19) + ";" + replacement,
            t.plusDays(20) + ";" + t.plusDays(39) + ";" + other,
            t.plusDays(40) + ";-;" + replacement);
    // Every generated change is bound to the separation and cannot be cancelled on its own.
    List<String> bound =
        jdbc.queryForList(
            "SELECT id::text FROM people.employment_change WHERE employee_id = ?"
                + " AND reason_code = 'MANAGER_SEPARATED' AND separation_id = ?::uuid",
            String.class,
            report,
            separation.get("id").asText());
    assertThat(bound).hasSize(2);
    problem(
        call(
            postJson(
                w.admin(),
                BASE + "/" + report + "/employment-changes/" + bound.get(0) + "/cancel/preview",
                "")),
        409,
        "EMPLOYMENT_CHANGE_NOT_CANCELLABLE");

    // Cancellation reverses all of them at once.
    cancel(w, manager, separation.get("id").asText(), 200);
    assertThat(rows(report, "MANAGER")).containsExactlyElementsOf(before);
  }

  @Test
  void aClearPlanLeavesTheIntervalsWithoutAManager() throws Exception {
    World w = world();
    UUID manager = hire(w, GIVEN, FAMILY);
    UUID report = hire(w, "Rachel", "Mwamba");
    LocalDate t = w.today();
    change(w, report, managerChange(t.minusDays(30), manager));
    separate(w, manager, command(t.plusDays(3), "END_OF_LAST_DAY", Map.of("action", "CLEAR")));
    assertThat(rows(report, "MANAGER"))
        .containsExactly(t.minusDays(30) + ";" + t.plusDays(3) + ";" + manager);
  }

  // ------------------------------------------------------------------------------------------
  // Protected principals (D22-6)
  // ------------------------------------------------------------------------------------------

  @Test
  void aTenantAdministratorAndTheCallerThemselvesAreNeverSeparatedHere() throws Exception {
    World w = world();
    UUID employee = hire(w, GIVEN, FAMILY);
    Member admin = member(w, "tenant-admin");
    expect(link(w, employee, admin), 201);
    Map<String, Object> command = command(w.today().plusDays(5), "END_OF_LAST_DAY", null);
    JsonNode preview = expect(preview(w, employee, command), 200);
    assertThat(preview.get("access").get("status").asText()).isEqualTo("PROTECTED_ADMIN");
    JsonNode refused = expect(commit(w, employee, command, preview, List.of()), 409);
    assertThat(refused.get("params").get("reason").asText()).isEqualTo("ADMIN_ACCESS");

    // The caller's own access.
    UUID self = hire(w, "Moi", "Même");
    UUID ownMembership =
        jdbc.queryForObject(
            "SELECT id FROM identity.tenant_membership WHERE subject = ?",
            UUID.class,
            w.adminSubject());
    expect(link(w, self, new Member(ownMembership, w.adminSubject(), "")), 201);
    JsonNode own = expect(preview(w, self, command), 200);
    assertThat(own.get("access").get("status").asText()).isEqualTo("SELF");
    JsonNode selfRefused = expect(commit(w, self, command, own, List.of()), 409);
    assertThat(selfRefused.get("params").get("reason").asText()).isEqualTo("SELF");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.access_revocation r"
                    + " JOIN identity.tenant_membership m ON m.id = r.membership_id"
                    + " WHERE m.tenant_id = ?",
                Integer.class,
                w.org().tenant()))
        .isZero();
  }

  // ------------------------------------------------------------------------------------------
  // A22-5: a stale binding never reaches the identity provider
  // ------------------------------------------------------------------------------------------

  @Test
  void theWorkerNeverDisablesAnIdentityWhoseBindingChanged() throws Exception {
    for (String scenario : List.of("RELINKED", "ROLE", "TENANT")) {
      provider.reset();
      World w = world();
      UUID employee = hire(w, GIVEN, FAMILY);
      Member member = member(w, "employee");
      expect(link(w, employee, member), 201);
      provider.mode(FakeIdentityDirectory.Mode.DOWN);
      JsonNode separation = separate(w, employee, command(w.today(), "IMMEDIATELY", null));
      UUID sid = UUID.fromString(separation.get("id").asText());
      provider.mode(FakeIdentityDirectory.Mode.UP);
      // Bypassing every guard, as a damaged or manipulated database could.
      jdbc.execute("SET session_replication_role = replica");
      try {
        switch (scenario) {
          case "RELINKED" -> {
            jdbc.update(
                "UPDATE identity.employee_access_link SET unlinked_at = now(),"
                    + " unlinked_by = 'test' WHERE employee_id = ?",
                employee);
            jdbc.update(
                "INSERT INTO identity.employee_access_link (id, tenant_id, employee_id,"
                    + " membership_id, linked_at, linked_by) VALUES (?, ?, ?, ?, now(), 'test')",
                UUID.randomUUID(),
                w.org().tenant(),
                hire(w, "Suivant", "Lien"),
                member.id());
          }
          case "ROLE" ->
              jdbc.update(
                  "UPDATE identity.tenant_membership SET role = 'tenant-admin' WHERE id = ?",
                  member.id());
          default ->
              jdbc.update(
                  "UPDATE identity.tenant_membership SET tenant_id = ? WHERE id = ?",
                  world().org().tenant(),
                  member.id());
        }
      } finally {
        jdbc.execute("SET session_replication_role = DEFAULT");
      }
      revocations.revokePending();
      assertThat(provider.revocations())
          .as(scenario)
          .noneMatch(r -> r.subject().equals(member.subject()));
      assertThat(revocationState(sid)).as(scenario).isEqualTo("MANUAL_INTERVENTION");
      assertThat(
              jdbc.queryForObject(
                  "SELECT outcome_code FROM identity.access_revocation WHERE separation_id = ?",
                  String.class,
                  sid))
          .isEqualTo(
              switch (scenario) {
                case "RELINKED" -> "STALE_LINK";
                case "ROLE" -> "MEMBERSHIP_CHANGED";
                default -> "TENANT_MISMATCH";
              });
      // A retry re-queues it; the binding still fails and the provider is still never called.
      expect(
          call(
              post(BASE + "/" + employee + "/separations/" + sid + "/access-revocation/retry")
                  .header("Authorization", w.admin())
                  .header("Idempotency-Key", Organizations.newKey())),
          200);
      revocations.revokePending();
      assertThat(provider.revocations())
          .as(scenario)
          .noneMatch(r -> r.subject().equals(member.subject()));
      assertThat(revocationState(sid)).as(scenario).isEqualTo("MANUAL_INTERVENTION");
    }
  }

  // ------------------------------------------------------------------------------------------
  // Provider refusal, retry and exhaustion
  // ------------------------------------------------------------------------------------------

  @Test
  void aRefusalNeedsInterventionAndARetryStartsAFreshBudget() throws Exception {
    World w = world();
    UUID employee = hire(w, GIVEN, FAMILY);
    Member member = member(w, "employee");
    expect(link(w, employee, member), 201);
    provider.mode(FakeIdentityDirectory.Mode.REVOCATION_REFUSED);
    JsonNode separation = separate(w, employee, command(w.today(), "IMMEDIATELY", null));
    UUID sid = UUID.fromString(separation.get("id").asText());
    String retry = BASE + "/" + employee + "/separations/" + sid + "/access-revocation/retry";
    problem(
        call(
            post(retry)
                .header("Authorization", w.admin())
                .header("Idempotency-Key", Organizations.newKey())),
        409,
        "ACCESS_REVOCATION_NOT_RETRYABLE");
    revocations.revokePending();
    assertThat(revocationState(sid)).isEqualTo("MANUAL_INTERVENTION");
    provider.mode(FakeIdentityDirectory.Mode.REVOCATION_ABSENT);
    JsonNode requeued =
        expect(
            call(
                post(retry)
                    .header("Authorization", w.admin())
                    .header("Idempotency-Key", Organizations.newKey())),
            200);
    assertThat(requeued.get("access").asText()).isEqualTo("SIGN_OUT_PENDING");
    revocations.revokePending();
    assertThat(revocationState(sid)).isEqualTo("COMPLETED");
  }

  // ------------------------------------------------------------------------------------------
  // Checklist
  // ------------------------------------------------------------------------------------------

  @Test
  void checklistTasksAreRemindersAdministratorsCompleteAndReopen() throws Exception {
    World w = world();
    UUID employee = hire(w, GIVEN, FAMILY);
    JsonNode separation =
        separate(w, employee, command(w.today().plusDays(4), "END_OF_LAST_DAY", null));
    String sid = separation.get("id").asText();
    JsonNode task = separation.get("tasks").get(0);
    assertThat(task.get("code").asText()).isEqualTo("RETURN_ASSIGNED_ASSETS");
    assertThat(task.get("dueDate").asText()).isEqualTo(w.today().plusDays(4).toString());
    String path =
        BASE
            + "/"
            + employee
            + "/separations/"
            + sid
            + "/tasks/"
            + task.get("id").asText()
            + "/status";
    JsonNode done = expect(taskStatus(w, path, "DONE", 0), 200);
    assertThat(done.get("status").asText()).isEqualTo("DONE");
    problem(taskStatus(w, path, "NOT_APPLICABLE", 1), 409, "SEPARATION_TASK_VERSION_CONFLICT");
    problem(taskStatus(w, path, "OPEN", 0), 409, "SEPARATION_TASK_VERSION_CONFLICT");
    expect(taskStatus(w, path, "OPEN", 1), 200);
    expect(taskStatus(w, path, "NOT_APPLICABLE", 2), 200);
    problem(taskStatus(w, path, "CANCELLED", 3), 400, "VALIDATION_FAILED");
    cancel(w, employee, sid, 200);
    problem(taskStatus(w, path, "OPEN", 4), 409, "SEPARATION_TASK_CLOSED");
    assertThat(
            jdbc.queryForList(
                "SELECT coalesce(from_status, '-') || '>' || to_status FROM"
                    + " people.separation_task_event WHERE task_id = ?::uuid ORDER BY version",
                String.class,
                task.get("id").asText()))
        .containsExactly(
            "->OPEN", "OPEN>DONE", "DONE>OPEN", "OPEN>NOT_APPLICABLE", "NOT_APPLICABLE>CANCELLED");
  }

  private MvcResult taskStatus(World w, String path, String status, long version) throws Exception {
    return call(
        postJson(
                w.admin(),
                path,
                "{\"status\":\"" + status + "\",\"expectedVersion\":" + version + "}")
            .header("Idempotency-Key", Organizations.newKey()));
  }

  // ------------------------------------------------------------------------------------------
  // Effective-date job and authorization
  // ------------------------------------------------------------------------------------------

  @Test
  void theEffectiveDateJobMarksDueSeparationsAndEmployeesHaveNoAccess() throws Exception {
    World w = world();
    UUID employee = hire(w, GIVEN, FAMILY);
    JsonNode separation =
        separate(w, employee, command(w.today().plusDays(2), "END_OF_LAST_DAY", null));
    UUID sid = UUID.fromString(separation.get("id").asText());
    separationJobs.markEffective();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM people.employment_separation WHERE id = ?", String.class, sid))
        .isEqualTo("SCHEDULED");
    jdbc.execute("SET session_replication_role = replica");
    try {
      jdbc.update(
          "UPDATE people.employment_separation SET effective_at = now() - interval '1 minute'"
              + " WHERE id = ?",
          sid);
    } finally {
      jdbc.execute("SET session_replication_role = DEFAULT");
    }
    assertThat(separationJobs.markEffective()).isGreaterThanOrEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM people.employment_separation WHERE id = ?", String.class, sid))
        .isEqualTo("EFFECTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                    + " 'people.employment.separation-effective.v1'"
                    + " AND envelope->'data'->>'separationId' = ?",
                Integer.class,
                sid.toString()))
        .isEqualTo(1);

    String employeeBearer =
        Hierarchy.bearer(w.org().tenant(), "sub-sep-employee-" + UUID.randomUUID(), "employee");
    for (String path :
        List.of(BASE + "/" + employee + "/separations", BASE + "/" + employee + "/access-link")) {
      assertThat(call(get(path).header("Authorization", employeeBearer)).getResponse().getStatus())
          .isEqualTo(403);
    }
  }

  // ------------------------------------------------------------------------------------------
  // Privacy (A22-6)
  // ------------------------------------------------------------------------------------------

  /** Audit metadata keys are allow-listed; markers never reach metadata or outbox data. */
  private void assertPrivate(World w) {
    List<Map<String, Object>> audits =
        jdbc.queryForList(
            "SELECT action, metadata::text AS metadata, after_state_sha256 FROM"
                + " platform.audit_event WHERE tenant_id = ? AND (action LIKE"
                + " 'employee-separation.%' OR action LIKE 'employee-access-link.%' OR action"
                + " LIKE 'access-revocation.%' OR action LIKE 'tenant-membership.%' OR action LIKE"
                + " 'separation-task.%')",
            w.org().tenant());
    assertThat(audits).isNotEmpty();
    Set<String> keys = new TreeSet<>();
    for (Map<String, Object> audit : audits) {
      try {
        JSON.readTree((String) audit.get("metadata")).fieldNames().forEachRemaining(keys::add);
      } catch (Exception malformed) {
        throw new IllegalStateException(malformed);
      }
      assertThat((String) audit.get("after_state_sha256")).matches("^[0-9a-f]{64}$");
      assertThat((String) audit.get("metadata"))
          .doesNotContainIgnoringCase("élodie")
          .doesNotContainIgnoringCase("kanza")
          .doesNotContain("@exemple.cd")
          .doesNotContain("RESIGNATION")
          .doesNotContain("RETURN_ASSIGNED_ASSETS")
          .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}")
          .doesNotContainPattern("[0-9a-f]{64}");
    }
    assertThat(METADATA_KEYS).containsAll(keys);
    String outbox =
        jdbc.queryForObject(
            "SELECT coalesce(string_agg((envelope->'data')::text, ' '), '') FROM"
                + " platform.outbox_event WHERE tenant_id = ? AND (event_type LIKE"
                + " 'people.employment.separation-%' OR event_type LIKE 'identity.%')",
            String.class, w.org().tenant());
    assertThat(outbox)
        .doesNotContainIgnoringCase("élodie")
        .doesNotContain("@exemple.cd")
        .doesNotContain("RESIGNATION")
        .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
  }

  @Test
  void logsCarryNoPersonalOrRestrictedValues(CapturedOutput output) throws Exception {
    World w = world();
    UUID employee = hire(w, GIVEN, FAMILY);
    Member member = member(w, "employee");
    expect(link(w, employee, member), 201);
    separate(w, employee, command(w.today(), "IMMEDIATELY", null));
    revocations.revokePending();
    // Application logs are structured JSON lines (MockMvc's own request dumps are test output).
    String logs =
        String.join(
            "\n",
            output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp\"")).toList());
    assertThat(logs)
        .isNotEmpty()
        .doesNotContain(member.address())
        .doesNotContain(member.subject())
        .doesNotContain(GIVEN)
        .doesNotContain(FAMILY)
        .doesNotContain("RESIGNATION");
  }
}
