package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.MINE;
import static com.divalhr.core.people.schedule.ScheduleWorld.SCHEDULES;
import static com.divalhr.core.people.schedule.ScheduleWorld.preview;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.publish;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * MVP-050A R100-1 (PR #100 architecture review): a site schedule runs on the selected site's clock,
 * never the organization's. The organization is in Kinshasa (UTC+1) and the Goma site in {@code
 * Africa/Lubumbashi} (UTC+2), one hour apart: the stored and returned time zone is the site's; the
 * business date ("not in the past") is the site's, on both sides of midnight; local times convert
 * in the site's zone; a changed site zone makes a reviewed preview stale; and an employee working
 * at both sites sees each shift in its own schedule's stored zone.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class WorkScheduleSiteTimezoneIntegrationTest {

  private static final String KINSHASA = "Africa/Kinshasa";
  private static final String GOMA = "Africa/Lubumbashi";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  /** A world whose organization is in Kinshasa, with a Goma site and a department there. */
  private record Goma(ScheduleWorld w, UUID site, UUID department) {}

  private Goma goma() throws Exception {
    ScheduleWorld w = new ScheduleWorld(mvc, jdbc, lookups);
    UUID site =
        Hierarchy.newSite(
            mvc, w.tenant(), w.org.legalEntityId(), Hierarchy.code("GO"), GOMA, "2026-01-01", null);
    UUID department =
        Hierarchy.newSiteUnit(
            mvc, "/api/v1/departments", w.tenant(), site, Hierarchy.code("GD"), "2026-01-01", null);
    assertThat(organizationZone(w)).isEqualTo(KINSHASA);
    return new Goma(w, site, department);
  }

  private String organizationZone(ScheduleWorld w) {
    return jdbc.queryForObject(
        "SELECT timezone FROM tenant.organization WHERE id = ?", String.class, w.tenant());
  }

  private static String reason(JsonNode problem) {
    return problem.get("params").get("reason").asText();
  }

  @Test
  void theSiteTimeZoneIsStoredReturnedAndUsedToConvertLocalTimes() throws Exception {
    Goma g = goma();
    ScheduleWorld w = g.w();
    LocalDate d = w.today.plusDays(6);
    List<Map<String, Object>> eightToFour = List.of(shift("a", d, 8, 16, null));

    JsonNode atGoma = w.expect(preview(w.admin, proposal(g.site(), d, d, eightToFour)), 200);
    assertThat(atGoma.get("siteTimezone").asText()).isEqualTo(GOMA);
    // 08:00 in Goma is 06:00 UTC; the same local time in Kinshasa is 07:00 UTC.
    assertThat(atGoma.get("slots").get(0).get("startAt").asText()).isEqualTo(d + "T06:00:00Z");
    assertThat(atGoma.get("slots").get(0).get("endAt").asText()).isEqualTo(d + "T14:00:00Z");
    JsonNode atKinshasa = w.expect(preview(w.admin, proposal(w.site(), d, d, eightToFour)), 200);
    assertThat(atKinshasa.get("siteTimezone").asText()).isEqualTo(KINSHASA);
    assertThat(atKinshasa.get("slots").get(0).get("startAt").asText()).isEqualTo(d + "T07:00:00Z");

    String id = w.published(proposal(g.site(), d, d, eightToFour));
    assertThat(
            jdbc.queryForObject(
                "SELECT site_timezone FROM people.work_schedule WHERE tenant_id = ? AND id ="
                    + " ?::uuid",
                String.class,
                w.tenant(),
                id))
        .isEqualTo(GOMA);
    assertThat(
            jdbc.queryForObject(
                "SELECT start_at = ?::timestamptz FROM people.work_schedule_slot"
                    + " WHERE tenant_id = ? AND schedule_id = ?::uuid",
                Boolean.class,
                d + "T06:00:00Z",
                w.tenant(),
                id))
        .isTrue();
    JsonNode detail = w.expect(get(SCHEDULES + "/" + id).header("Authorization", w.admin), 200);
    assertThat(detail.get("siteTimezone").asText()).isEqualTo(GOMA);
    JsonNode listed =
        w.expect(get(SCHEDULES + "?siteId=" + g.site()).header("Authorization", w.admin), 200);
    assertThat(listed.get("items").get(0).get("siteTimezone").asText()).isEqualTo(GOMA);
    // The response never carries the organization's zone under the old, ambiguous name.
    assertThat(atGoma.has("timezone")).isFalse();
    assertThat(detail.has("timezone")).isFalse();
    assertThat(organizationZone(w)).isEqualTo(KINSHASA);
  }

  @Test
  void nearMidnightTheSiteLocalDateDecidesWhatIsInThePast() throws Exception {
    Goma g = goma();
    ScheduleWorld w = g.w();
    LocalDate day = w.today.plusDays(10);
    LocalDate eve = day.minusDays(1);

    // 22:30 UTC on the eve: 23:30 in Kinshasa (still the eve), 00:30 in Goma (already the day).
    clock.set(eve.atTime(22, 30).toInstant(ZoneOffset.UTC));
    assertThat(
            reason(
                w.problem(
                    preview(
                        w.admin,
                        proposal(g.site(), eve, eve, List.of(shift("a", eve, 8, 9, null)))),
                    422,
                    "WORK_SCHEDULE_PERIOD_INVALID")))
        .isEqualTo("PAST");
    JsonNode gomaDay =
        w.expect(
            preview(w.admin, proposal(g.site(), day, day, List.of(shift("a", day, 8, 9, null)))),
            200);
    assertThat(gomaDay.get("asOf").asText()).isEqualTo(day.toString());
    JsonNode kinshasaEve =
        w.expect(
            preview(w.admin, proposal(w.site(), eve, eve, List.of(shift("a", eve, 8, 9, null)))),
            200);
    assertThat(kinshasaEve.get("asOf").asText()).isEqualTo(eve.toString());

    // 23:30 UTC on the eve: midnight has passed in Kinshasa too.
    clock.set(eve.atTime(23, 30).toInstant(ZoneOffset.UTC));
    assertThat(
            reason(
                w.problem(
                    preview(
                        w.admin,
                        proposal(w.site(), eve, eve, List.of(shift("a", eve, 8, 9, null)))),
                    422,
                    "WORK_SCHEDULE_PERIOD_INVALID")))
        .isEqualTo("PAST");

    // Publication applies the same rule: a Kinshasa eve previewed before Kinshasa's midnight is
    // refused once it has passed there.
    clock.set(eve.atTime(22, 50).toInstant(ZoneOffset.UTC));
    Map<String, Object> late = proposal(w.site(), eve, eve, List.of(shift("a", eve, 20, 22, null)));
    String digest = w.digest(late);
    clock.set(eve.atTime(23, 10).toInstant(ZoneOffset.UTC));
    assertThat(
            reason(
                w.problem(
                    publish(w.admin, Organizations.newKey(), late, digest),
                    422,
                    "WORK_SCHEDULE_PERIOD_INVALID")))
        .isEqualTo("PAST");
    assertThat(w.rows()).containsExactly(0, 0);
  }

  @Test
  void aChangedSiteTimeZoneMakesTheReviewedPreviewStale() throws Exception {
    Goma g = goma();
    ScheduleWorld w = g.w();
    LocalDate d = w.today.plusDays(7);
    Map<String, Object> proposal =
        proposal(g.site(), d, d, List.of(shift("a", d, 8, 16, null), shift("b", d, 16, 23, null)));
    String digest = w.digest(proposal);
    // No API changes a site's time zone (a site is created with it); a direct change still
    // invalidates every preview reviewed under the old clock.
    jdbc.update(
        "UPDATE tenant.site SET timezone = ? WHERE tenant_id = ? AND id = ?",
        KINSHASA,
        w.tenant(),
        g.site());
    w.problem(
        publish(w.admin, Organizations.newKey(), proposal, digest),
        409,
        "WORK_SCHEDULE_PREVIEW_STALE");
    assertThat(w.rows()).containsExactly(0, 0);
    // Re-reviewed under the new clock, it publishes with the new snapshot.
    String id = w.published(proposal);
    assertThat(
            jdbc.queryForObject(
                "SELECT site_timezone FROM people.work_schedule WHERE tenant_id = ? AND id ="
                    + " ?::uuid",
                String.class,
                w.tenant(),
                id))
        .isEqualTo(KINSHASA);
  }

  @Test
  void anEmployeeSeesEachShiftInItsOwnScheduleSiteTimeZone() throws Exception {
    Goma g = goma();
    ScheduleWorld w = g.w();
    Person ana = w.person("Ana", "Bahati");
    LocalDate d = w.today.plusDays(3);
    w.published(proposal(w.site(), d, d, List.of(shift("k", d, 8, 16, ana.employee()))));
    w.expect(w.change(ana.employee(), w.placement(g.site(), g.department(), d.plusDays(2))), 201);
    LocalDate later = d.plusDays(3);
    w.published(
        proposal(g.site(), later, later, List.of(shift("g", later, 8, 16, ana.employee()))));

    JsonNode mine = w.expect(get(MINE).header("Authorization", ana.bearer()), 200);
    assertThat(mine.get("items")).hasSize(2);
    JsonNode inKinshasa = mine.get("items").get(0);
    JsonNode inGoma = mine.get("items").get(1);
    assertThat(inKinshasa.get("site").get("id").asText()).isEqualTo(w.site().toString());
    assertThat(inKinshasa.get("siteTimezone").asText()).isEqualTo(KINSHASA);
    assertThat(inKinshasa.get("start").asText()).isEqualTo(d + "T08:00");
    assertThat(inGoma.get("site").get("id").asText()).isEqualTo(g.site().toString());
    assertThat(inGoma.get("siteTimezone").asText()).isEqualTo(GOMA);
    // Shown as published, in Goma's own time: never converted to the organization's zone.
    assertThat(inGoma.get("start").asText()).isEqualTo(later + "T08:00");
    assertThat(inGoma.get("end").asText()).isEqualTo(later + "T16:00");
  }

  // ------------------------------------------------------------------------------------------
  // R100-2: the default "My schedule" view is instant-based
  // ------------------------------------------------------------------------------------------

  private static List<String> starts(JsonNode page) {
    List<String> starts = new java.util.ArrayList<>();
    page.get("items")
        .forEach(
            item ->
                starts.add(item.get("start").asText() + "@" + item.get("siteTimezone").asText()));
    return starts;
  }

  @Test
  void theDefaultViewShowsShiftsNotYetEndedAtOnePinnedServerInstant() throws Exception {
    Goma g = goma();
    ScheduleWorld w = g.w();
    Person ana = w.person("Ana", "Mwamba");
    LocalDate d = w.today.plusDays(5);
    // The site (Kinshasa, UTC+1): an ended day shift, an overnight shift and a next-day shift.
    w.published(
        proposal(
            w.site(),
            d,
            d.plusDays(1),
            List.of(
                shift("day", d, 8, 16, ana.employee()),
                ScheduleWorld.slot(
                    "night",
                    d + "T22:00",
                    d.plusDays(1) + "T06:00",
                    "Night watch",
                    "Veille de nuit",
                    ana.employee()),
                shift("next", d.plusDays(1), 8, 12, ana.employee()))));
    // Later at Goma (UTC+2), in Goma's own time.
    w.expect(w.change(ana.employee(), w.placement(g.site(), g.department(), d.plusDays(2))), 201);
    LocalDate later = d.plusDays(3);
    w.published(
        proposal(g.site(), later, later, List.of(shift("goma", later, 8, 16, ana.employee()))));
    // The organization runs on Goma's clock: one calendar day ahead of the Kinshasa site tonight.
    jdbc.update("UPDATE tenant.organization SET timezone = ? WHERE id = ?", GOMA, w.tenant());

    // 22:30 UTC: 23:30 on d at the Kinshasa site, already 00:30 on d + 1 for the organization.
    Instant anchor = d.atTime(22, 30).toInstant(ZoneOffset.UTC);
    clock.set(anchor);
    JsonNode all = w.expect(get(MINE).header("Authorization", ana.bearer()), 200);
    assertThat(all.get("asOfInstant").asText()).isEqualTo(anchor.toString());
    assertThat(all.get("from").isNull()).isTrue();
    // The overnight shift in progress at the site stays; the ended day shift does not.
    assertThat(starts(all))
        .containsExactly(
            d + "T22:00@" + KINSHASA,
            d.plusDays(1) + "T08:00@" + KINSHASA,
            later + "T08:00@" + GOMA);
    assertThat(all.get("items").get(2).get("end").asText()).isEqualTo(later + "T16:00");

    // Pages keep the first page's instant: the clock passing the next-day shift's end between
    // pages neither drops it nor adds anything.
    JsonNode first = w.expect(get(MINE + "?limit=1").header("Authorization", ana.bearer()), 200);
    assertThat(starts(first)).containsExactly(d + "T22:00@" + KINSHASA);
    String cursor = first.get("nextCursor").asText();
    clock.set(d.plusDays(1).atTime(11, 30).toInstant(ZoneOffset.UTC));
    JsonNode second =
        w.expect(
            get(MINE + "?limit=1&cursor=" + cursor).header("Authorization", ana.bearer()), 200);
    assertThat(second.get("asOfInstant").asText()).isEqualTo(anchor.toString());
    assertThat(starts(second)).containsExactly(d.plusDays(1) + "T08:00@" + KINSHASA);
    JsonNode third =
        w.expect(
            get(MINE + "?limit=1&cursor=" + second.get("nextCursor").asText())
                .header("Authorization", ana.bearer()),
            200);
    assertThat(starts(third)).containsExactly(later + "T08:00@" + GOMA);
    assertThat(third.get("nextCursor").isNull()).isTrue();
    // A new view at the new instant no longer holds the ended shifts.
    JsonNode now = w.expect(get(MINE).header("Authorization", ana.bearer()), 200);
    assertThat(starts(now)).containsExactly(later + "T08:00@" + GOMA);

    // Explicit dates keep the local-start-date view in each schedule's own zone, ended or not.
    JsonNode dated =
        w.expect(
            get(MINE + "?from=" + d + "&to=" + d + "&limit=1")
                .header("Authorization", ana.bearer()),
            200);
    assertThat(dated.get("asOfInstant").isNull()).isTrue();
    assertThat(dated.get("from").asText()).isEqualTo(d.toString());
    assertThat(starts(dated)).containsExactly(d + "T08:00@" + KINSHASA);
    String datedCursor = dated.get("nextCursor").asText();
    assertThat(
            starts(
                w.expect(
                    get(MINE + "?from=" + d + "&to=" + d + "&limit=1&cursor=" + datedCursor)
                        .header("Authorization", ana.bearer()),
                    200)))
        .containsExactly(d + "T22:00@" + KINSHASA);

    // A cursor of one view is refused in the other, and a tampered one anywhere.
    String current = first.get("nextCursor").asText();
    w.problem(
        get(MINE + "?limit=1&cursor=" + datedCursor).header("Authorization", ana.bearer()),
        400,
        "CURSOR_INVALID");
    w.problem(
        get(MINE + "?from=" + d + "&to=" + d + "&limit=1&cursor=" + current)
            .header("Authorization", ana.bearer()),
        400,
        "CURSOR_INVALID");
    String tampered =
        current.substring(0, 5) + (current.charAt(5) == 'A' ? 'B' : 'A') + current.substring(6);
    w.problem(
        get(MINE + "?limit=1&cursor=" + tampered).header("Authorization", ana.bearer()),
        400,
        "CURSOR_INVALID");
    w.problem(
        get(MINE + "?limit=2&cursor=" + current).header("Authorization", ana.bearer()),
        400,
        "CURSOR_INVALID");
  }
}
