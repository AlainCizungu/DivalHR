package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.CorrectionCalls.commit;
import static com.divalhr.core.people.schedule.CorrectionCalls.commitRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.previewRecord;
import static com.divalhr.core.people.schedule.CorrectionCalls.values;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approval;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.approve;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.decision;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submission;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submit;
import static com.divalhr.core.people.schedule.TimesheetCalls.confirmed;
import static com.divalhr.core.people.schedule.TimesheetCalls.generate;
import static com.divalhr.core.people.schedule.TimesheetCalls.generation;
import static com.divalhr.core.people.schedule.TimesheetCalls.mine;
import static com.divalhr.core.people.schedule.TimesheetCalls.previewRefresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refreshBody;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.EXPLANATION;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.locking;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.previewLock;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.previewUnlock;
import static com.divalhr.core.people.schedule.TimesheetPeriodCalls.reasoned;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * API fixtures of locked approved weeks for the payroll tests (MVP-090A), built only through the
 * public API of MVP-050A to MVP-071: a tenant with one legal entity and two sites, people,
 * published shifts, check-ins, generations, submissions, approvals, exports, locks and unlocks.
 * Nothing is written to a people table directly, so what payroll reads is exactly what the product
 * produces.
 */
public final class LockedWeeks {

  /** The business zone of the fixture organization. */
  public static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final SettableClock clock;
  private final ScheduleWorld w;
  private final String admin;
  private final Person mia;
  private final Person ana;
  private final Person eve;
  private final Person zoe;

  /**
   * A tenant, its legal entity and two sites, Mia managing Ana, Eve and Zoe.
   *
   * @param mvc MockMvc
   * @param jdbc JDBC (reads of identifiers only)
   * @param lookups e-mail lookups
   * @param clock the settable platform clock
   * @throws Exception on request failure
   */
  public LockedWeeks(MockMvc mvc, JdbcTemplate jdbc, EmailLookup lookups, SettableClock clock)
      throws Exception {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.clock = clock;
    this.w = new ScheduleWorld(mvc, jdbc, lookups);
    this.admin = administrator();
    this.mia = w.person("Mia", "Mbuyi");
    this.ana = w.person("Ana", "Kalala");
    this.eve = w.person("Eve", "Ngoy");
    this.zoe = w.person("Zoe", "Tshala");
    w.manageAll(mia, ana, eve, zoe);
  }

  /**
   * A generated week of the main site: Ana worked Monday and Tuesday, Eve and Zoe Monday (four
   * READY rows, eight hours each less the five-minute late check-ins); nobody has submitted.
   *
   * @param monday the Monday
   * @param generationId the generation
   * @param sheets employee to timesheet
   * @param records shift reference to attendance record
   * @param shifts shift reference to [start, end]
   */
  public record Week(
      LocalDate monday,
      String generationId,
      Map<UUID, String> sheets,
      Map<String, String> records,
      Map<String, Instant[]> shifts) {

    /** Keeps immutable copies of the maps. */
    public Week {
      sheets = Map.copyOf(sheets);
      records = Map.copyOf(records);
      shifts = Map.copyOf(shifts);
    }

    /** The Sunday. */
    public LocalDate sunday() {
      return monday.plusDays(6);
    }
  }

  /** The tenant. */
  public UUID tenant() {
    return w.tenant();
  }

  /** The legal entity. */
  public UUID legalEntity() {
    return w.org.legalEntityId();
  }

  /** The main site (where the weeks are scheduled). */
  public UUID site() {
    return w.site();
  }

  /** The legal entity's other site (never scheduled unless asked). */
  public UUID otherSite() {
    return w.otherSite;
  }

  /** The fixture's tenant administrator (its own per-subject rate limits). */
  public String admin() {
    return admin;
  }

  /** Another tenant administrator of the tenant (its own per-subject rate limits). */
  public String administrator() {
    return Hierarchy.bearer(w.tenant(), "sub-pay90-" + UUID.randomUUID(), "tenant-admin");
  }

  /** Ana. */
  public Person ana() {
    return ana;
  }

  /** Eve. */
  public Person eve() {
    return eve;
  }

  /** Zoe. */
  public Person zoe() {
    return zoe;
  }

  /** Mia, the manager. */
  public Person mia() {
    return mia;
  }

