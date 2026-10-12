package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.history;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.fieldNames;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
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
 * MVP-060A end to end over the public API against real PostgreSQL (A60A-9 items 1 to 8, 11 and 13):
 * check-in and check-out at the server's instant and the history; both windows exactly at their
 * boundaries; the same 404 for every undisclosable slot and record; cancelled, replaced and
 * substituted shifts refused while the approved substitute checks in; evidence kept across a later
 * cancellation; one actionable open record per employee, a missed check-out no longer blocking;
 * duplicates and replays creating no evidence and re-authorized; the history's cursor binding and
 * tenant isolation; and no subject, name, number, label or token in logs, audit metadata or events.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class AttendanceIntegrationTest {

  private static final Duration TWO_HOURS = Duration.ofHours(2);
  private static final Duration DAY = Duration.ofHours(24);
  private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private MeterRegistry meters;

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  /** Ana works 08-16 on {@code day} (slot 0); slot 1 (09-12) is uncovered. */
  private record Team(ScheduleWorld w, Person ana, Person ben, LocalDate day, String schedule) {

    String slot(int position) throws Exception {
      return slotIds(w, schedule).get(position);
    }
  }

  private Team team(int inDays) throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    LocalDate day = w.today.plusDays(inDays);
    String schedule =
        w.published(
            proposal(
                w.site(),
                day,
                day,
                List.of(shift("a", day, 8, 16, ana.employee()), shift("u", day, 9, 12, null))));
    return new Team(w, ana, ben, day, schedule);
  }

  private JsonNode mine(Person person) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(get(ScheduleWorld.MINE).header("Authorization", person.bearer()))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private static JsonNode shiftOf(JsonNode page, String slot) {
    for (JsonNode item : page.get("items")) {
      if (item.get("id").asText().equals(slot)) {
        return item;
      }
    }
    return null;
  }

  private int events(String recordId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM people.attendance_event WHERE record_id = ?::uuid",
            Integer.class,
            recordId);
    return n == null ? 0 : n;
  }

  private static String logs(CapturedOutput output) {
    return String.join(
        "\n", output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp")).toList());
  }

  /** A problem without its per-request fields. */
  private static JsonNode stable(JsonNode problem) {
    com.fasterxml.jackson.databind.node.ObjectNode copy =
        (com.fasterxml.jackson.databind.node.ObjectNode) problem.deepCopy();
    copy.remove("correlationId");
    copy.remove("instance");
    return copy;
  }

  // ------------------------------------------------------------------------------------------
  // 1, 7, 8, 13: the journey, duplicates, replays and privacy
  // ------------------------------------------------------------------------------------------

  @Test
  void anEmployeeChecksInAndOutAtTheServerInstantAndSeesTheirHistory(CapturedOutput output)
      throws Exception {
    Team t = team(3);
    ScheduleWorld w = t.w();
    String slot = t.slot(0);
    Instant[] shift = instants(jdbc, slot);
    Instant in = shift[0].minus(TWO_HOURS);
    clock.set(in);

    JsonNode before = shiftOf(mine(t.ana()), slot);
    assertThat(before.get("attendance").get("status").asText()).isEqualTo("NOT_RECORDED");
    assertThat(before.get("attendance").get("checkInAvailable").asBoolean()).isTrue();
    assertThat(before.get("attendance").get("checkOutAvailable").asBoolean()).isFalse();

    String key = Organizations.newKey();
    MockHttpServletResponse created = w.call(checkIn(t.ana(), key, slot));
    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(created.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode receipt = JSON.readTree(created.getContentAsString());
    assertThat(fieldNames(receipt))
        .containsExactly("attendanceRecordId", "slotId", "status", "checkedInAt", "checkedOutAt");
    assertThat(receipt.get("status").asText()).isEqualTo("OPEN");
    assertThat(Instant.parse(receipt.get("checkedInAt").asText())).isEqualTo(in);
    assertThat(receipt.get("checkedOutAt").isNull()).isTrue();
    assertThat(receipt.get("slotId").asText()).isEqualTo(slot);
    String id = receipt.get("attendanceRecordId").asText();

    // The evidence: the server instant, the unchanged subject, a digest (never the key).
    Map<String, Object> event =
        jdbc.queryForMap(
            "SELECT event_type, recorded_at, recorded_by, idempotency_digest, employee_id,"
                + " employment_id FROM people.attendance_event WHERE record_id = ?::uuid",
            id);
    assertThat(event.get("event_type")).isEqualTo("CHECKED_IN");
    assertThat(((java.sql.Timestamp) event.get("recorded_at")).toInstant()).isEqualTo(in);
    assertThat(event.get("recorded_by")).isEqualTo(t.ana().subject());
    assertThat((String) event.get("idempotency_digest"))
        .matches("^[0-9a-f]{64}$")
        .isNotEqualTo(key);
    assertThat(event.get("employee_id")).isEqualTo(t.ana().employee());
    assertThat(event.get("employment_id"))
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT id FROM people.employment WHERE employee_id = ?",
                UUID.class,
                t.ana().employee()));

    // My schedule: open, with the check-in; no check-out at the very same instant.
    JsonNode open = shiftOf(mine(t.ana()), slot).get("attendance");
    assertThat(fieldNames(open))
        .containsExactly(
            "status",
            "attendanceRecordId",
            "checkedInAt",
            "checkedOutAt",
            "checkInAvailable",
            "checkOutAvailable");
    assertThat(open.get("status").asText()).isEqualTo("OPEN");
    assertThat(open.get("attendanceRecordId").asText()).isEqualTo(id);
    assertThat(open.get("checkInAvailable").asBoolean()).isFalse();
    assertThat(open.get("checkOutAvailable").asBoolean()).isFalse();

    // A replay returns the stored receipt and records nothing; another key is refused.
    MockHttpServletResponse replayed = w.call(checkIn(t.ana(), key, slot));
    assertThat(replayed.getStatus()).isEqualTo(201);
    assertThat(replayed.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replayed.getContentAsString())).isEqualTo(receipt);
    w.problem(checkIn(t.ana(), Organizations.newKey(), slot), 409, "ATTENDANCE_ALREADY_CHECKED_IN");
    // Strictly after the check-in.
    w.problem(checkOut(t.ana(), Organizations.newKey(), id), 409, "ATTENDANCE_STATE_CONFLICT");
    assertThat(events(id)).isEqualTo(1);

    Instant out = shift[1].plus(Duration.ofHours(1));
    clock.set(out);
    String outKey = Organizations.newKey();
    JsonNode completed = w.expect(checkOut(t.ana(), outKey, id), 200);
    assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(Instant.parse(completed.get("checkedInAt").asText())).isEqualTo(in);
    assertThat(Instant.parse(completed.get("checkedOutAt").asText())).isEqualTo(out);
    MockHttpServletResponse again = w.call(checkOut(t.ana(), outKey, id));
    assertThat(again.getStatus()).isEqualTo(200);
    assertThat(again.getHeader("Idempotent-Replayed")).isEqualTo("true");
    w.problem(checkOut(t.ana(), Organizations.newKey(), id), 409, "ATTENDANCE_ALREADY_CHECKED_OUT");
    assertThat(events(id)).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM people.attendance_record WHERE id = ?::uuid", String.class, id))
        .isEqualTo("COMPLETED");

    // The history: newest first, the shift and both server instants, private and no-store.
    MockHttpServletResponse listed = w.call(history(t.ana().bearer()));
    assertThat(listed.getStatus()).isEqualTo(200);
    assertThat(listed.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode page = JSON.readTree(listed.getContentAsString(StandardCharsets.UTF_8));
    assertThat(fieldNames(page)).containsExactly("items", "nextCursor", "asOfInstant");
    assertThat(Instant.parse(page.get("asOfInstant").asText())).isEqualTo(out);
    JsonNode item = page.get("items").get(0);
    assertThat(fieldNames(item))
        .containsExactly(
            "id", "status", "checkedInAt", "checkedOutAt", "checkOutAvailable", "shift");
    assertThat(item.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(item.get("checkOutAvailable").asBoolean()).isFalse();
    assertThat(item.get("shift").get("slotId").asText()).isEqualTo(slot);
    assertThat(item.get("shift").get("labels").get("fr").asText()).isEqualTo("Caissière");
    assertThat(item.toString()).doesNotContain(t.ana().subject()).doesNotContain("employment");

    // Audit metadata, events and logs carry identifiers and states only.
    String tenant = w.tenant().toString();
    List<String> audit =
        jdbc.queryForList(
            "SELECT action || ' ' || metadata::text FROM platform.audit_event"
                + " WHERE tenant_id = ?::uuid AND action LIKE 'attendance.%' ORDER BY occurred_at",
            String.class, tenant);
    assertThat(audit).anyMatch(a -> a.startsWith("attendance.check-in "));
    assertThat(audit).anyMatch(a -> a.startsWith("attendance.check-out "));
    assertThat(audit).anyMatch(a -> a.startsWith("attendance.self-list "));
    List<String> envelopes =
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' LIKE 'people.attendance.%'",
            String.class, tenant);
    assertThat(envelopes).hasSize(2);
    JsonNode data = JSON.readTree(envelopes.get(0)).get("data");
    assertThat(fieldNames(data))
        .containsExactlyInAnyOrder(
            "attendanceRecordId",
            "scheduleId",
            "slotId",
            "employeeId",
            "employmentId",
            "eventType",
            "recordedAt");
    String evidence = String.join("\n", audit) + String.join("\n", envelopes) + logs(output);
    for (String secret :
        List.of(
            t.ana().subject(),
            t.ana().number(),
            "Kalala",
            "Caissière",
            "Cashier",
            key,
            outKey,
            "Bearer ")) {
      assertThat(evidence).as("no %s", secret).doesNotContain(secret);
    }
    assertThat(logs(output))
        .contains("\"attendance_checked_in\"")
        .contains("\"attendance_checked_out\"");
    assertThat(
            meters
                .find(com.divalhr.core.people.schedule.application.AttendanceService.LATENCY_METRIC)
                .tag("operation", "attendance.check-in")
                .tag("outcome", "success")
                .timer())
        .isNotNull();
  }

  // ------------------------------------------------------------------------------------------
  // 2, 8: the windows at their exact boundaries
  // ------------------------------------------------------------------------------------------

  @Test
  void bothWindowsAreExactAtTheirBoundaries() throws Exception {
    Team t = team(3);
    ScheduleWorld w = t.w();
    String slot = t.slot(0);
    Instant[] shift = instants(jdbc, slot);

    clock.set(shift[0].minus(TWO_HOURS).minus(MICRO));
    assertThat(shiftOf(mine(t.ana()), slot).get("attendance").get("checkInAvailable").asBoolean())
        .isFalse();
    w.problem(checkIn(t.ana(), Organizations.newKey(), slot), 409, "ATTENDANCE_CHECK_IN_TOO_EARLY");
    clock.set(shift[1]);
    w.problem(
        checkIn(t.ana(), Organizations.newKey(), slot), 409, "ATTENDANCE_CHECK_IN_WINDOW_CLOSED");
    clock.set(shift[1].minus(MICRO));
    assertThat(shiftOf(mine(t.ana()), slot).get("attendance").get("checkInAvailable").asBoolean())
        .isTrue();
    String id =
        w.expect(checkIn(t.ana(), Organizations.newKey(), slot), 201)
            .get("attendanceRecordId")
            .asText();

    clock.set(shift[1].plus(DAY).plus(MICRO));
    JsonNode late = w.expect(history(t.ana().bearer()), 200).get("items").get(0);
    assertThat(late.get("status").asText()).isEqualTo("OPEN");
    assertThat(late.get("checkOutAvailable").asBoolean()).isFalse();
    w.problem(
        checkOut(t.ana(), Organizations.newKey(), id), 409, "ATTENDANCE_CHECK_OUT_WINDOW_CLOSED");
    clock.set(shift[1].plus(DAY));
    assertThat(
            w.expect(history(t.ana().bearer()), 200)
                .get("items")
                .get(0)
                .get("checkOutAvailable")
                .asBoolean())
        .isTrue();
    w.expect(checkOut(t.ana(), Organizations.newKey(), id), 200);
    assertThat(events(id)).isEqualTo(2);
  }

  // ------------------------------------------------------------------------------------------
  // 3: one answer for every undisclosable slot and record; validation; no recording power
  // ------------------------------------------------------------------------------------------

  @Test
  void undisclosableSlotsAndRecordsAreOneIndistinguishable404() throws Exception {
    Team t = team(3);
    ScheduleWorld w = t.w();
    Team foreign = team(3);
    Instant[] shift = instants(jdbc, t.slot(0));
    clock.set(shift[0].minus(Duration.ofHours(1)));

    List<JsonNode> answers = new ArrayList<>();
    for (Object slot :
        List.of(UUID.randomUUID().toString(), "not-a-uuid", foreign.slot(0), t.slot(1), 42)) {
      answers.add(
          stable(
              w.problem(
                  checkIn(t.ana().bearer(), Organizations.newKey(), Map.of("slotId", slot)),
                  404,
                  "ATTENDANCE_SHIFT_NOT_FOUND")));
    }
    // Another employee's shift.
    answers.add(
        stable(
            w.problem(
                checkIn(t.ben(), Organizations.newKey(), t.slot(0)),
                404,
                "ATTENDANCE_SHIFT_NOT_FOUND")));
    assertThat(answers).allMatch(answers.get(0)::equals);
    assertThat(answers.get(0).get("params").size()).isZero();
    assertThat(answers.get(0).toString()).doesNotContain(t.slot(0)).doesNotContain(foreign.slot(0));

    // Validation (before any lookup): the body, the slot and the key.
    w.problem(
        checkIn(t.ben().bearer(), Organizations.newKey(), Map.of()), 400, "VALIDATION_FAILED");
    w.problem(
        checkIn(t.ben().bearer(), Organizations.newKey(), Map.of("slotId", t.slot(0), "at", "x")),
        400,
        "VALIDATION_FAILED");
    w.problem(checkIn(t.ben(), null, t.slot(0)), 400, "VALIDATION_FAILED");
    w.problem(
        checkIn(
            t.ben().bearer(),
            Organizations.newKey(),
            Map.of("slotId", t.slot(0), "employeeId", t.ben().employee().toString())),
        400,
        "VALIDATION_FAILED");

    // A tenant administrator has no recording power from that role.
    assertThat(
            w.call(checkIn(w.admin, Organizations.newKey(), Map.of("slotId", t.slot(0))))
                .getStatus())
        .isEqualTo(403);
    String id =
        w.expect(checkIn(t.ana(), Organizations.newKey(), t.slot(0)), 201)
            .get("attendanceRecordId")
            .asText();
    clock.set(shift[0].plus(Duration.ofHours(1)));

    // Records: unknown, malformed, foreign and another employee's are one 404.
    String foreignRecord =
        w.expect(checkIn(foreign.ana(), Organizations.newKey(), foreign.slot(0)), 201)
            .get("attendanceRecordId")
            .asText();
    List<JsonNode> records = new ArrayList<>();
    for (MockHttpRequest call :
        List.of(
            new MockHttpRequest(t.ana(), UUID.randomUUID().toString()),
            new MockHttpRequest(t.ana(), "nope"),
            new MockHttpRequest(t.ana(), foreignRecord),
            new MockHttpRequest(t.ben(), id))) {
      records.add(
          stable(
              w.problem(
                  checkOut(call.person(), Organizations.newKey(), call.record()),
                  404,
                  "ATTENDANCE_RECORD_NOT_FOUND")));
    }
    assertThat(records).allMatch(records.get(0)::equals);
    assertThat(records.get(0).toString()).doesNotContain(id).doesNotContain(foreignRecord);
    assertThat(w.call(checkOut(w.admin, Organizations.newKey(), id)).getStatus()).isEqualTo(403);
    assertThat(events(id)).isEqualTo(1);

    // Tenant isolation of the history: the foreign tenant's employee sees only their own.
    JsonNode theirs = w.expect(history(foreign.ana().bearer()), 200).get("items");
    assertThat(theirs).hasSize(1);
    assertThat(theirs.get(0).get("id").asText()).isEqualTo(foreignRecord);
    assertThat(w.expect(history(t.ben().bearer()), 200).get("items")).isEmpty();
  }

  private record MockHttpRequest(Person person, String record) {}

  // ------------------------------------------------------------------------------------------
  // 4, 5: cancelled, replaced and substituted shifts; evidence kept
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheCurrentOperationalCoverageChecksInAndEvidenceSurvivesLaterChanges() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, ben);
    LocalDate d3 = w.today.plusDays(3);
    LocalDate d4 = w.today.plusDays(4);
    LocalDate d5 = w.today.plusDays(5);
    LocalDate d6 = w.today.plusDays(6);
    String substituted =
        w.published(proposal(w.site(), d3, d3, List.of(shift("a", d3, 8, 16, ana.employee()))));
    String cancelled =
        w.published(proposal(w.site(), d4, d4, List.of(shift("a", d4, 8, 16, ana.employee()))));
    String original =
        w.published(proposal(w.site(), d5, d5, List.of(shift("a", d5, 8, 16, ana.employee()))));
    String later =
        w.published(proposal(w.site(), d6, d6, List.of(shift("a", d6, 8, 16, ana.employee()))));
    String s3 = slotIds(w, substituted).get(0);
    String s4 = slotIds(w, cancelled).get(0);
    String s5 = slotIds(w, original).get(0);
    String s6 = slotIds(w, later).get(0);

    // Before any window: a substitution to Ben, a cancellation and a replacement.
    String requestId = w.expect(request(ana, Organizations.newKey(), s3), 201).get("id").asText();
    w.expect(approve(mia, requestId, Organizations.newKey(), ben.employee()), 200);
    w.expect(cancel(w.admin, Organizations.newKey(), cancelled, "Fermeture"), 200);
    String successor =
        w.replaced(
            original, List.of(shift("a", d5, 8, 16, ana.employee())), "Nouvelle organisation");
    String s5b = slotIds(w, successor).get(0);

    // The substituted shift: the published employee may not, the substitute may.
    clock.set(instants(jdbc, s3)[0].minus(Duration.ofHours(1)));
    w.problem(checkIn(ana, Organizations.newKey(), s3), 404, "ATTENDANCE_SHIFT_NOT_FOUND");
    String benRecord =
        w.expect(checkIn(ben, Organizations.newKey(), s3), 201).get("attendanceRecordId").asText();
    JsonNode benShift = shiftOf(mine(ben), s3);
    assertThat(benShift.get("assignmentSource").asText()).isEqualTo("SUBSTITUTION");
    assertThat(benShift.get("attendance").get("status").asText()).isEqualTo("OPEN");
    assertThat(
            jdbc.queryForObject(
                "SELECT employee_id FROM people.attendance_record WHERE id = ?::uuid",
                UUID.class,
                benRecord))
        .isEqualTo(ben.employee());

    // A cancelled schedule, a replaced original: refused; the successor is checked in.
    clock.set(instants(jdbc, s4)[0].minus(Duration.ofHours(1)));
    w.problem(checkIn(ana, Organizations.newKey(), s4), 404, "ATTENDANCE_SHIFT_NOT_FOUND");
    clock.set(instants(jdbc, s5)[0].minus(Duration.ofHours(1)));
    w.problem(checkIn(ana, Organizations.newKey(), s5), 404, "ATTENDANCE_SHIFT_NOT_FOUND");
    String fifth =
        w.expect(checkIn(ana, Organizations.newKey(), s5b), 201).get("attendanceRecordId").asText();
    clock.set(instants(jdbc, s5b)[0].plus(Duration.ofHours(1)));
    w.expect(checkOut(ana, Organizations.newKey(), fifth), 200);

    // Checked in, then the schedule is cancelled: the record stays and can be completed.
    Instant[] sixth = instants(jdbc, s6);
    clock.set(sixth[0].minus(Duration.ofHours(1)));
    String kept =
        w.expect(checkIn(ana, Organizations.newKey(), s6), 201).get("attendanceRecordId").asText();
    w.expect(cancel(w.admin, Organizations.newKey(), later, "Fermeture"), 200);
    assertThat(shiftOf(mine(ana), s6)).isNull();
    clock.set(sixth[0].plus(Duration.ofHours(2)));
    JsonNode done = w.expect(checkOut(ana, Organizations.newKey(), kept), 200);
    assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
    JsonNode items = w.expect(history(ana.bearer()), 200).get("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("id").asText()).isEqualTo(kept);
    assertThat(items.get(0).get("shift").get("slotId").asText()).isEqualTo(s6);
  }

  // ------------------------------------------------------------------------------------------
  // 6: one actionable open record
  // ------------------------------------------------------------------------------------------

  @Test
  void anEmployeeHasOneActionableOpenRecordAndAMissedCheckOutNoLongerBlocks() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate d = w.today.plusDays(3);
    LocalDate e = w.today.plusDays(6);
    String first =
        w.published(
            proposal(
                w.site(),
                d,
                d,
                List.of(
                    shift("a", d, 8, 16, ana.employee()), shift("b", d, 17, 20, ana.employee()))));
    String second =
        w.published(proposal(w.site(), e, e, List.of(shift("c", e, 8, 16, ana.employee()))));
    String a = slotIds(w, first).get(0);
    String b = slotIds(w, first).get(1);
    String c = slotIds(w, second).get(0);
    Instant[] shiftA = instants(jdbc, a);
    Instant[] shiftB = instants(jdbc, b);

    clock.set(shiftA[0].minus(Duration.ofHours(1)));
    String ra =
        w.expect(checkIn(ana, Organizations.newKey(), a), 201).get("attendanceRecordId").asText();
    clock.set(shiftA[1].plus(Duration.ofMinutes(30)));
    w.problem(checkIn(ana, Organizations.newKey(), b), 409, "ATTENDANCE_OPEN_RECORD_EXISTS");
    w.expect(checkOut(ana, Organizations.newKey(), ra), 200);
    clock.set(shiftB[0].minus(Duration.ofMinutes(10)));
    String rb =
        w.expect(checkIn(ana, Organizations.newKey(), b), 201).get("attendanceRecordId").asText();

    // B is never checked out: once its window closed it no longer blocks C.
    clock.set(instants(jdbc, c)[0].minus(Duration.ofHours(1)));
    assertThat(clock.instant()).isAfter(shiftB[1].plus(DAY));
    w.expect(checkIn(ana, Organizations.newKey(), c), 201);
    JsonNode missed = null;
    for (JsonNode item : w.expect(history(ana.bearer()), 200).get("items")) {
      if (item.get("id").asText().equals(rb)) {
        missed = item;
      }
    }
    assertThat(missed).isNotNull();
    assertThat(missed.get("status").asText()).isEqualTo("OPEN");
    assertThat(missed.get("checkOutAvailable").asBoolean()).isFalse();
    w.problem(checkOut(ana, Organizations.newKey(), rb), 409, "ATTENDANCE_CHECK_OUT_WINDOW_CLOSED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM people.attendance_record WHERE employee_id = ?"
                    + " AND check_out_event_id IS NULL",
                Integer.class,
                ana.employee()))
        .isEqualTo(2);
  }

  // ------------------------------------------------------------------------------------------
  // 7: replays re-authorize the caller's current link
  // ------------------------------------------------------------------------------------------

  @Test
  void aReplayIsReauthorizedAndADeniedReplayRevealsNothing() throws Exception {
    Team t = team(3);
    ScheduleWorld w = t.w();
    Instant[] shift = instants(jdbc, t.slot(0));
    clock.set(shift[0].minus(Duration.ofHours(1)));
    String key = Organizations.newKey();
    String id = w.expect(checkIn(t.ana(), key, t.slot(0)), 201).get("attendanceRecordId").asText();
    w.unlink(t.ana().employee());
    MockHttpServletResponse denied = w.call(checkIn(t.ana(), key, t.slot(0)));
    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(JSON.readTree(denied.getContentAsString()).get("code").asText())
        .isEqualTo("EMPLOYEE_LINK_REQUIRED");
    assertThat(denied.getContentAsString()).doesNotContain(id).doesNotContain(t.slot(0));
    assertThat(denied.getHeader("Idempotent-Replayed")).isNull();
    w.problem(history(t.ana().bearer()), 403, "EMPLOYEE_LINK_REQUIRED");
    w.problem(checkOut(t.ana(), Organizations.newKey(), id), 403, "EMPLOYEE_LINK_REQUIRED");
    assertThat(events(id)).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // 11: the history's cursor binding and limits
  // ------------------------------------------------------------------------------------------

  @Test
  void theHistoryPagesNewestFirstWithACursorBoundToItsCaller() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    List<String> records = new ArrayList<>();
    for (int days = 3; days <= 5; days++) {
      LocalDate d = w.today.plusDays(days);
      String slot =
          slotIds(
                  w,
                  w.published(
                      proposal(w.site(), d, d, List.of(shift("a", d, 8, 16, ana.employee())))))
              .get(0);
      Instant[] shift = instants(jdbc, slot);
      clock.set(shift[0]);
      String id =
          w.expect(checkIn(ana, Organizations.newKey(), slot), 201)
              .get("attendanceRecordId")
              .asText();
      clock.set(shift[1]);
      w.expect(checkOut(ana, Organizations.newKey(), id), 200);
      records.add(0, id);
    }
    JsonNode first = w.expect(history(ana.bearer()).queryParam("limit", "2"), 200);
    assertThat(first.get("items")).hasSize(2);
    assertThat(first.get("items").get(0).get("id").asText()).isEqualTo(records.get(0));
    assertThat(first.get("items").get(1).get("id").asText()).isEqualTo(records.get(1));
    String cursor = first.get("nextCursor").asText();
    Instant asOf = Instant.parse(first.get("asOfInstant").asText());
    clock.set(asOf.plus(Duration.ofHours(1)));
    JsonNode second =
        w.expect(history(ana.bearer()).queryParam("limit", "2").queryParam("cursor", cursor), 200);
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("items").get(0).get("id").asText()).isEqualTo(records.get(2));
    assertThat(Instant.parse(second.get("asOfInstant").asText())).isEqualTo(asOf);
    assertThat(second.get("nextCursor").isNull()).isTrue();
    // Another caller, another page size or a tampered cursor: refused.
    w.problem(
        history(ben.bearer()).queryParam("limit", "2").queryParam("cursor", cursor),
        400,
        "CURSOR_INVALID");
    w.problem(
        history(ana.bearer()).queryParam("limit", "3").queryParam("cursor", cursor),
        400,
        "CURSOR_INVALID");
    w.problem(
        history(ana.bearer()).queryParam("limit", "2").queryParam("cursor", cursor + "x"),
        400,
        "CURSOR_INVALID");
    for (String limit : List.of("0", "101", "abc")) {
      w.problem(history(ana.bearer()).queryParam("limit", limit), 400, "VALIDATION_FAILED");
    }
    assertThat(w.expect(history(ana.bearer()).queryParam("limit", "100"), 200).get("items"))
        .hasSize(3);
  }
}
