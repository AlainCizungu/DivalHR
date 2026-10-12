package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.SCHEDULES;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.APPROVAL_REASON;
import static com.divalhr.core.people.schedule.SubstitutionCalls.REASON;
import static com.divalhr.core.people.schedule.SubstitutionCalls.REJECTION_REASON;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.candidates;
import static com.divalhr.core.people.schedule.SubstitutionCalls.decide;
import static com.divalhr.core.people.schedule.SubstitutionCalls.decisionBody;
import static com.divalhr.core.people.schedule.SubstitutionCalls.fieldNames;
import static com.divalhr.core.people.schedule.SubstitutionCalls.history;
import static com.divalhr.core.people.schedule.SubstitutionCalls.ids;
import static com.divalhr.core.people.schedule.SubstitutionCalls.inbox;
import static com.divalhr.core.people.schedule.SubstitutionCalls.reject;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.requestBody;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * MVP-051 end to end over the public API against real PostgreSQL (D51-10 items 1 to 14, 16 and 21):
 * an employee's request for their own future operational shift, its replay and the same 404 for
 * every undisclosable shift; the refused shifts (uncovered, started, cancelled, replaced,
 * substituted, pending) and fail-closed employment and manager rules; the effective manager's
 * dynamic inbox, never another employee's, an administrator's or a leave substitute's; candidates
 * restricted to eligible direct reports with minimal fields; approval moving only the operational
 * coverage, rejection keeping it; both schedules' views and the administrators' detail; voiding by
 * cancellation and replacement; decision-time re-evaluation; self-selection and chains refused;
 * replays re-authorized; and reasons, names, numbers and labels absent from logs, audit metadata,
 * events and errors.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class WorkScheduleSubstitutionIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  /** A team at the first site: the manager, two reports and a published schedule. */
  private record Team(
      ScheduleWorld w, Person mia, Person ana, Person ben, LocalDate day, String schedule) {

    String slot(int position) throws Exception {
      return slotIds(w, schedule).get(position);
    }
  }

  /** Mia manages Ana and Ben; Ana works 08-16 on {@code day} (slot 0); slot 1 is uncovered. */
  private Team team() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, ben);
    LocalDate day = w.today.plusDays(6);
    String schedule =
        w.published(
            proposal(
                w.site(),
                day,
                day,
                List.of(shift("a", day, 8, 16, ana.employee()), shift("u", day, 9, 12, null))));
    return new Team(w, mia, ana, ben, day, schedule);
  }

  /** Ana's request on her shift; its id. */
  private static String requested(Team t) throws Exception {
    return t.w()
        .expect(request(t.ana(), Organizations.newKey(), t.slot(0)), 201)
        .get("id")
        .asText();
  }

  private static String logs(CapturedOutput output) {
    return String.join(
        "\n", output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp")).toList());
  }

  private List<String> metadata(String tenant) {
    return jdbc.queryForList(
        "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?::uuid"
            + " AND action LIKE 'work-schedule-substitution.%'",
        String.class, tenant);
  }

  private List<String> events(String tenant) {
    return jdbc.queryForList(
        "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
            + " AND envelope ->> 'eventType' LIKE 'people.work-schedule-substitution.%'",
        String.class, tenant);
  }

  private String evidence(String scheduleId) {
    return jdbc.queryForObject(
        "SELECT (SELECT row_to_json(s)::text FROM people.work_schedule s WHERE s.id = ?::uuid)"
            + " || (SELECT string_agg(row_to_json(l)::text, ',' ORDER BY l.position)"
            + " FROM people.work_schedule_slot l WHERE l.schedule_id = ?::uuid)",
        String.class,
        scheduleId,
        scheduleId);
  }

  private JsonNode mine(Person person) throws Exception {
    return mine(person, "");
  }

  private JsonNode mine(Person person, String query) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get(ScheduleWorld.MINE + query).header("Authorization", person.bearer()))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return JSON.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }

  // ------------------------------------------------------------------------------------------
  // 1, 2: the request
  // ------------------------------------------------------------------------------------------

  @Test
  void anEmployeeRequestsASubstituteForTheirOwnFutureShift(CapturedOutput output) throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    String key = Organizations.newKey();
    MockHttpServletResponse created = w.call(request(t.ana(), key, t.slot(0)));
    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(created.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode body = JSON.readTree(created.getContentAsString());
    assertThat(fieldNames(body))
        .containsExactly(
            "id", "requestedAt", "state", "shift", "reasonLocale", "reason", "decision");
    assertThat(body.get("state").asText()).isEqualTo("PENDING");
    assertThat(body.get("reason").asText()).isEqualTo(REASON);
    assertThat(body.get("decision").isNull()).isTrue();
    JsonNode shift = body.get("shift");
    assertThat(shift.get("slotId").asText()).isEqualTo(t.slot(0));
    assertThat(shift.get("scheduleId").asText()).isEqualTo(t.schedule());
    assertThat(shift.get("start").asText()).isEqualTo(t.day() + "T08:00");
    assertThat(shift.get("labels").get("fr").asText()).isEqualTo("Caissière");
    String id = body.get("id").asText();

    // Stored with the single qualifying employment; the manager is never stored.
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT employee_id, employment_id, requested_by FROM"
                + " people.work_schedule_substitution_request WHERE id = ?::uuid",
            id);
    assertThat(row.get("employee_id")).isEqualTo(t.ana().employee());
    assertThat(row.get("employment_id"))
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT id FROM people.employment WHERE employee_id = ?",
                UUID.class,
                t.ana().employee()));
    assertThat(row.get("requested_by")).isEqualTo(t.ana().subject());
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_substitution_pending"
                    + " WHERE request_id = ?::uuid",
                id))
        .isEqualTo(1);

    // The exact retry replays; the history and the manager's inbox show it.
    MockHttpServletResponse replayed = w.call(request(t.ana(), key, t.slot(0)));
    assertThat(replayed.getStatus()).isEqualTo(201);
    assertThat(replayed.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replayed.getContentAsString()).get("id").asText()).isEqualTo(id);
    JsonNode mine = w.expect(history(t.ana().bearer()), 200);
    assertThat(ids(mine)).containsExactly(id);
    assertThat(mine.get("items").get(0).get("state").asText()).isEqualTo("PENDING");
    assertThat(mine.get("asOfInstant").isTextual()).isTrue();
    JsonNode inbox = w.expect(inbox(t.mia().bearer()), 200);
    assertThat(ids(inbox)).containsExactly(id);
    JsonNode item = inbox.get("items").get(0);
    assertThat(fieldNames(item))
        .containsExactly("id", "requestedAt", "employee", "reasonLocale", "reason", "shift");
    assertThat(fieldNames(item.get("employee")))
        .containsExactly("employeeNumber", "givenNames", "familyName");
    assertThat(item.get("employee").get("employeeNumber").asText()).isEqualTo(t.ana().number());
    assertThat(item.get("reason").asText()).isEqualTo(REASON);
    assertThat(item.get("shift").get("startAt").asText()).isNotBlank();

    // Audit and event: IDs and state only.
    String tenant = w.tenant().toString();
    assertThat(metadata(tenant)).isNotEmpty();
    assertThat(String.join("\n", metadata(tenant)))
        .contains(id)
        .doesNotContain(REASON)
        .doesNotContain("Kalala")
        .doesNotContain(t.ana().number())
        .doesNotContain("Caissière")
        .doesNotContain(t.ana().subject());
    List<String> events = events(tenant);
    assertThat(events).hasSize(1);
    assertThat(events.get(0))
        .contains("people.work-schedule-substitution.requested.v1")
        .contains(id)
        .doesNotContain(REASON)
        .doesNotContain("Kalala")
        .doesNotContain(t.ana().number())
        .doesNotContain("Cashier")
        .doesNotContain(t.ana().subject());
    assertThat(logs(output))
        .contains("work_schedule_substitution_requested")
        .doesNotContain(REASON)
        .doesNotContain("Kalala")
        .doesNotContain(t.ana().number())
        .doesNotContain("Caissière");
  }

  @Test
  void undisclosableShiftsAreTheSame404AndValuesAreNeverEchoed() throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    // Another employee's shift, an uncovered slot, an unknown and a malformed ID.
    for (String slot :
        List.of(t.slot(1), UUID.randomUUID().toString(), "not-a-slot", t.slot(0) + "x")) {
      JsonNode problem =
          w.problem(
              request(t.ben(), Organizations.newKey(), slot), 404, "WORK_SCHEDULE_SHIFT_NOT_FOUND");
      assertThat(problem.get("params").isEmpty()).isTrue();
      assertThat(problem.toString()).doesNotContain(slot).doesNotContain(REASON);
    }
    w.problem(
        request(t.ana(), Organizations.newKey(), t.slot(1)), 404, "WORK_SCHEDULE_SHIFT_NOT_FOUND");
    // Another tenant's employee asking for this tenant's slot.
    Team other = team();
    other
        .w()
        .problem(
            request(other.ana(), Organizations.newKey(), t.slot(0)),
            404,
            "WORK_SCHEDULE_SHIFT_NOT_FOUND");
    // No link first; validation names fields, never values.
    w.unlink(t.ben().employee());
    w.problem(request(t.ben(), Organizations.newKey(), t.slot(0)), 403, "EMPLOYEE_LINK_REQUIRED");
    JsonNode invalid =
        w.problem(
            request(t.ana().bearer(), null, requestBody(t.slot(0), "de", "x‮y")),
            400,
            "VALIDATION_FAILED");
    assertThat(invalid.toString()).doesNotContain(t.slot(0));
    assertThat(invalid.get("params").get("fields").toString())
        .contains("Idempotency-Key")
        .contains("reasonLocale")
        .contains("reason");
    w.problem(
        request(t.ana().bearer(), Organizations.newKey(), "{\"slotId\":null,\"x\":1}"),
        400,
        "VALIDATION_FAILED");
    // The tenant administrator has no employee self-service.
    assertThat(
            w.call(request(w.admin, Organizations.newKey(), requestBody(t.slot(0), "fr", REASON)))
                .getStatus())
        .isEqualTo(403);
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_substitution_request"
                    + " WHERE tenant_id = ?",
                w.tenant()))
        .isZero();
  }

  // ------------------------------------------------------------------------------------------
  // 3, 4: refused shifts and fail-closed employment
  // ------------------------------------------------------------------------------------------

  @Test
  void startedCancelledReplacedSubstitutedAndPendingShiftsCannotBeRequested() throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    String id = requested(t);
    w.problem(
        request(t.ana(), Organizations.newKey(), t.slot(0)),
        409,
        "WORK_SCHEDULE_SUBSTITUTION_ALREADY_PENDING");
    // After a rejection, a new request is allowed.
    w.expect(reject(t.mia(), id, Organizations.newKey()), 200);
    String again = requested(t);
    // After an approval, neither the original nor the substitute can request it (no chain).
    w.expect(approve(t.mia(), again, Organizations.newKey(), t.ben().employee()), 200);
    JsonNode substituted =
        w.problem(
            request(t.ana(), Organizations.newKey(), t.slot(0)),
            409,
            "WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE");
    assertThat(substituted.get("params").get("reason").asText()).isEqualTo("SUBSTITUTED");
    w.problem(
        request(t.ben(), Organizations.newKey(), t.slot(0)), 404, "WORK_SCHEDULE_SHIFT_NOT_FOUND");

    // A cancelled and a replaced schedule.
    Person cleo = w.person("Cléo", "Banza");
    w.manage(cleo.employee(), t.mia().employee(), w.today);
    LocalDate e = t.day().plusDays(1);
    String cancelled =
        w.published(proposal(w.site(), e, e, List.of(shift("c", e, 8, 12, cleo.employee()))));
    w.expect(cancel(w.admin, Organizations.newKey(), cancelled, "Fermeture du site"), 200);
    JsonNode voided =
        w.problem(
            request(cleo, Organizations.newKey(), slotIds(w, cancelled).get(0)),
            409,
            "WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE");
    assertThat(voided.get("params").get("reason").asText()).isEqualTo("NOT_OPERATIONAL");
    LocalDate f = e.plusDays(1);
    String original =
        w.published(proposal(w.site(), f, f, List.of(shift("o", f, 8, 12, cleo.employee()))));
    w.replaced(original, List.of(shift("r", f, 13, 17, cleo.employee())), "Réorganisation");
    assertThat(
            w.problem(
                    request(cleo, Organizations.newKey(), slotIds(w, original).get(0)),
                    409,
                    "WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE")
                .get("params")
                .get("reason")
                .asText())
        .isEqualTo("NOT_OPERATIONAL");

    // A started shift: the window closes exactly at its start.
    LocalDate g = f.plusDays(1);
    String started =
        w.published(proposal(w.site(), g, g, List.of(shift("s", g, 8, 12, cleo.employee()))));
    Instant start =
        jdbc.queryForObject(
                "SELECT start_at FROM people.work_schedule_slot WHERE schedule_id = ?::uuid",
                java.sql.Timestamp.class,
                started)
            .toInstant();
    clock.set(start);
    w.problem(
        request(cleo, Organizations.newKey(), slotIds(w, started).get(0)),
        409,
        "WORK_SCHEDULE_SUBSTITUTION_WINDOW_CLOSED");
    clock.set(start.minusSeconds(1));
    w.expect(request(cleo, Organizations.newKey(), slotIds(w, started).get(0)), 201);
  }

  @Test
  void noQualifyingEmploymentAndNoManagerFailClosed() throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    // Ana moves to the other site before her shift: no employment owns the shift's placement.
    w.expect(
        w.change(
            t.ana().employee(),
            w.placement(w.otherSite, w.otherSiteDepartment, t.day().minusDays(1))),
        201);
    assertThat(
            w.problem(
                    request(t.ana(), Organizations.newKey(), t.slot(0)),
                    409,
                    "WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE")
                .get("params")
                .get("reason")
                .asText())
        .isEqualTo("EMPLOYMENT");
    // Ben's manager line ends before his shift: no qualifying manager.
    LocalDate e = t.day().plusDays(1);
    String schedule =
        w.published(proposal(w.site(), e, e, List.of(shift("b", e, 8, 12, t.ben().employee()))));
    w.expect(w.change(t.ben().employee(), w.managerCommand(null, e.minusDays(1))), 201);
    assertThat(
            w.problem(
                    request(t.ben(), Organizations.newKey(), slotIds(w, schedule).get(0)),
                    409,
                    "WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE")
                .get("params")
                .get("reason")
                .asText())
        .isEqualTo("NO_MANAGER");
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_substitution_request"
                    + " WHERE tenant_id = ?",
                w.tenant()))
        .isZero();
  }

  // ------------------------------------------------------------------------------------------
  // 5, 6: the dynamic manager
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheCurrentEffectiveManagerSeesAndDecidesAndAHistoryChangeReroutes() throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    String id = requested(t);
    Person noa = w.person("Noa", "Tshala");
    // Another employee, the requester herself, a leave-approval substitute of Mia and an
    // administrator see nothing.
    assertThat(ids(w.expect(inbox(noa.bearer()), 200))).isEmpty();
    assertThat(ids(w.expect(inbox(t.ana().bearer()), 200))).isEmpty();
    assertThat(ids(w.expect(inbox(t.ben().bearer()), 200))).isEmpty();
    w.expect(
        Employees.postJson(
                w.admin,
                "/api/v1/leave-approval-substitutions",
                JSON.writeValueAsString(
                    Map.of(
                        "managerEmployeeId", t.mia().employee().toString(),
                        "substituteEmployeeId", noa.employee().toString(),
                        "effectiveFrom", w.today.toString(),
                        "effectiveTo", t.day().plusDays(5).toString(),
                        "reasonLocale", "fr",
                        "reason", "Absence prévue de la responsable")))
            .header("Idempotency-Key", Organizations.newKey()),
        201);
    assertThat(ids(w.expect(inbox(noa.bearer()), 200))).isEmpty();
    w.problem(
        approve(noa, id, Organizations.newKey(), t.ben().employee()),
        404,
        "WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND");
    assertThat(w.call(inbox(w.admin)).getStatus()).isEqualTo(403);
    assertThat(
            w.call(
                    decide(
                        w.admin,
                        id,
                        Organizations.newKey(),
                        decisionBody("REJECTED", null, "fr", REJECTION_REASON)))
                .getStatus())
        .isEqualTo(403);
    // Undisclosable requests are the same 404 for decisions and candidates.
    for (String unknown : List.of(UUID.randomUUID().toString(), "nope")) {
      w.problem(
          reject(t.mia(), unknown, Organizations.newKey()),
          404,
          "WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND");
      w.problem(
          candidates(t.mia().bearer(), unknown, "Ben"),
          404,
          "WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND");
    }

    // Ana now reports to Noa from before the shift: the request moves at once.
    w.manage(t.ana().employee(), noa.employee(), t.day().minusDays(1));
    assertThat(ids(w.expect(inbox(t.mia().bearer()), 200))).isEmpty();
    assertThat(ids(w.expect(inbox(noa.bearer()), 200))).containsExactly(id);
    w.problem(
        reject(t.mia(), id, Organizations.newKey()), 404, "WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND");
    w.problem(candidates(t.mia().bearer(), id, "Ben"), 404, "WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND");
    w.expect(reject(noa, id, Organizations.newKey()), 200);
  }

  // ------------------------------------------------------------------------------------------
  // 7: candidates
  // ------------------------------------------------------------------------------------------

  @Test
  void candidatesAreOnlyEligibleDirectReportsWithMinimalFields() throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    Person bea = w.person("Béatrice", "Ilunga");
    Person bob = w.person("Bob", "Ilunga");
    Person bill = w.person("Bill", "Ilunga");
    Person boris = w.person("Boris", "Ilunga");
    Person noa = w.person("Noa", "Ilunga");
    w.manageAll(t.mia(), bea, bob, bill, boris);
    // Noa is another manager's report; Bob leaves before the shift ends; Bill moves to the other
    // site; Boris already works an overlapping shift there.
    w.manage(noa.employee(), t.ana().employee(), w.today);
    w.expect(w.separate(bob.employee(), t.day().minusDays(1)), 201);
    w.expect(
        w.change(
            bill.employee(), w.placement(w.otherSite, w.otherSiteDepartment, t.day().minusDays(2))),
        201);
    w.expect(
        w.change(
            boris.employee(),
            w.placement(w.otherSite, w.otherSiteDepartment, t.day().minusDays(2))),
        201);
    w.published(
        proposal(
            w.otherSite, t.day(), t.day(), List.of(shift("o", t.day(), 15, 18, boris.employee()))));
    String id = requested(t);

    JsonNode page = w.expect(candidates(t.mia().bearer(), id, "ilunga"), 200);
    assertThat(page.get("items")).hasSize(2);
    List<String> found = new ArrayList<>();
    page.get("items")
        .forEach(
            item -> {
              assertThat(fieldNames(item))
                  .containsExactly("id", "employeeNumber", "givenNames", "familyName");
              found.add(item.get("id").asText());
            });
    assertThat(found)
        .containsExactlyInAnyOrder(t.ben().employee().toString(), bea.employee().toString());
    // Accent- and case-insensitive name words, and an employee-number prefix.
    assertThat(ids(w.expect(candidates(t.mia().bearer(), id, "BEATRICE"), 200)))
        .containsExactly(bea.employee().toString());
    assertThat(ids(w.expect(candidates(t.mia().bearer(), id, t.ben().number()), 200)))
        .containsExactly(t.ben().employee().toString());
    // Never the requester or the manager.
    assertThat(ids(w.expect(candidates(t.mia().bearer(), id, "Kalala"), 200))).isEmpty();
    assertThat(ids(w.expect(candidates(t.mia().bearer(), id, "Mbuyi"), 200))).isEmpty();
    // Pages of one, bound to the request, the query and the caller.
    JsonNode first =
        w.expect(candidates(t.mia().bearer(), id, "ilunga").queryParam("limit", "1"), 200);
    assertThat(first.get("items")).hasSize(1);
    String cursor = first.get("nextCursor").asText();
    JsonNode second =
        w.expect(
            candidates(t.mia().bearer(), id, "ilunga")
                .queryParam("limit", "1")
                .queryParam("cursor", cursor),
            200);
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("nextCursor").isNull()).isTrue();
    w.problem(
        candidates(t.mia().bearer(), id, "ilung")
            .queryParam("limit", "1")
            .queryParam("cursor", cursor),
        400,
        "CURSOR_INVALID");
    // Query and limit validation, never echoed.
    for (String query : List.of("a", " b ")) {
      JsonNode problem =
          w.problem(candidates(t.mia().bearer(), id, query), 400, "VALIDATION_FAILED");
      assertThat(problem.get("params").get("fields").toString()).contains("query");
    }
    w.problem(candidates(t.mia().bearer(), id, null), 400, "VALIDATION_FAILED");
    w.problem(
        candidates(t.mia().bearer(), id, "ilunga").queryParam("limit", "21"),
        400,
        "VALIDATION_FAILED");
    // The disclosure audit carries a digest of the query, never the query.
    assertThat(String.join("\n", metadata(w.tenant().toString())))
        .contains("substitute-candidates")
        .doesNotContain("ilunga")
        .doesNotContain("BEATRICE");
  }

  // ------------------------------------------------------------------------------------------
  // 8 to 11: approval and rejection
  // ------------------------------------------------------------------------------------------

  @Test
  void approvalMovesOnlyTheOperationalCoverageAndBothViewsFollow(CapturedOutput output)
      throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    String before = evidence(t.schedule());
    assertThat(ids(mine(t.ana()))).containsExactly(t.slot(0));
    String id = requested(t);
    String key = Organizations.newKey();
    MockHttpServletResponse decided = w.call(approve(t.mia(), id, key, t.ben().employee()));
    assertThat(decided.getStatus()).isEqualTo(200);
    JsonNode receipt = JSON.readTree(decided.getContentAsString());
    assertThat(fieldNames(receipt))
        .containsExactly("requestId", "decisionId", "state", "decidedAt");
    assertThat(receipt.get("state").asText()).isEqualTo("APPROVED");
    assertThat(decided.getContentAsString()).doesNotContain(APPROVAL_REASON).doesNotContain(REASON);

    // The published evidence is byte-identical; only the coverage moved.
    assertThat(evidence(t.schedule())).isEqualTo(before);
    Map<String, Object> coverage =
        jdbc.queryForMap(
            "SELECT employee_id, decision_id FROM people.work_schedule_slot_coverage"
                + " WHERE slot_id = ?::uuid",
            t.slot(0));
    assertThat(coverage.get("employee_id")).isEqualTo(t.ben().employee());
    assertThat(coverage.get("decision_id").toString())
        .isEqualTo(receipt.get("decisionId").asText());
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_substitution_pending"
                    + " WHERE request_id = ?::uuid",
                id))
        .isZero();

    // Ana no longer works it; Ben does, as a substitution, without Ana or either reason.
    assertThat(ids(mine(t.ana()))).isEmpty();
    JsonNode bens = mine(t.ben());
    assertThat(ids(bens)).containsExactly(t.slot(0));
    assertThat(bens.get("items").get(0).get("assignmentSource").asText()).isEqualTo("SUBSTITUTION");
    assertThat(bens.toString())
        .doesNotContain("Kalala")
        .doesNotContain(REASON)
        .doesNotContain(APPROVAL_REASON);
    // An explicit-dates view reads the coverage too.
    assertThat(ids(mine(t.ben(), "?from=" + t.day()))).containsExactly(t.slot(0));
    // Ana's history: approved, with Ben's minimal identity and the reason.
    JsonNode item = w.expect(history(t.ana().bearer()), 200).get("items").get(0);
    assertThat(item.get("state").asText()).isEqualTo("APPROVED");
    JsonNode decision = item.get("decision");
    assertThat(fieldNames(decision))
        .containsExactly("outcome", "decidedAt", "reasonLocale", "reason", "substitute");
    assertThat(decision.get("reason").asText()).isEqualTo(APPROVAL_REASON);
    assertThat(fieldNames(decision.get("substitute")))
        .containsExactly("id", "employeeNumber", "givenNames", "familyName");
    assertThat(decision.get("substitute").get("id").asText())
        .isEqualTo(t.ben().employee().toString());
    assertThat(item.toString()).doesNotContain(t.mia().employee().toString());

    // The administrators' detail: published history and current coverage side by side.
    JsonNode slot =
        w.expect(get(SCHEDULES + "/" + t.schedule()).header("Authorization", w.admin), 200)
            .get("slots")
            .get(0);
    assertThat(slot.get("employee").get("id").asText()).isEqualTo(t.ana().employee().toString());
    assertThat(slot.get("effectiveEmployee").get("id").asText())
        .isEqualTo(t.ben().employee().toString());
    assertThat(fieldNames(slot.get("substitution")))
        .containsExactly("requestId", "decisionId", "approvedAt");
    assertThat(slot.get("substitution").get("requestId").asText()).isEqualTo(id);
    assertThat(slot.toString()).doesNotContain(REASON).doesNotContain(APPROVAL_REASON);

    // Replays: same receipt; a second decision with another key is already decided.
    MockHttpServletResponse replayed = w.call(approve(t.mia(), id, key, t.ben().employee()));
    assertThat(replayed.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replayed.getContentAsString())).isEqualTo(receipt);
    w.problem(
        reject(t.mia(), id, Organizations.newKey()),
        409,
        "WORK_SCHEDULE_SUBSTITUTION_ALREADY_DECIDED");

    String tenant = w.tenant().toString();
    String audit = String.join("\n", metadata(tenant));
    assertThat(audit)
        .contains("\"authority\": \"MANAGER\"")
        .doesNotContain(APPROVAL_REASON)
        .doesNotContain(REASON)
        .doesNotContain("Ilunga")
        .doesNotContain(t.ben().number())
        .doesNotContain(t.mia().subject());
    String events = String.join("\n", events(tenant));
    assertThat(events)
        .contains("people.work-schedule-substitution.approved.v1")
        .contains(t.ben().employee().toString())
        .doesNotContain(APPROVAL_REASON)
        .doesNotContain("Ilunga")
        .doesNotContain(t.ben().number())
        .doesNotContain("Caissière")
        .doesNotContain(t.mia().subject());
    assertThat(logs(output))
        .contains("work_schedule_substitution_approved")
        .doesNotContain(APPROVAL_REASON)
        .doesNotContain(REASON)
        .doesNotContain("Ilunga")
        .doesNotContain(t.ben().number());
  }

  @Test
  void aRejectionKeepsTheOriginalCoverage() throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    String id = requested(t);
    w.problem(
        decide(
            t.mia().bearer(),
            id,
            Organizations.newKey(),
            decisionBody("REJECTED", t.ben().employee(), "fr", REJECTION_REASON)),
        400,
        "VALIDATION_FAILED");
    w.problem(
        decide(
            t.mia().bearer(),
            id,
            Organizations.newKey(),
            decisionBody("APPROVED", null, "fr", APPROVAL_REASON)),
        400,
        "VALIDATION_FAILED");
    JsonNode receipt = w.expect(reject(t.mia(), id, Organizations.newKey()), 200);
    assertThat(receipt.get("state").asText()).isEqualTo("REJECTED");
    assertThat(ids(mine(t.ana()))).containsExactly(t.slot(0));
    assertThat(mine(t.ana()).get("items").get(0).get("assignmentSource").asText())
        .isEqualTo("PUBLISHED");
    assertThat(ids(mine(t.ben()))).isEmpty();
    JsonNode item = w.expect(history(t.ana().bearer()), 200).get("items").get(0);
    assertThat(item.get("state").asText()).isEqualTo("REJECTED");
    assertThat(item.get("decision").get("reason").asText()).isEqualTo(REJECTION_REASON);
    assertThat(item.get("decision").get("substitute").isNull()).isTrue();
    assertThat(ids(w.expect(inbox(t.mia().bearer()), 200))).isEmpty();
    JsonNode slot =
        w.expect(get(SCHEDULES + "/" + t.schedule()).header("Authorization", w.admin), 200)
            .get("slots")
            .get(0);
    assertThat(slot.get("effectiveEmployee").get("id").asText())
        .isEqualTo(t.ana().employee().toString());
    assertThat(slot.get("substitution").isNull()).isTrue();
  }

  @Test
  void anUndecidedRequestExpiresWhenTheShiftStarts() throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    String id = requested(t);
    Instant start =
        jdbc.queryForObject(
                "SELECT start_at FROM people.work_schedule_slot WHERE id = ?::uuid",
                java.sql.Timestamp.class,
                t.slot(0))
            .toInstant();
    clock.set(start);
    assertThat(w.expect(history(t.ana().bearer()), 200).get("items").get(0).get("state").asText())
        .isEqualTo("EXPIRED");
    assertThat(ids(w.expect(inbox(t.mia().bearer()), 200))).isEmpty();
    w.problem(
        approve(t.mia(), id, Organizations.newKey(), t.ben().employee()),
        409,
        "WORK_SCHEDULE_SUBSTITUTION_WINDOW_CLOSED");
    w.problem(candidates(t.mia().bearer(), id, "Ben"), 404, "WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND");
  }

  // ------------------------------------------------------------------------------------------
  // 12: cancellation and replacement
  // ------------------------------------------------------------------------------------------

  @Test
  void aCancellationVoidsPendingRequestsAndReleasesSubstitutedCoverage() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    Person cleo = w.person("Cléo", "Banza");
    w.manageAll(mia, ana, ben, cleo);
    LocalDate day = w.today.plusDays(6);
    String schedule =
        w.published(
            proposal(
                w.site(),
                day,
                day,
                List.of(
                    shift("a", day, 8, 12, ana.employee()),
                    shift("c", day, 13, 17, cleo.employee()))));
    List<String> slots = slotIds(w, schedule);
    String approved =
        w.expect(request(ana, Organizations.newKey(), slots.get(0)), 201).get("id").asText();
    w.expect(approve(mia, approved, Organizations.newKey(), ben.employee()), 200);
    String pending =
        w.expect(request(cleo, Organizations.newKey(), slots.get(1)), 201).get("id").asText();
    w.expect(cancel(w.admin, Organizations.newKey(), schedule, "Fermeture du site"), 200);

    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_slot_coverage WHERE schedule_id ="
                    + " ?::uuid",
                schedule))
        .isZero();
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_substitution_pending WHERE schedule_id"
                    + " = ?::uuid",
                schedule))
        .isZero();
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_substitution_request WHERE schedule_id"
                    + " = ?::uuid",
                schedule))
        .isEqualTo(2);
    assertThat(ids(mine(ben))).isEmpty();
    assertThat(w.expect(history(cleo.bearer()), 200).get("items").get(0).get("state").asText())
        .isEqualTo("VOIDED");
    assertThat(w.expect(history(ana.bearer()), 200).get("items").get(0).get("state").asText())
        .isEqualTo("APPROVED");
    assertThat(ids(w.expect(inbox(mia.bearer()), 200))).isEmpty();
    w.problem(
        reject(mia, pending, Organizations.newKey()),
        409,
        "WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE");
    // The released instants are free again for Ben.
    w.published(proposal(w.otherSite, day, day, List.of(shift("o", day, 9, 11, null))));
    LocalDate next = day.plusDays(1);
    w.published(proposal(w.site(), next, next, List.of(shift("n", next, 8, 9, ben.employee()))));
  }

  @Test
  void aReplacementIgnoresTheReplacedCoverageIncludingSubstitutionsAndCarriesNothingOver()
      throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    String id = requested(t);
    w.expect(approve(t.mia(), id, Organizations.newKey(), t.ben().employee()), 200);
    // Ben, the substitute, may be assigned the very same instants in the replacement.
    String successor =
        w.replaced(
            t.schedule(),
            List.of(shift("r", t.day(), 8, 16, t.ben().employee())),
            "Réorganisation");
    assertThat(w.status(t.schedule())).isEqualTo("REPLACED");
    JsonNode bens = mine(t.ben());
    assertThat(bens.get("items")).hasSize(1);
    assertThat(bens.get("items").get(0).get("scheduleId").asText()).isEqualTo(successor);
    assertThat(bens.get("items").get(0).get("assignmentSource").asText()).isEqualTo("PUBLISHED");
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_substitution_request WHERE schedule_id"
                    + " = ?::uuid",
                successor))
        .isZero();
    assertThat(ids(mine(t.ana()))).isEmpty();
  }

  // ------------------------------------------------------------------------------------------
  // 13, 14: decision-time re-evaluation, self-selection and chains
  // ------------------------------------------------------------------------------------------

  @Test
  void theSubstituteIsReEvaluatedWhenTheManagerDecides() throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    Person bea = w.person("Bea", "Ilunga");
    Person bob = w.person("Bob", "Ilunga");
    Person bill = w.person("Bill", "Ilunga");
    w.manageAll(t.mia(), bea, bob, bill);
    String id = requested(t);
    assertThat(ids(w.expect(candidates(t.mia().bearer(), id, "Ilunga"), 200))).hasSize(4);
    // After the search: Bea gets an overlapping shift elsewhere, Bob a separation, Bill a move.
    w.expect(
        w.change(
            bea.employee(), w.placement(w.otherSite, w.otherSiteDepartment, w.today.plusDays(1))),
        201);
    w.published(
        proposal(
            w.otherSite, t.day(), t.day(), List.of(shift("o", t.day(), 15, 18, bea.employee()))));
    w.expect(w.change(bea.employee(), w.placement(w.site(), w.org.departmentId(), t.day())), 201);
    w.expect(w.separate(bob.employee(), t.day().minusDays(1)), 201);
    w.expect(
        w.change(
            bill.employee(), w.placement(w.otherSite, w.otherSiteDepartment, t.day().minusDays(1))),
        201);
    w.problem(
        approve(t.mia(), id, Organizations.newKey(), bea.employee()),
        409,
        "WORK_SCHEDULE_SUBSTITUTE_CONFLICT");
    for (Person refused : List.of(bob, bill)) {
      JsonNode problem =
          w.problem(
              approve(t.mia(), id, Organizations.newKey(), refused.employee()),
              422,
              "WORK_SCHEDULE_SUBSTITUTE_NOT_ELIGIBLE");
      assertThat(problem.get("params").isEmpty()).isTrue();
      assertThat(problem.toString()).doesNotContain(refused.employee().toString());
    }
    // Ben stops reporting to Mia: refused the same way, like a foreign or unknown employee.
    Person noa = w.person("Noa", "Tshala");
    w.manage(t.ben().employee(), noa.employee(), t.day().minusDays(1));
    Team other = team();
    for (UUID refused : List.of(t.ben().employee(), other.ben().employee(), UUID.randomUUID())) {
      w.problem(
          approve(t.mia(), id, Organizations.newKey(), refused),
          422,
          "WORK_SCHEDULE_SUBSTITUTE_NOT_ELIGIBLE");
    }
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule_substitution_decision"
                    + " WHERE request_id = ?::uuid",
                id))
        .isZero();
    assertThat(ids(mine(t.ana()))).containsExactly(t.slot(0));
  }

  @Test
  void theManagerCannotChooseThemselvesOrTheRequesterAndNoChainIsPossible() throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    // Mia is herself placed at the site and reports to someone: still never a candidate.
    Person zoe = w.person("Zoe", "Mbuyi");
    w.manage(t.mia().employee(), zoe.employee(), w.today);
    String id = requested(t);
    for (UUID refused : List.of(t.mia().employee(), t.ana().employee())) {
      w.problem(
          approve(t.mia(), id, Organizations.newKey(), refused),
          422,
          "WORK_SCHEDULE_SUBSTITUTE_NOT_ELIGIBLE");
    }
    w.expect(approve(t.mia(), id, Organizations.newKey(), t.ben().employee()), 200);
    // The substituted shift cannot be requested again, by either employee.
    w.problem(
        request(t.ben(), Organizations.newKey(), t.slot(0)), 404, "WORK_SCHEDULE_SHIFT_NOT_FOUND");
    w.problem(
        request(t.ana(), Organizations.newKey(), t.slot(0)),
        409,
        "WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE");
  }

  // ------------------------------------------------------------------------------------------
  // 16: replays re-authorize
  // ------------------------------------------------------------------------------------------

  @Test
  void replaysAreReAuthorizedAndNeverLeakAStoredResponse() throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    String requestKey = Organizations.newKey();
    String id = w.expect(request(t.ana(), requestKey, t.slot(0)), 201).get("id").asText();
    String decisionKey = Organizations.newKey();
    w.expect(reject(t.mia(), id, decisionKey), 200);

    // The manager line moves away: the decision replay is the same 404, without any ID.
    Person noa = w.person("Noa", "Tshala");
    w.manage(t.ana().employee(), noa.employee(), t.day().minusDays(1));
    JsonNode denied =
        w.problem(reject(t.mia(), id, decisionKey), 404, "WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND");
    assertThat(denied.toString()).doesNotContain(id);

    // The requester loses her link: 403; relinked as Ben's membership: 409 without params.
    w.unlink(t.ana().employee());
    w.problem(request(t.ana(), requestKey, t.slot(0)), 403, "EMPLOYEE_LINK_REQUIRED");
    w.unlink(t.ben().employee());
    w.expect(
        Employees.postJson(
                w.admin,
                ScheduleWorld.EMPLOYEES + "/" + t.ben().employee() + "/access-link",
                "{\"membershipId\":\"" + t.ana().membership() + "\"}")
            .header("Idempotency-Key", Organizations.newKey()),
        201);
    JsonNode reused =
        w.problem(request(t.ana(), requestKey, t.slot(0)), 409, "IDEMPOTENCY_KEY_REUSED");
    assertThat(reused.get("params").isEmpty()).isTrue();
    assertThat(reused.toString()).doesNotContain(id);
  }

  @Test
  void aChangedBodyUnderTheSameKeyIsRefusedBeforeAnyLookup() throws Exception {
    Team t = team();
    ScheduleWorld w = t.w();
    String key = Organizations.newKey();
    w.expect(request(t.ana(), key, t.slot(0)), 201);
    Map<String, Object> other = new LinkedHashMap<>(requestBody(t.slot(0), "en", "Training day"));
    w.problem(request(t.ana().bearer(), key, other), 409, "IDEMPOTENCY_KEY_REUSED");
    w.problem(
        request(t.ana().bearer(), key, JSON.writeValueAsString(other))
            .contentType(MediaType.APPLICATION_JSON),
        409,
        "IDEMPOTENCY_KEY_REUSED");
  }
}
