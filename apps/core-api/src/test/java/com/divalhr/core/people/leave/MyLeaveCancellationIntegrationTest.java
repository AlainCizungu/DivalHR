package com.divalhr.core.people.leave;

import static com.divalhr.core.people.leave.LeaveWorld.ADMIN_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.JSON;
import static com.divalhr.core.people.leave.LeaveWorld.MY_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.MY_REQUESTS;
import static com.divalhr.core.people.leave.LeaveWorld.cancel;
import static com.divalhr.core.people.leave.LeaveWorld.cancellation;
import static com.divalhr.core.people.leave.LeaveWorld.decide;
import static com.divalhr.core.people.leave.LeaveWorld.decision;
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

/**
 * MVP-041C (Issue #91) end to end against real PostgreSQL: an employee cancels their own pending
 * requests of either route, before or after their first day; the request leaves the approval
 * inboxes and releases its dates; the receipt is minimal; the history shows the cancellation and
 * its reason but never the actor; audit, event, log and metric contents; ownership, tenant, link
 * and role isolation; exact, changed-body and post-relink replays; decided and cancelled requests;
 * validation without echo.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class MyLeaveCancellationIntegrationTest {

  private static final String FRENCH_REASON = "Mes dates ont changé, désolée !";
  private static final String ENGLISH_REASON = "Family plans moved to next month.";

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

  private Map<String, JsonNode> history(LeaveWorld w, Person p) throws Exception {
    Map<String, JsonNode> byId = new LinkedHashMap<>();
    w.expect(get(MY_REQUESTS).header("Authorization", p.bearer()), 200)
        .get("items")
        .forEach(r -> byId.put(r.get("id").asText(), r));
    return byId;
  }

  /** A pending request that started in the past (the API only accepts future starts). */
  private String pastPending(String model, LocalDate start, LocalDate end) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO people.leave_request (id, tenant_id, employee_id, employment_id,"
            + " policy_version_id, start_date, end_date, requested_amount, state, submitted_at,"
            + " submitted_by) SELECT ?, tenant_id, employee_id, employment_id, policy_version_id,"
            + " ?, ?, requested_amount, 'PENDING', now(), submitted_by FROM people.leave_request"
            + " WHERE id = ?::uuid",
        id,
        start,
        end,
        model);
    return id.toString();
  }

  // ------------------------------------------------------------------------------------------
  // Cancel, release, history, audit, event, logs and metrics (AC1-AC3, AC6, AC7)
  // ------------------------------------------------------------------------------------------

  @Test
  void anEmployeeCancelsTheirOwnPendingRequestsOfEitherRoute(CapturedOutput output)
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
    assertThat(ids(w.expect(get(MY_APPROVALS).header("Authorization", manager.bearer()), 200)))
        .containsExactly(toManager);
    assertThat(ids(w.expect(get(ADMIN_APPROVALS).header("Authorization", w.admin), 200)))
        .containsExactlyInAnyOrder(toAdmins, kept);
    // Pending dates block another request.
    w.problem(
        LeaveWorld.submit(report.bearer(), central, t.plusDays(11), t.plusDays(11)),
        409,
        "LEAVE_REQUEST_OVERLAP");

    // The manager-routed request, in French: the receipt is minimal and never carries the reason.
    String key = Organizations.newKey();
    MockHttpServletResponse done =
        w.call(cancel(report.bearer(), toManager, key, cancellation("fr", FRENCH_REASON)));
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
    assertThat(done.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(done.getHeader("Idempotent-Replayed")).isNull();
    assertThat(done.getContentAsString()).doesNotContain("dates ont");
    JsonNode receipt = JSON.readTree(done.getContentAsString());
    assertThat(fieldNames(receipt))
        .containsExactlyInAnyOrder("requestId", "cancellationId", "state", "cancelledAt");
    assertThat(receipt.get("requestId").asText()).isEqualTo(toManager);
    assertThat(receipt.get("state").asText()).isEqualTo("CANCELLED");
    assertThat(w.state(toManager)).isEqualTo("CANCELLED");
    // The admin-routed request, in English.
    w.expect(
        cancel(
            report.bearer(), toAdmins, Organizations.newKey(), cancellation("en", ENGLISH_REASON)),
        200);
    assertThat(w.cancellations()).containsExactly(2, 2, 2);
    assertThat(w.decisions()).containsExactly(0, 0, 0);

    // Both left their inboxes; the dates are free again in the same commit.
    assertThat(ids(w.expect(get(MY_APPROVALS).header("Authorization", manager.bearer()), 200)))
        .isEmpty();
    assertThat(ids(w.expect(get(ADMIN_APPROVALS).header("Authorization", w.admin), 200)))
        .containsExactly(kept);
    w.expect(LeaveWorld.submit(report.bearer(), managed, t.plusDays(10), t.plusDays(12)), 201);
    w.expect(LeaveWorld.submit(report.bearer(), central, t.plusDays(20), t.plusDays(20)), 201);

    // The history: CANCELLED with the reason as written, never who cancelled.
    MockHttpServletResponse mine =
        w.call(get(MY_REQUESTS).header("Authorization", report.bearer()));
    assertThat(mine.getContentAsString())
        .doesNotContain(report.subject())
        .doesNotContain("cancelledBy")
        .doesNotContain("cancelled_by");
    Map<String, JsonNode> byId = history(w, report);
    JsonNode cancelled = byId.get(toManager);
    assertThat(cancelled.get("state").asText()).isEqualTo("CANCELLED");
    assertThat(cancelled.get("decision").isNull()).isTrue();
    JsonNode shown = cancelled.get("cancellation");
    assertThat(fieldNames(shown))
        .containsExactlyInAnyOrder("id", "reasonLocale", "reason", "cancelledAt");
    assertThat(shown.get("id").asText()).isEqualTo(receipt.get("cancellationId").asText());
    assertThat(shown.get("reasonLocale").asText()).isEqualTo("fr");
    assertThat(shown.get("reason").asText()).isEqualTo(FRENCH_REASON);
    assertThat(byId.get(toAdmins).get("cancellation").get("reason").asText())
        .isEqualTo(ENGLISH_REASON);
    assertThat(byId.get(kept).get("cancellation").isNull()).isTrue();
    // The row keeps the unchanged verified subject.
    assertThat(
            jdbc.queryForObject(
                "SELECT cancelled_by FROM people.leave_request_cancellation"
                    + " WHERE request_id = ?::uuid",
                String.class,
                toManager))
        .isEqualTo(report.subject());

    // Audit: identifiers and states only; the digest covers the whole cancelled state.
    JsonNode metadata =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE action ="
                    + " 'leave-request.cancel' AND resource_id = ?::uuid",
                String.class,
                toManager));
    assertThat(fieldNames(metadata))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "requestId",
            "cancellationId",
            "employeeId",
            "employmentId",
            "policyId",
            "policyVersionId",
            "priorState",
            "resultingState");
    assertThat(metadata.get("priorState").asText()).isEqualTo("PENDING");
    assertThat(metadata.get("resultingState").asText()).isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT after_state_sha256 FROM platform.audit_event WHERE action ="
                    + " 'leave-request.cancel' AND resource_id = ?::uuid",
                String.class,
                toManager))
        .hasSize(64);
    // Event: identifiers and the resulting state only.
    JsonNode envelope =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?"
                    + " AND envelope ->> 'eventType' = 'people.leave-request.cancelled.v1'",
                String.class,
                toManager));
    assertThat(fieldNames(envelope.get("data")))
        .containsExactlyInAnyOrder(
            "requestId",
            "cancellationId",
            "employeeId",
            "employmentId",
            "policyId",
            "policyVersionId",
            "state");
    assertThat(envelope.get("data").get("state").asText()).isEqualTo("CANCELLED");
    // No reason, locale, name or subject outside the cancellation row and the employee's history.
    for (String text :
        List.of(
            envelope.toString(),
            metadata.toString(),
            String.join(
                "\n",
                jdbc.queryForList(
                    "SELECT response_body::text FROM platform.idempotency_record"
                        + " WHERE operation = 'leave-request.self-cancel'",
                    String.class)))) {
      assertThat(text)
          .doesNotContain("dates ont")
          .doesNotContain("Family plans")
          .doesNotContain("Mbuyi-Ngalula")
          .doesNotContain(report.subject());
    }
    assertThat(logs(output))
        .contains("leave_request_self_cancelled")
        .doesNotContain("dates ont")
        .doesNotContain("Family plans")
        .doesNotContain("Mbuyi-Ngalula")
        .doesNotContain(report.subject())
        .doesNotContain(key);
    // Metrics: the fixed operation name and outcome only.
    List<String> tags = new ArrayList<>();
    for (Meter meter :
        meters
            .find(OperationMetrics.METRIC)
            .tag("operation", "leave-request.self-cancel")
            .meters()) {
      meter.getId().getTags().forEach(tag -> tags.add(tag.getKey() + "=" + tag.getValue()));
    }
    assertThat(tags)
        .contains("operation=leave-request.self-cancel", "outcome=updated")
        .allMatch(tag -> tag.startsWith("operation=") || tag.startsWith("outcome="));
  }

  @Test
  void aPendingRequestIsCancelledBeforeOnOrAfterItsFirstDay() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Aline", "Tshibanda");
    Person q = w.person("Grâce", "Lukusa");
    // On its first day, in the future, already ended, and started but not ended.
    String today = w.request(p, central, t, t);
    String future = w.request(p, central, t.plusDays(40), t.plusDays(41));
    String ended = pastPending(today, LeaveWorld.HIRED.plusDays(1), LeaveWorld.HIRED.plusDays(2));
    String model = w.request(q, central, t.plusDays(60), t.plusDays(60));
    String ongoing = pastPending(model, LeaveWorld.HIRED.plusDays(10), t.plusDays(20));
    for (String[] target :
        List.of(
            new String[] {p.bearer(), today},
            new String[] {p.bearer(), future},
            new String[] {p.bearer(), ended},
            new String[] {q.bearer(), ongoing})) {
      w.expect(
          cancel(target[0], target[1], Organizations.newKey(), cancellation("fr", "Plus besoin.")),
          200);
      assertThat(w.state(target[1])).isEqualTo("CANCELLED");
    }
    assertThat(w.cancellations()).containsExactly(4, 4, 4);
  }

  // ------------------------------------------------------------------------------------------
  // Ownership, tenant, link and role (D41C-1; AC1, AC5)
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheLinkedOwnerCancelsAndEveryOtherCallerLearnsNothing() throws Exception {
    LeaveWorld w = world();
    LeaveWorld other = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    Person owner = w.person("Bénédicte", "Mbuyi");
    Person colleague = w.person("Aline", "Tshibanda");
    Person manager = w.person("Josué", "Kabeya");
    w.manage(owner.employee(), manager.employee(), t);
    String mine = w.request(owner, managed, t.plusDays(5), t.plusDays(5));
    String foreignPolicy = other.policy("ADM", "TENANT_ADMIN");
    Person foreigner = other.person("Grâce", "Lukusa");
    String foreign = other.request(foreigner, foreignPolicy, t.plusDays(5), t.plusDays(5));
    Map<String, Object> body = cancellation("en", "Not needed any more.");

    // Another employee (even the request's manager), another tenant's request, unknown, malformed.
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
              cancel(attempt[0], attempt[1], Organizations.newKey(), body),
              404,
              "LEAVE_REQUEST_NOT_FOUND");
      assertThat(refused.get("params").size()).isZero();
    }
    // No link; no membership; the tenant-admin role; no token.
    String unlinked = UUID.randomUUID().toString();
    w.member(unlinked);
    w.problem(
        cancel(LeaveWorld.employeeBearer(w.tenant(), unlinked), mine, Organizations.newKey(), body),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    for (String bearer :
        List.of(LeaveWorld.employeeBearer(w.tenant(), UUID.randomUUID().toString()), w.admin)) {
      w.problem(cancel(bearer, mine, Organizations.newKey(), body), 403, "ACCESS_DENIED");
    }
    assertThat(
            w.call(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                            MY_REQUESTS + "/" + mine + "/cancellation")
                        .header("Idempotency-Key", Organizations.newKey())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .getStatus())
        .isEqualTo(401);
    assertThat(w.state(mine)).isEqualTo("PENDING");
    assertThat(other.state(foreign)).isEqualTo("PENDING");
    assertThat(w.cancellations()).containsExactly(0, 0, 0);
    assertThat(other.cancellations()).containsExactly(0, 0, 0);
    // Self-service denials are counted on the bounded counter with fixed reasons only.
    List<String> reasons = new ArrayList<>();
    for (Meter meter :
        meters
            .find(ScopeAuthorizationInterceptor.SELF_SERVICE_DENIALS)
            .tag("operation", "leave-request.self-cancel")
            .meters()) {
      reasons.add(meter.getId().getTag("reason"));
    }
    assertThat(reasons).contains("not_found", "link_required");
  }

  // ------------------------------------------------------------------------------------------
  // Replay, decided and cancelled requests (D41C-3, D41C-4; AC2, AC4, AC5)
  // ------------------------------------------------------------------------------------------

  @Test
  void replaysFollowTheCurrentLinkBeforeAnyStoredReceiptIsRead() throws Exception {
    LeaveWorld w = world();
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person owner = w.person("Bénédicte", "Mbuyi");
    String cancelled = w.request(owner, central, w.today.plusDays(5), w.today.plusDays(5));
    Map<String, Object> body = cancellation("fr", FRENCH_REASON);
    String key = Organizations.newKey();
    JsonNode receipt = w.expect(cancel(owner.bearer(), cancelled, key, body), 200);

    // Exact replay; changed body under the key (before any state rule); another key.
    MockHttpServletResponse replay = w.call(cancel(owner.bearer(), cancelled, key, body));
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(receipt);
    w.problem(
        cancel(owner.bearer(), cancelled, key, cancellation("en", FRENCH_REASON)),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    JsonNode again =
        w.problem(
            cancel(owner.bearer(), cancelled, Organizations.newKey(), body),
            409,
            "LEAVE_REQUEST_ALREADY_CANCELLED");
    assertThat(again.get("params").size()).isZero();

    // Unlinked: the replay is refused before any stored body is read.
    w.unlink(owner.employee());
    MockHttpServletResponse noLink = w.call(cancel(owner.bearer(), cancelled, key, body));
    assertThat(noLink.getStatus()).isEqualTo(403);
    assertThat(JSON.readTree(noLink.getContentAsString()).get("code").asText())
        .isEqualTo("EMPLOYEE_LINK_REQUIRED");
    assertThat(noLink.getContentAsString()).doesNotContain(receipt.get("cancellationId").asText());
    // Relinked to another employee: no stored receipt or identifier.
    UUID another = w.hire("Grâce", "Lukusa");
    w.linkTo(another, owner.membership());
    MockHttpServletResponse relinked = w.call(cancel(owner.bearer(), cancelled, key, body));
    assertThat(relinked.getStatus()).isEqualTo(404);
    assertThat(JSON.readTree(relinked.getContentAsString()).get("code").asText())
        .isEqualTo("LEAVE_REQUEST_NOT_FOUND");
    assertThat(relinked.getHeader("Idempotent-Replayed")).isNull();
    assertThat(relinked.getContentAsString())
        .doesNotContain(receipt.get("cancellationId").asText());
    // Linked back to the owner: the replay is served again.
    w.unlink(another);
    w.linkTo(owner.employee(), owner.membership());
    MockHttpServletResponse back = w.call(cancel(owner.bearer(), cancelled, key, body));
    assertThat(back.getStatus()).isEqualTo(200);
    assertThat(back.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(w.cancellations()).containsExactly(1, 1, 1);
  }

  @Test
  void decidedRequestsCannotBeCancelledAndCancelledRequestsCannotBeDecided() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    String managed = w.policy("MGR", "MANAGER");
    Person owner = w.person("Bénédicte", "Mbuyi");
    Person manager = w.person("Josué", "Kabeya");
    w.manage(owner.employee(), manager.employee(), t);
    String cancelled = w.request(owner, managed, t.plusDays(5), t.plusDays(5));
    String cancelledCentral = w.request(owner, central, t.plusDays(7), t.plusDays(7));
    String approved = w.request(owner, managed, t.plusDays(10), t.plusDays(10));
    String rejected = w.request(owner, central, t.plusDays(15), t.plusDays(15));
    Map<String, Object> body = cancellation("fr", FRENCH_REASON);
    for (String id : List.of(cancelled, cancelledCentral)) {
      w.expect(cancel(owner.bearer(), id, Organizations.newKey(), body), 200);
    }
    // A cancelled request cannot be decided, by its manager or an administrator.
    w.problem(
        decide(
            MY_APPROVALS,
            manager.bearer(),
            cancelled,
            Organizations.newKey(),
            decision("APPROVED", "fr", "Accordé.")),
        409,
        "LEAVE_REQUEST_ALREADY_DECIDED");
    w.problem(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            cancelledCentral,
            Organizations.newKey(),
            decision("REJECTED", "en", "Not this week.")),
        409,
        "LEAVE_REQUEST_ALREADY_DECIDED");
    // Decided requests cannot be cancelled.
    w.expect(
        decide(
            MY_APPROVALS,
            manager.bearer(),
            approved,
            Organizations.newKey(),
            decision("APPROVED", "fr", "Accordé.")),
        200);
    w.expect(
        decide(
            ADMIN_APPROVALS,
            w.admin,
            rejected,
            Organizations.newKey(),
            decision("REJECTED", "en", "Not this week.")),
        200);
    for (String id : List.of(approved, rejected)) {
      JsonNode decided =
          w.problem(
              cancel(owner.bearer(), id, Organizations.newKey(), body),
              409,
              "LEAVE_REQUEST_ALREADY_DECIDED");
      assertThat(decided.get("params").size()).isZero();
    }
    assertThat(w.state(approved)).isEqualTo("APPROVED");
    assertThat(w.state(rejected)).isEqualTo("REJECTED");
    assertThat(w.cancellations()).containsExactly(2, 2, 2);
    assertThat(w.decisions()).containsExactly(2, 2, 2);
  }

  // ------------------------------------------------------------------------------------------
  // Validation (D41C-3; the decision-reason grammar version 1, shared)
  // ------------------------------------------------------------------------------------------

  @Test
  void cancellationsAreValidatedWithoutEchoingTheReason() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Bénédicte", "Mbuyi");
    // Validation runs before ownership; a second subject keeps both under the per-subject limit.
    Person q = w.person("Aline", "Tshibanda");
    String id = w.request(p, central, t.plusDays(3), t.plusDays(3));

    record Bad(Object body, String expected) {}
    int attempt = 0;
    Map<String, Object> extra = new LinkedHashMap<>(cancellation("en", "Fine reason"));
    extra.put("employeeId", p.employee().toString());
    for (Bad bad :
        List.of(
            new Bad(cancellation("en", null), "reason:REQUIRED"),
            new Bad(cancellation("en", " \u3000 "), "reason:REQUIRED"),
            new Bad(cancellation("en", "x"), "reason:LENGTH"),
            new Bad(cancellation("en", "secret-reason-" + "é".repeat(487)), "reason:LENGTH"),
            new Bad(cancellation("en", "secret\u0007reason"), "reason:FORMAT"),
            new Bad(cancellation("en", "secret\nreason"), "reason:FORMAT"),
            new Bad(cancellation("en", "secret\u200Breason"), "reason:FORMAT"),
            new Bad(cancellation("en", "secret\u202Ereason"), "reason:FORMAT"),
            new Bad(cancellation("de", "Fine reason"), "reasonLocale:FORMAT"),
            new Bad(cancellation(null, "Fine reason"), "reasonLocale:REQUIRED"),
            new Bad(Map.of("reasonLocale", "en", "reason", 42), "reason:FORMAT"),
            new Bad(extra, "body:UNKNOWN_PROPERTY"))) {
      String bearer = attempt++ % 2 == 0 ? p.bearer() : q.bearer();
      JsonNode problem =
          w.problem(
              cancel(bearer, id, Organizations.newKey(), bad.body()), 400, "VALIDATION_FAILED");
      assertThat(fields(problem)).as("%s", bad.expected()).containsExactly(bad.expected());
      assertThat(problem.toString()).doesNotContain("secret");
    }
    JsonNode noKey =
        w.problem(
            cancel(p.bearer(), id, null, cancellation("en", "Fine")), 400, "VALIDATION_FAILED");
    assertThat(fields(noKey)).containsExactly("Idempotency-Key:REQUIRED");
    assertThat(w.cancellations()).containsExactly(0, 0, 0);
    assertThat(w.state(id)).isEqualTo("PENDING");

    // NFC, trimmed, supplementary characters count once: 500 code points are accepted.
    String composed = "Cafe\u0301 " + "😀".repeat(495);
    w.expect(
        cancel(
            p.bearer(),
            id,
            Organizations.newKey(),
            cancellation("fr", " \u00A0" + composed + "\u2028 ")),
        200);
    String stored =
        jdbc.queryForObject(
            "SELECT reason_text FROM people.leave_request_cancellation WHERE request_id = ?::uuid",
            String.class,
            id);
    assertThat(stored).isEqualTo("Café " + "😀".repeat(495));
    assertThat(stored.codePointCount(0, stored.length())).isEqualTo(500);
  }
}
