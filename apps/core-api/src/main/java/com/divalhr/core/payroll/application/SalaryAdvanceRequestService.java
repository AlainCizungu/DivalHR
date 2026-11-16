package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceBodies.CommitRequest;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.RequestCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.VersionedCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.ConsentDocument;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.EligibilityEntry;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.EligibilityView;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestPage;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestPreview;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestSummary;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestView;
import com.divalhr.core.payroll.application.SalaryAdvanceCommands.RequestCommit;
import com.divalhr.core.payroll.application.SalaryAdvanceCommands.RequestDraft;
import com.divalhr.core.payroll.application.SalaryAdvanceCommands.VersionedAction;
import com.divalhr.core.payroll.application.SalaryAdvanceEligibility.Evaluation;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.SalaryAdvanceReason;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.ConsentRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.EventRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.NewRequest;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.RequestPosition;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.RequestRow;
import com.divalhr.core.payroll.internal.SalaryAdvanceConstraintViolations;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The employee's own salary-advance requests (MVP-120A, Issue #204, ADR 0041): eligibility under
 * the employer policy, the preview, the consented submission, the history and the withdrawal.
 * Authorization ({@code employee} role, an active membership) has run before this; the employee is
 * resolved from the caller's current active employee-access link on every request and the employee
 * predicate is part of every statement. Managers and platform administrators have no route.
 *
 * <p>A request is a request to the employer: it creates no loan, makes no credit decision, sends
 * nothing to any financial institution, moves no money and makes no payroll deduction. A new
 * submission runs in one idempotent transaction; the database re-proves every frozen fact.
 */
@Service
public class SalaryAdvanceRequestService {

  /** Eligibility read (interceptor operation). */
  public static final String SELF_ELIGIBILITY = "salary-advance.self-eligibility";

  /** Preview (interceptor operation). */
  public static final String SELF_PREVIEW = "salary-advance.self-preview";

  /** List (interceptor operation). */
  public static final String SELF_LIST = "salary-advance.self-list";

  /** Detail (interceptor operation). */
  public static final String SELF_READ = "salary-advance.self-read";

  /** Submission (idempotency operation and interceptor operation). */
  public static final String SELF_SUBMIT = "salary-advance.self-submit";

  /** Withdrawal (idempotency operation and interceptor operation). */
  public static final String SELF_WITHDRAW = "salary-advance.self-withdraw";

  /** Per-subject reads. */
  public static final String SUBJECT_READ_BUCKET = "salary-advance-self-read";

  /** Per-subject previews. */
  public static final String SUBJECT_PREVIEW_BUCKET = "salary-advance-self-preview";

  /** Per-subject commands. */
  public static final String SUBJECT_WRITE_BUCKET = "salary-advance-self-write";

  /** Per-tenant previews. */
  public static final String TENANT_PREVIEW_BUCKET = "salary-advance-self-preview";

  /** Per-tenant commands. */
  public static final String TENANT_WRITE_BUCKET = "salary-advance-self-write";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.salary-advance");
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final String EMPLOYEE_ROLE = "employee";
  private static final String POSITION_CODE = "REQ";
  private static final IdempotentOperation.Spec SUBMIT_SPEC =
      new IdempotentOperation.Spec(
          SELF_SUBMIT, "salary_advance_request", "submit", "submitted", 201);
  private static final IdempotentOperation.Spec WITHDRAW_SPEC =
      new IdempotentOperation.Spec(
          SELF_WITHDRAW, "salary_advance_request", "withdraw", "withdrawn", 200);

