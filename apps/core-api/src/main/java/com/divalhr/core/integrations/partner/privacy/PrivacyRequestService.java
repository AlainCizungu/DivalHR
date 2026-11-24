package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.RequestEvent;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.RequestPage;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.RequestView;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.RequestState;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.RequestType;
import com.divalhr.core.integrations.partner.privacy.PrivacyQuotas.Quota;
import com.divalhr.core.integrations.partner.privacy.PrivacyRequestStore.RequestRow;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The employee's own data-rights requests about partner-referral data (LAUNCH-001, L7). A request
 * is not a promise that it will be granted and deletes nothing by itself. Another employee's
 * request or referral is simply absent. Tenant administrators have no route here.
 */
@Service
public class PrivacyRequestService {

  /** Operation names. */
  public static final String CREATE = "privacy.request.create";

  public static final String LIST = "privacy.request.list";
  public static final String GET = "privacy.request.get";
  public static final String CANCEL = "privacy.request.cancel";

  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "privacy_request", "create", "created", 201);
  private static final IdempotentOperation.Spec CANCEL_SPEC =
      new IdempotentOperation.Spec(CANCEL, "privacy_request", "cancel", "cancelled", 200);

  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 50;
  private static final Set<RequestState> CANCELLABLE =
      Set.of(
          RequestState.SUBMITTED,
          RequestState.UNDER_REVIEW,
          RequestState.MORE_INFORMATION_REQUIRED);

  private final IdempotentOperation operations;
  private final EmployeeAccessLinks links;
  private final PrivacyRequestStore store;
  private final PrivacyRecorder recorder;
  private final PrivacyQuotas quotas;
  private final CursorCodec cursors;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param links employee access links
   * @param store request storage
   * @param recorder audit and outbox
   * @param quotas privacy quotas
   * @param cursors signed cursors
   * @param clock server clock
   */
  public PrivacyRequestService(
      IdempotentOperation operations,
      EmployeeAccessLinks links,
      PrivacyRequestStore store,
      PrivacyRecorder recorder,
      PrivacyQuotas quotas,
      CursorCodec cursors,
      Clock clock) {
    this.operations = operations;
    this.links = links;
    this.store = store;
    this.recorder = recorder;
    this.quotas = quotas;
    this.cursors = cursors;
    this.clock = clock;
  }

  /**
   * The verified employee. The tenant comes from the verified token, never from the request.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param correlationId request correlation id
   */
  public record Caller(TenantId tenant, String subject, String correlationId) {

    @Override
    public String toString() {
      return "PrivacyCaller[redacted]";
    }
  }

  private record Create(RequestType type, String referral) {}

  /**
   * Creates a request, or replays an identical earlier creation.
   *
   * @param caller verified employee
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the request
   */
  public IdempotentOperation.Result<RequestView> create(
      Caller caller, String idempotencyKey, JsonNode body) {
    Create command =
        operations.validated(
            CREATE_SPEC,
            () -> {
              PrivacyCommands.key(idempotencyKey);
              List<Map<String, String>> problems = PrivacyCommands.problems();
              PrivacyCommands.onlyKeys(body, Set.of("requestType", "referralId"), problems);
              RequestType type =
                  PrivacyCommands.enumeration(body, "requestType", RequestType.class, problems);
              String referral = null;
              JsonNode node = body.get("referralId");
              if (node == null) {
                problems.add(PrivacyCommands.problem("referralId", "REQUIRED"));
              } else if (!node.isNull()) {
                referral =
                    PrivacyCommands.pattern(body, "referralId", PrivacyCommands.REFERRAL, problems);
              }
              PrivacyCommands.throwIfAny(problems);
              return new Create(type, referral);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenant", caller.tenant().toString());
    canonical.put("type", command.type().name());
    canonical.put("referral", command.referral() == null ? "-" : command.referral());
    try {
      return operations.execute(
          CREATE_SPEC,
          caller.subject(),
          idempotencyKey,
          canonical,
          RequestView.class,
          TIMEOUT,
          () -> created(caller, command));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<RequestView> created(Caller caller, Create command) {
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    UUID referral = null;
    if (command.referral() != null) {
      referral =
          store
              .ownReferral(tenant.value(), link.employeeId(), command.referral())
              .orElseThrow(() -> new ApiException(ErrorCode.PRIVACY_REFERRAL_NOT_FOUND, Map.of()));
    }
    if (store.openExists(tenant.value(), link.employeeId(), command.type().name(), referral)) {
      throw new ApiException(ErrorCode.PRIVACY_REQUEST_DUPLICATE_OPEN, Map.of());
    }
    quotas.require(Quota.EMPLOYEE_REQUESTS, link.employeeId().toString());
    Instant now = clock.instant();
    UUID id = UUID.randomUUID();
    String reference = PrivacyCommands.newReference("PQ-");
    store.insert(
        tenant.value(), id, reference, link.employeeId(), command.type().name(), referral, now);
    store.insertEvent(
        tenant.value(), id, 1, null, RequestState.SUBMITTED.name(), "EMPLOYEE", null, now);
    recorder.record(
        caller.subject(),
        "privacy.request.create",
        "integrations.privacy-request.submitted.v1",
        tenant.value(),
        "privacy-request",
        id,
        caller.correlationId(),
        Map.of(
            "requestId",
            id.toString(),
            "requestType",
            command.type().name(),
            "state",
            "SUBMITTED",
            "version",
            1,
            "actorRole",
            "EMPLOYEE"),
        now);
    RequestRow row = store.mine(tenant.value(), link.employeeId(), reference).orElseThrow();
    return new IdempotentOperation.Completed<>(view(row), id, Outcome.CREATED);
  }

  /**
   * Cancels the employee's own open request.
   *
   * @param caller verified employee
   * @param reference raw request reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the request
   */
  public IdempotentOperation.Result<RequestView> cancel(
      Caller caller, String reference, String idempotencyKey, JsonNode body) {
    Integer expected =
        operations.validated(
            CANCEL_SPEC,
            () -> {
              PrivacyCommands.key(idempotencyKey);
              List<Map<String, String>> problems = PrivacyCommands.problems();
              PrivacyCommands.onlyKeys(body, Set.of("expectedVersion"), problems);
              Integer version =
                  PrivacyCommands.integer(body, "expectedVersion", 1, Integer.MAX_VALUE, problems);
              PrivacyCommands.throwIfAny(problems);
              return version;
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenant", caller.tenant().toString());
    canonical.put("request", reference);
    canonical.put("expectedVersion", expected);
    try {
      return operations.execute(
          CANCEL_SPEC,
          caller.subject(),
          idempotencyKey,
          canonical,
          RequestView.class,
          TIMEOUT,
          () -> cancelled(caller, reference, expected));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<RequestView> cancelled(
      Caller caller, String reference, int expected) {
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    RequestRow row =
        store
            .lockMine(tenant.value(), link.employeeId(), checked(reference))
            .orElseThrow(PrivacyRequestService::notFound);
    if (row.version() != expected) {
      throw new ApiException(ErrorCode.PRIVACY_REQUEST_VERSION_STALE, Map.of());
    }
    RequestState from = RequestState.valueOf(row.state());
    if (!CANCELLABLE.contains(from)) {
      throw new ApiException(ErrorCode.PRIVACY_REQUEST_TRANSITION_INVALID, Map.of());
    }
    Instant now = clock.instant();
    int sequence = store.events(tenant.value(), row.id()).size() + 1;
    store.move(
        tenant.value(),
        row.id(),
        RequestState.CANCELLED_BY_EMPLOYEE.name(),
        row.version() + 1,
        null,
        now);
    store.insertEvent(
        tenant.value(),
        row.id(),
        sequence,
        from.name(),
        RequestState.CANCELLED_BY_EMPLOYEE.name(),
        "EMPLOYEE",
        null,
        now);
    recorder.record(
        caller.subject(),
        "privacy.request.cancel",
        "integrations.privacy-request.cancelled.v1",
        tenant.value(),
        "privacy-request",
        row.id(),
        caller.correlationId(),
        Map.of(
            "requestId", row.id().toString(),
            "requestType", row.requestType(),
            "fromState", from.name(),
            "state", RequestState.CANCELLED_BY_EMPLOYEE.name(),
            "version", row.version() + 1,
            "actorRole", "EMPLOYEE"),
        now);
    RequestRow updated = store.mine(tenant.value(), link.employeeId(), reference).orElseThrow();
    return new IdempotentOperation.Completed<>(view(updated), row.id(), Outcome.UPDATED);
  }

  /**
   * The employee's own requests, newest first.
   *
   * @param caller verified employee
   * @param state raw state filter or {@code null}
   * @param cursor opaque cursor or {@code null}
   * @param limit raw page size or {@code null}
   * @return the page
   */
  @Transactional
  public RequestPage list(Caller caller, String state, String cursor, String limit) {
    int size = PrivacyCommands.limit(limit, DEFAULT_LIMIT, MAX_LIMIT);
    String stateFilter = null;
    if (state != null) {
      try {
        stateFilter = RequestState.valueOf(state).name();
      } catch (IllegalArgumentException unknown) {
        throw PrivacyCommands.fail("state", "FORMAT");
      }
    }
    SelfLink link = link(caller);
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "own-privacy-requests");
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    if (stateFilter != null) {
      filters.put("state", stateFilter);
    }
    CursorScope scope = new CursorScope(LIST, caller.tenant(), filters);
    PrivacyRequestStore.Position after = null;
    if (cursor != null) {
      KeysetPosition position = cursors.decode(cursor, scope);
      RequestRow at =
          store
              .byId(caller.tenant().value(), position.id())
              .filter(r -> r.employeeId().equals(link.employeeId()))
              .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
      after = new PrivacyRequestStore.Position(at.createdAt(), at.id());
    }
    List<RequestRow> rows =
        store.pageMine(caller.tenant().value(), link.employeeId(), stateFilter, after, size + 1);
    List<RequestRow> shown = rows.subList(0, Math.min(rows.size(), size));
    String next =
        rows.size() > size
            ? cursors.encode(scope, new KeysetPosition("PRQ", shown.get(shown.size() - 1).id()))
            : null;
    return new RequestPage(shown.stream().map(this::view).toList(), next);
  }

  /**
   * One of the employee's own requests.
   *
   * @param caller verified employee
   * @param reference raw request reference
   * @return the request
   */
  @Transactional
  public RequestView get(Caller caller, String reference) {
    SelfLink link = link(caller);
    return view(
        store
            .mine(caller.tenant().value(), link.employeeId(), checked(reference))
            .orElseThrow(PrivacyRequestService::notFound));
  }

  RequestView view(RequestRow row) {
    List<RequestEvent> history =
        store.events(row.tenantId(), row.id()).stream()
            .map(
                e ->
                    new RequestEvent(
                        e.sequence(), e.fromState(), e.toState(), e.reason(), e.occurredAt()))
            .toList();
    return new RequestView(
        row.reference(),
        row.requestType(),
        row.referralReference(),
        row.state(),
        row.version(),
        row.decisionReason(),
        store.latestEmployeeMessage(row.tenantId(), row.id()),
        row.createdAt(),
        row.updatedAt(),
        row.exportAvailable()
            || ("ACCESS".equals(row.requestType())
                && ("APPROVED".equals(row.state()) || "PARTIALLY_APPROVED".equals(row.state()))),
        history);
  }

  private SelfLink link(Caller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  private static String checked(String raw) {
    if (raw == null || !PrivacyCommands.REQUEST.matcher(raw).matches()) {
      throw notFound();
    }
    return raw;
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.PRIVACY_REQUEST_NOT_FOUND, Map.of());
  }

  private static ApiException translate(ApiException refused) {
    if (refused.code() == ErrorCode.IDEMPOTENCY_KEY_REUSED) {
      return new ApiException(ErrorCode.PRIVACY_IDEMPOTENCY_REUSED, Map.of());
    }
    return refused;
  }
}
