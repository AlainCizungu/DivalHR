package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceHandoffBodies.ReconcileHandoff;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.Page;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.Summary;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.View;
import com.divalhr.core.payroll.application.SalaryAdvanceHandoffCommands.Versioned;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffOutcome;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffRetry;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffState;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.EventRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.HandoffRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.Next;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.Position;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The tenant administrator's view of controlled partner handoffs and the manual synthetic reconcile
 * or retry (MVP-120B, Issue #206, ADR 0042). The administrator can read bounded delivery
 * diagnostics and move a stuck handoff along; they can never create, alter or replace the
 * employee's consent, and never see the canonical payload, a signature or a partner response.
 *
 * <p>A reconcile of an ambiguous delivery sends one signed status inquiry for the application id
 * (bounded by the adapter timeouts, inside the one idempotent transaction that holds the handoff
 * row lock). Only an authenticated acknowledgement moves it to RECEIVED, and only an authenticated
 * proof that the partner never received it allows a resend; anything else is a conflict that
 * changes nothing.
 */
@Service
public class SalaryAdvanceHandoffReviewService {

  /** List (interceptor operation). */
  public static final String LIST = "salary-advance-handoff.list";

  /** Detail (interceptor operation). */
  public static final String READ = "salary-advance-handoff.read";

  /** Reconcile (idempotency operation and interceptor operation). */
  public static final String RECONCILE = "salary-advance-handoff.reconcile";

  /** Per-subject reads (per minute). */
  public static final String SUBJECT_READ_BUCKET = "salary-advance-handoff-admin-read";

  /** Per-subject reconciles (per minute). */
  public static final String SUBJECT_RECONCILE_BUCKET = "salary-advance-handoff-reconcile";

  /** Per-tenant reconciles. */
  public static final String TENANT_RECONCILE_BUCKET = "salary-advance-handoff-reconcile";

  /** Administrator reconciles per subject per hour. */
  static final int RECONCILES_PER_HOUR = 20;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.salary-advance-handoff");
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String ADMIN_ROLE = "TENANT_ADMIN";
  private static final String POSITION_CODE = "HOF";
  private static final int MAX_RECONCILES = 3;
  private static final IdempotentOperation.Spec RECONCILE_SPEC =
      new IdempotentOperation.Spec(
          RECONCILE, "salary_advance_handoff", "reconcile", "reconciled", 200);

  private final IdempotentOperation operations;
  private final JdbcSalaryAdvanceHandoffRepository handoffs;
  private final JdbcSalaryAdvanceRepository requests;
  private final CompensationCalendar calendar;
  private final SalaryAdvanceHandoffRecorder recorder;
  private final SalaryAdvanceQuotas quotas;
  private final SalaryAdvancePartnerProfiles profiles;
  private final SalaryAdvancePartnerSecrets secrets;
  private final SalaryAdvancePartnerGateway gateway;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param handoffs handoff storage
   * @param requests request storage (lock order: request first)
   * @param calendar business time
   * @param recorder audit and outbox
   * @param quotas hourly quotas
   * @param profiles operator-controlled partner profile
   * @param secrets partner signing keys
   * @param gateway the partner port
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   */
  public SalaryAdvanceHandoffReviewService(
      IdempotentOperation operations,
      JdbcSalaryAdvanceHandoffRepository handoffs,
      JdbcSalaryAdvanceRepository requests,
      CompensationCalendar calendar,
      SalaryAdvanceHandoffRecorder recorder,
      SalaryAdvanceQuotas quotas,
      SalaryAdvancePartnerProfiles profiles,
      SalaryAdvancePartnerSecrets secrets,
      SalaryAdvancePartnerGateway gateway,
      PayrollOperations plumbing,
      CursorCodec cursors) {
    this.operations = operations;
    this.handoffs = handoffs;
    this.requests = requests;
    this.calendar = calendar;
    this.recorder = recorder;
    this.quotas = quotas;
    this.profiles = profiles;
    this.secrets = secrets;
    this.gateway = gateway;
    this.plumbing = plumbing;
    this.cursors = cursors;
  }

  /**
   * The tenant's handoffs, newest first.
   *
   * @param caller verified tenant administrator
   * @param state raw state filter, or {@code null}
   * @param reviewState raw partner-review status filter, or {@code null}
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public Page list(
      PayrollCaller caller, String state, String reviewState, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "salary_advance_handoff_list",
        LIST,
        () -> {
          FieldErrors errors = new FieldErrors();
          int size = SalaryAdvanceCommands.limit(limit, errors);
          String filter = SalaryAdvanceHandoffReads.stateFilter(state, errors);
          String reviewFilter = SalaryAdvanceHandoffReads.reviewFilter(reviewState, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                Instant now = calendar.now();
                quotas.requireCounted(
                    "handoff-admin-read",
                    SalaryAdvanceQuotas.READS_PER_HOUR,
                    tenant,
                    caller.subject(),
                    now);
                CursorScope scope = scope(caller, filter, reviewFilter, size);
                Position after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  HandoffRow at =
                      handoffs
                          .handoff(tenant, position.id())
                          .filter(row -> POSITION_CODE.equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  after = new Position(at.authorizedAt(), at.id());
                }
                List<HandoffRow> rows =
                    handoffs.page(tenant, null, filter, reviewFilter, after, size + 1);
                List<HandoffRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope,
                          new KeysetPosition(POSITION_CODE, shown.get(shown.size() - 1).id()));
                }
                recorder.disclosed(
                    caller,
                    SalaryAdvanceHandoffRecorder.READ,
                    tenant.value(),
                    cursor == null ? "tenant-list-first" : "tenant-list-cursor",
                    now);
                List<Summary> items =
                    shown.stream()
                        .map(row -> SalaryAdvanceHandoffViews.summary(row, true))
                        .toList();
                return new Page(items, next);
              });
        });
  }

  /**
   * One handoff with its delivery diagnostics.
   *
   * @param caller verified tenant administrator
   * @param handoffId raw path value
   * @return the view, after its disclosure audit committed
   */
  public View get(PayrollCaller caller, String handoffId) {
    return plumbing.observed(
        LOG,
        "salary_advance_handoff_read",
        READ,
        () -> {
          UUID id = SalaryAdvanceCommands.id(handoffId, ErrorCode.SALARY_ADVANCE_HANDOFF_NOT_FOUND);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                Instant now = calendar.now();
                quotas.requireCounted(
                    "handoff-admin-read",
                    SalaryAdvanceQuotas.READS_PER_HOUR,
                    tenant,
                    caller.subject(),
                    now);
                HandoffRow row =
                    handoffs
                        .handoff(tenant, id)
                        .orElseThrow(
                            () ->
                                new ApiException(
                                    ErrorCode.SALARY_ADVANCE_HANDOFF_NOT_FOUND, Map.of()));
                View view = SalaryAdvanceHandoffReads.view(handoffs, tenant, row.id(), true);
                recorder.disclosed(
                    caller, SalaryAdvanceHandoffRecorder.READ, row.id(), "tenant-handoff", now);
                return view;
              });
        });
  }

  /**
   * Reconciles an ambiguous delivery with the partner, or retries a failed one, or replays an
   * identical command.
   *
   * @param caller verified tenant administrator
   * @param handoffId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the handoff version the administrator saw and the confirmation
   * @return the handoff after the action
   */
  public IdempotentOperation.Result<View> reconcile(
      PayrollCaller caller, String handoffId, String idempotencyKey, ReconcileHandoff body) {
    return plumbing.command(
        RECONCILE,
        () -> {
          record Parsed(UUID id, Versioned action) {}
          Parsed parsed =
              operations.validated(
                  RECONCILE_SPEC,
                  () ->
                      new Parsed(
                          SalaryAdvanceCommands.id(
                              handoffId, ErrorCode.SALARY_ADVANCE_HANDOFF_NOT_FOUND),
                          SalaryAdvanceHandoffCommands.versioned(idempotencyKey, body)));
          return operations.execute(
              RECONCILE_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.action().canonical(caller.tenant(), "handoffId", parsed.id()),
              View.class,
              TIMEOUT,
              () -> reconciled(caller, parsed.id(), parsed.action()));
        });
  }

  private IdempotentOperation.Completed<View> reconciled(
      PayrollCaller caller, UUID handoffId, Versioned command) {
    TenantId tenant = caller.tenant();
    handoffs.limits(tenant, TIMEOUT);
    Instant now = calendar.now();
    quotas.requireCommands(
        handoffs.eventsSince(tenant, caller.subject(), ADMIN_ROLE, now.minus(Duration.ofHours(1))),
        RECONCILES_PER_HOUR,
        now);
    HandoffRow seen =
        handoffs
            .handoff(tenant, handoffId)
            .orElseThrow(
                () -> new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_NOT_FOUND, Map.of()));
    // The request first, then its handoff: the same lock order everywhere both are held.
    requests.lockRequest(tenant, seen.requestId());
    HandoffRow row =
        handoffs
            .lock(tenant, handoffId)
            .orElseThrow(
                () -> new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_NOT_FOUND, Map.of()));
    if (row.version() != command.expectedVersion()) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_VERSION_CONFLICT, Map.of());
    }
    if (caller.subject().equals(row.authorizedBy())) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_SELF_REVIEW_FORBIDDEN, Map.of());
    }
    SalaryAdvanceHandoffState state =
        SalaryAdvanceHandoffState.parse(row.state())
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED, Map.of()));
    Step step =
        switch (state) {
          case FAILED -> retry(row);
          case DELIVERY_UNKNOWN -> inquire(row, now);
          default ->
              throw new ApiException(
                  ErrorCode.SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED, Map.of());
        };
    int version = row.version() + 1;
    try {
      handoffs.insertEvent(
          tenant,
          row.id(),
          new EventRow(
              handoffs.nextEventSequence(tenant, row.id()),
              row.state(),
              step.next().state(),
              ADMIN_ROLE,
              caller.subject(),
              now,
              step.outcome().name()));
      if (step.acknowledgement() != null) {
        handoffs.insertAck(tenant, row.id(), step.acknowledgement(), now, "RECONCILE");
      }
      if (handoffs.update(tenant, row.id(), row.version(), step.next().at(now)) != 1) {
        throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_VERSION_CONFLICT, Map.of());
      }
      handoffs.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw SalaryAdvanceHandoffService.integrity(violated);
    }
    recorder.reconciled(
        caller,
        new SalaryAdvanceHandoffRecorder.Change(
            row.id(),
            row.requestId(),
            row.legalEntityId(),
            row.currency(),
            step.next().state(),
            step.next().reviewState(),
            version,
            step.outcome().name(),
            null,
            0,
            ADMIN_ROLE,
            now));
    LOG.atInfo()
        .addKeyValue("operation", RECONCILE)
        .addKeyValue("outcome", step.outcome().name())
        .log("salary_advance_handoff_reconciled");
    return new IdempotentOperation.Completed<>(
        SalaryAdvanceHandoffReads.view(handoffs, tenant, row.id(), true),
        row.id(),
        Outcome.UPDATED);
  }

  /** A FAILED handoff goes back to QUEUED for a bounded new delivery round. */
  private Step retry(HandoffRow row) {
    if (row.adminRetryCount() >= SalaryAdvanceHandoffRetry.MAX_ADMIN_ACTIONS) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED, Map.of());
    }
    return new Step(
        SalaryAdvanceHandoffOutcome.ADMIN_RETRY,
        null,
        new Pending(
            "QUEUED",
            null,
            row.attemptCount(),
            row.reconcileCount(),
            row.adminRetryCount() + 1,
            true));
  }

  /** A DELIVERY_UNKNOWN handoff is asked, with one signed inquiry, what the partner holds. */
  private Step inquire(HandoffRow row, Instant now) {
    if (row.reconcileCount() >= MAX_RECONCILES) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_DELIVERY_UNKNOWN, Map.of());
    }
    SalaryAdvancePartnerProfile profile =
        profiles
            .byCode(row.partnerCode())
            .orElseThrow(
                () -> new ApiException(ErrorCode.SALARY_ADVANCE_PARTNER_NOT_ENABLED, Map.of()));
    if (!secrets.deliveryAvailable()) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_PARTNER_UNAVAILABLE, Map.of());
    }
    long timestamp = now.getEpochSecond();
    byte[] body = inquiryBody(row.applicationId());
    String signature = secrets.delivery().signInquiry(timestamp, row.applicationId(), body);
    SalaryAdvancePartnerGateway.InquiryResult answer;
    try {
      answer =
          gateway.inquire(
              profile, row.applicationId(), secrets.keyVersion(), timestamp, signature, body);
    } catch (RuntimeException unexpected) {
      answer =
          new SalaryAdvancePartnerGateway.InquiryResult(
              SalaryAdvancePartnerGateway.InquiryOutcome.INCONCLUSIVE, null);
    }
    return switch (answer.outcome()) {
      case RECEIVED ->
          new Step(
              SalaryAdvanceHandoffOutcome.RECONCILED_RECEIVED,
              boundedReference(answer.reference()),
              new Pending(
                  "RECEIVED",
                  "RECEIVED",
                  row.attemptCount(),
                  row.reconcileCount() + 1,
                  row.adminRetryCount(),
                  false));
      case NOT_RECEIVED -> {
        // The only case in which a resend becomes allowed: an authenticated proof of absence.
        boolean exhausted =
            row.attemptCount()
                >= SalaryAdvanceHandoffRetry.MAX_ATTEMPTS * (row.adminRetryCount() + 1);
        yield new Step(
            SalaryAdvanceHandoffOutcome.RECONCILED_NOT_RECEIVED,
            null,
            new Pending(
                exhausted ? "FAILED" : "QUEUED",
                null,
                row.attemptCount(),
                row.reconcileCount() + 1,
                row.adminRetryCount(),
                !exhausted));
      }
      case INCONCLUSIVE ->
          throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_DELIVERY_UNKNOWN, Map.of());
    };
  }

  /** The inquiry body: the application id only. */
  static byte[] inquiryBody(UUID applicationId) {
    return ("{\"applicationId\":\"" + applicationId + "\",\"schemaVersion\":1}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String boundedReference(String reference) {
    if (reference == null || reference.isBlank() || reference.length() > 80) {
      return "SYNTHETIC-RECONCILED";
    }
    return reference;
  }

  private static CursorScope scope(
      PayrollCaller caller, String state, String reviewState, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "tenant-handoffs");
    filters.put("limit", Integer.toString(size));
    if (state != null) {
      filters.put("state", state);
    }
    if (reviewState != null) {
      filters.put("reviewState", reviewState);
    }
    return new CursorScope(LIST, caller.tenant(), filters);
  }

  /** The result of one reconcile decision. */
  private record Step(
      SalaryAdvanceHandoffOutcome outcome, String acknowledgement, Pending pending) {

    Pending next() {
      return pending;
    }
  }

  /** The columns after the decision; the instant is added when the update is written. */
  private record Pending(
      String state,
      String reviewState,
      int attemptCount,
      int reconcileCount,
      int adminRetryCount,
      boolean queued) {

    Next at(Instant now) {
      return new Next(
          state,
          reviewState,
          null,
          attemptCount,
          reconcileCount,
          adminRetryCount,
          queued ? now : null,
          null,
          now);
    }
  }
}
