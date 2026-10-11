package com.divalhr.core.people.leave;

import static com.divalhr.core.people.leave.LeaveWorld.ADMIN_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.EXCEPTIONS;
import static com.divalhr.core.people.leave.LeaveWorld.JSON;
import static com.divalhr.core.people.leave.LeaveWorld.MY_APPROVALS;
import static com.divalhr.core.people.leave.LeaveWorld.MY_REQUESTS;
import static com.divalhr.core.people.leave.LeaveWorld.SUBSTITUTIONS;
import static com.divalhr.core.people.leave.LeaveWorld.appoint;
import static com.divalhr.core.people.leave.LeaveWorld.appointment;
import static com.divalhr.core.people.leave.LeaveWorld.cancellation;
import static com.divalhr.core.people.leave.LeaveWorld.decide;
import static com.divalhr.core.people.leave.LeaveWorld.decision;
import static com.divalhr.core.people.leave.LeaveWorld.revoke;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.leave.LeaveWorld.Person;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-041G (Issue #97) end to end against real PostgreSQL: a tenant administrator with exact MFA
 * appoints, lists and revokes one effective-dated substitute per manager; the creation rules
 * (employees, employment, reporting line, overlap, dates and the organization's business date); the
 * manager inbox shows primary and substitute work together with its capacity, never the
 * substitute's own request; the substitute approves or rejects as {@code MANAGER_SUBSTITUTE} beside
 * a primary manager who keeps every right; revocation removes undecided delegated work at once; a
 * substitute decision replays after expiry or revocation under the current link only; role, MFA,
 * link and tenant boundaries; manager-line changes; privacy of the employee's history, the audit,
 * the events and the logs; the fail-closed disclosure.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LeaveSubstitutionIntegrationTest {

  private static final String APPOINT_REASON = "Congé du responsable, intérim assuré.";
  private static final String REVOKE_REASON = "Manager returned early from leave.";
  private static final String DECIDE_REASON = "Validé pendant l’absence du responsable.";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;

  private LeaveWorld world() throws Exception {
    return new LeaveWorld(mvc, jdbc, lookups);
  }

  /** A manager with reports, a substitute who reports to them and manages someone, an outsider. */
  private record Team(
      LeaveWorld w,
      String managed,
      String central,
      Person manager,
      Person substitute,
      Person report,
      Person colleague,
      Person own,
      Person outsider,
      Person outsiders) {}

  private Team team() throws Exception {
    LeaveWorld w = world();
    LocalDate t = w.today;
    Team team =
        new Team(
            w,
            w.policy("MGR", "MANAGER"),
            w.policy("ADM", "TENANT_ADMIN"),
            w.person("Josué", "Kabeya"),
            w.person("Grâce", "Lukusa"),
            w.person("Bénédicte", "Mbuyi-Ngalula"),
            w.person("Aline", "Tshibanda"),
            w.person("Divine", "Kalonji"),
            w.person("Amani", "Ilunga"),
            w.person("Chance", "Mwamba"));
    w.manage(team.report().employee(), team.manager().employee(), t);
    w.manage(team.colleague().employee(), team.manager().employee(), t);
    w.manage(team.substitute().employee(), team.manager().employee(), t);
    w.manage(team.own().employee(), team.substitute().employee(), t);
    w.manage(team.outsiders().employee(), team.outsider().employee(), t);
    return team;
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.get("items").forEach(item -> ids.add(item.get("id").asText()));
    return ids;
  }

  private static Map<String, String> capacities(JsonNode page) {
    Map<String, String> capacities = new LinkedHashMap<>();
    page.get("items")
        .forEach(
            item ->
                capacities.put(
                    item.get("id").asText(),
                    item.has("approvalCapacity") ? item.get("approvalCapacity").asText() : null));
    return capacities;
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

  private static MockHttpServletRequestBuilder inbox(String bearer) {
    return get(MY_APPROVALS).header("Authorization", bearer);
  }

  private static MockHttpServletRequestBuilder list(String bearer) {
    return get(SUBSTITUTIONS).header("Authorization", bearer);
  }

  private JsonNode metadata(String action, Object resource) throws Exception {
    return JSON.readTree(
        jdbc.queryForObject(
            "SELECT metadata::text FROM platform.audit_event WHERE action = ?"
                + " AND resource_id = ?::uuid",
            String.class,
            action,
            resource.toString()));
  }

  private JsonNode event(String type, Object subject) throws Exception {
    return JSON.readTree(
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'eventType' = ?"
                + " AND envelope ->> 'subject' = ?",
            String.class,
            type,
            subject.toString()));
  }

  // ------------------------------------------------------------------------------------------
  // Appointment, list and revocation (D41G-3, D41G-7)
  // ------------------------------------------------------------------------------------------

  @Test
  void anAdministratorAppointsListsAndRevokesWithoutErasingHistory(CapturedOutput output)
      throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    Map<String, Object> body =
        appointment(
            team.manager().employee(),
            team.substitute().employee(),
            t,
            t.plusDays(20),
            "fr",
            APPOINT_REASON);
    String key = Organizations.newKey();
    MockHttpServletResponse created = w.call(appoint(w.admin, key, body));
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    assertThat(created.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode receipt = JSON.readTree(created.getContentAsString());
    assertThat(fieldNames(receipt)).containsExactlyInAnyOrder("substitutionId", "createdAt");
    String id = receipt.get("substitutionId").asText();

    // The exact replay returns the stored receipt; a changed body under the key is refused.
    MockHttpServletResponse replay = w.call(appoint(w.admin, key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(receipt);
    Map<String, Object> changed = new LinkedHashMap<>(body);
    changed.put("effectiveTo", t.plusDays(21).toString());
    w.problem(appoint(w.admin, key, changed), 409, "IDEMPOTENCY_KEY_REUSED");

    // The list: both employees' directory fields, the dates, the derived state, the reason.
    MockHttpServletResponse listed = w.call(list(w.admin));
    assertThat(listed.getStatus()).isEqualTo(200);
    assertThat(listed.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode page = JSON.readTree(listed.getContentAsString());
    assertThat(fieldNames(page)).containsExactlyInAnyOrder("items", "nextCursor", "asOf");
    assertThat(page.get("asOf").asText()).isEqualTo(t.toString());
    assertThat(ids(page)).containsExactly(id);
    JsonNode item = page.get("items").get(0);
    assertThat(fieldNames(item))
        .containsExactlyInAnyOrder(
            "id",
            "manager",
            "substitute",
            "effectiveFrom",
            "effectiveTo",
            "state",
            "reasonLocale",
            "reason",
            "createdAt",
            "revocation");
    assertThat(fieldNames(item.get("manager")))
        .containsExactlyInAnyOrder("id", "employeeNumber", "givenNames", "familyName");
    assertThat(item.get("manager").get("id").asText())
        .isEqualTo(team.manager().employee().toString());
    assertThat(item.get("substitute").get("familyName").asText()).isEqualTo("Lukusa");
    assertThat(item.get("state").asText()).isEqualTo("ACTIVE");
    assertThat(item.get("reason").asText()).isEqualTo(APPOINT_REASON);
    assertThat(item.get("revocation").isNull()).isTrue();
    assertThat(listed.getContentAsString())
        .doesNotContain(w.adminSubject)
        .doesNotContain("createdBy");
    JsonNode read =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE action ="
                    + " 'leave-approval-substitution.read' AND tenant_id = ?"
                    + " ORDER BY occurred_at DESC LIMIT 1",
                String.class,
                w.tenant()));
    assertThat(fieldNames(read))
        .containsExactlyInAnyOrder("schemaVersion", "view", "page", "resultCount");

    // Revocation: immediate, append-only, with its own receipt and evidence.
    Map<String, Object> revocation = cancellation("en", REVOKE_REASON);
    String revokeKey = Organizations.newKey();
    MockHttpServletResponse revoked = w.call(revoke(w.admin, id, revokeKey, revocation));
    assertThat(revoked.getStatus()).as(revoked.getContentAsString()).isEqualTo(201);
    assertThat(revoked.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode revokedReceipt = JSON.readTree(revoked.getContentAsString());
    assertThat(fieldNames(revokedReceipt))
        .containsExactlyInAnyOrder("substitutionId", "revocationId", "state", "revokedAt");
    assertThat(revokedReceipt.get("state").asText()).isEqualTo("REVOKED");
    MockHttpServletResponse revokeReplay = w.call(revoke(w.admin, id, revokeKey, revocation));
    assertThat(revokeReplay.getStatus()).isEqualTo(201);
    assertThat(revokeReplay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(revokeReplay.getContentAsString())).isEqualTo(revokedReceipt);
    JsonNode again =
        w.problem(
            revoke(w.admin, id, Organizations.newKey(), revocation),
            409,
            "LEAVE_APPROVAL_SUBSTITUTION_ALREADY_REVOKED");
    assertThat(again.get("params").size()).isZero();
    JsonNode after = w.expect(list(w.admin), 200).get("items").get(0);
    assertThat(after.get("state").asText()).isEqualTo("REVOKED");
    assertThat(fieldNames(after.get("revocation")))
        .containsExactlyInAnyOrder("revokedAt", "reasonLocale", "reason");
    assertThat(after.get("revocation").get("reason").asText()).isEqualTo(REVOKE_REASON);
    // The appointment itself is unchanged.
    assertThat(after.get("effectiveTo").asText()).isEqualTo(t.plusDays(20).toString());
    assertThat(after.get("reason").asText()).isEqualTo(APPOINT_REASON);
    assertThat(w.substitutions()).containsExactly(1, 1, 2, 2);

    // Audit metadata and events: identifiers, dates and states; never a reason or a subject.
    JsonNode createAudit = metadata("leave-approval-substitution.create", id);
    assertThat(fieldNames(createAudit))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "substitutionId",
            "managerEmployeeId",
            "substituteEmployeeId",
            "effectiveFrom",
            "effectiveTo",
            "state");
    JsonNode revokeAudit = metadata("leave-approval-substitution.revoke", id);
    assertThat(fieldNames(revokeAudit))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "substitutionId",
            "revocationId",
            "managerEmployeeId",
            "substituteEmployeeId",
            "priorState",
            "state");
    assertThat(revokeAudit.get("priorState").asText()).isEqualTo("ACTIVE");
    JsonNode createdEvent = event("people.leave-approval-substitution.created.v1", id);
    JsonNode revokedEvent = event("people.leave-approval-substitution.revoked.v1", id);
    assertThat(fieldNames(createdEvent.get("data")))
        .containsExactlyInAnyOrder(
            "substitutionId",
            "managerEmployeeId",
            "substituteEmployeeId",
            "effectiveFrom",
            "effectiveTo",
            "state");
    assertThat(revokedEvent.get("data").get("state").asText()).isEqualTo("REVOKED");
    for (String evidence :
        List.of(
            createAudit.toString(),
            revokeAudit.toString(),
            createdEvent.toString(),
            revokedEvent.toString())) {
      assertThat(evidence)
          .doesNotContain("intérim")
          .doesNotContain("returned early")
          .doesNotContain("Lukusa")
          .doesNotContain(w.adminSubject);
    }
    assertThat(logs(output))
        .contains("leave_approval_substitution_appointed")
        .contains("leave_approval_substitution_revoked")
        .contains("leave_approval_substitution_listed")
        .doesNotContain("intérim")
        .doesNotContain("returned early")
        .doesNotContain("Mbuyi-Ngalula")
        .doesNotContain(key)
        .doesNotContain(revokeKey);
  }

  @Test
  void appointmentShapesAreValidatedWithoutEcho() throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    UUID manager = team.manager().employee();
    UUID substitute = team.substitute().employee();

    // Shape problems are pairs only, never the values.
    for (Object[] bad :
        List.of(
            new Object[] {
              appointment(null, substitute, t, t, "fr", APPOINT_REASON),
              List.of("managerEmployeeId:REQUIRED")
            },
            new Object[] {
              appointment("not-an-id", substitute, t, t, "fr", APPOINT_REASON),
              List.of("managerEmployeeId:FORMAT")
            },
            new Object[] {
              appointment(manager, substitute, "2026-02-30", t, "fr", APPOINT_REASON),
              List.of("effectiveFrom:FORMAT")
            },
            new Object[] {
              appointment(manager, substitute, t, t.minusDays(1), "fr", APPOINT_REASON),
              List.of("effectiveTo:RANGE")
            },
            new Object[] {
              appointment(manager, substitute, t, "3000-01-01", "fr", APPOINT_REASON),
              List.of("effectiveTo:RANGE")
            },
            new Object[] {
              appointment(manager, substitute, t, t, "de", APPOINT_REASON),
              List.of("reasonLocale:FORMAT")
            },
            new Object[] {
              appointment(manager, substitute, t, t, "fr", "secret\u0007reason"),
              List.of("reason:FORMAT")
            })) {
      JsonNode problem =
          w.problem(appoint(w.admin, Organizations.newKey(), bad[0]), 400, "VALIDATION_FAILED");
      assertThat(fields(problem)).isEqualTo(bad[1]);
      assertThat(problem.toString()).doesNotContain("secret").doesNotContain("not-an-id");
    }
    Map<String, Object> extra = appointment(manager, substitute, t, t, "fr", APPOINT_REASON);
    extra.put("tenantId", UUID.randomUUID().toString());
    assertThat(
            fields(
                w.problem(
                    appoint(w.admin, Organizations.newKey(), extra), 400, "VALIDATION_FAILED")))
        .containsExactly("body:UNKNOWN_PROPERTY");
    assertThat(
            fields(
                w.problem(
                    appoint(
                        w.admin,
                        null,
                        appointment(manager, substitute, t, t, "fr", APPOINT_REASON)),
                    400,
                    "VALIDATION_FAILED")))
        .containsExactly("Idempotency-Key:REQUIRED");
  }

  @Test
  void appointmentsAreRefusedOutsideTheirBusinessRules() throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    UUID manager = team.manager().employee();
    UUID substitute = team.substitute().employee();
    // Business rules: stable codes with empty params.
    LeaveWorld other = world();
    Person foreign = other.person("Esther", "Mutombo");
    UUID late =
        Employees.hire(
            mvc, jdbc, w.org, w.hiring(), Employees.number(), "Fiston", "Kasongo", t.plusDays(10));
    for (Object[] refused :
        List.of(
            new Object[] {
              appointment(manager, manager, t, t, "fr", APPOINT_REASON),
              422,
              "LEAVE_APPROVAL_SUBSTITUTION_SAME_EMPLOYEE"
            },
            new Object[] {
              appointment(manager, UUID.randomUUID(), t, t, "fr", APPOINT_REASON),
              404,
              "EMPLOYEE_NOT_FOUND"
            },
            new Object[] {
              appointment(manager, foreign.employee(), t, t, "fr", APPOINT_REASON),
              404,
              "EMPLOYEE_NOT_FOUND"
            },
            // Employed only from t + 10: not on every day of the appointment.
            new Object[] {
              appointment(manager, late, t, t.plusDays(10), "fr", APPOINT_REASON),
              422,
              "LEAVE_APPROVAL_SUBSTITUTION_EMPLOYMENT_PERIOD"
            },
            // Before anyone was hired.
            new Object[] {
              appointment(
                  manager,
                  substitute,
                  LeaveWorld.HIRED.minusDays(1),
                  LeaveWorld.HIRED,
                  "fr",
                  APPOINT_REASON),
              422,
              "LEAVE_APPROVAL_SUBSTITUTION_EMPLOYMENT_PERIOD"
            },
            // The report manages nobody.
            new Object[] {
              appointment(
                  team.report().employee(), substitute, t, t.plusDays(3), "fr", APPOINT_REASON),
              422,
              "LEAVE_APPROVAL_SUBSTITUTION_NO_REPORTING_LINE"
            },
            // The manager's lines start today: none overlaps an appointment that ends yesterday.
            new Object[] {
              appointment(
                  manager, substitute, t.minusDays(5), t.minusDays(1), "fr", APPOINT_REASON),
              422,
              "LEAVE_APPROVAL_SUBSTITUTION_NO_REPORTING_LINE"
            })) {
      JsonNode problem =
          w.problem(
              appoint(w.admin, Organizations.newKey(), refused[0]),
              (Integer) refused[1],
              (String) refused[2]);
      assertThat(problem.get("params").size()).isZero();
    }
    assertThat(w.substitutions()).containsExactly(0, 0, 0, 0);

    // One unrevoked appointment per manager per date, inclusive ends; a revocation frees the dates
    // (R98-1).
    String first = w.appoint(team.manager(), team.substitute(), t.plusDays(1), t.plusDays(10));
    w.problem(
        appoint(
            w.admin,
            Organizations.newKey(),
            appointment(
                manager,
                team.colleague().employee(),
                t.plusDays(10),
                t.plusDays(12),
                "fr",
                APPOINT_REASON)),
        409,
        "LEAVE_APPROVAL_SUBSTITUTION_OVERLAP");
    w.revoke(first);
    w.appoint(team.manager(), team.colleague(), t.plusDays(5), t.plusDays(5));
    w.problem(
        appoint(
            w.admin,
            Organizations.newKey(),
            appointment(
                manager,
                team.own().employee(),
                t.plusDays(4),
                t.plusDays(6),
                "fr",
                APPOINT_REASON)),
        409,
        "LEAVE_APPROVAL_SUBSTITUTION_OVERLAP");
    w.appoint(team.manager(), team.colleague(), t.plusDays(11), t.plusDays(12));
    // Another manager may name the same substitute on the same dates.
    w.appoint(team.substitute(), team.colleague(), t.plusDays(1), t.plusDays(10));
    assertThat(w.substitutions()).containsExactly(4, 1, 5, 5);

    // Unknown, malformed and another tenant's appointments are one answer.
    for (String unknown : List.of(UUID.randomUUID().toString(), "not-an-id")) {
      JsonNode problem =
          w.problem(
              revoke(w.admin, unknown, Organizations.newKey(), cancellation("en", REVOKE_REASON)),
              404,
              "LEAVE_APPROVAL_SUBSTITUTION_NOT_FOUND");
      assertThat(problem.get("params").size()).isZero();
    }
    other.problem(
        revoke(other.admin, first, Organizations.newKey(), cancellation("en", REVOKE_REASON)),
        404,
        "LEAVE_APPROVAL_SUBSTITUTION_NOT_FOUND");
    assertThat(ids(other.expect(list(other.admin), 200))).isEmpty();
  }

  @Test
  void aRevokedAppointmentIsReplacedForItsRemainingDatesWithoutRewritingHistory() throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    String id = w.request(team.report(), team.managed(), t.plusDays(10), t.plusDays(10));
    String a = w.appoint(team.manager(), team.substitute(), t, t.plusDays(30));
    String before =
        jdbc.queryForObject(
            "SELECT to_jsonb(s)::text FROM people.leave_approval_substitution s"
                + " WHERE id = ?::uuid",
            String.class,
            a);
    assertThat(capacities(w.expect(inbox(team.substitute().bearer()), 200)))
        .containsEntry(id, "SUBSTITUTE");
    // Revoke A, then appoint B over A's remaining dates: accepted.
    w.revoke(a);
    String b = w.appoint(team.manager(), team.outsider(), t, t.plusDays(30));
    assertThat(b).isNotEqualTo(a);
    // A stays in the history, revoked, with its original dates and reason; its row is unchanged.
    Map<String, JsonNode> listed = new LinkedHashMap<>();
    w.expect(list(w.admin), 200)
        .get("items")
        .forEach(item -> listed.put(item.get("id").asText(), item));
    assertThat(listed.get(a).get("state").asText()).isEqualTo("REVOKED");
    assertThat(listed.get(a).get("effectiveFrom").asText()).isEqualTo(t.toString());
    assertThat(listed.get(a).get("effectiveTo").asText()).isEqualTo(t.plusDays(30).toString());
    assertThat(listed.get(a).get("reason").asText()).isEqualTo("Remplacement pendant les congés.");
    assertThat(listed.get(b).get("state").asText()).isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT to_jsonb(s)::text FROM people.leave_approval_substitution s"
                    + " WHERE id = ?::uuid",
                String.class,
                a))
        .isEqualTo(before);
    assertThat(
            w.count(
                "SELECT count(*) FROM people.leave_approval_substitution_coverage"
                    + " WHERE tenant_id = ?",
                w.tenant()))
        .isEqualTo(1);
    // Only B grants the inbox and the decision now.
    assertThat(ids(w.expect(inbox(team.substitute().bearer()), 200))).doesNotContain(id);
    w.problem(
        decide(
            MY_APPROVALS,
            team.substitute().bearer(),
            id,
            Organizations.newKey(),
            decision("APPROVED", "fr", DECIDE_REASON)),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
    assertThat(capacities(w.expect(inbox(team.outsider().bearer()), 200)))
        .containsEntry(id, "SUBSTITUTE");
    w.expect(
        decide(
            MY_APPROVALS,
            team.outsider().bearer(),
            id,
            Organizations.newKey(),
            decision("APPROVED", "fr", DECIDE_REASON)),
        200);
    assertThat(
            jdbc.queryForMap(
                "SELECT manager_employee_id, delegated_from_manager_employee_id"
                    + " FROM people.leave_request_decision WHERE request_id = ?::uuid",
                id))
        .containsEntry("manager_employee_id", team.outsider().employee())
        .containsEntry("delegated_from_manager_employee_id", team.manager().employee());
    assertThat(metadata("leave-request.approve", id).get("substitutionId").asText()).isEqualTo(b);
  }

  @Test
  void statesFollowTheOrganizationsBusinessDate() throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    // Expired yesterday, active from today, scheduled from tomorrow (three managers).
    Person second = w.person("Héritier", "Banza");
    Person third = w.person("Irène", "Nsimba");
    w.manage(w.person("Joël", "Nkulu").employee(), second.employee(), t.minusDays(3));
    w.manage(w.person("Karine", "Mujinga").employee(), third.employee(), t.plusDays(1));
    String expired = w.appoint(second, team.substitute(), t.minusDays(2), t.minusDays(1));
    String active = w.appoint(team.manager(), team.substitute(), t, t);
    String scheduled = w.appoint(third, team.substitute(), t.plusDays(1), t.plusDays(2));
    Map<String, String> states = new LinkedHashMap<>();
    w.expect(list(w.admin), 200)
        .get("items")
        .forEach(item -> states.put(item.get("id").asText(), item.get("state").asText()));
    assertThat(states)
        .containsEntry(expired, "EXPIRED")
        .containsEntry(active, "ACTIVE")
        .containsEntry(scheduled, "SCHEDULED");
    // An expired appointment may still be revoked, for the record.
    w.revoke(expired);
    assertThat(metadata("leave-approval-substitution.revoke", expired).get("priorState").asText())
        .isEqualTo("EXPIRED");

    // One appointment day; the organization's zone decides whether it is today. Kiritimati
    // (UTC+14) is always at least one calendar day ahead of Pago Pago (UTC-11).
    ZoneId ahead = ZoneId.of("Pacific/Kiritimati");
    ZoneId behind = ZoneId.of("Pacific/Pago_Pago");
    LocalDate day = LocalDate.now(ahead);
    LeaveWorld zoned = world();
    String managed = zoned.policy("MGR", "MANAGER");
    Person boss = zoned.person("Jonas", "Mpiana");
    Person deputy = zoned.person("Kevine", "Matondo");
    Person worker = zoned.person("Laure", "Ngoy");
    zoned.manage(worker.employee(), boss.employee(), zoned.today);
    String request = zoned.request(worker, managed, day.plusDays(5), day.plusDays(5));
    String appointment = zoned.appoint(boss, deputy, day, day);
    jdbc.update(
        "UPDATE tenant.organization SET timezone = ? WHERE id = ?", behind.getId(), zoned.tenant());
    assertThat(zoned.expect(list(zoned.admin), 200).get("items").get(0).get("state").asText())
        .isEqualTo("SCHEDULED");
    assertThat(ids(zoned.expect(inbox(deputy.bearer()), 200))).isEmpty();
    zoned.problem(
        decide(
            MY_APPROVALS,
            deputy.bearer(),
            request,
            Organizations.newKey(),
            decision("APPROVED", "fr", DECIDE_REASON)),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
    jdbc.update(
        "UPDATE tenant.organization SET timezone = ? WHERE id = ?", ahead.getId(), zoned.tenant());
    JsonNode listedAhead = zoned.expect(list(zoned.admin), 200);
    assertThat(listedAhead.get("asOf").asText()).isEqualTo(day.toString());
    assertThat(listedAhead.get("items").get(0).get("id").asText()).isEqualTo(appointment);
    assertThat(listedAhead.get("items").get(0).get("state").asText()).isEqualTo("ACTIVE");
    assertThat(capacities(zoned.expect(inbox(deputy.bearer()), 200)))
        .containsExactly(Map.entry(request, "SUBSTITUTE"));
  }

  // ------------------------------------------------------------------------------------------
  // The manager inbox (D41G-1, D41G-4)
  // ------------------------------------------------------------------------------------------

  @Test
  void theInboxShowsPrimaryAndDelegatedWorkTogetherButNeverTheSubstitutesOwn() throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    String reported = w.request(team.report(), team.managed(), t.plusDays(10), t.plusDays(10));
    String colleagues = w.request(team.colleague(), team.managed(), t.plusDays(12), t.plusDays(12));
    String substitutes =
        w.request(team.substitute(), team.managed(), t.plusDays(14), t.plusDays(14));
    String owns = w.request(team.own(), team.managed(), t.plusDays(16), t.plusDays(16));
    String outsiders = w.request(team.outsiders(), team.managed(), t.plusDays(18), t.plusDays(18));
    String central = w.request(team.report(), team.central(), t.plusDays(30), t.plusDays(30));

    // Before any appointment: each manager sees their own reports only.
    assertThat(capacities(w.expect(inbox(team.substitute().bearer()), 200)))
        .containsExactly(Map.entry(owns, "PRIMARY"));
    String appointment = w.appoint(team.manager(), team.substitute(), t, t.plusDays(30));

    // The primary manager's inbox is unchanged.
    JsonNode primary = w.expect(inbox(team.manager().bearer()), 200);
    assertThat(capacities(primary))
        .containsOnlyKeys(reported, colleagues, substitutes)
        .containsValues("PRIMARY")
        .doesNotContainValue("SUBSTITUTE");
    // The substitute: their own report as primary, the manager's reports as substitute, never
    // their own request; newest first.
    JsonNode delegated = w.expect(inbox(team.substitute().bearer()), 200);
    assertThat(ids(delegated)).containsExactly(owns, colleagues, reported);
    assertThat(capacities(delegated))
        .containsEntry(owns, "PRIMARY")
        .containsEntry(colleagues, "SUBSTITUTE")
        .containsEntry(reported, "SUBSTITUTE");
    assertThat(fieldNames(delegated.get("items").get(1)))
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
            "approvalCapacity");
    assertThat(delegated.toString())
        .doesNotContain(appointment)
        .doesNotContain("intérim")
        .doesNotContain("Remplacement")
        .doesNotContain(team.manager().employee().toString())
        .doesNotContain(w.adminSubject);
    // An unrelated manager sees only their own; the tenant administrators' inbox has no capacity.
    assertThat(ids(w.expect(inbox(team.outsider().bearer()), 200))).containsExactly(outsiders);
    JsonNode admin = w.expect(get(ADMIN_APPROVALS).header("Authorization", w.admin), 200);
    assertThat(ids(admin)).containsExactly(central);
    assertThat(fieldNames(admin.get("items").get(0))).doesNotContain("approvalCapacity");
    // The disclosure audit counts the delegated items.
    JsonNode audit =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE action ="
                    + " 'leave-approval.manager-list' AND tenant_id = ? AND actor_subject = ?"
                    + " ORDER BY occurred_at DESC LIMIT 1",
                String.class,
                w.tenant(),
                team.substitute().subject()));
    assertThat(fieldNames(audit))
        .containsExactlyInAnyOrder(
            "schemaVersion", "view", "page", "resultCount", "substituteCount");
    assertThat(audit.get("resultCount").asInt()).isEqualTo(3);
    assertThat(audit.get("substituteCount").asInt()).isEqualTo(2);

    // Pages: authorization recomputed per page; the cursor is the caller's alone.
    JsonNode first =
        w.expect(
            get(MY_APPROVALS)
                .param("limit", "1")
                .header("Authorization", team.substitute().bearer()),
            200);
    assertThat(ids(first)).containsExactly(owns);
    String cursor = first.get("nextCursor").asText();
    w.problem(
        get(MY_APPROVALS)
            .param("limit", "1")
            .param("cursor", cursor)
            .header("Authorization", team.manager().bearer()),
        400,
        "CURSOR_INVALID");
    w.revoke(appointment);
    // Revoked between pages: the next page no longer holds delegated work.
    JsonNode next =
        w.expect(
            get(MY_APPROVALS)
                .param("limit", "1")
                .param("cursor", cursor)
                .header("Authorization", team.substitute().bearer()),
            200);
    assertThat(ids(next)).isEmpty();
    assertThat(capacities(w.expect(inbox(team.substitute().bearer()), 200)))
        .containsExactly(Map.entry(owns, "PRIMARY"));
    // The primary manager keeps all of it.
    assertThat(ids(w.expect(inbox(team.manager().bearer()), 200)))
        .containsExactlyInAnyOrder(reported, colleagues, substitutes);
  }

  @Test
  void delegatedWorkFollowsTheRequestsPrimaryManagerOnItsFirstDay() throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    // The report moves to the outsider from t + 20: a request starting then is the outsider's.
    w.manage(team.report().employee(), team.outsider().employee(), t.plusDays(20));
    String before = w.request(team.report(), team.managed(), t.plusDays(10), t.plusDays(10));
    String after = w.request(team.report(), team.managed(), t.plusDays(25), t.plusDays(25));
    w.appoint(team.manager(), team.substitute(), t, t.plusDays(60));
    assertThat(ids(w.expect(inbox(team.substitute().bearer()), 200)))
        .contains(before)
        .doesNotContain(after);
    w.problem(
        decide(
            MY_APPROVALS,
            team.substitute().bearer(),
            after,
            Organizations.newKey(),
            decision("APPROVED", "fr", DECIDE_REASON)),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
    // A correction of the line naming the manager hands the request to the outsider at once.
    w.correctManager(
        team.report().employee(), team.manager().employee(), team.outsider().employee());
    assertThat(ids(w.expect(inbox(team.substitute().bearer()), 200))).doesNotContain(before);
    assertThat(ids(w.expect(inbox(team.outsider().bearer()), 200))).contains(before, after);
    // A request no manager covers is a routing exception, whoever substitutes; the substitute
    // never overrides it.
    w.unmanage(team.colleague().employee(), t.plusDays(1));
    String uncovered = w.request(team.colleague(), team.managed(), t.plusDays(5), t.plusDays(5));
    assertThat(ids(w.expect(get(EXCEPTIONS).header("Authorization", w.admin), 200)))
        .contains(uncovered);
    assertThat(ids(w.expect(inbox(team.substitute().bearer()), 200))).doesNotContain(uncovered);
    assertThat(
            w.call(
                    decide(
                        EXCEPTIONS,
                        team.substitute().bearer(),
                        uncovered,
                        Organizations.newKey(),
                        decision("APPROVED", "fr", DECIDE_REASON)))
                .getStatus())
        .isIn(401, 403);
    // TENANT_ADMIN-routed requests never reach a substitute.
    String central = w.request(team.colleague(), team.central(), t.plusDays(40), t.plusDays(40));
    w.problem(
        decide(
            MY_APPROVALS,
            team.substitute().bearer(),
            central,
            Organizations.newKey(),
            decision("APPROVED", "fr", DECIDE_REASON)),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
  }

  // ------------------------------------------------------------------------------------------
  // Substitute decisions (D41G-2, D41G-4, D41G-7)
  // ------------------------------------------------------------------------------------------

  @Test
  void theSubstituteDecidesBesideAPrimaryManagerAndTheEmployeeSeesNoIdentity(CapturedOutput output)
      throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    String approvedId = w.request(team.report(), team.managed(), t.plusDays(10), t.plusDays(11));
    String rejectedId = w.request(team.colleague(), team.managed(), t.plusDays(12), t.plusDays(12));
    String primaryId = w.request(team.report(), team.managed(), t.plusDays(20), t.plusDays(20));
    String appointment = w.appoint(team.manager(), team.substitute(), t, t.plusDays(30));
    String key = Organizations.newKey();
    JsonNode receipt =
        w.expect(
            decide(
                MY_APPROVALS,
                team.substitute().bearer(),
                approvedId,
                key,
                decision("APPROVED", "fr", DECIDE_REASON)),
            200);
    assertThat(fieldNames(receipt))
        .containsExactlyInAnyOrder("requestId", "decisionId", "state", "decidedAt");
    w.expect(
        decide(
            MY_APPROVALS,
            team.substitute().bearer(),
            rejectedId,
            Organizations.newKey(),
            decision("REJECTED", "en", "Peak season, please pick other dates.")),
        200);
    // The primary manager keeps their authority and decides the third as MANAGER.
    w.approveAsManager(team.manager(), primaryId);
    // The first valid decision wins: the other approver's attempt is a conflict.
    w.problem(
        decide(
            MY_APPROVALS,
            team.manager().bearer(),
            approvedId,
            Organizations.newKey(),
            decision("REJECTED", "en", "Too late.")),
        409,
        "LEAVE_REQUEST_ALREADY_DECIDED");
    w.problem(
        decide(
            MY_APPROVALS,
            team.substitute().bearer(),
            primaryId,
            Organizations.newKey(),
            decision("REJECTED", "en", "Too late.")),
        409,
        "LEAVE_REQUEST_ALREADY_DECIDED");

    // The stored authority shapes.
    Map<String, Object> stored =
        jdbc.queryForMap(
            "SELECT approval_route, decision_authority, manager_employee_id,"
                + " delegated_from_manager_employee_id FROM people.leave_request_decision"
                + " WHERE request_id = ?::uuid",
            approvedId);
    assertThat(stored)
        .containsEntry("approval_route", "MANAGER")
        .containsEntry("decision_authority", "MANAGER_SUBSTITUTE")
        .containsEntry("manager_employee_id", team.substitute().employee())
        .containsEntry("delegated_from_manager_employee_id", team.manager().employee());
    assertThat(
            jdbc.queryForMap(
                "SELECT decision_authority, manager_employee_id,"
                    + " delegated_from_manager_employee_id FROM people.leave_request_decision"
                    + " WHERE request_id = ?::uuid",
                primaryId))
        .containsEntry("decision_authority", "MANAGER")
        .containsEntry("manager_employee_id", team.manager().employee())
        .containsEntry("delegated_from_manager_employee_id", null);
    assertThat(w.substituteDecisions()).containsExactly(2, 2, 2);
    // Both decided requests left both inboxes.
    assertThat(ids(w.expect(inbox(team.manager().bearer()), 200)))
        .doesNotContain(approvedId, rejectedId, primaryId);
    assertThat(ids(w.expect(inbox(team.substitute().bearer()), 200)))
        .doesNotContain(approvedId, rejectedId, primaryId);

    // Audit and events: the safe authority and the appointment, never a reason or an identity.
    JsonNode audit = metadata("leave-request.approve", approvedId);
    assertThat(fieldNames(audit))
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
            "substitutionId",
            "priorState",
            "resultingState");
    assertThat(audit.get("decisionAuthority").asText()).isEqualTo("MANAGER_SUBSTITUTE");
    assertThat(audit.get("substitutionId").asText()).isEqualTo(appointment);
    JsonNode approvedEvent = event("people.leave-request.substitute-approved.v1", approvedId);
    JsonNode rejectedEvent = event("people.leave-request.substitute-rejected.v1", rejectedId);
    assertThat(fieldNames(approvedEvent.get("data")))
        .containsExactlyInAnyOrder(
            "requestId",
            "decisionId",
            "employeeId",
            "employmentId",
            "policyId",
            "policyVersionId",
            "approvalRoute",
            "decisionAuthority",
            "substitutionId",
            "state");
    assertThat(rejectedEvent.get("data").get("state").asText()).isEqualTo("REJECTED");
    // The primary manager's decision keeps the unchanged v1 contract.
    assertThat(fieldNames(event("people.leave-request.approved.v1", primaryId).get("data")))
        .doesNotContain("decisionAuthority", "substitutionId");
    for (String evidence :
        List.of(audit.toString(), approvedEvent.toString(), rejectedEvent.toString())) {
      assertThat(evidence)
          .doesNotContain("absence")
          .doesNotContain("Peak season")
          .doesNotContain(team.substitute().employee().toString())
          .doesNotContain(team.manager().employee().toString())
          .doesNotContain(team.substitute().subject());
    }

    // The employee's history: outcome, reason and time only; no approver, capacity or appointment.
    MockHttpServletResponse history =
        w.call(get(MY_REQUESTS).header("Authorization", team.report().bearer()));
    JsonNode mine = null;
    for (JsonNode each : JSON.readTree(history.getContentAsString()).get("items")) {
      if (each.get("id").asText().equals(approvedId)) {
        mine = each;
      }
    }
    assertThat(mine).isNotNull();
    assertThat(fieldNames(mine.get("decision")))
        .containsExactlyInAnyOrder("id", "outcome", "reasonLocale", "reason", "decidedAt");
    assertThat(mine.get("decision").get("reason").asText()).isEqualTo(DECIDE_REASON);
    assertThat(history.getContentAsString())
        .doesNotContain(team.substitute().employee().toString())
        .doesNotContain(team.manager().employee().toString())
        .doesNotContain(appointment)
        .doesNotContain("SUBSTITUTE")
        .doesNotContain("Lukusa")
        .doesNotContain("Kabeya")
        .doesNotContain("delegated");
    assertThat(logs(output))
        .contains("leave_request_approved")
        .contains("MANAGER_SUBSTITUTE")
        .doesNotContain("absence du responsable")
        .doesNotContain("Peak season")
        .doesNotContain(key);
  }

  @Test
  void aSubstitutesDecisionReplaysAfterRevocationUnderTheCurrentLinkOnly() throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    String id = w.request(team.report(), team.managed(), t.plusDays(10), t.plusDays(10));
    String pending = w.request(team.colleague(), team.managed(), t.plusDays(12), t.plusDays(12));
    String appointment = w.appoint(team.manager(), team.substitute(), t, t.plusDays(30));
    Map<String, Object> body = decision("APPROVED", "fr", DECIDE_REASON);
    String key = Organizations.newKey();
    JsonNode receipt =
        w.expect(decide(MY_APPROVALS, team.substitute().bearer(), id, key, body), 200);

    // Revoked afterwards: the historical decision still replays; new work is refused.
    w.revoke(appointment);
    MockHttpServletResponse replay =
        w.call(decide(MY_APPROVALS, team.substitute().bearer(), id, key, body));
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(receipt);
    w.problem(
        decide(
            MY_APPROVALS,
            team.substitute().bearer(),
            id,
            key,
            decision("REJECTED", "fr", DECIDE_REASON)),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    w.problem(
        decide(MY_APPROVALS, team.substitute().bearer(), pending, Organizations.newKey(), body),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
    // The primary manager decides the undecided one.
    w.approveAsManager(team.manager(), pending);

    // Unlinked: the link is required; relinked to another employee: not theirs any more.
    w.unlink(team.substitute().employee());
    MockHttpServletResponse noLink =
        w.call(decide(MY_APPROVALS, team.substitute().bearer(), id, key, body));
    assertThat(noLink.getStatus()).isEqualTo(403);
    assertThat(noLink.getContentAsString()).doesNotContain(receipt.get("decisionId").asText());
    UUID another = w.hire("Marie", "Kalala");
    w.linkTo(another, team.substitute().membership());
    MockHttpServletResponse relinked =
        w.call(decide(MY_APPROVALS, team.substitute().bearer(), id, key, body));
    assertThat(relinked.getStatus()).isEqualTo(404);
    assertThat(JSON.readTree(relinked.getContentAsString()).get("code").asText())
        .isEqualTo("LEAVE_REQUEST_NOT_FOUND");
    assertThat(relinked.getContentAsString()).doesNotContain(receipt.get("decisionId").asText());
    // Back to the substitute's employee: it replays again.
    w.unlink(another);
    w.linkTo(team.substitute().employee(), team.substitute().membership());
    assertThat(
            w.call(decide(MY_APPROVALS, team.substitute().bearer(), id, key, body))
                .getHeader("Idempotent-Replayed"))
        .isEqualTo("true");
    assertThat(w.substituteDecisions()).containsExactly(1, 1, 1);
  }

  @Test
  void anExpiredAppointmentStillReplaysItsDecision() throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    String id = w.request(team.report(), team.managed(), t.plusDays(10), t.plusDays(10));
    w.appoint(team.manager(), team.substitute(), t, t);
    Map<String, Object> body = decision("REJECTED", "en", "Peak season, sorry.");
    String key = Organizations.newKey();
    w.expect(decide(MY_APPROVALS, team.substitute().bearer(), id, key, body), 200);
    // The business date moves past the last day (the zone a day behind expires it).
    String zone =
        jdbc.queryForObject(
            "SELECT timezone FROM tenant.organization WHERE id = ?", String.class, w.tenant());
    try {
      jdbc.update(
          "UPDATE tenant.organization SET timezone = ? WHERE id = ?",
          "Pacific/Kiritimati",
          w.tenant());
      LocalDate ahead = LocalDate.now(ZoneId.of("Pacific/Kiritimati"));
      if (ahead.isAfter(t)) {
        assertThat(w.expect(list(w.admin), 200).get("items").get(0).get("state").asText())
            .isEqualTo("EXPIRED");
      }
      assertThat(
              w.call(decide(MY_APPROVALS, team.substitute().bearer(), id, key, body))
                  .getHeader("Idempotent-Replayed"))
          .isEqualTo("true");
    } finally {
      jdbc.update("UPDATE tenant.organization SET timezone = ? WHERE id = ?", zone, w.tenant());
    }
  }

  // ------------------------------------------------------------------------------------------
  // Boundaries (D41G-1, D41G-3)
  // ------------------------------------------------------------------------------------------

  @Test
  void roleMfaLinkAndTenantBoundariesFailClosed() throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    String id = w.request(team.report(), team.managed(), t.plusDays(10), t.plusDays(10));
    String appointment = w.appoint(team.manager(), team.substitute(), t, t.plusDays(30));
    Map<String, Object> body =
        appointment(
            team.manager().employee(),
            team.colleague().employee(),
            t.plusDays(40),
            t.plusDays(41),
            "fr",
            APPOINT_REASON);
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant())
                .subject("sub-leave41g-pwd-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    // Employees (the manager and the substitute included) and password-only administrators
    // neither appoint, list nor revoke.
    for (String bearer :
        List.of(team.manager().bearer(), team.substitute().bearer(), passwordOnly)) {
      assertThat(w.call(appoint(bearer, Organizations.newKey(), body)).getStatus()).isIn(401, 403);
      assertThat(w.call(list(bearer)).getStatus()).isIn(401, 403);
      assertThat(
              w.call(
                      revoke(
                          bearer,
                          appointment,
                          Organizations.newKey(),
                          cancellation("en", REVOKE_REASON)))
                  .getStatus())
          .isIn(401, 403);
    }
    // An administrator is not an employee manager: the manager endpoints refuse them.
    assertThat(w.call(inbox(w.admin)).getStatus()).isIn(401, 403);
    assertThat(w.substitutions()).containsExactly(1, 0, 1, 1);

    // Another tenant's administrator cannot appoint this tenant's employees.
    LeaveWorld other = world();
    other.problem(appoint(other.admin, Organizations.newKey(), body), 404, "EMPLOYEE_NOT_FOUND");
    // Another tenant's employee with the same role learns nothing.
    Person foreigner = other.person("Esther", "Mutombo");
    assertThat(ids(other.expect(inbox(foreigner.bearer()), 200))).isEmpty();
    other.problem(
        decide(
            MY_APPROVALS,
            foreigner.bearer(),
            id,
            Organizations.newKey(),
            decision("APPROVED", "fr", DECIDE_REASON)),
        404,
        "LEAVE_REQUEST_NOT_FOUND");

    // An unlinked substitute neither lists nor decides.
    w.unlink(team.substitute().employee());
    w.problem(inbox(team.substitute().bearer()), 403, "EMPLOYEE_LINK_REQUIRED");
    w.problem(
        decide(
            MY_APPROVALS,
            team.substitute().bearer(),
            id,
            Organizations.newKey(),
            decision("APPROVED", "fr", DECIDE_REASON)),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    // Relinked to another employee: no delegated work.
    UUID another = w.hire("Marie", "Kalala");
    w.linkTo(another, team.substitute().membership());
    assertThat(ids(w.expect(inbox(team.substitute().bearer()), 200))).isEmpty();
    w.problem(
        decide(
            MY_APPROVALS,
            team.substitute().bearer(),
            id,
            Organizations.newKey(),
            decision("APPROVED", "fr", DECIDE_REASON)),
        404,
        "LEAVE_REQUEST_NOT_FOUND");
    assertThat(w.state(id)).isEqualTo("PENDING");
    assertThat(w.substituteDecisions()).containsExactly(0, 0, 0);
  }

  @Test
  void approvalStillNeedsTheEmploymentButRejectionDoesNot() throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    LocalDate t = w.today;
    String first = w.request(team.report(), team.managed(), t.plusDays(30), t.plusDays(35));
    String second = w.request(team.report(), team.managed(), t.plusDays(40), t.plusDays(41));
    w.appoint(team.manager(), team.substitute(), t, t.plusDays(60));
    w.expect(w.separate(team.report().employee(), w.separation(t.plusDays(32))), 201);
    JsonNode refused =
        w.problem(
            decide(
                MY_APPROVALS,
                team.substitute().bearer(),
                first,
                Organizations.newKey(),
                decision("APPROVED", "fr", DECIDE_REASON)),
            409,
            "LEAVE_REQUEST_NOT_ELIGIBLE");
    assertThat(refused.get("params").get("reason").asText()).isEqualTo("EMPLOYMENT_PERIOD");
    w.expect(
        decide(
            MY_APPROVALS,
            team.substitute().bearer(),
            first,
            Organizations.newKey(),
            decision("REJECTED", "fr", DECIDE_REASON)),
        200);
    assertThat(w.state(first)).isEqualTo("REJECTED");
    assertThat(w.state(second)).isEqualTo("PENDING");
  }

  // ------------------------------------------------------------------------------------------
  // Fail-closed disclosure (D41G-3)
  // ------------------------------------------------------------------------------------------

  @Test
  void noListIsReturnedWhenItsDisclosureAuditCannotBeWritten() throws Exception {
    Team team = team();
    LeaveWorld w = team.w();
    w.appoint(team.manager(), team.substitute(), w.today, w.today.plusDays(3));
    String function = "no_leave_substitution_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "CREATE FUNCTION public."
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action ="
            + " 'leave-approval-substitution.read' AND NEW.tenant_id = '"
            + w.tenant()
            + "' THEN RAISE EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + function
            + " BEFORE INSERT ON platform.audit_event FOR EACH ROW EXECUTE FUNCTION public."
            + function
            + "()");
    try {
      MockHttpServletResponse refused = w.call(list(w.admin));
      assertThat(refused.getStatus()).isEqualTo(500);
      assertThat(refused.getContentAsString())
          .doesNotContain("Lukusa")
          .doesNotContain("intérim")
          .doesNotContain("Remplacement");
    } finally {
      jdbc.execute("DROP TRIGGER " + function + " ON platform.audit_event");
      jdbc.execute("DROP FUNCTION public." + function + "()");
    }
    assertThat(w.expect(list(w.admin), 200).get("items")).hasSize(1);
  }
}
