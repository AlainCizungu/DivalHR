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
import static com.divalhr.core.people.leave.LeaveWorld.withdraw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.leave.LeaveWorld.Person;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * MVP-041F (Issue #95) end to end against real PostgreSQL: an employee withdraws their own approved
 * future leave of either route; the approval decision stays unchanged and visible beside the
 * withdrawal; the dates are released; the approval inboxes never regain the request; the receipt is
 * minimal; audit, event, log and metric contents; ownership, tenant, link and role isolation;
 * exact, changed-body and post-relink replays; every non-approved state; the business-date window
 * in several time zones; validation without echo.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class MyLeaveWithdrawalIntegrationTest {

  private static final String FRENCH_REASON = "Mes projets ont changé, désolée !";
  private static final String ENGLISH_REASON = "My plans changed and I no longer need it.";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private MeterRegistry meters;

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

  private static List<String> fields(JsonNode problem) {
    List<String> fields = new ArrayList<>();
    problem
        .path("params")
        .path("fields")
        .forEach(f -> fields.add(f.get("field").asText() + ":" + f.get("constraint").asText()));
    return fields;
  }

  private static String logs(CapturedOutput output) {
    return String.join(
        "\n", output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp\"")).toList());
  }

  private static Map<String, Object> reason(String locale, String text) {
    return cancellation(locale, text);
  }

  private Map<String, JsonNode> history(LeaveWorld w, Person p) throws Exception {
    Map<String, JsonNode> byId = new LinkedHashMap<>();
    w.expect(get(MY_REQUESTS).header("Authorization", p.bearer()), 200)
        .get("items")
        .forEach(r -> byId.put(r.get("id").asText(), r));
    return byId;
  }

  /** The whole stored decision row of a request, as JSON text. */
  private String decisionRow(String requestId) {
    return jdbc.queryForObject(
        "SELECT to_jsonb(d)::text FROM people.leave_request_decision d"
            + " WHERE request_id = ?::uuid",
        String.class,
        requestId);
  }

  // ------------------------------------------------------------------------------------------
  // Withdraw, keep the approval, release, history, inboxes, audit, event, logs and metrics
  // ------------------------------------------------------------------------------------------

  @Test
  void anEmployeeWithdrawsTheirOwnApprovedFutureLeaveOfEitherRoute(CapturedOutput output)
      throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person manager = w.person("Josué", "Kabeya");
    Person report = w.person("Bénédicte", "Mbuyi-Ngalula");
    w.manage(report.employee(), manager.employee(), t);
    String toManager = w.request(report, managed, t.plusDays(10), t.plusDays(12));
    String toAdmins = w.request(report, central, t.plusDays(20), t.plusDays(20));
    String kept = w.request(report, central, t.plusDays(30), t.plusDays(30));
    w.approveAsManager(manager, toManager);
    w.approveAsAdmin(toAdmins);
    String managerApproval = decisionRow(toManager);
    String adminApproval = decisionRow(toAdmins);
    // Approved dates block another request.
    w.problem(
        LeaveWorld.submit(report.bearer(), central, t.plusDays(11), t.plusDays(11)),
        409,
        "LEAVE_REQUEST_OVERLAP");

    // The manager-routed approval, in French: 201, the receipt is minimal.
    String key = Organizations.newKey();
    MockHttpServletResponse done =
        w.call(withdraw(report.bearer(), toManager, key, reason("fr", FRENCH_REASON)));
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(201);
    assertThat(done.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(done.getHeader("Idempotent-Replayed")).isNull();
    assertThat(done.getContentAsString()).doesNotContain("projets ont");
    JsonNode receipt = JSON.readTree(done.getContentAsString());
    assertThat(fieldNames(receipt))
        .containsExactlyInAnyOrder("withdrawalId", "requestId", "state", "withdrawnAt");
    assertThat(receipt.get("requestId").asText()).isEqualTo(toManager);
    assertThat(receipt.get("state").asText()).isEqualTo("WITHDRAWN");
    assertThat(w.state(toManager)).isEqualTo("WITHDRAWN");
    // The administrator-routed approval, in English.
    w.expect(
        withdraw(report.bearer(), toAdmins, Organizations.newKey(), reason("en", ENGLISH_REASON)),
        201);
    assertThat(w.withdrawals()).containsExactly(2, 2, 2);
    // The approvals are untouched: same rows, same counts.
    assertThat(decisionRow(toManager)).isEqualTo(managerApproval);
    assertThat(decisionRow(toAdmins)).isEqualTo(adminApproval);
    assertThat(w.decisions()).containsExactly(2, 2, 2);

    // The dates are free again in the same commit; the inboxes never regain the requests.
    w.expect(LeaveWorld.submit(report.bearer(), central, t.plusDays(11), t.plusDays(11)), 201);
    assertThat(ids(w.expect(get(MY_APPROVALS).header("Authorization", manager.bearer()), 200)))
        .doesNotContain(toManager, toAdmins);
    assertThat(ids(w.expect(get(ADMIN_APPROVALS).header("Authorization", w.admin), 200)))
        .doesNotContain(toManager, toAdmins)
        .contains(kept);
    assertThat(ids(w.expect(get(EXCEPTIONS).header("Authorization", w.admin), 200)))
        .doesNotContain(toManager, toAdmins);

    // The history: WITHDRAWN, the approval and the withdrawal with their reasons, never an actor.
    MockHttpServletResponse mine =
        w.call(get(MY_REQUESTS).header("Authorization", report.bearer()));
    assertThat(mine.getContentAsString())
        .doesNotContain(report.subject())
        .doesNotContain(manager.subject())
        .doesNotContain(w.adminSubject)
        .doesNotContain("withdrawnBy")
        .doesNotContain("decisionAuthority")
        .doesNotContain("managerEmployeeId");
    JsonNode page = JSON.readTree(mine.getContentAsString());
    assertThat(page.get("asOf").asText()).isEqualTo(t.toString());
    Map<String, JsonNode> byId = history(w, report);
    JsonNode withdrawn = byId.get(toManager);
    assertThat(withdrawn.get("state").asText()).isEqualTo("WITHDRAWN");
    assertThat(withdrawn.get("decision").get("outcome").asText()).isEqualTo("APPROVED");
    assertThat(withdrawn.get("decision").get("reason").asText()).isEqualTo("Approved, enjoy.");
    JsonNode shown = withdrawn.get("withdrawal");
    assertThat(fieldNames(shown))
        .containsExactlyInAnyOrder("id", "reasonLocale", "reason", "withdrawnAt");
    assertThat(shown.get("id").asText()).isEqualTo(receipt.get("withdrawalId").asText());
    assertThat(shown.get("reasonLocale").asText()).isEqualTo("fr");
    assertThat(shown.get("reason").asText()).isEqualTo(FRENCH_REASON);
    assertThat(byId.get(toAdmins).get("withdrawal").get("reason").asText())
        .isEqualTo(ENGLISH_REASON);
    assertThat(byId.get(toAdmins).get("decision").get("reason").asText())
        .isEqualTo("Accordé, bon repos.");
    assertThat(byId.get(kept).get("withdrawal").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT withdrawn_by FROM people.leave_request_withdrawal"
                    + " WHERE request_id = ?::uuid",
                String.class,
                toManager))
        .isEqualTo(report.subject());

    // Audit: identifiers and states only.
    JsonNode metadata =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE action ="
                    + " 'leave-request.withdraw' AND resource_id = ?::uuid",
                String.class,
                toManager));
    assertThat(fieldNames(metadata))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "withdrawalId",
            "requestId",
            "employeeId",
            "employmentId",
            "policyId",
            "policyVersionId",
            "decisionId",
            "priorState",
            "resultingState");
    assertThat(metadata.get("priorState").asText()).isEqualTo("APPROVED");
    assertThat(metadata.get("resultingState").asText()).isEqualTo("WITHDRAWN");
    assertThat(metadata.get("decisionId").asText())
        .isEqualTo(JSON.readTree(managerApproval).get("id").asText());
    assertThat(
            jdbc.queryForObject(
                "SELECT after_state_sha256 FROM platform.audit_event WHERE action ="
                    + " 'leave-request.withdraw' AND resource_id = ?::uuid",
                String.class,
                toManager))
        .hasSize(64);
    // Event: identifiers and the transition only.
    JsonNode envelope =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?"
                    + " AND envelope ->> 'eventType' = 'people.leave-request.withdrawn.v1'",
                String.class,
                toManager));
    assertThat(fieldNames(envelope.get("data")))
        .containsExactlyInAnyOrder(
            "withdrawalId",
            "requestId",
            "employeeId",
            "employmentId",
            "policyId",
            "policyVersionId",
            "decisionId",
            "priorState",
            "resultingState");
    // No reason, locale, name or subject outside the withdrawal row and the employee's history.
    for (String text :
        List.of(
            envelope.toString(),
            metadata.toString(),
            String.join(
                "\n",
                jdbc.queryForList(
                    "SELECT response_body::text FROM platform.idempotency_record"
                        + " WHERE operation = 'leave-request.self-withdraw'",
                    String.class)))) {
      assertThat(text)
          .doesNotContain("projets ont")
          .doesNotContain("My plans")
          .doesNotContain("Mbuyi-Ngalula")
          .doesNotContain("Approved, enjoy")
          .doesNotContain(report.subject())
          .doesNotContain("\"fr\"")
          .doesNotContain(t.plusDays(10).toString());
    }
    assertThat(logs(output))
        .contains("leave_request_self_withdrawn")
        .doesNotContain("projets ont")
        .doesNotContain("My plans")
        .doesNotContain("Mbuyi-Ngalula")
        .doesNotContain(report.subject())
        .doesNotContain(key);
    List<String> tags = new ArrayList<>();
    for (Meter meter :
        meters
            .find(OperationMetrics.METRIC)
            .tag("operation", "leave-request.self-withdraw")
            .meters()) {
      meter.getId().getTags().forEach(tag -> tags.add(tag.getKey() + "=" + tag.getValue()));
    }
    assertThat(tags)
        .contains("operation=leave-request.self-withdraw", "outcome=created")
        .allMatch(tag -> tag.startsWith("operation=") || tag.startsWith("outcome="));
  }

  // ------------------------------------------------------------------------------------------
  // The window: strictly before the first day, on the organization's business date (D41F-1)
  // ------------------------------------------------------------------------------------------

  @Test
  void leaveStartingTodayCannotBeWithdrawnAndTheDateIsTheOrganizations() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Aline", "Tshibanda");
    String today = w.request(p, central, t, t);
    String tomorrow = w.request(p, central, t.plusDays(1), t.plusDays(1));
    w.approveAsAdmin(today);
    w.approveAsAdmin(tomorrow);
    JsonNode closed =
        w.problem(
            withdraw(p.bearer(), today, Organizations.newKey(), reason("fr", FRENCH_REASON)),
            409,
            "LEAVE_REQUEST_WITHDRAWAL_WINDOW_CLOSED");
    assertThat(closed.get("params").size()).isZero();
    assertThat(w.state(today)).isEqualTo("APPROVED");
    w.expect(withdraw(p.bearer(), tomorrow, Organizations.newKey(), reason("fr", "Annulé.")), 201);

    // One first day; the organization's zone decides whether it has been reached. Kiritimati
    // (UTC+14) is always at least one calendar day ahead of Pago Pago (UTC-11).
    ZoneId ahead = ZoneId.of("Pacific/Kiritimati");
    ZoneId behind = ZoneId.of("Pacific/Pago_Pago");
    LocalDate first = LocalDate.now(ahead);
    assertThat(LocalDate.now(behind)).isBefore(first);
    Person q = w.person("Grâce", "Lukusa");
    String zoned = w.request(q, central, first, first);
    w.approveAsAdmin(zoned);
    jdbc.update(
        "UPDATE tenant.organization SET timezone = ? WHERE id = ?", ahead.getId(), w.tenant());
    w.problem(
        withdraw(q.bearer(), zoned, Organizations.newKey(), reason("en", ENGLISH_REASON)),
        409,
        "LEAVE_REQUEST_WITHDRAWAL_WINDOW_CLOSED");
    assertThat(
            w.expect(get(MY_REQUESTS).header("Authorization", q.bearer()), 200)
                .get("asOf")
                .asText())
        .isEqualTo(first.toString());
    jdbc.update(
        "UPDATE tenant.organization SET timezone = ? WHERE id = ?", behind.getId(), w.tenant());
    w.expect(
        withdraw(q.bearer(), zoned, Organizations.newKey(), reason("en", ENGLISH_REASON)), 201);
    assertThat(w.state(zoned)).isEqualTo("WITHDRAWN");
    assertThat(w.withdrawals()).containsExactly(2, 2, 2);
  }

  // ------------------------------------------------------------------------------------------
  // Ownership, tenant, link and role (D41F-1)
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheLinkedOwnerWithdrawsAndEveryOtherCallerLearnsNothing() throws Exception {
    LeaveWorld w = world();
    LeaveWorld other = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person owner = w.person("Bénédicte", "Mbuyi");
    Person colleague = w.person("Aline", "Tshibanda");
    Person manager = w.person("Josué", "Kabeya");
    w.manage(owner.employee(), manager.employee(), t);
    String mine = w.request(owner, managed, t.plusDays(5), t.plusDays(5));
    w.approveAsManager(manager, mine);
    String foreignPolicy = other.policy("ADM", "TENANT_ADMIN");
    Person foreigner = other.person("Grâce", "Lukusa");
    String foreign = other.request(foreigner, foreignPolicy, t.plusDays(5), t.plusDays(5));
    other.approveAsAdmin(foreign);
    Map<String, Object> body = reason("en", "Not needed any more.");

    for (String[] attempt :
        List.of(
            new String[] {colleague.bearer(), mine},
            new String[] {manager.bearer(), mine},
            new String[] {owner.bearer(), foreign},
            new String[] {foreigner.bearer(), mine},
            new String[] {owner.bearer(), UUID.randomUUID().toString()},
            new String[] {owner.bearer(), "not-a-uuid"})) {
      JsonNode refused =
          w.problem(
              withdraw(attempt[0], attempt[1], Organizations.newKey(), body),
              404,
              "LEAVE_REQUEST_NOT_FOUND");
      assertThat(refused.get("params").size()).isZero();
    }
    String unlinked = UUID.randomUUID().toString();
    w.member(unlinked);
    w.problem(
        withdraw(
            LeaveWorld.employeeBearer(w.tenant(), unlinked), mine, Organizations.newKey(), body),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    for (String bearer :
        List.of(LeaveWorld.employeeBearer(w.tenant(), UUID.randomUUID().toString()), w.admin)) {
      w.problem(withdraw(bearer, mine, Organizations.newKey(), body), 403, "ACCESS_DENIED");
    }
    assertThat(
            w.call(
                    MockMvcRequestBuilders.post(MY_REQUESTS + "/" + mine + "/withdrawal")
                        .header("Idempotency-Key", Organizations.newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .getStatus())
        .isEqualTo(401);
    assertThat(w.state(mine)).isEqualTo("APPROVED");
    assertThat(other.state(foreign)).isEqualTo("APPROVED");
    assertThat(w.withdrawals()).containsExactly(0, 0, 0);
    assertThat(other.withdrawals()).containsExactly(0, 0, 0);
    List<String> reasons = new ArrayList<>();
    for (Meter meter :
        meters
            .find(ScopeAuthorizationInterceptor.SELF_SERVICE_DENIALS)
            .tag("operation", "leave-request.self-withdraw")
            .meters()) {
      reasons.add(meter.getId().getTag("reason"));
    }
    assertThat(reasons).contains("not_found", "link_required");
  }

  // ------------------------------------------------------------------------------------------
  // Replay (D41F-1, D41F-3)
  // ------------------------------------------------------------------------------------------

  @Test
  void replaysFollowTheCurrentLinkBeforeAnyStoredReceiptIsRead() throws Exception {
    LeaveWorld w = world();
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person owner = w.person("Bénédicte", "Mbuyi");
    String id = w.request(owner, central, w.today.plusDays(5), w.today.plusDays(5));
    w.approveAsAdmin(id);
    Map<String, Object> body = reason("fr", FRENCH_REASON);
    String key = Organizations.newKey();
    JsonNode receipt = w.expect(withdraw(owner.bearer(), id, key, body), 201);

    MockHttpServletResponse replay = w.call(withdraw(owner.bearer(), id, key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(receipt);
    // A changed body under the key is refused first, before any state rule.
    w.problem(
        withdraw(owner.bearer(), id, key, reason("en", FRENCH_REASON)),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    JsonNode again =
        w.problem(
            withdraw(owner.bearer(), id, Organizations.newKey(), body),
            409,
            "LEAVE_REQUEST_ALREADY_WITHDRAWN");
    assertThat(again.get("params").size()).isZero();

    w.unlink(owner.employee());
    MockHttpServletResponse noLink = w.call(withdraw(owner.bearer(), id, key, body));
    assertThat(noLink.getStatus()).isEqualTo(403);
    assertThat(JSON.readTree(noLink.getContentAsString()).get("code").asText())
        .isEqualTo("EMPLOYEE_LINK_REQUIRED");
    assertThat(noLink.getContentAsString()).doesNotContain(receipt.get("withdrawalId").asText());
    UUID another = w.hire("Grâce", "Lukusa");
    w.linkTo(another, owner.membership());
    MockHttpServletResponse relinked = w.call(withdraw(owner.bearer(), id, key, body));
    assertThat(relinked.getStatus()).isEqualTo(404);
    assertThat(JSON.readTree(relinked.getContentAsString()).get("code").asText())
        .isEqualTo("LEAVE_REQUEST_NOT_FOUND");
    assertThat(relinked.getHeader("Idempotent-Replayed")).isNull();
    assertThat(relinked.getContentAsString()).doesNotContain(receipt.get("withdrawalId").asText());
    w.unlink(another);
    w.linkTo(owner.employee(), owner.membership());
    MockHttpServletResponse back = w.call(withdraw(owner.bearer(), id, key, body));
    assertThat(back.getStatus()).isEqualTo(201);
    assertThat(back.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(w.withdrawals()).containsExactly(1, 1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // Every other state, and what a withdrawn request refuses (D41F-3)
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyApprovedLeaveIsWithdrawnAndWithdrawnLeaveNeverMovesAgain() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Bénédicte", "Mbuyi");
    Person q = w.person("Aline", "Tshibanda");
    String pending = w.request(p, central, t.plusDays(3), t.plusDays(3));
    String rejected = w.request(p, central, t.plusDays(5), t.plusDays(5));
    String cancelled = w.request(p, central, t.plusDays(7), t.plusDays(7));
    String amended = w.request(q, central, t.plusDays(9), t.plusDays(9));
    w.expect(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            rejected,
            Organizations.newKey(),
            decision("REJECTED", "en", "Not this week.")),
        200);
    w.expect(
        cancel(p.bearer(), cancelled, Organizations.newKey(), cancellation("fr", "Plus besoin.")),
        200);
    w.expect(
        amend(
            q.bearer(),
            amended,
            Organizations.newKey(),
            amendment(central, t.plusDays(11), t.plusDays(11), 1, "fr", "Autres dates.")),
        201);
    Map<String, Object> body = reason("fr", FRENCH_REASON);
    for (String[] attempt :
        List.of(
            new String[] {p.bearer(), pending, "LEAVE_REQUEST_NOT_APPROVED"},
            new String[] {p.bearer(), rejected, "LEAVE_REQUEST_ALREADY_DECIDED"},
            new String[] {p.bearer(), cancelled, "LEAVE_REQUEST_ALREADY_CANCELLED"},
            new String[] {q.bearer(), amended, "LEAVE_REQUEST_ALREADY_AMENDED"})) {
      JsonNode refused =
          w.problem(
              withdraw(attempt[0], attempt[1], Organizations.newKey(), body), 409, attempt[2]);
      assertThat(refused.get("params").size()).isZero();
    }
    assertThat(w.state(pending)).isEqualTo("PENDING");
    assertThat(w.state(rejected)).isEqualTo("REJECTED");

    // A withdrawn request is never cancelled, amended, decided or withdrawn again.
    Person r = w.person("Grâce", "Lukusa");
    String withdrawn = w.request(r, central, t.plusDays(20), t.plusDays(20));
    w.approveAsAdmin(withdrawn);
    w.expect(withdraw(r.bearer(), withdrawn, Organizations.newKey(), body), 201);
    w.problem(
        cancel(r.bearer(), withdrawn, Organizations.newKey(), cancellation("fr", "Plus besoin.")),
        409,
        "LEAVE_REQUEST_ALREADY_WITHDRAWN");
    w.problem(
        amend(
            r.bearer(),
            withdrawn,
            Organizations.newKey(),
            amendment(central, t.plusDays(22), t.plusDays(22), 1, "fr", "Autres dates.")),
        409,
        "LEAVE_REQUEST_ALREADY_WITHDRAWN");
    w.problem(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            withdrawn,
            Organizations.newKey(),
            decision("REJECTED", "en", "Too late.")),
        409,
        "LEAVE_REQUEST_ALREADY_DECIDED");
    w.problem(
        withdraw(r.bearer(), withdrawn, Organizations.newKey(), body),
        409,
        "LEAVE_REQUEST_ALREADY_WITHDRAWN");
    assertThat(w.state(withdrawn)).isEqualTo("WITHDRAWN");
    assertThat(w.withdrawals()).containsExactly(1, 1, 1);
    assertThat(w.decisions()).containsExactly(2, 2, 2);
  }

  // ------------------------------------------------------------------------------------------
  // Validation (the decision-reason grammar version 1, shared)
  // ------------------------------------------------------------------------------------------

  @Test
  void withdrawalsAreValidatedWithoutEchoingTheReason() throws Exception {
    LeaveWorld w = world();
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Bénédicte", "Mbuyi");
    Person q = w.person("Aline", "Tshibanda");
    String id = w.request(p, central, w.today.plusDays(3), w.today.plusDays(3));
    w.approveAsAdmin(id);

    record Bad(Object body, String expected) {}
    int attempt = 0;
    Map<String, Object> extra = new LinkedHashMap<>(reason("en", "Fine reason"));
    extra.put("state", "WITHDRAWN");
    for (Bad bad :
        List.of(
            new Bad(reason("en", null), "reason:REQUIRED"),
            new Bad(reason("en", "x"), "reason:LENGTH"),
            new Bad(reason("en", "secret\nreason"), "reason:FORMAT"),
            new Bad(reason("en", "secret\u202Ereason"), "reason:FORMAT"),
            new Bad(reason("de", "Fine reason"), "reasonLocale:FORMAT"),
            new Bad(reason(null, "Fine reason"), "reasonLocale:REQUIRED"),
            new Bad(extra, "body:UNKNOWN_PROPERTY"))) {
      String bearer = attempt++ % 2 == 0 ? p.bearer() : q.bearer();
      JsonNode problem =
          w.problem(
              withdraw(bearer, id, Organizations.newKey(), bad.body()), 400, "VALIDATION_FAILED");
      assertThat(fields(problem)).as("%s", bad.expected()).containsExactly(bad.expected());
      assertThat(problem.toString()).doesNotContain("secret");
    }
    JsonNode noKey =
        w.problem(withdraw(p.bearer(), id, null, reason("en", "Fine")), 400, "VALIDATION_FAILED");
    assertThat(fields(noKey)).containsExactly("Idempotency-Key:REQUIRED");
    assertThat(w.withdrawals()).containsExactly(0, 0, 0);
    assertThat(w.state(id)).isEqualTo("APPROVED");
    // Normalized like every leave reason: NFC and trimmed.
    w.expect(
        withdraw(p.bearer(), id, Organizations.newKey(), reason("fr", " Cafe\u0301 fermé ")), 201);
    assertThat(
            jdbc.queryForObject(
                "SELECT reason_text FROM people.leave_request_withdrawal"
                    + " WHERE request_id = ?::uuid",
                String.class,
                id))
        .isEqualTo("Café fermé");
  }
}
