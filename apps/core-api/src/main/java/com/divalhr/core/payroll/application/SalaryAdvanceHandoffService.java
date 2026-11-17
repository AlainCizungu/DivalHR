package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceHandoffBodies.CommitHandoff;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffBodies.WithdrawHandoff;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.Page;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.Preview;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.Summary;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.View;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses;
import com.divalhr.core.payroll.application.SalaryAdvanceHandoffCommands.Authorization;
import com.divalhr.core.payroll.application.SalaryAdvanceHandoffCommands.Versioned;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffPayload;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffState;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.ConsentRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.EventRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.HandoffRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.NewHandoff;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.Next;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.Position;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.RequestRow;
import com.divalhr.core.payroll.internal.SalaryAdvanceConstraintViolations;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
 * The employee's controlled partner handoff (MVP-120B, Issue #206, ADR 0042): the preview of the
 * closed handoff disclosure, the authorization with fresh versioned consent, the bounded status
 * reads and the withdrawal before the partner durably receives the request. Authorization ({@code
 * employee} role, an active membership) has run before this; the employee is resolved from the
 * caller's current active employee-access link on every request and the employee predicate is part
 * of every statement. Managers and platform administrators have no route, and no administrator can
 * manufacture the employee's consent.
 *
 * <p>A handoff sends a minimal, closed payload to a controlled synthetic test receiver. It makes no
 * credit decision, sends no funds, makes no payroll deduction and DivalHR is not the lender.
 * Authorization runs in one idempotent transaction: the handoff, its consent, its two authorization
 * events, the audit record and the outbox event commit together or not at all.
 */
@Service
public class SalaryAdvanceHandoffService {

  /** Preview (interceptor operation). */
  public static final String SELF_PREVIEW = "salary-advance-handoff.self-preview";

  /** Authorization (idempotency operation and interceptor operation). */
  public static final String SELF_AUTHORIZE = "salary-advance-handoff.self-authorize";

  /** Detail (interceptor operation). */
  public static final String SELF_READ = "salary-advance-handoff.self-read";

  /** List (interceptor operation). */
  public static final String SELF_LIST = "salary-advance-handoff.self-list";

  /** Withdrawal (idempotency operation and interceptor operation). */
  public static final String SELF_WITHDRAW = "salary-advance-handoff.self-withdraw";

  /** Per-subject reads (per minute). */
  public static final String SUBJECT_READ_BUCKET = "salary-advance-handoff-read";

  /** Per-subject previews (per minute). */
  public static final String SUBJECT_PREVIEW_BUCKET = "salary-advance-handoff-preview";

  /** Per-subject commands (per minute). */
  public static final String SUBJECT_WRITE_BUCKET = "salary-advance-handoff-write";

  /** Per-tenant previews. */
  public static final String TENANT_PREVIEW_BUCKET = "salary-advance-handoff-preview";

  /** Per-tenant commands. */
  public static final String TENANT_WRITE_BUCKET = "salary-advance-handoff-write";

  /** Employee previews per subject per hour. */
  static final int PREVIEWS_PER_HOUR = 10;

  /** Employee authorizations and withdrawals per subject per hour. */
  static final int COMMANDS_PER_HOUR = 10;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.salary-advance-handoff");
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final String EMPLOYEE_ROLE = "employee";
  private static final String POSITION_CODE = "HOF";
  private static final IdempotentOperation.Spec AUTHORIZE_SPEC =
      new IdempotentOperation.Spec(
          SELF_AUTHORIZE, "salary_advance_handoff", "authorize", "authorized", 201);
  private static final IdempotentOperation.Spec WITHDRAW_SPEC =
      new IdempotentOperation.Spec(
          SELF_WITHDRAW, "salary_advance_handoff", "withdraw", "withdrawn", 200);

