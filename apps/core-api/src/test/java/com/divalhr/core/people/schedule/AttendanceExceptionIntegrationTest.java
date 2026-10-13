package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-060C (Issue #119, C60C-8 items 1 to 11 and 17) against real PostgreSQL: every exception type
 * at its exact boundaries; terminal schedules, a pre-shift substitution and recorded history; the
 * employee's, the manager's (rerouted at once) and the tenant administrator's scopes with the site
 * filter; foreign and undisclosable scopes; validation; signed keyset pages with a pinned instant
 * and cursor binding; the fail-closed audit, its metadata and the privacy of logs.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class AttendanceExceptionIntegrationTest {

  static final String MINE = "/api/v1/me/attendance/exceptions";
  static final String TEAM = "/api/v1/me/team-attendance/exceptions";
  static final String ADMIN = "/api/v1/attendance/exceptions";
  private static final Duration TOLERANCE = Duration.ofMinutes(5);
  private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);
  private static final Duration DAY = Duration.ofHours(24);

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @MockitoSpyBean private AuditRecorder audit;

  @AfterEach
  void reset() {
    clock.reset();
    org.mockito.Mockito.reset((AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
  }

  static MockHttpServletRequestBuilder list(String bearer, String path, String query) {
    return get(path + (query.isEmpty() ? "" : "?" + query)).header("Authorization", bearer);
  }

  static String range(LocalDate from, LocalDate to) {
    return "from=" + from + "&to=" + to;
  }

  private JsonNode page(ScheduleWorld w, String bearer, String path, String query)
      throws Exception {
    MockHttpServletResponse response = w.call(list(bearer, path, query));
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  /** "TYPE slot employee" per item, in page order (employee from the item, or the caller). */
  private static List<String> rows(JsonNode page, UUID self) {
    List<String> rows = new ArrayList<>();
    page.get("items")
        .forEach(
            item ->
                rows.add(
                    item.get("type").asText()
                        + " "
                        + item.get("slotId").asText()
                        + " "
                        + (item.has("employee")
                            ? item.get("employee").get("id").asText()
                            : String.valueOf(self))));
    return rows;
  }

  private static String row(String type, String slot, Person person) {
    return type + " " + slot + " " + person.employee();
  }

  private static JsonNode stable(JsonNode problem) {
    ObjectNode copy = (ObjectNode) problem.deepCopy();
    copy.remove("correlationId");
    copy.remove("instance");
    return copy;
  }

  // ------------------------------------------------------------------------------------------
  // 1: every type at its exact boundaries
  // ------------------------------------------------------------------------------------------

  @Test
  void everyTypeAppearsExactlyAtItsBoundary() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    w.manageAll(mia, ana);
    LocalDate d = w.today.plusDays(3);
    String schedule =
        w.published(
            proposal(
                w.site(),
                d,
                d.plusDays(3),
                List.of(
                    shift("s0", d, 8, 16, ana.employee()),
                    shift("s1", d.plusDays(1), 8, 16, ana.employee()),
                    shift("s2", d.plusDays(2), 8, 16, ana.employee()),
                    shift("s3", d.plusDays(3), 8, 16, ana.employee()))));
    List<String> slots = slotIds(w, schedule);
    Instant[] s0 = instants(jdbc, slots.get(0));
    Instant[] s1 = instants(jdbc, slots.get(1));
    Instant[] s2 = instants(jdbc, slots.get(2));
    Instant[] s3 = instants(jdbc, slots.get(3));
    String dates = range(d, d.plusDays(6));

    // s0: in exactly 5 minutes late and out exactly 5 minutes early: no exception.
    clock.set(s0[0].plus(TOLERANCE));
    String r0 =
        w.expect(checkIn(ana, Organizations.newKey(), slots.get(0)), 201)
            .get("attendanceRecordId")
            .asText();
    clock.set(s0[1].minus(TOLERANCE));
    w.expect(checkOut(ana, Organizations.newKey(), r0), 200);
    // s1: one microsecond beyond each tolerance: late and early, rounded up to 6 minutes.
    clock.set(s1[0].plus(TOLERANCE).plus(MICRO));
    String r1 =
        w.expect(checkIn(ana, Organizations.newKey(), slots.get(1)), 201)
            .get("attendanceRecordId")
            .asText();
    clock.set(s1[1].minus(TOLERANCE).minus(MICRO));
    w.expect(checkOut(ana, Organizations.newKey(), r1), 200);

    // Before s2's end: s1's two facts only, in type order.
    clock.set(s2[1].minus(MICRO));
    JsonNode before = page(w, ana.bearer(), MINE, dates);
    assertThat(rows(before, ana.employee()))
        .containsExactly(
            row("LATE_CHECK_IN", slots.get(1), ana), row("EARLY_CHECK_OUT", slots.get(1), ana));
    JsonNode late = before.get("items").get(0);
    assertThat(late.get("varianceMinutes").asInt()).isEqualTo(6);
    assertThat(late.get("attendanceRecordId").asText()).isEqualTo(r1);
    assertThat(Instant.parse(late.get("checkedInAt").asText()))
        .isEqualTo(s1[0].plus(TOLERANCE).plus(MICRO));
    JsonNode early = before.get("items").get(1);
    assertThat(early.get("varianceMinutes").asInt()).isEqualTo(6);
    assertThat(Instant.parse(early.get("checkedOutAt").asText()))
        .isEqualTo(s1[1].minus(TOLERANCE).minus(MICRO));
    assertThat(early.get("labels").get("fr").asText()).isEqualTo("Caissière");
    assertThat(Instant.parse(before.get("asOfInstant").asText())).isEqualTo(s2[1].minus(MICRO));

    // At s2's end, its missing check-in begins (no record, no variance).
    clock.set(s2[1]);
    JsonNode missing = page(w, ana.bearer(), MINE, dates);
    assertThat(rows(missing, ana.employee()))
        .containsExactly(
            row("MISSING_CHECK_IN", slots.get(2), ana),
            row("LATE_CHECK_IN", slots.get(1), ana),
            row("EARLY_CHECK_OUT", slots.get(1), ana));
    JsonNode none = missing.get("items").get(0);
    assertThat(none.get("attendanceRecordId").isNull()).isTrue();
    assertThat(none.get("checkedInAt").isNull()).isTrue();
    assertThat(none.get("varianceMinutes").isNull()).isTrue();

    // s3: checked in, never out: missing only strictly after the inclusive deadline.
    clock.set(s3[0].minus(Duration.ofHours(1)));
    w.expect(checkIn(ana, Organizations.newKey(), slots.get(3)), 201);
    clock.set(s3[1].plus(DAY));
    assertThat(rows(page(w, ana.bearer(), MINE, dates), ana.employee()))
        .doesNotContain(row("MISSING_CHECK_OUT", slots.get(3), ana));
    clock.set(s3[1].plus(DAY).plus(MICRO));
    JsonNode all = page(w, ana.bearer(), MINE, dates);
    assertThat(rows(all, ana.employee()))
        .containsExactly(
            row("MISSING_CHECK_OUT", slots.get(3), ana),
            row("MISSING_CHECK_IN", slots.get(2), ana),
            row("LATE_CHECK_IN", slots.get(1), ana),
            row("EARLY_CHECK_OUT", slots.get(1), ana));
    assertThat(all.get("items").get(0).get("checkedOutAt").isNull()).isTrue();
    // Without dates: the last 31 site-local dates through the as-of date, the same rows.
    assertThat(rows(page(w, ana.bearer(), MINE, ""), ana.employee()))
        .isEqualTo(rows(all, ana.employee()));
    // Each type alone.
    assertThat(rows(page(w, ana.bearer(), MINE, dates + "&type=EARLY_CHECK_OUT"), ana.employee()))
        .containsExactly(row("EARLY_CHECK_OUT", slots.get(1), ana));
    assertThat(rows(page(w, ana.bearer(), MINE, dates + "&type=MISSING_CHECK_IN"), ana.employee()))
        .containsExactly(row("MISSING_CHECK_IN", slots.get(2), ana));
    // The manager sees the same facts with Ana's minimal identity.
    JsonNode team = page(w, mia.bearer(), TEAM, dates);
    assertThat(rows(team, null)).isEqualTo(rows(all, ana.employee()));
    assertThat(team.get("items").get(0).get("employee").get("employeeNumber").asText())
        .isEqualTo(ana.number());
  }

  // ------------------------------------------------------------------------------------------
  // 2, 3, 4: terminal schedules, a pre-shift substitute and recorded history
  // ------------------------------------------------------------------------------------------

  @Test
  void terminalSchedulesSubstitutesAndRecordedHistoryKeepTheirTargets() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, ben);
    LocalDate d = w.today.plusDays(3);
    // A: cancelled before the shift: no missing check-in.
    String a = w.published(proposal(w.site(), d, d, List.of(shift("a", d, 8, 16, ana.employee()))));
    String a1 = slotIds(w, a).get(0);
    w.expect(cancel(w.admin, Organizations.newKey(), a, "Fermeture"), 200);
    // B: Ana checks in before the start, then B is cancelled (only possible before the start): her
    // record stays reviewable; Ben's slot creates nothing.
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
    // C: Ben's shift replaced by one of Ana's before it starts.
    LocalDate c = d.plusDays(2);
    String cs =
        w.published(proposal(w.site(), c, c, List.of(shift("c1", c, 8, 16, ben.employee()))));
    String c1 = slotIds(w, cs).get(0);
    String successor =
        w.replaced(cs, List.of(shift("c2", c, 10, 18, ana.employee())), "Réorganisation");
    String c2 = slotIds(w, successor).get(0);
    // D: Ana checks in early, then her approved substitute Ben takes the shift.
    LocalDate e = d.plusDays(3);
    String ds =
        w.published(proposal(w.site(), e, e, List.of(shift("d1", e, 8, 16, ana.employee()))));
    String d1 = slotIds(w, ds).get(0);
    String requestId = w.expect(request(ana, Organizations.newKey(), d1), 201).get("id").asText();

    Instant[] bAt = instants(jdbc, bSlots.get(0));
    clock.set(bAt[0].minus(Duration.ofHours(1)));
    w.expect(checkIn(ana, Organizations.newKey(), bSlots.get(0)), 201);
    w.expect(cancel(w.admin, Organizations.newKey(), bs, "Fermeture"), 200);
    Instant[] dAt = instants(jdbc, d1);
    clock.set(dAt[0].minus(Duration.ofHours(1)));
    w.expect(checkIn(ana, Organizations.newKey(), d1), 201);
    w.expect(approve(mia, requestId, Organizations.newKey(), ben.employee()), 200);

    clock.set(dAt[1].plus(DAY).plus(MICRO));
    String dates = range(d, d.plusDays(6));
    Set<String> expected =
        Set.of(
            row("MISSING_CHECK_IN", d1, ben),
            row("MISSING_CHECK_OUT", d1, ana),
            row("MISSING_CHECK_IN", c2, ana),
            row("MISSING_CHECK_OUT", bSlots.get(0), ana));
    List<String> review = rows(page(w, w.admin, ADMIN, dates), null);
    assertThat(review).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(String.join("\n", review))
        .doesNotContain(a1)
        .doesNotContain(bSlots.get(1))
        .doesNotContain(c1);
    assertThat(rows(page(w, mia.bearer(), TEAM, dates), null))
        .containsExactlyInAnyOrderElementsOf(expected);
    assertThat(rows(page(w, ben.bearer(), MINE, dates), ben.employee()))
        .containsExactly(row("MISSING_CHECK_IN", d1, ben));
    assertThat(rows(page(w, ana.bearer(), MINE, dates), ana.employee()))
        .containsExactlyInAnyOrder(
            row("MISSING_CHECK_OUT", d1, ana),
            row("MISSING_CHECK_IN", c2, ana),
            row("MISSING_CHECK_OUT", bSlots.get(0), ana));
    // Same slot: employee descending (PostgreSQL's unsigned byte order of uuid, which is the order
    // of the lowercase text form, not java.util.UUID's signed order), then type.
    List<String> onD1 = review.stream().filter(r -> r.contains(d1)).toList();
    String first =
        String.valueOf(ben.employee()).compareTo(String.valueOf(ana.employee())) > 0
            ? "MISSING_CHECK_IN"
            : "MISSING_CHECK_OUT";
    assertThat(onD1.get(0)).startsWith(first);
  }

  // ------------------------------------------------------------------------------------------
  // 5, 6, 7, 8: scopes, rerouting, the site filter and undisclosable scopes
  // ------------------------------------------------------------------------------------------

  @Test
  void eachViewSeesOnlyItsScopeAndAManagerChangeReroutesAtOnce() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    Person zoe = w.person("Zoé", "Tshala");
    Person noa = w.person("Noa", "Banza");
    w.manageAll(mia, ana, ben);
    LocalDate d = w.today.plusDays(3);
    String schedule =
        w.published(
            proposal(
                w.site(),
                d,
                d,
                List.of(
                    shift("a", d, 10, 18, ana.employee()), shift("b", d, 8, 16, ben.employee()))));
    List<String> slots = slotIds(w, schedule);
    Instant[] a = instants(jdbc, slots.get(0));
    String dates = range(d, d.plusDays(1));
    clock.set(a[1].plus(Duration.ofMinutes(1)));
    String anaRow = row("MISSING_CHECK_IN", slots.get(0), ana);
    String benRow = row("MISSING_CHECK_IN", slots.get(1), ben);

    // The employee: only her own. A colleague, Ana herself as "manager": nothing.
    assertThat(rows(page(w, ana.bearer(), MINE, dates), ana.employee())).containsExactly(anaRow);
    assertThat(rows(page(w, mia.bearer(), MINE, dates), mia.employee())).isEmpty();
    assertThat(rows(page(w, mia.bearer(), TEAM, dates), null)).containsExactly(anaRow, benRow);
    assertThat(page(w, zoe.bearer(), TEAM, dates).get("items")).isEmpty();
    assertThat(page(w, ana.bearer(), TEAM, dates).get("items")).isEmpty();
    // A tenant administrator: the tenant, and the site filter; a foreign tenant sees nothing.
    assertThat(rows(page(w, w.admin, ADMIN, dates), null)).containsExactly(anaRow, benRow);
    assertThat(rows(page(w, w.admin, ADMIN, dates + "&siteId=" + w.site()), null))
        .containsExactly(anaRow, benRow);
    assertThat(page(w, w.admin, ADMIN, dates + "&siteId=" + w.otherSite).get("items")).isEmpty();
    ScheduleWorld other = new ScheduleWorld(mvc, jdbc, lookups);
    assertThat(page(other, other.admin, ADMIN, dates).get("items")).isEmpty();
    // Unknown, foreign, malformed and non-site scopes: one indistinguishable 404, no detail.
    List<JsonNode> scopes = new ArrayList<>();
    for (String site :
        List.of(
            UUID.randomUUID().toString(),
            other.site().toString(),
            "nope",
            w.org.legalEntityId().toString())) {
      scopes.add(
          w.problem(
              list(w.admin, ADMIN, dates + "&siteId=" + site),
              404,
              "ATTENDANCE_EXCEPTION_SCOPE_NOT_FOUND"));
    }
    for (JsonNode scope : scopes) {
      assertThat(stable(scope)).isEqualTo(stable(scopes.get(0)));
      assertThat(scope.toString()).doesNotContain(other.site().toString());
    }
    // An employee role cannot open the review; an administrator without a link has no own view.
    assertThat(w.call(list(ana.bearer(), ADMIN, dates)).getStatus()).isEqualTo(403);
    w.problem(list(w.admin, MINE, dates), 403, "ACCESS_DENIED");

    // Mia's first page (Ana's later shift first) leaves a cursor on Ana's row.
    JsonNode first = page(w, mia.bearer(), TEAM, dates + "&limit=1");
    assertThat(rows(first, null)).containsExactly(anaRow);
    String cursor = first.get("nextCursor").asText();

    // A leave approval substitute of Mia gains nothing.
    w.expect(
        Employees.postJson(
                w.admin,
                "/api/v1/leave-approval-substitutions",
                JSON.writeValueAsString(
                    Map.of(
                        "managerEmployeeId", mia.employee().toString(),
                        "substituteEmployeeId", noa.employee().toString(),
                        "effectiveFrom", w.today.toString(),
                        "effectiveTo", d.plusDays(5).toString(),
                        "reasonLocale", "fr",
                        "reason", "Absence prévue de la responsable")))
            .header("Idempotency-Key", Organizations.newKey()),
        201);
    assertThat(page(w, noa.bearer(), TEAM, dates).get("items")).isEmpty();

    // Ana now reports to Noa from before the shift: at once Mia loses her, the cursor too.
    clock.reset();
    w.manage(ana.employee(), noa.employee(), d.minusDays(1));
    clock.set(a[1].plus(Duration.ofMinutes(1)));
    assertThat(rows(page(w, mia.bearer(), TEAM, dates), null)).containsExactly(benRow);
    assertThat(rows(page(w, noa.bearer(), TEAM, dates), null)).containsExactly(anaRow);
    w.problem(list(mia.bearer(), TEAM, dates + "&limit=1&cursor=" + cursor), 400, "CURSOR_INVALID");
  }

  // ------------------------------------------------------------------------------------------
  // 9, 10: validation, ordering, keyset pages, the pinned instant and cursor binding
  // ------------------------------------------------------------------------------------------

  @Test
  void pagesAreSignedKeysetsAtOnePinnedInstantAndInputsAreValidated() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    LocalDate d = w.today.plusDays(3);
    String schedule =
        w.published(
            proposal(
                w.site(),
                d,
                d.plusDays(3),
                List.of(
                    shift("0", d, 8, 16, ana.employee()),
                    shift("1", d.plusDays(1), 8, 16, ana.employee()),
                    shift("2", d.plusDays(2), 8, 16, ana.employee()),
                    shift("3", d.plusDays(3), 8, 16, ana.employee()))));
    List<String> slots = slotIds(w, schedule);
    Instant third = instants(jdbc, slots.get(2))[1];
    Instant fourth = instants(jdbc, slots.get(3))[1];
    String dates = range(d, d.plusDays(6));
    clock.set(third);
    JsonNode first = page(w, ana.bearer(), MINE, dates + "&limit=2");
    assertThat(rows(first, ana.employee()))
        .containsExactly(
            row("MISSING_CHECK_IN", slots.get(2), ana), row("MISSING_CHECK_IN", slots.get(1), ana));
    String cursor = first.get("nextCursor").asText();
    // The fourth shift ends before the next page: the pinned instant keeps it out.
    clock.set(fourth.plus(DAY));
    JsonNode second = page(w, ana.bearer(), MINE, dates + "&limit=2&cursor=" + cursor);
    assertThat(rows(second, ana.employee()))
        .containsExactly(row("MISSING_CHECK_IN", slots.get(0), ana));
    assertThat(second.get("nextCursor").isNull()).isTrue();
    assertThat(second.get("asOfInstant")).isEqualTo(first.get("asOfInstant"));
    assertThat(rows(page(w, ana.bearer(), MINE, dates + "&limit=2"), ana.employee()))
        .startsWith(row("MISSING_CHECK_IN", slots.get(3), ana));
    // The cursor is bound to its caller, filters and size.
    for (String reuse :
        List.of(
            dates + "&limit=3&cursor=" + cursor,
            dates + "&limit=2&type=MISSING_CHECK_IN&cursor=" + cursor,
            range(d, d.plusDays(5)) + "&limit=2&cursor=" + cursor,
            dates + "&limit=2&cursor=" + cursor + "x")) {
      w.problem(list(ana.bearer(), MINE, reuse), 400, "CURSOR_INVALID");
    }
    w.problem(list(ben.bearer(), MINE, dates + "&limit=2&cursor=" + cursor), 400, "CURSOR_INVALID");
    w.problem(list(w.admin, ADMIN, dates + "&limit=2&cursor=" + cursor), 400, "CURSOR_INVALID");

    // Validation names fields, never values; the 93-date bound is inclusive.
    for (String bad :
        List.of(
            "type=LATE",
            "from=" + d,
            "to=" + d,
            range(d, d.minusDays(1)),
            range(d, d.plusDays(93)),
            "from=2026-02-30&to=2026-03-01",
            "limit=0",
            "limit=101")) {
      JsonNode problem = w.problem(list(ana.bearer(), MINE, bad), 400, "VALIDATION_FAILED");
      assertThat(problem.toString()).doesNotContain(d.toString()).doesNotContain("LATE\"");
    }
    page(w, ana.bearer(), MINE, range(d, d.plusDays(92)));
    page(w, w.admin, ADMIN, range(d, d.plusDays(92)) + "&limit=100");
  }

  // ------------------------------------------------------------------------------------------
  // 11, 17: the fail-closed audit, its metadata and privacy
  // ------------------------------------------------------------------------------------------

  @Test
  void disclosuresAreAuditedMinimallyFailClosedAndNothingPersonalIsLogged(CapturedOutput output)
      throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    w.manageAll(mia, ana);
    LocalDate d = w.today.plusDays(3);
    String schedule =
        w.published(proposal(w.site(), d, d, List.of(shift("a", d, 8, 16, ana.employee()))));
    String slot = slotIds(w, schedule).get(0);
    clock.set(instants(jdbc, slot)[1]);
    String dates = range(d, d.plusDays(1));
    page(w, ana.bearer(), MINE, dates + "&type=MISSING_CHECK_IN");
    page(w, mia.bearer(), TEAM, dates);
    JsonNode admin = page(w, w.admin, ADMIN, dates + "&siteId=" + w.site() + "&limit=1");
    assertThat(admin.get("items")).hasSize(1);

    List<Map<String, Object>> audits =
        jdbc.queryForList(
            "SELECT action, metadata::text AS metadata, after_state_sha256 FROM"
                + " platform.audit_event WHERE tenant_id = ? AND action LIKE"
                + " 'attendance.exception-%' ORDER BY occurred_at",
            w.tenant());
    assertThat(audits)
        .extracting(m -> m.get("action"))
        .containsExactly(
            "attendance.exception-self-list",
            "attendance.exception-team-list",
            "attendance.exception-admin-list");
    for (Map<String, Object> audit : audits) {
      JsonNode metadata = JSON.readTree((String) audit.get("metadata"));
      Set<String> keys = new HashSet<>();
      metadata.fieldNames().forEachRemaining(keys::add);
      assertThat(keys)
          .isSubsetOf("schemaVersion", "view", "page", "resultCount", "type", "siteFilter")
          .contains("schemaVersion", "view", "page", "resultCount", "siteFilter");
      assertThat((String) audit.get("after_state_sha256")).matches("[0-9a-f]{64}");
    }
    assertThat(JSON.readTree((String) audits.get(0).get("metadata")).get("type").asText())
        .isEqualTo("MISSING_CHECK_IN");
    assertThat(JSON.readTree((String) audits.get(2).get("metadata")).get("siteFilter").asBoolean())
        .isTrue();

    String logs =
        String.join(
            "\n",
            output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp")).toList());
    String published =
        logs
            + audits.stream().map(a -> String.valueOf(a.get("metadata"))).toList()
            + jdbc.queryForList(
                "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?"
                    + " AND event_type LIKE '%exception%'",
                String.class, w.tenant().toString());
    for (String secret :
        List.of(
            "Kalala",
            "Mbuyi",
            ana.number(),
            mia.number(),
            ana.subject(),
            mia.subject(),
            "Caissière",
            "Cashier",
            d.toString(),
            w.site().toString(),
            slot,
            "MISSING_CHECK_IN\"}")) {
      assertThat(published).as("no %s", secret).doesNotContain(secret);
    }
    assertThat(logs).contains("attendance_exceptions_listed");

    // An audit failure fails the disclosure: no page leaves the server.
    AuditRecorder target = AopTestUtils.getUltimateTargetObject(audit);
    doThrow(new IllegalStateException("simulated audit failure"))
        .when(target)
        .record(
            argThat(
                (AuditEvent event) ->
                    event != null && "attendance.exception-team-list".equals(event.action())));
    try {
      MockHttpServletResponse failed = w.call(list(mia.bearer(), TEAM, dates));
      assertThat(failed.getStatus()).isEqualTo(500);
      assertThat(failed.getContentAsString()).doesNotContain(slot).doesNotContain("Kalala");
    } finally {
      org.mockito.Mockito.reset(target);
      doCallRealMethod().when(target).record(any(AuditEvent.class));
    }
  }
}
