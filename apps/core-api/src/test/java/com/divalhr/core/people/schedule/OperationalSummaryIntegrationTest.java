package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.AttendanceCalls.instants;
import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static com.divalhr.core.people.schedule.ScheduleWorld.proposal;
import static com.divalhr.core.people.schedule.ScheduleWorld.shift;
import static com.divalhr.core.people.schedule.SubstitutionCalls.slotIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.platform.ai.AiServiceUnavailableException;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Request;
import com.divalhr.core.platform.ai.OperationalSummaryPort;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * MVP-101 (Issue #156, ADR 0027) against real PostgreSQL, with the AI service replaced by a
 * deterministic stand-in behind the real port: both audiences and their exact authorization, tenant
 * and manager-scope isolation, filter equivalence with MVP-100, the figures equal to the
 * operational status at the same instant, exactly the closed aggregate crossing the AI port,
 * request validation, safe failures, no database transaction during the AI call, the fail-closed
 * audit, rate limiting and the privacy of responses, audit rows and logs.
 */
@IntegrationTest
@TestPropertySource(properties = "divalhr.request-limits.subject.operational-summary=30")
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class OperationalSummaryIntegrationTest {

  private static final String ADMIN = "/api/v1/operations/status-summary";
  private static final String TEAM = "/api/v1/me/team-operations/status-summary";
  private static final String ADMIN_STATUS = "/api/v1/operations/status";
  private static final String TEAM_STATUS = "/api/v1/me/team-operations/status";
  private static final Duration GRACE = Duration.ofMinutes(30);
  private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);
  private static final String EN = "{\"language\":\"en\"}";
  private static final String FR = "{\"language\":\"fr\"}";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @MockitoSpyBean private AuditRecorder audit;
  @MockitoBean private OperationalSummaryPort ai;

  private final List<Request> sent = new CopyOnWriteArrayList<>();
  private final List<Boolean> transactionDuringCall = new CopyOnWriteArrayList<>();

  @BeforeEach
  void stubTheAiService() {
    when(ai.generate(any()))
        .thenAnswer(
            call -> {
              Request request = call.getArgument(0);
              sent.add(request);
              transactionDuringCall.add(
                  TransactionSynchronizationManager.isActualTransactionActive());
              return OperationalSummaryStub.answer(request, "MODEL");
            });
  }

  @AfterEach
  void reset() {
    clock.reset();
    org.mockito.Mockito.reset((AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
  }

  private record Day(
      ScheduleWorld w, Person mia, Person ana, Person ben, Person cy, Person dee, Instant start) {}

  /** Six assigned shifts and one uncovered; Mia manages Ana, Ben and Cy. */
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
    return new Day(w, mia, ana, ben, cy, dee, at[0]);
  }

  private static MockHttpServletRequestBuilder generate(String bearer, String path, String body) {
    return Employees.postJson(bearer, path, body);
  }

  private static MockHttpServletRequestBuilder read(String bearer, String path, String query) {
    return get(path + (query.isEmpty() ? "" : "?" + query)).header("Authorization", bearer);
  }

  private JsonNode ok(ScheduleWorld w, MockHttpServletRequestBuilder request) throws Exception {
    MockHttpServletResponse response = w.call(request);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private static JsonNode withoutVolatile(JsonNode problem) {
    com.fasterxml.jackson.databind.node.ObjectNode copy =
        (com.fasterxml.jackson.databind.node.ObjectNode) problem.deepCopy();
    copy.remove("correlationId");
    copy.remove("instance");
    return copy;
  }

  // ------------------------------------------------------------------------------------------
  // D101-1, D101-3, D101-10: the same audiences, scopes and figures as MVP-100
  // ------------------------------------------------------------------------------------------

  @Test
  void bothAudiencesGetAGroundedBriefingWithTheFiguresOfTheOperationalStatus() throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    clock.set(t.start().plus(GRACE).plus(MICRO));

    JsonNode status = ok(w, read(w.admin, ADMIN_STATUS, ""));
    JsonNode briefing = ok(w, generate(w.admin, ADMIN, EN));
    assertThat(briefing.get("language").asText()).isEqualTo("en");
    assertThat(briefing.get("generationMode").asText()).isEqualTo("MODEL");
    assertThat(briefing.get("asOfInstant")).isEqualTo(status.get("asOfInstant"));
    assertThat(briefing.get("generatedAt").asText()).isNotBlank();
    assertThat(briefing.get("sourceSnapshot").get("thresholds"))
        .isEqualTo(status.get("thresholds"));
    assertThat(briefing.get("sourceSnapshot").get("shifts")).isEqualTo(status.get("shifts"));
    assertThat(briefing.get("sourceSnapshot").get("coverage")).isEqualTo(status.get("coverage"));
    assertThat(briefing.get("sourceSnapshot").get("approvals")).isEqualTo(status.get("approvals"));
    assertThat(briefing.get("limitations")).hasSize(3);
    assertThat(briefing.get("highlights").size()).isBetween(1, 5);
    assertThat(briefing.get("headline").get("code").asText())
        .isEqualTo("SEVERAL_AREAS_NEED_ATTENTION");
    // The two attention areas of this day (not checked in, uncovered) are always selected first.
    List<String> types = new ArrayList<>();
    briefing.get("highlights").forEach(h -> types.add(h.get("factType").asText()));
    assertThat(types).startsWith("NOT_CHECKED_IN", "UNCOVERED_SHIFTS");
    briefing
        .get("highlights")
        .forEach(
            h ->
                assertThat(h.fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder("factType", "severity", "routeId", "text"));

    // The manager's briefing: only her reports, no coverage, the figures of her team status.
    JsonNode teamStatus = ok(w, read(t.mia().bearer(), TEAM_STATUS, ""));
    JsonNode team = ok(w, generate(t.mia().bearer(), TEAM, FR));
    assertThat(team.get("language").asText()).isEqualTo("fr");
    assertThat(team.get("sourceSnapshot").get("shifts")).isEqualTo(teamStatus.get("shifts"));
    assertThat(team.get("sourceSnapshot").get("coverage").isNull()).isTrue();
    assertThat(team.get("sourceSnapshot").get("approvals")).isEqualTo(teamStatus.get("approvals"));
    assertThat(sent.get(1).scopeKind()).isEqualTo("TEAM");
    assertThat(sent.get(1).uncovered()).isNull();

    // A manager who manages nobody receives a briefing of zeros, not an error.
    JsonNode nobody = ok(w, generate(t.dee().bearer(), TEAM, EN));
    assertThat(nobody.get("sourceSnapshot").get("shifts").get("scheduled").asInt()).isZero();
    assertThat(nobody.get("highlights").get(0).get("factType").asText())
        .isEqualTo("SCHEDULED_SHIFTS");
  }

  @Test
  void filtersBehaveExactlyAsInTheOperationalStatus() throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    ScheduleWorld other = new ScheduleWorld(mvc, jdbc, lookups);
    clock.set(t.start().plus(GRACE).plus(MICRO));

    String site = "siteId=" + w.site();
    String entity = "legalEntityId=" + w.org.legalEntityId();
    for (String query : List.of(site, entity, site + "&" + entity, "siteId=" + w.otherSite)) {
      sent.clear();
      JsonNode status = ok(w, read(w.admin, ADMIN_STATUS, query));
      JsonNode briefing = ok(w, generate(w.admin, ADMIN + "?" + query, EN));
      assertThat(briefing.get("sourceSnapshot").get("shifts"))
          .as(query)
          .isEqualTo(status.get("shifts"));
      assertThat(briefing.get("sourceSnapshot").get("coverage"))
          .as(query)
          .isEqualTo(status.get("coverage"));
      assertThat(sent.get(0).siteFilter()).isEqualTo(query.contains("siteId"));
      assertThat(sent.get(0).legalEntityFilter()).isEqualTo(query.contains("legalEntityId"));
      // A filter is only ever a presence flag: its value never crosses the AI port.
      assertThat(sent.get(0).toJson().toString())
          .doesNotContain(w.site().toString())
          .doesNotContain(w.org.legalEntityId().toString());
    }

    // The undisclosable filters are the very same indistinguishable 404 as in MVP-100.
    String code = "OPERATIONAL_STATUS_SCOPE_NOT_FOUND";
    JsonNode malformed = w.problem(generate(w.admin, ADMIN + "?siteId=nope", EN), 404, code);
    sent.clear();
    for (String query :
        List.of(
            "siteId=" + UUID.randomUUID(),
            "siteId=" + other.site(),
            "siteId=" + w.org.legalEntityId(),
            "legalEntityId=" + w.site(),
            "legalEntityId=" + other.org.legalEntityId(),
            "siteId=%20")) {
      assertThat(withoutVolatile(w.problem(generate(w.admin, ADMIN + "?" + query, EN), 404, code)))
          .as(query)
          .isEqualTo(withoutVolatile(malformed));
    }
    assertThat(sent).isEmpty();
  }

  // ------------------------------------------------------------------------------------------
  // D101-1, D101-14: authorization and tenant isolation
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyTheRightCallerGeneratesAndATenantNeverSeesAnotherTenantsFigures() throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    ScheduleWorld other = new ScheduleWorld(mvc, jdbc, lookups);
    Person foreign = other.person("Zed", "Foreign");
    LocalDate d = other.today.plusDays(3);
    other.published(
        proposal(other.site(), d, d, List.of(shift("f", d, 8, 16, foreign.employee()))));
    clock.set(t.start().plus(GRACE).plus(MICRO));

    JsonNode mine = ok(w, generate(w.admin, ADMIN, EN));
    JsonNode theirs = ok(other, generate(other.admin, ADMIN, EN));
    assertThat(mine.get("sourceSnapshot").get("shifts").get("scheduled").asInt()).isEqualTo(6);
    assertThat(theirs.get("sourceSnapshot").get("shifts").get("scheduled").asInt()).isLessThan(2);

    w.problem(generate(t.ana().bearer(), ADMIN, EN), 403, "ACCESS_DENIED");
    w.problem(generate(w.admin, TEAM, EN), 403, "ACCESS_DENIED");
    String subject = UUID.randomUUID().toString();
    w.member(subject);
    w.problem(
        generate(ScheduleWorld.employeeBearer(w.tenant(), subject), TEAM, EN),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    assertThat(w.call(post(ADMIN).contentType("application/json").content(EN)).getStatus())
        .isEqualTo(401);
    assertThat(w.call(post(TEAM).contentType("application/json").content(EN)).getStatus())
        .isEqualTo(401);
    // Nothing was sent for a refused caller, and the first call's data holds nobody's identity.
    assertThat(sent).hasSize(2);
  }

  // ------------------------------------------------------------------------------------------
  // D101-1: the body is exactly one language
  // ------------------------------------------------------------------------------------------

  @Test
  void theBodyIsExactlyOneLanguageAndNothingIsSentOtherwise() throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    for (String body :
        List.of(
            "{\"language\":\"EN\"}",
            "{\"language\":\"de\"}",
            "{\"language\":\"\"}",
            "{\"language\":1}",
            "{\"language\":[\"en\"]}",
            "{\"language\":\"en \"}")) {
      w.problem(generate(w.admin, ADMIN, body), 400, "OPERATIONAL_SUMMARY_LANGUAGE_INVALID");
    }
    for (String body :
        List.of(
            "{}",
            "{\"language\":null}",
            "{\"language\":\"en\",\"tenantId\":\"x\"}",
            "{\"language\":\"en\",\"siteId\":\"x\"}",
            "{\"language\":\"en\",\"facts\":[]}",
            "[]",
            "{\"language\":")) {
      w.problem(generate(w.admin, ADMIN, body), 400, "VALIDATION_FAILED");
    }
    assertThat(
            w.call(post(ADMIN).header("Authorization", w.admin).contentType("application/json"))
                .getStatus())
        .isEqualTo(400);
    assertThat(
            w.call(
                    post(ADMIN)
                        .header("Authorization", w.admin)
                        .contentType("text/plain")
                        .content(EN))
                .getStatus())
        .isEqualTo(415);
    assertThat(sent).isEmpty();
  }

  // ------------------------------------------------------------------------------------------
  // D101-4, D101-8: Core stays the authority; failures are safe and nothing partial is returned
  // ------------------------------------------------------------------------------------------

  @Test
  void aFailedOrUngroundedAiAnswerIsOneSafe503WithoutAuditOrPartialBriefing(CapturedOutput output)
      throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    clock.set(t.start().plus(GRACE).plus(MICRO));
    org.mockito.Mockito.clearInvocations(
        (AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));

    when(ai.generate(any())).thenThrow(new AiServiceUnavailableException("timeout"));
    JsonNode unavailable =
        w.problem(generate(w.admin, ADMIN, EN), 503, "OPERATIONAL_SUMMARY_UNAVAILABLE");
    assertThat(unavailable.has("highlights")).isFalse();
    assertThat(unavailable.has("params") ? unavailable.get("params").size() : 0).isZero();

    // An answer that selects a fact it was not given, or states another number, is refused too.
    when(ai.generate(any()))
        .thenAnswer(
            call -> {
              Request request = call.getArgument(0);
              var answer = OperationalSummaryStub.answer(request, "MODEL");
              var first = answer.highlights().get(0);
              var tampered = new ArrayList<>(answer.highlights());
              tampered.set(
                  0,
                  new com.divalhr.core.platform.ai.OperationalSummaryContract.Highlight(
                      first.factId(),
                      first.factType(),
                      first.severity(),
                      first.routeId(),
                      "9999 items"));
              return new com.divalhr.core.platform.ai.OperationalSummaryContract.Response(
                  answer.language(),
                  answer.generationMode(),
                  answer.templateVersion(),
                  answer.headlineCode(),
                  answer.headlineText(),
                  tampered,
                  answer.limitations());
            });
    w.problem(generate(w.admin, ADMIN, EN), 503, "OPERATIONAL_SUMMARY_UNAVAILABLE");

    verify(audit, never()).record(any());
    String logs = output.getAll();
    assertThat(logs).contains("operational_summary_request").doesNotContain("9999 items");
  }

  @Test
  void noDatabaseTransactionIsOpenWhileTheAiServiceIsCalled() throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    ok(w, generate(w.admin, ADMIN, EN));
    ok(w, generate(t.mia().bearer(), TEAM, EN));
    assertThat(transactionDuringCall).containsExactly(false, false);
  }

  // ------------------------------------------------------------------------------------------
  // D101-12: the fail-closed audit, the privacy of the response and of the logs
  // ------------------------------------------------------------------------------------------

  @Test
  void everyBriefingIsAFailClosedAuditedDisclosureWithoutPersonalDataAnywhere(CapturedOutput output)
      throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    clock.set(t.start().plus(GRACE).plus(MICRO));
    org.mockito.Mockito.clearInvocations(
        (AuditRecorder) AopTestUtils.getUltimateTargetObject(audit));
    int logged = output.getAll().length();

    JsonNode admin = ok(w, generate(w.admin, ADMIN + "?siteId=" + w.site(), EN));
    JsonNode team = ok(w, generate(t.mia().bearer(), TEAM, FR));

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, org.mockito.Mockito.atLeast(2)).record(events.capture());
    List<AuditEvent> mine =
        events.getAllValues().stream().filter(e -> e.action().startsWith("ai.")).toList();
    assertThat(mine)
        .extracting(AuditEvent::action)
        .containsExactly("ai.operational-summary-admin", "ai.operational-summary-team");
    AuditEvent first = mine.get(0);
    assertThat(first.result()).isEqualTo("SUCCESS");
    assertThat(first.afterStateSha256()).matches("[0-9a-f]{64}");
    assertThat(first.metadata())
        .containsOnlyKeys(
            "schemaVersion",
            "language",
            "scopeKind",
            "siteFilter",
            "legalEntityFilter",
            "generationMode",
            "selectedFactCount",
            "severityBands",
            "templateVersion")
        .containsEntry("language", "en")
        .containsEntry("scopeKind", "ORGANIZATION")
        .containsEntry("siteFilter", true)
        .containsEntry("legalEntityFilter", false)
        .containsEntry("generationMode", "MODEL");
    assertThat(mine.get(1).metadata())
        .containsEntry("language", "fr")
        .containsEntry("scopeKind", "TEAM");

    String everything =
        admin.toString()
            + team
            + first.metadata()
            + mine.get(1).metadata()
            + output.getAll().substring(logged);
    for (Person person : List.of(t.ana(), t.ben(), t.cy(), t.dee(), t.mia())) {
      assertThat(everything)
          .doesNotContain(person.number())
          .doesNotContain(person.employee().toString())
          .doesNotContain(person.subject());
    }
    assertThat(everything)
        .doesNotContain("Kalala")
        .doesNotContain("Mbuyi")
        .doesNotContain(w.site().toString())
        .doesNotContain(w.adminSubject)
        .doesNotContain(w.tenant().toString());
    assertThat(output.getAll().substring(logged)).contains("operational_summary_request");
    // No new table holds a briefing: the audit digest is the only trace.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.audit_event WHERE action LIKE 'ai.%' AND tenant_id ="
                    + " ?",
                Integer.class, w.tenant()))
        .isEqualTo(2);
  }

  // ------------------------------------------------------------------------------------------
  // D101-14: rate limiting
  // ------------------------------------------------------------------------------------------

  @Test
  void aSubjectIsRateLimitedAndTheRefusalCallsNothing() throws Exception {
    Day t = day();
    ScheduleWorld w = t.w();
    for (int i = 0; i < 30; i++) {
      ok(w, generate(w.admin, ADMIN, EN));
    }
    int calls = sent.size();
    MockHttpServletResponse limited = w.call(generate(w.admin, ADMIN, EN));
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(
            JSON.readTree(limited.getContentAsString(StandardCharsets.UTF_8)).get("code").asText())
        .isEqualTo("RATE_LIMITED");
    assertThat(limited.getHeader("Retry-After")).isNotBlank();
    assertThat(sent).hasSize(calls);
  }
}
