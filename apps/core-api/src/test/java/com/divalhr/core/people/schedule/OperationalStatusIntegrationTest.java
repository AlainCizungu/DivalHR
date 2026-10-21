package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.ScheduleWorld.slot;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.support.Hierarchy;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * MVP-100 (Issue #154, ADR 0026; A100-1 to A100-5) against real PostgreSQL: the exclusive partition
 * of shift occurrences at its exact 5- and 30-minute boundaries, the late subset, uncovered slots,
 * substitution and terminal-schedule semantics, the manager's and the administrator's scopes, the
 * site and legal-entity filters, undisclosable filters, authorization, tenant isolation, the
 * bounded and explicitly truncated details, the fail-closed audit and the privacy of logs.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class OperationalStatusIntegrationTest {

  static final String ADMIN = "/api/v1/operations/status";
  static final String TEAM = "/api/v1/me/team-operations/status";
  private static final Duration TOLERANCE = Duration.ofMinutes(5);
  private static final Duration GRACE = Duration.ofMinutes(30);
  private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);

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

  private static MockHttpServletRequestBuilder read(String bearer, String path, String query) {
    return get(path + (query.isEmpty() ? "" : "?" + query)).header("Authorization", bearer);
  }

  private JsonNode status(ScheduleWorld w, String bearer, String path, String query)
      throws Exception {
    MockHttpServletResponse response = w.call(read(bearer, path, query));
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  /** scheduled, expected, notCheckedIn, present, completed, late. */
  private static List<Integer> shifts(JsonNode status) {
    JsonNode s = status.get("shifts");
    return List.of(
        s.get("scheduled").asInt(),
        s.get("expected").asInt(),
        s.get("notCheckedIn").asInt(),
        s.get("present").asInt(),
        s.get("completed").asInt(),
        s.get("late").asInt());
  }

  /** A100-1: the four states are an exclusive partition of the scheduled occurrences. */
  private static void assertPartition(JsonNode status) {
    List<Integer> s = shifts(status);
    assertThat(s.get(1) + s.get(2) + s.get(3) + s.get(4)).isEqualTo(s.get(0));
  }

  private static List<String> employees(JsonNode list) {
    List<String> ids = new ArrayList<>();
    list.get("items").forEach(item -> ids.add(item.get("employee").get("id").asText()));
    return ids;
  }

  private static JsonNode stable(JsonNode problem) {
    ObjectNode copy = (ObjectNode) problem.deepCopy();
    copy.remove("correlationId");
    copy.remove("instance");
    return copy;
  }

  /** A day of seven shifts: six assigned and one uncovered, all 08:00 to 16:00 at the site. */
  private record Day(
      ScheduleWorld w,
      Person mia,
      Person ana,
      Person ben,
      Person cy,
      Person dee,
      Person eli,
      LocalDate day,
      List<String> slots,
      Instant start,
      Instant end) {}

  private Day day() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    Person cy = w.person("Cy", "Tshibanda");
    Person dee = w.person("Dee", "Mukendi");
    Person eli = w.person("Eli", "Kasongo");
    w.manageAll(mia, ana, ben, cy);
    LocalDate d = w.today.plusDays(3);
    String schedule =
        w.published(
            proposal(
                w.site(),
                d,
                d,
                List.of(
                    shift("a", d, 8, 16, ana.employee()),
                    shift("b", d, 8, 16, ben.employee()),
                    shift("c", d, 8, 16, cy.employee()),
                    shift("x", d, 8, 16, dee.employee()),
                    shift("y", d, 8, 16, eli.employee()),
                    shift("m", d, 8, 16, mia.employee()),
                    shift("u", d, 8, 16, null))));
    List<String> slots = slotIds(w, schedule);
    Instant[] at = instants(jdbc, slots.get(0));
    return new Day(w, mia, ana, ben, cy, dee, eli, d, slots, at[0], at[1]);
  }

  // ------------------------------------------------------------------------------------------
  // A100-1, B: the exclusive partition at the exact boundaries
  // ------------------------------------------------------------------------------------------

  @Test
  void theFourStatesPartitionTheScheduledOccurrencesAtTheirExactBoundaries() throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();

    // Before the start: six assigned occurrences, all expected; one uncovered slot apart.
    clock.set(t.start().minus(Duration.ofHours(1)));
    JsonNode before = status(w, w.admin, ADMIN, "");
    assertThat(shifts(before)).containsExactly(6, 6, 0, 0, 0, 0);
    assertThat(before.get("coverage").get("uncovered").asInt()).isEqualTo(1);
    assertPartition(before);
    assertThat(Instant.parse(before.get("asOfInstant").asText()))
        .isEqualTo(t.start().minus(Duration.ofHours(1)));
    JsonNode rules = before.get("thresholds");
    assertThat(rules.get("lateMinutes").asInt()).isEqualTo(5);
    assertThat(rules.get("notCheckedInGraceMinutes").asInt()).isEqualTo(30);
    assertThat(rules.get("leaveOverdueHours").asInt()).isEqualTo(48);
    assertThat(rules.get("timesheetOverdueHours").asInt()).isEqualTo(72);
    assertThat(rules.get("detailLimit").asInt()).isEqualTo(20);

    // Exactly 5 minutes after the start is not late; one microsecond more is.
    clock.set(t.start().plus(TOLERANCE));
    String ana =
        w.expect(checkIn(t.ana(), Organizations.newKey(), t.slots().get(0)), 201)
            .get("attendanceRecordId")
            .asText();
    clock.set(t.start().plus(TOLERANCE).plus(MICRO));
    String ben =
        w.expect(checkIn(t.ben(), Organizations.newKey(), t.slots().get(1)), 201)
            .get("attendanceRecordId")
            .asText();
    JsonNode checkedIn = status(w, w.admin, ADMIN, "");
    assertThat(shifts(checkedIn)).containsExactly(6, 4, 0, 2, 0, 1);
    assertPartition(checkedIn);

    // Exactly 30 minutes after the start: still expected; no "not checked in" yet.
    clock.set(t.start().plus(GRACE));
    JsonNode exactly = status(w, w.admin, ADMIN, "");
    assertThat(shifts(exactly)).containsExactly(6, 4, 0, 2, 0, 1);
    assertThat(exactly.get("details").get("notCheckedIn").get("items")).isEmpty();
    assertThat(exactly.get("details").get("notCheckedIn").get("truncated").asBoolean()).isFalse();

    // One microsecond later: the four occurrences with no check-in are "not checked in".
    clock.set(t.start().plus(GRACE).plus(MICRO));
    JsonNode after = status(w, w.admin, ADMIN, "");
    assertThat(shifts(after)).containsExactly(6, 0, 4, 2, 0, 1);
    assertPartition(after);
    JsonNode details = after.get("details").get("notCheckedIn");
    assertThat(employees(details))
        .containsExactlyInAnyOrder(
            t.cy().employee().toString(),
            t.dee().employee().toString(),
            t.eli().employee().toString(),
            t.mia().employee().toString());
    assertThat(details.get("items").get(0).get("minutesSinceStart").asLong()).isEqualTo(30);
    assertThat(details.get("items").get(0).get("shift").get("labels").get("fr").asText())
        .isEqualTo("Caissière");

    // Checked out by the instant: completed (late stays a subset of present and completed).
    clock.set(t.start().plus(Duration.ofHours(2)));
    w.expect(checkOut(t.ana(), Organizations.newKey(), ana), 200);
    JsonNode out = status(w, w.admin, ADMIN, "");
    assertThat(shifts(out)).containsExactly(6, 0, 4, 1, 1, 1);
    assertPartition(out);
    w.expect(checkOut(t.ben(), Organizations.newKey(), ben), 200);
    JsonNode lateAndOut = status(w, w.admin, ADMIN, "");
    assertThat(shifts(lateAndOut)).containsExactly(6, 0, 4, 0, 2, 1);
    assertPartition(lateAndOut);
  }

  // ------------------------------------------------------------------------------------------
  // A100-2: effective assignee and terminal schedules
  // ------------------------------------------------------------------------------------------

  @Test
  void anApprovedSubstituteIsTheEffectiveAssigneeAndTerminalSchedulesAreExcluded()
      throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    Person cy = w.person("Cy", "Tshibanda");
    Person zoe = w.person("Zoe", "Lunda");
    w.manageAll(mia, ana, ben, cy, zoe);
    LocalDate d = w.today.plusDays(3);
    // Zoe works at the other site from the day before; her schedule is cancelled before it starts.
    w.expect(
        w.change(zoe.employee(), w.placement(w.otherSite, w.otherSiteDepartment, d.minusDays(1))),
        201);
    String live =
        w.published(
            proposal(
                w.site(),
                d,
                d,
                List.of(
                    shift("a", d, 8, 16, ana.employee()), shift("c", d, 8, 16, cy.employee()))));
    String doomed =
        w.published(proposal(w.otherSite, d, d, List.of(shift("z", d, 8, 16, zoe.employee()))));
    List<String> slots = slotIds(w, live);
    Instant[] at = instants(jdbc, slots.get(0));

    // Ana asks for a substitute; Mia approves Ben before the shift.
    String substitution =
        w.expect(request(ana, Organizations.newKey(), slots.get(0)), 201).get("id").asText();
    w.expect(approve(mia, substitution, Organizations.newKey(), ben.employee()), 200);
    w.expect(cancel(w.admin, Organizations.newKey(), doomed, "Fermeture"), 200);

    clock.set(at[0].plus(GRACE).plus(MICRO));
    JsonNode now = status(w, w.admin, ADMIN, "");
    assertThat(shifts(now)).containsExactly(2, 0, 2, 0, 0, 0);
    assertThat(employees(now.get("details").get("notCheckedIn")))
        .containsExactlyInAnyOrder(ben.employee().toString(), cy.employee().toString())
        .doesNotContain(ana.employee().toString())
        .doesNotContain(zoe.employee().toString());
    // The cancelled schedule's occurrence counts for nothing, nor its uncovered notion.
    assertThat(shifts(status(w, w.admin, ADMIN, "siteId=" + w.otherSite)))
        .containsExactly(0, 0, 0, 0, 0, 0);
    // The manager sees the effective assignees: Ben (the substitute, who reports to Mia) and Cy.
    assertThat(shifts(status(w, mia.bearer(), TEAM, ""))).containsExactly(2, 0, 2, 0, 0, 0);
  }

  // ------------------------------------------------------------------------------------------
  // Scopes: the manager, the site and the legal entity
  // ------------------------------------------------------------------------------------------

  @Test
  void theManagerSeesOnlyHerReportsAndTheAdministratorTheOrganizationWithItsFilters()
      throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    clock.set(t.start().plus(GRACE).plus(MICRO));

    JsonNode org = status(w, w.admin, ADMIN, "");
    assertThat(shifts(org)).containsExactly(6, 0, 6, 0, 0, 0);
    // Mia's reports are Ana, Ben and Cy: never Mia herself, Dee or Eli; no uncovered notion.
    JsonNode team = status(w, t.mia().bearer(), TEAM, "");
    assertThat(shifts(team)).containsExactly(3, 0, 3, 0, 0, 0);
    assertThat(team.get("coverage").isNull()).isTrue();
    assertThat(team.get("details").get("uncovered").isNull()).isTrue();
    assertThat(employees(team.get("details").get("notCheckedIn")))
        .containsExactlyInAnyOrder(
            t.ana().employee().toString(),
            t.ben().employee().toString(),
            t.cy().employee().toString());
    assertThat(team.get("details").get("overdueLeave").get("items")).isEmpty();
    // The administrator's response never carries the manager-only lists.
    assertThat(org.get("details").get("overdueLeave").isNull()).isTrue();
    assertThat(org.get("details").get("overdueTimesheets").isNull()).isTrue();
    assertThat(org.get("details").get("uncovered").get("items")).hasSize(1);

    // A manager who manages nobody gets zeros, not an error; her own shift is never counted.
    JsonNode nobody = status(w, t.dee().bearer(), TEAM, "");
    assertThat(shifts(nobody)).containsExactly(0, 0, 0, 0, 0, 0);

    // The site filter: the shift's site equals the unfiltered view; another site is empty.
    assertThat(shifts(status(w, w.admin, ADMIN, "siteId=" + w.site()))).isEqualTo(shifts(org));
    JsonNode otherSite = status(w, w.admin, ADMIN, "siteId=" + w.otherSite);
    assertThat(shifts(otherSite)).containsExactly(0, 0, 0, 0, 0, 0);
    assertThat(otherSite.get("coverage").get("uncovered").asInt()).isZero();
    // The legal-entity filter uses the effective placement of the assignee; the uncovered slot
    // follows the legal entity of its site.
    String entity = "legalEntityId=" + w.org.legalEntityId();
    JsonNode byEntity = status(w, w.admin, ADMIN, entity);
    assertThat(shifts(byEntity)).isEqualTo(shifts(org));
    assertThat(byEntity.get("coverage").get("uncovered").asInt()).isEqualTo(1);
    // A second legal entity with no placement has nothing, and no uncovered slot either.
    UUID second =
        Hierarchy.newLegalEntity(mvc, w.tenant(), Hierarchy.code("LE"), "2026-01-01", null);
    JsonNode empty = status(w, w.admin, ADMIN, "legalEntityId=" + second);
    assertThat(shifts(empty)).containsExactly(0, 0, 0, 0, 0, 0);
    assertThat(empty.get("coverage").get("uncovered").asInt()).isZero();
    // Combined filters narrow further.
    assertThat(shifts(status(w, w.admin, ADMIN, "siteId=" + w.site() + "&" + entity)))
        .isEqualTo(shifts(org));
    assertThat(shifts(status(w, w.admin, ADMIN, "siteId=" + w.otherSite + "&" + entity)))
        .containsExactly(0, 0, 0, 0, 0, 0);
  }

  @Test
  void aMovedPlacementFollowsTheLegalEntityOnTheShiftsDate() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    UUID second =
        Hierarchy.newLegalEntity(mvc, w.tenant(), Hierarchy.code("LE"), "2026-01-01", null);
    UUID secondSite =
        Hierarchy.newSite(mvc, w.tenant(), second, Hierarchy.code("SC"), "2026-01-01", null);
    UUID secondDepartment =
        Hierarchy.newSiteUnit(
            mvc,
            "/api/v1/departments",
            w.tenant(),
            secondSite,
            Hierarchy.code("DD"),
            "2026-01-01",
            null);
    LocalDate d = w.today.plusDays(4);
    // Ben moves to the second legal entity's site the day before the shift.
    java.util.Map<String, Object> command =
        w.placement(secondSite, secondDepartment, d.minusDays(1));
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> placement =
        (java.util.Map<String, Object>) command.get("placement");
    placement.put("legalEntityId", second.toString());
    w.expect(w.change(ben.employee(), command), 201);
    w.published(proposal(w.site(), d, d, List.of(shift("a", d, 8, 16, ana.employee()))));
    w.published(proposal(secondSite, d, d, List.of(shift("b", d, 8, 16, ben.employee()))));
    clock.set(instantsOfDay(w, d).plus(GRACE).plus(MICRO));

    assertThat(shifts(status(w, w.admin, ADMIN, "legalEntityId=" + w.org.legalEntityId())))
        .containsExactly(1, 0, 1, 0, 0, 0);
    JsonNode moved = status(w, w.admin, ADMIN, "legalEntityId=" + second);
    assertThat(shifts(moved)).containsExactly(1, 0, 1, 0, 0, 0);
    assertThat(employees(moved.get("details").get("notCheckedIn")))
        .containsExactly(ben.employee().toString());
    assertThat(shifts(status(w, w.admin, ADMIN, ""))).containsExactly(2, 0, 2, 0, 0, 0);
  }

  private Instant instantsOfDay(ScheduleWorld w, LocalDate d) {
    return jdbc.queryForObject(
            "SELECT min(l.start_at) FROM people.work_schedule_slot l JOIN people.work_schedule s"
                + " ON s.tenant_id = l.tenant_id AND s.id = l.schedule_id WHERE l.tenant_id = ?"
                + " AND s.first_date = ?",
            java.sql.Timestamp.class,
            w.tenant(),
            d)
        .toInstant();
  }

  // ------------------------------------------------------------------------------------------
  // A100-4: undisclosable filters, authorization and tenant isolation
  // ------------------------------------------------------------------------------------------

  @Test
  void undisclosableFiltersAreOneIndistinguishable404() throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    ScheduleWorld other = new ScheduleWorld(mvc, jdbc, lookups);
    clock.set(t.start());
    String code = "OPERATIONAL_STATUS_SCOPE_NOT_FOUND";
    JsonNode malformed = w.problem(read(w.admin, ADMIN, "siteId=nope"), 404, code);
    assertThat(malformed.has("params") ? malformed.get("params").size() : 0).isZero();
    List<String> bad =
        List.of(
            "siteId=" + UUID.randomUUID(), // unknown
            "siteId=" + other.site(), // another tenant's site
            "siteId=" + w.org.legalEntityId(), // a legal entity is not a site
            "legalEntityId=" + w.site(), // a site is not a legal entity
            "legalEntityId=" + other.org.legalEntityId(), // another tenant's entity
            "legalEntityId=" + UUID.randomUUID(),
            "siteId=%20");
    for (String query : bad) {
      assertThat(stable(w.problem(read(w.admin, ADMIN, query), 404, code)))
          .as(query)
          .isEqualTo(stable(malformed));
    }
  }

  @Test
  void onlyTheRightCallerReadsAndATenantNeverSeesAnotherTenantsFigures() throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    ScheduleWorld other = new ScheduleWorld(mvc, jdbc, lookups);
    Person foreign = other.person("Zed", "Foreign");
    LocalDate d = other.today.plusDays(3);
    other.published(
        proposal(other.site(), d, d, List.of(shift("f", d, 8, 16, foreign.employee()))));
    clock.set(t.start().plus(GRACE).plus(MICRO));

    // Another tenant's administrator and the first tenant's figures never mix.
    assertThat(shifts(status(w, w.admin, ADMIN, ""))).containsExactly(6, 0, 6, 0, 0, 0);
    JsonNode foreignView = status(other, other.admin, ADMIN, "");
    assertThat(foreignView.get("details").get("notCheckedIn").get("items").size()).isLessThan(2);
    assertThat(foreignView.toString()).doesNotContain(t.ana().employee().toString());
    assertThat(status(w, w.admin, ADMIN, "").toString())
        .doesNotContain(foreign.employee().toString());

    // An employee is not an administrator; an administrator is not an employee with a link.
    w.problem(read(t.ana().bearer(), ADMIN, ""), 403, "ACCESS_DENIED");
    w.problem(read(w.admin, TEAM, ""), 403, "ACCESS_DENIED");
    // An employee without an active link is refused with the link code.
    String subject = UUID.randomUUID().toString();
    w.member(subject);
    w.problem(
        read(ScheduleWorld.employeeBearer(w.tenant(), subject), TEAM, ""),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    // Unauthenticated callers.
    assertThat(w.call(get(ADMIN)).getStatus()).isEqualTo(401);
    assertThat(w.call(get(TEAM)).getStatus()).isEqualTo(401);
  }

  // ------------------------------------------------------------------------------------------
  // A100-3: bounded, deterministic, explicitly truncated details
  // ------------------------------------------------------------------------------------------

  @Test
  void detailListsAreCappedOrderedAndTheirAggregatesStayComplete() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    LocalDate d = w.today.plusDays(3);
    List<java.util.Map<String, Object>> slots = new ArrayList<>();
    // One worker, 21 consecutive half-hour shifts: 21 occurrences, one more than the detail limit.
    for (int i = 0; i < 21; i++) {
      int minutes = i * 30;
      slots.add(
          slot(
              "s" + i,
              d + String.format("T%02d:%02d", minutes / 60, minutes % 60),
              d + String.format("T%02d:%02d", (minutes + 30) / 60, (minutes + 30) % 60),
              "Cashier",
              "Caissière",
              ana.employee()));
    }
    for (int i = 0; i < 22; i++) {
      slots.add(slot("u" + i, d + "T17:00", d + "T18:00", "Late", "Tard", null));
    }
    String schedule = w.published(proposal(w.site(), d, d, slots));
    Instant last =
        jdbc.queryForObject(
                "SELECT max(start_at) FROM people.work_schedule_slot WHERE tenant_id = ?",
                java.sql.Timestamp.class,
                w.tenant())
            .toInstant();
    assertThat(slotIds(w, schedule)).hasSize(43);
    clock.set(last.plus(Duration.ofHours(2)));

    JsonNode view = status(w, w.admin, ADMIN, "");
    assertThat(shifts(view).get(0)).isEqualTo(21);
    assertThat(shifts(view).get(2)).isEqualTo(21);
    JsonNode missing = view.get("details").get("notCheckedIn");
    assertThat(missing.get("items")).hasSize(20);
    assertThat(missing.get("truncated").asBoolean()).isTrue();
    // Oldest start first, then slot, then employee: the same twice.
    List<Instant> starts = new ArrayList<>();
    missing
        .get("items")
        .forEach(i -> starts.add(Instant.parse(i.get("shift").get("startAt").asText())));
    assertThat(starts).isSorted();
    assertThat(status(w, w.admin, ADMIN, "").get("details").get("notCheckedIn").get("items"))
        .isEqualTo(missing.get("items"));
    assertThat(view.get("coverage").get("uncovered").asInt()).isEqualTo(22);
    assertThat(view.get("details").get("uncovered").get("items")).hasSize(20);
    assertThat(view.get("details").get("uncovered").get("truncated").asBoolean()).isTrue();
    // The uncovered list carries shift facts only.
    assertThat(view.get("details").get("uncovered").get("items").get(0).has("employee")).isFalse();
  }

  // ------------------------------------------------------------------------------------------
  // Audit and privacy
  // ------------------------------------------------------------------------------------------

  @Test
  void everyResponseIsAFailClosedDisclosureWithoutPersonalDataInAuditOrLogs(CapturedOutput output)
      throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    clock.set(t.start().plus(GRACE).plus(MICRO));
    org.mockito.Mockito.clearInvocations(
        (AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
    int logged = output.getAll().length();

    JsonNode admin = status(w, w.admin, ADMIN, "siteId=" + w.site());
    status(w, t.mia().bearer(), TEAM, "");

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(audit, org.mockito.Mockito.atLeast(2)).record(events.capture());
    List<AuditEvent> mine =
        events.getAllValues().stream()
            .filter(e -> e.action().startsWith("operations.status-"))
            .toList();
    assertThat(mine)
        .extracting(AuditEvent::action)
        .containsExactly("operations.status-admin-view", "operations.status-team-view");
    AuditEvent adminEvent = mine.get(0);
    assertThat(adminEvent.resourceType()).isEqualTo("organization");
    assertThat(adminEvent.metadata())
        .containsEntry("view", "organization-operational-status")
        .containsEntry("scheduled", 6)
        .containsEntry("siteFilter", true)
        .containsEntry("legalEntityFilter", false);
    String metadata = adminEvent.metadata().toString() + mine.get(1).metadata();
    for (Person person : List.of(t.ana(), t.ben(), t.cy(), t.dee(), t.eli(), t.mia())) {
      assertThat(metadata)
          .doesNotContain(person.number())
          .doesNotContain(person.employee().toString());
    }
    assertThat(metadata)
        .doesNotContain("Cashier")
        .doesNotContain("Caissière")
        .doesNotContain("Kalala");
    assertThat(adminEvent.afterStateSha256()).isNotBlank();

    // Logs: operation, outcome and counts only.
    String logs = output.getAll().substring(logged);
    assertThat(logs).contains("operational_status_read");
    for (Person person : List.of(t.ana(), t.mia(), t.cy())) {
      assertThat(logs).doesNotContain(person.number()).doesNotContain(person.employee().toString());
    }
    assertThat(logs).doesNotContain("Kalala").doesNotContain("Mbuyi").doesNotContain("Caissière");
    assertThat(admin.get("details").toString())
        .contains("Kalala"); // the response itself names them: that is the disclosure
  }
}
