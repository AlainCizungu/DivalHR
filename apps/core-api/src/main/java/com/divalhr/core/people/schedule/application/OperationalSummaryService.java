package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.application.OperationalStatusService.Basis;
import com.divalhr.core.people.schedule.domain.OperationalSummary;
import com.divalhr.core.people.schedule.domain.OperationalSummary.InvalidOutputException;
import com.divalhr.core.people.schedule.domain.OperationalSummary.InvalidSourceException;
import com.divalhr.core.people.schedule.domain.OperationalSummary.Language;
import com.divalhr.core.platform.ai.AiServiceUnavailableException;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Fact;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Highlight;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Request;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Response;
import com.divalhr.core.platform.ai.OperationalSummaryPort;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-101 (Issue #156, ADR 0027): the grounded daily operational briefing. On request it reads the
 * MVP-100 counts of the caller's own audience at one server-owned instant, builds the closed
 * candidate facts, asks the AI service (authenticated as the dedicated Core AI client) to select
 * and render them, checks everything that comes back, records a fail-closed audit event and returns
 * the briefing. Nothing is stored: no briefing, no prompt, no cache, no history.
 *
 * <p>The AI service receives aggregate counts, fixed thresholds, closed enumerations, opaque
 * per-request fact IDs and the as-of instant, and nothing else (see {@link OperationalSummary}).
 * The database read finishes before the AI call starts, so no connection is held while waiting.
 */
@Service
public class OperationalSummaryService {

  /** The manager's briefing (interceptor operation). */
  public static final String TEAM_OPERATION = "operations.status-summary-team";

  /** The tenant administrator's briefing (interceptor operation). */
  public static final String ADMIN_OPERATION = "operations.status-summary-admin";

  /** Audit action of the manager's briefing. */
  public static final String TEAM_AUDIT = "ai.operational-summary-team";

  /** Audit action of the administrator's briefing. */
  public static final String ADMIN_AUDIT = "ai.operational-summary-admin";

  /** Per-subject rate-limit bucket (both audiences). */
  public static final String SUBJECT_BUCKET = "operational-summary";

  /** Per-tenant rate-limit bucket (both audiences). */
  public static final String TENANT_BUCKET = "operational-summary";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.ai");
  private static final String METER = "divalhr.ai.operational_summary";
  private static final Duration AUDIT_TIMEOUT = Duration.ofSeconds(5);

  private final OperationalStatusService statuses;
  private final OperationalSummaryPort ai;
  private final AuditRecorder audit;
  private final OperationMetrics metrics;
  private final MeterRegistry meters;
  private final BusinessCalendar calendar;
  private final PlatformTransactionManager transactionManager;
  private final Random random;

  /**
   * Creates the service.
   *
   * @param statuses the MVP-100 counts
   * @param ai the AI service port
   * @param audit audit recorder
   * @param metrics operation metrics
   * @param meters meter registry
   * @param calendar the platform clock
   * @param transactionManager transactions
   */
  @Autowired
  public OperationalSummaryService(
      OperationalStatusService statuses,
      OperationalSummaryPort ai,
      AuditRecorder audit,
      OperationMetrics metrics,
      MeterRegistry meters,
      BusinessCalendar calendar,
      PlatformTransactionManager transactionManager) {
    this(statuses, ai, audit, metrics, meters, calendar, transactionManager, new SecureRandom());
  }

  /** Test seam: a deterministic fact-ID permutation. */
  OperationalSummaryService(
      OperationalStatusService statuses,
      OperationalSummaryPort ai,
      AuditRecorder audit,
      OperationMetrics metrics,
      MeterRegistry meters,
      BusinessCalendar calendar,
      PlatformTransactionManager transactionManager,
      Random random) {
    this.statuses = statuses;
    this.ai = ai;
    this.audit = audit;
    this.metrics = metrics;
    this.meters = meters;
    this.calendar = calendar;
    this.transactionManager = transactionManager;
    this.random = random;
  }

  /**
   * A checked briefing.
   *
   * @param request what the AI service was sent (also the source snapshot shown to the user)
   * @param response the validated briefing
   * @param generatedAt when the briefing was produced (server clock)
   */
  public record Briefing(Request request, Response response, Instant generatedAt) {}

  /**
   * The briefing of the caller's team.
   *
   * @param caller verified employee
   * @param language requested language, the exact wire value
   * @return the briefing
   */
  public Briefing team(PeopleCaller caller, Object language) {
    Language requested = language(language);
    return run(
        TEAM_OPERATION,
        TEAM_AUDIT,
        caller,
        requested,
        () -> statuses.teamBasis(caller, TEAM_OPERATION));
  }

  /**
   * The briefing of the organization, optionally of one site and/or one legal entity.
   *
   * @param caller verified tenant administrator (exact MFA)
   * @param language requested language, the exact wire value
   * @param siteId site filter or {@code null}
   * @param legalEntityId legal-entity filter or {@code null}
   * @return the briefing
   */
  public Briefing tenant(
      PeopleCaller caller, Object language, String siteId, String legalEntityId) {
    Language requested = language(language);
    return run(
        ADMIN_OPERATION,
        ADMIN_AUDIT,
        caller,
        requested,
        () -> statuses.organizationBasis(caller, siteId, legalEntityId));
  }

  private static Language language(Object raw) {
    return Language.parse(raw)
        .orElseThrow(
            () -> new ApiException(ErrorCode.OPERATIONAL_SUMMARY_LANGUAGE_INVALID, Map.of()));
  }

  private Briefing run(
      String operation,
      String auditAction,
      PeopleCaller caller,
      Language language,
      Supplier<Basis> basisSource) {
    long started = System.nanoTime();
    String failure = null;
    String mode = null;
    int selected = 0;
    try {
      Basis basis = basisSource.get();
      Request request;
      try {
        request =
            OperationalSummary.snapshot(
                basis.status(), language, basis.siteFilter(), basis.legalEntityFilter(), random);
      } catch (InvalidSourceException invalid) {
        failure = "source_invalid";
        throw new ApiException(ErrorCode.OPERATIONAL_SUMMARY_SOURCE_INVALID, Map.of());
      }
      Response response;
      try {
        response = ai.generate(request);
      } catch (AiServiceUnavailableException unavailable) {
        failure = unavailable.reason();
        throw new ApiException(ErrorCode.OPERATIONAL_SUMMARY_UNAVAILABLE, Map.of());
      }
      try {
        OperationalSummary.validate(request, response);
      } catch (InvalidOutputException invalid) {
        failure = "output_" + invalid.reason();
        throw new ApiException(ErrorCode.OPERATIONAL_SUMMARY_UNAVAILABLE, Map.of());
      }
      Instant generatedAt = calendar.now().truncatedTo(ChronoUnit.MICROS);
      mode = response.generationMode();
      selected = response.highlights().size();
      disclose(caller, auditAction, request, response, generatedAt);
      metrics.record(operation, Outcome.LISTED);
      observe(operation, "listed", language, mode, null, started, selected);
      return new Briefing(request, response, generatedAt);
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case EMPLOYEE_LINK_REQUIRED -> Outcome.DENIED;
            case OPERATIONAL_STATUS_SCOPE_NOT_FOUND -> Outcome.NOT_FOUND;
            case OPERATIONAL_SUMMARY_SOURCE_INVALID, OPERATIONAL_SUMMARY_UNAVAILABLE ->
                Outcome.FAILURE;
            default -> Outcome.VALIDATION_FAILED;
          };
      metrics.record(operation, outcome);
      observe(
          operation, outcome.name().toLowerCase(Locale.ROOT), language, mode, failure, started, 0);
      throw rejected;
    } catch (RuntimeException broken) {
      metrics.record(operation, Outcome.FAILURE);
      observe(operation, "failure", language, mode, "internal", started, 0);
      throw broken;
    }
  }

  /**
   * The fail-closed disclosure audit: language, scope kind, filter presence, mode, count of
   * selected facts, their severity bands and the template version. The digest binds tenant, actor,
   * as-of instant, the exact governed snapshot and the selected fact IDs; it is a one-way hash, and
   * neither the snapshot, the IDs nor any text is stored.
   */
  private void disclose(
      PeopleCaller caller, String action, Request request, Response response, Instant generatedAt) {
    List<String> bands = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    for (Highlight highlight : response.highlights()) {
      bands.add(highlight.severity());
      ids.add(highlight.factId());
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", WorkScheduleService.SCHEMA_VERSION);
    metadata.put("language", request.language());
    metadata.put("scopeKind", request.scopeKind());
    metadata.put("siteFilter", request.siteFilter());
    metadata.put("legalEntityFilter", request.legalEntityFilter());
    metadata.put("generationMode", response.generationMode());
    metadata.put("selectedFactCount", response.highlights().size());
    metadata.put("severityBands", List.copyOf(bands));
    metadata.put("templateVersion", response.templateVersion());
    StringBuilder text =
        new StringBuilder("DIVALHR-OPERATIONAL-SUMMARY-DISCLOSURE\nversion=1\ntenant=")
            .append(caller.tenant().value())
            .append("\nactor=")
            .append(caller.subject())
            .append("\nasOf=")
            .append(request.asOfInstant())
            .append("\nscope=")
            .append(request.scopeKind())
            .append(':')
            .append(request.siteFilter())
            .append(':')
            .append(request.legalEntityFilter())
            .append('\n');
    for (Fact fact : request.facts()) {
      text.append("fact;id=")
          .append(fact.factId())
          .append(";type=")
          .append(fact.type())
          .append(";value=")
          .append(fact.value())
          .append(";severity=")
          .append(fact.severity())
          .append('\n');
    }
    text.append("selected=").append(String.join(",", ids)).append('\n');
    AuditEvent event =
        new AuditEvent(
            UUID.randomUUID(),
            generatedAt,
            caller.subject(),
            action,
            "organization",
            caller.tenant().value(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text.toString()));
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) AUDIT_TIMEOUT.toSeconds());
    template.executeWithoutResult(tx -> audit.record(event));
  }

  /** Bounded telemetry: operation, outcome, language, mode, failure class, latency band, count. */
  private void observe(
      String operation,
      String outcome,
      Language language,
      String mode,
      String failure,
      long startedNanos,
      int selected) {
    String band = latencyBand(Duration.ofNanos(System.nanoTime() - startedNanos));
    Counter.builder(METER)
        .description("Operational summary requests by bounded outcome")
        .tag("operation", operation)
        .tag("outcome", outcome)
        .tag("language", language.wire())
        .tag("mode", mode == null ? "none" : mode.toLowerCase(Locale.ROOT))
        .tag("failure", failure == null ? "none" : failure)
        .register(meters)
        .increment();
    LOG.atInfo()
        .addKeyValue("operation", operation)
        .addKeyValue("outcome", outcome)
        .addKeyValue("language", language.wire())
        .addKeyValue("generationMode", mode == null ? "none" : mode)
        .addKeyValue("failureClass", failure == null ? "none" : failure)
        .addKeyValue("latencyBand", band)
        .addKeyValue("selectedFacts", selected)
        .log("operational_summary_request");
  }

  static String latencyBand(Duration elapsed) {
    long millis = elapsed.toMillis();
    if (millis <= 500) {
      return "<=500ms";
    }
    if (millis <= 1_000) {
      return "<=1s";
    }
    if (millis <= 2_000) {
      return "<=2s";
    }
    return millis <= 5_000 ? "<=5s" : ">5s";
  }
}
