package com.divalhr.core.people.leave;

import static com.divalhr.core.people.leave.LeaveWorld.ADMIN_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.EXCEPTIONS;
import static com.divalhr.core.people.leave.LeaveWorld.JSON;
import static com.divalhr.core.people.leave.LeaveWorld.MY_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.MY_REQUESTS;
import static com.divalhr.core.people.leave.LeaveWorld.amend;
import static com.divalhr.core.people.leave.LeaveWorld.amendment;
import static com.divalhr.core.people.leave.LeaveWorld.cancel;
import static com.divalhr.core.people.leave.LeaveWorld.cancellation;
import static com.divalhr.core.people.leave.LeaveWorld.decide;
import static com.divalhr.core.people.leave.LeaveWorld.decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.leave.LeaveWorld.Person;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * MVP-041E (Issue #92) end to end against real PostgreSQL: the routing-exception queue holds only
 * the pending MANAGER-routed requests that no active, non-superseded manager line covers on their
 * first day, re-derived on every read (no manager, a scheduled manager, a correction, a cleared and
 * restored manager); a tenant administrator with exact MFA approves or rejects one as an override
 * under the unchanged MANAGER route; approval re-checks the employment; the override replays after
 * a later manager assignment; terminal requests, managed requests and other routes are refused;
 * role, MFA and tenant isolation; cursors; fail-closed disclosure; privacy of the employee's view,
 * the audit, the event and the logs.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LeaveRoutingExceptionIntegrationTest {

  private static final String FRENCH_REASON =
      "Aucun responsable admissible; décision administrative.";

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

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static MockHttpServletRequestBuilder queue(String bearer) {
    return get(EXCEPTIONS).header("Authorization", bearer);
  }

  private static String logs(CapturedOutput output) {
    return String.join(
        "\n", output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp\"")).toList());
  }

  // ------------------------------------------------------------------------------------------
  // The dynamic queue (D41DE-1, D41DE-4; AC4)
  // ------------------------------------------------------------------------------------------

  @Test
  void theQueueHoldsOnlyRequestsNoQualifyingManagerCovers() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person manager = w.person("Josué", "Kabeya");
    Person alone = w.person("Amani", "Mbuyi");
    Person managedNow = w.person("Bahati", "Ilunga");
    Person scheduled = w.person("Chance", "Tshibanda");
    Person restored = w.person("Divine", "Lukusa");
    w.manage(managedNow.employee(), manager.employee(), t);
    w.manage(scheduled.employee(), manager.employee(), t.plusDays(20));
    w.manage(restored.employee(), manager.employee(), t);
    w.unmanage(restored.employee(), t.plusDays(5));

    String noManager = w.request(alone, managed, t.plusDays(10), t.plusDays(10));
    String adminRouted = w.request(alone, central, t.plusDays(12), t.plusDays(12));
    String covered = w.request(managedNow, managed, t.plusDays(10), t.plusDays(10));
    String beforeScheduled = w.request(scheduled, managed, t.plusDays(10), t.plusDays(10));
    String afterScheduled = w.request(scheduled, managed, t.plusDays(30), t.plusDays(30));
    String cleared = w.request(restored, managed, t.plusDays(10), t.plusDays(10));

    JsonNode page = w.expect(queue(w.admin), 200);
    assertThat(ids(page)).containsExactlyInAnyOrder(noManager, beforeScheduled, cleared);
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
            "state",
            "exceptionReason");
    for (JsonNode each : page.get("items")) {
      assertThat(each.get("approvalRoute").asText()).isEqualTo("MANAGER");
      assertThat(each.get("state").asText()).isEqualTo("PENDING");
      assertThat(each.get("exceptionReason").asText()).isEqualTo("NO_QUALIFYING_MANAGER");
    }
    // Neither inbox holds an exception: the admin inbox only TENANT_ADMIN, the manager's only
    // covered requests.
    assertThat(ids(w.expect(get(ADMIN_APPROVALS).header("Authorization", w.admin), 200)))
        .containsExactly(adminRouted);
    assertThat(ids(w.expect(get(MY_APPROVALS).header("Authorization", manager.bearer()), 200)))
        .containsExactlyInAnyOrder(covered, afterScheduled);

    // A correction supersedes the covering line with another manager: still covered, never an
    // exception, now in the new manager's inbox. A restored manager takes a request out of the
    // queue, and so does a manager line that now starts on or before a request's first day.
    // (The new manager is another employee of the fixture: the import limit keeps it small.)
    Person corrected = alone;
    w.correctManager(managedNow.employee(), manager.employee(), corrected.employee());
    w.manage(restored.employee(), manager.employee(), t.plusDays(8));
    w.manage(scheduled.employee(), manager.employee(), t.plusDays(8));
    assertThat(ids(w.expect(queue(w.admin), 200))).containsExactly(noManager);
    assertThat(ids(w.expect(get(MY_APPROVALS).header("Authorization", manager.bearer()), 200)))
        .containsExactlyInAnyOrder(afterScheduled, beforeScheduled, cleared);
    assertThat(ids(w.expect(get(MY_APPROVALS).header("Authorization", corrected.bearer()), 200)))
        .containsExactly(covered);
    // Deciding one that has a qualifying manager now is indistinguishable from unknown.
    JsonNode refused =
        w.problem(
            decide(
                EXCEPTIONS,
                w.admin,
                cleared,
                Organizations.newKey(),
                decision("APPROVED", "fr", FRENCH_REASON)),
            404,
            "LEAVE_REQUEST_NOT_FOUND");
    assertThat(refused.get("params").size()).isZero();

    // The disclosure audit of each page: the view and count only.
    JsonNode metadata =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE action ="
                    + " 'leave-routing-exception.list' AND tenant_id = ?"
                    + " ORDER BY occurred_at DESC LIMIT 1",
                String.class,
                w.tenant()));
    assertThat(fieldNames(metadata))
        .containsExactlyInAnyOrder("schemaVersion", "view", "page", "resultCount");
    assertThat(metadata.get("view").asText()).isEqualTo("routing-exceptions");
  }

  // ------------------------------------------------------------------------------------------
  // The override (D41DE-4; AC5, AC7, AC8)
  // ------------------------------------------------------------------------------------------

  @Test
  void aTenantAdministratorDecidesAnExceptionWithoutRewritingTheRoute(CapturedOutput output)
      throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person manager = w.person("Josué", "Kabeya");
    Person report = w.person("Bénédicte", "Mbuyi-Ngalula");
    String approved = w.request(report, managed, t.plusDays(10), t.plusDays(11));
    String rejected = w.request(report, managed, t.plusDays(20), t.plusDays(20));

    String key = Organizations.newKey();
    Map<String, Object> approve = decision("APPROVED", "fr", FRENCH_REASON);
    MockHttpServletResponse done = w.call(decide(EXCEPTIONS, w.admin, approved, key, approve));
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
    assertThat(done.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode receipt = JSON.readTree(done.getContentAsString());
    assertThat(fieldNames(receipt))
        .containsExactlyInAnyOrder("requestId", "decisionId", "state", "decidedAt");
    assertThat(receipt.get("state").asText()).isEqualTo("APPROVED");
    w.expect(
        decide(
            EXCEPTIONS,
            w.admin,
            rejected,
            Organizations.newKey(),
            decision("REJECTED", "en", "No manager covers these dates.")),
        200);
    assertThat(w.state(approved)).isEqualTo("APPROVED");
    assertThat(w.state(rejected)).isEqualTo("REJECTED");
    assertThat(ids(w.expect(queue(w.admin), 200))).isEmpty();
    assertThat(w.overrides()).containsExactly(2, 2, 2);
    // The existing approve/reject audit actions and events are untouched.
    assertThat(w.decisions()).containsExactly(2, 0, 0);

    // Stored: the policy route stays MANAGER, the authority is the override, no manager.
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT approval_route, decision_authority, manager_employee_id, decided_by"
                + " FROM people.leave_request_decision WHERE request_id = ?::uuid",
            approved);
    assertThat(row.get("approval_route")).isEqualTo("MANAGER");
    assertThat(row.get("decision_authority")).isEqualTo("TENANT_ADMIN_OVERRIDE");
    assertThat(row.get("manager_employee_id")).isNull();
    assertThat(row.get("decided_by")).isEqualTo(w.adminSubject);

    // The employee sees the outcome and reason exactly like any decision, never the authority.
    MockHttpServletResponse mine =
        w.call(get(MY_REQUESTS).header("Authorization", report.bearer()));
    assertThat(mine.getContentAsString())
        .contains(FRENCH_REASON)
        .doesNotContain("TENANT_ADMIN_OVERRIDE")
        .doesNotContain("decisionAuthority")
        .doesNotContain(w.adminSubject);
    JsonNode decision = null;
    for (JsonNode r : JSON.readTree(mine.getContentAsString()).get("items")) {
      if (r.get("id").asText().equals(approved)) {
        decision = r.get("decision");
      }
    }
    assertThat(decision).isNotNull();
    assertThat(fieldNames(decision))
        .containsExactlyInAnyOrder("id", "outcome", "reasonLocale", "reason", "decidedAt");

    // Audit and event: identifiers, the original route, the authority and the states only.
    JsonNode metadata =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE action ="
                    + " 'leave-request.routing-exception.approve' AND resource_id = ?::uuid",
                String.class,
                approved));
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
            "decisionAuthority",
            "priorState",
            "resultingState");
    assertThat(metadata.get("approvalRoute").asText()).isEqualTo("MANAGER");
    assertThat(metadata.get("decisionAuthority").asText()).isEqualTo("TENANT_ADMIN_OVERRIDE");
    JsonNode envelope =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?"
                    + " AND envelope ->> 'eventType' ="
                    + " 'people.leave-request.routing-exception-approved.v1'",
                String.class,
                approved));
    assertThat(fieldNames(envelope.get("data")))
        .containsExactlyInAnyOrder(
            "requestId",
            "decisionId",
            "employeeId",
            "employmentId",
            "policyId",
            "policyVersionId",
            "approvalRoute",
            "decisionAuthority",
            "state");
    for (String text : List.of(envelope.toString(), metadata.toString())) {
      assertThat(text)
          .doesNotContain("admissible")
          .doesNotContain("Mbuyi-Ngalula")
          .doesNotContain(t.plusDays(10).toString())
          .doesNotContain(w.adminSubject);
    }
    assertThat(logs(output))
        .contains("leave_request_routing_exception_approved")
        .doesNotContain("admissible")
        .doesNotContain("Mbuyi-Ngalula")
        .doesNotContain(key);

    // A manager assigned later does not invalidate the exact replay of the historical override.
    w.manage(report.employee(), manager.employee(), t);
    MockHttpServletResponse replay = w.call(decide(EXCEPTIONS, w.admin, approved, key, approve));
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(receipt);
    // A changed body under the key is a key conflict first.
    w.problem(
        decide(EXCEPTIONS, w.admin, approved, key, decision("REJECTED", "fr", FRENCH_REASON)),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    // The ordinary admin endpoint never decides a MANAGER-routed request.
    w.problem(
        decide(ADMIN_APPROVALS, w.admin, rejected, Organizations.newKey(), approve),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
    assertThat(w.overrides()).containsExactly(2, 2, 2);

    // The replay serves only a stored override: were the stored record to name a request decided
    // by another authority, nothing stored is returned.
    String central = w.policy("ADM", "TENANT_ADMIN");
    String adminDecided = w.request(report, central, t.plusDays(40), t.plusDays(40));
    w.expect(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            adminDecided,
            Organizations.newKey(),
            decision("REJECTED", "en", "Not this week.")),
        200);
    jdbc.update(
        "UPDATE platform.idempotency_record SET resource_id = ?::uuid"
            + " WHERE operation = 'leave-request.routing-exception-decide' AND idempotency_key = ?",
        adminDecided,
        key);
    MockHttpServletResponse foreignReplay =
        w.call(decide(EXCEPTIONS, w.admin, approved, key, approve));
    assertThat(foreignReplay.getStatus()).isEqualTo(404);
    assertThat(foreignReplay.getHeader("Idempotent-Replayed")).isNull();
    assertThat(foreignReplay.getContentAsString())
        .doesNotContain(receipt.get("decisionId").asText());
  }

  @Test
  void approvalRechecksTheEmploymentAndRejectionDoesNot() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person leaving = w.person("Bénédicte", "Mbuyi");
    String cut = w.request(leaving, managed, t.plusDays(30), t.plusDays(35));
    String alsoCut = w.request(leaving, managed, t.plusDays(40), t.plusDays(41));
    w.expect(w.separate(leaving.employee(), w.separation(t.plusDays(32))), 201);
    JsonNode notEligible =
        w.problem(
            decide(
                EXCEPTIONS,
                w.admin,
                cut,
                Organizations.newKey(),
                decision("APPROVED", "fr", FRENCH_REASON)),
            409,
            "LEAVE_REQUEST_NOT_ELIGIBLE");
    assertThat(notEligible.get("params").get("reason").asText()).isEqualTo("EMPLOYMENT_PERIOD");
    assertThat(w.state(cut)).isEqualTo("PENDING");
    w.expect(
        decide(
            EXCEPTIONS,
            w.admin,
            alsoCut,
            Organizations.newKey(),
            decision("REJECTED", "fr", FRENCH_REASON)),
        200);
    assertThat(w.overrides()).containsExactly(1, 1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // Refusals, roles, MFA and tenants (D41DE-1, D41DE-4)
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyCurrentExceptionsAreDecidedAndOnlyByVerifiedTenantAdministrators() throws Exception {
    LeaveWorld w = world();
    LeaveWorld other = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person manager = w.person("Josué", "Kabeya");
    Person covered = w.person("Bahati", "Ilunga");
    Person alone = w.person("Amani", "Mbuyi");
    Person another = w.person("Chance", "Tshibanda");
    w.manage(covered.employee(), manager.employee(), t);
    String managedRequest = w.request(covered, managed, t.plusDays(5), t.plusDays(5));
    String adminRouted = w.request(alone, central, t.plusDays(5), t.plusDays(5));
    String exception = w.request(alone, managed, t.plusDays(9), t.plusDays(9));
    String cancelledOne = w.request(alone, managed, t.plusDays(13), t.plusDays(13));
    String amendedOne = w.request(another, managed, t.plusDays(17), t.plusDays(17));
    String decidedOne = w.request(another, managed, t.plusDays(21), t.plusDays(21));
    String foreignPolicy = other.policy("MGR", "MANAGER");
    Person foreigner = other.person("Grâce", "Lukusa");
    String foreign = other.request(foreigner, foreignPolicy, t.plusDays(5), t.plusDays(5));
    w.expect(
        cancel(alone.bearer(), cancelledOne, Organizations.newKey(), cancellation("fr", "Non.")),
        200);
    w.expect(
        amend(
            another.bearer(),
            amendedOne,
            Organizations.newKey(),
            amendment(managed, t.plusDays(18), t.plusDays(18), 1, "fr", "Décalé.")),
        201);
    w.expect(
        decide(
            EXCEPTIONS,
            w.admin,
            decidedOne,
            Organizations.newKey(),
            decision("REJECTED", "fr", FRENCH_REASON)),
        200);
    Map<String, Object> body = decision("APPROVED", "fr", FRENCH_REASON);

    // Managed, admin-routed, another tenant's, unknown, malformed: indistinguishable.
    for (String id :
        List.of(managedRequest, adminRouted, foreign, UUID.randomUUID().toString(), "nope")) {
      JsonNode refused =
          w.problem(
              decide(EXCEPTIONS, w.admin, id, Organizations.newKey(), body),
              404,
              "LEAVE_REQUEST_NOT_FOUND");
      assertThat(refused.get("params").size()).isZero();
    }
    // Terminal MANAGER-routed requests: their own conflict, without params.
    for (String[] terminal :
        List.of(
            new String[] {cancelledOne, "LEAVE_REQUEST_ALREADY_CANCELLED"},
            new String[] {amendedOne, "LEAVE_REQUEST_ALREADY_AMENDED"},
            new String[] {decidedOne, "LEAVE_REQUEST_ALREADY_DECIDED"})) {
      JsonNode refused =
          w.problem(
              decide(EXCEPTIONS, w.admin, terminal[0], Organizations.newKey(), body),
              409,
              terminal[1]);
      assertThat(refused.get("params").size()).isZero();
    }
    // Employees (the manager included), password-only administrators and another tenant's
    // administrator neither list nor decide.
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant())
                .subject("sub-leave41e-pwd-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    for (String bearer : List.of(manager.bearer(), alone.bearer(), passwordOnly)) {
      assertThat(w.call(queue(bearer)).getStatus()).isIn(401, 403);
      assertThat(
              w.call(decide(EXCEPTIONS, bearer, exception, Organizations.newKey(), body))
                  .getStatus())
          .isIn(401, 403);
    }
    assertThat(ids(w.expect(queue(other.admin), 200))).containsExactly(foreign);
    w.problem(
        decide(EXCEPTIONS, other.admin, exception, Organizations.newKey(), body),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
    assertThat(w.state(exception)).isEqualTo("PENDING");
    assertThat(w.overrides()).containsExactly(1, 1, 1);
    // The queue: this tenant's current exceptions only (the amendment's replacement included).
    List<String> current = ids(w.expect(queue(w.admin), 200));
    assertThat(current).contains(exception).doesNotContain(foreign, managedRequest, adminRouted);
    assertThat(current).hasSize(2);
  }

  @Test
  void cursorsStayWithTheirQueueCallerAndTenant() throws Exception {
    LeaveWorld w = world();
    LeaveWorld other = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    List<String> all = new ArrayList<>();
    for (String given : List.of("Amani", "Bahati", "Chance")) {
      Person p = w.person(given, "Mbuyi");
      all.add(w.request(p, managed, t.plusDays(10), t.plusDays(10)));
    }
    JsonNode first =
        w.expect(get(EXCEPTIONS).param("limit", "2").header("Authorization", w.admin), 200);
    String cursor = first.get("nextCursor").asText();
    JsonNode second =
        w.expect(
            get(EXCEPTIONS)
                .param("limit", "2")
                .param("cursor", cursor)
                .header("Authorization", w.admin),
            200);
    List<String> seen = new ArrayList<>(ids(first));
    seen.addAll(ids(second));
    assertThat(seen).containsExactlyInAnyOrderElementsOf(all);
    assertThat(second.get("nextCursor").isNull()).isTrue();
    for (MockHttpServletRequestBuilder misuse :
        List.of(
            get(EXCEPTIONS)
                .param("limit", "3")
                .param("cursor", cursor)
                .header("Authorization", w.admin),
            get(ADMIN_APPROVALS)
                .param("limit", "2")
                .param("cursor", cursor)
                .header("Authorization", w.admin),
            get(EXCEPTIONS)
                .param("limit", "2")
                .param("cursor", cursor)
                .header("Authorization", other.admin))) {
      w.problem(misuse, 400, "CURSOR_INVALID");
    }
  }

  // ------------------------------------------------------------------------------------------
  // Fail-closed disclosure (D41DE-4)
  // ------------------------------------------------------------------------------------------

  @Test
  void noExceptionPageIsReturnedWhenItsDisclosureAuditCannotBeWritten() throws Exception {
    LeaveWorld w = world();
    String managed = w.policy("MGR", "MANAGER");
    Person report = w.person("Bénédicte", "Mbuyi");
    w.request(report, managed, w.today.plusDays(5), w.today.plusDays(5));
    String function = "no_leave_exception_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "CREATE FUNCTION public."
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action ="
            + " 'leave-routing-exception.list' AND NEW.tenant_id = '"
            + w.tenant()
            + "' THEN RAISE EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + function
            + " BEFORE INSERT ON platform.audit_event FOR EACH ROW EXECUTE FUNCTION public."
            + function
            + "()");
    try {
      MockHttpServletResponse refused = w.call(queue(w.admin));
      assertThat(refused.getStatus()).isEqualTo(500);
      assertThat(refused.getContentAsString()).doesNotContain("Mbuyi").doesNotContain("PENDING");
    } finally {
      jdbc.execute("DROP TRIGGER " + function + " ON platform.audit_event");
      jdbc.execute("DROP FUNCTION public." + function + "()");
    }
    assertThat(w.expect(queue(w.admin), 200).get("items")).hasSize(1);
  }
}
