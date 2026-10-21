package com.divalhr.core.people.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.OperationalSummaryStub;
import com.divalhr.core.people.schedule.application.OperationalStatusService.Basis;
import com.divalhr.core.people.schedule.application.OperationalSummaryService.Briefing;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Detail;
import com.divalhr.core.people.schedule.domain.OperationalStatus.NotCheckedIn;
import com.divalhr.core.people.schedule.domain.OperationalStatus.OverdueLeave;
import com.divalhr.core.people.schedule.domain.OperationalStatus.OverdueTimesheet;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Shifts;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Status;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Waiting;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.platform.ai.AiServiceUnavailableException;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Highlight;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Request;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Response;
import com.divalhr.core.platform.ai.OperationalSummaryPort;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * MVP-101 (ADR 0027): the briefing orchestration without Spring or a database: the order of
 * language, source, AI call, output check and fail-closed audit; what each failure becomes; what
 * the audit, the metrics and the logs may contain. The end-to-end boundaries are proven in {@code
 * OperationalSummaryIntegrationTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class OperationalSummaryServiceTest {

  private static final Instant AS_OF = Instant.parse("2026-10-21T10:00:00.123456Z");
  private static final TenantId TENANT = new TenantId(UUID.randomUUID());
  private static final String SUBJECT = "6f0c2c3e-0000-4000-8000-0000000000aa";
  private static final PeopleCaller CALLER = new PeopleCaller(TENANT, SUBJECT, "corr-1");

  private OperationalStatusService statuses;
  private AuditRecorder audit;
  private OperationMetrics metrics;
  private SimpleMeterRegistry meters;
  private final AtomicInteger aiCalls = new AtomicInteger();
  private final List<Request> sent = new ArrayList<>();
  private Function<Request, Response> ai;
  private OperationalSummaryService service;

  @BeforeEach
  void setUp() {
    statuses = mock(OperationalStatusService.class);
    audit = mock(AuditRecorder.class);
    metrics = mock(OperationMetrics.class);
    meters = new SimpleMeterRegistry();
    BusinessCalendar calendar = mock(BusinessCalendar.class);
    when(calendar.now()).thenReturn(Instant.parse("2026-10-21T10:00:01.5Z"));
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    ai = request -> OperationalSummaryStub.answer(request, "MODEL");
    OperationalSummaryPort port =
        request -> {
          aiCalls.incrementAndGet();
          sent.add(request);
          return ai.apply(request);
        };
    service =
        new OperationalSummaryService(
            statuses, port, audit, metrics, meters, calendar, transactions, new Random(5));
    when(statuses.organizationBasis(any(), any(), any())).thenReturn(organization(false));
    when(statuses.teamBasis(any(), any())).thenReturn(team());
  }

  private static Status status(Integer uncovered, Shifts shifts, Waiting leave, Waiting sheets) {
    boolean team = uncovered == null;
    return new Status(
        AS_OF,
        shifts,
        uncovered,
        leave,
        sheets,
        new Detail<NotCheckedIn>(List.of(), false),
        team ? null : new Detail<Shift>(List.of(), false),
        team ? new Detail<OverdueLeave>(List.of(), false) : null,
        team ? new Detail<OverdueTimesheet>(List.of(), false) : null);
  }

  private static Basis organization(boolean filtered) {
    return new Basis(
        status(2, new Shifts(10, 2, 3, 3, 2, 1), new Waiting(4, 1), new Waiting(2, 0)),
        filtered,
        false);
  }

  private static Basis team() {
    return new Basis(
        status(null, new Shifts(3, 0, 1, 1, 1, 0), new Waiting(0, 0), new Waiting(1, 1)),
        false,
        false);
  }

  private AuditEvent recorded() {
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(event.capture());
    return event.getValue();
  }

  @Test
  void aBriefingIsCheckedAuditedAndReturned(CapturedOutput output) {
    Briefing briefing = service.tenant(CALLER, "en", null, null);

    assertThat(briefing.response().highlights()).isNotEmpty();
    assertThat(briefing.generatedAt()).isEqualTo(Instant.parse("2026-10-21T10:00:01.5Z"));
    assertThat(briefing.request().scopeKind()).isEqualTo("ORGANIZATION");
    verify(metrics).record(OperationalSummaryService.ADMIN_OPERATION, Outcome.LISTED);

    AuditEvent event = recorded();
    assertThat(event.action()).isEqualTo("ai.operational-summary-admin");
    assertThat(event.result()).isEqualTo("SUCCESS");
    assertThat(event.actorSubject()).isEqualTo(SUBJECT);
    assertThat(event.tenantId()).isEqualTo(TENANT.value());
    assertThat(event.afterStateSha256()).matches("[0-9a-f]{64}");
    assertThat(event.metadata())
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
        .containsEntry("generationMode", "MODEL")
        .containsEntry("selectedFactCount", briefing.response().highlights().size());
    // Never text, counts, fact IDs, the subject or the instants in the audit metadata.
    String metadata = event.metadata().toString();
    for (Highlight highlight : briefing.response().highlights()) {
      assertThat(metadata)
          .doesNotContain(highlight.text())
          .doesNotContain(highlight.factId() + ",");
    }
    assertThat(metadata).doesNotContain(SUBJECT).doesNotContain("2026-");

    // Telemetry: bounded classes only.
    String logs = output.getAll();
    assertThat(logs).contains("operational_summary_request");
    assertThat(logs).doesNotContain(SUBJECT).doesNotContain(TENANT.value().toString());
    for (Highlight highlight : briefing.response().highlights()) {
      assertThat(logs).doesNotContain(highlight.text());
    }
    assertThat(meters.getMeters()).isNotEmpty();
    meters
        .getMeters()
        .forEach(
            meter -> {
              meter
                  .getId()
                  .getTags()
                  .forEach(tag -> assertThat(tag.getValue()).doesNotContain(SUBJECT));
            });
  }

  @Test
  void theTeamBriefingUsesItsOwnOperationAndAuditAction() {
    Briefing briefing = service.team(CALLER, "fr");
    assertThat(briefing.request().scopeKind()).isEqualTo("TEAM");
    assertThat(briefing.request().language()).isEqualTo("fr");
    assertThat(briefing.request().uncovered()).isNull();
    assertThat(recorded().action()).isEqualTo("ai.operational-summary-team");
    verify(metrics).record(OperationalSummaryService.TEAM_OPERATION, Outcome.LISTED);
  }

  @Test
  void filtersAreSentOnlyAsPresenceFlags() {
    when(statuses.organizationBasis(any(), eq("11111111-1111-4111-8111-111111111111"), any()))
        .thenReturn(organization(true));
    service.tenant(CALLER, "en", "11111111-1111-4111-8111-111111111111", null);
    assertThat(sent.get(0).siteFilter()).isTrue();
    assertThat(sent.get(0).legalEntityFilter()).isFalse();
    assertThat(sent.get(0).toJson().toString()).doesNotContain("11111111");
  }

  @Test
  void aLanguageThatIsNotExactlyEnOrFrIsRejectedBeforeAnythingIsRead() {
    for (Object bad : new Object[] {"EN", "de", "", " fr", 1, List.of("en")}) {
      assertThatThrownBy(() -> service.tenant(CALLER, bad, null, null))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.code()).isEqualTo(ErrorCode.OPERATIONAL_SUMMARY_LANGUAGE_INVALID));
    }
    verify(statuses, never()).organizationBasis(any(), any(), any());
    assertThat(aiCalls.get()).isZero();
    verify(audit, never()).record(any());
  }

  @Test
  void anInconsistentSourceFailsClosedWithoutCallingTheAiService() {
    Status broken = status(0, new Shifts(5, 1, 1, 1, 1, 0), new Waiting(0, 0), new Waiting(0, 0));
    when(statuses.organizationBasis(any(), any(), any()))
        .thenReturn(new Basis(broken, false, false));
    assertThatThrownBy(() -> service.tenant(CALLER, "en", null, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.OPERATIONAL_SUMMARY_SOURCE_INVALID));
    assertThat(aiCalls.get()).isZero();
    verify(audit, never()).record(any());
    verify(metrics).record(OperationalSummaryService.ADMIN_OPERATION, Outcome.FAILURE);
  }

  @Test
  void everyAiFailureIsTheSameSafeUnavailableAnswerWithNoAuditAndNoPartialBriefing(
      CapturedOutput output) {
    ai =
        request -> {
          throw new AiServiceUnavailableException("timeout");
        };
    assertThatThrownBy(() -> service.tenant(CALLER, "en", null, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.OPERATIONAL_SUMMARY_UNAVAILABLE);
              assertThat(e.params()).isEmpty();
            });
    verify(audit, never()).record(any());
    assertThat(output.getAll()).doesNotContain("Briefing of the day");
    assertThat(
            meters
                .find("divalhr.ai.operational_summary")
                .tag("failure", "timeout")
                .tag("outcome", "failure")
                .counter())
        .isNotNull();
  }

  @Test
  void anUngroundedAnswerIsRejectedAsUnavailable() {
    List<Function<Response, Response>> tamperings =
        List.of(
            r -> withHighlights(r, r.highlights().subList(1, r.highlights().size())),
            r -> withText(r, "9999 more items"),
            r -> withText(r, r.highlights().get(0).text() + " The employee was absent."),
            r -> withText(r, r.highlights().get(0).text() + " https://x.example"),
            r ->
                new Response(
                    "fr",
                    r.generationMode(),
                    r.templateVersion(),
                    r.headlineCode(),
                    r.headlineText(),
                    r.highlights(),
                    r.limitations()));
    for (Function<Response, Response> tamper : tamperings) {
      ai = request -> tamper.apply(OperationalSummaryStub.answer(request, "MODEL"));
      assertThatThrownBy(() -> service.tenant(CALLER, "en", null, null))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.code()).isEqualTo(ErrorCode.OPERATIONAL_SUMMARY_UNAVAILABLE));
    }
    verify(audit, never()).record(any());
  }

  private static Response withHighlights(Response r, List<Highlight> highlights) {
    return new Response(
        r.language(),
        r.generationMode(),
        r.templateVersion(),
        r.headlineCode(),
        r.headlineText(),
        highlights,
        r.limitations());
  }

  private static Response withText(Response r, String text) {
    List<Highlight> list = new ArrayList<>(r.highlights());
    Highlight h = list.get(0);
    list.set(0, new Highlight(h.factId(), h.factType(), h.severity(), h.routeId(), text));
    return withHighlights(r, list);
  }

  @Test
  void theAuditIsFailClosed() {
    org.mockito.Mockito.doThrow(new IllegalStateException("audit store down"))
        .when(audit)
        .record(any());
    assertThatThrownBy(() -> service.tenant(CALLER, "en", null, null))
        .isInstanceOf(IllegalStateException.class);
    verify(metrics).record(OperationalSummaryService.ADMIN_OPERATION, Outcome.FAILURE);
  }

  @Test
  void aFallbackBriefingIsAuditedWithItsMode() {
    ai = request -> OperationalSummaryStub.answer(request, "FALLBACK");
    Briefing briefing = service.tenant(CALLER, "en", null, null);
    assertThat(briefing.response().generationMode()).isEqualTo("FALLBACK");
    assertThat(recorded().metadata()).containsEntry("generationMode", "FALLBACK");
  }

  @Test
  void whatIsSentIsAggregateOnlyAndEveryFactIsClosed() {
    service.tenant(CALLER, "en", null, null);
    Request request = sent.get(0);
    assertThat(request.toJson().keySet())
        .containsExactly(
            "schemaVersion",
            "language",
            "asOfInstant",
            "scope",
            "thresholds",
            "shifts",
            "coverage",
            "approvals",
            "facts");
    assertThat(request.facts())
        .allSatisfy(
            fact -> {
              assertThat(fact.factId()).matches("f([1-9]|1[01])");
              assertThat(fact.value()).isNotNegative();
            });
    assertThat(request.asOfInstant()).isEqualTo(AS_OF.toString());
  }

  @Test
  void latencyBandsAreBounded() {
    assertThat(OperationalSummaryService.latencyBand(java.time.Duration.ofMillis(10)))
        .isEqualTo("<=500ms");
    assertThat(OperationalSummaryService.latencyBand(java.time.Duration.ofMillis(900)))
        .isEqualTo("<=1s");
    assertThat(OperationalSummaryService.latencyBand(java.time.Duration.ofMillis(1_500)))
        .isEqualTo("<=2s");
    assertThat(OperationalSummaryService.latencyBand(java.time.Duration.ofMillis(4_000)))
        .isEqualTo("<=5s");
    assertThat(OperationalSummaryService.latencyBand(java.time.Duration.ofSeconds(9)))
        .isEqualTo(">5s");
  }
}
