package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.leave.api.CreateLeavePolicyRequest;
import com.divalhr.core.people.leave.api.LeavePolicyResponses.Page;
import com.divalhr.core.people.leave.api.LeavePolicyResponses.Policy;
import com.divalhr.core.people.leave.api.LeavePolicyResponses.Result;
import com.divalhr.core.people.leave.domain.LeavePolicy;
import com.divalhr.core.people.leave.internal.JdbcLeavePolicyRepository;
import com.divalhr.core.people.leave.internal.LeaveConstraintViolations;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leave policy configuration (MVP-040A): create a policy with its version 1, and list policies with
 * their status on the organization's business date.
 *
 * <p>A creation is one transaction: idempotency reservation, policy, version 1, success audit, the
 * {@value #EVENT_TYPE} outbox event and the stored response commit or roll back together. Audit
 * metadata and event data hold identifiers, the code, the version and the enums only; names and
 * other submitted values never reach audit metadata, events, logs, metrics or errors. Lists are not
 * disclosure-audited (organization configuration, no personal data).
 */
@Service
public class LeavePolicyService {

  /** Create operation (authorization, idempotency scope, audit action, metrics, logs). */
  public static final String CREATE = "leave-policy.create";

  /** List operation (authorization, cursor binding, metrics, logs). */
  public static final String LIST = "leave-policy.list";

  /** Outbox event type (existing envelope). */
  public static final String EVENT_TYPE = "people.leave-policy.created.v1";

  /** Per-subject read bucket. */
  public static final String SUBJECT_READ_BUCKET = "leave-policy-read";

  /** Per-subject write bucket. */
  public static final String SUBJECT_WRITE_BUCKET = "leave-policy-write";

  /** Per-tenant write bucket. */
  public static final String TENANT_WRITE_BUCKET = "leave-policy-write";

  /** Default page size. */
  static final int DEFAULT_LIMIT = 25;

  /** Largest page size. */
  static final int MAX_LIMIT = 50;

  /** Audit and event schema version. */
  static final int SCHEMA_VERSION = 1;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.leave");
  private static final String SOURCE = "core-api/people";
  private static final String RESOURCE = "leave-policy";
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec SPEC =
      new IdempotentOperation.Spec(CREATE, "leave_policy", "create", "created", 201);

  /** The cursor's position code pins the business date: {@code A} + {@code yyyyMMdd}. */
  private static final Pattern CURSOR_CODE = Pattern.compile("^A([0-9]{8})$");

  static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");

  private final IdempotentOperation operations;
  private final JdbcLeavePolicyRepository policies;
  private final BusinessCalendar calendar;
  private final CursorCodec cursors;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final OperationMetrics metrics;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param policies leave policy storage
   * @param calendar the organization's business date (platform clock)
   * @param cursors signed cursors
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param metrics operation metrics
   * @param json JSON mapper
   */
  public LeavePolicyService(
      IdempotentOperation operations,
      JdbcLeavePolicyRepository policies,
      BusinessCalendar calendar,
      CursorCodec cursors,
      AuditRecorder audit,
      OutboxWriter outbox,
      OperationMetrics metrics,
      JsonMapper json) {
    this.operations = operations;
    this.policies = policies;
    this.calendar = calendar;
    this.cursors = cursors;
    this.audit = audit;
    this.outbox = outbox;
    this.metrics = metrics;
    this.json = json;
  }

  /**
   * Creates a policy with its version 1, or replays an earlier identical request.
   *
   * @param caller verified caller
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the created or replayed policy
   */
  public IdempotentOperation.Result<Result> create(
      PeopleCaller caller, String idempotencyKey, CreateLeavePolicyRequest request) {
    LeavePolicyCommand command =
        operations.validated(SPEC, () -> LeavePolicyCommand.from(idempotencyKey, request));
    return operations.execute(
        SPEC,
        caller.subject(),
        idempotencyKey,
        command.canonical(caller.tenant()),
        Result.class,
        TRANSACTION_TIMEOUT,
        () -> created(caller, command));
  }

  private IdempotentOperation.Completed<Result> created(
      PeopleCaller caller, LeavePolicyCommand command) {
    TenantId tenant = caller.tenant();
    ZoneId zone = calendar.zone(tenant);
    LocalDate asOf = calendar.today(zone);
    Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
    LeavePolicy policy =
        new LeavePolicy(
            UUID.randomUUID(),
            UUID.randomUUID(),
            command.code(),
            1,
            command.nameEn(),
            command.nameFr(),
            command.unit(),
            command.balanceMode(),
            command.annualEntitlement(),
            command.minimumServiceDays(),
            command.approvalRoute(),
            command.payrollEffect(),
            command.effectiveFrom(),
            command.effectiveTo(),
            now);
    try {
      policies.insert(tenant, policy, caller.subject());
    } catch (DataIntegrityViolationException violated) {
      if (LeaveConstraintViolations.constraint(violated)
          .filter(LeaveConstraintViolations.CODE_UNIQUE::equals)
          .isPresent()) {
        throw new ApiException(ErrorCode.LEAVE_POLICY_CODE_EXISTS, Map.of());
      }
      throw violated;
    }

    Map<String, Object> safe = safeAttributes(policy);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            CREATE,
            RESOURCE,
            policy.id(),
            tenant.value(),
            "SUCCESS",
            caller.correlationId(),
            safe,
            Fingerprints.sha256(json.writeValueAsString(afterState(tenant, policy, caller)))));
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            EVENT_TYPE,
            SCHEMA_VERSION,
            tenant.toString(),
            SOURCE,
            policy.id().toString(),
            now.toString(),
            caller.correlationId(),
            null,
            safe));
    Result body = new Result(Policy.of(policy, asOf), asOf, zone.getId());
    return new IdempotentOperation.Completed<>(body, policy.id(), Outcome.CREATED);
  }

  /**
   * One page of the tenant's policies ordered by code, then id. The first page reads the business
   * date; later pages reuse the one signed in the cursor, so every page reports the same {@code
   * asOf}.
   *
   * @param caller verified caller
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 50, default 25)
   * @return the page
   */
  @Transactional(readOnly = true)
  public Page list(PeopleCaller caller, String cursor, String limit) {
    try {
      TenantId tenant = caller.tenant();
      int size = limit(limit);
      CursorScope scope = new CursorScope(LIST, tenant, Map.of("limit", Integer.toString(size)));
      ZoneId zone = calendar.zone(tenant);
      LocalDate asOf;
      String afterCode = null;
      UUID afterId = null;
      if (cursor == null) {
        asOf = calendar.today(zone);
      } else {
        KeysetPosition position = cursors.decode(cursor, scope);
        asOf = pinned(position);
        afterId = position.id();
        afterCode = policies.code(tenant, afterId).orElseThrow(LeavePolicyService::invalidCursor);
      }
      List<LeavePolicy> rows = policies.page(tenant, afterCode, afterId, size + 1);
      List<LeavePolicy> shown = rows.subList(0, Math.min(rows.size(), size));
      String next = null;
      if (rows.size() > size) {
        next =
            cursors.encode(
                scope,
                new KeysetPosition("A" + asOf.format(BASIC), shown.get(shown.size() - 1).id()));
      }
      Page page =
          new Page(
              shown.stream().map(policy -> Policy.of(policy, asOf)).toList(),
              next,
              asOf,
              zone.getId());
      metrics.record(LIST, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", LIST)
          .addKeyValue("page", cursor == null ? "first" : "next")
          .addKeyValue("resultCount", shown.size())
          .addKeyValue("outcome", "listed")
          .log("leave_policy_listed");
      return page;
    } catch (ApiException rejected) {
      metrics.record(LIST, Outcome.VALIDATION_FAILED);
      LOG.atInfo()
          .addKeyValue("operation", LIST)
          .addKeyValue("outcome", "validation_failed")
          .addKeyValue("code", rejected.code().name())
          .log("leave_policy_list_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(LIST, Outcome.FAILURE);
      throw failure;
    }
  }

  static int limit(String raw) {
    if (raw == null || raw.isEmpty()) {
      return DEFAULT_LIMIT;
    }
    if (LIMIT.matcher(raw).matches()) {
      int value = Integer.parseInt(raw);
      if (value >= 1 && value <= MAX_LIMIT) {
        return value;
      }
    }
    FieldErrors errors = new FieldErrors();
    errors.add("limit", Constraint.RANGE);
    errors.throwIfAny();
    throw new IllegalStateException("unreachable");
  }

  static LocalDate pinned(KeysetPosition position) {
    Matcher matcher = CURSOR_CODE.matcher(position.code());
    if (!matcher.matches()) {
      throw invalidCursor();
    }
    try {
      return LocalDate.parse(matcher.group(1), BASIC);
    } catch (DateTimeParseException malformed) {
      throw invalidCursor();
    }
  }

  static ApiException invalidCursor() {
    return new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
  }

  /** Audit metadata and event data: identifiers, code, version and enums only (D40A-7). */
  private static Map<String, Object> safeAttributes(LeavePolicy policy) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("schemaVersion", SCHEMA_VERSION);
    values.put("policyId", policy.id().toString());
    values.put("versionId", policy.versionId().toString());
    values.put("code", policy.code());
    values.put("versionNumber", policy.versionNumber());
    values.put("unit", policy.unit().name());
    values.put("balanceMode", policy.balanceMode().name());
    values.put("approvalRoute", policy.approvalRoute().name());
    values.put("payrollEffect", policy.payrollEffect().name());
    return values;
  }

  /** The whole created state; only its digest is stored ({@code after_state_sha256}). */
  private static Map<String, Object> afterState(
      TenantId tenant, LeavePolicy policy, PeopleCaller caller) {
    Map<String, Object> state = new TreeMap<>(safeAttributes(policy));
    state.put("tenantId", tenant.toString());
    state.put("nameEn", policy.nameEn());
    state.put("nameFr", policy.nameFr());
    state.put(
        "annualEntitlement",
        policy.annualEntitlement() == null ? null : policy.annualEntitlement().toPlainString());
    state.put("minimumServiceDays", policy.minimumServiceDays());
    state.put("effectiveFrom", policy.effectiveFrom().toString());
    state.put("effectiveTo", policy.effectiveTo() == null ? null : policy.effectiveTo().toString());
    state.put("createdAt", policy.createdAt().toString());
    state.put("createdBy", caller.subject());
    return state;
  }
}
