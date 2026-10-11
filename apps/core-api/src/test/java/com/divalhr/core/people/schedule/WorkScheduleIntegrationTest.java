package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.MINE;
import static com.divalhr.core.people.schedule.ScheduleWorld.SCHEDULES;
import static com.divalhr.core.people.schedule.ScheduleWorld.preview;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.publish;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.ScheduleWorld.slot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-050A end to end over the public API against real PostgreSQL: bilingual preview normalization
 * and publication of a mixed assigned/uncovered schedule with its evidence, exact replay and
 * changed-body conflict; stale previews after placement, employment, separation and site changes;
 * the period, site, limit, slot, eligibility and conflict rules; the overnight shift; tenant
 * isolation, exact MFA and membership; the administrators' list and its cursor binding; the
 * employee's own view and its privacy; and labels, names and numbers absent from logs, audit
 * metadata, events and errors.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class WorkScheduleIntegrationTest {

  private static final String EN_LABEL = "Front desk (morning)";
  private static final String FR_LABEL = "Accueil - équipe du matin";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;

  private ScheduleWorld world() throws Exception {
    return new ScheduleWorld(mvc, jdbc, lookups);
  }

  private static String logs(CapturedOutput output) {
    return String.join(
        "\n", output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp\"")).toList());
  }

  private JsonNode metadata(String action, Object resource) throws Exception {
    return JSON.readTree(
        jdbc.queryForObject(
            "SELECT metadata::text FROM platform.audit_event WHERE action = ?"
                + " AND resource_id = ?::uuid",
            String.class,
            action,
            resource.toString()));
  }

  private JsonNode event(String type, Object subject) throws Exception {
    return JSON.readTree(
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'eventType' = ?"
                + " AND envelope ->> 'subject' = ?",
            String.class,
            type,
            subject.toString()));
  }

  private static List<String> fields(JsonNode problem) {
    List<String> fields = new ArrayList<>();
    problem
        .path("params")
        .path("fields")
        .forEach(f -> fields.add(f.get("field").asText() + ":" + f.get("constraint").asText()));
    return fields;
  }

  private static MockHttpServletRequestBuilder list(String bearer, String query) {
    return get(SCHEDULES + query).header("Authorization", bearer);
  }

  private static MockHttpServletRequestBuilder detail(String bearer, String id) {
    return get(SCHEDULES + "/" + id).header("Authorization", bearer);
  }

  private static MockHttpServletRequestBuilder mine(String bearer, String query) {
    return get(MINE + query).header("Authorization", bearer);
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.get("items").forEach(item -> ids.add(item.get("id").asText()));
    return ids;
  }

  // ------------------------------------------------------------------------------------------
  // Preview and publication
  // ------------------------------------------------------------------------------------------

  @Test
  void anAdministratorPreviewsAndPublishesAMixedBilingualSchedule(CapturedOutput output)
      throws Exception {
    ScheduleWorld w = world();
    Person ana = w.person("Ana", "Kabeya");
    Person ben = w.person("Ben", "Lukusa");
    LocalDate d = w.today.plusDays(5);
    Map<String, Object> proposal =
        proposal(
            w.site(),
            d,
            d.plusDays(2),
            List.of(
                slot(
                    "a",
                    d + "T08:00",
                    d + "T16:00",
                    "  " + EN_LABEL + " ",
                    FR_LABEL,
                    ana.employee()),
                slot(
                    "b",
                    d + "T22:00",
                    d.plusDays(1) + "T06:00",
                    "Night   watch",
                    "Veille de nuit",
                    ben.employee()),
                slot(
                    "c",
                    d.plusDays(1) + "T08:00",
                    d.plusDays(1) + "T16:00",
                    "Cashier",
                    "Caissière",
                    null),
                slot(
                    "d",
                    d.plusDays(1) + "T08:00",
                    d.plusDays(1) + "T16:00",
                    "Cashier",
                    "Caissière",
                    null)));
    JsonNode preview = w.expect(preview(w.admin, proposal), 200);
    assertThat(preview.get("asOf").asText()).isEqualTo(w.today.toString());
    assertThat(preview.get("timezone").asText()).isEqualTo("Africa/Kinshasa");
    assertThat(preview.get("site").get("id").asText()).isEqualTo(w.site().toString());
    assertThat(preview.get("site").get("code").asText()).isEqualTo(w.org.site());
    assertThat(preview.get("publishable").asBoolean()).isTrue();
    assertThat(preview.get("totals").toString())
        .isEqualTo("{\"slots\":4,\"assigned\":2,\"uncovered\":2}");
    assertThat(preview.get("warnings").toString()).isEqualTo("[\"UNCOVERED_SLOTS\"]");
    assertThat(preview.get("previewDigest").asText()).matches("[0-9a-f]{64}");
    JsonNode first = preview.get("slots").get(0);
    // Labels are trimmed with spaces collapsed; instants are the Kinshasa local times.
    assertThat(first.get("labels").get("en").asText()).isEqualTo(EN_LABEL);
    assertThat(first.get("labels").get("fr").asText()).isEqualTo(FR_LABEL);
    assertThat(first.get("startAt").asText()).isEqualTo(d + "T07:00:00Z");
    assertThat(first.get("employee").get("employeeNumber").asText()).isEqualTo(ana.number());
    assertThat(first.get("issue").isNull()).isTrue();
    JsonNode night = preview.get("slots").get(1);
    assertThat(night.get("labels").get("en").asText()).isEqualTo("Night watch");
    assertThat(night.get("overnight").asBoolean()).isTrue();
    assertThat(night.get("endAt").asText()).isEqualTo(d.plusDays(1) + "T05:00:00Z");
    assertThat(preview.get("slots").get(2).get("employee").isNull()).isTrue();
    // A preview stores nothing but its disclosure.
    assertThat(w.rows()).containsExactly(0, 0);

    String digest = preview.get("previewDigest").asText();
    String key = Organizations.newKey();
    var published = w.call(publish(w.admin, key, proposal, digest));
    assertThat(published.getStatus()).isEqualTo(201);
    assertThat(published.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode receipt = JSON.readTree(published.getContentAsString());
    String id = receipt.get("scheduleId").asText();
    assertThat(receipt.get("totals").toString())
        .isEqualTo("{\"slots\":4,\"assigned\":2,\"uncovered\":2}");
    assertThat(w.rows()).containsExactly(1, 4);
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule WHERE id = ?::uuid AND site_id = ?"
                    + " AND first_date = ? AND last_date = ? AND timezone = 'Africa/Kinshasa'"
                    + " AND slot_count = 4 AND assigned_count = 2 AND published_by = ?",
                id,
                w.site(),
                d,
                d.plusDays(2),
                w.adminSubject))
        .isEqualTo(1);

    // Exact replay returns the original receipt; a changed body under the key conflicts first.
    var replay = w.call(publish(w.admin, key, proposal, digest));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(receipt);
    Map<String, Object> changed = new LinkedHashMap<>(proposal);
    changed.put("lastDate", d.plusDays(3).toString());
    w.problem(publish(w.admin, key, changed, digest), 409, "IDEMPOTENCY_KEY_REUSED");
    assertThat(w.rows()).containsExactly(1, 4);

    // Evidence: IDs, site, dates and counts only.
    JsonNode audit = metadata("work-schedule.publish", id);
    assertThat(audit.get("slotCount").asInt()).isEqualTo(4);
    assertThat(audit.get("assignedCount").asInt()).isEqualTo(2);
    assertThat(audit.get("uncoveredCount").asInt()).isEqualTo(2);
    assertThat(audit.get("siteId").asText()).isEqualTo(w.site().toString());
    JsonNode event = event("people.work-schedule.published.v1", id);
    assertThat(event.get("data").get("scheduleId").asText()).isEqualTo(id);
    assertThat(event.get("data").get("uncoveredCount").asInt()).isEqualTo(2);
    for (String text : List.of(audit.toString(), event.toString())) {
      assertThat(text)
          .doesNotContain("Cashier", "Caissi", "Accueil", "Night", "Front desk")
          .doesNotContain(ana.number(), "Kabeya", "Lukusa", ana.employee().toString())
          .doesNotContain(w.adminSubject);
    }

    // The administrator reads it back: slots in order, uncovered ones without an employee.
    JsonNode read = w.expect(detail(w.admin, id), 200);
    assertThat(read.get("totals").get("uncovered").asInt()).isEqualTo(2);
    assertThat(read.get("slots")).hasSize(4);
    assertThat(read.get("slots").get(0).get("labels").get("fr").asText()).isEqualTo(FR_LABEL);
    assertThat(read.get("slots").get(0).get("employee").get("familyName").asText())
        .isEqualTo("Kabeya");
    assertThat(read.get("slots").get(1).get("overnight").asBoolean()).isTrue();
    assertThat(read.get("slots").get(2).get("employee").isNull()).isTrue();
    assertThat(read.toString()).doesNotContain(w.adminSubject).doesNotContain("publishedBy");
    JsonNode page = w.expect(list(w.admin, ""), 200);
    assertThat(ids(page)).containsExactly(id);
    assertThat(page.get("items").get(0).get("site").get("code").asText()).isEqualTo(w.org.site());

    // A second schedule overlapping the site by one day is refused; an adjacent one is not.
    Map<String, Object> overlapping =
        proposal(
            w.site(), d.plusDays(2), d.plusDays(3), List.of(shift("x", d.plusDays(3), 8, 9, null)));
    w.problem(preview(w.admin, overlapping), 409, "WORK_SCHEDULE_OVERLAP");
    w.problem(
        publish(w.admin, Organizations.newKey(), overlapping, digest),
        409,
        "WORK_SCHEDULE_OVERLAP");
    w.published(
        proposal(
            w.site(),
            d.plusDays(3),
            d.plusDays(3),
            List.of(shift("y", d.plusDays(3), 8, 9, null))));
    assertThat(w.rows()).containsExactly(2, 5);

    String logs = logs(output);
    assertThat(logs).contains("work_schedule_published").contains("work_schedule_previewed");
    assertThat(logs)
        .doesNotContain(EN_LABEL, FR_LABEL, "Caissi", "Night watch")
        .doesNotContain(ana.number(), ben.number(), "Kabeya", "Lukusa")
        .doesNotContain(digest);
  }

  // ------------------------------------------------------------------------------------------
  // Stale previews
  // ------------------------------------------------------------------------------------------

  @Test
  void aPreviewIsStaleOnceAFactItBoundChanges() throws Exception {
    ScheduleWorld w = world();
    Person ana = w.person("Ana", "Mbala");
    LocalDate d = w.today.plusDays(10);

    // A placement change to another department of the same site (still eligible).
    Map<String, Object> first =
        proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, ana.employee())));
    String digest = w.digest(first);
    w.expect(
        w.change(ana.employee(), w.placement(w.site(), w.secondDepartment, d.minusDays(3))), 201);
    w.problem(
        publish(w.admin, Organizations.newKey(), first, digest),
        409,
        "WORK_SCHEDULE_PREVIEW_STALE");

    // A separation after the slot (still eligible, but the employment changed).
    digest = w.digest(first);
    w.expect(w.separate(ana.employee(), d.plusDays(20)), 201);
    w.problem(
        publish(w.admin, Organizations.newKey(), first, digest),
        409,
        "WORK_SCHEDULE_PREVIEW_STALE");

    // A change of the site's own period (still in effect on the date).
    digest = w.digest(first);
    jdbc.update(
        "UPDATE tenant.legal_entity SET effective_from = DATE '2025-01-01' WHERE id = ?",
        w.org.legalEntityId());
    jdbc.update("UPDATE tenant.site SET effective_from = DATE '2025-06-01' WHERE id = ?", w.site());
    w.problem(
        publish(w.admin, Organizations.newKey(), first, digest),
        409,
        "WORK_SCHEDULE_PREVIEW_STALE");

    // A different proposal under a digest it was not reviewed with.
    digest = w.digest(first);
    Map<String, Object> other =
        proposal(w.site(), d, d, List.of(shift("a", d, 8, 13, ana.employee())));
    w.problem(
        publish(w.admin, Organizations.newKey(), other, digest),
        409,
        "WORK_SCHEDULE_PREVIEW_STALE");
    assertThat(w.rows()).containsExactly(0, 0);

    // A placement change away from the site refuses with the slot's own reason.
    digest = w.digest(first);
    w.expect(
        w.change(ana.employee(), w.placement(w.otherSite, w.otherSiteDepartment, d.minusDays(2))),
        201);
    JsonNode refused =
        w.problem(
            publish(w.admin, Organizations.newKey(), first, digest),
            422,
            "WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE");
    assertThat(refused.get("params"))
        .isEqualTo(JSON.readTree("{\"position\":\"1\",\"reason\":\"NOT_AT_SITE\"}"));
    // With a fresh preview and a still-valid digest, it publishes.
    Map<String, Object> fresh = proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, null)));
    w.published(fresh);
    assertThat(w.rows()).containsExactly(1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // Period, site, limit and shape
  // ------------------------------------------------------------------------------------------

  @Test
  void periodSiteLimitAndShapeRulesFailClosed() throws Exception {
    ScheduleWorld w = world();
    ScheduleWorld other = world();
    LocalDate d = w.today.plusDays(3);
    List<Map<String, Object>> one = List.of(shift("a", d, 8, 9, null));
    assertThat(
            w.problem(
                    preview(w.admin, proposal(w.site(), d, d.minusDays(1), one)),
                    422,
                    "WORK_SCHEDULE_PERIOD_INVALID")
                .get("params")
                .get("reason")
                .asText())
        .isEqualTo("ORDER");
    assertThat(
            w.problem(
                    preview(w.admin, proposal(w.site(), w.today.minusDays(1), d, one)),
                    422,
                    "WORK_SCHEDULE_PERIOD_INVALID")
                .get("params")
                .get("reason")
                .asText())
        .isEqualTo("PAST");
    assertThat(
            w.problem(
                    preview(w.admin, proposal(w.site(), d, d.plusDays(31), one)),
                    422,
                    "WORK_SCHEDULE_PERIOD_INVALID")
                .get("params")
                .get("reason")
                .asText())
        .isEqualTo("TOO_LONG");
    // 31 days and today are fine.
    assertThat(
            w.expect(
                    preview(
                        w.admin,
                        proposal(
                            w.site(),
                            w.today,
                            w.today.plusDays(30),
                            List.of(shift("a", w.today.plusDays(30), 8, 9, null)))),
                    200)
                .get("publishable")
                .asBoolean())
        .isTrue();
    // Unknown, foreign and inactive sites are the same refusal.
    UUID ended =
        Hierarchy.newSite(
            mvc,
            w.tenant(),
            w.org.legalEntityId(),
            Hierarchy.code("SE"),
            "2026-01-01",
            d.plusDays(1).toString());
    for (UUID site : List.of(UUID.randomUUID(), other.site(), ended)) {
      JsonNode problem =
          w.problem(
              preview(w.admin, proposal(site, d, d.plusDays(2), one)),
              422,
              "WORK_SCHEDULE_SITE_NOT_ACTIVE");
      assertThat(problem.get("params").toString()).isEqualTo("{}");
    }
    // Slots: at least one, at most 500 (before any item is read).
    assertThat(
            fields(
                w.problem(
                    preview(w.admin, proposal(w.site(), d, d, List.of())),
                    400,
                    "VALIDATION_FAILED")))
        .containsExactly("slots:REQUIRED");
    List<Map<String, Object>> many = new ArrayList<>();
    for (int i = 0; i < 501; i++) {
      many.add(shift("s" + i, d, 8, 9, null));
    }
    assertThat(
            w.problem(
                    preview(w.admin, proposal(w.site(), d, d, many)),
                    422,
                    "WORK_SCHEDULE_LIMIT_EXCEEDED")
                .get("params")
                .toString())
        .isEqualTo("{\"maximum\":\"500\"}");
    // Shape: every problem listed, nothing echoed.
    Map<String, Object> bad = new LinkedHashMap<>();
    bad.put("clientRef", "a");
    bad.put("start", d + "T8:00");
    bad.put("end", d + "T25:00");
    bad.put("labels", Map.of("en", "<b>Lead</b>", "fr", "contact@exemple.cd", "de", "x"));
    bad.put("employeeId", "nope");
    bad.put("note", "free text");
    Map<String, Object> twin = shift("a", d, 8, 9, null);
    Map<String, Object> body = proposal(w.site(), d, d, List.of(bad, twin));
    body.put("extra", 1);
    JsonNode problem = w.problem(preview(w.admin, body), 400, "VALIDATION_FAILED");
    assertThat(fields(problem))
        .containsExactlyInAnyOrder(
            "body:UNKNOWN_PROPERTY",
            "slots[0]:UNKNOWN_PROPERTY",
            "slots[0].start:FORMAT",
            "slots[0].end:FORMAT",
            "slots[0].labels:UNKNOWN_PROPERTY",
            "slots[0].labels.en:FORMAT",
            "slots[0].labels.fr:FORMAT",
            "slots[0].employeeId:FORMAT",
            "slots[1].clientRef:DUPLICATE");
    assertThat(problem.toString()).doesNotContain("Lead", "contact@", "free text", "nope");
    // Publication needs the digest and an explicit confirmation.
    Map<String, Object> valid = proposal(w.site(), d, d, one);
    Map<String, Object> unconfirmed = new LinkedHashMap<>(valid);
    unconfirmed.put("previewDigest", "ABC");
    unconfirmed.put("confirm", false);
    assertThat(
            fields(
                w.problem(
                    com.divalhr.core.support.Employees.postJson(
                            w.admin, SCHEDULES, JSON.writeValueAsString(unconfirmed))
                        .header("Idempotency-Key", Organizations.newKey()),
                    400,
                    "VALIDATION_FAILED")))
        .containsExactlyInAnyOrder("previewDigest:FORMAT", "confirm:REQUIRED");
    assertThat(
            fields(
                w.problem(publish(w.admin, null, valid, "0".repeat(64)), 400, "VALIDATION_FAILED")))
        .containsExactly("Idempotency-Key:REQUIRED");
    assertThat(w.rows()).containsExactly(0, 0);
  }

  // ------------------------------------------------------------------------------------------
  // Slots, eligibility and conflicts
  // ------------------------------------------------------------------------------------------

  private static String issue(JsonNode preview, int index) {
    JsonNode issue = preview.get("slots").get(index).get("issue");
    return issue.isNull() ? "-" : issue.get("code").asText() + ":" + issue.get("reason").asText();
  }

  @Test
  void slotsAreCheckedForTimesEligibilityAndConflicts() throws Exception {
    ScheduleWorld w = world();
    ScheduleWorld other = world();
    Person ana = w.person("Ana", "Ilunga");
    Person ben = w.person("Ben", "Ngoy");
    Person cleo = w.person("Cléo", "Tshiala");
    Person dan = w.person("Dan", "Kalala");
    Person eve = w.person("Eve", "Mwamba", w.today.plusDays(30));
    Person foreigner = other.person("Fred", "Kasongo");
    LocalDate d = w.today.plusDays(7);
    // Ben moves to the other site before the schedule; Cléo leaves before her slot ends; Eve
    // starts after it.
    w.expect(
        w.change(ben.employee(), w.placement(w.otherSite, w.otherSiteDepartment, d.minusDays(1))),
        201);
    w.expect(w.separate(cleo.employee(), d), 201);
    Map<String, Object> proposal =
        proposal(
            w.site(),
            d,
            d.plusDays(1),
            List.of(
                slot(
                    "t1",
                    d.minusDays(1) + "T08:00",
                    d.minusDays(1) + "T09:00",
                    "Cashier",
                    "Caissière",
                    null),
                slot("t2", d + "T10:00", d + "T10:00", "Cashier", "Caissière", null),
                slot("t3", d + "T20:00", d.plusDays(2) + "T01:00", "Cashier", "Caissière", null),
                slot("t4", d + "T08:00", d.plusDays(1) + "T09:00", "Cashier", "Caissière", null),
                slot("e1", d + "T08:00", d + "T12:00", "Cashier", "Caissière", UUID.randomUUID()),
                slot(
                    "e2", d + "T08:00", d + "T12:00", "Cashier", "Caissière", foreigner.employee()),
                slot("e3", d + "T08:00", d + "T12:00", "Cashier", "Caissière", ben.employee()),
                slot(
                    "e4",
                    d + "T20:00",
                    d.plusDays(1) + "T02:00",
                    "Cashier",
                    "Caissière",
                    cleo.employee()),
                slot("e5", d + "T08:00", d + "T12:00", "Cashier", "Caissière", eve.employee()),
                slot("c1", d + "T08:00", d + "T12:00", "Cashier", "Caissière", ana.employee()),
                slot("c2", d + "T11:00", d + "T15:00", "Cashier", "Caissière", ana.employee()),
                slot("c3", d + "T12:00", d + "T16:00", "Cashier", "Caissière", ana.employee()),
                slot("ok", d + "T08:00", d + "T12:00", "Cashier", "Caissière", dan.employee())));
    JsonNode preview = w.expect(preview(w.admin, proposal), 200);
    assertThat(preview.get("publishable").asBoolean()).isFalse();
    List<String> issues = new ArrayList<>();
    for (int i = 0; i < preview.get("slots").size(); i++) {
      issues.add(issue(preview, i));
    }
    assertThat(issues)
        .containsExactly(
            "WORK_SCHEDULE_SLOT_INVALID:OUTSIDE_PERIOD",
            "WORK_SCHEDULE_SLOT_INVALID:END_NOT_AFTER_START",
            "WORK_SCHEDULE_SLOT_INVALID:TOO_LONG",
            "WORK_SCHEDULE_SLOT_INVALID:TOO_LONG",
            "WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE:NOT_FOUND",
            "WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE:NOT_FOUND",
            "WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE:NOT_AT_SITE",
            "WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE:SEPARATED",
            "WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE:NOT_EMPLOYED",
            "-",
            "WORK_SCHEDULE_EMPLOYEE_CONFLICT:IN_SCHEDULE",
            "-",
            "-");
    // Unknown and foreign employees reveal nothing.
    assertThat(preview.get("slots").get(4).get("employee").isNull()).isTrue();
    assertThat(preview.get("slots").get(5).get("employee").isNull()).isTrue();
    assertThat(preview.toString()).doesNotContain("Kasongo");
    // Publication refuses with the first issue's code, position and reason; nothing is written.
    JsonNode refused =
        w.problem(
            publish(
                w.admin, Organizations.newKey(), proposal, preview.get("previewDigest").asText()),
            422,
            "WORK_SCHEDULE_SLOT_INVALID");
    assertThat(refused.get("params"))
        .isEqualTo(JSON.readTree("{\"position\":\"1\",\"reason\":\"OUTSIDE_PERIOD\"}"));
    assertThat(w.rows()).containsExactly(0, 0);

    // Across publications and sites: Ana's published overnight shift at the first site blocks an
    // overlapping shift at the other site the next morning; back to back is fine.
    w.published(
        proposal(
            w.site(),
            d,
            d,
            List.of(
                slot(
                    "a",
                    d + "T20:00",
                    d.plusDays(1) + "T04:00",
                    "Guard",
                    "Gardien",
                    ana.employee()))));
    w.expect(
        w.change(ana.employee(), w.placement(w.otherSite, w.otherSiteDepartment, d.plusDays(1))),
        201);
    Map<String, Object> next =
        proposal(
            w.otherSite,
            d.plusDays(1),
            d.plusDays(1),
            List.of(
                slot(
                    "p",
                    d.plusDays(1) + "T02:00",
                    d.plusDays(1) + "T08:00",
                    "Guard",
                    "Gardien",
                    ana.employee()),
                slot(
                    "q",
                    d.plusDays(1) + "T04:00",
                    d.plusDays(1) + "T08:00",
                    "Guard",
                    "Gardien",
                    ana.employee())));
    JsonNode conflict = w.expect(preview(w.admin, next), 200);
    assertThat(issue(conflict, 0)).isEqualTo("WORK_SCHEDULE_EMPLOYEE_CONFLICT:PUBLISHED");
    assertThat(issue(conflict, 1)).isEqualTo("-");
    JsonNode refusedConflict =
        w.problem(
            publish(w.admin, Organizations.newKey(), next, conflict.get("previewDigest").asText()),
            409,
            "WORK_SCHEDULE_EMPLOYEE_CONFLICT");
    assertThat(refusedConflict.get("params"))
        .isEqualTo(JSON.readTree("{\"position\":\"1\",\"reason\":\"PUBLISHED\"}"));
    w.published(
        proposal(
            w.otherSite,
            d.plusDays(1),
            d.plusDays(1),
            List.of(
                slot(
                    "q",
                    d.plusDays(1) + "T04:00",
                    d.plusDays(1) + "T08:00",
                    "Guard",
                    "Gardien",
                    ana.employee()))));
    assertThat(w.rows()).containsExactly(2, 2);
  }

  @Test
  void localTimesThatDoNotExistOrRepeatAreInvalid() throws Exception {
    ScheduleWorld w = world();
    jdbc.update(
        "UPDATE tenant.organization SET timezone = 'Europe/Paris' WHERE id = ?", w.tenant());
    LocalDate autumn = LocalDate.of(2027, 10, 31);
    LocalDate spring = LocalDate.of(2027, 3, 28);
    // Only future dates are accepted; these are future in any test run before 2027.
    org.junit.jupiter.api.Assumptions.assumeTrue(w.today.isBefore(spring.minusDays(1)));
    JsonNode fall =
        w.expect(
            preview(
                w.admin,
                proposal(
                    w.site(),
                    autumn,
                    autumn,
                    List.of(
                        slot(
                            "a",
                            autumn + "T02:30",
                            autumn + "T09:00",
                            "Cashier",
                            "Caissière",
                            null),
                        slot(
                            "b",
                            autumn + "T00:00",
                            autumn + "T02:15",
                            "Cashier",
                            "Caissière",
                            null),
                        slot(
                            "c",
                            autumn + "T03:00",
                            autumn + "T11:00",
                            "Cashier",
                            "Caissière",
                            null)))),
            200);
    assertThat(issue(fall, 0)).isEqualTo("WORK_SCHEDULE_SLOT_INVALID:LOCAL_TIME_INVALID");
    assertThat(issue(fall, 1)).isEqualTo("WORK_SCHEDULE_SLOT_INVALID:LOCAL_TIME_INVALID");
    assertThat(issue(fall, 2)).isEqualTo("-");
    assertThat(fall.get("timezone").asText()).isEqualTo("Europe/Paris");
    JsonNode gap =
        w.expect(
            preview(
                w.admin,
                proposal(
                    w.site(),
                    spring,
                    spring,
                    List.of(
                        slot(
                            "a",
                            spring + "T01:00",
                            spring + "T02:30",
                            "Cashier",
                            "Caissière",
                            null)))),
            200);
    assertThat(issue(gap, 0)).isEqualTo("WORK_SCHEDULE_SLOT_INVALID:LOCAL_TIME_INVALID");
    // A shift across the spring change lasts its real duration: 22:00 to 06:00 is 7 hours.
    JsonNode across =
        w.expect(
            preview(
                w.admin,
                proposal(
                    w.site(),
                    spring.minusDays(1),
                    spring.minusDays(1),
                    List.of(
                        slot(
                            "a",
                            spring.minusDays(1) + "T22:00",
                            spring + "T06:00",
                            "Guard",
                            "Gardien",
                            null)))),
            200);
    assertThat(across.get("slots").get(0).get("startAt").asText())
        .isEqualTo(spring.minusDays(1) + "T21:00:00Z");
    assertThat(across.get("slots").get(0).get("endAt").asText()).isEqualTo(spring + "T04:00:00Z");
    w.published(
        proposal(
            w.site(),
            spring.minusDays(1),
            spring.minusDays(1),
            List.of(
                slot(
                    "a",
                    spring.minusDays(1) + "T22:00",
                    spring + "T06:00",
                    "Guard",
                    "Gardien",
                    null))));
    assertThat(
            w.count(
                "SELECT count(*) FROM people.work_schedule WHERE tenant_id = ?"
                    + " AND timezone = 'Europe/Paris'",
                w.tenant()))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Tenant isolation, MFA and membership
  // ------------------------------------------------------------------------------------------

  @Test
  void tenantsMfaMembershipAndRolesFailClosed() throws Exception {
    ScheduleWorld w = world();
    ScheduleWorld other = world();
    Person ana = w.person("Ana", "Kitenge");
    LocalDate d = w.today.plusDays(4);
    Map<String, Object> proposal =
        proposal(w.site(), d, d, List.of(shift("a", d, 8, 12, ana.employee())));
    String id = w.published(proposal);

    // Unknown, malformed and foreign schedules are the same 404.
    for (String unknown : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
      JsonNode problem = w.problem(detail(w.admin, unknown), 404, "WORK_SCHEDULE_NOT_FOUND");
      assertThat(problem.get("params").toString()).isEqualTo("{}");
    }
    other.problem(detail(other.admin, id), 404, "WORK_SCHEDULE_NOT_FOUND");
    assertThat(ids(other.expect(list(other.admin, ""), 200))).isEmpty();

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
      assertThat(w.call(preview(bearer, proposal)).getStatus()).isIn(401, 403);
      assertThat(
              w.call(publish(bearer, Organizations.newKey(), proposal, "0".repeat(64))).getStatus())
          .isIn(401, 403);
      assertThat(w.call(list(bearer, "")).getStatus()).isIn(401, 403);
      assertThat(w.call(detail(bearer, id)).getStatus()).isIn(401, 403);
    }
    assertThat(w.call(preview(passwordOnly, proposal)).getContentAsString())
        .contains("MFA_REQUIRED");
    // An administrator is not an employee: the self view refuses them.
    assertThat(w.call(mine(w.admin, "")).getStatus()).isIn(401, 403);
    // Another tenant's administrator cannot schedule this tenant's site or employee.
    other.problem(
        preview(
            other.admin,
            proposal(
                w.site(),
                d.plusDays(1),
                d.plusDays(1),
                List.of(shift("a", d.plusDays(1), 8, 9, null)))),
        422,
        "WORK_SCHEDULE_SITE_NOT_ACTIVE");
    JsonNode foreign =
        other.expect(
            preview(
                other.admin,
                proposal(other.site(), d, d, List.of(shift("a", d, 8, 9, ana.employee())))),
            200);
    assertThat(issue(foreign, 0)).isEqualTo("WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE:NOT_FOUND");
    assertThat(w.rows()).containsExactly(1, 1);
  }

  // ------------------------------------------------------------------------------------------
  // The administrators' list
  // ------------------------------------------------------------------------------------------

  @Test
  void theListPagesLatestFirstWithFiltersAndABoundCursor() throws Exception {
    ScheduleWorld w = world();
    LocalDate d = w.today.plusDays(2);
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      LocalDate day = d.plusDays(i * 2L);
      ids.add(
          w.published(
              proposal(w.site(), day, day.plusDays(1), List.of(shift("a", day, 8, 9, null)))));
    }
    String elsewhere = w.published(proposal(w.otherSite, d, d, List.of(shift("a", d, 8, 9, null))));
    JsonNode first = w.expect(list(w.admin, "?siteId=" + w.site() + "&limit=2"), 200);
    assertThat(ids(first)).containsExactly(ids.get(2), ids.get(1));
    String cursor = first.get("nextCursor").asText();
    JsonNode second =
        w.expect(list(w.admin, "?siteId=" + w.site() + "&limit=2&cursor=" + cursor), 200);
    assertThat(ids(second)).containsExactly(ids.get(0));
    assertThat(second.get("nextCursor").isNull()).isTrue();
    // The cursor is bound to the filters, the page size and the caller.
    w.problem(list(w.admin, "?limit=2&cursor=" + cursor), 400, "CURSOR_INVALID");
    w.problem(
        list(w.admin, "?siteId=" + w.site() + "&limit=3&cursor=" + cursor), 400, "CURSOR_INVALID");
    String colleague =
        Hierarchy.bearer(w.tenant(), "sub-sched-colleague-" + UUID.randomUUID(), "tenant-admin");
    w.problem(
        list(colleague, "?siteId=" + w.site() + "&limit=2&cursor=" + cursor),
        400,
        "CURSOR_INVALID");
    // Dates select overlapping periods; no filter lists every site.
    assertThat(ids(w.expect(list(w.admin, "?from=" + d.plusDays(4) + "&to=" + d.plusDays(4)), 200)))
        .containsExactly(ids.get(2));
    assertThat(ids(w.expect(list(w.admin, ""), 200)))
        .containsExactlyInAnyOrder(ids.get(0), ids.get(1), ids.get(2), elsewhere);
    assertThat(
            fields(
                w.problem(
                    list(w.admin, "?siteId=x&from=2026-13-01&limit=0"), 400, "VALIDATION_FAILED")))
        .containsExactlyInAnyOrder("siteId:FORMAT", "from:FORMAT", "limit:RANGE");
    assertThat(
            fields(
                w.problem(
                    list(w.admin, "?from=" + d + "&to=" + d.minusDays(1)),
                    400,
                    "VALIDATION_FAILED")))
        .containsExactly("to:RANGE");
    // Reads are disclosures.
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.audit_event WHERE action = 'work-schedule.read'"
                    + " AND tenant_id = ?",
                w.tenant()))
        .isEqualTo(4);
  }

  // ------------------------------------------------------------------------------------------
  // The employee's own schedule
  // ------------------------------------------------------------------------------------------

  @Test
  void anEmployeeSeesOnlyTheirOwnPublishedShifts() throws Exception {
    ScheduleWorld w = world();
    Person ana = w.person("Ana", "Mbuyi");
    Person ben = w.person("Ben", "Kazadi");
    Person cleo = w.person("Cléo", "Banza");
    LocalDate d = w.today.plusDays(3);
    String id =
        w.published(
            proposal(
                w.site(),
                d,
                d.plusDays(2),
                List.of(
                    slot(
                        "a1",
                        d + "T22:00",
                        d.plusDays(1) + "T06:00",
                        "Night watch",
                        "Veille de nuit",
                        ana.employee()),
                    shift("b1", d, 8, 16, ben.employee()),
                    shift("u1", d, 8, 16, null),
                    shift("a2", d.plusDays(1), 14, 18, ana.employee()),
                    shift("a3", d.plusDays(2), 8, 12, ana.employee()))));
    JsonNode page = w.expect(mine(ana.bearer(), "?limit=2"), 200);
    assertThat(page.get("asOf").asText()).isEqualTo(w.today.toString());
    assertThat(page.get("from").asText()).isEqualTo(w.today.toString());
    assertThat(page.get("items")).hasSize(2);
    JsonNode night = page.get("items").get(0);
    assertThat(night.get("scheduleId").asText()).isEqualTo(id);
    assertThat(night.get("start").asText()).isEqualTo(d + "T22:00");
    assertThat(night.get("overnight").asBoolean()).isTrue();
    assertThat(night.get("labels").get("fr").asText()).isEqualTo("Veille de nuit");
    assertThat(night.get("site").get("code").asText()).isEqualTo(w.org.site());
    assertThat(night.get("timezone").asText()).isEqualTo("Africa/Kinshasa");
    String cursor = page.get("nextCursor").asText();
    JsonNode next = w.expect(mine(ana.bearer(), "?limit=2&cursor=" + cursor), 200);
    assertThat(next.get("items")).hasSize(1);
    assertThat(next.get("nextCursor").isNull()).isTrue();
    // Nothing about anyone else, the uncovered slot, the publisher or the audit.
    String all = page.toString() + next.toString();
    assertThat(all)
        .doesNotContain("Kazadi", "Mbuyi", ben.employee().toString(), ana.employee().toString())
        .doesNotContain(w.adminSubject, "employee\"", "publish");
    assertThat(w.expect(mine(ben.bearer(), ""), 200).get("items")).hasSize(1);
    assertThat(w.expect(mine(cleo.bearer(), ""), 200).get("items")).isEmpty();
    // The cursor is bound to the caller and the dates.
    w.problem(mine(ben.bearer(), "?limit=2&cursor=" + cursor), 400, "CURSOR_INVALID");
    w.problem(
        mine(ana.bearer(), "?limit=2&from=" + d + "&cursor=" + cursor), 400, "CURSOR_INVALID");
    // Dates select by start date.
    assertThat(
            w.expect(mine(ana.bearer(), "?from=" + d.plusDays(1) + "&to=" + d.plusDays(1)), 200)
                .get("items"))
        .hasSize(1);
    assertThat(
            fields(
                w.problem(
                    mine(ana.bearer(), "?from=" + d + "&to=" + d.plusDays(366)),
                    400,
                    "VALIDATION_FAILED")))
        .containsExactly("to:RANGE");
    // Employees cannot discover the administrators' endpoints.
    assertThat(w.call(list(ana.bearer(), "")).getStatus()).isIn(401, 403);
    assertThat(w.call(detail(ana.bearer(), id)).getStatus()).isIn(401, 403);
    // An unlinked employee is refused, never shown another's shifts.
    w.unlink(ana.employee());
    w.problem(mine(ana.bearer(), ""), 403, "EMPLOYEE_LINK_REQUIRED");
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.audit_event WHERE action = 'work-schedule.self-list'"
                    + " AND tenant_id = ? AND resource_id = ?",
                w.tenant(),
                ana.employee()))
        .isEqualTo(3);
  }
}
