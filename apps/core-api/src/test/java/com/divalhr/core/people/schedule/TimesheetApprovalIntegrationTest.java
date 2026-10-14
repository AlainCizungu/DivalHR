package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.CorrectionCalls.commit;
import static com.divalhr.core.people.schedule.CorrectionCalls.commitRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.previewRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.values;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approval;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approve;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.decision;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.giveBack;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.queue;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submission;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submit;
import static com.divalhr.core.people.schedule.TimesheetCalls.confirmed;
import static com.divalhr.core.people.schedule.TimesheetCalls.generate;
import static com.divalhr.core.people.schedule.TimesheetCalls.generation;
import static com.divalhr.core.people.schedule.TimesheetCalls.mine;
import static com.divalhr.core.people.schedule.TimesheetCalls.previewRefresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refreshBody;
import static com.divalhr.core.people.schedule.TimesheetCalls.timesheet;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-070B/C (Issue #130, T70BC-13 items 1 to 11, 13, 14, 16 and 17) against real PostgreSQL: a
 * clean draft submitted by its linked employee only, to the manager effective on the site-local
 * Sunday; every blocker, stale evidence and foreign target refused before writing; the route
 * resolved again for the queue, reads and decisions (a manager change moves the authority at once);
 * an approval that is final and stops refreshes; a return that restores the draft for a refresh and
 * the next cycle; idempotent replays; tenant administrators reading, never deciding; and the
 * privacy of responses, audit metadata, events and logs.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class TimesheetApprovalIntegrationTest {

  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");
  private static final String REASON = "Le lundi doit être corrigé avec la pause déclarée.";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String hr(ScheduleWorld w) {
    return Hierarchy.bearer(w.tenant(), "sub-tsa-" + UUID.randomUUID(), "tenant-admin");
  }

  private static String key() {
    return Organizations.newKey();
  }

  /**
   * A generated week (the clock is its Sunday noon at the site): Mia manages Ana, Ben and Dan; Cid
   * has no manager; Zoé manages nobody.
   *
   * <ul>
   *   <li>Ana: Monday and Tuesday checked in and out (two READY rows, clean).
   *   <li>Ben: Monday checked in only (OPEN_ATTENDANCE).
   *   <li>Cid: Monday checked in and out (clean, but no approval route).
   *   <li>Dan: Tuesday without attendance (MISSING_ATTENDANCE, no READY row).
   * </ul>
   */
  private record Week(
      ScheduleWorld w,
      Person mia,
      Person zoe,
      Person ana,
      Person ben,
      Person cid,
      Person dan,
      LocalDate monday,
      String generationId,
      Map<UUID, String> drafts,
      String anaMonday) {

    String draft(Person person) {
      return drafts.get(person.employee());
    }

    Map<String, Object> period() {
      return TimesheetCalls.period(w.site(), monday);
    }

    LocalDate sunday() {
      return monday.plusDays(6);
    }
  }

  private Week week() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person zoe = w.person("Zoé", "Lunda");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    Person cid = w.person("Cid", "Tshibanda");
    Person dan = w.person("Dan", "Mukendi");
    w.manageAll(mia, ana, ben, dan);
    LocalDate monday = Timesheet.Period.mondayOf(w.today).plusDays(7);
    List<String> slots =
        slotIds(
            w,
            w.published(
                proposal(
                    w.site(),
                    monday,
                    monday.plusDays(1),
                    List.of(
                        shift("a0", monday, 8, 16, ana.employee()),
                        shift("b0", monday, 8, 16, ben.employee()),
                        shift("c0", monday, 8, 16, cid.employee()),
                        shift("a1", monday.plusDays(1), 8, 16, ana.employee()),
                        shift("d1", monday.plusDays(1), 8, 16, dan.employee())))));
    Instant[] monday0 = instants(jdbc, slots.get(0));
    Instant[] tuesday = instants(jdbc, slots.get(3));
    clock.set(monday0[0].plus(Duration.ofMinutes(5)));
    String a0 = w.expect(checkIn(ana, key(), slots.get(0)), 201).get("attendanceRecordId").asText();
    w.expect(checkIn(ben, key(), slots.get(1)), 201);
    String c0 = w.expect(checkIn(cid, key(), slots.get(2)), 201).get("attendanceRecordId").asText();
    clock.set(monday0[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), a0), 200);
    w.expect(checkOut(cid, key(), c0), 200);
    clock.set(tuesday[0].plus(Duration.ofMinutes(5)));
    String a1 = w.expect(checkIn(ana, key(), slots.get(3)), 201).get("attendanceRecordId").asText();
    clock.set(tuesday[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), a1), 200);
    clock.set(monday.plusDays(6).atTime(LocalTime.NOON).atZone(KINSHASA).toInstant());
    String generationId =
        w.expect(generate(hr(w), key(), confirmed(TimesheetCalls.period(w.site(), monday))), 200)
            .get("generation")
            .get("generationId")
            .asText();
    Map<UUID, String> drafts = new LinkedHashMap<>();
    w.expect(generation(hr(w), generationId), 200)
        .get("timesheets")
        .forEach(
            d ->
                drafts.put(
                    UUID.fromString(d.get("employee").get("id").asText()),
                    d.get("timesheetId").asText()));
    return new Week(w, mia, zoe, ana, ben, cid, dan, monday, generationId, drafts, a0);
  }

  private int actions(ScheduleWorld w) {
    return w.count("SELECT count(*) FROM people.timesheet_action WHERE tenant_id = ?", w.tenant());
  }

  /** Actions, timesheet audit records of the lifecycle and lifecycle events of the tenant. */
  private List<Integer> written(ScheduleWorld w) {
    return List.of(
        actions(w),
        w.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action IN"
                + " ('timesheet.submit', 'timesheet.approve', 'timesheet.return')",
            w.tenant()),
        w.count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ? AND"
                + " envelope ->> 'eventType' IN ('people.timesheet.submitted.v1',"
                + " 'people.timesheet.approved.v1', 'people.timesheet.returned.v1')",
            w.tenant().toString()));
  }

  private static Set<String> fields(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static List<String> kinds(JsonNode actions) {
    List<String> kinds = new ArrayList<>();
    actions.forEach(a -> kinds.add(a.get("action").asText() + ":" + a.get("cycle").asInt()));
    return kinds;
  }

  /** Submits Ana's reviewed draft; returns the receipt. */
  private static JsonNode submitted(Week t) throws Exception {
    JsonNode mine = t.w().expect(mine(t.ana().bearer(), t.draft(t.ana())), 200);
    return t.w().expect(submit(t.ana().bearer(), key(), t.draft(t.ana()), submission(mine)), 200);
  }

  // ------------------------------------------------------------------------------------------
  // 1, 8, 11, 13, 14, 16: submission, approval, immutability
  // ------------------------------------------------------------------------------------------

  @Test
  void aCleanDraftIsSubmittedThenApprovedByItsRoutedManagerAndBecomesFinal() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String id = t.draft(t.ana());
    String admin = hr(w);

    JsonNode mine = w.expect(mine(t.ana().bearer(), id), 200);
    assertThat(mine.get("status").asText()).isEqualTo("DRAFT");
    assertThat(mine.get("submissionCycle").asInt()).isZero();
    assertThat(mine.get("generationVersion").asInt()).isEqualTo(1);
    assertThat(mine.get("submission").get("eligible").asBoolean()).isTrue();
    assertThat(mine.get("submission").get("blockers")).isEmpty();
    assertThat(mine.get("actions")).isEmpty();

    String key = key();
    Map<String, Object> body = submission(mine);
    JsonNode receipt = w.expect(submit(t.ana().bearer(), key, id, body), 200);
    assertThat(fields(receipt))
        .containsExactly(
            "action",
            "actionId",
            "cycle",
            "generationVersion",
            "occurredAt",
            "revision",
            "status",
            "timesheetId");
    assertThat(receipt.get("action").asText()).isEqualTo("SUBMITTED");
    assertThat(receipt.get("status").asText()).isEqualTo("SUBMITTED");
    assertThat(receipt.get("cycle").asInt()).isEqualTo(1);
    // 13: the same key and payload replay the receipt; another payload under it is refused.
    MockHttpServletResponse replayed = w.call(submit(t.ana().bearer(), key, id, body));
    assertThat(replayed.getStatus()).isEqualTo(200);
    assertThat(replayed.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replayed.getContentAsString())).isEqualTo(receipt);
    Map<String, Object> other = new LinkedHashMap<>(body);
    other.put("revision", 2);
    w.problem(submit(t.ana().bearer(), key, id, other), 409, "IDEMPOTENCY_KEY_REUSED");
    assertThat(written(w)).containsExactly(1, 1, 1);

    // 16: the employee sees the submission, never who approves it.
    mine = w.expect(mine(t.ana().bearer(), id), 200);
    assertThat(mine.get("status").asText()).isEqualTo("SUBMITTED");
    assertThat(mine.get("submission").get("eligible").asBoolean()).isFalse();
    assertThat(kinds(mine.get("actions"))).containsExactly("SUBMITTED:1");
    assertThat(fields(mine.get("actions").get(0)))
        .containsExactly(
            "action", "cycle", "occurredAt", "returnReason", "revision", "workedMinutes");
    assertThat(mine.toString())
        .doesNotContain(t.mia().employee().toString())
        .doesNotContain(t.mia().number())
        .doesNotContain("Mbuyi")
        .doesNotContain(t.mia().subject());
    w.problem(
        submit(t.ana().bearer(), key(), id, submission(mine)),
        409,
        "TIMESHEET_SUBMISSION_NOT_ALLOWED");

    // 14: administrators read the state and history; they never decide.
    JsonNode detail = w.expect(timesheet(admin, id), 200);
    assertThat(detail.get("status").asText()).isEqualTo("SUBMITTED");
    assertThat(detail.get("submissionCycle").asInt()).isEqualTo(1);
    assertThat(kinds(detail.get("actions"))).containsExactly("SUBMITTED:1");
    assertThat(detail.get("actions").get(0).get("actionId").asText())
        .isEqualTo(receipt.get("actionId").asText());
    assertThat(detail.toString()).doesNotContain(t.mia().employee().toString());
    assertThat(
            w.expect(generation(admin, t.generationId()), 200)
                .get("timesheets")
                .findValuesAsText("status"))
        .contains("SUBMITTED", "DRAFT");
    assertThat(w.call(queue(admin)).getStatus()).isEqualTo(403);
    assertThat(w.call(approve(admin, key(), id, Map.of("confirm", true))).getStatus())
        .isEqualTo(403);

    // 11: submitted time is never refreshed.
    w.problem(
        previewRefresh(admin, t.generationId(), t.period()),
        409,
        "TIMESHEET_GENERATION_NOT_ALLOWED");
    Map<String, Object> forged = new LinkedHashMap<>(t.period());
    forged.put("version", 1);
    forged.put("previewDigest", "a".repeat(64));
    forged.put("confirm", true);
    w.problem(
        refresh(admin, key(), t.generationId(), forged), 409, "TIMESHEET_GENERATION_NOT_ALLOWED");

    // The routed manager's queue and read; nobody else's.
    JsonNode page = w.expect(queue(t.mia().bearer()), 200);
    assertThat(page.get("items")).hasSize(1);
    JsonNode item = page.get("items").get(0);
    assertThat(fields(item))
        .containsExactly(
            "cycle",
            "employee",
            "period",
            "revision",
            "rowCount",
            "submittedAt",
            "timesheetId",
            "workedMinutes");
    assertThat(item.get("timesheetId").asText()).isEqualTo(id);
    assertThat(item.get("employee").get("employeeNumber").asText()).isEqualTo(t.ana().number());
    assertThat(item.get("rowCount").asInt()).isEqualTo(2);
    assertThat(item.get("workedMinutes").asInt()).isEqualTo(948);
    assertThat(w.expect(queue(t.zoe().bearer()), 200).get("items")).isEmpty();
    assertThat(w.expect(queue(t.ana().bearer()), 200).get("items")).isEmpty();
    w.problem(approval(t.zoe().bearer(), id), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(approval(t.ana().bearer(), id), 404, "TIMESHEET_TARGET_NOT_FOUND");
    JsonNode reviewed = w.expect(approval(t.mia().bearer(), id), 200);
    assertThat(reviewed.get("status").asText()).isEqualTo("SUBMITTED");
    assertThat(reviewed.get("submissionActionId").asText())
        .isEqualTo(receipt.get("actionId").asText());
    assertThat(reviewed.get("rows")).hasSize(2);
    assertThat(reviewed.get("rows").get(0).has("employmentId")).isFalse();
    assertThat(reviewed.get("totals").get("workedMinutes").asInt()).isEqualTo(948);
    w.problem(
        approve(t.zoe().bearer(), key(), id, decision(reviewed)),
        404,
        "TIMESHEET_TARGET_NOT_FOUND");

    // 8: the approval appends one action and makes the timesheet APPROVED.
    String approvalKey = key();
    JsonNode approved =
        w.expect(approve(t.mia().bearer(), approvalKey, id, decision(reviewed)), 200);
    assertThat(approved.get("action").asText()).isEqualTo("APPROVED");
    assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
    assertThat(approved.get("cycle").asInt()).isEqualTo(1);
    MockHttpServletResponse again =
        w.call(approve(t.mia().bearer(), approvalKey, id, decision(reviewed)));
    assertThat(again.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(again.getContentAsString())).isEqualTo(approved);
    assertThat(written(w)).containsExactly(2, 2, 2);
    assertThat(w.expect(queue(t.mia().bearer()), 200).get("items")).isEmpty();
    w.problem(approval(t.mia().bearer(), id), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(
        approve(t.mia().bearer(), key(), id, decision(reviewed)),
        409,
        "TIMESHEET_DECISION_NOT_ALLOWED");
    w.problem(
        giveBack(t.mia().bearer(), key(), id, TimesheetApprovalCalls.giveBack(reviewed, REASON)),
        409,
        "TIMESHEET_DECISION_NOT_ALLOWED");
    mine = w.expect(mine(t.ana().bearer(), id), 200);
    assertThat(mine.get("status").asText()).isEqualTo("APPROVED");
    assertThat(kinds(mine.get("actions"))).containsExactly("APPROVED:1", "SUBMITTED:1");
    assertThat(written(w)).containsExactly(2, 2, 2);

    // Bounded audit metadata; events with stable IDs and numbers only.
    for (String action : List.of("timesheet.submit", "timesheet.approve")) {
      JsonNode metadata =
          JSON.readTree(
              jdbc.queryForObject(
                  "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                      + " AND action = ?",
                  String.class,
                  w.tenant(),
                  action));
      assertThat(fields(metadata))
          .containsExactly(
              "action",
              "cycle",
              "generationVersion",
              "revision",
              "rowCount",
              "schemaVersion",
              "workedMinuteBand");
      assertThat(metadata.get("workedMinuteBand").asText()).isEqualTo("481-2400");
      assertThat(metadata.toString()).doesNotContain(id).doesNotContain("948");
    }
    String event =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' = 'people.timesheet.approved.v1'",
            String.class,
            w.tenant().toString());
    JsonNode data = JSON.readTree(event).get("data");
    assertThat(fields(data))
        .containsExactly(
            "actionId",
            "cycle",
            "decision",
            "employeeId",
            "generationId",
            "generationVersion",
            "occurredAt",
            "periodEndAt",
            "periodStartAt",
            "revision",
            "rowCount",
            "siteId",
            "status",
            "submissionActionId",
            "timesheetId",
            "weekStart",
            "workedMinutes");
    assertThat(event)
        .doesNotContain("Kalala")
        .doesNotContain(t.mia().subject())
        .doesNotContain(t.mia().employee().toString());
  }

  // ------------------------------------------------------------------------------------------
  // 2, 3, 4: blockers, stale evidence, ownership and isolation
  // ------------------------------------------------------------------------------------------

  @Test
  void blockersStaleEvidenceAndForeignTargetsAreRefusedBeforeWriting() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();

    JsonNode ben = w.expect(mine(t.ben().bearer(), t.draft(t.ben())), 200);
    assertThat(ben.get("submission").get("eligible").asBoolean()).isFalse();
    assertThat(ben.get("submission").get("blockers").toString())
        .isEqualTo("[\"OPEN_ATTENDANCE\",\"NO_READY_ROWS\"]");
    w.problem(
        submit(t.ben().bearer(), key(), t.draft(t.ben()), submission(ben)),
        409,
        "TIMESHEET_SUBMISSION_NOT_ALLOWED");
    JsonNode dan = w.expect(mine(t.dan().bearer(), t.draft(t.dan())), 200);
    assertThat(dan.get("submission").get("blockers").toString())
        .isEqualTo("[\"MISSING_ATTENDANCE\",\"NO_READY_ROWS\"]");
    w.problem(
        submit(t.dan().bearer(), key(), t.draft(t.dan()), submission(dan)),
        409,
        "TIMESHEET_SUBMISSION_NOT_ALLOWED");

    String id = t.draft(t.ana());
    JsonNode ana = w.expect(mine(t.ana().bearer(), id), 200);
    Map<String, Object> revision = submission(ana);
    revision.put("revision", 2);
    w.problem(submit(t.ana().bearer(), key(), id, revision), 409, "TIMESHEET_DECISION_STALE");
    Map<String, Object> version = submission(ana);
    version.put("generationVersion", 2);
    w.problem(submit(t.ana().bearer(), key(), id, version), 409, "TIMESHEET_DECISION_STALE");

    // Only the linked employee submits their own; another's, a foreign, a malformed or an
    // unknown timesheet is the same 404, and the body never names the employee.
    w.problem(
        submit(t.ben().bearer(), key(), id, submission(ana)), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(
        submit(t.mia().bearer(), key(), id, submission(ana)), 404, "TIMESHEET_TARGET_NOT_FOUND");
    ScheduleWorld other = new ScheduleWorld(mvc, jdbc, lookups);
    Person stranger = other.person("Eli", "Kabila");
    w.problem(
        submit(stranger.bearer(), key(), id, submission(ana)), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(
        submit(t.ana().bearer(), key(), "not-a-uuid", submission(ana)),
        404,
        "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(
        submit(t.ana().bearer(), key(), UUID.randomUUID().toString(), submission(ana)),
        404,
        "TIMESHEET_TARGET_NOT_FOUND");
    Map<String, Object> employee = submission(ana);
    employee.put("employeeId", t.ben().employee().toString());
    JsonNode unknown =
        w.problem(submit(t.ana().bearer(), key(), id, employee), 400, "VALIDATION_FAILED");
    assertThat(unknown.toString()).contains("UNKNOWN_PROPERTY");
    Map<String, Object> unconfirmed = submission(ana);
    unconfirmed.put("confirm", false);
    w.problem(submit(t.ana().bearer(), key(), id, unconfirmed), 400, "VALIDATION_FAILED");
    w.problem(submit(t.ana().bearer(), null, id, submission(ana)), 400, "VALIDATION_FAILED");
    // An unlinked employee has no self-service.
    w.unlink(t.cid().employee());
    w.problem(
        submit(t.cid().bearer(), key(), t.draft(t.cid()), submission(ana)),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    assertThat(written(w)).containsExactly(0, 0, 0);
    assertThat(
            w.count(
                "SELECT count(*) FROM people.timesheet WHERE tenant_id = ? AND status <> 'DRAFT'",
                w.tenant()))
        .isZero();
  }

  // ------------------------------------------------------------------------------------------
  // 5, 6, 7: the route
  // ------------------------------------------------------------------------------------------

  @Test
  void theRouteIsTheManagerEffectiveOnTheSiteLocalSundayResolvedAgainEveryTime() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String id = t.draft(t.ana());

    // 6: no manager, or a manager without an active link, is no route; nobody is named.
    JsonNode cid = w.expect(mine(t.cid().bearer(), t.draft(t.cid())), 200);
    assertThat(cid.get("submission").get("blockers").toString())
        .isEqualTo("[\"APPROVAL_ROUTE_UNAVAILABLE\"]");
    JsonNode refused =
        w.problem(
            submit(t.cid().bearer(), key(), t.draft(t.cid()), submission(cid)),
            409,
            "TIMESHEET_APPROVAL_ROUTE_UNAVAILABLE");
    assertThat(refused.has("params") ? refused.get("params").size() : 0).isZero();

    // 5: Zoé manages Ana from the week's Saturday on: the route is Zoé, the manager on the Sunday,
    // not Mia (Monday to Friday). (Saturday is in the past at the site's Sunday noon.)
    Map<String, Object> late = w.managerCommand(t.zoe().employee(), t.sunday().minusDays(1));
    late.put("reasonCode", "LATE_NOTIFICATION");
    w.expect(w.change(t.ana().employee(), late), 201);
    JsonNode receipt = submitted(t);
    assertThat(
            jdbc.queryForObject(
                "SELECT manager_employee_id FROM people.timesheet_action WHERE id = ?::uuid",
                UUID.class,
                receipt.get("actionId").asText()))
        .isEqualTo(t.zoe().employee());
    assertThat(w.expect(queue(t.mia().bearer()), 200).get("items")).isEmpty();
    assertThat(w.expect(queue(t.zoe().bearer()), 200).get("items")).hasSize(1);
    JsonNode reviewed = w.expect(approval(t.zoe().bearer(), id), 200);

    // 7: Mia becomes the manager from that Sunday on: Zoé loses the authority at once, Mia
    // receives it, for the queue, the read and the decision.
    w.manage(t.ana().employee(), t.mia().employee(), t.sunday());
    assertThat(w.expect(queue(t.zoe().bearer()), 200).get("items")).isEmpty();
    w.problem(approval(t.zoe().bearer(), id), 404, "TIMESHEET_TARGET_NOT_FOUND");
    w.problem(
        approve(t.zoe().bearer(), key(), id, decision(reviewed)),
        404,
        "TIMESHEET_TARGET_NOT_FOUND");
    assertThat(w.expect(queue(t.mia().bearer()), 200).get("items")).hasSize(1);
    // Filters: the site and week, or another week (nothing).
    assertThat(
            w.expect(
                    queue(t.mia().bearer(), "siteId=" + w.site() + "&weekStart=" + t.monday()), 200)
                .get("items"))
        .hasSize(1);
    assertThat(
            w.expect(queue(t.mia().bearer(), "weekStart=" + t.monday().minusDays(7)), 200)
                .get("items"))
        .isEmpty();
    w.problem(
        queue(t.mia().bearer(), "weekStart=" + t.monday().plusDays(1)), 400, "VALIDATION_FAILED");
    JsonNode mias = w.expect(approval(t.mia().bearer(), id), 200);
    // The decision records the deciding manager; the submission keeps the routed one.
    JsonNode approved = w.expect(approve(t.mia().bearer(), key(), id, decision(mias)), 200);
    assertThat(
            jdbc.queryForObject(
                "SELECT manager_employee_id FROM people.timesheet_action WHERE id = ?::uuid",
                UUID.class,
                approved.get("actionId").asText()))
        .isEqualTo(t.mia().employee());

    // 6: a manager without an active link is no route: Zoé manages Cid's clean week, then loses
    // her link.
    Map<String, Object> zoe = w.managerCommand(t.zoe().employee(), t.monday());
    zoe.put("reasonCode", "LATE_NOTIFICATION");
    w.expect(w.change(t.cid().employee(), zoe), 201);
    assertThat(
            w.expect(mine(t.cid().bearer(), t.draft(t.cid())), 200)
                .get("submission")
                .get("eligible")
                .asBoolean())
        .isTrue();
    w.unlink(t.zoe().employee());
    JsonNode unlinked = w.expect(mine(t.cid().bearer(), t.draft(t.cid())), 200);
    assertThat(unlinked.get("submission").get("blockers").toString())
        .isEqualTo("[\"APPROVAL_ROUTE_UNAVAILABLE\"]");
    w.problem(
        submit(t.cid().bearer(), key(), t.draft(t.cid()), submission(unlinked)),
        409,
        "TIMESHEET_APPROVAL_ROUTE_UNAVAILABLE");
  }

  // ------------------------------------------------------------------------------------------
  // 9, 10, 3: return, refresh and resubmission; stale decisions
  // ------------------------------------------------------------------------------------------

  @Test
  void aReturnRestoresTheDraftWhichRefreshesAndResubmitsAsTheNextCycle() throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String id = t.draft(t.ana());
    String admin = hr(w);
    submitted(t);
    JsonNode reviewed = w.expect(approval(t.mia().bearer(), id), 200);

    // The reason is required and in its grammar.
    for (Object bad :
        List.of(
            "Trop bref",
            "Voir https://exemple.test pour le détail",
            "Balise <b>interdite</b> ici.",
            "Retour​ avec un caractère invisible.",
            "x".repeat(501),
            42)) {
      w.problem(
          giveBack(t.mia().bearer(), key(), id, TimesheetApprovalCalls.giveBack(reviewed, bad)),
          400,
          "VALIDATION_FAILED");
    }
    Map<String, Object> missing = decision(reviewed);
    w.problem(giveBack(t.mia().bearer(), key(), id, missing), 400, "VALIDATION_FAILED");
    // An approval takes no note.
    Map<String, Object> noted = TimesheetApprovalCalls.giveBack(reviewed, REASON);
    w.problem(approve(t.mia().bearer(), key(), id, noted), 400, "VALIDATION_FAILED");
    // 3: stale evidence.
    Map<String, Object> cycle = decision(reviewed);
    cycle.put("cycle", 2);
    w.problem(approve(t.mia().bearer(), key(), id, cycle), 409, "TIMESHEET_DECISION_STALE");
    Map<String, Object> action = decision(reviewed);
    action.put("submissionActionId", UUID.randomUUID().toString());
    w.problem(approve(t.mia().bearer(), key(), id, action), 409, "TIMESHEET_DECISION_STALE");
    assertThat(written(w)).containsExactly(1, 1, 1);

    // The source changes after the submission (Ana's Monday is corrected): it is never approved,
    // but it can be returned so the administrators refresh it.
    Instant[] monday = instants(jdbc, slot(t));
    Map<String, Object> corrected = values(monday[0], monday[1].minus(Duration.ofMinutes(30)));
    JsonNode preview = w.expect(previewRecord(admin, t.anaMonday(), corrected), 200);
    w.expect(commitRecord(admin, key(), t.anaMonday(), commit(corrected, preview)), 201);
    w.problem(
        approve(t.mia().bearer(), key(), id, decision(reviewed)), 409, "TIMESHEET_DECISION_STALE");

    // 9: the return appends its evidence and restores DRAFT.
    JsonNode returned =
        w.expect(
            giveBack(
                t.mia().bearer(),
                key(),
                id,
                TimesheetApprovalCalls.giveBack(reviewed, "  " + REASON + "  ")),
            200);
    assertThat(returned.get("action").asText()).isEqualTo("RETURNED");
    assertThat(returned.get("status").asText()).isEqualTo("DRAFT");
    assertThat(returned.get("cycle").asInt()).isEqualTo(1);
    assertThat(w.expect(queue(t.mia().bearer()), 200).get("items")).isEmpty();
    JsonNode mine = w.expect(mine(t.ana().bearer(), id), 200);
    assertThat(mine.get("status").asText()).isEqualTo("DRAFT");
    assertThat(mine.get("submissionCycle").asInt()).isEqualTo(1);
    assertThat(kinds(mine.get("actions"))).containsExactly("RETURNED:1", "SUBMITTED:1");
    assertThat(mine.get("actions").get(0).get("returnReason").asText()).isEqualTo(REASON);
    JsonNode detail = w.expect(timesheet(admin, id), 200);
    assertThat(detail.get("actions").get(0).get("returnReason").asText()).isEqualTo(REASON);

    // 10: the returned draft refreshes under 070A and resubmits as cycle 2; cycle 1 stays.
    JsonNode refreshPreview = w.expect(previewRefresh(admin, t.generationId(), t.period()), 200);
    assertThat(refreshPreview.get("changedTimesheets").asInt()).isEqualTo(1);
    w.expect(refresh(admin, key(), t.generationId(), refreshBody(t.period(), refreshPreview)), 200);
    mine = w.expect(mine(t.ana().bearer(), id), 200);
    assertThat(mine.get("revision").asInt()).isEqualTo(2);
    assertThat(mine.get("generationVersion").asInt()).isEqualTo(2);
    assertThat(mine.get("totals").get("workedMinutes").asInt()).isEqualTo(474 + 450);
    // The old evidence is stale for a new submission.
    w.problem(
        submit(t.ana().bearer(), key(), id, submission(reviewed)), 409, "TIMESHEET_DECISION_STALE");
    JsonNode second = w.expect(submit(t.ana().bearer(), key(), id, submission(mine)), 200);
    assertThat(second.get("cycle").asInt()).isEqualTo(2);
    assertThat(second.get("revision").asInt()).isEqualTo(2);
    // A decision of cycle 1 is stale now.
    w.problem(
        giveBack(t.mia().bearer(), key(), id, TimesheetApprovalCalls.giveBack(reviewed, REASON)),
        409,
        "TIMESHEET_DECISION_STALE");
    JsonNode current = w.expect(approval(t.mia().bearer(), id), 200);
    assertThat(kinds(current.get("actions")))
        .containsExactly("SUBMITTED:2", "RETURNED:1", "SUBMITTED:1");
    assertThat(current.get("actions").get(1).get("returnReason").asText()).isEqualTo(REASON);
    w.expect(approve(t.mia().bearer(), key(), id, decision(current)), 200);
    assertThat(
            jdbc.queryForList(
                "SELECT action || ':' || cycle || ':' || revision FROM people.timesheet_action"
                    + " WHERE timesheet_id = ?::uuid ORDER BY sequence",
                String.class,
                id))
        .containsExactly("SUBMITTED:1:1", "RETURNED:1:1", "SUBMITTED:2:2", "APPROVED:2:2");
    assertThat(
            jdbc.queryForObject(
                "SELECT return_reason FROM people.timesheet_action WHERE timesheet_id = ?::uuid"
                    + " AND action = 'RETURNED'",
                String.class,
                id))
        .isEqualTo(REASON);
  }

  private String slot(Week t) {
    return jdbc.queryForObject(
        "SELECT slot_id::text FROM people.attendance_record WHERE id = ?::uuid",
        String.class,
        t.anaMonday());
  }

  // ------------------------------------------------------------------------------------------
  // 16, 17: privacy of errors, audit and logs
  // ------------------------------------------------------------------------------------------

  @Test
  void errorsAuditAndLogsNeverCarryIdentifiersNamesReasonsOrExactTime(CapturedOutput output)
      throws Exception {
    Week t = week();
    ScheduleWorld w = t.w();
    String id = t.draft(t.ana());
    JsonNode receipt = submitted(t);
    JsonNode reviewed = w.expect(approval(t.mia().bearer(), id), 200);
    JsonNode stale = decisionProblem(t, reviewed);
    JsonNode returned =
        w.expect(
            giveBack(
                t.mia().bearer(), key(), id, TimesheetApprovalCalls.giveBack(reviewed, REASON)),
            200);
    String audit =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action LIKE 'timesheet.%'",
                String.class, w.tenant()));
    String events =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                    + " AND envelope ->> 'eventType' LIKE 'people.timesheet.%'",
                String.class, w.tenant().toString()));
    for (String text : List.of(audit, stale.toString())) {
      assertThat(text)
          .doesNotContain("948")
          .doesNotContain(id)
          .doesNotContain(receipt.get("actionId").asText())
          .doesNotContain(t.ana().employee().toString())
          .doesNotContain(t.mia().employee().toString());
    }
    for (String text : List.of(audit, events, stale.toString())) {
      assertThat(text)
          .doesNotContain("Kalala")
          .doesNotContain("Mbuyi")
          .doesNotContain(t.ana().number())
          .doesNotContain(t.ana().subject())
          .doesNotContain(t.mia().subject())
          .doesNotContain("pause déclarée");
    }
    String logs =
        String.join(
            "\n",
            output.getAll().lines().filter(line -> line.contains("divalhr.timesheet")).toList());
    assertThat(logs).contains("timesheet_submitted").contains("timesheet_returned");
    // Every application log line (test-side request dumps are not application logs).
    String application =
        String.join(
            "\n",
            output
                .getAll()
                .lines()
                .filter(line -> line.contains("\"logger_name\":\"divalhr."))
                .toList());
    assertThat(application)
        .doesNotContain(id)
        .doesNotContain(receipt.get("actionId").asText())
        .doesNotContain(returned.get("actionId").asText())
        .doesNotContain(t.ana().employee().toString())
        .doesNotContain(t.mia().employee().toString())
        .doesNotContain("pause déclarée")
        .doesNotContain("Kalala");
  }

  private JsonNode decisionProblem(Week t, JsonNode reviewed) throws Exception {
    Map<String, Object> stale = decision(reviewed);
    stale.put("revision", 9);
    return t.w()
        .problem(
            approve(t.mia().bearer(), key(), t.draft(t.ana()), stale),
            409,
            "TIMESHEET_DECISION_STALE");
  }
}
