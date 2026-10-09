package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.leave.api.CreateMyLeaveRequest;
import com.divalhr.core.people.leave.api.MyLeaveResponses.Policy;
import com.divalhr.core.people.leave.api.MyLeaveResponses.PolicyPage;
import com.divalhr.core.people.leave.api.MyLeaveResponses.Request;
import com.divalhr.core.people.leave.api.MyLeaveResponses.RequestPage;
import com.divalhr.core.people.leave.domain.Employment;
import com.divalhr.core.people.leave.domain.LeavePolicy;
import com.divalhr.core.people.leave.domain.LeaveRequest;
import com.divalhr.core.people.leave.domain.LeaveRequestState;
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
import java.util.TreeMap;
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
 * pending request and the caller's own request history.
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

  /** Counts the self-service link denial (A30-1) on the bounded counter only. */
  private <T> T counted(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
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
