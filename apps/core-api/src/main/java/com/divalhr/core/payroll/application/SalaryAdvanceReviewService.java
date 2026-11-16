package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceBodies.RefuseCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.VersionedCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestPage;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestSummary;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestView;
import com.divalhr.core.payroll.application.SalaryAdvanceCommands.Refusal;
import com.divalhr.core.payroll.application.SalaryAdvanceCommands.VersionedAction;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.ConsentRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.DecisionRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.EventRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.RequestPosition;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.RequestRow;
import com.divalhr.core.payroll.internal.SalaryAdvanceConstraintViolations;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The employer review of salary-advance requests for tenant administrators with privileged MFA
 * (MVP-120A, Issue #204, ADR 0041). The employer confirms that it is prepared to support a later
 * deduction if a regulated partner approves, or refuses with one closed reason; neither is a credit
 * decision, no money moves and no deduction is made. Authorization has run before this; tenant and
 * actor never come from the request. Every command locks the request row first and then re-reads
 * it, so exactly one of two concurrent commands wins; the other is a stable conflict.
 *
 * <p>The internal note (at most 500 characters) is stored with the decision, shown to
 * administrators only, and never appears in an employee response, an audit record, an outbox event,
 * a log or a partner-facing field. No rule stops an administrator who is also the requesting
 * employee from deciding their own request; none was asked for (open question recorded in ADR
 * 0041).
 */
@Service
public class SalaryAdvanceReviewService {

  /** List (interceptor operation). */
  public static final String LIST = "salary-advance-request.list";

  /** Detail (interceptor operation). */
  public static final String READ = "salary-advance-request.read";

  /** Employer confirmation (idempotency operation and interceptor operation). */
  public static final String CONFIRM = "salary-advance-request.confirm";

  /** Employer refusal (idempotency operation and interceptor operation). */
  public static final String REFUSE = "salary-advance-request.refuse";

  /** Per-subject reads. */
  public static final String SUBJECT_READ_BUCKET = "salary-advance-request-read";

  /** Per-subject decisions. */
  public static final String SUBJECT_DECISION_BUCKET = "salary-advance-decision-write";

  /** Per-tenant decisions. */
  public static final String TENANT_DECISION_BUCKET = "salary-advance-decision-write";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.salary-advance-review");
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final String ADMIN_ROLE = "tenant-admin";
  private static final String POSITION_CODE = "REQ";
  private static final IdempotentOperation.Spec CONFIRM_SPEC =
      new IdempotentOperation.Spec(CONFIRM, "salary_advance_decision", "confirm", "confirmed", 200);
  private static final IdempotentOperation.Spec REFUSE_SPEC =
      new IdempotentOperation.Spec(REFUSE, "salary_advance_decision", "refuse", "refused", 200);

  private final IdempotentOperation operations;
  private final JdbcSalaryAdvanceRepository repository;
  private final CompensationCalendar calendar;
  private final SalaryAdvanceRecorder recorder;
  private final SalaryAdvanceQuotas quotas;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param repository salary-advance storage
   * @param calendar business date and server time
   * @param recorder audit and outbox
   * @param quotas hourly quotas
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   */
  public SalaryAdvanceReviewService(
      IdempotentOperation operations,
      JdbcSalaryAdvanceRepository repository,
      CompensationCalendar calendar,
      SalaryAdvanceRecorder recorder,
      SalaryAdvanceQuotas quotas,
      PayrollOperations plumbing,
      CursorCodec cursors) {
    this.operations = operations;
    this.repository = repository;
    this.calendar = calendar;
    this.recorder = recorder;
    this.quotas = quotas;
    this.plumbing = plumbing;
    this.cursors = cursors;
  }

  // ------------------------------------------------------------------------------------------
  // Read
  // ------------------------------------------------------------------------------------------

  /**
   * A page of the tenant's requests, newest first.
   *
   * @param caller verified tenant administrator
   * @param status raw status filter, or {@code null}
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public RequestPage list(PayrollCaller caller, String status, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "salary_advance_request_list",
        LIST,
        () -> {
          FieldErrors errors = new FieldErrors();
          int size = SalaryAdvanceCommands.limit(limit, errors);
          String filter = SalaryAdvanceCommands.statusFilter(status, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                Instant now = calendar.now();
                quotas.requireCounted(
                    "admin-read",
                    SalaryAdvanceQuotas.READS_PER_HOUR,
                    tenant,
                    caller.subject(),
                    now);
                CursorScope scope = scope(caller, filter, size);
                RequestPosition after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  RequestRow at =
                      repository
                          .request(tenant, position.id())
                          .filter(row -> POSITION_CODE.equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  after = new RequestPosition(at.submittedAt(), at.id());
                }
                List<RequestRow> rows =
                    repository.requestPage(tenant, null, filter, after, size + 1);
                List<RequestRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope,
                          new KeysetPosition(POSITION_CODE, shown.get(shown.size() - 1).id()));
                }
                recorder.disclosed(
                    caller,
                    SalaryAdvanceRecorder.REQUEST_READ,
                    "salary-advance-request",
                    tenant.value(),
                    "list",
                    cursor == null,
                    shown.size(),
                    now);
                List<RequestSummary> items =
                    shown.stream().map(row -> SalaryAdvanceViews.summary(row, true)).toList();
                return new RequestPage(items, next);
              });
        });
  }

  /**
   * One request with its frozen evidence, history and the internal note.
   *
   * @param caller verified tenant administrator
   * @param requestId raw path value
   * @return the view, after its disclosure audit committed
   */
  public RequestView get(PayrollCaller caller, String requestId) {
    return plumbing.observed(
        LOG,
        "salary_advance_request_read",
        READ,
        () -> {
          UUID id = SalaryAdvanceCommands.id(requestId, ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                Instant now = calendar.now();
                quotas.requireCounted(
                    "admin-read",
                    SalaryAdvanceQuotas.READS_PER_HOUR,
                    tenant,
                    caller.subject(),
                    now);
                RequestRow row =
                    repository
                        .request(tenant, id)
                        .orElseThrow(
                            () ->
                                new ApiException(
                                    ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND, Map.of()));
                RequestView view = view(tenant, row.id());
                recorder.disclosed(
                    caller,
                    SalaryAdvanceRecorder.REQUEST_READ,
                    "salary-advance-request",
                    row.id(),
                    "request",
                    true,
                    1,
                    now);
                return view;
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Decisions
  // ------------------------------------------------------------------------------------------

  /**
   * Records that the employer is prepared to support a later deduction if a regulated partner
   * approves (not a credit approval), or replays an identical confirmation.
   *
   * @param caller verified tenant administrator
   * @param requestId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request version the caller saw and the confirmation
   * @return the confirmed request
   */
  public IdempotentOperation.Result<RequestView> confirm(
      PayrollCaller caller, String requestId, String idempotencyKey, VersionedCommand body) {
    return plumbing.command(
        CONFIRM,
        () -> {
          record Parsed(UUID id, VersionedAction action) {}
          Parsed parsed =
              operations.validated(
                  CONFIRM_SPEC,
                  () ->
                      new Parsed(
                          SalaryAdvanceCommands.id(
                              requestId, ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND),
                          SalaryAdvanceCommands.versioned(idempotencyKey, body)));
          return operations.execute(
              CONFIRM_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.action().canonical(caller.tenant(), parsed.id()),
              RequestView.class,
              TIMEOUT,
              () ->
                  decided(
                      caller,
                      parsed.id(),
                      parsed.action().expectedVersion(),
                      "CONFIRMED",
                      "EMPLOYER_CONFIRMED",
                      null,
                      null));
        });
  }

  /**
   * Records a bounded employer-policy refusal (not a credit decision), or replays an identical
   * refusal.
   *
   * @param caller verified tenant administrator
   * @param requestId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the version, the closed reason, the optional internal note and the confirmation
   * @return the refused request
   */
  public IdempotentOperation.Result<RequestView> refuse(
      PayrollCaller caller, String requestId, String idempotencyKey, RefuseCommand body) {
    return plumbing.command(
        REFUSE,
        () -> {
          record Parsed(UUID id, Refusal refusal) {}
          Parsed parsed =
              operations.validated(
                  REFUSE_SPEC,
                  () ->
                      new Parsed(
                          SalaryAdvanceCommands.id(
                              requestId, ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND),
                          SalaryAdvanceCommands.refusal(idempotencyKey, body)));
          return operations.execute(
              REFUSE_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.refusal().canonical(caller.tenant(), parsed.id()),
              RequestView.class,
              TIMEOUT,
              () ->
                  decided(
                      caller,
                      parsed.id(),
                      parsed.refusal().expectedVersion(),
                      "REFUSED",
                      "EMPLOYER_REFUSED",
                      parsed.refusal().reasonCode(),
                      parsed.refusal().note()));
        });
  }

  private IdempotentOperation.Completed<RequestView> decided(
      PayrollCaller caller,
      UUID requestId,
      int expectedVersion,
      String decision,
      String toStatus,
      String reasonCode,
      String note) {
    TenantId tenant = caller.tenant();
    repository.limits(tenant, TIMEOUT);
    Instant now = calendar.now();
    quotas.requireDecision(tenant, caller.subject(), now);
    RequestRow row =
        repository
            .lockRequest(tenant, requestId)
            .orElseThrow(
                () -> new ApiException(ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND, Map.of()));
    if (row.rowVersion() != expectedVersion) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_VERSION_CONFLICT, Map.of());
    }
    if (!"SUBMITTED".equals(row.status())) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_TRANSITION_NOT_ALLOWED, Map.of());
    }
    int version = row.rowVersion() + 1;
    try {
      repository.insertDecision(
          tenant, requestId, new DecisionRow(decision, reasonCode, note, caller.subject(), now));
      repository.insertEvent(
          tenant,
          requestId,
          new EventRow(
              version,
              row.status(),
              toStatus,
              "TENANT_ADMIN",
              caller.subject(),
              now,
              calendar.today(tenant),
              reasonCode));
      if (repository.advanceRequest(tenant, requestId, row.rowVersion(), toStatus) != 1) {
        throw new ApiException(ErrorCode.SALARY_ADVANCE_VERSION_CONFLICT, Map.of());
      }
      repository.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    boolean confirmed = "EMPLOYER_CONFIRMED".equals(toStatus);
    recorder.requestChanged(
        caller,
        confirmed ? SalaryAdvanceRecorder.EMPLOYER_CONFIRM : SalaryAdvanceRecorder.EMPLOYER_REFUSE,
        confirmed ? SalaryAdvanceRecorder.CONFIRMED_EVENT : SalaryAdvanceRecorder.REFUSED_EVENT,
        requestId,
        row.legalEntityId(),
        toStatus,
        version,
        row.currency(),
        row.policySequence(),
        reasonCode,
        now);
    LOG.atInfo()
        .addKeyValue("operation", confirmed ? CONFIRM : REFUSE)
        .addKeyValue("outcome", "recorded")
        .log("salary_advance_decision_recorded");
    return new IdempotentOperation.Completed<>(view(tenant, requestId), requestId, Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Parts
  // ------------------------------------------------------------------------------------------

  private RequestView view(TenantId tenant, UUID id) {
    RequestRow row =
        repository
            .request(tenant, id)
            .orElseThrow(() -> new IllegalStateException("request vanished"));
    ConsentRow consent =
        repository
            .consent(tenant, id)
            .orElseThrow(() -> new IllegalStateException("consent missing"));
    return SalaryAdvanceViews.view(
        row,
        consent,
        repository.events(tenant, id),
        repository.decision(tenant, id).orElse(null),
        true);
  }

  private static CursorScope scope(PayrollCaller caller, String status, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "tenant");
    filters.put("role", ADMIN_ROLE);
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    if (status != null) {
      filters.put("status", status);
    }
    return new CursorScope(LIST, caller.tenant(), filters);
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  private static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        SalaryAdvanceConstraintViolations.constraint(violated)
            .flatMap(SalaryAdvanceConstraintViolations::answer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
