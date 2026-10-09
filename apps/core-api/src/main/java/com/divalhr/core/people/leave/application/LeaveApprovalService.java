package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.ManagerGraphLock;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.leave.api.DecideLeaveRequest;
import com.divalhr.core.people.leave.api.LeaveApprovalResponses.Item;
import com.divalhr.core.people.leave.api.LeaveApprovalResponses.Page;
import com.divalhr.core.people.leave.api.LeaveApprovalResponses.Receipt;
import com.divalhr.core.people.leave.domain.ApprovalRoute;
import com.divalhr.core.people.leave.domain.Employment;
import com.divalhr.core.people.leave.domain.LeaveApprovalItem;
import com.divalhr.core.people.leave.domain.LeaveDecision;
import com.divalhr.core.people.leave.domain.LeaveRequestState;
import com.divalhr.core.people.leave.domain.LeaveRouting;
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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * Leave approval (MVP-041B, Issue #89; ADR 0010): the manager and tenant-administrator inboxes and
 * the one terminal decision on a pending request.
 *
 * <p>The route is the immutable {@code approval_route} of the request's policy version. A {@code
 * MANAGER} request belongs to the employee whose active, non-superseded MANAGER line covers the
 * request's employment on its first day, resolved through the caller's own active link and
 * re-evaluated on every list, decision and replay; a {@code TENANT_ADMIN} request belongs to the
 * tenant administrators. Anything else is {@code 404 LEAVE_REQUEST_NOT_FOUND} with empty params.
 *
 * <p>A decision is one bounded transaction in the ADR 0008 order. Manager: idempotency (step 0),
 * the tenant's manager-graph lock (step 1), the request's employment {@code FOR SHARE} (step 2),
 * the caller's link and membership {@code FOR SHARE} (step 4), the request {@code FOR UPDATE} (step
 * 5), then the decision, the transition, the deferred V20 checks, the audit record and the outbox
 * event (step 6). Tenant administrator: the same without steps 1 and 4. A replay re-runs the route
 * checks (manager: steps 1 and 4 and the reporting line) before any stored body is read.
 *
 * <p>The reason is Restricted HR: it is stored only in the decision row, returned only to the
 * requesting employee's own history, and never enters logs, metrics, audit metadata, events or the
 * idempotency response.
 */
@Service
public class LeaveApprovalService {

  /** Manager inbox (and its disclosure audit action). */
  public static final String MANAGER_LIST = "leave-approval.manager-list";

  /** Tenant-administrator inbox (and its disclosure audit action). */
  public static final String ADMIN_LIST = "leave-approval.admin-list";

  /** Manager decision (idempotency operation). */
  public static final String MANAGER_DECIDE = "leave-request.manager-decide";

  /** Tenant-administrator decision (idempotency operation). */
  public static final String ADMIN_DECIDE = "leave-request.admin-decide";

  /** Audit action of an approval. */
  public static final String APPROVE = "leave-request.approve";

  /** Audit action of a rejection. */
  public static final String REJECT = "leave-request.reject";

  /** Outbox event of an approval. */
  public static final String APPROVED_EVENT = "people.leave-request.approved.v1";

  /** Outbox event of a rejection. */
  public static final String REJECTED_EVENT = "people.leave-request.rejected.v1";

  /** Per-subject inbox bucket (60 per minute). */
  public static final String SUBJECT_READ_BUCKET = "leave-approval-read";

  /** Per-subject decision bucket (20 per minute). */
  public static final String SUBJECT_WRITE_BUCKET = "leave-approval-write";

  /** Per-tenant decision bucket (200 per 10 minutes). */
  public static final String TENANT_WRITE_BUCKET = "leave-approval-write";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.leave");
  private static final String SOURCE = "core-api/people";
  private static final String POSITION = "LA";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec MANAGER_SPEC =
      new IdempotentOperation.Spec(MANAGER_DECIDE, "leave_request", "decide", "decided", 200);
  private static final IdempotentOperation.Spec ADMIN_SPEC =
      new IdempotentOperation.Spec(ADMIN_DECIDE, "leave_request", "decide", "decided", 200);

