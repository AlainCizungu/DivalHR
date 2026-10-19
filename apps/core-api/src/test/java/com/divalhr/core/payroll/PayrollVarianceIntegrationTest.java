package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PayrollInputCalls.JSON;
import static com.divalhr.core.payroll.PayrollInputCalls.generate;
import static com.divalhr.core.payroll.PayrollInputCalls.generating;
import static com.divalhr.core.payroll.PayrollInputCalls.preview;
import static com.divalhr.core.payroll.PayrollInputCalls.scope;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.LockedWeeks;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * MVP-091 (Issue #143) against real PostgreSQL through the public API only: compatible runs are
 * compared employee by employee with exact classifications and totals that reconcile to the stored
 * inputs; the default comparison is the newest eligible same-length preceding run; unknown, foreign
 * and incompatible runs reveal nothing; cursors are bound to tenant, run pair, thresholds, view and
 * page size; the review writes no payroll evidence and no event; audits and logs carry no
 * identifier, name, number, subject, cursor or exact total.
 *
 * <p>One tenant with two locked weeks (Monday for Ana, Eve and Zoe and Tuesday for Ana, as in the
 * MVP-090A fixture) yields periods of six days that differ on purpose: r1 is Tuesday of the first
 * week (Ana once), r4 is Monday and Tuesday of the first (Ana twice, Eve, Zoe), r3 is Monday and
 * Tuesday of the second (the same), r2 is Tuesday of the second (Ana once) and r0 is a seven-day
 * period. (Attendance is rate limited per person, so the fixture stays at two weeks.)
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class PayrollVarianceIntegrationTest {

  private static World world;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  /** The shared, read-only world of generated runs. */
  private record World(
      LockedWeeks f,
      LockedWeeks other,
      String r0,
      String r1,
      String r2,
      String r3,
      String r4,
      String foreignRun) {}

  @AfterAll
  static void forget() {
    world = null;
  }

  private World world() throws Exception {
    if (world == null) {
      // The foreign tenant first: building a world moves the clock to its last Sunday.
      LockedWeeks other = new LockedWeeks(mvc, jdbc, lookups, clock);
      LocalDate om = other.nextMonday();
      other.locked(om);
      String foreign = run(other, om, om.plusDays(6));
      clock.reset();
      LockedWeeks f = new LockedWeeks(mvc, jdbc, lookups, clock);
      LocalDate m = f.nextMonday();
      f.locked(m);
      f.locked(m.plusDays(7));
      String r1 = run(f, m.plusDays(1), m.plusDays(6));
      String r3 = run(f, m.plusDays(7), m.plusDays(12));
      String r2 = run(f, m.plusDays(8), m.plusDays(13));
      String r4 = run(f, m, m.plusDays(5));
      String r0 = run(f, m, m.plusDays(6));
      world = new World(f, other, r0, r1, r2, r3, r4, foreign);
    }
    clock.reset();
    return world;
  }

  private static String run(LockedWeeks f, LocalDate start, LocalDate end) throws Exception {
    Map<String, Object> period = scope(f.legalEntity(), start, end);
    JsonNode preview = f.expect(preview(f.admin(), period), 200);
    assertThat(preview.get("ready").asBoolean()).as(preview.toString()).isTrue();
    return f.expect(generate(f.admin(), Organizations.newKey(), generating(period, preview)), 200)
        .get("run")
        .get("runId")
        .asText();
  }

  private static MockHttpServletRequestBuilder review(String bearer, String run, String query) {
    return MockMvcRequestBuilders.get(
            "/api/v1/payroll-inputs/" + run + "/variance" + (query == null ? "" : "?" + query))
        .header("Authorization", bearer);
  }

  private static MockHttpServletRequestBuilder candidates(String bearer, String run) {
    return MockMvcRequestBuilders.get("/api/v1/payroll-inputs/" + run + "/variance-candidates")
        .header("Authorization", bearer);
  }

  private int minutes(String run, UUID employee) {
    Integer sum =
        jdbc.queryForObject(
            "SELECT coalesce(sum(worked_minutes), 0) FROM payroll.input_employee WHERE run_id ="
                + " ?::uuid AND employee_id = ?",
            Integer.class,
            run,
            employee);
    return sum == null ? 0 : sum;
  }

  private static JsonNode line(JsonNode review, UUID employee) {
    for (JsonNode item : review.get("items")) {
      if (item.get("employeeId").asText().equals(employee.toString())) {
        return item;
      }
    }
    throw new AssertionError("employee not in the page");
  }

  private static List<String> ids(JsonNode items) {
    List<String> ids = new ArrayList<>();
    items.forEach(i -> ids.add(i.get("employeeId").asText()));
    return ids;
  }

  private static Set<String> fields(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** A snapshot of everything a review must not change. */
  private String evidence(UUID tenant) {
    String runs =
        jdbc.queryForObject(
            "SELECT coalesce(string_agg(r::text, '|' ORDER BY r.id), '') FROM payroll.input_run r"
                + " WHERE r.tenant_id = ?",
            String.class,
            tenant);
    String employees =
        jdbc.queryForObject(
            "SELECT coalesce(string_agg(e::text, '|' ORDER BY e.run_id, e.employee_id,"
                + " e.employment_id), '') FROM payroll.input_employee e WHERE e.tenant_id = ?",
            String.class,
            tenant);
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM payroll.input_source_row WHERE tenant_id = ?",
            Integer.class,
            tenant);
    Integer weeks =
        jdbc.queryForObject(
            "SELECT count(*) FROM payroll.input_source_week WHERE tenant_id = ?",
            Integer.class,
            tenant);
    Integer events =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'tenantId' = ?",
            Integer.class,
            tenant.toString());
    Integer generated =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action ="
                + " 'payroll-input.generate'",
            Integer.class,
            tenant);
    return runs + "#" + employees + "#" + rows + "#" + weeks + "#" + events + "#" + generated;
  }

  // ------------------------------------------------------------------------------------------
  // 1, 2, 4, 5: eligible runs, the default, exact classifications and reconciling totals
  // ------------------------------------------------------------------------------------------

  @Test
  void theDefaultComparisonIsTheNewestEligibleSameLengthPrecedingRun() throws Exception {
    World w = world();
    String admin = w.f().administrator();

    JsonNode forSecond = w.f().expect(candidates(admin, w.r2()), 200);
    // r1 ends the day before r2 starts, r4 the day before that; r3 ends after r2 starts and r0
    // has another length (7 days).
    assertThat(forSecond.get("candidates").findValuesAsText("runId"))
        .containsExactly(w.r1(), w.r4());
    assertThat(forSecond.get("defaultRunId").asText()).isEqualTo(w.r1());
    assertThat(forSecond.get("current").get("runId").asText()).isEqualTo(w.r2());
    // The same two precede r3 (r2 ends after r3 starts).
    assertThat(
            w.f()
                .expect(candidates(admin, w.r3()), 200)
                .get("candidates")
                .findValuesAsText("runId"))
        .containsExactly(w.r1(), w.r4());
    // The first runs, and the run of another length, have no eligible baseline: an empty list.
    for (String none : List.of(w.r1(), w.r4(), w.r0())) {
      JsonNode empty = w.f().expect(candidates(admin, none), 200);
      assertThat(empty.get("candidates")).isEmpty();
      assertThat(empty.get("defaultRunId").isNull()).isTrue();
      w.f().problem(review(admin, none, null), 404, "PAYROLL_VARIANCE_TARGET_NOT_FOUND");
    }
    // The defaulted review compares with the same run the candidates named.
    JsonNode defaulted = w.f().expect(review(admin, w.r2(), null), 200);
    assertThat(defaulted.get("comparison").get("runId").asText()).isEqualTo(w.r1());
    assertThat(defaulted.get("comparisonIsDefault").asBoolean()).isTrue();
    JsonNode chosen = w.f().expect(review(admin, w.r2(), "comparisonRunId=" + w.r4()), 200);
    assertThat(chosen.get("comparison").get("runId").asText()).isEqualTo(w.r4());
    assertThat(chosen.get("comparisonIsDefault").asBoolean()).isFalse();
  }

  @Test
  void newMissingChangedAndUnchangedEmployeesAreClassifiedAndTotalsReconcileToTheStoredInputs()
      throws Exception {
    World w = world();
    LockedWeeks f = w.f();
    UUID ana = f.ana().employee();
    UUID eve = f.eve().employee();
    UUID zoe = f.zoe().employee();
    String admin = f.administrator();

    // r3 (Ana twice, Eve, Zoe) against r1 (Ana once): Ana CHANGED, Eve and Zoe NEW.
    JsonNode growth = f.expect(review(admin, w.r3(), "comparisonRunId=" + w.r1()), 200);
    int anaThen = minutes(w.r1(), ana);
    int anaNow = minutes(w.r3(), ana);
    assertThat(anaNow).isEqualTo(2 * anaThen);
    JsonNode anaLine = line(growth, ana);
    assertThat(anaLine.get("classification").asText()).isEqualTo("CHANGED");
    assertThat(anaLine.get("currentMinutes").asInt()).isEqualTo(anaNow);
    assertThat(anaLine.get("comparisonMinutes").asInt()).isEqualTo(anaThen);
    assertThat(anaLine.get("minuteDelta").asInt()).isEqualTo(anaThen);
    assertThat(anaLine.get("percentageDeltaBasisPoints").asInt()).isEqualTo(10000);
    assertThat(anaLine.get("material").asBoolean()).isTrue();
    assertThat(anaLine.get("current").get("employmentIds")).hasSize(1);
    assertThat(anaLine.get("comparison").get("employmentIds")).hasSize(1);
    for (UUID newcomer : List.of(eve, zoe)) {
      JsonNode newLine = line(growth, newcomer);
      assertThat(newLine.get("classification").asText()).isEqualTo("NEW");
      assertThat(newLine.get("comparison").isNull()).isTrue();
      assertThat(newLine.get("percentageDeltaBasisPoints").isNull()).isTrue();
      assertThat(newLine.get("minuteDelta").asInt()).isEqualTo(minutes(w.r3(), newcomer));
      assertThat(newLine.get("material").asBoolean()).isTrue();
    }
    JsonNode totals = growth.get("totals");
    assertThat(totals.get("currentEmployees").asInt()).isEqualTo(3);
    assertThat(totals.get("comparisonEmployees").asInt()).isEqualTo(1);
    assertThat(totals.get("currentMinutes").asInt())
        .isEqualTo(anaNow + minutes(w.r3(), eve) + minutes(w.r3(), zoe));
    assertThat(totals.get("comparisonMinutes").asInt()).isEqualTo(anaThen);
    assertThat(totals.get("minuteDelta").asInt())
        .isEqualTo(totals.get("currentMinutes").asInt() - totals.get("comparisonMinutes").asInt());
    assertThat(totals.get("newCount").asInt()).isEqualTo(2);
    assertThat(totals.get("changedCount").asInt()).isEqualTo(1);
    assertThat(totals.get("missingCount").asInt()).isZero();
    assertThat(totals.get("unchangedCount").asInt()).isZero();
    assertThat(totals.get("materialCount").asInt()).isEqualTo(3);
    // Review order: material first, then the largest absolute delta, then the employee ID.
    List<Integer> deltas = new ArrayList<>();
    growth.get("items").forEach(i -> deltas.add(Math.abs(i.get("minuteDelta").asInt())));
    assertThat(deltas).isSortedAccordingTo(java.util.Comparator.reverseOrder());

    // r2 (Ana once) against r4 (Ana twice, Eve, Zoe): Ana CHANGED downwards, Eve and Zoe MISSING.
    JsonNode shrink = f.expect(review(admin, w.r2(), "comparisonRunId=" + w.r4()), 200);
    assertThat(line(shrink, ana).get("minuteDelta").asInt()).isEqualTo(-anaThen);
    assertThat(line(shrink, ana).get("percentageDeltaBasisPoints").asInt()).isEqualTo(-5000);
    for (UUID gone : List.of(eve, zoe)) {
      JsonNode missing = line(shrink, gone);
      assertThat(missing.get("classification").asText()).isEqualTo("MISSING");
      assertThat(missing.get("current").isNull()).isTrue();
      assertThat(missing.get("percentageDeltaBasisPoints").asInt()).isEqualTo(-10000);
      assertThat(missing.get("material").asBoolean()).isTrue();
    }
    assertThat(shrink.get("totals").get("missingCount").asInt()).isEqualTo(2);
    assertThat(shrink.get("totals").get("minuteDelta").asInt())
        .isEqualTo(anaThen - minutes(w.r4(), ana) - minutes(w.r4(), eve) - minutes(w.r4(), zoe));

    // r2 (Ana once) against r1 (Ana once): UNCHANGED, never material, even at zero thresholds.
    JsonNode same =
        f.expect(
            review(
                admin,
                w.r2(),
                "comparisonRunId=" + w.r1() + "&absoluteMinuteThreshold=0&percentageThreshold=0"),
            200);
    assertThat(line(same, ana).get("classification").asText()).isEqualTo("UNCHANGED");
    assertThat(line(same, ana).get("minuteDelta").asInt()).isZero();
    assertThat(line(same, ana).get("material").asBoolean()).isFalse();
    assertThat(same.get("totals").get("materialCount").asInt()).isZero();
    assertThat(same.get("thresholds").get("absoluteMinuteThreshold").asInt()).isZero();
    assertThat(same.get("thresholds").get("percentageThreshold").asInt()).isZero();
    // The default thresholds are echoed when none is sent.
    assertThat(growth.get("thresholds").get("absoluteMinuteThreshold").asInt()).isEqualTo(120);
    assertThat(growth.get("thresholds").get("percentageThreshold").asInt()).isEqualTo(20);

    // The views partition the lines; the totals never depend on the view.
    for (String view : List.of("ALL", "MATERIAL", "NEW", "MISSING", "CHANGED", "UNCHANGED")) {
      JsonNode filtered =
          f.expect(review(admin, w.r3(), "comparisonRunId=" + w.r1() + "&view=" + view), 200);
      assertThat(filtered.get("view").asText()).isEqualTo(view);
      assertThat(filtered.get("totals")).isEqualTo(totals);
      int expected =
          switch (view) {
            case "ALL", "MATERIAL" -> 3;
            case "NEW" -> 2;
            case "CHANGED" -> 1;
            default -> 0;
          };
      assertThat(filtered.get("matchingCount").asInt()).as(view).isEqualTo(expected);
      assertThat(filtered.get("items")).as(view).hasSize(expected);
    }
  }

  @Test
  void aReviewIsReproducibleAndPaginatesWithTotalsThatNeverChange() throws Exception {
    World w = world();
    LockedWeeks f = w.f();
    String admin = f.administrator();
    String base = "comparisonRunId=" + w.r1();
    JsonNode whole = f.expect(review(admin, w.r3(), base), 200);
    assertThat(f.expect(review(admin, w.r3(), base), 200).get("items"))
        .isEqualTo(whole.get("items"));
    List<String> expected = ids(whole.get("items"));
    assertThat(expected).hasSize(3);

    List<String> walked = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode page =
          f.expect(
              review(
                  admin, w.r3(), base + "&limit=1" + (cursor == null ? "" : "&cursor=" + cursor)),
              200);
      assertThat(page.get("items")).hasSizeLessThanOrEqualTo(1);
      assertThat(page.get("totals")).isEqualTo(whole.get("totals"));
      assertThat(page.get("matchingCount").asInt()).isEqualTo(3);
      walked.addAll(ids(page.get("items")));
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
      pages++;
    } while (cursor != null && pages < 10);
    assertThat(pages).isEqualTo(3);
    assertThat(walked).isEqualTo(expected);

    // A cursor is bound to the page size, thresholds, view, comparison run, current run and
    // tenant; any other use is the same CURSOR_INVALID, and never echoes the cursor.
    String first =
        f.expect(review(admin, w.r3(), base + "&limit=1"), 200).get("nextCursor").asText();
    f.expect(review(admin, w.r3(), base + "&limit=1&cursor=" + first), 200);
    List<MockHttpServletRequestBuilder> misuse =
        List.of(
            review(admin, w.r3(), base + "&limit=2&cursor=" + first),
            review(admin, w.r3(), base + "&limit=1&absoluteMinuteThreshold=121&cursor=" + first),
            review(admin, w.r3(), base + "&limit=1&percentageThreshold=21&cursor=" + first),
            review(admin, w.r3(), base + "&limit=1&view=MATERIAL&cursor=" + first),
            review(admin, w.r3(), "limit=1&cursor=" + first + "&comparisonRunId=" + w.r4()),
            review(admin, w.r2(), "comparisonRunId=" + w.r1() + "&limit=1&cursor=" + first),
            review(f.administrator(), w.r3(), base + "&limit=1&cursor=" + first + "x"));
    for (MockHttpServletRequestBuilder request : misuse) {
      MockHttpServletResponse response = f.call(request);
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getContentAsString()).doesNotContain(first);
    }
    // Another tenant's administrator cannot use it on its own runs.
    w.f()
        .problem(
            review(w.other().admin(), w.foreignRun(), "limit=1&cursor=" + first),
            404,
            "PAYROLL_VARIANCE_TARGET_NOT_FOUND");
  }

  // ------------------------------------------------------------------------------------------
  // 6: unknown, foreign, malformed and incompatible runs reveal nothing; authority
  // ------------------------------------------------------------------------------------------

  @Test
  void unknownForeignMalformedAndIncompatibleRunsAreTheSameSafe404() throws Exception {
    World w = world();
    LockedWeeks f = w.f();
    String admin = f.administrator();
    String code = "PAYROLL_VARIANCE_TARGET_NOT_FOUND";
    Set<String> bodies = new TreeSet<>();
    List<MockHttpServletRequestBuilder> refused =
        List.of(
            review(admin, UUID.randomUUID().toString(), null),
            review(admin, "nope", null),
            candidates(admin, UUID.randomUUID().toString()),
            candidates(admin, "nope"),
            // Another tenant's run as the current run, and as the comparison run.
            review(admin, w.foreignRun(), null),
            candidates(admin, w.foreignRun()),
            review(admin, w.r2(), "comparisonRunId=" + w.foreignRun()),
            review(w.other().admin(), w.r2(), null),
            review(w.other().admin(), w.foreignRun(), "comparisonRunId=" + w.r2()),
            // Malformed and unknown comparison runs.
            review(admin, w.r2(), "comparisonRunId=nope"),
            review(admin, w.r2(), "comparisonRunId=" + UUID.randomUUID()),
            // Incompatible: another length (P0 is seven days), itself, a later run, an overlapping
            // run.
            review(admin, w.r2(), "comparisonRunId=" + w.r0()),
            review(admin, w.r2(), "comparisonRunId=" + w.r2()),
            review(admin, w.r1(), "comparisonRunId=" + w.r2()),
            review(admin, w.r3(), "comparisonRunId=" + w.r2()),
            review(admin, w.r2(), "comparisonRunId=" + w.r3()));
    for (MockHttpServletRequestBuilder request : refused) {
      MockHttpServletResponse response = f.call(request);
      assertThat(response.getStatus()).isEqualTo(404);
      JsonNode problem = JSON.readTree(response.getContentAsString());
      assertThat(problem.get("code").asText()).isEqualTo(code);
      assertThat(problem.get("params").size()).isZero();
      String text = response.getContentAsString();
      bodies.add(text.replaceAll("\"(correlationId|instance|traceId)\":\"[^\"]*\"", ""));
      assertThat(text).doesNotContain(w.r2()).doesNotContain(w.foreignRun());
    }
    assertThat(bodies).hasSize(1);
  }

  @Test
  void onlyTenantAdministratorsWithExactMfaReviewAndValidationNamesOnlyTheField() throws Exception {
    World w = world();
    LockedWeeks f = w.f();
    String admin = f.administrator();
    String platform =
        com.divalhr.core.support.Hierarchy.bearer(
            f.tenant(), "sub-platform-" + UUID.randomUUID(), "platform-admin");
    for (String bearer : List.of(f.ana().bearer(), f.mia().bearer(), platform)) {
      assertThat(f.call(review(bearer, w.r3(), null)).getStatus()).isEqualTo(403);
      assertThat(f.call(candidates(bearer, w.r3())).getStatus()).isEqualTo(403);
    }
    String subject = "sub-pay91-pwd-" + UUID.randomUUID();
    Memberships.grant(f.tenant(), subject, "tenant-admin");
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .tenant(f.tenant())
                .subject(subject)
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    f.problem(review(passwordOnly, w.r3(), null), 403, "MFA_REQUIRED");
    f.problem(candidates(passwordOnly, w.r3()), 403, "MFA_REQUIRED");
    assertThat(
            f.call(MockMvcRequestBuilders.get("/api/v1/payroll-inputs/" + w.r3() + "/variance"))
                .getStatus())
        .isEqualTo(401);

    // The tenant never comes from the query.
    assertThat(
            f.expect(review(admin, w.r3(), "tenantId=" + w.other().tenant()), 200)
                .get("current")
                .get("runId")
                .asText())
        .isEqualTo(w.r3());

    // Invalid thresholds, view and limit: 400 with the field and its constraint only.
    Map<String, String> invalid =
        Map.of(
            "absoluteMinuteThreshold=10081", "absoluteMinuteThreshold:RANGE",
            "absoluteMinuteThreshold=-1", "absoluteMinuteThreshold:FORMAT",
            "absoluteMinuteThreshold=abc", "absoluteMinuteThreshold:FORMAT",
            "absoluteMinuteThreshold=1.5", "absoluteMinuteThreshold:FORMAT",
            "percentageThreshold=101", "percentageThreshold:RANGE",
            "percentageThreshold=-5", "percentageThreshold:FORMAT",
            "view=BOGUS", "view:FORMAT",
            "limit=0", "limit:RANGE",
            "limit=101", "limit:RANGE");
    for (Map.Entry<String, String> entry : invalid.entrySet()) {
      MockHttpServletResponse response =
          f.call(review(admin, w.r3(), "comparisonRunId=" + w.r1() + "&" + entry.getKey()));
      assertThat(response.getStatus()).as(entry.getKey()).isEqualTo(400);
      JsonNode problem = JSON.readTree(response.getContentAsString());
      assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
      JsonNode field = problem.get("params").get("fields").get(0);
      assertThat(field.get("field").asText() + ":" + field.get("constraint").asText())
          .isEqualTo(entry.getValue());
      assertThat(response.getContentAsString())
          .doesNotContain(entry.getKey().substring(entry.getKey().indexOf('=') + 1) + "\"");
    }
    // The bounds themselves are valid.
    f.expect(
        review(
            admin,
            w.r3(),
            "comparisonRunId="
                + w.r1()
                + "&absoluteMinuteThreshold=10080&percentageThreshold=100&limit=100"),
        200);
    f.expect(
        review(
            admin,
            w.r3(),
            "comparisonRunId="
                + w.r1()
                + "&absoluteMinuteThreshold=0&percentageThreshold=0&limit=1"),
        200);
  }

  // ------------------------------------------------------------------------------------------
  // 8, 9: read-only; audit, logs and responses carry no identifier, name, number, subject
  // ------------------------------------------------------------------------------------------

  @Test
  void aReviewWritesNoPayrollEvidenceNoEventAndOnlyBoundedAuditAndLogs(CapturedOutput output)
      throws Exception {
    World w = world();
    LockedWeeks f = w.f();
    String admin = f.administrator();
    String before = evidence(f.tenant());
    int disclosures = audits(f.tenant());
    int runsBefore =
        f.count("SELECT count(*) FROM payroll.input_run WHERE tenant_id = ?", f.tenant());

    JsonNode review =
        f.expect(review(admin, w.r3(), "comparisonRunId=" + w.r1() + "&limit=1"), 200);
    String cursor = review.get("nextCursor").asText();
    f.expect(review(admin, w.r3(), "comparisonRunId=" + w.r1() + "&limit=1&cursor=" + cursor), 200);
    f.expect(candidates(admin, w.r2()), 200);
    MockHttpServletResponse response = f.call(review(admin, w.r3(), "comparisonRunId=" + w.r1()));
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    f.problem(
        review(admin, w.r3(), "comparisonRunId=nope"), 404, "PAYROLL_VARIANCE_TARGET_NOT_FOUND");

    assertThat(evidence(f.tenant())).isEqualTo(before);
    assertThat(f.count("SELECT count(*) FROM payroll.input_run WHERE tenant_id = ?", f.tenant()))
        .isEqualTo(runsBefore);
    // Four disclosures: two review pages, the candidates, the third review; the 404 audits none.
    assertThat(audits(f.tenant())).isEqualTo(disclosures + 4);

    List<String> actions =
        jdbc.queryForList(
            "SELECT DISTINCT action FROM platform.audit_event WHERE tenant_id = ? AND action LIKE"
                + " 'payroll-variance.%' ORDER BY action",
            String.class, f.tenant());
    assertThat(actions).containsExactly("payroll-variance.candidates", "payroll-variance.review");
    List<String> metadata =
        jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ? AND action ="
                + " 'payroll-variance.review' ORDER BY occurred_at DESC LIMIT 2",
            String.class,
            f.tenant());
    JsonNode next = JSON.readTree(metadata.get(1));
    assertThat(fields(next))
        .containsExactly(
            "absoluteMinuteThreshold",
            "comparisonPeriodEnd",
            "comparisonPeriodStart",
            "currentPeriodEnd",
            "currentPeriodStart",
            "employeeCountBand",
            "filter",
            "materialCountBand",
            "minuteDeltaBand",
            "page",
            "percentageThreshold",
            "resultCount",
            "schemaVersion",
            "view");
    assertThat(next.get("page").asText()).isEqualTo("next");
    assertThat(next.get("absoluteMinuteThreshold").asInt()).isEqualTo(120);
    assertThat(next.get("percentageThreshold").asInt()).isEqualTo(20);
    assertThat(next.get("employeeCountBand").asText()).isEqualTo("1-10");
    assertThat(next.get("minuteDeltaBand").asText()).isEqualTo("1-2400");
    String joined = String.join("|", metadata);
    for (String secret :
        List.of(
            w.r1(),
            w.r3(),
            f.ana().employee().toString(),
            f.ana().subject(),
            f.ana().number(),
            f.admin(),
            cursor)) {
      assertThat(joined).doesNotContain(secret);
    }
    // Neither the response nor the logs carry a name, number, subject or e-mail address.
    String logs = output.getOut() + output.getErr();
    assertThat(response.getContentAsString())
        .doesNotContain("Kalala")
        .doesNotContain("Ngoy")
        .doesNotContain(f.ana().number())
        .doesNotContain(f.ana().subject())
        .doesNotContain("@");
    for (String secret :
        List.of(
            w.r1(),
            w.r3(),
            w.r4(),
            w.r2(),
            f.ana().employee().toString(),
            f.ana().subject(),
            f.ana().number(),
            "Kalala",
            cursor)) {
      assertThat(logs).as("logs").doesNotContain(secret);
    }
    assertThat(logs).contains("payroll_variance_reviewed").contains("payroll_variance_rejected");
  }

  private int audits(UUID tenant) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action LIKE"
                + " 'payroll-variance.%'",
            Integer.class, tenant);
    return n == null ? 0 : n;
  }
}