  private final IdempotentOperation operations;
  private final JdbcSalaryAdvanceHandoffRepository handoffs;
  private final JdbcSalaryAdvanceRepository requests;
  private final EmployeeAccessLinks links;
  private final CompensationCalendar calendar;
  private final SalaryAdvanceHandoffRecorder recorder;
  private final SalaryAdvanceQuotas quotas;
  private final SalaryAdvancePartnerProfiles profiles;
  private final SalaryAdvancePartnerSecrets secrets;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;
  private final MeterRegistry meters;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param handoffs handoff storage
   * @param requests salary-advance request storage (locks the request, withdraws it with its
   *     handoff)
   * @param links the identity port (joins this module's transactions)
   * @param calendar business date and preview window
   * @param recorder audit and outbox
   * @param quotas hourly quotas
   * @param profiles operator-controlled partner profile
   * @param secrets partner signing keys
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   * @param meters the self-service denial counter
   */
  public SalaryAdvanceHandoffService(
      IdempotentOperation operations,
      JdbcSalaryAdvanceHandoffRepository handoffs,
      JdbcSalaryAdvanceRepository requests,
      EmployeeAccessLinks links,
      CompensationCalendar calendar,
      SalaryAdvanceHandoffRecorder recorder,
      SalaryAdvanceQuotas quotas,
      SalaryAdvancePartnerProfiles profiles,
      SalaryAdvancePartnerSecrets secrets,
      PayrollOperations plumbing,
      CursorCodec cursors,
      MeterRegistry meters) {
    this.operations = operations;
    this.handoffs = handoffs;
    this.requests = requests;
    this.links = links;
    this.calendar = calendar;
    this.recorder = recorder;
    this.quotas = quotas;
    this.profiles = profiles;
    this.secrets = secrets;
    this.plumbing = plumbing;
    this.cursors = cursors;
    this.meters = meters;
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Previews the handoff of one of the caller's employer-confirmed requests without writing
   * anything.
   *
   * @param caller verified employee
   * @param requestId raw path value
   * @return the preview, after its disclosure audit committed
   */
  public Preview preview(PayrollCaller caller, String requestId) {
    return own(
        SELF_PREVIEW,
        "salary_advance_handoff_preview",
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
                    "handoff-preview", PREVIEWS_PER_HOUR, tenant, caller.subject(), now);
                RequestRow request =
                    requests
                        .myRequest(tenant, link.employeeId(), id)
                        .orElseThrow(
                            () ->
                                new ApiException(
                                    ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND, Map.of()));
                SalaryAdvanceHandoffConsent.Document document =
                    SalaryAdvanceHandoffConsent.document(SalaryAdvanceHandoffConsent.V1)
                        .orElseThrow();
                List<String> blockers = blockers(tenant, request);
                Instant expiresAt = CompensationCalendar.expiry(now);
                String digest =
                    blockers.isEmpty() ? digest(tenant, link, request, expiresAt) : null;
                recorder.disclosed(
                    caller, SalaryAdvanceHandoffRecorder.PREVIEW, request.id(), "preview", now);
                return new Preview(
                    request.id(),
                    request.rowVersion(),
                    request.currency(),
                    SalaryAdvanceViews.money(request.currency(), request.requestedMinor()),
                    SalaryAdvanceViews.money(request.currency(), request.ceilingMinor()),
                    request.partnerCode(),
                    SalaryAdvanceHandoffViews.document(document),
                    blockers,
                    digest,
                    digest == null ? null : expiresAt,
                    SalaryAdvanceResponses.NOTICE,
                    SalaryAdvanceHandoffResponses.HANDOFF_NOTICE);
              });
        });
  }

  /** The closed blockers of a handoff, in a stable order. */
  private List<String> blockers(TenantId tenant, RequestRow request) {
    List<String> blockers = new ArrayList<>();
    switch (request.status()) {
      case "SUBMITTED" -> blockers.add("EMPLOYER_CONFIRMATION_REQUIRED");
      case "EMPLOYER_CONFIRMED" -> {
        // eligible so far
      }
      default -> blockers.add("REQUEST_TERMINAL");
    }
    if (handoffs.byRequest(tenant, request.id()).isPresent()) {
      blockers.add("HANDOFF_EXISTS");
    }
    if (profiles.byCode(request.partnerCode()).isEmpty()) {
      blockers.add("PARTNER_NOT_ENABLED");
    } else if (!secrets.deliveryAvailable()) {
      blockers.add("PARTNER_UNAVAILABLE");
    }
    return blockers;
  }

  // ------------------------------------------------------------------------------------------
  // Read
  // ------------------------------------------------------------------------------------------

  /**
   * The caller's own handoffs, newest first.
   *
   * @param caller verified employee
   * @param state raw state filter, or {@code null}
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public Page list(PayrollCaller caller, String state, String cursor, String limit) {
    return own(
        SELF_LIST,
        "salary_advance_handoff_self_list",
        () -> {
          FieldErrors errors = new FieldErrors();
          int size = SalaryAdvanceCommands.limit(limit, errors);
          String filter = SalaryAdvanceHandoffReads.stateFilter(state, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                Instant now = calendar.now();
                quotas.requireCounted(
                    "handoff-self-read",
                    SalaryAdvanceQuotas.READS_PER_HOUR,
                    tenant,
                    caller.subject(),
                    now);
                CursorScope scope = scope(caller, filter, size);
                Position after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  HandoffRow at =
                      handoffs
                          .handoff(tenant, position.id())
                          .filter(row -> row.employeeId().equals(link.employeeId()))
                          .filter(row -> POSITION_CODE.equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  after = new Position(at.authorizedAt(), at.id());
                }
                List<HandoffRow> rows =
                    handoffs.page(tenant, link.employeeId(), filter, after, size + 1);
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
                    link.employeeId(),
                    cursor == null ? "own-list-first" : "own-list-cursor",
                    now);
                List<Summary> items =
                    shown.stream()
                        .map(row -> SalaryAdvanceHandoffViews.summary(row, false))
                        .toList();
                return new Page(items, next);
              });
        });
  }

  /**
   * The handoff of one of the caller's own requests with its bounded status and history.
   *
   * @param caller verified employee
   * @param requestId raw path value
   * @return the view, after its disclosure audit committed
   */
  public View get(PayrollCaller caller, String requestId) {
    return own(
        SELF_READ,
        "salary_advance_handoff_self_read",
        () -> {
          UUID id = SalaryAdvanceCommands.id(requestId, ErrorCode.SALARY_ADVANCE_HANDOFF_NOT_FOUND);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                Instant now = calendar.now();
                quotas.requireCounted(
                    "handoff-self-read",
                    SalaryAdvanceQuotas.READS_PER_HOUR,
                    tenant,
                    caller.subject(),
                    now);
                HandoffRow row =
                    handoffs
                        .myByRequest(tenant, link.employeeId(), id)
                        .orElseThrow(
                            () ->
                                new ApiException(
                                    ErrorCode.SALARY_ADVANCE_HANDOFF_NOT_FOUND, Map.of()));
                View view = view(tenant, row, false);
                recorder.disclosed(
                    caller, SalaryAdvanceHandoffRecorder.READ, row.id(), "own-handoff", now);
                return view;
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Authorize
  // ------------------------------------------------------------------------------------------

  /**
   * Authorizes the handoff of a previewed, employer-confirmed request with the employee's explicit
   * consent, or replays an identical authorization.
   *
   * @param caller verified employee
   * @param requestId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the previewed request version, its digest and the consent affirmation
   * @return the recorded handoff
   */
  public IdempotentOperation.Result<View> authorize(
      PayrollCaller caller, String requestId, String idempotencyKey, CommitHandoff body) {
    return commandOf(
        SELF_AUTHORIZE,
        () -> {
          record Parsed(UUID id, Authorization authorization) {}
          Parsed parsed =
              operations.validated(
                  AUTHORIZE_SPEC,
                  () ->
                      new Parsed(
                          SalaryAdvanceCommands.id(
                              requestId, ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND),
                          SalaryAdvanceHandoffCommands.authorization(idempotencyKey, body)));
          return operations.execute(
              AUTHORIZE_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.authorization().canonical(caller.tenant(), parsed.id()),
              View.class,
              TIMEOUT,
              () -> authorized(caller, parsed.id(), parsed.authorization()));
        });
  }

  private IdempotentOperation.Completed<View> authorized(
      PayrollCaller caller, UUID requestId, Authorization command) {
    TenantId tenant = caller.tenant();
    handoffs.limits(tenant, TIMEOUT);
    SelfLink link = link(caller);
    Instant now = calendar.now();
    requireEmployeeCommand(tenant, caller.subject(), now);
    RequestRow request =
        requests
            .lockRequest(tenant, requestId)
            .filter(found -> found.employeeId().equals(link.employeeId()))
            .orElseThrow(
                () -> new ApiException(ErrorCode.SALARY_ADVANCE_REQUEST_NOT_FOUND, Map.of()));
    switch (request.status()) {
      case "EMPLOYER_CONFIRMED" -> {
        // eligible
      }
      case "SUBMITTED" ->
          throw new ApiException(ErrorCode.SALARY_ADVANCE_EMPLOYER_CONFIRMATION_REQUIRED, Map.of());
      default -> throw new ApiException(ErrorCode.SALARY_ADVANCE_REQUEST_TERMINAL, Map.of());
    }
    if (handoffs.byRequest(tenant, requestId).isPresent()) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_EXISTS, Map.of());
    }
    SalaryAdvancePartnerProfile profile =
        profiles
            .byCode(request.partnerCode())
            .orElseThrow(
                () -> new ApiException(ErrorCode.SALARY_ADVANCE_PARTNER_NOT_ENABLED, Map.of()));
    if (!secrets.deliveryAvailable()) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_PARTNER_UNAVAILABLE, Map.of());
    }
    Optional<SalaryAdvanceHandoffConsent.Document> document =
        SalaryAdvanceHandoffConsent.document(command.consentVersion());
    if (document.isEmpty()) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_CONSENT_STALE, Map.of());
    }
    Instant expiresAt = command.expiresAt();
    if (request.rowVersion() != command.expectedRequestVersion()
        || !expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !digest(tenant, link, request, expiresAt).equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_PREVIEW_STALE, Map.of());
    }
    UUID handoffId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    SalaryAdvanceHandoffPayload payload =
        new SalaryAdvanceHandoffPayload(
            applicationId,
            request.id(),
            legalEntityReference(request.legalEntityId(), applicationId),
            // Employment was verified active against the payroll reference when the request was
            // submitted and the
            // employer confirmed it afterwards; that frozen evidence is what the partner receives.
            true,
            request.employmentStart(),
            request.referencePeriodStart(),
            request.currency(),
            request.requestedMinor(),
            request.ceilingMinor(),
            document.get().version(),
            document.get().contentSha256(),
            now,
            correlationFor(applicationId));
    String payloadDigest = payload.sha256();
    try {
      handoffs.insert(
          tenant,
          new NewHandoff(
              handoffId,
              request.id(),
              request.employeeId(),
              request.legalEntityId(),
              profile.code(),
              applicationId,
              payloadDigest,
              SalaryAdvanceHandoffPayload.SHARED_CATEGORIES,
              secrets.keyVersion(),
              now,
              caller.subject(),
              now));
      handoffs.insertConsent(
          tenant,
          handoffId,
          request.id(),
          new ConsentRow(
              document.get().version(),
              command.language(),
              now,
              document.get().contentSha256(),
              payloadDigest,
              caller.subject()));
      handoffs.insertEvent(
          tenant,
          handoffId,
          new EventRow(1, null, "AUTHORIZED", "EMPLOYEE", caller.subject(), now, null));
      handoffs.insertEvent(
          tenant,
          handoffId,
          new EventRow(2, "AUTHORIZED", "QUEUED", "EMPLOYEE", caller.subject(), now, null));
      handoffs.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.authorized(
        caller,
        new SalaryAdvanceHandoffRecorder.Change(
            handoffId,
            request.id(),
            request.legalEntityId(),
            request.currency(),
            SalaryAdvanceHandoffState.QUEUED.name(),
            null,
            1,
            null,
            null,
            0,
            "EMPLOYEE",
            now));
    LOG.atInfo()
        .addKeyValue("operation", SELF_AUTHORIZE)
        .addKeyValue("outcome", "recorded")
        .log("salary_advance_handoff_authorized");
    return new IdempotentOperation.Completed<>(
        view(tenant, handoffId, false), handoffId, Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Withdraw
  // ------------------------------------------------------------------------------------------

  /**
   * Withdraws the handoff of one of the caller's own requests before the partner durably received
   * it, or replays an identical withdrawal. The request and its handoff move together.
   *
   * @param caller verified employee
   * @param requestId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the handoff version the caller saw and the confirmation
   * @return the withdrawn handoff
   */
  public IdempotentOperation.Result<View> withdraw(
      PayrollCaller caller, String requestId, String idempotencyKey, WithdrawHandoff body) {
    return commandOf(
        SELF_WITHDRAW,
        () -> {
          record Parsed(UUID id, Versioned action) {}
          Parsed parsed =
              operations.validated(
                  WITHDRAW_SPEC,
                  () ->
                      new Parsed(
                          SalaryAdvanceCommands.id(
                              requestId, ErrorCode.SALARY_ADVANCE_HANDOFF_NOT_FOUND),
                          SalaryAdvanceHandoffCommands.versioned(idempotencyKey, body)));
          return operations.execute(
              WITHDRAW_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.action().canonical(caller.tenant(), "requestId", parsed.id()),
              View.class,
              TIMEOUT,
              () -> withdrawn(caller, parsed.id(), parsed.action()));
        });
  }

  private IdempotentOperation.Completed<View> withdrawn(
      PayrollCaller caller, UUID requestId, Versioned command) {
    TenantId tenant = caller.tenant();
    handoffs.limits(tenant, TIMEOUT);
    SelfLink link = link(caller);
    Instant now = calendar.now();
    requireEmployeeCommand(tenant, caller.subject(), now);
    // The request first, then its handoff: the same lock order everywhere both are held.
    RequestRow request =
        requests
            .lockRequest(tenant, requestId)
            .filter(found -> found.employeeId().equals(link.employeeId()))
            .orElseThrow(
                () -> new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_NOT_FOUND, Map.of()));
    HandoffRow row =
        handoffs
            .byRequest(tenant, requestId)
            .flatMap(found -> handoffs.lock(tenant, found.id()))
            .orElseThrow(
                () -> new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_NOT_FOUND, Map.of()));
    if (row.version() != command.expectedVersion()) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_VERSION_CONFLICT, Map.of());
    }
    // Only before the partner durably received it: a receipt, an ambiguous send, a failed delivery
    // and a send in
    // flight are all refused, so a withdrawal and a receipt can never both win.
    if (!SalaryAdvanceHandoffState.parse(row.state())
        .map(SalaryAdvanceHandoffState::employeeMayWithdraw)
        .orElse(false)) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED, Map.of());
    }
    int version = row.version() + 1;
    try {
      handoffs.insertEvent(
          tenant,
          row.id(),
          new EventRow(
              handoffs.nextEventSequence(tenant, row.id()),
              "QUEUED",
              "WITHDRAWN",
              "EMPLOYEE",
              caller.subject(),
              now,
              "WITHDRAWN_BEFORE_RECEIPT"));
      if (handoffs.update(
              tenant,
              row.id(),
              row.version(),
              new Next(
                  "WITHDRAWN",
                  null,
                  null,
                  row.attemptCount(),
                  row.reconcileCount(),
                  row.adminRetryCount(),
                  null,
                  null,
                  now))
          != 1) {
        throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_VERSION_CONFLICT, Map.of());
      }
      requests.insertEvent(
          tenant,
          requestId,
          new JdbcSalaryAdvanceRepository.EventRow(
              request.rowVersion() + 1,
              request.status(),
              "WITHDRAWN",
              "EMPLOYEE",
              caller.subject(),
              now,
              calendar.today(tenant),
              null));
      if (requests.advanceRequest(tenant, requestId, request.rowVersion(), "WITHDRAWN") != 1) {
        throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_VERSION_CONFLICT, Map.of());
      }
      handoffs.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.withdrawn(
        caller,
        new SalaryAdvanceHandoffRecorder.Change(
            row.id(),
            requestId,
            row.legalEntityId(),
            row.currency(),
            "WITHDRAWN",
            null,
            version,
            "WITHDRAWN_BEFORE_RECEIPT",
            null,
            0,
            "EMPLOYEE",
            now));
    return new IdempotentOperation.Completed<>(
        view(tenant, row.id(), false), row.id(), Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Parts
  // ------------------------------------------------------------------------------------------

  private void requireEmployeeCommand(TenantId tenant, String subject, Instant now) {
    quotas.requireCommands(
        handoffs.eventsSince(tenant, subject, "EMPLOYEE", now.minus(Duration.ofHours(1))),
        COMMANDS_PER_HOUR,
        now);
  }

  private View view(TenantId tenant, HandoffRow row, boolean administrator) {
    return view(tenant, row.id(), administrator);
  }

  private View view(TenantId tenant, UUID id, boolean administrator) {
    return SalaryAdvanceHandoffReads.view(handoffs, tenant, id, administrator);
  }

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

  /**
   * The preview digest: binds tenant, employee, request and version, consent and the amount-bound
   * payload shape.
   */
  private static String digest(
      TenantId tenant, SelfLink link, RequestRow request, Instant expiresAt) {
    SalaryAdvanceHandoffConsent.Document document =
        SalaryAdvanceHandoffConsent.document(SalaryAdvanceHandoffConsent.V1).orElseThrow();
    String shape =
        Fingerprints.sha256(
            "DIVALHR-HANDOFF-SHAPE\nversion=1\nrequest="
                + request.id()
                + "\ncurrency="
                + request.currency()
                + "\nrequested="
                + request.requestedMinor()
                + "\nceiling="
                + request.ceilingMinor()
                + "\nmonth="
                + request.referencePeriodStart()
                + "\nstart="
                + request.employmentStart()
                + "\npartner="
                + request.partnerCode()
                + "\n");
    return SalaryAdvanceHandoffDigests.preview(
        tenant,
        link.employeeId(),
        request.id(),
        request.rowVersion(),
        document.version(),
        document.contentSha256(),
        shape,
        expiresAt);
  }

  /**
   * An opaque per-handoff reference of the legal entity: never the real id and never the display
   * name.
   */
  static UUID legalEntityReference(UUID legalEntityId, UUID applicationId) {
    return UUID.nameUUIDFromBytes(
        ("DIVALHR-LEGAL-ENTITY-REF\n" + legalEntityId + "\n" + applicationId)
            .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The payload's correlation reference, derived from the application id so that the delivery
   * worker rebuilds the identical bytes for every attempt.
   */
  static UUID correlationFor(UUID applicationId) {
    return UUID.nameUUIDFromBytes(
        ("DIVALHR-HANDOFF-CORRELATION\n" + applicationId).getBytes(StandardCharsets.UTF_8));
  }

  private static CursorScope scope(PayrollCaller caller, String state, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "own-handoffs");
    filters.put("role", EMPLOYEE_ROLE);
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    if (state != null) {
      filters.put("state", state);
    }
    return new CursorScope(SELF_LIST, caller.tenant(), filters);
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        SalaryAdvanceConstraintViolations.constraint(violated)
            .flatMap(SalaryAdvanceConstraintViolations::answer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