  /** Performs a call. */
  public MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
    return w.call(request);
  }

  /** Performs a call and asserts its status; the JSON body. */
  public JsonNode expect(MockHttpServletRequestBuilder request, int status) throws Exception {
    return w.expect(request, status);
  }

  /** Performs a call and asserts its status and problem code. */
  public JsonNode problem(MockHttpServletRequestBuilder request, int status, String code)
      throws Exception {
    return w.problem(request, status, code);
  }

  /** A count query. */
  public int count(String sql, Object... args) {
    return w.count(sql, args);
  }

  /** The Monday after the fixture's today (the first week that can be scheduled freely). */
  public LocalDate nextMonday() {
    return Timesheet.Period.mondayOf(w.today).plusDays(7);
  }

  private static String key() {
    return Organizations.newKey();
  }

  /**
   * Schedules, works and generates a week of the main site (the clock ends at its Sunday noon).
   *
   * @param monday the Monday
   * @return the week
   * @throws Exception on request failure
   */
  public Week generated(LocalDate monday) throws Exception {
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
                        shift("e0", monday, 8, 16, eve.employee()),
                        shift("z0", monday, 8, 16, zoe.employee()),
                        shift("a1", monday.plusDays(1), 8, 16, ana.employee())))));
    Map<String, Instant[]> shifts = new LinkedHashMap<>();
    shifts.put("a0", instants(jdbc, slots.get(0)));
    shifts.put("a1", instants(jdbc, slots.get(3)));
    Instant[] mon = shifts.get("a0");
    Map<String, String> records = new LinkedHashMap<>();
    clock.set(mon[0].plus(Duration.ofMinutes(5)));
    records.put("a0", record(checkIn(ana, key(), slots.get(0))));
    records.put("e0", record(checkIn(eve, key(), slots.get(1))));
    records.put("z0", record(checkIn(zoe, key(), slots.get(2))));
    clock.set(mon[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), records.get("a0")), 200);
    w.expect(checkOut(eve, key(), records.get("e0")), 200);
    w.expect(checkOut(zoe, key(), records.get("z0")), 200);
    Instant[] tue = shifts.get("a1");
    clock.set(tue[0].plus(Duration.ofMinutes(5)));
    records.put("a1", record(checkIn(ana, key(), slots.get(3))));
    clock.set(tue[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), records.get("a1")), 200);
    sundayNoon(monday);
    String generationId =
        w.expect(generate(admin, key(), confirmed(TimesheetCalls.period(w.site(), monday))), 200)
            .get("generation")
            .get("generationId")
            .asText();
    Map<UUID, String> sheets = new LinkedHashMap<>();
    w.expect(generation(admin, generationId), 200)
        .get("timesheets")
        .forEach(
            d ->
                sheets.put(
                    UUID.fromString(d.get("employee").get("id").asText()),
                    d.get("timesheetId").asText()));
    return new Week(monday, generationId, sheets, records, shifts);
  }

  /**
   * Schedules one shift of Eve in a week of a site without generating it (a week that matters but
   * is not generated).
   *
   * @param siteId site
   * @param monday the Monday
   * @throws Exception on request failure
   */
  public void scheduledOnly(UUID siteId, LocalDate monday) throws Exception {
    w.published(
        proposal(siteId, monday, monday, List.of(shift("e", monday, 8, 16, eve.employee()))));
  }

  /**
   * Sets the clock to a week's Sunday noon at the business zone.
   *
   * @param monday the Monday
   */
  public void sundayNoon(LocalDate monday) {
    clock.set(monday.plusDays(6).atTime(LocalTime.NOON).atZone(ZONE).toInstant());
  }

  /** The week with every timesheet submitted and approved by Mia (cycle 1). */
  public Week approved(Week week) throws Exception {
    approveCycle(week, ana);
    approveCycle(week, eve);
    approveCycle(week, zoe);
    return week;
  }

  /** Submits one person's timesheet of the week and has Mia approve it. */
  public JsonNode approveCycle(Week week, Person person) throws Exception {
    String id = week.sheets().get(person.employee());
    JsonNode own = w.expect(mine(person.bearer(), id), 200);
    w.expect(submit(person.bearer(), key(), id, submission(own)), 200);
    JsonNode reviewed = w.expect(approval(mia.bearer(), id), 200);
    return w.expect(approve(mia.bearer(), key(), id, decision(reviewed)), 200);
  }

  /** A generated, approved and locked week; the LOCKED action's identifier. */
  public Week locked(LocalDate monday) throws Exception {
    Week week = approved(generated(monday));
    lock(week);
    return week;
  }

  /** Locks the week; returns the LOCKED action's identifier. */
  public String lock(Week week) throws Exception {
    JsonNode preview = w.expect(previewLock(admin, scope(week)), 200);
    assertThat(preview.get("ready").asBoolean()).as(preview.toString()).isTrue();
    return w.expect(TimesheetPeriodCalls.lock(admin, key(), locking(scope(week), preview)), 200)
        .get("action")
        .get("actionId")
        .asText();
  }

  /** Unlocks the week with a closed reason. */
  public void unlock(Week week) throws Exception {
    w.expect(unlocking(week), 200);
  }

  /** The unlock of the week with a closed reason, previewed, as a call (for races). */
  public MockHttpServletRequestBuilder unlocking(Week week) throws Exception {
    Map<String, Object> reasoned =
        reasoned(scope(week), "ATTENDANCE_CORRECTION_REQUIRED", EXPLANATION);
    JsonNode preview = w.expect(previewUnlock(admin, reasoned), 200);
    return TimesheetPeriodCalls.unlock(
        administrator(), key(), TimesheetPeriodCalls.unlocking(reasoned, preview));
  }

  /**
   * An administrator's correction of Ana's Monday check-in (late by the given minutes): the
   * preview, then the commit's response (201 on an open week, 409 TIMESHEET_PERIOD_LOCKED on a
   * locked one).
   */
  public MockHttpServletResponse correctAna(Week week, int lateMinutes) throws Exception {
    Instant[] mon = week.shifts().get("a0");
    Map<String, Object> fix = values(mon[0].plus(Duration.ofMinutes(lateMinutes)), mon[1]);
    String fixer = administrator();
    JsonNode reviewed = w.expect(previewRecord(fixer, week.records().get("a0"), fix), 200);
    return w.call(commitRecord(fixer, key(), week.records().get("a0"), commit(fix, reviewed)));
  }

  /** Refreshes the generation (after a reopening). */
  public void refresh(Week week) throws Exception {
    String refresher = administrator();
    Map<String, Object> period = TimesheetCalls.period(w.site(), week.monday());
    JsonNode preview = w.expect(previewRefresh(refresher, week.generationId(), period), 200);
    w.expect(
        TimesheetCalls.refresh(refresher, key(), week.generationId(), refreshBody(period, preview)),
        200);
  }

  /** Exports the approved week (MVP-070E); the export's identifier. */
  public String export(Week week) throws Exception {
    Map<String, Object> scope =
        TimesheetExportCalls.scope(w.org.legalEntityId(), w.site(), week.monday());
    JsonNode preview = w.expect(TimesheetExportCalls.preview(admin, scope), 200);
    return w.expect(
            TimesheetExportCalls.create(
                admin, key(), TimesheetExportCalls.creation(scope, preview)),
            200)
        .get("export")
        .get("exportId")
        .asText();
  }

  /** The period-lock scope body of a week. */
  public Map<String, Object> scope(Week week) {
    return TimesheetPeriodCalls.scope(w.org.legalEntityId(), w.site(), week.monday());
  }

  /** The current lock state of a week's generation, read through the API. */
  public JsonNode history(Week week) throws Exception {
    return w.expect(TimesheetPeriodCalls.history(admin, week.generationId()), 200);
  }

  /**
   * Moves Zoe's employment placement to another legal entity's site and department from a date
   * (MVP-022; a late notification when the date is past).
   *
   * @param legalEntityId legal entity
   * @param siteId its site
   * @param departmentId the site's department
   * @param from first day
   * @throws Exception on request failure
   */
  public void placeZoeIn(UUID legalEntityId, UUID siteId, UUID departmentId, LocalDate from)
      throws Exception {
    Map<String, Object> placement = new LinkedHashMap<>();
    placement.put("legalEntityId", legalEntityId.toString());
    placement.put("siteId", siteId.toString());
    placement.put("departmentId", departmentId.toString());
    Map<String, Object> change = new LinkedHashMap<>();
    change.put("type", "CHANGE");
    change.put("effectiveFrom", from.toString());
    change.put("placement", placement);
    if (from.isBefore(w.today)) {
      change.put("reasonCode", "LATE_NOTIFICATION");
    }
    w.expect(w.change(zoe.employee(), change), 201);
  }

  /** A GET through MockMvc with a bearer. */
  public static MockHttpServletRequestBuilder get(String bearer, String path) {
    return MockMvcRequestBuilders.get(path).header("Authorization", bearer);
  }

  private String record(MockHttpServletRequestBuilder call) throws Exception {
    return w.expect(call, 201).get("attendanceRecordId").asText();
  }
}
