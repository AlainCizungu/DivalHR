package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.leave.api.AmendLeaveRequest;
import com.divalhr.core.people.leave.api.CancelLeaveRequest;
import com.divalhr.core.people.leave.api.CreateMyLeaveRequest;
import com.divalhr.core.people.leave.api.MyLeaveResponses.AmendmentReceipt;
import com.divalhr.core.people.leave.api.MyLeaveResponses.CancellationReceipt;
import com.divalhr.core.people.leave.api.MyLeaveResponses.Policy;
import com.divalhr.core.people.leave.api.MyLeaveResponses.PolicyPage;
import com.divalhr.core.people.leave.api.MyLeaveResponses.Request;
import com.divalhr.core.people.leave.api.MyLeaveResponses.RequestPage;
import com.divalhr.core.people.leave.domain.Employment;
import com.divalhr.core.people.leave.domain.LeaveAmendment;
import com.divalhr.core.people.leave.domain.LeaveCancellation;
import com.divalhr.core.people.leave.domain.LeavePolicy;
import com.divalhr.core.people.leave.domain.LeaveRequest;
import com.divalhr.core.people.leave.domain.LeaveRequestState;
import com.divalhr.core.people.leave.domain.LeaveRouting;
import com.divalhr.core.people.leave.internal.JdbcLeavePolicyRepository;
import com.divalhr.core.people.leave.internal.JdbcLeaveRequestRepository;
import com.divalhr.core.people.leave.internal.LeaveConstraintViolations;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Employee self-service leave (MVP-041A, Issue #87): the requestable policy catalogue, submitting a
 * pending request and the caller's own request history; cancelling a pending request of their own
 * (MVP-041C, Issue #91); amending one by replacement (MVP-041D, Issue #92).
 *
 * <p>Authorization (role {@code employee}, verified tenant, active membership) has run before any
 * of this. Every operation then resolves the caller's own employee through the identity port's
 * active link ({@code FOR SHARE}, lock order step 4, ADR 0008/0009); no link is {@code 403
 * EMPLOYEE_LINK_REQUIRED}, counted on the bounded self-service counter, never durable
 * privileged-denial evidence. No request names an employee: it is always the caller's own.
 *
 * <p>A creation is one bounded transaction: idempotency reservation (step 0); for a new execution,
 * the business date, the link (step 4), plain reads of the immutable policy version and of the last
 * committed employment periods, the insert (PostgreSQL's exclusion constraint serializes
 * overlapping pending requests), then the audit record and the {@value #EVENT_TYPE} outbox event
 * (step 6). No manager-graph lock: this slice routes no approval. A replay is returned only after
 * the same transaction re-reads the caller's current active link (step 4) and finds the stored
 * request under that employee (R88-2); otherwise no stored response or identifier is returned. No
 * balance, working day, holiday or payroll amount is calculated; the amount is the employee's own,
 * in the policy's unit.
 */
@Service
public class MyLeaveService {

  /** Policy catalogue operation. */
  public static final String POLICIES = "leave-policy.self-list";

  /** Create operation (and audit action). */
  public static final String CREATE = "leave-request.create";

  /** Request history operation (and disclosure audit action). */
  public static final String REQUESTS = "leave-request.self-list";

  /** Outbox event type (existing envelope). */
  public static final String EVENT_TYPE = "people.leave-request.created.v1";

  /** Cancellation operation (idempotency operation; MVP-041C). */
  public static final String CANCEL = "leave-request.self-cancel";

  /** Audit action of a cancellation (MVP-041C). */
  public static final String CANCEL_AUDIT = "leave-request.cancel";

  /** Outbox event of a cancellation (MVP-041C). */
  public static final String CANCELLED_EVENT = "people.leave-request.cancelled.v1";

  /** Amendment operation (idempotency operation; MVP-041D). */
  public static final String AMEND = "leave-request.self-amend";

  /** Audit action of an amendment (MVP-041D). */
  public static final String AMEND_AUDIT = "leave-request.amend";

  /** Outbox event of an amendment (MVP-041D). */
  public static final String AMENDED_EVENT = "people.leave-request.amended.v1";

  /** Per-subject self-service read bucket (60 per minute). */
  public static final String SUBJECT_READ_BUCKET = "leave-self-read";

  /** Per-subject request-write bucket (10 per minute). */
  public static final String SUBJECT_WRITE_BUCKET = "leave-request-write";

  /** Per-tenant request-write bucket (200 per 10 minutes). */
  public static final String TENANT_WRITE_BUCKET = "leave-request-write";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.leave");
  private static final String SOURCE = "core-api/people";
  private static final String REQUEST_CODE = "LR";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec SPEC =
      new IdempotentOperation.Spec(CREATE, "leave_request", "create", "created", 201);
  private static final IdempotentOperation.Spec CANCEL_SPEC =
      new IdempotentOperation.Spec(CANCEL, "leave_request", "cancel", "cancelled", 200);
  private static final IdempotentOperation.Spec AMEND_SPEC =
      new IdempotentOperation.Spec(AMEND, "leave_request", "amend", "amended", 201);

  private final IdempotentOperation operations;
  private final JdbcLeavePolicyRepository policies;
  private final JdbcLeaveRequestRepository requests;
  private final EmployeeAccessLinks links;
  private final BusinessCalendar calendar;
  private final CursorCodec cursors;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final OperationMetrics metrics;
  private final MeterRegistry meters;
  private final PlatformTransactionManager transactionManager;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param policies leave policy storage
   * @param requests leave request storage
   * @param links the identity port (joins this module's transactions)
   * @param calendar the organization's business date (platform clock)
   * @param cursors signed cursors
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param meters meter registry (self-service denial counter)
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public MyLeaveService(
      IdempotentOperation operations,
      JdbcLeavePolicyRepository policies,
      JdbcLeaveRequestRepository requests,
      EmployeeAccessLinks links,
      BusinessCalendar calendar,
      CursorCodec cursors,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager,
      JsonMapper json) {
    this.operations = operations;
    this.policies = policies;
    this.requests = requests;
    this.links = links;
    this.calendar = calendar;
    this.cursors = cursors;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.meters = meters;
    this.transactionManager = transactionManager;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------
  // Policy catalogue
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the policies the caller may request (planned or active on the business date), by
   * code, then id. The first page reads the business date; the signed cursor pins it.
   *
   * @param caller verified caller
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 50, default 25)
   * @return the page
   */
  public PolicyPage policies(PeopleCaller caller, String cursor, String limit) {
    return recorded(
        POLICIES,
        () -> {
          TenantId tenant = caller.tenant();
          int size = LeavePolicyService.limit(limit);
          CursorScope scope = scope(POLICIES, caller, size);
          KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
          LocalDate pinned = after == null ? null : LeavePolicyService.pinned(after);
          return read(
              () -> {
                link(caller);
                ZoneId zone = calendar.zone(tenant);
                LocalDate asOf = pinned == null ? calendar.today(zone) : pinned;
                String afterCode = null;
                if (after != null) {
                  afterCode =
                      policies
                          .code(tenant, after.id())
                          .orElseThrow(LeavePolicyService::invalidCursor);
                }
                List<LeavePolicy> rows =
                    policies.openPage(
                        tenant, asOf, afterCode, after == null ? null : after.id(), size + 1);
                List<LeavePolicy> shown = rows.subList(0, Math.min(rows.size(), size));
                String next =
                    rows.size() > size
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                "A" + asOf.format(LeavePolicyService.BASIC),
                                shown.get(shown.size() - 1).id()))
                        : null;
                LOG.atInfo()
                    .addKeyValue("operation", POLICIES)
                    .addKeyValue("page", after == null ? "first" : "next")
                    .addKeyValue("resultCount", shown.size())
                    .addKeyValue("outcome", "listed")
                    .log("leave_policy_self_listed");
                return new PolicyPage(
                    shown.stream().map(policy -> Policy.of(policy, asOf)).toList(),
                    next,
                    asOf,
                    zone.getId());
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Create
  // ------------------------------------------------------------------------------------------

  /**
   * Submits a pending leave request for the caller's own employee, or replays an earlier identical
   * request.
   *
   * @param caller verified caller
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body request
   * @return the created or replayed request
   */
  public IdempotentOperation.Result<Request> create(
      PeopleCaller caller, String idempotencyKey, CreateMyLeaveRequest body) {
    TenantId tenant = caller.tenant();
    // Deterministic validation only: the business date is checked for a new execution (R88-1).
    LeaveRequestCommand command =
        operations.validated(SPEC, () -> LeaveRequestCommand.from(idempotencyKey, body));
    return counted(
        CREATE,
        () ->
            operations.execute(
                SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(tenant),
                Request.class,
                TRANSACTION_TIMEOUT,
                requestId -> replayable(caller, requestId),
                () -> created(caller, command)));
  }

  /**
   * A stored response is returned only to the employee it was created for, through the caller's
   * current active link (R88-2). No link is {@code 403 EMPLOYEE_LINK_REQUIRED}; a link to another
   * employee is {@code 409 IDEMPOTENCY_KEY_REUSED} without params, so neither the old response nor
   * its identifiers are returned.
   */
  private void replayable(PeopleCaller caller, UUID requestId) {
    SelfLink link = link(caller);
    if (requestId == null
        || requests.find(caller.tenant(), link.employeeId(), requestId).isEmpty()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of());
    }
  }

  private IdempotentOperation.Completed<Request> created(
      PeopleCaller caller, LeaveRequestCommand command) {
    TenantId tenant = caller.tenant();
    // One read of the time zone, one business date for the whole new command (D41A-3 rule 1).
    command.requireStartFrom(calendar.today(calendar.zone(tenant)));
    SelfLink link = link(caller);
    LeavePolicy policy =
        policies
            .covering(tenant, command.policyId(), command.startDate(), command.endDate())
            .orElseThrow(() -> new ApiException(ErrorCode.LEAVE_POLICY_NOT_REQUESTABLE, Map.of()));
    Employment employment =
        requests
            .employments(tenant, link.employeeId(), command.startDate(), command.endDate())
            .stream()
            .filter(candidate -> candidate.covers(command.startDate(), command.endDate()))
            .findFirst()
            .orElseThrow(() -> notEligible("EMPLOYMENT_PERIOD"));
    if (command
        .startDate()
        .isBefore(employment.effectiveFrom().plusDays(policy.minimumServiceDays()))) {
      throw notEligible("MINIMUM_SERVICE");
    }
    Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
    LeaveRequest request =
        new LeaveRequest(
            UUID.randomUUID(),
            link.employeeId(),
            employment.id(),
            policy.id(),
            policy.versionId(),
            policy.code(),
            policy.nameEn(),
            policy.nameFr(),
            policy.unit(),
            command.startDate(),
            command.endDate(),
            command.amount(),
            LeaveRequestState.PENDING,
            now,
            null,
            null,
            null,
            null);
    try {
      requests.insert(tenant, request, caller.subject());
    } catch (DataIntegrityViolationException violated) {
      if (LeaveConstraintViolations.constraint(violated)
          .filter(LeaveConstraintViolations.REQUEST_OVERLAP::equals)
          .isPresent()) {
        throw new ApiException(ErrorCode.LEAVE_REQUEST_OVERLAP, Map.of());
      }
      throw violated;
    }

    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("schemaVersion", LeavePolicyService.SCHEMA_VERSION);
    ids.put("requestId", request.id().toString());
    ids.put("employeeId", request.employeeId().toString());
    ids.put("employmentId", request.employmentId().toString());
    ids.put("policyId", request.policyId().toString());
    ids.put("policyVersionId", request.policyVersionId().toString());
    ids.put("unit", request.unit().name());
    ids.put("state", request.state().name());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            CREATE,
            "leave-request",
            request.id(),
            tenant.value(),
            "SUCCESS",
            caller.correlationId(),
            ids,
            Fingerprints.sha256(json.writeValueAsString(afterState(tenant, request, caller)))));
    Map<String, Object> data = new LinkedHashMap<>(ids);
    data.put("startDate", request.startDate().toString());
    data.put("endDate", request.endDate().toString());
    data.put("amount", request.amount().toPlainString());
    data.remove("schemaVersion");
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            EVENT_TYPE,
            LeavePolicyService.SCHEMA_VERSION,
            tenant.toString(),
            SOURCE,
            request.id().toString(),
            now.toString(),
            caller.correlationId(),
            null,
            data));
    return new IdempotentOperation.Completed<>(Request.of(request), request.id(), Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Cancel (MVP-041C)
  // ------------------------------------------------------------------------------------------

  /**
   * Cancels the caller's own pending request, or replays an earlier identical cancellation while
   * the request is still the caller's linked employee's (D41C-1, D41C-4).
   *
   * <p>One bounded transaction in the ADR 0008 order: idempotency (step 0), the caller's own link
   * and membership {@code FOR SHARE} (step 4), the request {@code FOR UPDATE}, bound to the tenant
   * and the linked employee (step 5), then the cancellation evidence, the transition, the deferred
   * V21 checks, the audit record and the outbox event (step 6). No manager-graph or employment
   * lock: it requests nothing after the request row, so it cannot invert a decision's
   * employment/link/request order. A decision and a cancellation of one request serialize on its
   * row; the second observes the terminal state.
   *
   * @param caller verified employee
   * @param requestId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body reason and its language
   * @return the receipt
   */
  public IdempotentOperation.Result<CancellationReceipt> cancel(
      PeopleCaller caller, String requestId, String idempotencyKey, CancelLeaveRequest body) {
    LeaveCancellationCommand command =
        operations.validated(
            CANCEL_SPEC, () -> LeaveCancellationCommand.from(idempotencyKey, requestId, body));
    return counted(
        CANCEL,
        () ->
            operations.execute(
                CANCEL_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                CancellationReceipt.class,
                TRANSACTION_TIMEOUT,
                stored -> cancellationReplayable(caller, stored),
                () -> cancelled(caller, command)));
  }

  /**
   * A stored cancellation receipt is returned only while the caller's current active link still
   * resolves to the employee whose request it cancelled (R88): no link is {@code 403
   * EMPLOYEE_LINK_REQUIRED}; otherwise {@code 404 LEAVE_REQUEST_NOT_FOUND}, with no stored response
   * or identifier.
   */
  private void cancellationReplayable(PeopleCaller caller, UUID requestId) {
    SelfLink link = link(caller);
    if (requestId == null
        || requests
            .find(caller.tenant(), link.employeeId(), requestId)
            .filter(request -> request.cancellation() != null)
            .isEmpty()) {
      throw LeaveDecisionCommand.notFound();
    }
  }

  private IdempotentOperation.Completed<CancellationReceipt> cancelled(
      PeopleCaller caller, LeaveCancellationCommand command) {
    TenantId tenant = caller.tenant();
    // The link is checked before anything about the request is answered.
    SelfLink link = link(caller);
    LeaveRouting routing =
        requests
            .lockOwn(tenant, link.employeeId(), command.requestId())
            .orElseThrow(LeaveDecisionCommand::notFound);
    if (routing.state().terminal()) {
      throw terminal(routing.state());
    }
    Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
    LeaveCancellation cancellation =
        new LeaveCancellation(
            UUID.randomUUID(), routing.id(), command.reasonLocale(), command.reason(), now);
    try {
      requests.cancel(tenant, cancellation, caller.subject());
      requests.checkTerminalConsistency(tenant);
    } catch (DataIntegrityViolationException violated) {
      Optional<String> constraint = LeaveConstraintViolations.constraint(violated);
      if (constraint.filter(LeaveConstraintViolations.CANCELLATION_UNIQUE::equals).isPresent()) {
        throw alreadyCancelled();
      }
      if (constraint
          .filter(LeaveConstraintViolations.CANCELLATION_CONSISTENCY::contains)
          .isPresent()) {
        // Never expected: the checks above hold the row lock. No database text is exposed.
        throw new IllegalStateException("leave cancellation inconsistent with its request");
      }
      throw violated;
    }

    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("schemaVersion", LeavePolicyService.SCHEMA_VERSION);
    ids.put("requestId", routing.id().toString());
    ids.put("cancellationId", cancellation.id().toString());
    ids.put("employeeId", routing.employeeId().toString());
    ids.put("employmentId", routing.employmentId().toString());
    ids.put("policyId", routing.policyId().toString());
    ids.put("policyVersionId", routing.policyVersionId().toString());
    Map<String, Object> metadata = new LinkedHashMap<>(ids);
    metadata.put("priorState", LeaveRequestState.PENDING.name());
    metadata.put("resultingState", LeaveRequestState.CANCELLED.name());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            CANCEL_AUDIT,
            "leave-request",
            routing.id(),
            tenant.value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                json.writeValueAsString(cancelledState(tenant, routing, cancellation, caller)))));
    Map<String, Object> data = new LinkedHashMap<>(ids);
    data.remove("schemaVersion");
    data.put("state", LeaveRequestState.CANCELLED.name());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            CANCELLED_EVENT,
            LeavePolicyService.SCHEMA_VERSION,
            tenant.toString(),
            SOURCE,
            routing.id().toString(),
            now.toString(),
            caller.correlationId(),
            null,
            data));
    LOG.atInfo()
        .addKeyValue("operation", CANCEL)
        .addKeyValue("approvalRoute", routing.route().name())
        .addKeyValue("outcome", "cancelled")
        .log("leave_request_self_cancelled");
    return new IdempotentOperation.Completed<>(
        new CancellationReceipt(routing.id(), cancellation.id(), LeaveRequestState.CANCELLED, now),
        routing.id(),
        Outcome.UPDATED);
  }

  /** The whole cancelled state, including the reason and the actor; only its digest is stored. */
  private static Map<String, Object> cancelledState(
      TenantId tenant, LeaveRouting routing, LeaveCancellation cancellation, PeopleCaller caller) {
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", tenant.toString());
    state.put("requestId", routing.id().toString());
    state.put("employeeId", routing.employeeId().toString());
    state.put("employmentId", routing.employmentId().toString());
    state.put("policyVersionId", routing.policyVersionId().toString());
    state.put("startDate", routing.startDate().toString());
    state.put("endDate", routing.endDate().toString());
    state.put("state", LeaveRequestState.CANCELLED.name());
    state.put("cancellationId", cancellation.id().toString());
    state.put("reasonLocale", cancellation.reasonLocale());
    state.put("reason", cancellation.reason());
    state.put("cancelledAt", cancellation.cancelledAt().toString());
    state.put("cancelledBy", caller.subject());
    return state;
  }

  private static ApiException alreadyCancelled() {
    return new ApiException(ErrorCode.LEAVE_REQUEST_ALREADY_CANCELLED, Map.of());
  }

  /** The conflict of a terminal request, by its state. */
  private static ApiException terminal(LeaveRequestState state) {
    return switch (state) {
      case CANCELLED -> alreadyCancelled();
      case AMENDED -> new ApiException(ErrorCode.LEAVE_REQUEST_ALREADY_AMENDED, Map.of());
      default -> new ApiException(ErrorCode.LEAVE_REQUEST_ALREADY_DECIDED, Map.of());
    };
  }

  // ------------------------------------------------------------------------------------------
  // Amend (MVP-041D)
  // ------------------------------------------------------------------------------------------

  /**
   * Replaces the caller's own pending request: the original becomes {@code AMENDED} and a new
   * {@code PENDING} replacement, which passes every current submission rule, takes its place, with
   * immutable amendment evidence. Or replays an earlier identical amendment while the original is
   * still the caller's linked employee's (D41DE-1, D41DE-3).
   *
   * <p>One bounded transaction in the ADR 0008 order: idempotency (step 0); a plain read of the
   * original to discover its employee, then that employee's employments that the original or the
   * replacement interval touch, locked {@code FOR SHARE} in UUID order (step 2); the caller's own
   * link and membership {@code FOR SHARE} (step 4); the original {@code FOR UPDATE}, bound to the
   * tenant and the linked employee (step 5); then the state and every submission rule on the
   * organization's business date, the transition, the replacement, the evidence, the deferred V22
   * checks, the audit record and the outbox event (step 6). No employment is locked after the
   * request. The original is moved to {@code AMENDED} before the replacement is inserted, so the
   * overlap exclusion excludes exactly the original; any failure rolls both back.
   *
   * @param caller verified employee
   * @param requestId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the replacement's fields, the reason and its language
   * @return the receipt
   */
  public IdempotentOperation.Result<AmendmentReceipt> amend(
      PeopleCaller caller, String requestId, String idempotencyKey, AmendLeaveRequest body) {
    LeaveAmendmentCommand command =
        operations.validated(
            AMEND_SPEC, () -> LeaveAmendmentCommand.from(idempotencyKey, requestId, body));
    return counted(
        AMEND,
        () ->
            operations.execute(
                AMEND_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                AmendmentReceipt.class,
                TRANSACTION_TIMEOUT,
                stored -> amendmentReplayable(caller, stored),
                () -> amended(caller, command)));
  }

  /**
   * A stored amendment receipt is returned only while the caller's current active link still
   * resolves to the employee whose request it amended (R88): no link is {@code 403
   * EMPLOYEE_LINK_REQUIRED}; otherwise {@code 404 LEAVE_REQUEST_NOT_FOUND}, with no stored response
   * or identifier.
   */
  private void amendmentReplayable(PeopleCaller caller, UUID requestId) {
    SelfLink link = link(caller);
    if (requestId == null
        || requests
            .find(caller.tenant(), link.employeeId(), requestId)
            .filter(request -> request.amendment() != null)
            .isEmpty()) {
      throw LeaveDecisionCommand.notFound();
    }
  }

  private IdempotentOperation.Completed<AmendmentReceipt> amended(
      PeopleCaller caller, LeaveAmendmentCommand command) {
    TenantId tenant = caller.tenant();
    LeaveRequestCommand replacement = command.replacement();
    // One read of the time zone, one business date for the whole command (D41A-3 rule 1).
    LocalDate today = calendar.today(calendar.zone(tenant));
    // Step 2: the employments the original and the replacement interval touch, in UUID order.
    Set<UUID> locked = new TreeSet<>();
    requests
        .routing(tenant, command.requestId())
        .ifPresent(
            seen -> {
              Set<UUID> wanted = new TreeSet<>();
              wanted.add(seen.employmentId());
              requests
                  .employments(
                      tenant, seen.employeeId(), replacement.startDate(), replacement.endDate())
                  .forEach(found -> wanted.add(found.id()));
              wanted.forEach(
                  id -> requests.shareEmployment(tenant, id).ifPresent(found -> locked.add(id)));
            });
    // Step 4: the link is checked before anything about the request is answered.
    SelfLink link = link(caller);
    // Step 5: the original, bound to the linked employee.
    LeaveRouting original =
        requests
            .lockOwn(tenant, link.employeeId(), command.requestId())
            .orElseThrow(LeaveDecisionCommand::notFound);
    if (original.state().terminal()) {
      throw terminal(original.state());
    }
    // Every current submission rule, against the replacement (D41A-3).
    replacement.requireStartFrom(today);
    LeavePolicy policy =
        policies
            .covering(
                tenant, replacement.policyId(), replacement.startDate(), replacement.endDate())
            .orElseThrow(() -> new ApiException(ErrorCode.LEAVE_POLICY_NOT_REQUESTABLE, Map.of()));
    Employment employment =
        requests
            .employments(tenant, link.employeeId(), replacement.startDate(), replacement.endDate())
            .stream()
            .filter(candidate -> locked.contains(candidate.id()))
            .filter(candidate -> candidate.covers(replacement.startDate(), replacement.endDate()))
            .findFirst()
            .orElseThrow(() -> notEligible("EMPLOYMENT_PERIOD"));
    if (replacement
        .startDate()
        .isBefore(employment.effectiveFrom().plusDays(policy.minimumServiceDays()))) {
      throw notEligible("MINIMUM_SERVICE");
    }
    Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
    LeaveRequest created =
        new LeaveRequest(
            UUID.randomUUID(),
            link.employeeId(),
            employment.id(),
            policy.id(),
            policy.versionId(),
            policy.code(),
            policy.nameEn(),
            policy.nameFr(),
            policy.unit(),
            replacement.startDate(),
            replacement.endDate(),
            replacement.amount(),
            LeaveRequestState.PENDING,
            now,
            null,
            null,
            null,
            original.id());
    LeaveAmendment amendment =
        new LeaveAmendment(
            UUID.randomUUID(),
            original.id(),
            created.id(),
            command.reasonLocale(),
            command.reason(),
            now);
    try {
      requests.amend(tenant, amendment, created, caller.subject());
      requests.checkTerminalConsistency(tenant);
    } catch (DataIntegrityViolationException violated) {
      Optional<String> constraint = LeaveConstraintViolations.constraint(violated);
      if (constraint.filter(LeaveConstraintViolations.REQUEST_OVERLAP::equals).isPresent()) {
        throw new ApiException(ErrorCode.LEAVE_REQUEST_OVERLAP, Map.of());
      }
      if (constraint
          .filter(LeaveConstraintViolations.AMENDMENT_ORIGINAL_UNIQUE::equals)
          .isPresent()) {
        throw new ApiException(ErrorCode.LEAVE_REQUEST_ALREADY_AMENDED, Map.of());
      }
      if (constraint
          .filter(LeaveConstraintViolations.AMENDMENT_CONSISTENCY::contains)
          .isPresent()) {
        // Never expected: the checks above hold the row lock. No database text is exposed.
        throw new IllegalStateException("leave amendment inconsistent with its requests");
      }
      throw violated;
    }

    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("schemaVersion", LeavePolicyService.SCHEMA_VERSION);
    ids.put("amendmentId", amendment.id().toString());
    ids.put("originalRequestId", original.id().toString());
    ids.put("replacementRequestId", created.id().toString());
    ids.put("employeeId", link.employeeId().toString());
    ids.put("originalEmploymentId", original.employmentId().toString());
    ids.put("replacementEmploymentId", created.employmentId().toString());
    ids.put("originalPolicyId", original.policyId().toString());
    ids.put("originalPolicyVersionId", original.policyVersionId().toString());
    ids.put("replacementPolicyId", created.policyId().toString());
    ids.put("replacementPolicyVersionId", created.policyVersionId().toString());
    ids.put("priorState", LeaveRequestState.PENDING.name());
    ids.put("originalState", LeaveRequestState.AMENDED.name());
    ids.put("replacementState", LeaveRequestState.PENDING.name());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            AMEND_AUDIT,
            "leave-request",
            original.id(),
            tenant.value(),
            "SUCCESS",
            caller.correlationId(),
            ids,
            Fingerprints.sha256(
                json.writeValueAsString(
                    amendedState(tenant, original, created, amendment, caller)))));
    Map<String, Object> data = new LinkedHashMap<>(ids);
    data.remove("schemaVersion");
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            AMENDED_EVENT,
            LeavePolicyService.SCHEMA_VERSION,
            tenant.toString(),
            SOURCE,
            original.id().toString(),
            now.toString(),
            caller.correlationId(),
            null,
            data));
    LOG.atInfo()
        .addKeyValue("operation", AMEND)
        .addKeyValue("approvalRoute", policy.approvalRoute().name())
        .addKeyValue("outcome", "amended")
        .log("leave_request_self_amended");
    return new IdempotentOperation.Completed<>(
        new AmendmentReceipt(
            amendment.id(),
            original.id(),
            created.id(),
            LeaveRequestState.AMENDED,
            LeaveRequestState.PENDING,
            now),
        original.id(),
        Outcome.CREATED);
  }

  /**
   * The whole amended state (both requests and the evidence), including the reason and the actor;
   * only its digest is stored.
   */
  private static Map<String, Object> amendedState(
      TenantId tenant,
      LeaveRouting original,
      LeaveRequest replacement,
      LeaveAmendment amendment,
      PeopleCaller caller) {
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", tenant.toString());
    state.put("originalRequestId", original.id().toString());
    state.put("originalState", LeaveRequestState.AMENDED.name());
    state.put("replacement", afterState(tenant, replacement, caller));
    state.put("amendmentId", amendment.id().toString());
    state.put("reasonLocale", amendment.reasonLocale());
    state.put("reason", amendment.reason());
    state.put("amendedAt", amendment.amendedAt().toString());
    state.put("amendedBy", caller.subject());
    return state;
  }

  // ------------------------------------------------------------------------------------------
  // History
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the caller's own requests, newest first. A fail-closed disclosure: the body is
   * returned only after its audit record committed with the read.
   *
   * @param caller verified caller
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 50, default 25)
   * @return the page
   */
  public RequestPage requests(PeopleCaller caller, String cursor, String limit) {
    return recorded(
        REQUESTS,
        () -> {
          TenantId tenant = caller.tenant();
          int size = LeavePolicyService.limit(limit);
          CursorScope scope = scope(REQUESTS, caller, size);
          KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
          if (after != null && !REQUEST_CODE.equals(after.code())) {
            throw LeavePolicyService.invalidCursor();
          }
          String page = after == null ? "first" : "next";
          RequestPage body =
              read(
                  () -> {
                    SelfLink link = link(caller);
                    LeaveRequest last = null;
                    if (after != null) {
                      last =
                          requests
                              .find(tenant, link.employeeId(), after.id())
                              .orElseThrow(LeavePolicyService::invalidCursor);
                    }
                    List<LeaveRequest> rows =
                        requests.page(
                            tenant,
                            link.employeeId(),
                            last == null ? null : last.submittedAt(),
                            last == null ? null : last.id(),
                            size + 1);
                    List<LeaveRequest> shown = rows.subList(0, Math.min(rows.size(), size));
                    String next =
                        rows.size() > size
                            ? cursors.encode(
                                scope,
                                new KeysetPosition(REQUEST_CODE, shown.get(shown.size() - 1).id()))
                            : null;
                    disclosed(caller, link.employeeId(), page, shown);
                    return new RequestPage(shown.stream().map(Request::of).toList(), next);
                  });
          LOG.atInfo()
              .addKeyValue("operation", REQUESTS)
              .addKeyValue("page", page)
              .addKeyValue("resultCount", body.items().size())
              .addKeyValue("outcome", "listed")
              .log("leave_request_self_listed");
          return body;
        });
  }

  /** The disclosure audit: the employee, the page and the count; the IDs only in the digest. */
  private void disclosed(
      PeopleCaller caller, UUID employeeId, String page, List<LeaveRequest> shown) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", LeavePolicyService.SCHEMA_VERSION);
    metadata.put("view", "my-leave-requests");
    metadata.put("page", page);
    metadata.put("resultCount", shown.size());
    StringBuilder text =
        new StringBuilder("DIVALHR-LEAVE-DISCLOSURE\nversion=1\nemployee=")
            .append(employeeId)
            .append('\n');
    shown.forEach(request -> text.append("id=").append(request.id()).append('\n'));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            calendar.now().truncatedTo(ChronoUnit.MICROS),
            caller.subject(),
            REQUESTS,
            "employee",
            employeeId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text.toString())));
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  /** Bound to the operation, tenant, subject (a digest) and page size. */
  private static CursorScope scope(String operation, PeopleCaller caller, int size) {
    return new CursorScope(
        operation,
        caller.tenant(),
        Map.of(
            "subject",
            Fingerprints.sha256("DIVALHR-LEAVE-SELF\nsubject=" + caller.subject()),
            "limit",
            Integer.toString(size)));
  }

  /** The caller's own active link ({@code FOR SHARE}), or {@code 403 EMPLOYEE_LINK_REQUIRED}. */
  private SelfLink link(PeopleCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  private static ApiException notEligible(String reason) {
    return new ApiException(ErrorCode.LEAVE_REQUEST_NOT_ELIGIBLE, Map.of("reason", reason));
  }

  /** A bounded read transaction; the result is returned only after it committed. */
  private <T> T read(Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) TRANSACTION_TIMEOUT.toSeconds());
    T result = template.execute(status -> work.get());
    if (result == null) {
      throw new IllegalStateException("read transaction returned nothing");
    }
    return result;
  }

  /** Records a read's outcome, counting link denials on the self-service counter. */
  private <T> T recorded(String operation, Supplier<T> work) {
    try {
      T result = counted(operation, work);
      metrics.record(operation, Outcome.LISTED);
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          rejected.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED
              ? Outcome.DENIED
              : Outcome.VALIDATION_FAILED;
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("leave_self_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }

  /**
   * Counts the self-service denials (A30-1) on the bounded counter only: no link, and (MVP-041C) no
   * such request of the caller's own.
   */
  private <T> T counted(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
      } else if (denied.code() == ErrorCode.LEAVE_REQUEST_NOT_FOUND) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "not_found");
      }
      throw denied;
    }
  }

  /** The whole persisted state; only its digest is stored ({@code after_state_sha256}). */
  private static Map<String, Object> afterState(
      TenantId tenant, LeaveRequest request, PeopleCaller caller) {
    Map<String, Object> state = new TreeMap<>();
    state.put("id", request.id().toString());
    state.put("tenantId", tenant.toString());
    state.put("employeeId", request.employeeId().toString());
    state.put("employmentId", request.employmentId().toString());
    state.put("policyVersionId", request.policyVersionId().toString());
    state.put("startDate", request.startDate().toString());
    state.put("endDate", request.endDate().toString());
    state.put("requestedAmount", request.amount().toPlainString());
    state.put("state", request.state().name());
    state.put("submittedAt", request.submittedAt().toString());
    state.put("submittedBy", caller.subject());
    return state;
  }
}
