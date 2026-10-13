package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.history;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.CorrectionCalls.EXPLANATION;
import static com.divalhr.core.people.schedule.CorrectionCalls.at;
import static com.divalhr.core.people.schedule.CorrectionCalls.commit;
import static com.divalhr.core.people.schedule.CorrectionCalls.commitMissing;
import static com.divalhr.core.people.schedule.CorrectionCalls.commitRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.missing;
import static com.divalhr.core.people.schedule.CorrectionCalls.previewMissing;
import static com.divalhr.core.people.schedule.CorrectionCalls.previewRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.values;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.support.Bootstraps;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-062 (Issue #126, C62-11 items 1 to 7 and 9 to 14) against real PostgreSQL: creating a missing
 * record and replacing times (adding, replacing and clearing a check-out); every time rule at its
 * exact boundary and the explanation grammar; the derived exceptions changing at once; the original
 * events unchanged; the numbered history; a preview that writes nothing and a commit that needs the
 * identical fresh preview; idempotent replays; exact-MFA tenant administrators only, inside their
 * tenant; substitutes, recorded owners and terminal schedules; and the explanation's privacy in
 * logs, metrics, errors, events and employee views.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class AttendanceCorrectionIntegrationTest {

  private static final String EXCEPTIONS = "/api/v1/attendance/exceptions";
  private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);
  private static final Duration DAY = Duration.ofHours(24);

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private MeterRegistry meters;

  @AfterEach
  void reset() {
    clock.reset();
  }

  /** A fresh exact-MFA tenant administrator of the world (stays inside the per-subject limit). */
  private static String hr(ScheduleWorld w) {
    return Hierarchy.bearer(w.tenant(), "sub-corr-" + UUID.randomUUID(), "tenant-admin");
  }

  private static String key() {
    return Organizations.newKey();
  }

  private static JsonNode stable(JsonNode problem) {
    ObjectNode copy = (ObjectNode) problem.deepCopy();
    copy.remove("correlationId");
    copy.remove("instance");
    return copy;
  }

  private static List<String> types(JsonNode array) {
    List<String> types = new ArrayList<>();
    array.forEach(type -> types.add(type.asText()));
    return types;
  }

  /** The tenant's exceptions as "TYPE slot employee". */
  private List<String> exceptions(ScheduleWorld w, LocalDate from) throws Exception {
    JsonNode page =
        w.expect(
            get(EXCEPTIONS + "?from=" + from + "&to=" + from.plusDays(6))
                .header("Authorization", hr(w)),
            200);
    List<String> rows = new ArrayList<>();
    page.get("items")
        .forEach(
            item ->
                rows.add(
                    item.get("type").asText()
                        + " "
                        + item.get("slotId").asText()
                        + " "
                        + item.get("employee").get("id").asText()));
    return rows;
  }

  private static String row(String type, String slot, Person person) {
    return type + " " + slot + " " + person.employee();
  }

  /** Previews then commits, as one administrator; returns the receipt. */
  private JsonNode correctRecord(ScheduleWorld w, String record, Map<String, Object> proposal)
      throws Exception {
    String admin = hr(w);
    JsonNode preview = w.expect(previewRecord(admin, record, proposal), 200);
    return w.expect(commitRecord(admin, key(), record, commit(proposal, preview)), 201);
  }

  private JsonNode createMissing(ScheduleWorld w, Map<String, Object> proposal) throws Exception {
    String admin = hr(w);
    JsonNode preview = w.expect(previewMissing(admin, proposal), 200);
    return w.expect(commitMissing(admin, key(), commit(proposal, preview)), 201);
  }

  private String events(String record) {
    return jdbc.queryForObject(
        "SELECT coalesce(string_agg(row_to_json(e)::text, '|' ORDER BY e.event_type), '')"
            + " FROM people.attendance_event e WHERE e.record_id = ?::uuid",
        String.class,
        record);
  }

  private JsonNode myRecord(Person person, String record) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(history(person.bearer())).andReturn().getResponse();
    for (JsonNode item :
        JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("items")) {
      if (item.get("id").asText().equals(record)) {
        return item;
      }
    }
    throw new AssertionError("record not in the employee's history");
  }

  private JsonNode myShift(ScheduleWorld w, Person person, LocalDate day, String slot)
      throws Exception {
    JsonNode page =
        w.expect(
            get(ScheduleWorld.MINE + "?from=" + day + "&to=" + day)
                .header("Authorization", person.bearer()),
            200);
    for (JsonNode item : page.get("items")) {
      if (item.get("id").asText().equals(slot)) {
        return item.get("attendance");
      }
    }
    throw new AssertionError("shift not in the employee's schedule");
  }

  // ------------------------------------------------------------------------------------------
  // 1, 4: a missing record, with a check-in only or with a check-out
  // ------------------------------------------------------------------------------------------

  @Test
  void aMissingRecordIsCreatedWithACheckInOnlyOrWithACheckOutAndItsExceptionDisappears()
      throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate d = w.today.plusDays(3);
    String schedule =
        w.published(
            proposal(
                w.site(),
                d,
                d.plusDays(1),
                List.of(
                    shift("s0", d, 8, 16, ana.employee()),
                    shift("s1", d.plusDays(1), 8, 16, ana.employee()))));
    List<String> slots = slotIds(w, schedule);
    Instant[] s0 = instants(jdbc, slots.get(0));
    Instant[] s1 = instants(jdbc, slots.get(1));
    // At s1's end, exactly s0's check-out deadline (inclusive): both missing check-ins only.
    clock.set(s1[1]);
    assertThat(exceptions(w, d))
        .containsExactlyInAnyOrder(
            row("MISSING_CHECK_IN", slots.get(0), ana), row("MISSING_CHECK_IN", slots.get(1), ana));

    // s0: a check-in only. The preview shows the target, boundaries and both exception sets.
    Map<String, Object> only = missing(slots.get(0), ana.employee(), s0[0], null);
    String admin = hr(w);
    JsonNode preview = w.expect(previewMissing(admin, only), 200);
    assertThat(preview.get("operation").asText()).isEqualTo("CREATE_MISSING_RECORD");
    assertThat(preview.get("targetVersion").asInt()).isZero();
    assertThat(preview.get("attendanceRecordId").isNull()).isTrue();
    assertThat(preview.get("before").isNull()).isTrue();
    assertThat(preview.get("employee").get("employeeNumber").asText()).isEqualTo(ana.number());
    assertThat(preview.get("employee").get("familyName").asText()).isEqualTo("Kalala");
    assertThat(preview.get("shift").get("labels").get("fr").asText()).isNotEmpty();
    assertThat(Instant.parse(preview.get("boundaries").get("checkInOpensAt").asText()))
        .isEqualTo(s0[0].minus(Duration.ofHours(2)));
    assertThat(Instant.parse(preview.get("boundaries").get("checkOutUntil").asText()))
        .isEqualTo(s0[1].plus(DAY));
    assertThat(types(preview.get("exceptionsBefore"))).containsExactly("MISSING_CHECK_IN");
    assertThat(types(preview.get("exceptionsAfter"))).isEmpty();
    assertThat(types(preview.get("warnings"))).containsExactly("NO_CHECK_OUT");
    assertThat(preview.get("explanation").asText()).isEqualTo(EXPLANATION);
    JsonNode created = w.expect(commitMissing(admin, key(), commit(only, preview)), 201);
    String r0 = created.get("record").get("attendanceRecordId").asText();
    assertThat(created.get("correctionVersion").asInt()).isEqualTo(1);
    assertThat(created.get("record").get("status").asText()).isEqualTo("OPEN");
    assertThat(created.get("before").isNull()).isTrue();
    assertThat(created.has("explanation")).isFalse();

    // s1: a check-in and a check-out.
    JsonNode both =
        createMissing(
            w, missing(slots.get(1), ana.employee(), s1[0], s1[1].minus(Duration.ofMinutes(3))));
    String r1 = both.get("record").get("attendanceRecordId").asText();
    assertThat(both.get("record").get("status").asText()).isEqualTo("COMPLETED");

    // The derived exceptions change at once: no missing check-in; s0 has no check-out yet but its
    // deadline has not passed.
    assertThat(exceptions(w, d)).isEmpty();
    clock.set(s0[1].plus(DAY).plus(MICRO));
    assertThat(exceptions(w, d)).containsExactly(row("MISSING_CHECK_OUT", slots.get(0), ana));

    // No event was invented; the records name their correction.
    assertThat(events(r0)).isEmpty();
    assertThat(events(r1)).isEmpty();
    assertThat(
            w.count(
                "SELECT count(*) FROM people.attendance_record WHERE tenant_id = ?"
                    + " AND check_in_event_id IS NULL AND correction_version = 1"
                    + " AND corrected_at IS NOT NULL",
                w.tenant()))
        .isEqualTo(2);

    // The employee sees the effective times and that the organization corrected them; never who
    // or why.
    JsonNode mine = myRecord(ana, r1);
    assertThat(mine.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(Instant.parse(mine.get("checkedInAt").asText())).isEqualTo(s1[0]);
    assertThat(mine.get("correctedAt").isNull()).isFalse();
    assertThat(mine.get("checkInRecordingMethod").isNull()).isTrue();
    assertThat(mine.toString())
        .doesNotContain(EXPLANATION)
        .doesNotContain("SUPERVISOR_OR_EMPLOYEE");
    JsonNode shift = myShift(w, ana, d, slots.get(0));
    assertThat(shift.get("status").asText()).isEqualTo("OPEN");
    assertThat(shift.get("correctedAt").isNull()).isFalse();
    assertThat(shift.get("checkOutAvailable").asBoolean()).isFalse();
  }

  // ------------------------------------------------------------------------------------------
  // 2, 4, 5, 6: replace a check-in; add, replace and clear a check-out; numbered history
  // ------------------------------------------------------------------------------------------

  @Test
  void timesAreReplacedACheckOutAddedReplacedAndClearedWithANumberedHistory() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate d = w.today.plusDays(3);
    String slot =
        slotIds(
                w,
                w.published(
                    proposal(w.site(), d, d, List.of(shift("a", d, 8, 16, ana.employee())))))
            .get(0);
    Instant[] at = instants(jdbc, slot);
    // Ana checks in 20 minutes late and never checks out.
    clock.set(at[0].plus(Duration.ofMinutes(20)));
    String record = w.expect(checkIn(ana, key(), slot), 201).get("attendanceRecordId").asText();
    String original = events(record);
    clock.set(at[1].plus(DAY).plus(Duration.ofHours(2)));
    assertThat(exceptions(w, d))
        .containsExactlyInAnyOrder(
            row("LATE_CHECK_IN", slot, ana), row("MISSING_CHECK_OUT", slot, ana));

    // 1. The check-in replaced (on time) and the missing check-out added.
    Map<String, Object> first = values(at[0], at[1]);
    String admin = hr(w);
    JsonNode preview = w.expect(previewRecord(admin, record, first), 200);
    assertThat(preview.get("targetVersion").asInt()).isZero();
    assertThat(Instant.parse(preview.get("before").get("checkedInAt").asText()))
        .isEqualTo(at[0].plus(Duration.ofMinutes(20)));
    assertThat(preview.get("before").get("checkedOutAt").isNull()).isTrue();
    assertThat(types(preview.get("exceptionsBefore")))
        .containsExactly("LATE_CHECK_IN", "MISSING_CHECK_OUT");
    assertThat(types(preview.get("exceptionsAfter"))).isEmpty();
    assertThat(types(preview.get("warnings"))).containsExactly("ORIGINAL_EVENTS_KEPT");
    JsonNode v1 = w.expect(commitRecord(admin, key(), record, commit(first, preview)), 201);
    assertThat(v1.get("correctionVersion").asInt()).isEqualTo(1);
    assertThat(v1.get("record").get("status").asText()).isEqualTo("COMPLETED");
    assertThat(exceptions(w, d)).isEmpty();

    // 2. The check-out replaced (early): an exception appears at once.
    JsonNode v2 = correctRecord(w, record, values(at[0], at[1].minus(Duration.ofMinutes(30))));
    assertThat(v2.get("correctionVersion").asInt()).isEqualTo(2);
    assertThat(exceptions(w, d)).containsExactly(row("EARLY_CHECK_OUT", slot, ana));

    // 3. The check-out cleared: open again; the employee cannot check out (its event stays).
    String admin3 = hr(w);
    Map<String, Object> cleared = values(at[0], null);
    JsonNode clearing = w.expect(previewRecord(admin3, record, cleared), 200);
    assertThat(types(clearing.get("warnings"))).contains("CHECK_OUT_REMOVED", "NO_CHECK_OUT");
    JsonNode v3 = w.expect(commitRecord(admin3, key(), record, commit(cleared, clearing)), 201);
    assertThat(v3.get("record").get("status").asText()).isEqualTo("OPEN");
    assertThat(v3.get("record").get("checkedOutAt").isNull()).isTrue();
    assertThat(exceptions(w, d)).containsExactly(row("MISSING_CHECK_OUT", slot, ana));

    // The original events are byte-for-byte unchanged after three corrections.
    assertThat(events(record)).isEqualTo(original);

    // History: newest first, before and after of each, the explanation, who (pseudonymous).
    JsonNode history = w.expect(CorrectionCalls.history(admin3, record), 200);
    assertThat(history.get("record").get("correctionVersion").asInt()).isEqualTo(3);
    List<Integer> versions = new ArrayList<>();
    history.get("corrections").forEach(c -> versions.add(c.get("version").asInt()));
    assertThat(versions).containsExactly(3, 2, 1);
    JsonNode latest = history.get("corrections").get(0);
    assertThat(latest.get("correctedByYou").asBoolean()).isTrue();
    assertThat(history.get("corrections").get(2).get("correctedByYou").asBoolean()).isFalse();
    assertThat(latest.get("administratorReference").asText()).matches("^[0-9A-F]{8}$");
    assertThat(latest.get("explanation").asText()).isEqualTo(EXPLANATION);
    assertThat(Instant.parse(latest.get("before").get("checkedOutAt").asText()))
        .isEqualTo(at[1].minus(Duration.ofMinutes(30)));
    assertThat(latest.get("after").get("checkedOutAt").isNull()).isTrue();
    JsonNode oldest = history.get("corrections").get(2);
    assertThat(Instant.parse(oldest.get("before").get("checkedInAt").asText()))
        .isEqualTo(at[0].plus(Duration.ofMinutes(20)));
    assertThat(history.get("originalEvents").get(0).get("type").asText()).isEqualTo("CHECKED_IN");
    assertThat(history.get("originalEvents").get(0).get("recordingMethod").asText())
        .isEqualTo("SELF");
    assertThat(history.get("originalEvents")).hasSize(1);
    assertThat(history.toString()).doesNotContain("sub-corr-");
  }

  @Test
  void anOpenRecordCorrectedKeepsItsSingleCheckOutAndAClearedOneIsNeverReattached()
      throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate d = w.today.plusDays(3);
    String schedule =
        w.published(
            proposal(
                w.site(),
                d,
                d.plusDays(1),
                List.of(
                    shift("a", d, 8, 16, ana.employee()),
                    shift("b", d.plusDays(1), 8, 16, ana.employee()))));
    List<String> slots = slotIds(w, schedule);
    Instant[] a = instants(jdbc, slots.get(0));
    // An open record's check-in corrected: Ana still checks out herself, after the corrected
    // check-in.
    clock.set(a[0].plus(Duration.ofMinutes(15)));
    String record =
        w.expect(checkIn(ana, key(), slots.get(0)), 201).get("attendanceRecordId").asText();
    clock.set(a[0].plus(Duration.ofHours(1)));
    correctRecord(w, record, values(a[0], null));
    assertThat(myShift(w, ana, d, slots.get(0)).get("checkOutAvailable").asBoolean()).isTrue();
    clock.set(a[1]);
    JsonNode out = w.expect(checkOut(ana, key(), record), 200);
    assertThat(Instant.parse(out.get("checkedInAt").asText())).isEqualTo(a[0]);
    // Cleared by a correction, the check-out is never re-recorded by the employee.
    correctRecord(w, record, values(a[0], null));
    assertThat(myShift(w, ana, d, slots.get(0)).get("checkOutAvailable").asBoolean()).isFalse();
    w.problem(checkOut(ana, key(), record), 409, "ATTENDANCE_ALREADY_CHECKED_OUT");
    // A correction may add it back.
    JsonNode back = correctRecord(w, record, values(a[0], a[1]));
    assertThat(back.get("correctionVersion").asInt()).isEqualTo(3);
    assertThat(back.get("record").get("status").asText()).isEqualTo("COMPLETED");
  }

  // ------------------------------------------------------------------------------------------
  // 3: exact time boundaries, ordering, the future bound and the explanation grammar
  // ------------------------------------------------------------------------------------------

  @Test
  void everyTimeRuleAndTheExplanationGrammarHoldAtTheirExactBoundaries() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate d = w.today.plusDays(3);
    String slot =
        slotIds(
                w,
                w.published(
                    proposal(w.site(), d, d, List.of(shift("a", d, 8, 16, ana.employee())))))
            .get(0);
    Instant[] at = instants(jdbc, slot);
    Instant opens = at[0].minus(Duration.ofHours(2));
    Instant until = at[1].plus(DAY);
    Instant now = until.plus(Duration.ofHours(1));
    clock.set(now);
    List<Object[]> cases = new ArrayList<>();
    // Accepted exactly at each boundary.
    cases.add(new Object[] {values(opens, until), 200, null});
    cases.add(new Object[] {values(at[1].minus(MICRO), null), 200, null});
    // One microsecond beyond each: RANGE on the field, nothing clamped.
    cases.add(new Object[] {values(opens.minus(MICRO), at[1]), 400, "checkedInAt RANGE"});
    cases.add(new Object[] {values(at[1], null), 400, "checkedInAt RANGE"});
    cases.add(new Object[] {values(at[0], until.plus(MICRO)), 400, "checkedOutAt RANGE"});
    // Strictly ordered, never reordered.
    cases.add(new Object[] {values(at[0], at[0]), 400, "checkedOutAt RANGE"});
    cases.add(new Object[] {values(at[1], at[0]), 400, "checkedOutAt RANGE"});
    for (Object[] c : cases) {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>) c[0];
      MockHttpServletResponse response =
          w.call(previewMissing(hr(w), missingBody(slot, ana, body)));
      assertThat(response.getStatus()).as(body.toString()).isEqualTo((int) c[1]);
      if (c[2] != null) {
        JsonNode problem = JSON.readTree(response.getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        JsonNode field = problem.get("params").get("fields").get(0);
        assertThat(field.get("field").asText() + " " + field.get("constraint").asText())
            .isEqualTo(c[2]);
        assertThat(response.getContentAsString())
            .doesNotContain(String.valueOf(body.get("checkedInAt")));
      }
    }
    // The future bound: at most 5 minutes after the server's instant.
    clock.set(at[0].plus(Duration.ofHours(1)));
    Instant server = at[0].plus(Duration.ofHours(1));
    w.expect(
        previewMissing(
            hr(w), missing(slot, ana.employee(), at[0], server.plus(Duration.ofMinutes(5)))),
        200);
    w.problem(
        previewMissing(
            hr(w),
            missing(slot, ana.employee(), at[0], server.plus(Duration.ofMinutes(5)).plus(MICRO))),
        400,
        "VALIDATION_FAILED");
    clock.set(now);
    // Shapes: seven fractional digits, no offset, a lower-case z, an unknown reason.
    Map<String, Object> body = missing(slot, ana.employee(), at[0], at[1]);
    for (String in :
        List.of("2026-11-02T07:00:00.0000001Z", "2026-11-02T07:00:00", "2026-11-02t07:00:00z")) {
      Map<String, Object> malformed = new HashMap<>(body);
      malformed.put("checkedInAt", in);
      field(w, malformed, "checkedInAt", "FORMAT");
    }
    Map<String, Object> reason = new HashMap<>(body);
    reason.put("reasonCode", "PAYROLL");
    field(w, reason, "reasonCode", "FORMAT");
    // The explanation: trimmed, 10 to 500 characters, no control character, markup or link.
    for (Object[] e :
        List.of(
            new Object[] {"court", "LENGTH"},
            new Object[] {"   neuf car  ", "LENGTH"},
            new Object[] {"x".repeat(501), "LENGTH"},
            new Object[] {"Voir <b>ici</b> svp", "FORMAT"},
            new Object[] {"Voir https://exemple.test", "FORMAT"},
            new Object[] {"Voir www.exemple.test", "FORMAT"},
            new Object[] {"Ligne une\nligne deux", "FORMAT"},
            new Object[] {"Texte avec ‮ inversé", "FORMAT"},
            new Object[] {"", "REQUIRED"})) {
      Map<String, Object> bad = new HashMap<>(body);
      bad.put("explanation", e[0]);
      field(w, bad, "explanation", (String) e[1]);
    }
    Map<String, Object> trimmed = new HashMap<>(body);
    trimmed.put("explanation", "  " + "é".repeat(500) + "  ");
    assertThat(w.expect(previewMissing(hr(w), trimmed), 200).get("explanation").asText())
        .isEqualTo("é".repeat(500));
    Map<String, Object> unknown = new HashMap<>(body);
    unknown.put("tenantId", w.tenant().toString());
    field(w, unknown, "body", "UNKNOWN_PROPERTY");
    // The instant is the instant whatever the offset: the same digest.
    Map<String, Object> utc = new HashMap<>(body);
    utc.put("checkedInAt", at(at[0], 0));
    utc.put("checkedOutAt", at(at[1], 0));
    String admin = hr(w);
    assertThat(w.expect(previewMissing(admin, utc), 200).get("previewDigest"))
        .isEqualTo(w.expect(previewMissing(admin, body), 200).get("previewDigest"));
    // Values identical to the current ones change nothing: not allowed.
    String record = createMissing(w, body).get("record").get("attendanceRecordId").asText();
    w.problem(
        previewRecord(hr(w), record, values(at[0], at[1])),
        409,
        "ATTENDANCE_CORRECTION_NOT_ALLOWED");
  }

  private static Map<String, Object> missingBody(
      String slot, Person person, Map<String, Object> values) {
    Map<String, Object> body = new HashMap<>(values);
    body.put("slotId", slot);
    body.put("employeeId", person.employee().toString());
    return body;
  }

  private void field(ScheduleWorld w, Map<String, Object> body, String name, String constraint)
      throws Exception {
    JsonNode problem = w.problem(previewMissing(hr(w), body), 400, "VALIDATION_FAILED");
    Set<String> fields = new TreeSet<>();
    problem
        .get("params")
        .get("fields")
        .forEach(f -> fields.add(f.get("field").asText() + " " + f.get("constraint").asText()));
    assertThat(fields).as(String.valueOf(body.get(name))).containsExactly(name + " " + constraint);
    assertThat(problem.toString()).doesNotContain(EXPLANATION);
  }

  // ------------------------------------------------------------------------------------------
  // 7, 9: a preview writes nothing; a commit needs the identical fresh preview; replays
  // ------------------------------------------------------------------------------------------

  @Test
  void aPreviewWritesNothingAndACommitNeedsTheIdenticalFreshPreviewAndReplays() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate d = w.today.plusDays(3);
    String slot =
        slotIds(
                w,
                w.published(
                    proposal(w.site(), d, d, List.of(shift("a", d, 8, 16, ana.employee())))))
            .get(0);
    Instant[] at = instants(jdbc, slot);
    clock.set(at[1].plus(Duration.ofHours(1)));
    List<Integer> before = written(w);
    String admin = hr(w);
    Map<String, Object> proposal = missing(slot, ana.employee(), at[0], at[1]);
    JsonNode preview = w.expect(previewMissing(admin, proposal), 200);
    w.expect(previewMissing(admin, proposal), 200);
    // Nothing but the disclosure audit.
    assertThat(written(w)).isEqualTo(before);
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action = 'attendance.correction-preview'",
                w.tenant()))
        .isEqualTo(2);

    // A different digest, version or proposal, or no confirmation: refused, nothing written.
    Map<String, Object> body = commit(proposal, preview);
    Map<String, Object> digest = new HashMap<>(body);
    digest.put("previewDigest", "0".repeat(64));
    w.problem(commitMissing(admin, key(), digest), 409, "ATTENDANCE_CORRECTION_PREVIEW_STALE");
    Map<String, Object> version = new HashMap<>(body);
    version.put("targetVersion", 1);
    w.problem(commitMissing(admin, key(), version), 409, "ATTENDANCE_CORRECTION_PREVIEW_STALE");
    Map<String, Object> changed = new HashMap<>(body);
    changed.put("explanation", EXPLANATION + " (modifiée)");
    w.problem(commitMissing(admin, key(), changed), 409, "ATTENDANCE_CORRECTION_PREVIEW_STALE");
    Map<String, Object> unconfirmed = new HashMap<>(body);
    unconfirmed.remove("confirm");
    w.problem(commitMissing(admin, key(), unconfirmed), 400, "VALIDATION_FAILED");
    w.problem(commitMissing(admin, null, body), 400, "VALIDATION_FAILED");
    // Another administrator's preview is not theirs: stale.
    w.problem(commitMissing(hr(w), key(), body), 409, "ATTENDANCE_CORRECTION_PREVIEW_STALE");
    assertThat(written(w)).isEqualTo(before);

    // The commit; an exact retry replays it; a changed body on the same key is refused.
    String k = key();
    MockHttpServletResponse first = w.call(commitMissing(admin, k, body));
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(first.getHeader("Cache-Control")).isEqualTo("private, no-store");
    MockHttpServletResponse replay = w.call(commitMissing(admin, k, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    w.problem(commitMissing(admin, k, changed), 409, "IDEMPOTENCY_KEY_REUSED");
    assertThat(
            w.count(
                "SELECT count(*) FROM people.attendance_correction WHERE tenant_id = ?",
                w.tenant()))
        .isEqualTo(1);
    // The same reviewed preview on a new key: the target changed (a record now exists).
    w.problem(commitMissing(admin, key(), body), 409, "ATTENDANCE_CORRECTION_PREVIEW_STALE");
    // A record's preview is stale once another correction committed.
    String record =
        JSON.readTree(first.getContentAsString()).get("record").get("attendanceRecordId").asText();
    Map<String, Object> early = values(at[0], at[1].minus(Duration.ofMinutes(20)));
    String admin2 = hr(w);
    JsonNode stalePreview = w.expect(previewRecord(admin2, record, early), 200);
    assertThat(stalePreview.get("targetVersion").asInt()).isEqualTo(1);
    correctRecord(w, record, values(at[0], at[1].minus(Duration.ofMinutes(10))));
    w.problem(
        commitRecord(admin2, key(), record, commit(early, stalePreview)),
        409,
        "ATTENDANCE_CORRECTION_PREVIEW_STALE");
  }

  /** Corrections, records, events and attendance outbox events of the tenant. */
  private List<Integer> written(ScheduleWorld w) {
    return List.of(
        w.count(
            "SELECT count(*) FROM people.attendance_correction WHERE tenant_id = ?", w.tenant()),
        w.count("SELECT count(*) FROM people.attendance_record WHERE tenant_id = ?", w.tenant()),
        w.count("SELECT count(*) FROM people.attendance_event WHERE tenant_id = ?", w.tenant()),
        w.count(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' LIKE 'people.attendance.%'",
            w.tenant().toString()));
  }

  // ------------------------------------------------------------------------------------------
  // 10: exact-MFA tenant administrators only, inside their tenant
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyAnExactMfaTenantAdministratorCorrectsInsideTheTenant() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    w.manageAll(mia, ana);
    LocalDate d = w.today.plusDays(3);
    String slot =
        slotIds(
                w,
                w.published(
                    proposal(w.site(), d, d, List.of(shift("a", d, 8, 16, ana.employee())))))
            .get(0);
    Instant[] at = instants(jdbc, slot);
    clock.set(at[0].plus(Duration.ofMinutes(10)));
    String record = w.expect(checkIn(ana, key(), slot), 201).get("attendanceRecordId").asText();
    clock.set(at[1].plus(Duration.ofHours(1)));
    Map<String, Object> proposal = values(at[0], at[1]);
    // The employee, her manager, a password-only administrator, a platform administrator.
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant())
                .subject("sub-corr-pwd-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    String platform =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant())
                .subject(Bootstraps.platformSubject())
                .roles(List.of("platform-admin"))
                .build();
    for (String bearer : List.of(ana.bearer(), mia.bearer(), passwordOnly, platform)) {
      for (MockHttpServletRequestBuilder call :
          List.of(
              previewRecord(bearer, record, proposal),
              commitRecord(bearer, key(), record, proposal),
              CorrectionCalls.history(bearer, record),
              previewMissing(bearer, missing(slot, ana.employee(), at[0], at[1])))) {
        assertThat(w.call(call).getStatus()).isEqualTo(403);
      }
    }
    w.problem(previewRecord(passwordOnly, record, proposal), 403, "MFA_REQUIRED");
    // A foreign tenant's administrator: the same 404 as an unknown or malformed target.
    ScheduleWorld other = new ScheduleWorld(mvc, jdbc, lookups);
    List<JsonNode> problems = new ArrayList<>();
    for (String target : List.of(record, UUID.randomUUID().toString(), "nope")) {
      problems.add(
          other.problem(
              previewRecord(hr(other), target, proposal),
              404,
              "ATTENDANCE_CORRECTION_TARGET_NOT_FOUND"));
      problems.add(
          other.problem(
              CorrectionCalls.history(hr(other), target),
              404,
              "ATTENDANCE_CORRECTION_TARGET_NOT_FOUND"));
    }
    problems.add(
        other.problem(
            previewMissing(hr(other), missing(slot, ana.employee(), at[0], at[1])),
            404,
            "ATTENDANCE_CORRECTION_TARGET_NOT_FOUND"));
    problems.add(
        other.problem(
            previewMissing(hr(other), missing("nope", ana.employee(), at[0], at[1])),
            404,
            "ATTENDANCE_CORRECTION_TARGET_NOT_FOUND"));
    for (JsonNode problem : problems) {
      assertThat(stable(problem)).isEqualTo(stable(problems.get(0)));
      assertThat(problem.toString()).doesNotContain(record).doesNotContain(slot);
    }
    // Inside the tenant: every response is private, no-store.
    String admin = hr(w);
    MockHttpServletResponse preview = w.call(previewRecord(admin, record, proposal));
    assertThat(preview.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(w.call(CorrectionCalls.history(admin, record)).getHeader("Cache-Control"))
        .isEqualTo("private, no-store");
    assertThat(
            w.count(
                "SELECT count(*) FROM people.attendance_correction WHERE tenant_id = ?",
                w.tenant()))
        .isZero();
  }

  // ------------------------------------------------------------------------------------------
  // 11, 12: substitutes, recorded owners and terminal schedules
  // ------------------------------------------------------------------------------------------

  @Test
  void substitutesRecordedOwnersAndTerminalSchedulesKeepTheirTargets() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, ben);
    LocalDate d = w.today.plusDays(3);
    // A: cancelled before the shift: no record may be created.
    String a = w.published(proposal(w.site(), d, d, List.of(shift("a", d, 8, 16, ana.employee()))));
    String a1 = slotIds(w, a).get(0);
    w.expect(cancel(w.admin, key(), a, "Fermeture"), 200);
    // B: Ana checks in before the start, then B is cancelled: her record stays correctable; Ben's
    // missing record is never created.
    LocalDate b = d.plusDays(1);
    String bs =
        w.published(
            proposal(
                w.site(),
                b,
                b,
                List.of(
                    shift("b1", b, 8, 16, ana.employee()), shift("b2", b, 8, 16, ben.employee()))));
    List<String> bSlots = slotIds(w, bs);
    // D: Ana checks in early, then her approved substitute Ben takes the shift.
    LocalDate e = d.plusDays(3);
    String ds =
        w.published(proposal(w.site(), e, e, List.of(shift("d1", e, 8, 16, ana.employee()))));
    String d1 = slotIds(w, ds).get(0);
    String requestId = w.expect(request(ana, key(), d1), 201).get("id").asText();
    Instant[] bAt = instants(jdbc, bSlots.get(0));
    clock.set(bAt[0].minus(Duration.ofHours(1)));
    String bRecord =
        w.expect(checkIn(ana, key(), bSlots.get(0)), 201).get("attendanceRecordId").asText();
    w.expect(cancel(w.admin, key(), bs, "Fermeture"), 200);
    Instant[] dAt = instants(jdbc, d1);
    clock.set(dAt[0].minus(Duration.ofHours(1)));
    String dRecord = w.expect(checkIn(ana, key(), d1), 201).get("attendanceRecordId").asText();
    w.expect(approve(mia, requestId, key(), ben.employee()), 200);
    clock.set(dAt[1].plus(DAY).plus(MICRO));
    Instant[] aAt = instants(jdbc, a1);

    // The cancelled schedule: nothing created, for anyone; the recorded owner's record stays
    // correctable, with a neutral warning.
    w.problem(
        previewMissing(hr(w), missing(a1, ana.employee(), aAt[0], aAt[1])),
        409,
        "ATTENDANCE_CORRECTION_NOT_ALLOWED");
    w.problem(
        previewMissing(hr(w), missing(bSlots.get(1), ben.employee(), bAt[0], bAt[1])),
        409,
        "ATTENDANCE_CORRECTION_NOT_ALLOWED");
    String admin = hr(w);
    Map<String, Object> bFix = values(bAt[0], bAt[1]);
    JsonNode bPreview = w.expect(previewRecord(admin, bRecord, bFix), 200);
    assertThat(types(bPreview.get("warnings"))).contains("SCHEDULE_NOT_OPERATIONAL");
    assertThat(bPreview.get("employee").get("id").asText()).isEqualTo(ana.employee().toString());
    w.expect(commitRecord(admin, key(), bRecord, commit(bFix, bPreview)), 201);
    // The substituted shift: Ana's record stays hers; the missing record is Ben's (the effective
    // assignee), never Ana's.
    correctRecord(w, dRecord, values(dAt[0].minus(Duration.ofHours(1)), dAt[1]));
    assertThat(
            w.count(
                "SELECT count(*) FROM people.attendance_record WHERE id = ?::uuid"
                    + " AND employee_id = ?",
                dRecord,
                ana.employee()))
        .isEqualTo(1);
    w.problem(
        previewMissing(hr(w), missing(d1, ana.employee(), dAt[0], dAt[1])),
        404,
        "ATTENDANCE_CORRECTION_TARGET_NOT_FOUND");
    JsonNode benRecord = createMissing(w, missing(d1, ben.employee(), dAt[0], dAt[1]));
    assertThat(benRecord.get("record").get("slotId").asText()).isEqualTo(d1);
    assertThat(w.count("SELECT count(*) FROM people.attendance_record WHERE slot_id = ?::uuid", d1))
        .isEqualTo(2);
    assertThat(exceptions(w, d))
        .doesNotContain(row("MISSING_CHECK_IN", d1, ben))
        .doesNotContain(row("MISSING_CHECK_OUT", d1, ana));
  }

  // ------------------------------------------------------------------------------------------
  // 13, 14: audit, outbox and the explanation's privacy
  // ------------------------------------------------------------------------------------------

  @Test
  void theAuditOutboxLogsMetricsAndEmployeeViewsNeverCarryTheExplanation(CapturedOutput output)
      throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate d = w.today.plusDays(3);
    String slot =
        slotIds(
                w,
                w.published(
                    proposal(w.site(), d, d, List.of(shift("a", d, 8, 16, ana.employee())))))
            .get(0);
    Instant[] at = instants(jdbc, slot);
    clock.set(at[1].plus(Duration.ofHours(1)));
    String marker = "Mention privée " + UUID.randomUUID();
    Map<String, Object> proposal = CorrectionCalls.missing(slot, ana.employee(), at[0], null);
    proposal.put("explanation", marker);
    proposal.put("reasonCode", "OTHER");
    String admin = hr(w);
    JsonNode preview = w.expect(previewMissing(admin, proposal), 200);
    w.problem(
        commitMissing(admin, key(), commitStale(proposal, preview)),
        409,
        "ATTENDANCE_CORRECTION_PREVIEW_STALE");
    JsonNode receipt = w.expect(commitMissing(admin, key(), commit(proposal, preview)), 201);
    String record = receipt.get("record").get("attendanceRecordId").asText();
    String correction = receipt.get("correctionId").asText();

    // The audit record: bounded metadata only.
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT metadata::text AS metadata, resource_id::text AS resource, result"
                + " FROM platform.audit_event WHERE tenant_id = ? AND action ="
                + " 'attendance.correction-create'",
            w.tenant());
    JsonNode metadata = JSON.readTree((String) audit.get("metadata"));
    Set<String> keys = new TreeSet<>();
    metadata.fieldNames().forEachRemaining(keys::add);
    assertThat(keys)
        .containsExactly(
            "hadCheckOutBefore", "hasCheckOutAfter", "operation", "reasonCode", "schemaVersion");
    assertThat(metadata.get("operation").asText()).isEqualTo("CREATE_MISSING_RECORD");
    assertThat(audit.get("resource")).isEqualTo(record);
    // The outbox event: stable IDs, operation, reason, presence flags, version and instant.
    String event =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                + " AND envelope ->> 'eventType' = 'people.attendance.corrected.v1'",
            String.class,
            w.tenant().toString());
    JsonNode data = JSON.readTree(event).get("data");
    Set<String> fields = new TreeSet<>();
    data.fieldNames().forEachRemaining(fields::add);
    assertThat(fields)
        .containsExactly(
            "attendanceRecordId",
            "correctedAt",
            "correctionId",
            "correctionVersion",
            "employeeId",
            "employmentId",
            "hadCheckOutBefore",
            "hadRecordBefore",
            "hasCheckOutAfter",
            "operation",
            "reasonCode",
            "scheduleId",
            "slotId");
    assertThat(data.get("correctionId").asText()).isEqualTo(correction);
    // Never the explanation, an instant of the evidence, a name or a label anywhere but the
    // correction row and the administrators' views.
    for (String text : List.of(event, (String) audit.get("metadata"))) {
      assertThat(text)
          .doesNotContain(marker)
          .doesNotContain("Kalala")
          .doesNotContain(at[0].toString());
    }
    assertThat(myRecord(ana, record).toString()).doesNotContain(marker).doesNotContain("OTHER");
    assertThat(myShift(w, ana, d, slot).toString()).doesNotContain(marker);
    assertThat(w.expect(CorrectionCalls.history(admin, record), 200).toString()).contains(marker);
    // Logs: operation, outcome, correction operation and reason code only.
    String logs = output.getAll();
    assertThat(logs)
        .doesNotContain(marker)
        .doesNotContain(record)
        .doesNotContain(correction)
        .doesNotContain(slot)
        .doesNotContain(preview.get("previewDigest").asText())
        .contains("attendance_correction_recorded")
        .contains("\"correctionOperation\":\"CREATE_MISSING_RECORD\"");
    // Metrics: bounded tags only.
    meters
        .find("divalhr.attendance.correction")
        .counters()
        .forEach(
            counter -> {
              Set<String> tags = new TreeSet<>();
              counter.getId().getTags().forEach(tag -> tags.add(tag.getKey()));
              assertThat(tags)
                  .containsExactly("correctionOperation", "operation", "outcome", "reasonCode");
            });
    assertThat(meters.find("divalhr.attendance.correction").tag("outcome", "created").counters())
        .isNotEmpty();
  }

  private static Map<String, Object> commitStale(Map<String, Object> proposal, JsonNode preview) {
    Map<String, Object> body = commit(proposal, preview);
    body.put("previewDigest", "f".repeat(64));
    return body;
  }
}
