package com.divalhr.core.people.leave;

import static com.divalhr.core.people.leave.LeaveWorld.ADMIN_APPROVALS;
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
 * MVP-041D (Issue #92) end to end against real PostgreSQL: an employee replaces their own pending
 * request; the original becomes AMENDED and a PENDING replacement, which passes every current
 * submission rule, takes its place in one transaction; only the original is excluded from the
 * overlap; the history links both with the reason but never the actor; the receipt is minimal;
 * audit, event, log and metric contents; ownership, tenant, link and role isolation; exact,
 * changed-body and post-relink replays; terminal-state refusals and chains; validation without
 * echo.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class MyLeaveAmendmentIntegrationTest {

  private static final String FRENCH_REASON = "Mes dates de voyage ont changé.";
  private static final String ENGLISH_REASON = "My travel dates changed.";

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

  // ------------------------------------------------------------------------------------------
  // Replace, release, history, audit, event, logs and metrics (AC1, AC2, AC7, AC8)
  // ------------------------------------------------------------------------------------------

  @Test
  void anEmployeeReplacesTheirOwnPendingRequestAtomically(CapturedOutput output) throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String managed = w.policy("MGR", "MANAGER");
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person manager = w.person("Josué", "Kabeya");
    Person report = w.person("Bénédicte", "Mbuyi-Ngalula");
    w.manage(report.employee(), manager.employee(), t);
    String original = w.request(report, managed, t.plusDays(10), t.plusDays(12));
    String other = w.request(report, central, t.plusDays(30), t.plusDays(30));
    assertThat(ids(w.expect(get(MY_APPROVALS).header("Authorization", manager.bearer()), 200)))
        .containsExactly(original);

    // The replacement changes the policy (and so the route) and overlaps the original's own dates.
    String key = Organizations.newKey();
    MockHttpServletResponse done =
        w.call(
            amend(
                report.bearer(),
                original,
                key,
                amendment(central, t.plusDays(11), t.plusDays(14), 3, "fr", FRENCH_REASON)));
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(201);
    assertThat(done.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(done.getHeader("Idempotent-Replayed")).isNull();
    assertThat(done.getContentAsString()).doesNotContain("voyage");
    JsonNode receipt = JSON.readTree(done.getContentAsString());
    assertThat(fieldNames(receipt))
        .containsExactlyInAnyOrder(
            "amendmentId",
            "originalRequestId",
            "replacementRequestId",
            "originalState",
            "replacementState",
            "amendedAt");
    String replacement = receipt.get("replacementRequestId").asText();
    assertThat(receipt.get("originalRequestId").asText()).isEqualTo(original);
    assertThat(receipt.get("originalState").asText()).isEqualTo("AMENDED");
    assertThat(receipt.get("replacementState").asText()).isEqualTo("PENDING");
    assertThat(w.state(original)).isEqualTo("AMENDED");
    assertThat(w.state(replacement)).isEqualTo("PENDING");
    assertThat(w.amendments()).containsExactly(1, 1, 1);

    // The original left the manager's inbox; the replacement follows its own policy's route.
    assertThat(ids(w.expect(get(MY_APPROVALS).header("Authorization", manager.bearer()), 200)))
        .isEmpty();
    assertThat(ids(w.expect(get(ADMIN_APPROVALS).header("Authorization", w.admin), 200)))
        .containsExactlyInAnyOrder(replacement, other);
    // The original's own day outside the replacement is free; the replacement's dates are held.
    w.expect(LeaveWorld.submit(report.bearer(), managed, t.plusDays(10), t.plusDays(10)), 201);
    w.problem(
        LeaveWorld.submit(report.bearer(), managed, t.plusDays(14), t.plusDays(14)),
        409,
        "LEAVE_REQUEST_OVERLAP");

    // A reason-only amendment of the replacement is allowed: the chain records it.
    JsonNode second =
        w.expect(
            amend(
                report.bearer(),
                replacement,
                Organizations.newKey(),
                amendment(central, t.plusDays(11), t.plusDays(14), 3, "en", ENGLISH_REASON)),
            201);
    String last = second.get("replacementRequestId").asText();

    // The history links both ways, with the reasons as written, never the actor.
    MockHttpServletResponse mine =
        w.call(get(MY_REQUESTS).header("Authorization", report.bearer()));
    assertThat(mine.getContentAsString())
        .doesNotContain(report.subject())
        .doesNotContain("amendedBy")
        .doesNotContain("amended_by")
        .doesNotContain("decisionAuthority");
    Map<String, JsonNode> byId = history(w, report);
    JsonNode amended = byId.get(original);
    assertThat(amended.get("state").asText()).isEqualTo("AMENDED");
    assertThat(amended.get("amendedFromRequestId").isNull()).isTrue();
    JsonNode shown = amended.get("amendment");
    assertThat(fieldNames(shown))
        .containsExactlyInAnyOrder(
            "id", "replacementRequestId", "reasonLocale", "reason", "amendedAt");
    assertThat(shown.get("id").asText()).isEqualTo(receipt.get("amendmentId").asText());
    assertThat(shown.get("replacementRequestId").asText()).isEqualTo(replacement);
    assertThat(shown.get("reasonLocale").asText()).isEqualTo("fr");
    assertThat(shown.get("reason").asText()).isEqualTo(FRENCH_REASON);
    JsonNode middle = byId.get(replacement);
    assertThat(middle.get("state").asText()).isEqualTo("AMENDED");
    assertThat(middle.get("amendedFromRequestId").asText()).isEqualTo(original);
    assertThat(middle.get("amendment").get("reason").asText()).isEqualTo(ENGLISH_REASON);
    assertThat(middle.get("policyCode").asText()).isEqualTo("ADM");
    assertThat(middle.get("amount").decimalValue()).isEqualByComparingTo("3");
    JsonNode current = byId.get(last);
    assertThat(current.get("state").asText()).isEqualTo("PENDING");
    assertThat(current.get("amendedFromRequestId").asText()).isEqualTo(replacement);
    assertThat(current.get("amendment").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT amended_by FROM people.leave_request_amendment"
                    + " WHERE original_request_id = ?::uuid",
                String.class,
                original))
        .isEqualTo(report.subject());

    // Audit: identifiers and states only; the digest covers the whole amended state.
    JsonNode metadata =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE action ="
                    + " 'leave-request.amend' AND resource_id = ?::uuid",
                String.class,
                original));
    assertThat(fieldNames(metadata))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "amendmentId",
            "originalRequestId",
            "replacementRequestId",
            "employeeId",
            "originalEmploymentId",
            "replacementEmploymentId",
            "originalPolicyId",
            "originalPolicyVersionId",
            "replacementPolicyId",
            "replacementPolicyVersionId",
            "priorState",
            "originalState",
            "replacementState");
    assertThat(metadata.get("originalState").asText()).isEqualTo("AMENDED");
    assertThat(
            jdbc.queryForObject(
                "SELECT after_state_sha256 FROM platform.audit_event WHERE action ="
                    + " 'leave-request.amend' AND resource_id = ?::uuid",
                String.class,
                original))
        .hasSize(64);
    JsonNode envelope =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?"
                    + " AND envelope ->> 'eventType' = 'people.leave-request.amended.v1'",
                String.class,
                original));
    List<String> expectedData = new ArrayList<>(fieldNames(metadata));
    expectedData.remove("schemaVersion");
    assertThat(fieldNames(envelope.get("data"))).containsExactlyInAnyOrderElementsOf(expectedData);
    // No reason, locale, name, date, amount or subject outside the evidence and the history.
    for (String text :
        List.of(
            envelope.toString(),
            metadata.toString(),
            String.join(
                "\n",
                jdbc.queryForList(
                    "SELECT response_body::text FROM platform.idempotency_record"
                        + " WHERE operation = 'leave-request.self-amend'",
                    String.class)))) {
      assertThat(text)
          .doesNotContain("voyage")
          .doesNotContain("travel")
          .doesNotContain("Mbuyi-Ngalula")
          .doesNotContain(t.plusDays(11).toString())
          .doesNotContain(report.subject());
    }
    assertThat(logs(output))
        .contains("leave_request_self_amended")
        .doesNotContain("voyage")
        .doesNotContain("travel dates")
        .doesNotContain("Mbuyi-Ngalula")
        .doesNotContain(report.subject())
        .doesNotContain(key);
    List<String> tags = new ArrayList<>();
    for (Meter meter :
        meters
            .find(OperationMetrics.METRIC)
            .tag("operation", "leave-request.self-amend")
            .meters()) {
      meter.getId().getTags().forEach(tag -> tags.add(tag.getKey() + "=" + tag.getValue()));
    }
    assertThat(tags)
        .contains("operation=leave-request.self-amend", "outcome=created")
        .allMatch(tag -> tag.startsWith("operation=") || tag.startsWith("outcome="));
  }

  // ------------------------------------------------------------------------------------------
  // Every current submission rule against the replacement (D41DE-3)
  // ------------------------------------------------------------------------------------------

  @Test
  void theReplacementPassesEveryCurrentSubmissionRuleOrNothingChanges() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    String senior = w.policy("SEN", "TENANT_ADMIN", 3650);
    Person p = w.person("Bénédicte", "Mbuyi");
    Person q = w.person("Aline", "Tshibanda");
    String original = w.request(p, central, t.plusDays(5), t.plusDays(6));
    String blocking = w.request(p, central, t.plusDays(20), t.plusDays(20));
    String leaving = w.request(q, central, t.plusDays(5), t.plusDays(5));
    w.expect(w.separate(q.employee(), w.separation(t.plusDays(25))), 201);

    record Refusal(Person who, String id, Map<String, Object> body, int status, String code) {}
    for (Refusal r :
        List.of(
            // The business date: no replacement before today.
            new Refusal(
                p,
                original,
                amendment(central, t.minusDays(1), t.plusDays(1), 1, "fr", "Avancé."),
                400,
                "VALIDATION_FAILED"),
            new Refusal(
                q,
                leaving,
                amendment(
                    UUID.randomUUID().toString(), t.plusDays(5), t.plusDays(5), 1, "fr", "Autre."),
                409,
                "LEAVE_POLICY_NOT_REQUESTABLE"),
            new Refusal(
                p,
                original,
                amendment(senior, t.plusDays(5), t.plusDays(6), 1, "fr", "Autre politique."),
                409,
                "LEAVE_REQUEST_NOT_ELIGIBLE"),
            new Refusal(
                q,
                leaving,
                amendment(central, t.plusDays(24), t.plusDays(26), 1, "fr", "Plus tard."),
                409,
                "LEAVE_REQUEST_NOT_ELIGIBLE"),
            // Only the original is excluded from the overlap.
            new Refusal(
                p,
                original,
                amendment(central, t.plusDays(19), t.plusDays(21), 3, "fr", "Plus long."),
                409,
                "LEAVE_REQUEST_OVERLAP"))) {
      JsonNode refused =
          w.problem(
              amend(r.who().bearer(), r.id(), Organizations.newKey(), r.body()),
              r.status(),
              r.code());
      assertThat(refused.toString()).doesNotContain("Avancé").doesNotContain("Plus long");
      assertThat(w.state(r.id())).isEqualTo("PENDING");
    }
    // The reasons of the eligibility refusals.
    JsonNode minimum =
        w.problem(
            amend(
                p.bearer(),
                original,
                Organizations.newKey(),
                amendment(senior, t.plusDays(5), t.plusDays(6), 1, "fr", "Autre politique.")),
            409,
            "LEAVE_REQUEST_NOT_ELIGIBLE");
    assertThat(minimum.get("params").get("reason").asText()).isEqualTo("MINIMUM_SERVICE");
    JsonNode period =
        w.problem(
            amend(
                q.bearer(),
                leaving,
                Organizations.newKey(),
                amendment(central, t.plusDays(24), t.plusDays(26), 1, "fr", "Plus tard.")),
            409,
            "LEAVE_REQUEST_NOT_ELIGIBLE");
    assertThat(period.get("params").get("reason").asText()).isEqualTo("EMPLOYMENT_PERIOD");
    assertThat(w.amendments()).containsExactly(0, 0, 0);
    assertThat(w.state(blocking)).isEqualTo("PENDING");
    // The original's dates were never released by a refused amendment.
    w.problem(
        LeaveWorld.submit(p.bearer(), central, t.plusDays(6), t.plusDays(6)),
        409,
        "LEAVE_REQUEST_OVERLAP");
    // Still within its employment, the separated employee's replacement is accepted.
    w.expect(
        amend(
            q.bearer(),
            leaving,
            Organizations.newKey(),
            amendment(central, t.plusDays(24), t.plusDays(25), 2, "fr", "Dernier jour.")),
        201);
  }

  // ------------------------------------------------------------------------------------------
  // Ownership, tenant, link and role (D41DE-1; AC3)
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheLinkedOwnerAmendsAndEveryOtherCallerLearnsNothing() throws Exception {
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
    Map<String, Object> body =
        amendment(managed, t.plusDays(6), t.plusDays(6), 1, "en", "Moved by a day.");

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
              amend(attempt[0], attempt[1], Organizations.newKey(), body),
              404,
              "LEAVE_REQUEST_NOT_FOUND");
      assertThat(refused.get("params").size()).isZero();
    }
    String unlinked = UUID.randomUUID().toString();
    w.member(unlinked);
    w.problem(
        amend(LeaveWorld.employeeBearer(w.tenant(), unlinked), mine, Organizations.newKey(), body),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    for (String bearer :
        List.of(LeaveWorld.employeeBearer(w.tenant(), UUID.randomUUID().toString()), w.admin)) {
      w.problem(amend(bearer, mine, Organizations.newKey(), body), 403, "ACCESS_DENIED");
    }
    assertThat(
            w.call(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                            MY_REQUESTS + "/" + mine + "/amendment")
                        .header("Idempotency-Key", Organizations.newKey())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .getStatus())
        .isEqualTo(401);
    assertThat(w.state(mine)).isEqualTo("PENDING");
    assertThat(other.state(foreign)).isEqualTo("PENDING");
    assertThat(w.amendments()).containsExactly(0, 0, 0);
    assertThat(other.amendments()).containsExactly(0, 0, 0);
    List<String> reasons = new ArrayList<>();
    for (Meter meter :
        meters
            .find(ScopeAuthorizationInterceptor.SELF_SERVICE_DENIALS)
            .tag("operation", "leave-request.self-amend")
            .meters()) {
      reasons.add(meter.getId().getTag("reason"));
    }
    assertThat(reasons).contains("not_found", "link_required");
  }

  // ------------------------------------------------------------------------------------------
  // Replay, terminal states and chains (D41DE-3; AC3)
  // ------------------------------------------------------------------------------------------

  @Test
  void replaysFollowTheCurrentLinkBeforeAnyStoredReceiptIsRead() throws Exception {
    LeaveWorld w = world();
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person owner = w.person("Bénédicte", "Mbuyi");
    LocalDate t = w.today;
    String original = w.request(owner, central, t.plusDays(5), t.plusDays(5));
    Map<String, Object> body = amendment(central, t.plusDays(6), t.plusDays(7), 2, "fr", "Décalé.");
    String key = Organizations.newKey();
    JsonNode receipt = w.expect(amend(owner.bearer(), original, key, body), 201);

    MockHttpServletResponse replay = w.call(amend(owner.bearer(), original, key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(receipt);
    // A changed body under the key is a key conflict before any state rule.
    w.problem(
        amend(
            owner.bearer(),
            original,
            key,
            amendment(central, t.plusDays(6), t.plusDays(7), 2, "fr", "Décalé encore.")),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    JsonNode again =
        w.problem(
            amend(owner.bearer(), original, Organizations.newKey(), body),
            409,
            "LEAVE_REQUEST_ALREADY_AMENDED");
    assertThat(again.get("params").size()).isZero();

    w.unlink(owner.employee());
    MockHttpServletResponse noLink = w.call(amend(owner.bearer(), original, key, body));
    assertThat(noLink.getStatus()).isEqualTo(403);
    assertThat(noLink.getContentAsString())
        .doesNotContain(receipt.get("replacementRequestId").asText());
    UUID another = w.hire("Grâce", "Lukusa");
    w.linkTo(another, owner.membership());
    MockHttpServletResponse relinked = w.call(amend(owner.bearer(), original, key, body));
    assertThat(relinked.getStatus()).isEqualTo(404);
    assertThat(JSON.readTree(relinked.getContentAsString()).get("code").asText())
        .isEqualTo("LEAVE_REQUEST_NOT_FOUND");
    assertThat(relinked.getHeader("Idempotent-Replayed")).isNull();
    assertThat(relinked.getContentAsString())
        .doesNotContain(receipt.get("replacementRequestId").asText());
    w.unlink(another);
    w.linkTo(owner.employee(), owner.membership());
    MockHttpServletResponse back = w.call(amend(owner.bearer(), original, key, body));
    assertThat(back.getStatus()).isEqualTo(201);
    assertThat(back.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(w.amendments()).containsExactly(1, 1, 1);
  }

  @Test
  void terminalRequestsAreRefusedByStateAndChainsEndNormally() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    String managed = w.policy("MGR", "MANAGER");
    Person owner = w.person("Bénédicte", "Mbuyi");
    Person manager = w.person("Josué", "Kabeya");
    w.manage(owner.employee(), manager.employee(), t);
    String approved = w.request(owner, managed, t.plusDays(5), t.plusDays(5));
    String cancelled = w.request(owner, central, t.plusDays(9), t.plusDays(9));
    String chained = w.request(owner, managed, t.plusDays(11), t.plusDays(11));
    w.expect(
        decide(
            MY_APPROVALS,
            manager.bearer(),
            approved,
            Organizations.newKey(),
            decision("APPROVED", "fr", "Accordé.")),
        200);
    w.expect(
        cancel(owner.bearer(), cancelled, Organizations.newKey(), cancellation("fr", "Annulé.")),
        200);
    for (String[] refusal :
        List.of(
            new String[] {approved, "LEAVE_REQUEST_ALREADY_DECIDED"},
            new String[] {cancelled, "LEAVE_REQUEST_ALREADY_CANCELLED"})) {
      JsonNode refused =
          w.problem(
              amend(
                  owner.bearer(),
                  refusal[0],
                  Organizations.newKey(),
                  amendment(central, t.plusDays(40), t.plusDays(40), 1, "fr", "Changement.")),
              409,
              refusal[1]);
      assertThat(refused.get("params").size()).isZero();
    }

    // A chain: the replacement is amended again; its predecessors are terminal.
    String first =
        w.expect(
                amend(
                    owner.bearer(),
                    chained,
                    Organizations.newKey(),
                    amendment(managed, t.plusDays(12), t.plusDays(12), 1, "fr", "Un jour après.")),
                201)
            .get("replacementRequestId")
            .asText();
    // The manager still sees only the pending replacement; deciding the original is refused.
    assertThat(ids(w.expect(get(MY_APPROVALS).header("Authorization", manager.bearer()), 200)))
        .containsExactly(first);
    w.problem(
        decide(
            MY_APPROVALS,
            manager.bearer(),
            chained,
            Organizations.newKey(),
            decision("APPROVED", "fr", "Accordé.")),
        409,
        "LEAVE_REQUEST_ALREADY_DECIDED");
    w.problem(
        cancel(owner.bearer(), chained, Organizations.newKey(), cancellation("fr", "Annulé.")),
        409,
        "LEAVE_REQUEST_ALREADY_AMENDED");
    // The replacement is decided normally.
    w.expect(
        decide(
            MY_APPROVALS,
            manager.bearer(),
            first,
            Organizations.newKey(),
            decision("APPROVED", "fr", "Accordé.")),
        200);
    assertThat(w.state(chained)).isEqualTo("AMENDED");
    assertThat(w.state(first)).isEqualTo("APPROVED");
    assertThat(w.amendments()).containsExactly(1, 1, 1);
    assertThat(w.decisions()).containsExactly(2, 2, 2);
  }

  // ------------------------------------------------------------------------------------------
  // Validation (the submission fields and the decision-reason grammar version 1, shared)
  // ------------------------------------------------------------------------------------------

  @Test
  void amendmentsAreValidatedWithoutEchoingAnything() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    String central = w.policy("ADM", "TENANT_ADMIN");
    Person p = w.person("Bénédicte", "Mbuyi");
    Person q = w.person("Aline", "Tshibanda");
    String id = w.request(p, central, t.plusDays(3), t.plusDays(3));
    LocalDate s = t.plusDays(4);

    record Bad(Object body, String expected) {}
    Map<String, Object> extra = amendment(central, s, s, 1, "en", "Fine reason");
    extra.put("employeeId", p.employee().toString());
    List<Bad> bads =
        List.of(
            new Bad(amendment(central, s, s, 1, "en", null), "reason:REQUIRED"),
            new Bad(amendment(central, s, s, 1, "en", "x"), "reason:LENGTH"),
            new Bad(amendment(central, s, s, 1, "en", "secret\u200Breason"), "reason:FORMAT"),
            new Bad(amendment(central, s, s, 1, "de", "Fine reason"), "reasonLocale:FORMAT"),
            new Bad(amendment(null, s, s, 1, "en", "Fine reason"), "policyId:REQUIRED"),
            new Bad(amendment("secret-policy", s, s, 1, "en", "Fine reason"), "policyId:FORMAT"),
            new Bad(amendment(central, s, s.minusDays(1), 1, "en", "Fine"), "endDate:RANGE"),
            new Bad(amendment(central, s, s.plusDays(366), 1, "en", "Fine"), "endDate:RANGE"),
            new Bad(amendment(central, s, s, 0, "en", "Fine reason"), "amount:RANGE"),
            new Bad(amendment(central, s, s, 1.234, "en", "Fine reason"), "amount:FORMAT"),
            new Bad(extra, "body:UNKNOWN_PROPERTY"));
    int attempt = 0;
    for (Bad bad : bads) {
      String bearer = attempt++ % 2 == 0 ? p.bearer() : q.bearer();
      JsonNode problem =
          w.problem(
              amend(bearer, id, Organizations.newKey(), bad.body()), 400, "VALIDATION_FAILED");
      assertThat(fields(problem)).as("%s", bad.expected()).containsExactly(bad.expected());
      assertThat(problem.toString()).doesNotContain("secret");
    }
    JsonNode noKey =
        w.problem(
            amend(q.bearer(), id, null, amendment(central, s, s, 1, "en", "Fine")),
            400,
            "VALIDATION_FAILED");
    assertThat(fields(noKey)).containsExactly("Idempotency-Key:REQUIRED");
    assertThat(w.amendments()).containsExactly(0, 0, 0);
    assertThat(w.state(id)).isEqualTo("PENDING");
  }
}
