package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.MINE;
import static com.divalhr.core.people.schedule.ScheduleWorld.SCHEDULES;
import static com.divalhr.core.people.schedule.ScheduleWorld.cancel;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.replace;
import static com.divalhr.core.people.schedule.ScheduleWorld.replacementPreview;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.ScheduleWorld.slot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * MVP-050B end to end over the public API against real PostgreSQL: a cancellation releases the site
 * dates and employee instants atomically, keeps the evidence and hides the shifts, and is replayed
 * exactly; a replacement keeps the original, activates only its successor, chains A, B, C without
 * branch, and is refused once stale; the change window closes exactly when the earliest slot
 * begins; tenant isolation, exact MFA and membership; the derived state, links and reason in the
 * administrators' reads only; and the reason, labels and names absent from logs, audit metadata and
 * events.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class WorkScheduleLifecycleIntegrationTest {

  private static final String CANCEL_REASON = "Fermeture exceptionnelle du site";
  private static final String REPLACE_REASON = "Réorganisation des équipes";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  private ScheduleWorld world() throws Exception {
    return new ScheduleWorld(mvc, jdbc, lookups);
  }

  private static String logs(CapturedOutput output) {
    return String.join(
        "\n", output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp")).toList());
  }

  private JsonNode detail(ScheduleWorld w, String id) throws Exception {
    return w.expect(get(SCHEDULES + "/" + id).header("Authorization", w.admin), 200);
  }

  private JsonNode mine(ScheduleWorld w, Person person, String query) throws Exception {
    return w.expect(get(MINE + query).header("Authorization", person.bearer()), 200);
  }

  private static List<String> starts(JsonNode page) {
    List<String> starts = new ArrayList<>();
    page.get("items").forEach(item -> starts.add(item.get("start").asText()));
    return starts;
  }

  private List<String> listed(ScheduleWorld w, String query) throws Exception {
    List<String> ids = new ArrayList<>();
    w.expect(get(SCHEDULES + query).header("Authorization", w.admin), 200)
        .get("items")
        .forEach(item -> ids.add(item.get("id").asText()));
    return ids;
  }

  private JsonNode metadata(String action, String resource) throws Exception {
    return JSON.readTree(
        jdbc.queryForObject(
            "SELECT metadata::text FROM platform.audit_event WHERE action = ?"
                + " AND resource_id = ?::uuid",
            String.class,
            action,
            resource));
  }

  private JsonNode event(String type, String subject) throws Exception {
    return JSON.readTree(
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'eventType' = ?"
                + " AND envelope ->> 'subject' = ?",
            String.class,
            type,
            subject));
  }

  private static List<String> fields(JsonNode problem) {
    List<String> fields = new ArrayList<>();
    problem
        .path("params")
        .path("fields")
        .forEach(f -> fields.add(f.get("field").asText() + ":" + f.get("constraint").asText()));
    return fields;
  }

  // ------------------------------------------------------------------------------------------
  // Cancellation
  // ------------------------------------------------------------------------------------------

  @Test
  void aCancellationReleasesCoverageKeepsTheEvidenceAndHidesTheShifts(CapturedOutput output)
      throws Exception {
    ScheduleWorld w = world();
    Person ana = w.person("Ana", "Kalala");
    LocalDate d = w.today.plusDays(5);
    String a =
        w.published(
            proposal(
                w.site(),
                d,
                d.plusDays(1),
                List.of(
                    slot("a1", d + "T08:00", d + "T16:00", "Front desk", "Accueil", ana.employee()),
                    shift("u1", d, 22, 23, null))));
    assertThat(starts(mine(w, ana, ""))).containsExactly(d + "T08:00");

    // Shape: the key, the reason and the confirmation; nothing echoed.
    w.problem(cancel(w.admin, null, a, CANCEL_REASON), 400, "VALIDATION_FAILED");
    assertThat(
            fields(
                w.problem(
                    cancel(w.admin, Organizations.newKey(), a, "   "), 400, "VALIDATION_FAILED")))
        .containsExactly("reason:REQUIRED");
    w.problem(
        cancel(w.admin, Organizations.newKey(), UUID.randomUUID().toString(), CANCEL_REASON),
        404,
        "WORK_SCHEDULE_NOT_FOUND");
    w.problem(
        cancel(w.admin, Organizations.newKey(), "not-a-uuid", CANCEL_REASON),
        404,
        "WORK_SCHEDULE_NOT_FOUND");

    String key = Organizations.newKey();
    MockHttpServletResponse cancelled = w.call(cancel(w.admin, key, a, CANCEL_REASON));
    assertThat(cancelled.getStatus()).isEqualTo(200);
    JsonNode receipt = JSON.readTree(cancelled.getContentAsString());
    assertThat(receipt.get("scheduleId").asText()).isEqualTo(a);
    assertThat(receipt.get("status").asText()).isEqualTo("CANCELLED");
    assertThat(receipt.get("replacementScheduleId").isNull()).isTrue();
    assertThat(cancelled.getContentAsString()).doesNotContain(CANCEL_REASON);
    // Exact replay; a changed body under the same key is refused.
    MockHttpServletResponse replay = w.call(cancel(w.admin, key, a, CANCEL_REASON));
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(cancelled.getContentAsString());
    w.problem(cancel(w.admin, key, a, "Une autre raison"), 409, "IDEMPOTENCY_KEY_REUSED");
    // One terminal action.
    w.problem(
        cancel(w.admin, Organizations.newKey(), a, CANCEL_REASON),
        409,
        "WORK_SCHEDULE_ALREADY_CANCELLED");
    w.problem(
        replacementPreview(w.admin, a, List.of(shift("x", d, 9, 10, null)), REPLACE_REASON),
        409,
        "WORK_SCHEDULE_ALREADY_CANCELLED");

    // The evidence stays; the state, instant and reason are derived for administrators.
    JsonNode found = detail(w, a);
    assertThat(found.get("status").asText()).isEqualTo("CANCELLED");
    assertThat(found.get("changeReason").asText()).isEqualTo(CANCEL_REASON);
    assertThat(Instant.parse(found.get("changedAt").asText()))
        .isEqualTo(Instant.parse(receipt.get("changedAt").asText()));
    assertThat(found.get("slots")).hasSize(2);
    assertThat(found.has("cancelledBy")).isFalse();
    assertThat(found.toString()).doesNotContain(w.adminSubject);
    assertThat(listed(w, "?status=CANCELLED")).containsExactly(a);
    assertThat(listed(w, "?status=PUBLISHED")).isEmpty();
    assertThat(
            fields(
                w.problem(
                    get(SCHEDULES + "?status=DRAFT").header("Authorization", w.admin),
                    400,
                    "VALIDATION_FAILED")))
        .containsExactly("status:FORMAT");

    // The employee no longer sees it, in either view.
    assertThat(mine(w, ana, "").get("items")).isEmpty();
    assertThat(mine(w, ana, "?from=" + d + "&to=" + d.plusDays(1)).get("items")).isEmpty();

    // The site dates and Ana's instants are free again.
    String b =
        w.published(
            proposal(
                w.site(),
                d,
                d,
                List.of(
                    slot(
                        "b1",
                        d + "T08:00",
                        d + "T16:00",
                        "Front desk",
                        "Accueil",
                        ana.employee()))));
    assertThat(starts(mine(w, ana, ""))).containsExactly(d + "T08:00");
    assertThat(mine(w, ana, "").get("items").get(0).get("scheduleId").asText()).isEqualTo(b);

    // Evidence and privacy: IDs, dates and counts only.
    JsonNode audited = metadata("work-schedule.cancel", a);
    assertThat(audited.get("scheduleId").asText()).isEqualTo(a);
    assertThat(audited.get("slotCount").asInt()).isEqualTo(2);
    JsonNode sent = event("people.work-schedule.cancelled.v1", a);
    assertThat(sent.get("data").get("assignedCount").asInt()).isEqualTo(1);
    String evidence = audited.toString() + sent.toString();
    assertThat(evidence).doesNotContain(CANCEL_REASON, "Accueil", "Kalala", w.adminSubject);
    assertThat(logs(output)).doesNotContain(CANCEL_REASON, "Fermeture", "Kalala", "Accueil");
    assertThat(w.rows()).containsExactly(2, 3);
  }

  // ------------------------------------------------------------------------------------------
  // Replacement
  // ------------------------------------------------------------------------------------------

  @Test
  void aReplacementKeepsTheOriginalActivatesOnlyItsSuccessorAndChains(CapturedOutput output)
      throws Exception {
    ScheduleWorld w = world();
    Person ana = w.person("Ana", "Ngoy");
    Person ben = w.person("Ben", "Ilunga");
    LocalDate d = w.today.plusDays(6);
    String a =
        w.published(
            proposal(
                w.site(),
                d,
                d,
                List.of(shift("a1", d, 8, 16, ana.employee()), shift("u1", d, 17, 22, null))));
    // The replacement may reuse Ana's own instants (the original's coverage is set aside).
    List<Map<String, Object>> second =
        List.of(shift("b1", d, 9, 17, ana.employee()), shift("b2", d, 8, 12, ben.employee()));
    JsonNode preview = w.expect(replacementPreview(w.admin, a, second, REPLACE_REASON), 200);
    assertThat(preview.get("publishable").asBoolean()).isTrue();
    assertThat(preview.get("site").get("id").asText()).isEqualTo(w.site().toString());
    assertThat(preview.get("firstDate").asText()).isEqualTo(d.toString());
    assertThat(preview.get("totals").get("assigned").asInt()).isEqualTo(2);
    String digest = preview.get("previewDigest").asText();
    // The site and period are the original's: they cannot be sent.
    Map<String, Object> withSite = new LinkedHashMap<>();
    withSite.put("slots", second);
    withSite.put("reason", REPLACE_REASON);
    withSite.put("siteId", w.otherSite.toString());
    assertThat(
            fields(
                w.problem(
                    Employees.postJson(
                        w.admin,
                        SCHEDULES + "/" + a + "/replacement-preview",
                        JSON.writeValueAsString(withSite)),
                    400,
                    "VALIDATION_FAILED")))
        .containsExactly("body:UNKNOWN_PROPERTY");
    // A digest of another reason is stale; nothing changes.
    w.problem(
        replace(w.admin, Organizations.newKey(), a, second, "Autre raison", digest),
        409,
        "WORK_SCHEDULE_PREVIEW_STALE");
    assertThat(w.status(a)).isEqualTo("PUBLISHED");

    MockHttpServletResponse done =
        w.call(replace(w.admin, Organizations.newKey(), a, second, REPLACE_REASON, digest));
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(201);
    JsonNode receipt = JSON.readTree(done.getContentAsString());
    String b = receipt.get("replacementScheduleId").asText();
    assertThat(receipt.get("status").asText()).isEqualTo("REPLACED");
    assertThat(receipt.get("totals").toString())
        .isEqualTo("{\"slots\":2,\"assigned\":2,\"uncovered\":0}");

    JsonNode original = detail(w, a);
    assertThat(original.get("status").asText()).isEqualTo("REPLACED");
    assertThat(original.get("replacedByScheduleId").asText()).isEqualTo(b);
    assertThat(original.get("changeReason").asText()).isEqualTo(REPLACE_REASON);
    assertThat(original.get("slots")).hasSize(2);
    JsonNode successor = detail(w, b);
    assertThat(successor.get("status").asText()).isEqualTo("PUBLISHED");
    assertThat(successor.get("replacesScheduleId").asText()).isEqualTo(a);
    assertThat(successor.get("changeReason").isNull()).isTrue();
    assertThat(successor.get("firstDate").asText()).isEqualTo(d.toString());
    // Employees see only the successor, never both.
    assertThat(starts(mine(w, ana, ""))).containsExactly(d + "T09:00");
    assertThat(starts(mine(w, ana, "?from=" + d + "&to=" + d))).containsExactly(d + "T09:00");
    assertThat(starts(mine(w, ben, ""))).containsExactly(d + "T08:00");
    // The original has its terminal action.
    w.problem(
        replace(w.admin, Organizations.newKey(), a, second, REPLACE_REASON, digest),
        409,
        "WORK_SCHEDULE_ALREADY_REPLACED");
    w.problem(
        cancel(w.admin, Organizations.newKey(), a, CANCEL_REASON),
        409,
        "WORK_SCHEDULE_ALREADY_REPLACED");
    // The successor still holds the site date.
    w.problem(
        ScheduleWorld.preview(
            w.admin, proposal(w.site(), d, d, List.of(shift("z", d, 8, 9, null)))),
        409,
        "WORK_SCHEDULE_OVERLAP");

    // B -> C: the chain continues; employees follow the current schedule only.
    String c = w.replaced(b, List.of(shift("c1", d, 10, 14, null)), "Effectif réduit");
    assertThat(w.status(b)).isEqualTo("REPLACED");
    assertThat(detail(w, c).get("replacesScheduleId").asText()).isEqualTo(b);
    assertThat(detail(w, b).get("replacesScheduleId").asText()).isEqualTo(a);
    assertThat(mine(w, ana, "").get("items")).isEmpty();
    assertThat(mine(w, ben, "?from=" + d + "&to=" + d).get("items")).isEmpty();
    assertThat(listed(w, "?siteId=" + w.site() + "&status=PUBLISHED")).containsExactly(c);
    assertThat(listed(w, "?siteId=" + w.site() + "&status=REPLACED")).containsOnly(a, b);
    assertThat(w.rows()).containsExactly(3, 5);

    // Evidence and privacy.
    JsonNode audited = metadata("work-schedule.replace", a);
    assertThat(audited.get("replacementScheduleId").asText()).isEqualTo(b);
    JsonNode sent = event("people.work-schedule.replaced.v1", a);
    assertThat(sent.get("data").get("replacementScheduleId").asText()).isEqualTo(b);
    String evidence = audited.toString() + sent.toString();
    assertThat(evidence).doesNotContain(REPLACE_REASON, "Ngoy", "Ilunga", w.adminSubject);
    assertThat(logs(output))
        .doesNotContain(REPLACE_REASON, "Réorganisation", "Effectif", "Ngoy", "Ilunga");
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.audit_event"
                    + " WHERE action = 'work-schedule.replacement-preview' AND tenant_id = ?",
                w.tenant()))
        .isEqualTo(2);
  }

  // ------------------------------------------------------------------------------------------
  // The change window
  // ------------------------------------------------------------------------------------------

  @Test
  void theWindowClosesExactlyWhenTheEarliestSlotBegins() throws Exception {
    ScheduleWorld w = world();
    LocalDate d = w.today.plusDays(9);
    String a = w.published(proposal(w.site(), d, d, List.of(shift("a1", d, 8, 12, null))));
    Instant start = d.atTime(8, 0).atZone(ZoneId.of("Africa/Kinshasa")).toInstant();

    clock.set(start);
    w.problem(
        cancel(w.admin, Organizations.newKey(), a, CANCEL_REASON),
        409,
        "WORK_SCHEDULE_CHANGE_WINDOW_CLOSED");
    w.problem(
        replacementPreview(w.admin, a, List.of(shift("x", d, 9, 10, null)), REPLACE_REASON),
        409,
        "WORK_SCHEDULE_CHANGE_WINDOW_CLOSED");

    // One hour earlier the window is open, and a proposed slot must start after that instant.
    clock.set(start.minusSeconds(3600));
    JsonNode preview =
        w.expect(
            replacementPreview(
                w.admin,
                a,
                List.of(shift("early", d, 7, 9, null), shift("late", d, 9, 10, null)),
                REPLACE_REASON),
            200);
    assertThat(preview.get("slots").get(0).get("issue").get("reason").asText())
        .isEqualTo("NOT_FUTURE");
    assertThat(preview.get("slots").get(1).get("issue").isNull()).isTrue();

    // A preview never holds the window open: the mutation checks it again.
    String digest = w.replacementDigest(a, List.of(shift("late", d, 9, 10, null)), REPLACE_REASON);
    clock.set(start);
    w.problem(
        replace(
            w.admin,
            Organizations.newKey(),
            a,
            List.of(shift("late", d, 9, 10, null)),
            REPLACE_REASON,
            digest),
        409,
        "WORK_SCHEDULE_CHANGE_WINDOW_CLOSED");

    clock.set(start.minusNanos(1_000));
    w.expect(cancel(w.admin, Organizations.newKey(), a, CANCEL_REASON), 200);
    assertThat(w.status(a)).isEqualTo("CANCELLED");
  }

  @Test
  void aScheduleWhosePeriodBeganIsReplaceableWhileEverySlotIsStillFuture() throws Exception {
    // R102-1: Monday to Friday, the first slot on Thursday, replaced on Tuesday.
    ScheduleWorld w = world();
    Person ana = w.person("Ana", "Ngoy");
    Person ben = w.person("Ben", "Ilunga");
    LocalDate monday = w.today.plusDays(4);
    LocalDate thursday = monday.plusDays(3);
    LocalDate friday = monday.plusDays(4);
    String a =
        w.published(
            proposal(
                w.site(),
                monday,
                friday,
                List.of(
                    shift("a1", thursday, 8, 16, ana.employee()),
                    shift("u1", friday, 8, 16, null))));

    clock.set(monday.plusDays(1).atTime(10, 0).atZone(ZoneId.of("Africa/Kinshasa")).toInstant());
    // The period began before the site's date, so a new publication of it is refused...
    assertThat(
            w.problem(
                    ScheduleWorld.preview(
                        w.admin,
                        proposal(
                            w.otherSite, monday, friday, List.of(shift("n", friday, 8, 9, null)))),
                    422,
                    "WORK_SCHEDULE_PERIOD_INVALID")
                .get("params")
                .get("reason")
                .asText())
        .isEqualTo("PAST");
    // ...but the original is still replaceable: every one of its slots starts later.
    List<Map<String, Object>> replacement =
        List.of(
            shift("b1", thursday, 8, 16, ben.employee()),
            shift("b2", friday, 9, 17, ana.employee()));
    JsonNode preview = w.expect(replacementPreview(w.admin, a, replacement, REPLACE_REASON), 200);
    assertThat(preview.get("publishable").asBoolean()).isTrue();
    assertThat(preview.get("firstDate").asText()).isEqualTo(monday.toString());
    assertThat(preview.get("lastDate").asText()).isEqualTo(friday.toString());
    MockHttpServletResponse done =
        w.call(
            replace(
                w.admin,
                Organizations.newKey(),
                a,
                replacement,
                REPLACE_REASON,
                preview.get("previewDigest").asText()));
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(201);
    String b = JSON.readTree(done.getContentAsString()).get("replacementScheduleId").asText();

    assertThat(w.status(a)).isEqualTo("REPLACED");
    JsonNode successor = detail(w, b);
    assertThat(successor.get("status").asText()).isEqualTo("PUBLISHED");
    assertThat(successor.get("replacesScheduleId").asText()).isEqualTo(a);
    assertThat(successor.get("firstDate").asText()).isEqualTo(monday.toString());
    // Employees see only the successor.
    assertThat(starts(mine(w, ana, ""))).containsExactly(friday + "T09:00");
    assertThat(starts(mine(w, ben, ""))).containsExactly(thursday + "T08:00");
  }

  // ------------------------------------------------------------------------------------------
  // Tenant isolation, MFA and membership
  // ------------------------------------------------------------------------------------------

  @Test
  void tenantsMfaMembershipAndRolesFailClosed() throws Exception {
    ScheduleWorld w = world();
    ScheduleWorld other = world();
    Person ana = w.person("Ana", "Mukendi");
    LocalDate d = w.today.plusDays(7);
    String a = w.published(proposal(w.site(), d, d, List.of(shift("a1", d, 8, 12, null))));
    List<Map<String, Object>> slots = List.of(shift("b1", d, 9, 12, null));
    // Another tenant's administrator: the same 404 as an unknown schedule.
    other.problem(
        cancel(other.admin, Organizations.newKey(), a, CANCEL_REASON),
        404,
        "WORK_SCHEDULE_NOT_FOUND");
    other.problem(
        replacementPreview(other.admin, a, slots, REPLACE_REASON), 404, "WORK_SCHEDULE_NOT_FOUND");
    other.problem(
        replace(other.admin, Organizations.newKey(), a, slots, REPLACE_REASON, "0".repeat(64)),
        404,
        "WORK_SCHEDULE_NOT_FOUND");
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant())
                .subject("sub-sched-pwd-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    String noMembership =
        "Bearer "
            + TestTokens.token()
                .tenant(w.tenant())
                .subject("sub-sched-nomember-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .build();
    for (String bearer : List.of(ana.bearer(), passwordOnly, noMembership)) {
      assertThat(w.call(cancel(bearer, Organizations.newKey(), a, CANCEL_REASON)).getStatus())
          .isIn(401, 403);
      assertThat(w.call(replacementPreview(bearer, a, slots, REPLACE_REASON)).getStatus())
          .isIn(401, 403);
      assertThat(
              w.call(
                      replace(
                          bearer, Organizations.newKey(), a, slots, REPLACE_REASON, "0".repeat(64)))
                  .getStatus())
          .isIn(401, 403);
    }
    assertThat(
            w.call(cancel(passwordOnly, Organizations.newKey(), a, CANCEL_REASON))
                .getContentAsString())
        .contains("MFA_REQUIRED");
    assertThat(w.status(a)).isEqualTo("PUBLISHED");
    // Employees never receive the state, links or reason.
    String own = w.call(get(MINE).header("Authorization", ana.bearer())).getContentAsString();
    assertThat(own).doesNotContain("status", "changeReason", "replaces");
  }
}