  private final IdempotentOperation operations;
  private final JdbcSalaryAdvanceRepository repository;
  private final SalaryAdvanceEligibility eligibility;
  private final EmployeeAccessLinks links;
  private final CompensationCalendar calendar;
  private final SalaryAdvanceRecorder recorder;
  private final SalaryAdvanceQuotas quotas;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;
  private final MeterRegistry meters;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param repository salary-advance storage
   * @param eligibility the closed eligibility rules over the employee's facts
   * @param links the identity port (joins this module's transactions)
   * @param calendar business date and preview window
   * @param recorder audit and outbox
   * @param quotas hourly quotas
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   * @param meters the self-service denial counter
   */
  public SalaryAdvanceRequestService(
      IdempotentOperation operations,
      JdbcSalaryAdvanceRepository repository,
      SalaryAdvanceEligibility eligibility,
      EmployeeAccessLinks links,
      CompensationCalendar calendar,
      SalaryAdvanceRecorder recorder,
      SalaryAdvanceQuotas quotas,
      PayrollOperations plumbing,
      CursorCodec cursors,
      MeterRegistry meters) {
    this.operations = operations;
    this.repository = repository;
    this.eligibility = eligibility;
    this.links = links;
    this.calendar = calendar;
    this.recorder = recorder;
    this.quotas = quotas;
    this.plumbing = plumbing;
    this.cursors = cursors;
    this.meters = meters;
  }

  // ------------------------------------------------------------------------------------------
  // Eligibility
  // ------------------------------------------------------------------------------------------

  /**
   * The caller's eligibility under the employer policy, in each currency.
   *
   * @param caller verified employee
   * @return the view, after its disclosure audit committed
   */
  public EligibilityView eligibility(PayrollCaller caller) {
    return own(
        SELF_ELIGIBILITY,
        "salary_advance_eligibility_read",
        () ->
            plumbing.inReadTransaction(
                caller.tenant(),
                TIMEOUT,
                () -> {
                  TenantId tenant = caller.tenant();
                  SelfLink link = link(caller);
                  Instant now = calendar.now();
                  quotas.requireCounted(
                      "eligibility",
                      SalaryAdvanceQuotas.ELIGIBILITY_PER_HOUR,
                      tenant,
                      caller.subject(),
                      now);
                  LocalDate today = calendar.today(tenant);
                  List<EligibilityEntry> items = new ArrayList<>();
                  for (CompensationCurrency currency : CompensationCurrency.values()) {
                    items.add(
                        SalaryAdvanceEligibility.entry(
                            eligibility.evaluate(
                                tenant, link.employeeId(), currency, today, now, null)));
                  }
                  recorder.disclosed(
                      caller,
                      SalaryAdvanceRecorder.ELIGIBILITY_READ,
                      "salary-advance-eligibility",
                      link.employeeId(),
                      "eligibility",
                      true,
                      items.size(),
                      now);
                  return new EligibilityView(today, SalaryAdvanceResponses.NOTICE, items);
                }));
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Evaluates a requested amount without writing anything.
   *
   * @param caller verified employee
   * @param body the currency and amount
   * @return the preview, after its disclosure audit committed
   */
  public RequestPreview preview(PayrollCaller caller, RequestCommand body) {
    return own(
        SELF_PREVIEW,
        "salary_advance_request_preview",
        () -> {
          RequestDraft draft = SalaryAdvanceCommands.requestDraft(body);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                Instant now = calendar.now();
                quotas.requireCounted(
                    "preview",
                    SalaryAdvanceQuotas.PREVIEWS_PER_HOUR,
                    tenant,
                    caller.subject(),
                    now);
                Evaluation evaluation =
                    eligibility.evaluate(
                        tenant,
                        link.employeeId(),
                        draft.currency(),
                        calendar.today(tenant),
                        now,
                        draft.amountMinor());
                EligibilityEntry entry = SalaryAdvanceEligibility.entry(evaluation);
                Instant expiresAt = CompensationCalendar.expiry(now);
                String digest =
                    evaluation.eligible()
                        ? digest(tenant, link, draft, evaluation, expiresAt)
                        : null;
                recorder.disclosed(
                    caller,
                    SalaryAdvanceRecorder.REQUEST_PREVIEW,
                    "salary-advance-request",
                    link.employeeId(),
                    "preview",
                    true,
                    1,
                    now);
                return new RequestPreview(
                    entry,
                    entry.reasons(),
                    digest,
                    digest == null ? null : expiresAt,
                    SalaryAdvanceResponses.NOTICE);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Read
  // ------------------------------------------------------------------------------------------

  /**
   * A page of the caller's own requests, newest first.
   *
   * @param caller verified employee
   * @param status raw status filter, or {@code null}
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public RequestPage list(PayrollCaller caller, String status, String cursor, String limit) {
    return own(
        SELF_LIST,
        "salary_advance_self_list",
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
                SelfLink link = link(caller);
                Instant now = calendar.now();
                quotas.requireCounted(
                    "self-read", SalaryAdvanceQuotas.READS_PER_HOUR, tenant, caller.subject(), now);
                CursorScope scope = scope(caller, SELF_LIST, filter, size);
                RequestPosition after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  RequestRow at =
                      repository
                          .myRequest(tenant, link.employeeId(), position.id())
                          .filter(row -> POSITION_CODE.equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  after = new RequestPosition(at.submittedAt(), at.id());
                }
                List<RequestRow> rows =
                    repository.requestPage(tenant, link.employeeId(), filter, after, size + 1);
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
                    link.employeeId(),
                    "own-list",
                    cursor == null,
                    shown.size(),
                    now);
                List<RequestSummary> items =
                    shown.stream().map(row -> SalaryAdvanceViews.summary(row, false)).toList();
                return new RequestPage(items, next);
              });
        });
  }

  /**
   * One of the caller's own requests with its frozen evidence and history.
   *
   * @param caller verified employee
   * @param requestId raw path value
   * @return the view, after its disclosure audit committed
   */
  public RequestView get(PayrollCaller caller, String requestId) {
    return own(
        SELF_READ,
        "salary_advance_self_read",
        () -> {
          UUID id = SalaryAdvanceCommands.id(requestId, ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                Instant now = calendar.now();
                quotas.requireCounted(
                    "self-read", SalaryAdvanceQuotas.READS_PER_HOUR, tenant, caller.subject(), now);
                RequestRow row =
                    repository
                        .myRequest(tenant, link.employeeId(), id)
                        .orElseThrow(
                            () ->
                                new ApiException(
                                    ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND, Map.of()));
                RequestView view = view(tenant, row);
                recorder.disclosed(
                    caller,
                    SalaryAdvanceRecorder.REQUEST_READ,
                    "salary-advance-request",
                    row.id(),
                    "own-request",
                    true,
                    1,
                    now);
                return view;
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Submit
  // ------------------------------------------------------------------------------------------

  /**
   * Submits a previewed request with the employee's explicit consent, or replays an identical
   * submission.
   *
   * @param caller verified employee
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the previewed amount, its digest and the consent affirmation
   * @return the recorded request
   */
  public IdempotentOperation.Result<RequestView> submit(
      PayrollCaller caller, String idempotencyKey, CommitRequest body) {
    return commandOf(
        SELF_SUBMIT,
        () -> {
          RequestCommit commit =
              operations.validated(
                  SUBMIT_SPEC, () -> SalaryAdvanceCommands.requestCommit(idempotencyKey, body));
          return operations.execute(
              SUBMIT_SPEC,
              caller.subject(),
              idempotencyKey,
              commit.canonical(caller.tenant()),
              RequestView.class,
              TIMEOUT,
              () -> submitted(caller, commit));
        });
  }

  private IdempotentOperation.Completed<RequestView> submitted(
      PayrollCaller caller, RequestCommit command) {
    TenantId tenant = caller.tenant();
    repository.limits(tenant, TIMEOUT);
    SelfLink link = link(caller);
    Instant now = calendar.now();
    quotas.requireEmployeeCommand(tenant, caller.subject(), now);
    LocalDate today = calendar.today(tenant);
    RequestDraft draft = command.draft();
    Evaluation evaluation =
        eligibility.evaluate(
            tenant, link.employeeId(), draft.currency(), today, now, draft.amountMinor());
    if (!evaluation.eligible()) {
      throw new ApiException(refusalOf(evaluation.reasons()), Map.of());
    }
    String consentVersion = evaluation.policy().consentVersion();
    Optional<ConsentDocument> document = SalaryAdvanceConsent.document(consentVersion);
    if (document.isEmpty() || !consentVersion.equals(command.consentVersion())) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_CONSENT_VERSION_STALE, Map.of());
    }
    Instant expiresAt = command.expiresAt();
    if (!expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !digest(tenant, link, draft, evaluation, expiresAt).equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_POLICY_PREVIEW_STALE, Map.of());
    }
    UUID id = UUID.randomUUID();
    RequestRow row =
        new RequestRow(
            id,
            link.employeeId(),
            null,
            evaluation.legalEntityId(),
            draft.currency().name(),
            "SUBMITTED",
            1,
            evaluation.policy().id(),
            evaluation.policy().sequence(),
            evaluation.policy().partnerCode(),
            evaluation.reference().packageId(),
            evaluation.reference().payslipId(),
            evaluation.reference().periodStart(),
            evaluation.reference().netMinor(),
            evaluation.ceilingMinor(),
            draft.amountMinor(),
            evaluation.employmentStart(),
            evaluation.tenureMonths(),
            today,
            now,
            caller.subject());
    try {
      repository.insertRequest(tenant, new NewRequest(row));
      repository.insertConsent(
          tenant,
          id,
          new ConsentRow(consentVersion, command.language(), now, document.get().contentSha256()));
      repository.insertEvent(
          tenant,
          id,
          new EventRow(1, null, "SUBMITTED", "EMPLOYEE", caller.subject(), now, today, null));
      repository.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.requestChanged(
        caller,
        SalaryAdvanceRecorder.REQUEST_SUBMIT,
        SalaryAdvanceRecorder.SUBMITTED_EVENT,
        id,
        row.legalEntityId(),
        "SUBMITTED",
        1,
        row.currency(),
        row.policySequence(),
        null,
        now);
    LOG.atInfo()
        .addKeyValue("operation", SELF_SUBMIT)
        .addKeyValue("outcome", "recorded")
        .log("salary_advance_request_recorded");
    return new IdempotentOperation.Completed<>(view(tenant, id, false), id, Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Withdraw
  // ------------------------------------------------------------------------------------------

  /**
   * Withdraws one of the caller's own requests, or replays an identical withdrawal.
   *
   * @param caller verified employee
   * @param requestId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request version the caller saw and the confirmation
   * @return the withdrawn request
   */
  public IdempotentOperation.Result<RequestView> withdraw(
      PayrollCaller caller, String requestId, String idempotencyKey, VersionedCommand body) {
    return commandOf(
        SELF_WITHDRAW,
        () -> {
          record Parsed(UUID id, VersionedAction action) {}
          Parsed parsed =
              operations.validated(
                  WITHDRAW_SPEC,
                  () ->
                      new Parsed(
                          SalaryAdvanceCommands.id(
                              requestId, ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND),
                          SalaryAdvanceCommands.versioned(idempotencyKey, body)));
          return operations.execute(
              WITHDRAW_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.action().canonical(caller.tenant(), parsed.id()),
              RequestView.class,
              TIMEOUT,
              () -> withdrawn(caller, parsed.id(), parsed.action()));
        });
  }

  private IdempotentOperation.Completed<RequestView> withdrawn(
      PayrollCaller caller, UUID requestId, VersionedAction command) {
    TenantId tenant = caller.tenant();
    repository.limits(tenant, TIMEOUT);
    SelfLink link = link(caller);
    Instant now = calendar.now();
    quotas.requireEmployeeCommand(tenant, caller.subject(), now);
    RequestRow row =
        repository
            .lockRequest(tenant, requestId)
            .filter(found -> found.employeeId().equals(link.employeeId()))
            .orElseThrow(
                () -> new ApiException(ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND, Map.of()));
    if (row.rowVersion() != command.expectedVersion()) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_VERSION_CONFLICT, Map.of());
    }
    if (!"SUBMITTED".equals(row.status()) && !"EMPLOYER_CONFIRMED".equals(row.status())) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_TRANSITION_NOT_ALLOWED, Map.of());
    }
    int version = row.rowVersion() + 1;
    try {
      repository.insertEvent(
          tenant,
          requestId,
          new EventRow(
              version,
              row.status(),
              "WITHDRAWN",
              "EMPLOYEE",
              caller.subject(),
              now,
              calendar.today(tenant),
              null));
      if (repository.advanceRequest(tenant, requestId, row.rowVersion(), "WITHDRAWN") != 1) {
        throw new ApiException(ErrorCode.SALARY_ADVANCE_VERSION_CONFLICT, Map.of());
      }
      repository.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.requestChanged(
        caller,
        SalaryAdvanceRecorder.REQUEST_WITHDRAW,
        SalaryAdvanceRecorder.WITHDRAWN_EVENT,
        requestId,
        row.legalEntityId(),
        "WITHDRAWN",
        version,
        row.currency(),
        row.policySequence(),
        null,
        now);
    return new IdempotentOperation.Completed<>(
        view(tenant, requestId, false), requestId, Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Parts
  // ------------------------------------------------------------------------------------------

  /** Runs an employee read; a missing link is counted as a self-service denial. */
  private <T> T own(String operation, String event, Supplier<T> work) {
    try {
      return plumbing.observed(LOG, event, operation, work);
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
      }
      throw denied;
    }
  }

  /** Runs an employee command; a missing link is counted as a self-service denial. */
  private <T> T commandOf(String operation, Supplier<T> work) {
    try {
      return plumbing.command(operation, work);
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
      }
      throw denied;
    }
  }

  private SelfLink link(PayrollCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  private static String digest(
      TenantId tenant,
      SelfLink link,
      RequestDraft draft,
      Evaluation evaluation,
      Instant expiresAt) {
    return SalaryAdvanceDigests.request(
        tenant,
        link.employeeId(),
        draft.currency(),
        draft.amountMinor(),
        evaluation.policy().id(),
        evaluation.policy().sequence(),
        evaluation.reference().payslipId(),
        evaluation.ceilingMinor(),
        evaluation.policy().consentVersion(),
        expiresAt);
  }

  /** The stable error of the first reason that blocks a submission. */
  static ErrorCode refusalOf(List<SalaryAdvanceReason> reasons) {
    SalaryAdvanceReason first = reasons.get(0);
    return switch (first) {
      case PAYROLL_REFERENCE_MISSING -> ErrorCode.SALARY_ADVANCE_PAYROLL_REFERENCE_MISSING;
      case CURRENCY_MISMATCH -> ErrorCode.SALARY_ADVANCE_CURRENCY_MISMATCH;
      case OPEN_LIMIT_REACHED -> ErrorCode.SALARY_ADVANCE_OPEN_LIMIT_REACHED;
      case WAITING_PERIOD -> ErrorCode.SALARY_ADVANCE_WAITING_PERIOD;
      case AMOUNT_OUT_OF_RANGE -> ErrorCode.SALARY_ADVANCE_AMOUNT_OUT_OF_RANGE;
      default -> ErrorCode.SALARY_ADVANCE_NOT_ELIGIBLE;
    };
  }

  private RequestView view(TenantId tenant, RequestRow row) {
    return view(tenant, row.id(), false);
  }

  private RequestView view(TenantId tenant, UUID id, boolean administrator) {
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
        administrator);
  }

  private static CursorScope scope(
      PayrollCaller caller, String operation, String status, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "own");
    filters.put("role", EMPLOYEE_ROLE);
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    if (status != null) {
      filters.put("status", status);
    }
    return new CursorScope(operation, caller.tenant(), filters);
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
