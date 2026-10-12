package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.history;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.fieldNames;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TeamAttendanceCalls.REASON_EN;
import static com.divalhr.core.people.schedule.TeamAttendanceCalls.REASON_FR;
import static com.divalhr.core.people.schedule.TeamAttendanceCalls.TEAM;
import static com.divalhr.core.people.schedule.TeamAttendanceCalls.actions;
import static com.divalhr.core.people.schedule.TeamAttendanceCalls.checkInBody;
import static com.divalhr.core.people.schedule.TeamAttendanceCalls.teamCheckIn;
import static com.divalhr.core.people.schedule.TeamAttendanceCalls.teamCheckOut;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-060B end to end over the public API against real PostgreSQL (B60B-9 items 1 to 10, 13, 16 and
 * 17): the effective manager's queue of check-ins and check-outs; a supervisor check-in and
 * check-out of a direct report with matching SUPERVISOR evidence and the target's normal record;
 * the employee's view of the method and reason without the supervisor; one indistinguishable 404
 * for non-managers, former managers, foreign, unknown, malformed and own resources; a manager
 * change rerouting at once; leave substitutes and substitution approvals granting nothing; the
 * unchanged windows at their exact boundaries; replays re-authorized; the queue's cursor binding
 * and tenant isolation; and no reason, subject, name or number in logs, audit metadata or events.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class TeamAttendanceIntegrationTest {

  private static final Duration TWO_HOURS = Duration.ofHours(2);
  private static final Duration DAY = Duration.ofHours(24);
  private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  /**
   * Mia manages Ana and Ben from today; Zoé has no reports. Ana works 08-16 (slot 0) and Ben 09-13
   * (slot 1) on {@code day}.
   */
  private record Team(
      ScheduleWorld w,
      Person mia,
      Person ana,
      Person ben,
      Person zoe,
      LocalDate day,
      String schedule,
      String anaSlot,
      String benSlot) {}

  private Team team(int inDays) throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    Person zoe = w.person("Zoé", "Tshala");
    w.manageAll(mia, ana, ben);
    LocalDate day = w.today.plusDays(inDays);
    String schedule =
        w.published(
            proposal(
                w.site(),
                day,
                day,
                List.of(
                    shift("a", day, 8, 16, ana.employee()),
                    shift("b", day, 9, 13, ben.employee()))));
    List<String> slots = slotIds(w, schedule);
    return new Team(w, mia, ana, ben, zoe, day, schedule, slots.get(0), slots.get(1));
  }

  private JsonNode queue(ScheduleWorld w, Person manager) throws Exception {
    return w.expect(actions(manager.bearer()), 200);
  }

  private static List<String> rows(JsonNode page) {
    List<String> rows = new ArrayList<>();
    page.get("items")
        .forEach(
            item ->
                rows.add(item.get("requiredAction").asText() + " " + item.get("slotId").asText()));
    return rows;
  }

  private static String logs(CapturedOutput output) {
    return String.join(
        "\n", output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp")).toList());
  }

  private static JsonNode stable(JsonNode problem) {
    ObjectNode copy = (ObjectNode) problem.deepCopy();
    copy.remove("correlationId");
    copy.remove("instance");
    return copy;
  }

  private JsonNode page(String bearer, String path) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get(path).header("Authorization", bearer)).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  // ------------------------------------------------------------------------------------------
  // 1, 6, 7, 8, 9, 17: the queue, both actions, the employee's view and privacy
  // ------------------------------------------------------------------------------------------

  @Test
  void aManagerRecordsADirectReportsCheckInAndCheckOutAndTheEmployeeSeesWhyNotWho(
      CapturedOutput output) throws Exception {
    Team t = team(3);
    ScheduleWorld w = t.w();
    Instant[] ana = instants(jdbc, t.anaSlot());
    Instant[] ben = instants(jdbc, t.benSlot());
    Instant in = ana[0].minus(Duration.ofHours(1));
    clock.set(in);

    // The queue: both reports' check-ins, Ben's first (earlier deadline), private and no-store.
    MockHttpServletResponse listed = w.call(actions(t.mia().bearer()));
    assertThat(listed.getStatus()).isEqualTo(200);
    assertThat(listed.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode page = JSON.readTree(listed.getContentAsString(StandardCharsets.UTF_8));
    assertThat(fieldNames(page)).containsExactly("items", "nextCursor", "asOfInstant");
    assertThat(Instant.parse(page.get("asOfInstant").asText())).isEqualTo(in);
    assertThat(rows(page)).containsExactly("CHECK_IN " + t.benSlot(), "CHECK_IN " + t.anaSlot());
    JsonNode item = page.get("items").get(1);
    assertThat(fieldNames(item))
        .containsExactly(
            "requiredAction",
            "actionDeadline",
            "slotId",
            "scheduleId",
            "attendanceRecordId",
            "employee",
            "attendanceStatus",
            "checkedInAt",
            "site",
            "siteTimezone",
            "start",
            "end",
            "startAt",
            "endAt",
            "overnight",
            "labels");
    assertThat(Instant.parse(item.get("actionDeadline").asText())).isEqualTo(ana[1]);
    assertThat(item.get("attendanceStatus").asText()).isEqualTo("NOT_RECORDED");
    assertThat(item.get("attendanceRecordId").isNull()).isTrue();
    assertThat(item.get("employee").get("id").asText()).isEqualTo(t.ana().employee().toString());
    assertThat(item.get("employee").get("employeeNumber").asText()).isEqualTo(t.ana().number());
    assertThat(item.get("employee").get("familyName").asText()).isEqualTo("Kalala");
    assertThat(item.get("labels").get("fr").asText()).isEqualTo("Caissière");
    // A colleague who manages nobody is told so; the queue never shows the manager herself.
    w.problem(actions(t.zoe().bearer()), 403, "TEAM_ATTENDANCE_MANAGER_REQUIRED");
    w.problem(actions(t.ana().bearer()), 403, "TEAM_ATTENDANCE_MANAGER_REQUIRED");

    // The check-in, in French: a minimal receipt for the target, no reason, no manager.
    String key = Organizations.newKey();
    JsonNode receipt = w.expect(teamCheckIn(t.mia(), key, t.anaSlot()), 201);
    assertThat(fieldNames(receipt))
        .containsExactly(
            "attendanceRecordId",
            "slotId",
            "employeeId",
            "status",
            "recordingMethod",
            "checkedInAt",
            "checkedOutAt");
    assertThat(receipt.get("employeeId").asText()).isEqualTo(t.ana().employee().toString());
    assertThat(receipt.get("status").asText()).isEqualTo("OPEN");
    assertThat(receipt.get("recordingMethod").asText()).isEqualTo("SUPERVISOR");
    assertThat(Instant.parse(receipt.get("checkedInAt").asText())).isEqualTo(in);
    String id = receipt.get("attendanceRecordId").asText();
    assertThat(receipt.toString()).doesNotContain(REASON_FR).doesNotContain(t.mia().number());
    // The exact retry replays; the record is the target's own; one SUPERVISOR event and action.
    MockHttpServletResponse replayed = w.call(teamCheckIn(t.mia(), key, t.anaSlot()));
    assertThat(replayed.getStatus()).isEqualTo(201);
    assertThat(replayed.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replayed.getContentAsString())).isEqualTo(receipt);
    assertThat(
            jdbc.queryForObject(
                "SELECT employee_id FROM people.attendance_record WHERE id = ?::uuid",
                UUID.class,
                id))
        .isEqualTo(t.ana().employee());
    UUID miaEmployment =
        jdbc.queryForObject(
            "SELECT id FROM people.employment WHERE employee_id = ?",
            UUID.class,
            t.mia().employee());
    assertThat(
            jdbc.queryForList(
                "SELECT e.recording_method || ' ' || s.manager_employee_id || ' '"
                    + " || s.manager_employment_id || ' ' || s.reason_locale || ' ' || s.reason"
                    + " FROM people.attendance_event e JOIN people.attendance_supervisor_action s"
                    + " ON s.tenant_id = e.tenant_id AND s.event_id = e.id"
                    + " WHERE e.record_id = ?::uuid",
                String.class,
                id))
        .containsExactly(
            "SUPERVISOR " + t.mia().employee() + " " + miaEmployment + " fr " + REASON_FR);
    // The employee's own check-in is refused: the slot is recorded.
    w.problem(
        checkIn(t.ana(), Organizations.newKey(), t.anaSlot()),
        409,
        "ATTENDANCE_ALREADY_CHECKED_IN");

    // A microsecond later (check-out needs a later instant), the queue asks for Ana's check-out
    // (with her record and check-in) and Ben's check-in.
    clock.set(in.plus(MICRO));
    JsonNode after = queue(w, t.mia());
    assertThat(rows(after)).containsExactly("CHECK_IN " + t.benSlot(), "CHECK_OUT " + t.anaSlot());
    JsonNode open = after.get("items").get(1);
    assertThat(open.get("attendanceRecordId").asText()).isEqualTo(id);
    assertThat(open.get("attendanceStatus").asText()).isEqualTo("OPEN");
    assertThat(Instant.parse(open.get("checkedInAt").asText())).isEqualTo(in);
    assertThat(Instant.parse(open.get("actionDeadline").asText())).isEqualTo(ana[1].plus(DAY));
    assertThat(Instant.parse(after.get("items").get(0).get("actionDeadline").asText()))
        .isEqualTo(ben[1]);

    // The check-out, in English.
    Instant out = ana[1].plus(Duration.ofHours(1));
    clock.set(out);
    JsonNode completed = w.expect(teamCheckOut(t.mia(), Organizations.newKey(), id), 200);
    assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(Instant.parse(completed.get("checkedOutAt").asText())).isEqualTo(out);
    w.problem(
        teamCheckOut(t.mia(), Organizations.newKey(), id), 409, "ATTENDANCE_ALREADY_CHECKED_OUT");
    assertThat(
            w.count(
                "SELECT count(*) FROM people.attendance_supervisor_action WHERE record_id ="
                    + " ?::uuid",
                UUID.fromString(id)))
        .isEqualTo(2);

    // The employee sees the method and the reasons exactly, never who recorded them.
    JsonNode mine = page(t.ana().bearer(), AttendanceCalls.ATTENDANCE).get("items").get(0);
    assertThat(mine.get("checkInRecordingMethod").asText()).isEqualTo("SUPERVISOR");
    assertThat(mine.get("checkInSupervisorReasonLocale").asText()).isEqualTo("fr");
    assertThat(mine.get("checkInSupervisorReason").asText()).isEqualTo(REASON_FR);
    assertThat(mine.get("checkOutRecordingMethod").asText()).isEqualTo("SUPERVISOR");
    assertThat(mine.get("checkOutSupervisorReasonLocale").asText()).isEqualTo("en");
    assertThat(mine.get("checkOutSupervisorReason").asText()).isEqualTo(REASON_EN);
    JsonNode schedule =
        page(t.ana().bearer(), ScheduleWorld.MINE + "?from=" + t.day() + "&to=" + t.day());
    JsonNode attendance = schedule.get("items").get(0).get("attendance");
    assertThat(attendance.get("checkInRecordingMethod").asText()).isEqualTo("SUPERVISOR");
    assertThat(attendance.get("checkInSupervisorReason").asText()).isEqualTo(REASON_FR);
    assertThat(attendance.get("checkOutSupervisorReason").asText()).isEqualTo(REASON_EN);
    for (JsonNode view : List.of(mine, schedule)) {
      assertThat(view.toString())
          .doesNotContain(t.mia().employee().toString())
          .doesNotContain(t.mia().number())
          .doesNotContain("Mbuyi")
          .doesNotContain(t.mia().subject())
          .doesNotContain(miaEmployment.toString());
    }

    // Audit metadata, events and logs carry identifiers, methods and states only.
    String tenant = w.tenant().toString();
    List<String> audit =
        jdbc.queryForList(
            "SELECT action || ' ' || metadata::text FROM platform.audit_event"
                + " WHERE tenant_id = ?::uuid AND action LIKE 'attendance.%' ORDER BY occurred_at",
            String.class, tenant);
    assertThat(audit).anyMatch(a -> a.startsWith("attendance.team-actions-list "));
    assertThat(audit)
        .anyMatch(
            a ->
                a.startsWith("attendance.supervisor-check-in ")
                    && a.contains("\"recordingMethod\": \"SUPERVISOR\"")
                    && a.contains("\"managerEmployeeId\": \"" + t.mia().employee() + "\""));
    assertThat(audit).anyMatch(a -> a.startsWith("attendance.supervisor-check-out "));
    List<String> events =
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' LIKE 'people.attendance.supervisor-%'",
            String.class, tenant);
    assertThat(events).hasSize(2);
    String published = String.join("\n", audit) + "\n" + String.join("\n", events) + logs(output);
    for (String secret :
        List.of(
            REASON_FR,
            REASON_EN,
            "Téléphone",
            "badge reader",
            t.mia().subject(),
            t.ana().subject(),
            t.ana().number(),
            t.mia().number(),
            "Kalala",
            "Mbuyi",
            "Caissière",
            "Bearer ")) {
      assertThat(published).as("no %s", secret).doesNotContain(secret);
    }
  }

  // ------------------------------------------------------------------------------------------
  // 2, 4, 16: undisclosable resources, the manager herself, cursor binding, tenant isolation
  // ------------------------------------------------------------------------------------------

  @Test
  void undisclosableActionsAreOneIndistinguishable404AndTheQueueIsBoundToItsCaller()
      throws Exception {
    Team t = team(3);
    ScheduleWorld w = t.w();
    Team foreign = team(3);
    // Mia also works a shift herself, covered by her.
    String own =
        slotIds(
                w,
                w.published(
                    proposal(
                        w.site(),
                        t.day().plusDays(1),
                        t.day().plusDays(1),
                        List.of(shift("m", t.day().plusDays(1), 8, 16, t.mia().employee())))))
            .get(0);
    Instant[] ana = instants(jdbc, t.anaSlot());
    clock.set(ana[0].minus(Duration.ofHours(1)));
    String record =
        w.expect(teamCheckIn(t.mia(), Organizations.newKey(), t.benSlot()), 201)
            .get("attendanceRecordId")
            .asText();

    // A non-manager, the other tenant's manager, the employee herself and the manager on her own
    // shift: the same problem as an unknown or malformed slot, with no detail.
    List<JsonNode> answers = new ArrayList<>();
    answers.add(w.expect(teamCheckIn(t.zoe(), Organizations.newKey(), t.anaSlot()), 404));
    answers.add(w.expect(teamCheckIn(foreign.mia(), Organizations.newKey(), t.anaSlot()), 404));
    answers.add(w.expect(teamCheckIn(t.ana(), Organizations.newKey(), t.anaSlot()), 404));
    clock.set(instants(jdbc, own)[0].minus(Duration.ofHours(1)));
    answers.add(w.expect(teamCheckIn(t.mia(), Organizations.newKey(), own), 404));
    clock.set(ana[0].minus(Duration.ofHours(1)));
    answers.add(
        w.expect(teamCheckIn(t.mia(), Organizations.newKey(), UUID.randomUUID().toString()), 404));
    answers.add(
        w.expect(
            teamCheckIn(
                t.mia().bearer(), Organizations.newKey(), checkInBody("nope", "fr", REASON_FR)),
            404));
    for (JsonNode answer : answers) {
      assertThat(answer.get("code").asText()).isEqualTo("TEAM_ATTENDANCE_ACTION_NOT_FOUND");
      assertThat(stable(answer)).isEqualTo(stable(answers.get(0)));
    }
    assertThat(answers.toString())
        .doesNotContain(t.anaSlot())
        .doesNotContain(own)
        .doesNotContain(t.ana().number());
    // Records likewise: Ben's record for Zoé, Ben himself and the foreign manager; unknown and
    // malformed IDs.
    List<JsonNode> records = new ArrayList<>();
    records.add(w.expect(teamCheckOut(t.zoe(), Organizations.newKey(), record), 404));
    records.add(w.expect(teamCheckOut(t.ben(), Organizations.newKey(), record), 404));
    records.add(w.expect(teamCheckOut(foreign.mia(), Organizations.newKey(), record), 404));
    records.add(
        w.expect(teamCheckOut(t.mia(), Organizations.newKey(), UUID.randomUUID().toString()), 404));
    records.add(w.expect(teamCheckOut(t.mia(), Organizations.newKey(), "nope"), 404));
    for (JsonNode answer : records) {
      assertThat(stable(answer)).isEqualTo(stable(answers.get(0)));
    }
    // A tenant administrator has no role here; validation names fields, never values.
    assertThat(w.call(actions(w.admin)).getStatus()).isEqualTo(403);
    assertThat(
            w.call(
                    teamCheckIn(
                        w.admin, Organizations.newKey(), checkInBody(t.anaSlot(), "fr", REASON_FR)))
                .getStatus())
        .isEqualTo(403);
    JsonNode invalid =
        w.problem(
            teamCheckIn(
                t.mia().bearer(), Organizations.newKey(), checkInBody(t.anaSlot(), "de", "x")),
            400,
            "VALIDATION_FAILED");
    assertThat(invalid.toString()).doesNotContain(t.anaSlot()).contains("reasonLocale");
    w.problem(
        teamCheckIn(t.mia().bearer(), null, checkInBody(t.anaSlot(), "fr", REASON_FR)),
        400,
        "VALIDATION_FAILED");
    assertThat(
            w.count(
                "SELECT count(*) FROM people.attendance_record WHERE slot_id = ?::uuid",
                UUID.fromString(t.anaSlot())))
        .isZero();

    // The queue pages with a cursor bound to its caller and size; other tenants see nothing.
    clock.set(ana[0].minus(Duration.ofHours(1)).plus(MICRO));
    JsonNode first =
        w.expect(get(TEAM + "/actions?limit=1").header("Authorization", t.mia().bearer()), 200);
    assertThat(rows(first)).containsExactly("CHECK_IN " + t.anaSlot());
    String cursor = first.get("nextCursor").asText();
    JsonNode second =
        w.expect(
            get(TEAM + "/actions?limit=1&cursor=" + cursor)
                .header("Authorization", t.mia().bearer()),
            200);
    assertThat(rows(second)).containsExactly("CHECK_OUT " + t.benSlot());
    assertThat(second.get("nextCursor").isNull()).isTrue();
    assertThat(second.get("asOfInstant")).isEqualTo(first.get("asOfInstant"));
    w.problem(
        get(TEAM + "/actions?limit=2&cursor=" + cursor).header("Authorization", t.mia().bearer()),
        400,
        "CURSOR_INVALID");
    clock.reset();
    w.manageAll(t.zoe(), w.person("Léa", "Banza"));
    w.problem(
        get(TEAM + "/actions?limit=1&cursor=" + cursor).header("Authorization", t.zoe().bearer()),
        400,
        "CURSOR_INVALID");
    assertThat(rows(queue(foreign.w(), foreign.mia())))
        .doesNotContain("CHECK_IN " + t.anaSlot())
        .doesNotContain("CHECK_OUT " + t.benSlot());
    w.problem(
        get(TEAM + "/actions?limit=101").header("Authorization", t.mia().bearer()),
        400,
        "VALIDATION_FAILED");
  }

  // ------------------------------------------------------------------------------------------
  // 3, 5, 13: the dynamic manager, other authorities, replays
  // ------------------------------------------------------------------------------------------

  @Test
  void aManagerChangeReroutesAtOnceAndNoOtherAuthorityCounts() throws Exception {
    Team t = team(3);
    ScheduleWorld w = t.w();
    Person noa = w.person("Noa", "Banza");
    Instant[] ana = instants(jdbc, t.anaSlot());
    clock.set(ana[0].minus(Duration.ofHours(1)));
    String key = Organizations.newKey();
    String id =
        w.expect(teamCheckIn(t.mia(), key, t.anaSlot()), 201).get("attendanceRecordId").asText();

    // A leave-approval substitute of Mia has no attendance authority.
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
    w.problem(actions(noa.bearer()), 403, "TEAM_ATTENDANCE_MANAGER_REQUIRED");
    w.problem(
        teamCheckIn(noa, Organizations.newKey(), t.benSlot()),
        404,
        "TEAM_ATTENDANCE_ACTION_NOT_FOUND");
    w.problem(
        teamCheckOut(noa, Organizations.newKey(), id), 404, "TEAM_ATTENDANCE_ACTION_NOT_FOUND");

    // Ana reports to Noa from before the shift: Mia loses the record at once, her replay too.
    // (The change is made today, so it needs no late-notification reason.)
    clock.reset();
    w.manage(t.ana().employee(), noa.employee(), t.day().minusDays(1));
    clock.set(ana[0].minus(Duration.ofHours(1)).plus(MICRO));
    assertThat(rows(queue(w, t.mia()))).containsExactly("CHECK_IN " + t.benSlot());
    w.problem(
        teamCheckOut(t.mia(), Organizations.newKey(), id), 404, "TEAM_ATTENDANCE_ACTION_NOT_FOUND");
    JsonNode denied = w.expect(teamCheckIn(t.mia(), key, t.anaSlot()), 404);
    assertThat(denied.get("code").asText()).isEqualTo("TEAM_ATTENDANCE_ACTION_NOT_FOUND");
    assertThat(denied.toString()).doesNotContain(id);
    assertThat(rows(queue(w, noa))).containsExactly("CHECK_OUT " + t.anaSlot());
    clock.set(ana[0]);
    w.expect(teamCheckOut(noa, Organizations.newKey(), id), 200);

    // An approved shift substitution moves the slot to Ben; approving it gave Mia nothing beyond
    // her own reporting line: once Ben reports to Noa, only Noa acts on his shifts.
    clock.reset();
    LocalDate later = t.day().plusDays(2);
    String slot =
        slotIds(
                w,
                w.published(
                    proposal(
                        w.site(),
                        later,
                        later,
                        List.of(shift("z", later, 8, 16, t.zoe().employee())))))
            .get(0);
    w.manage(t.zoe().employee(), t.mia().employee(), w.today);
    String requestId =
        w.expect(request(t.zoe(), Organizations.newKey(), slot), 201).get("id").asText();
    w.expect(approve(t.mia(), requestId, Organizations.newKey(), t.ben().employee()), 200);
    w.manage(t.ben().employee(), noa.employee(), later.minusDays(1));
    clock.set(instants(jdbc, slot)[0].minus(Duration.ofHours(1)));
    w.problem(
        teamCheckIn(t.mia(), Organizations.newKey(), slot),
        404,
        "TEAM_ATTENDANCE_ACTION_NOT_FOUND");
    JsonNode bens = w.expect(teamCheckIn(noa, Organizations.newKey(), slot), 201);
    assertThat(bens.get("employeeId").asText()).isEqualTo(t.ben().employee().toString());
  }

  // ------------------------------------------------------------------------------------------
  // 10: the unchanged windows at their exact boundaries
  // ------------------------------------------------------------------------------------------

  @Test
  void theMvp060aWindowsApplyAtTheirExactBoundaries() throws Exception {
    Team t = team(3);
    ScheduleWorld w = t.w();
    Instant[] ana = instants(jdbc, t.anaSlot());
    Instant[] ben = instants(jdbc, t.benSlot());

    // Check-in: from two hours before the start, inclusive.
    clock.set(ana[0].minus(TWO_HOURS).minus(MICRO));
    assertThat(rows(queue(w, t.mia()))).isEmpty();
    w.problem(
        teamCheckIn(t.mia(), Organizations.newKey(), t.anaSlot()),
        409,
        "ATTENDANCE_CHECK_IN_TOO_EARLY");
    clock.set(ana[0].minus(TWO_HOURS));
    assertThat(rows(queue(w, t.mia()))).containsExactly("CHECK_IN " + t.anaSlot());
    String id =
        w.expect(teamCheckIn(t.mia(), Organizations.newKey(), t.anaSlot()), 201)
            .get("attendanceRecordId")
            .asText();

    // Until the end, exclusive.
    clock.set(ben[1].minus(MICRO));
    assertThat(rows(queue(w, t.mia()))).contains("CHECK_IN " + t.benSlot());
    clock.set(ben[1]);
    assertThat(rows(queue(w, t.mia()))).doesNotContain("CHECK_IN " + t.benSlot());
    w.problem(
        teamCheckIn(t.mia(), Organizations.newKey(), t.benSlot()),
        409,
        "ATTENDANCE_CHECK_IN_WINDOW_CLOSED");

    // An employee with another actionable open record is not offered a check-in, and is refused.
    LocalDate day = t.day();
    String evening =
        slotIds(
                w,
                w.published(
                    proposal(
                        w.site(),
                        day.plusDays(1),
                        day.plusDays(1),
                        List.of(shift("e", day.plusDays(1), 8, 12, t.ana().employee())))))
            .get(0);
    clock.set(instants(jdbc, evening)[0].minus(Duration.ofHours(1)));
    assertThat(rows(queue(w, t.mia()))).doesNotContain("CHECK_IN " + evening);
    w.problem(
        teamCheckIn(t.mia(), Organizations.newKey(), evening),
        409,
        "ATTENDANCE_OPEN_RECORD_EXISTS");

    // Check-out: until 24 hours after the end, inclusive.
    clock.set(ana[1].plus(DAY).plus(MICRO));
    assertThat(rows(queue(w, t.mia()))).doesNotContain("CHECK_OUT " + t.anaSlot());
    w.problem(
        teamCheckOut(t.mia(), Organizations.newKey(), id),
        409,
        "ATTENDANCE_CHECK_OUT_WINDOW_CLOSED");
    clock.set(ana[1].plus(DAY));
    assertThat(rows(queue(w, t.mia()))).contains("CHECK_OUT " + t.anaSlot());
    w.expect(teamCheckOut(t.mia(), Organizations.newKey(), id), 200);
    w.expect(history(t.ana().bearer()), 200);
  }
}
