package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.ManagerGraphLock;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.leave.api.CreateLeaveApprovalSubstitutionRequest;
import com.divalhr.core.people.leave.api.LeaveApprovalSubstitutionResponses.Item;
import com.divalhr.core.people.leave.api.LeaveApprovalSubstitutionResponses.Page;
import com.divalhr.core.people.leave.api.LeaveApprovalSubstitutionResponses.Receipt;
import com.divalhr.core.people.leave.api.LeaveApprovalSubstitutionResponses.RevocationReceipt;
import com.divalhr.core.people.leave.api.RevokeLeaveApprovalSubstitutionRequest;
import com.divalhr.core.people.leave.domain.LeaveSubstitution;
import com.divalhr.core.people.leave.domain.LeaveSubstitutionEntry;
import com.divalhr.core.people.leave.domain.LeaveSubstitutionRevocation;
import com.divalhr.core.people.leave.domain.LeaveSubstitutionState;
import com.divalhr.core.people.leave.internal.JdbcLeaveSubstitutionRepository;
import com.divalhr.core.people.leave.internal.LeaveConstraintViolations;
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
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * Leave approval substitutes (MVP-041G, Issue #97; ADR 0013): a tenant administrator appoints one
 * effective-dated substitute for a manager, lists the appointments and revokes one. The evidence is
 * never changed or deleted: an appointment is effective on a business date inside its inclusive
 * period while no revocation exists, and its state is derived on every read. Exclusivity (one
 * unrevoked appointment per manager per date, R98-1) is held by the operational coverage row each
 * unrevoked appointment has: inserted with the appointment, removed with its revocation, so a
 * revocation frees the remaining dates for a replacement.
 *
 * <p>Creation and revocation are one bounded transaction each: idempotency (step 0), the tenant's
 * manager-graph lock (step 1, which every manager decision also takes, so no decision reads an
 * appointment half-written or a revocation not yet committed), every employment of the manager and
 * the substitute overlapping the period {@code FOR SHARE} in UUID order (step 2), then the
 * appointment row (revocation: {@code FOR UPDATE}), the evidence and the coverage change, the
 * deferred coverage checks, the audit record and the outbox event. An exact replay rechecks only
 * the tenant-administrator authorization, done before any argument is read; a changed body under
 * the same key is the idempotency conflict first.
 *
 * <p>The reasons are Restricted HR: stored only in their rows and returned only in this list; never
 * in receipts, logs, metrics, audit metadata or events.
 */
@Service
public class LeaveSubstitutionService {

  /** Appointment (idempotency operation, interceptor operation and audit action). */
  public static final String CREATE = "leave-approval-substitution.create";

  /** List (interceptor operation). */
  public static final String LIST = "leave-approval-substitution.list";

  /** The list's fail-closed disclosure audit action. */
  public static final String READ = "leave-approval-substitution.read";

  /** Revocation (idempotency operation, interceptor operation and audit action). */
  public static final String REVOKE = "leave-approval-substitution.revoke";

  /** Outbox event of an appointment. */
  public static final String CREATED_EVENT = "people.leave-approval-substitution.created.v1";

  /** Outbox event of a revocation. */
  public static final String REVOKED_EVENT = "people.leave-approval-substitution.revoked.v1";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.leave");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "leave-approval-substitution";
  private static final String POSITION = "LS";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "leave_approval_substitution", "create", "created", 201);
  private static final IdempotentOperation.Spec REVOKE_SPEC =
      new IdempotentOperation.Spec(REVOKE, "leave_approval_substitution", "revoke", "revoked", 201);

  private final IdempotentOperation operations;
  private final JdbcLeaveSubstitutionRepository substitutions;
  private final ManagerGraphLock graph;
  private final BusinessCalendar calendar;
  private final CursorCodec cursors;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final OperationMetrics metrics;
  private final PlatformTransactionManager transactionManager;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param substitutions appointment storage
   * @param graph the tenant's manager-graph lock
   * @param calendar the platform clock
   * @param cursors signed cursors
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   * @param json JSON mapper
   */
  public LeaveSubstitutionService(
      IdempotentOperation operations,
      JdbcLeaveSubstitutionRepository substitutions,
      ManagerGraphLock graph,
      BusinessCalendar calendar,
      CursorCodec cursors,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager,
      JsonMapper json) {
    this.operations = operations;
    this.substitutions = substitutions;
    this.graph = graph;
    this.calendar = calendar;
    this.cursors = cursors;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.transactionManager = transactionManager;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------
  // Create
  // ------------------------------------------------------------------------------------------

  /**
   * Appoints a substitute for a manager, or replays an earlier identical appointment.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body appointment
   * @return the receipt
   */
  public IdempotentOperation.Result<Receipt> create(
      PeopleCaller caller, String idempotencyKey, CreateLeaveApprovalSubstitutionRequest body) {
    LeaveSubstitutionCommand command =
        operations.validated(
            CREATE_SPEC, () -> LeaveSubstitutionCommand.from(idempotencyKey, body));
    return operations.execute(
        CREATE_SPEC,
        caller.subject(),
        idempotencyKey,
        command.canonical(caller.tenant()),
        Receipt.class,
        TRANSACTION_TIMEOUT,
        () -> created(caller, command));
  }

  private IdempotentOperation.Completed<Receipt> created(
      PeopleCaller caller, LeaveSubstitutionCommand command) {
    TenantId tenant = caller.tenant();
    UUID manager = command.managerEmployeeId();
    UUID substitute = command.substituteEmployeeId();
    if (manager.equals(substitute)) {
      throw refused(ErrorCode.LEAVE_APPROVAL_SUBSTITUTION_SAME_EMPLOYEE);
    }
    graph.lock(tenant);
    List<UUID> employees = List.of(manager, substitute);
    if (substitutions.employeesFound(tenant, employees) != 2) {
      throw new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, Map.of());
    }
    LocalDate from = command.effectiveFrom();
    LocalDate to = command.effectiveTo();
    substitutions.shareEmployments(tenant, employees, from, to);
    if (!substitutions.employedThroughout(tenant, manager, from, to)
        || !substitutions.employedThroughout(tenant, substitute, from, to)) {
      throw refused(ErrorCode.LEAVE_APPROVAL_SUBSTITUTION_EMPLOYMENT_PERIOD);
    }
    if (!substitutions.managesDuring(tenant, manager, from, to)) {
      throw refused(ErrorCode.LEAVE_APPROVAL_SUBSTITUTION_NO_REPORTING_LINE);
    }
    if (substitutions.overlaps(tenant, manager, from, to)) {
      throw refused(ErrorCode.LEAVE_APPROVAL_SUBSTITUTION_OVERLAP);
    }
    Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
    LeaveSubstitution substitution =
        new LeaveSubstitution(
            UUID.randomUUID(),
            manager,
            substitute,
            from,
            to,
            command.reasonLocale(),
            command.reason(),
            now);
    try {
      substitutions.insert(tenant, substitution, caller.subject());
      substitutions.checkCoverage(tenant);
    } catch (DataIntegrityViolationException violated) {
      // Never expected under the lock; the stable conflict, no database text.
      if (LeaveConstraintViolations.constraint(violated)
          .filter(LeaveConstraintViolations.SUBSTITUTION_OVERLAP::equals)
          .isPresent()) {
        throw refused(ErrorCode.LEAVE_APPROVAL_SUBSTITUTION_OVERLAP);
      }
      throw violated;
    }
    LeaveSubstitutionState state =
        LeaveSubstitutionState.of(from, to, false, calendar.today(tenant));
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("schemaVersion", LeavePolicyService.SCHEMA_VERSION);
    ids.put("substitutionId", substitution.id().toString());
    ids.put("managerEmployeeId", manager.toString());
    ids.put("substituteEmployeeId", substitute.toString());
    ids.put("effectiveFrom", from.toString());
    ids.put("effectiveTo", to.toString());
    ids.put("state", state.name());
    Map<String, Object> afterState = new TreeMap<>(ids);
    afterState.remove("schemaVersion");
    afterState.put("tenantId", tenant.toString());
    afterState.put("reasonLocale", substitution.reasonLocale());
    afterState.put("reason", substitution.reason());
    afterState.put("createdAt", now.toString());
    afterState.put("createdBy", caller.subject());
    recorded(caller, CREATE, substitution.id(), ids, afterState, now);
    published(caller, CREATED_EVENT, substitution.id(), ids, now);
    LOG.atInfo()
        .addKeyValue("operation", CREATE)
        .addKeyValue("state", state.name())
        .log("leave_approval_substitution_appointed");
    return new IdempotentOperation.Completed<>(
        new Receipt(substitution.id(), now), substitution.id(), Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Revoke
  // ------------------------------------------------------------------------------------------

  /**
   * Revokes an appointment of the tenant, scheduled, active or expired, effective immediately; or
   * replays an earlier identical revocation. Decisions already taken are unchanged.
   *
   * @param caller verified tenant administrator
   * @param substitutionId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body revocation
   * @return the receipt
   */
  public IdempotentOperation.Result<RevocationReceipt> revoke(
      PeopleCaller caller,
      String substitutionId,
      String idempotencyKey,
      RevokeLeaveApprovalSubstitutionRequest body) {
    LeaveSubstitutionRevocationCommand command =
        operations.validated(
            REVOKE_SPEC,
            () -> LeaveSubstitutionRevocationCommand.from(idempotencyKey, substitutionId, body));
    return operations.execute(
        REVOKE_SPEC,
        caller.subject(),
        idempotencyKey,
        command.canonical(caller.tenant()),
        RevocationReceipt.class,
        TRANSACTION_TIMEOUT,
        () -> revoked(caller, command));
  }

  private IdempotentOperation.Completed<RevocationReceipt> revoked(
      PeopleCaller caller, LeaveSubstitutionRevocationCommand command) {
    TenantId tenant = caller.tenant();
    graph.lock(tenant);
    LeaveSubstitution seen =
        substitutions
            .find(tenant, command.substitutionId())
            .orElseThrow(LeaveSubstitutionRevocationCommand::notFound);
    substitutions.shareEmployments(
        tenant,
        List.of(seen.managerEmployeeId(), seen.substituteEmployeeId()),
        seen.effectiveFrom(),
        seen.effectiveTo());
    LeaveSubstitution substitution =
        substitutions
            .lock(tenant, command.substitutionId())
            .orElseThrow(LeaveSubstitutionRevocationCommand::notFound);
    if (substitutions.revoked(tenant, substitution.id())) {
      throw refused(ErrorCode.LEAVE_APPROVAL_SUBSTITUTION_ALREADY_REVOKED);
    }
    LocalDate today = calendar.today(tenant);
    LeaveSubstitutionState prior =
        LeaveSubstitutionState.of(
            substitution.effectiveFrom(), substitution.effectiveTo(), false, today);
    Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
    LeaveSubstitutionRevocation revocation =
        new LeaveSubstitutionRevocation(
            UUID.randomUUID(), substitution.id(), command.reasonLocale(), command.reason(), now);
    try {
      substitutions.revoke(tenant, revocation, caller.subject());
      substitutions.checkCoverage(tenant);
    } catch (DataIntegrityViolationException violated) {
      // Never expected: the appointment row is locked. No database text is exposed.
      if (LeaveConstraintViolations.constraint(violated)
          .filter(LeaveConstraintViolations.SUBSTITUTION_REVOCATION_UNIQUE::equals)
          .isPresent()) {
        throw refused(ErrorCode.LEAVE_APPROVAL_SUBSTITUTION_ALREADY_REVOKED);
      }
      throw violated;
    }
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("schemaVersion", LeavePolicyService.SCHEMA_VERSION);
    ids.put("substitutionId", substitution.id().toString());
    ids.put("revocationId", revocation.id().toString());
    ids.put("managerEmployeeId", substitution.managerEmployeeId().toString());
    ids.put("substituteEmployeeId", substitution.substituteEmployeeId().toString());
    ids.put("priorState", prior.name());
    ids.put("state", LeaveSubstitutionState.REVOKED.name());
    Map<String, Object> afterState = new TreeMap<>(ids);
    afterState.remove("schemaVersion");
    afterState.put("tenantId", tenant.toString());
    afterState.put("effectiveFrom", substitution.effectiveFrom().toString());
    afterState.put("effectiveTo", substitution.effectiveTo().toString());
    afterState.put("reasonLocale", revocation.reasonLocale());
    afterState.put("reason", revocation.reason());
    afterState.put("revokedAt", now.toString());
    afterState.put("revokedBy", caller.subject());
    recorded(caller, REVOKE, substitution.id(), ids, afterState, now);
    published(caller, REVOKED_EVENT, substitution.id(), ids, now);
    LOG.atInfo()
        .addKeyValue("operation", REVOKE)
        .addKeyValue("priorState", prior.name())
        .log("leave_approval_substitution_revoked");
    return new IdempotentOperation.Completed<>(
        new RevocationReceipt(
            substitution.id(), revocation.id(), LeaveSubstitutionState.REVOKED, now),
        substitution.id(),
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // List
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the tenant's appointments, newest first, with their states on the business date. A
   * fail-closed disclosure: the body is returned only after its audit record committed with the
   * read.
   *
   * @param caller verified tenant administrator
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 50, default 25)
   * @return the page
   */
  public Page list(PeopleCaller caller, String cursor, String limit) {
    try {
      int size = LeavePolicyService.limit(limit);
      CursorScope scope = scope(caller, size);
      Page page =
          read(
              () -> {
                TenantId tenant = caller.tenant();
                Keyset after = after(tenant, cursor, scope);
                LocalDate today = calendar.today(tenant);
                List<LeaveSubstitutionEntry> rows =
                    substitutions.page(tenant, after.at(), after.id(), size + 1);
                List<LeaveSubstitutionEntry> shown = rows.subList(0, Math.min(rows.size(), size));
                String next =
                    rows.size() > size
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                POSITION, shown.get(shown.size() - 1).substitution().id()))
                        : null;
                Page listed =
                    new Page(
                        shown.stream().map(entry -> Item.of(entry, today)).toList(), next, today);
                disclosed(caller, cursor, listed);
                return listed;
              });
      metrics.record(LIST, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", LIST)
          .addKeyValue("page", cursor == null ? "first" : "next")
          .addKeyValue("resultCount", page.items().size())
          .addKeyValue("outcome", "listed")
          .log("leave_approval_substitution_listed");
      return page;
    } catch (ApiException rejected) {
      metrics.record(LIST, Outcome.VALIDATION_FAILED);
      LOG.atInfo()
          .addKeyValue("operation", LIST)
          .addKeyValue("outcome", Outcome.VALIDATION_FAILED.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("leave_approval_substitution_list_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(LIST, Outcome.FAILURE);
      throw failure;
    }
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
        substitutions
            .createdAt(tenant, position.id())
            .orElseThrow(LeavePolicyService::invalidCursor);
    return new Keyset(at, position.id());
  }

  /** Bound to the operation, tenant, subject (a digest) and page size. */
  private static CursorScope scope(PeopleCaller caller, int size) {
    return new CursorScope(
        LIST,
        caller.tenant(),
        Map.of(
            "subject",
            Fingerprints.sha256("DIVALHR-LEAVE-SUBSTITUTION\nsubject=" + caller.subject()),
            "limit",
            Integer.toString(size)));
  }

  /**
   * The disclosure audit: the view, page and count; the IDs and states, in order, in the digest.
   */
  private void disclosed(PeopleCaller caller, String cursor, Page page) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", LeavePolicyService.SCHEMA_VERSION);
    metadata.put("view", "leave-approval-substitutes");
    metadata.put("page", cursor == null ? "first" : "next");
    metadata.put("resultCount", page.items().size());
    StringBuilder text =
        new StringBuilder("DIVALHR-LEAVE-SUBSTITUTION-DISCLOSURE\nversion=1\nasOf=")
            .append(page.asOf())
            .append('\n');
    page.items()
        .forEach(
            item ->
                text.append("id=")
                    .append(item.id())
                    .append(";state=")
                    .append(item.state())
                    .append('\n'));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            calendar.now().truncatedTo(ChronoUnit.MICROS),
            caller.subject(),
            READ,
            "organization",
            caller.tenant().value(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text.toString())));
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  private void recorded(
      PeopleCaller caller,
      String action,
      UUID substitutionId,
      Map<String, Object> metadata,
      Map<String, Object> afterState,
      Instant now) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            action,
            RESOURCE,
            substitutionId,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            new LinkedHashMap<>(metadata),
            Fingerprints.sha256(json.writeValueAsString(afterState))));
  }

  private void published(
      PeopleCaller caller, String type, UUID substitutionId, Map<String, Object> ids, Instant now) {
    Map<String, Object> data = new LinkedHashMap<>(ids);
    data.remove("schemaVersion");
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            type,
            LeavePolicyService.SCHEMA_VERSION,
            caller.tenant().toString(),
            SOURCE,
            substitutionId.toString(),
            now.toString(),
            caller.correlationId(),
            null,
            data));
  }

  private static ApiException refused(ErrorCode code) {
    return new ApiException(code, Map.of());
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
}
