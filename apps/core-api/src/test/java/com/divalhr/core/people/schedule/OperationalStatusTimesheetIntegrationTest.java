package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.checkIn;
import static com.divalhr.core.people.schedule.AttendanceCalls.checkOut;
import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.queue;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submission;
import static com.divalhr.core.people.schedule.TimesheetApprovalCalls.submit;
import static com.divalhr.core.people.schedule.TimesheetCalls.confirmed;
import static com.divalhr.core.people.schedule.TimesheetCalls.generate;
import static com.divalhr.core.people.schedule.TimesheetCalls.mine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-100 (Issue #154, amendments C and D): the submitted-timesheet figures use the exact 72-hour
 * threshold (strictly greater), agree with the routed manager's approval queue, and give the
 * administrator an organization-scoped count that a site filter narrows.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class OperationalStatusTimesheetIntegrationTest {

  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");
  private static final String ADMIN = "/api/v1/operations/status";
  private static final String TEAM = "/api/v1/me/team-operations/status";
  private static final Duration LIMIT = Duration.ofHours(72);
  private static final Duration MICRO = Duration.ofNanos(1_000);

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String key() {
    return Organizations.newKey();
  }

  @Test
  void thresholdIsStrictAndFiguresAgreeWithTheQueue() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    Person mia = w.person("Mia", "Mbuyi");
    Person ana = w.person("Ana", "Kalala");
    Person ben = w.person("Ben", "Ilunga");
    w.manageAll(mia, ana, ben);
    String hr = Hierarchy.bearer(w.tenant(), "sub-ops-ts-" + UUID.randomUUID(), "tenant-admin");
    LocalDate monday = Timesheet.Period.mondayOf(w.today).plusDays(7);
    List<String> slots =
        slotIds(
            w,
            w.published(
                proposal(
                    w.site(),
                    monday,
                    monday,
                    List.of(
                        shift("a0", monday, 8, 16, ana.employee()),
                        shift("b0", monday, 8, 16, ben.employee())))));
    Instant[] span = instants(jdbc, slots.get(0));
    clock.set(span[0].plus(Duration.ofMinutes(5)));
    String a0 = w.expect(checkIn(ana, key(), slots.get(0)), 201).get("attendanceRecordId").asText();
    String b0 = w.expect(checkIn(ben, key(), slots.get(1)), 201).get("attendanceRecordId").asText();
    clock.set(span[1].minus(Duration.ofMinutes(1)));
    w.expect(checkOut(ana, key(), a0), 200);
    w.expect(checkOut(ben, key(), b0), 200);
    Instant sunday = monday.plusDays(6).atTime(LocalTime.NOON).atZone(KINSHASA).toInstant();
    clock.set(sunday);
    String generationId =
        w.expect(generate(hr, key(), confirmed(TimesheetCalls.period(w.site(), monday))), 200)
            .get("generation")
            .get("generationId")
            .asText();
    Map<UUID, String> drafts = new HashMap<>();
    w.expect(TimesheetCalls.generation(hr, generationId), 200)
        .get("timesheets")
        .forEach(
            d ->
                drafts.put(
                    UUID.fromString(d.get("employee").get("id").asText()),
                    d.get("timesheetId").asText()));

    Instant asOf = sunday.plus(Duration.ofHours(100));
    clock.set(asOf.minus(LIMIT));
    submitDraft(w, ana, drafts.get(ana.employee()));
    clock.set(asOf.minus(LIMIT).minus(MICRO));
    submitDraft(w, ben, drafts.get(ben.employee()));

    clock.set(asOf);
    JsonNode team = w.expect(get(TEAM).header("Authorization", mia.bearer()), 200);
    JsonNode waiting = team.get("approvals").get("timesheets");
    assertThat(waiting.get("pending").asInt()).isEqualTo(2);
    assertThat(waiting.get("overdue").asInt()).isEqualTo(1);
    JsonNode items = team.get("details").get("overdueTimesheets").get("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("employee").get("id").asText())
        .isEqualTo(ben.employee().toString());
    assertThat(items.get(0).get("weekStart").asText()).isEqualTo(monday.toString());
    assertThat(items.get(0).get("ageHours").asLong()).isEqualTo(72);

    // Consistency with the routed manager's own approval queue.
    JsonNode inbox = w.expect(queue(mia.bearer()), 200);
    assertThat(inbox.get("items")).hasSize(waiting.get("pending").asInt());

    // The administrator: organization-scoped counts, no person details; the site filter narrows.
    JsonNode org = w.expect(get(ADMIN).header("Authorization", hr), 200);
    assertThat(org.get("approvals").get("timesheets").get("pending").asInt()).isEqualTo(2);
    assertThat(org.get("approvals").get("timesheets").get("overdue").asInt()).isEqualTo(1);
    assertThat(org.get("details").get("overdueTimesheets").isNull()).isTrue();
    JsonNode site = w.expect(get(ADMIN + "?siteId=" + w.site()).header("Authorization", hr), 200);
    assertThat(site.get("approvals").get("timesheets").get("pending").asInt()).isEqualTo(2);
  }

  private static void submitDraft(ScheduleWorld w, Person p, String id) throws Exception {
    JsonNode mine = w.expect(mine(p.bearer(), id), 200);
    w.expect(submit(p.bearer(), key(), id, submission(mine)), 200);
  }
}