  private final IdempotentOperation operations;
  private final JdbcLeaveRequestRepository requests;
  private final EmployeeAccessLinks links;
  private final ManagerGraphLock graph;
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
   * @param requests leave request storage
   * @param links the identity port (joins this module's transactions)
   * @param graph the tenant's manager-graph lock
   * @param calendar the platform clock
   * @param cursors signed cursors
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param meters meter registry (self-service denial counter)
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public LeaveApprovalService(
      IdempotentOperation operations,
      JdbcLeaveRequestRepository requests,
      EmployeeAccessLinks links,
      ManagerGraphLock graph,
      BusinessCalendar calendar,
      CursorCodec cursors,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager,
      JsonMapper json) {
    this.operations = operations;
    this.requests = requests;
    this.links = links;
    this.graph = graph;
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
  // Inboxes
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the pending {@code MANAGER} requests that report to the caller's linked employee on
   * their first day, newest first. A fail-closed disclosure: the body is returned only after its
   * audit record committed with the read.
   *
   * @param caller verified employee
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 50, default 25)
   * @return the page
   */
  public Page managerList(PeopleCaller caller, String cursor, String limit) {
    return listed(
        MANAGER_LIST,
        true,
        cursor,
        () -> {
          int size = LeavePolicyService.limit(limit);
          return read(
              () -> {
                SelfLink link = link(caller);
                CursorScope scope = managerScope(caller, link, size);
                Keyset after = after(caller.tenant(), cursor, scope);
                List<LeaveApprovalItem> rows =
                    requests.managerQueue(
                        caller.tenant(), link.employeeId(), after.at(), after.id(), size + 1);
                Page page = page(rows, size, scope);
                disclosed(caller, MANAGER_LIST, "employee", link.employeeId(), cursor, page);
                return page;
              });
        });
  }

  /**
   * One page of the pending {@code TENANT_ADMIN} requests of the tenant, newest first. A
   * fail-closed disclosure.
   *
   * @param caller verified tenant administrator
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 50, default 25)
   * @return the page
   */
  public Page adminList(PeopleCaller caller, String cursor, String limit) {
    return listed(
        ADMIN_LIST,
        false,
        cursor,
        () -> {
          int size = LeavePolicyService.limit(limit);
          CursorScope scope = adminScope(caller, size);
          return read(
              () -> {
                Keyset after = after(caller.tenant(), cursor, scope);
                List<LeaveApprovalItem> rows =
                    requests.adminQueue(caller.tenant(), after.at(), after.id(), size + 1);
                Page page = page(rows, size, scope);
                disclosed(
                    caller, ADMIN_LIST, "organization", caller.tenant().value(), cursor, page);
                return page;
              });
        });
  }

  private record Keyset(Instant at, UUID id) {}

  private Keyset after(TenantId tenant, String cursor, CursorScope scope) {
    if (cursor == null) {
      return new Keyset(null, null);
    }
    KeysetPosition position = cursors.decode(cursor, scope);
    if (!POSITION.equals(position.code())) {
      throw LeavePolicyService.invalidCursor();
    }
    Instant at =
        requests.submittedAt(tenant, position.id()).orElseThrow(LeavePolicyService::invalidCursor);
    return new Keyset(at, position.id());
  }

  private Page page(List<LeaveApprovalItem> rows, int size, CursorScope scope) {
    List<LeaveApprovalItem> shown = rows.subList(0, Math.min(rows.size(), size));
    String next =
        rows.size() > size
            ? cursors.encode(scope, new KeysetPosition(POSITION, shown.get(shown.size() - 1).id()))
            : null;
    return new Page(shown.stream().map(Item::of).toList(), next);
  }

  /** Bound to the operation, tenant, subject (a digest), the manager's link and page size. */
  private static CursorScope managerScope(PeopleCaller caller, SelfLink link, int size) {
    return new CursorScope(
        MANAGER_LIST,
        caller.tenant(),
        Map.of(
            "subject",
            subjectDigest(caller),
            "manager",
            link.employeeId().toString(),
            "link",
            link.linkId().toString(),
            "limit",
            Integer.toString(size)));
  }

  /** Bound to the operation, tenant, subject (a digest) and page size. */
  private static CursorScope adminScope(PeopleCaller caller, int size) {
    return new CursorScope(
        ADMIN_LIST,
        caller.tenant(),
        Map.of("subject", subjectDigest(caller), "limit", Integer.toString(size)));
  }

  private static String subjectDigest(PeopleCaller caller) {
    return Fingerprints.sha256("DIVALHR-LEAVE-APPROVAL\nsubject=" + caller.subject());
  }

  /** The disclosure audit: the view, page and count; the request IDs, in order, in the digest. */
  private void disclosed(
      PeopleCaller caller,
      String action,
      String resourceType,
      UUID resourceId,
      String cursor,
      Page page) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", LeavePolicyService.SCHEMA_VERSION);
    metadata.put("view", MANAGER_LIST.equals(action) ? "manager-inbox" : "admin-inbox");
    metadata.put("page", cursor == null ? "first" : "next");
    metadata.put("resultCount", page.items().size());
    StringBuilder text =
        new StringBuilder("DIVALHR-LEAVE-APPROVAL-DISCLOSURE\nversion=1\nview=")
            .append(metadata.get("view"))
            .append('\n');
    page.items().forEach(item -> text.append("id=").append(item.id()).append('\n'));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            calendar.now().truncatedTo(ChronoUnit.MICROS),
            caller.subject(),
            action,
            resourceType,
            resourceId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text.toString())));
  }

  // ------------------------------------------------------------------------------------------
  // Decisions
  // ------------------------------------------------------------------------------------------

  /**
   * Decides a pending {@code MANAGER} request of one of the caller's reports, or replays an earlier
   * identical decision while the caller is still that request's manager.
   *
   * @param caller verified employee
   * @param requestId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body decision
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> managerDecide(
      PeopleCaller caller, String requestId, String idempotencyKey, DecideLeaveRequest body) {
    LeaveDecisionCommand command =
        operations.validated(
            MANAGER_SPEC, () -> LeaveDecisionCommand.from(idempotencyKey, requestId, body));
    return counted(
        MANAGER_DECIDE,
        true,
        () ->
            operations.execute(
                MANAGER_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                Receipt.class,
                TRANSACTION_TIMEOUT,
                stored -> managerReplayable(caller, stored),
                () -> managerDecided(caller, command)));
  }

  /**
   * Decides a pending {@code TENANT_ADMIN} request, or replays an earlier identical decision.
   *
   * @param caller verified tenant administrator
   * @param requestId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body decision
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> adminDecide(
      PeopleCaller caller, String requestId, String idempotencyKey, DecideLeaveRequest body) {
    LeaveDecisionCommand command =
        operations.validated(
            ADMIN_SPEC, () -> LeaveDecisionCommand.from(idempotencyKey, requestId, body));
    return counted(
        ADMIN_DECIDE,
        false,
        () ->
            operations.execute(
                ADMIN_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                Receipt.class,
                TRANSACTION_TIMEOUT,
                stored -> adminReplayable(caller, stored),
                () -> adminDecided(caller, command)));
  }

  private IdempotentOperation.Completed<Receipt> managerDecided(
      PeopleCaller caller, LeaveDecisionCommand command) {
    TenantId tenant = caller.tenant();
    graph.lock(tenant);
    Optional<LeaveRouting> seen = requests.routing(tenant, command.requestId());
    Employment employment =
        seen.flatMap(found -> requests.shareEmployment(tenant, found.employmentId())).orElse(null);
    // The link is checked before anything about the request is answered.
    SelfLink link = link(caller);
    LeaveRouting routing =
        seen.isEmpty() ? null : requests.lockRouting(tenant, command.requestId()).orElse(null);
    if (routing == null
        || routing.route() != ApprovalRoute.MANAGER
        || !requests.reportsTo(tenant, routing.id(), link.employeeId())) {
      throw LeaveDecisionCommand.notFound();
    }
    return decided(caller, command, routing, employment, link.employeeId());
  }

  private IdempotentOperation.Completed<Receipt> adminDecided(
      PeopleCaller caller, LeaveDecisionCommand command) {
    TenantId tenant = caller.tenant();
    LeaveRouting seen =
        requests
            .routing(tenant, command.requestId())
            .filter(found -> found.route() == ApprovalRoute.TENANT_ADMIN)
            .orElseThrow(LeaveDecisionCommand::notFound);
    Employment employment = requests.shareEmployment(tenant, seen.employmentId()).orElse(null);
    LeaveRouting routing =
        requests
            .lockRouting(tenant, command.requestId())
            .filter(found -> found.route() == ApprovalRoute.TENANT_ADMIN)
            .orElseThrow(LeaveDecisionCommand::notFound);
    return decided(caller, command, routing, employment, null);
  }

  /** A manager's replay: the lock, the current link and the current reporting line again. */
  private void managerReplayable(PeopleCaller caller, UUID requestId) {
    TenantId tenant = caller.tenant();
    graph.lock(tenant);
    Optional<LeaveRouting> routing =
        requestId == null ? Optional.empty() : requests.routing(tenant, requestId);
    SelfLink link = link(caller);
    if (routing.isEmpty()
        || routing.get().route() != ApprovalRoute.MANAGER
        || !requests.reportsTo(tenant, requestId, link.employeeId())) {
      throw LeaveDecisionCommand.notFound();
    }
  }

  /** An administrator's replay: the stored decision is this tenant's, under the admin route. */
  private void adminReplayable(PeopleCaller caller, UUID requestId) {
    if (requestId == null
        || requests
            .decisionRoute(caller.tenant(), requestId)
            .filter(route -> route == ApprovalRoute.TENANT_ADMIN)
            .isEmpty()) {
      throw LeaveDecisionCommand.notFound();
    }
  }

  private IdempotentOperation.Completed<Receipt> decided(
      PeopleCaller caller,
      LeaveDecisionCommand command,
      LeaveRouting routing,
      Employment employment,
      UUID managerEmployeeId) {
    TenantId tenant = caller.tenant();
    if (routing.state().terminal()) {
      throw alreadyDecided();
    }
    // Approving needs the employment to cover the whole interval still; rejecting does not.
    if (command.outcome() == LeaveRequestState.APPROVED
        && (employment == null || !employment.covers(routing.startDate(), routing.endDate()))) {
      throw new ApiException(
          ErrorCode.LEAVE_REQUEST_NOT_ELIGIBLE, Map.of("reason", "EMPLOYMENT_PERIOD"));
    }
    Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
    LeaveDecision decision =
        new LeaveDecision(
            UUID.randomUUID(),
            routing.id(),
            command.outcome(),
            routing.route(),
            managerEmployeeId,
            command.reasonLocale(),
            command.reason(),
            now);
    try {
      requests.decide(tenant, decision, caller.subject());
      requests.checkDecisionConsistency();
    } catch (DataIntegrityViolationException violated) {
      Optional<String> constraint = LeaveConstraintViolations.constraint(violated);
      if (constraint.filter(LeaveConstraintViolations.DECISION_UNIQUE::equals).isPresent()
          || constraint.filter("leave_request_transition"::equals).isPresent()) {
        throw alreadyDecided();
      }
      if (constraint.filter(LeaveConstraintViolations.DECISION_CONSISTENCY::contains).isPresent()) {
        // Never expected: the checks above hold the row lock. No database text is exposed.
        throw new IllegalStateException("leave decision inconsistent with its request");
      }
      throw violated;
    }

    boolean approved = decision.outcome() == LeaveRequestState.APPROVED;
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("schemaVersion", LeavePolicyService.SCHEMA_VERSION);
    ids.put("requestId", routing.id().toString());
    ids.put("decisionId", decision.id().toString());
    ids.put("employeeId", routing.employeeId().toString());
    ids.put("employmentId", routing.employmentId().toString());
    ids.put("policyId", routing.policyId().toString());
    ids.put("policyVersionId", routing.policyVersionId().toString());
    ids.put("approvalRoute", routing.route().name());
    Map<String, Object> metadata = new LinkedHashMap<>(ids);
    metadata.put("priorState", LeaveRequestState.PENDING.name());
    metadata.put("resultingState", decision.outcome().name());
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            approved ? APPROVE : REJECT,
            "leave-request",
            routing.id(),
            tenant.value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                json.writeValueAsString(afterState(tenant, routing, decision, caller)))));
    Map<String, Object> data = new LinkedHashMap<>(ids);
    data.remove("schemaVersion");
    data.put("state", decision.outcome().name());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            approved ? APPROVED_EVENT : REJECTED_EVENT,
            LeavePolicyService.SCHEMA_VERSION,
            tenant.toString(),
            SOURCE,
            routing.id().toString(),
            now.toString(),
            caller.correlationId(),
            null,
            data));
    LOG.atInfo()
        .addKeyValue("operation", approved ? APPROVE : REJECT)
        .addKeyValue("approvalRoute", routing.route().name())
        .addKeyValue("outcome", approved ? "approved" : "rejected")
        .log(approved ? "leave_request_approved" : "leave_request_rejected");
    return new IdempotentOperation.Completed<>(
        new Receipt(routing.id(), decision.id(), decision.outcome(), now),
        routing.id(),
        Outcome.UPDATED);
  }

  /** The whole decided state, including the reason and the actor; only its digest is stored. */
  private static Map<String, Object> afterState(
      TenantId tenant, LeaveRouting routing, LeaveDecision decision, PeopleCaller caller) {
    Map<String, Object> state = new TreeMap<>();
    state.put("tenantId", tenant.toString());
    state.put("requestId", routing.id().toString());
    state.put("employeeId", routing.employeeId().toString());
    state.put("employmentId", routing.employmentId().toString());
    state.put("policyVersionId", routing.policyVersionId().toString());
    state.put("startDate", routing.startDate().toString());
    state.put("endDate", routing.endDate().toString());
    state.put("state", decision.outcome().name());
    state.put("decisionId", decision.id().toString());
    state.put("approvalRoute", decision.route().name());
    state.put(
        "managerEmployeeId",
        decision.managerEmployeeId() == null ? "" : decision.managerEmployeeId().toString());
    state.put("reasonLocale", decision.reasonLocale());
    state.put("reason", decision.reason());
    state.put("decidedAt", decision.decidedAt().toString());
    state.put("decidedBy", caller.subject());
    return state;
  }

  private static ApiException alreadyDecided() {
    return new ApiException(ErrorCode.LEAVE_REQUEST_ALREADY_DECIDED, Map.of());
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  /** The caller's own active link ({@code FOR SHARE}), or {@code 403 EMPLOYEE_LINK_REQUIRED}. */
  private SelfLink link(PeopleCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
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

  /** Records an inbox read's outcome and logs it without any value. */
  private Page listed(String operation, boolean selfService, String cursor, Supplier<Page> work) {
    try {
      Page page = counted(operation, selfService, work);
      metrics.record(operation, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("page", cursor == null ? "first" : "next")
          .addKeyValue("resultCount", page.items().size())
          .addKeyValue("outcome", "listed")
          .log("leave_approval_listed");
      return page;
    } catch (ApiException rejected) {
      Outcome outcome =
          rejected.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED
              ? Outcome.DENIED
              : Outcome.VALIDATION_FAILED;
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("leave_approval_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }

  /**
   * Counts an employee manager's denials (no link, or no such request for them) on the bounded
   * self-service counter only; tenant-administrator denials are the interceptor's MVP-013 evidence.
   */
  private <T> T counted(String operation, boolean selfService, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException denied) {
      if (selfService && denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
      } else if (selfService && denied.code() == ErrorCode.LEAVE_REQUEST_NOT_FOUND) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "not_found");
      }
      throw denied;
    }
  }
}
