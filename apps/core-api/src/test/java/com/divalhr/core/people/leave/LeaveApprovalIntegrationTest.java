package com.divalhr.core.people.leave;

import static com.divalhr.core.people.leave.LeaveWorld.ADMIN_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.JSON;
import static com.divalhr.core.people.leave.LeaveWorld.MY_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.MY_REQUESTS;
import static com.divalhr.core.people.leave.LeaveWorld.decide;
import static com.divalhr.core.people.leave.LeaveWorld.decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.leave.LeaveWorld.Person;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-041B (Issue #89) end to end against PostgreSQL: the manager and tenant-administrator inboxes
 * and decisions, routing on the request's first day and rerouting after manager changes, exact
 * replay and key reuse, replay after manager, link and route changes, reason validation without
 * echo, the employee's terminal history without the decider, employment revalidation on approval,
 * overlap after approval and rejection, authorization, tenant and subject isolation, cursors, the
 * fail-closed disclosure audits, audit and event contents, and log privacy.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LeaveApprovalIntegrationTest {

  private static final String FRENCH_REASON = "Couverture de l’équipe assurée, bon congé !";
  private static final String ENGLISH_REASON = "Peak season: the team cannot cover these dates.";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;

  private LeaveWorld world() throws Exception {
    return new LeaveWorld(mvc, jdbc, lookups);
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.get("items").forEach(item -> ids.add(item.get("id").asText()));
    return ids;
  }

  private static List<String> fields(JsonNode problem) {
    List<String> fields = new ArrayList<>();
    problem
        .path("params")
        .path("fields")
        .forEach(f -> fields.add(f.get("field").asText() + ":" + f.get("constraint").asText()));
    return fields;
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** The application's structured log lines (MockMvc's own failure dump is not a log). */
  private static String logs(CapturedOutput output) {
    return String.join(
        "\n", output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp\"")).toList());
  }

  private static MockHttpServletRequestBuilder inbox(String base, String bearer) {
    return get(base).header("Authorization", bearer);
  }

  // ------------------------------------------------------------------------------------------
  // Routing, inboxes, decisions, history, audit and events (AC1-AC4, AC8)
  // ------------------------------------------------------------------------------------------

  @Test
  void eachApproverSeesAndDecidesOnlyTheRequestsRoutedToThem(CapturedOutput output)
      throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person manager = w.person("Josué", "Kabeya");
    Person report = w.person("Bénédicte", "Mbuyi-Ngalula");
    Person stranger = w.person("Aline", "Tshibanda");
    w.manage(report.employee(), manager.employee(), t.plusDays(10));

    String routed = w.request(report, managed, t.plusDays(20), t.plusDays(22));
    // Starts before the reporting line: no qualifying manager on its first day.
    String early = w.request(report, managed, t.plusDays(5), t.plusDays(6));
    String admin = w.request(report, central, t.plusDays(30), t.plusDays(31));
    String strangers = w.request(stranger, managed, t.plusDays(20), t.plusDays(20));

    // The manager's inbox: only the report's manager-routed request on its first day.
    MockHttpServletResponse inbox = w.call(inbox(MY_APPROVALS, manager.bearer()));
    assertThat(inbox.getStatus()).isEqualTo(200);
    assertThat(inbox.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode page = JSON.readTree(inbox.getContentAsString());
    assertThat(ids(page)).containsExactly(routed);
    JsonNode item = page.get("items").get(0);
    assertThat(fieldNames(item))
        .containsExactlyInAnyOrder(
            "id",
            "submittedAt",
            "employee",
            "policyId",
            "policyVersionId",
            "policyCode",
            "policyNames",
            "unit",
            "amount",
            "startDate",
            "endDate",
            "approvalRoute",
            "state");
    assertThat(item.get("employee").get("employeeNumber").asText()).isEqualTo(report.number());
    assertThat(item.get("employee").get("familyName").asText()).isEqualTo("Mbuyi-Ngalula");
    assertThat(item.get("policyNames").get("fr").asText()).isEqualTo("Congé annuel MGR");
    assertThat(item.get("approvalRoute").asText()).isEqualTo("MANAGER");
    assertThat(item.get("state").asText()).isEqualTo("PENDING");
    assertThat(page.get("nextCursor").isNull()).isTrue();

    // The tenant administrators' inbox: only the admin-routed request.
    JsonNode central1 = w.expect(inbox(ADMIN_APPROVALS, w.admin), 200);
    assertThat(ids(central1)).containsExactly(admin);
    // Nobody else learns anything about another route or another manager's report.
    assertThat(ids(w.expect(inbox(MY_APPROVALS, stranger.bearer()), 200))).isEmpty();
    assertThat(ids(w.expect(inbox(MY_APPROVALS, report.bearer()), 200))).isEmpty();
    Map<String, Object> approve = decision("APPROVED", "fr", FRENCH_REASON);
    for (String id : List.of(early, admin, strangers, UUID.randomUUID().toString())) {
      JsonNode refused =
          w.problem(
              decide(MY_APPROVALS, manager.bearer(), id, Organizations.newKey(), approve),
              404,
              "LEAVE_REQUEST_NOT_FOUND");
      assertThat(refused.get("params").size()).isZero();
    }
    for (Person other : List.of(stranger, report)) {
      w.problem(
          decide(MY_APPROVALS, other.bearer(), routed, Organizations.newKey(), approve),
          404,
          "LEAVE_REQUEST_NOT_FOUND");
    }
    for (String id : List.of(routed, early, strangers)) {
      w.problem(
          decide(ADMIN_APPROVALS, w.admin, id, Organizations.newKey(), approve),
          404,
          "LEAVE_REQUEST_NOT_FOUND");
    }
    assertThat(w.decisions()).containsExactly(0, 0, 0);

    // The manager approves; the receipt is minimal and never carries the reason.
    String key = Organizations.newKey();
    MockHttpServletResponse approved =
        w.call(decide(MY_APPROVALS, manager.bearer(), routed, key, approve));
    assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
    assertThat(approved.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(approved.getContentAsString()).doesNotContain("Couverture");
    JsonNode receipt = JSON.readTree(approved.getContentAsString());
    assertThat(fieldNames(receipt))
        .containsExactlyInAnyOrder("requestId", "decisionId", "state", "decidedAt");
    assertThat(receipt.get("requestId").asText()).isEqualTo(routed);
    assertThat(receipt.get("state").asText()).isEqualTo("APPROVED");
    assertThat(w.state(routed)).isEqualTo("APPROVED");
    // Exact replay; changed body under the key; another key on a decided request.
    MockHttpServletResponse replay =
        w.call(decide(MY_APPROVALS, manager.bearer(), routed, key, approve));
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(receipt);
    w.problem(
        decide(
            MY_APPROVALS, manager.bearer(), routed, key, decision("REJECTED", "fr", FRENCH_REASON)),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    JsonNode decided =
        w.problem(
            decide(MY_APPROVALS, manager.bearer(), routed, Organizations.newKey(), approve),
            409,
            "LEAVE_REQUEST_ALREADY_DECIDED");
    assertThat(decided.get("params").size()).isZero();
    // A decided request leaves the inbox.
    assertThat(ids(w.expect(inbox(MY_APPROVALS, manager.bearer()), 200))).isEmpty();

    // The tenant administrator rejects, in English; the request's dates are released.
    JsonNode rejected =
        w.expect(
            decide(
                ADMIN_APPROVALS,
                w.admin,
                admin,
                Organizations.newKey(),
                decision("REJECTED", "en", ENGLISH_REASON)),
            200);
    assertThat(rejected.get("state").asText()).isEqualTo("REJECTED");
    assertThat(ids(w.expect(inbox(ADMIN_APPROVALS, w.admin), 200))).isEmpty();
    assertThat(w.decisions()).containsExactly(2, 2, 2);

    // The employee's history: terminal states and the reasons as written, never who decided.
    MockHttpServletResponse mine =
        w.call(get(MY_REQUESTS).header("Authorization", report.bearer()));
    String history = mine.getContentAsString();
    assertThat(history)
        .doesNotContain(manager.employee().toString())
        .doesNotContain(manager.subject())
        .doesNotContain("decidedBy")
        .doesNotContain("manager");
    Map<String, JsonNode> byId = new LinkedHashMap<>();
    JSON.readTree(history).get("items").forEach(r -> byId.put(r.get("id").asText(), r));
    assertThat(byId.get(routed).get("state").asText()).isEqualTo("APPROVED");
    JsonNode reason = byId.get(routed).get("decision");
    assertThat(fieldNames(reason))
        .containsExactlyInAnyOrder("id", "outcome", "reasonLocale", "reason", "decidedAt");
    assertThat(reason.get("reason").asText()).isEqualTo(FRENCH_REASON);
    assertThat(reason.get("reasonLocale").asText()).isEqualTo("fr");
    assertThat(reason.get("id").asText()).isEqualTo(receipt.get("decisionId").asText());
    assertThat(byId.get(admin).get("decision").get("reason").asText()).isEqualTo(ENGLISH_REASON);
    assertThat(byId.get(early).get("decision").isNull()).isTrue();
    assertThat(byId.get(early).get("state").asText()).isEqualTo("PENDING");

    // Audit: identifiers, route and states only.
    JsonNode metadata =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE action ="
                    + " 'leave-request.approve' AND resource_id = ?::uuid",
                String.class,
                routed));
    assertThat(fieldNames(metadata))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "requestId",
            "decisionId",
            "employeeId",
            "employmentId",
            "policyId",
            "policyVersionId",
            "approvalRoute",
            "priorState",
            "resultingState");
    assertThat(metadata.get("priorState").asText()).isEqualTo("PENDING");
    assertThat(metadata.get("resultingState").asText()).isEqualTo("APPROVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT after_state_sha256 FROM platform.audit_event WHERE action ="
                    + " 'leave-request.approve' AND resource_id = ?::uuid",
                String.class,
                routed))
        .hasSize(64);
    // Events: identifiers, route and state only.
    for (String[] expected :
        List.of(
            new String[] {routed, "people.leave-request.approved.v1", "APPROVED"},
            new String[] {admin, "people.leave-request.rejected.v1", "REJECTED"})) {
      JsonNode envelope =
          JSON.readTree(
              jdbc.queryForObject(
                  "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject'"
                      + " = ? AND envelope ->> 'eventType' = ?",
                  String.class,
                  expected[0],
                  expected[1]));
      assertThat(fieldNames(envelope.get("data")))
          .containsExactlyInAnyOrder(
              "requestId",
              "decisionId",
              "employeeId",
              "employmentId",
              "policyId",
              "policyVersionId",
              "approvalRoute",
              "state");
      assertThat(envelope.get("data").get("state").asText()).isEqualTo(expected[2]);
      assertThat(envelope.toString())
          .doesNotContain("Couverture")
          .doesNotContain("Peak season")
          .doesNotContain(manager.employee().toString())
          .doesNotContain(manager.subject());
    }
    // The decision row keeps the deciding manager and subject (never shown to the employee).
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT approval_route, manager_employee_id, decided_by, reason_locale"
                + " FROM people.leave_request_decision WHERE request_id = ?::uuid",
            routed);
    assertThat(row.get("approval_route")).isEqualTo("MANAGER");
    assertThat(row.get("manager_employee_id")).isEqualTo(manager.employee());
    assertThat(row.get("decided_by")).isEqualTo(manager.subject());
    // Disclosure audits of both inboxes: view, page and count only.
    for (String[] view :
        List.of(
            new String[] {"leave-approval.manager-list", manager.employee().toString()},
            new String[] {"leave-approval.admin-list", w.tenant().toString()})) {
      List<String> metadataRows =
          jdbc.queryForList(
              "SELECT metadata::text FROM platform.audit_event WHERE action = ? AND resource_id ="
                  + " ?::uuid",
              String.class,
              view[0],
              view[1]);
      assertThat(metadataRows).isNotEmpty();
      assertThat(fieldNames(JSON.readTree(metadataRows.get(0))))
          .containsExactlyInAnyOrder("schemaVersion", "view", "page", "resultCount");
    }

    assertThat(logs(output))
        .contains("leave_request_approved")
        .contains("leave_request_rejected")
        .contains("leave_approval_listed")
        .doesNotContain("Couverture")
        .doesNotContain("Peak season")
        .doesNotContain("Mbuyi-Ngalula")
        .doesNotContain(manager.subject())
        .doesNotContain(key);
  }

  // ------------------------------------------------------------------------------------------
  // Rerouting and replay after manager, link and role changes (D41B-1, D41B-5)
  // ------------------------------------------------------------------------------------------

  @Test
  void managerChangesRerouteAndReplaysFollowTheCurrentRelationship() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person first = w.person("Josué", "Kabeya");
    Person second = w.person("Grâce", "Lukusa");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.manage(report.employee(), first.employee(), t.plusDays(1));
    String later = w.request(report, managed, t.plusDays(40), t.plusDays(41));
    String sooner = w.request(report, managed, t.plusDays(12), t.plusDays(12));
    assertThat(ids(w.expect(inbox(MY_APPROVALS, first.bearer()), 200)))
        .containsExactlyInAnyOrder(later, sooner);

    // The first manager decides the sooner request.
    String key = Organizations.newKey();
    Map<String, Object> approve = decision("APPROVED", "en", "Covered by the night shift.");
    JsonNode receipt = w.expect(decide(MY_APPROVALS, first.bearer(), sooner, key, approve), 200);

    // A scheduled change from t + 30: the later request now belongs to the second manager.
    w.manage(report.employee(), second.employee(), t.plusDays(30));
    assertThat(ids(w.expect(inbox(MY_APPROVALS, first.bearer()), 200))).isEmpty();
    assertThat(ids(w.expect(inbox(MY_APPROVALS, second.bearer()), 200))).containsExactly(later);
    w.problem(
        decide(MY_APPROVALS, first.bearer(), later, Organizations.newKey(), approve),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
    // The first manager still manages the sooner request's first day: the replay is theirs.
    MockHttpServletResponse replay =
        w.call(decide(MY_APPROVALS, first.bearer(), sooner, key, approve));
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(receipt);

    // A correction from t + 5 hands the sooner request to the second manager: the first
    // manager's replay is refused without the stored receipt.
    w.manage(report.employee(), second.employee(), t.plusDays(5));
    MockHttpServletResponse refused =
        w.call(decide(MY_APPROVALS, first.bearer(), sooner, key, approve));
    assertThat(refused.getStatus()).isEqualTo(404);
    assertThat(refused.getHeader("Idempotent-Replayed")).isNull();
    assertThat(refused.getContentAsString())
        .doesNotContain(receipt.get("decisionId").asText())
        .doesNotContain(sooner);

    // Unlinked: the link denial, before anything about the request.
    w.unlink(second.employee());
    JsonNode cursorless =
        w.problem(inbox(MY_APPROVALS, second.bearer()), 403, "EMPLOYEE_LINK_REQUIRED");
    assertThat(cursorless.get("params").size()).isZero();
    w.problem(
        decide(MY_APPROVALS, second.bearer(), later, Organizations.newKey(), approve),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    // Relinked to another employee: nothing of the former employee's reports.
    UUID elsewhere = w.hire("Paul", "Ilunga");
    w.linkTo(elsewhere, second.membership());
    assertThat(ids(w.expect(inbox(MY_APPROVALS, second.bearer()), 200))).isEmpty();
    w.problem(
        decide(MY_APPROVALS, second.bearer(), later, Organizations.newKey(), approve),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
    assertThat(w.decisions()).containsExactly(1, 1, 1);
    assertThat(w.state(later)).isEqualTo("PENDING");
  }

  @Test
  void anAdministratorReplayNeedsTheAdministratorRole() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    String id = w.request(report, central, t.plusDays(3), t.plusDays(3));
    String key = Organizations.newKey();
    Map<String, Object> reject = decision("REJECTED", "fr", "Effectif insuffisant cette semaine.");
    JsonNode receipt = w.expect(decide(ADMIN_APPROVALS, w.admin, id, key, reject), 200);
    MockHttpServletResponse replay = w.call(decide(ADMIN_APPROVALS, w.admin, id, key, reject));
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(receipt);
    // The same subject as an employee, without MFA, in another tenant; another employee.
    String sub = w.adminSubject;
    UUID other = Hierarchy.newTenant(mvc);
    for (String bearer :
        List.of(
            LeaveWorld.employeeBearer(w.tenant(), sub),
            "Bearer "
                + TestTokens.token()
                    .tenant(w.tenant())
                    .subject(sub)
                    .roles(List.of("tenant-admin"))
                    .acr(TestTokens.PASSWORD_ACR)
                    .build(),
            Hierarchy.bearer(other, sub, "tenant-admin"),
            report.bearer())) {
      MockHttpServletResponse denied = w.call(decide(ADMIN_APPROVALS, bearer, id, key, reject));
      assertThat(denied.getStatus()).isIn(401, 403, 404, 409);
      assertThat(denied.getHeader("Idempotent-Replayed")).isNull();
      assertThat(denied.getContentAsString()).doesNotContain(receipt.get("decisionId").asText());
    }
    assertThat(w.decisions()).containsExactly(1, 1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // Reason contract (D41B-4)
  // ------------------------------------------------------------------------------------------

  @Test
  void decisionsAreValidatedWithoutEchoingTheReason() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    String id = w.request(report, central, t.plusDays(3), t.plusDays(3));

    record Bad(Object body, String expected) {}
    Map<String, Object> extra = new LinkedHashMap<>(decision("APPROVED", "en", "Fine reason"));
    extra.put("employeeId", report.employee().toString());
    for (Bad bad :
        List.of(
            new Bad(decision("APPROVED", "en", null), "reason:REQUIRED"),
            new Bad(decision("APPROVED", "en", "   "), "reason:REQUIRED"),
            new Bad(decision("APPROVED", "en", "x"), "reason:LENGTH"),
            new Bad(
                decision("APPROVED", "en", "secret-reason-" + "é".repeat(487)), "reason:LENGTH"),
            new Bad(decision("APPROVED", "en", "secret\u0007reason"), "reason:FORMAT"),
            new Bad(decision("APPROVED", "en", "secret\nreason"), "reason:FORMAT"),
            new Bad(decision("APPROVED", "en", "secret\u200breason"), "reason:FORMAT"),
            new Bad(decision("MAYBE", "en", "Fine reason"), "decision:FORMAT"),
            new Bad(decision(null, "en", "Fine reason"), "decision:REQUIRED"),
            new Bad(decision("APPROVED", "de", "Fine reason"), "reasonLocale:FORMAT"),
            new Bad(decision("APPROVED", null, "Fine reason"), "reasonLocale:REQUIRED"),
            new Bad(extra, "body:UNKNOWN_PROPERTY"))) {
      JsonNode problem =
          w.problem(
              decide(ADMIN_APPROVALS, w.admin, id, Organizations.newKey(), bad.body()),
              400,
              "VALIDATION_FAILED");
      assertThat(fields(problem)).as("%s", bad.expected()).containsExactly(bad.expected());
      assertThat(problem.toString()).doesNotContain("secret");
    }
    w.problem(
        decide(ADMIN_APPROVALS, w.admin, id, null, decision("APPROVED", "en", "Fine")),
        400,
        "VALIDATION_FAILED");
    w.problem(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            "not-a-uuid",
            Organizations.newKey(),
            decision("APPROVED", "en", "Fine")),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
    assertThat(w.decisions()).containsExactly(0, 0, 0);

    // 501 code points before NFC, 500 after (supplementary characters count once), trimmed.
    String composed = "Cafe\u0301 " + "😀".repeat(495);
    w.expect(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            id,
            Organizations.newKey(),
            decision("APPROVED", "en", "  " + composed + "  ")),
        200);
    String stored =
        jdbc.queryForObject(
            "SELECT reason_text FROM people.leave_request_decision WHERE request_id = ?::uuid",
            String.class,
            id);
    assertThat(stored).isEqualTo("Café " + "😀".repeat(495));
    assertThat(stored.codePointCount(0, stored.length())).isEqualTo(500);
  }

  // ------------------------------------------------------------------------------------------
  // Employment revalidation and overlap (D41B-2, D41B-4; AC5)
  // ------------------------------------------------------------------------------------------

  @Test
  void approvalNeedsTheEmploymentRejectionDoesNotAndOnlyRejectionReleasesTheDates()
      throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person report = w.person("Bénédicte", "Mbuyi");
    Person leaving = w.person("Aline", "Tshibanda");

    // Approved dates keep blocking; rejected dates are released.
    String approvedId = w.request(report, central, t.plusDays(10), t.plusDays(12));
    w.expect(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            approvedId,
            Organizations.newKey(),
            decision("APPROVED", "en", "Approved for the planned trip.")),
        200);
    w.problem(
        LeaveWorld.submit(report.bearer(), central, t.plusDays(12), t.plusDays(13)),
        409,
        "LEAVE_REQUEST_OVERLAP");
    String rejectedId = w.request(report, central, t.plusDays(20), t.plusDays(22));
    w.problem(
        LeaveWorld.submit(report.bearer(), central, t.plusDays(21), t.plusDays(21)),
        409,
        "LEAVE_REQUEST_OVERLAP");
    w.expect(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            rejectedId,
            Organizations.newKey(),
            decision("REJECTED", "fr", "Période de clôture.")),
        200);
    w.expect(LeaveWorld.submit(report.bearer(), central, t.plusDays(21), t.plusDays(21)), 201);

    // The employment ends inside the interval: approval is refused, rejection is not, and a past
    // first day never refuses a decision.
    String cut = w.request(leaving, central, t.plusDays(30), t.plusDays(35));
    String alsoCut = w.request(leaving, central, t.plusDays(40), t.plusDays(41));
    w.expect(w.separate(leaving.employee(), w.separation(t.plusDays(32))), 201);
    JsonNode notEligible =
        w.problem(
            decide(
                ADMIN_APPROVALS,
                w.admin,
                cut,
                Organizations.newKey(),
                decision("APPROVED", "en", "Approved before the departure.")),
            409,
            "LEAVE_REQUEST_NOT_ELIGIBLE");
    assertThat(notEligible.get("params").get("reason").asText()).isEqualTo("EMPLOYMENT_PERIOD");
    assertThat(w.state(cut)).isEqualTo("PENDING");
    w.expect(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            alsoCut,
            Organizations.newKey(),
            decision("REJECTED", "en", "Not employed on these dates.")),
        200);
    assertThat(w.state(alsoCut)).isEqualTo("REJECTED");
    // A decided request is refused as decided before any other rule (here, the ended employment).
    w.problem(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            alsoCut,
            Organizations.newKey(),
            decision("APPROVED", "en", "Approved after all.")),
        409,
        "LEAVE_REQUEST_ALREADY_DECIDED");
  }

  // ------------------------------------------------------------------------------------------
  // Authorization, isolation and cursors (D41B-3, D41B-7; AC7)
  // ------------------------------------------------------------------------------------------

  @Test
  void inboxesAreBoundToTheirCallerRouteAndTenant() throws Exception {
    LeaveWorld w = world();
    LeaveWorld other = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person manager = w.person("Josué", "Kabeya");
    List<String> mine = new ArrayList<>();
    List<String> theirs = new ArrayList<>();
    for (String given : List.of("Amani", "Bahati", "Chance")) {
      Person report = w.person(given, "Mbuyi");
      w.manage(report.employee(), manager.employee(), t.plusDays(1));
      mine.add(w.request(report, managed, t.plusDays(10), t.plusDays(10)));
      theirs.add(w.request(report, central, t.plusDays(20), t.plusDays(20)));
    }
    String otherCentral = other.policy("ADM", "TENANT_ADMIN");
    Person foreign = other.person("Étranger", "Kasongo");
    String foreignId = other.request(foreign, otherCentral, t.plusDays(5), t.plusDays(5));

    // Two pages of two then one, newest first, for the manager and the administrator.
    for (String[] view :
        List.of(
            new String[] {MY_APPROVALS, manager.bearer()},
            new String[] {ADMIN_APPROVALS, w.admin})) {
      JsonNode first =
          w.expect(get(view[0]).param("limit", "2").header("Authorization", view[1]), 200);
      assertThat(first.get("items")).hasSize(2);
      String cursor = first.get("nextCursor").asText();
      JsonNode second =
          w.expect(
              get(view[0])
                  .param("limit", "2")
                  .param("cursor", cursor)
                  .header("Authorization", view[1]),
              200);
      assertThat(second.get("items")).hasSize(1);
      assertThat(second.get("nextCursor").isNull()).isTrue();
      List<String> all = new ArrayList<>(ids(first));
      all.addAll(ids(second));
      assertThat(all)
          .containsExactlyInAnyOrderElementsOf(view[0].equals(MY_APPROVALS) ? mine : theirs);
      // A cursor never moves to another page size, operation, caller or tenant.
      w.problem(
          get(view[0]).param("limit", "3").param("cursor", cursor).header("Authorization", view[1]),
          400,
          "CURSOR_INVALID");
      String crossed = view[0].equals(MY_APPROVALS) ? ADMIN_APPROVALS : MY_APPROVALS;
      String crossedBearer = view[0].equals(MY_APPROVALS) ? w.admin : manager.bearer();
      w.problem(
          get(crossed)
              .param("limit", "2")
              .param("cursor", cursor)
              .header("Authorization", crossedBearer),
          400,
          "CURSOR_INVALID");
      w.problem(
          get(ADMIN_APPROVALS)
              .param("limit", "2")
              .param("cursor", cursor)
              .header("Authorization", other.admin),
          400,
          "CURSOR_INVALID");
    }
    // A relink invalidates the manager's cursor.
    JsonNode first =
        w.expect(
            get(MY_APPROVALS).param("limit", "2").header("Authorization", manager.bearer()), 200);
    w.unlink(manager.employee());
    w.linkTo(manager.employee(), manager.membership());
    w.problem(
        get(MY_APPROVALS)
            .param("limit", "2")
            .param("cursor", first.get("nextCursor").asText())
            .header("Authorization", manager.bearer()),
        400,
        "CURSOR_INVALID");

    // Another tenant's request is unknown here.
    w.problem(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            foreignId,
            Organizations.newKey(),
            decision("APPROVED", "en", "Fine")),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
    assertThat(ids(w.expect(inbox(ADMIN_APPROVALS, other.admin), 200))).containsExactly(foreignId);

    // Roles: an employee cannot use the administrators' inbox, an administrator not the
    // managers'; no token is 401; an employee member without a link is 403 link required.
    w.problem(inbox(ADMIN_APPROVALS, manager.bearer()), 403, "ACCESS_DENIED");
    w.problem(inbox(MY_APPROVALS, w.admin), 403, "ACCESS_DENIED");
    assertThat(w.call(get(MY_APPROVALS)).getStatus()).isEqualTo(401);
    String unlinked = UUID.randomUUID().toString();
    w.member(unlinked);
    w.problem(
        inbox(MY_APPROVALS, LeaveWorld.employeeBearer(w.tenant(), unlinked)),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    w.problem(
        decide(
            MY_APPROVALS,
            LeaveWorld.employeeBearer(w.tenant(), unlinked),
            UUID.randomUUID().toString(),
            Organizations.newKey(),
            decision("APPROVED", "en", "Fine")),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    // Password-only administrators are refused.
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant())
                .subject("sub-leave41b-pwd-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    assertThat(w.call(inbox(ADMIN_APPROVALS, passwordOnly)).getStatus()).isIn(401, 403);
  }

  // ------------------------------------------------------------------------------------------
  // Fail-closed disclosure (D41B-6)
  // ------------------------------------------------------------------------------------------

  @Test
  void noInboxPageIsReturnedWhenItsDisclosureAuditCannotBeWritten() throws Exception {
    LeaveWorld w = world();
    String central = w.policy("ADM", "TENANT_ADMIN");
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.manage(report.employee(), manager.employee(), w.today.plusDays(1));
    w.request(report, central, w.today.plusDays(3), w.today.plusDays(3));
    w.request(report, managed, w.today.plusDays(5), w.today.plusDays(5));
    String function = "no_leave_approval_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "CREATE FUNCTION public."
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action LIKE"
            + " 'leave-approval.%' AND NEW.tenant_id = '"
            + w.tenant()
            + "' THEN RAISE EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + function
            + " BEFORE INSERT ON platform.audit_event FOR EACH ROW EXECUTE FUNCTION public."
            + function
            + "()");
    try {
      for (MockHttpServletRequestBuilder request :
          List.of(inbox(ADMIN_APPROVALS, w.admin), inbox(MY_APPROVALS, manager.bearer()))) {
        MockHttpServletResponse refused = w.call(request);
        assertThat(refused.getStatus()).isEqualTo(500);
        assertThat(refused.getContentAsString()).doesNotContain("Mbuyi").doesNotContain("PENDING");
      }
    } finally {
      jdbc.execute("DROP TRIGGER " + function + " ON platform.audit_event");
      jdbc.execute("DROP FUNCTION public." + function + "()");
    }
    assertThat(w.expect(inbox(ADMIN_APPROVALS, w.admin), 200).get("items")).hasSize(1);
    assertThat(w.expect(inbox(MY_APPROVALS, manager.bearer()), 200).get("items")).hasSize(1);
  }
}
