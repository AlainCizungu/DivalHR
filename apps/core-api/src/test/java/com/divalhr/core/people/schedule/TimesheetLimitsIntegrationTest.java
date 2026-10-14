package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.approve;
import static com.divalhr.core.people.schedule.SubstitutionCalls.request;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TimesheetCalls.confirmed;
import static com.divalhr.core.people.schedule.TimesheetCalls.generate;
import static com.divalhr.core.people.schedule.TimesheetCalls.period;
import static com.divalhr.core.people.schedule.TimesheetCalls.previewGeneration;
import static com.divalhr.core.people.schedule.TimesheetCalls.previewRefresh;
import static com.divalhr.core.people.schedule.TimesheetCalls.refresh;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-070A bounds (T70A-3, T70A-12 item 8) with small test limits (one draft, two rows): a week
 * over either bound is refused with {@code 422 TIMESHEET_GENERATION_LIMIT_EXCEEDED} by the preview
 * and the command before any write, never partially generated; a refresh that would exceed them is
 * refused the same way. Production uses 500 drafts and 5,000 rows.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@TestPropertySource(
    properties = {"divalhr.timesheets.max-drafts=1", "divalhr.timesheets.max-rows=2"})
class TimesheetLimitsIntegrationTest {

  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String hr(ScheduleWorld w) {
    return Hierarchy.bearer(w.tenant(), "sub-ts-lim-" + UUID.randomUUID(), "tenant-admin");
  }

  private static String key() {
    return Organizations.newKey();
  }

  private List<Integer> written(ScheduleWorld w) {
    return List.of(
        w.count("SELECT count(*) FROM people.timesheet_generation WHERE tenant_id = ?", w.tenant()),
        w.count("SELECT count(*) FROM people.timesheet WHERE tenant_id = ?", w.tenant()),
        w.count("SELECT count(*) FROM people.timesheet_row WHERE tenant_id = ?", w.tenant()),
        w.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('timesheet.generate', 'timesheet.refresh')",
            w.tenant()));
  }

  private void wednesday(LocalDate monday) {
    clock.set(monday.plusDays(2).atTime(LocalTime.NOON).atZone(KINSHASA).toInstant());
  }

  @Test
  void aWeekOverEitherBoundIsRefusedBeforeAnyWrite() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    LocalDate monday = Timesheet.Period.mondayOf(w.today).plusDays(7);
    // Two drafts (one each): over the one-draft bound.
    w.published(
        proposal(
            w.site(),
            monday,
            monday,
            List.of(
                shift("a", monday, 8, 12, ana.employee()),
                shift("b", monday, 8, 12, ben.employee()))));
    // Three rows for one employee: over the two-row bound.
    LocalDate next = monday.plusDays(7);
    w.published(
        proposal(
            w.site(),
            next,
            next.plusDays(2),
            List.of(
                shift("a", next, 8, 12, ana.employee()),
                shift("b", next.plusDays(1), 8, 12, ana.employee()),
                shift("c", next.plusDays(2), 8, 12, ana.employee()))));
    wednesday(next);
    String admin = hr(w);
    for (LocalDate week : List.of(monday, next)) {
      w.problem(
          previewGeneration(admin, period(w.site(), week)),
          422,
          "TIMESHEET_GENERATION_LIMIT_EXCEEDED");
      w.problem(
          generate(admin, key(), confirmed(period(w.site(), week))),
          422,
          "TIMESHEET_GENERATION_LIMIT_EXCEEDED");
    }
    assertThat(written(w)).containsExactly(0, 0, 0, 0);
  }

  @Test
  void aRefreshOverTheBoundIsRefusedBeforeAnyWrite() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, ben);
    LocalDate monday = Timesheet.Period.mondayOf(w.today).plusDays(7);
    List<String> slots =
        slotIds(
            w,
            w.published(
                proposal(
                    w.site(),
                    monday,
                    monday.plusDays(6),
                    List.of(
                        shift("a", monday, 8, 12, ana.employee()),
                        shift("b", monday.plusDays(6), 8, 12, ana.employee())))));
    wednesday(monday);
    String admin = hr(w);
    Map<String, Object> week = period(w.site(), monday);
    // Exactly at both bounds: one draft, two rows.
    JsonNode preview = w.expect(previewGeneration(admin, week), 200);
    assertThat(preview.get("maxDrafts").asInt()).isEqualTo(1);
    assertThat(preview.get("maxRows").asInt()).isEqualTo(2);
    String id =
        w.expect(generate(admin, key(), confirmed(week)), 200)
            .get("generation")
            .get("generationId")
            .asText();
    // Sunday goes to Ben: the refresh would hold two drafts.
    String requested = w.expect(request(ana, key(), slots.get(1)), 201).get("id").asText();
    w.expect(approve(mia, requested, key(), ben.employee()), 200);
    List<Integer> before = written(w);
    w.problem(previewRefresh(admin, id, week), 422, "TIMESHEET_GENERATION_LIMIT_EXCEEDED");
    Map<String, Object> body = new LinkedHashMap<>(week);
    body.put("version", 1);
    body.put("previewDigest", "0".repeat(64));
    body.put("confirm", true);
    w.problem(refresh(admin, key(), id, body), 422, "TIMESHEET_GENERATION_LIMIT_EXCEEDED");
    assertThat(written(w)).isEqualTo(before);
  }
}
