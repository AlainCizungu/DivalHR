package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.PartnerCrypto;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.HoldView;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PlatformRequest;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PlatformRequestPage;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.RequestEvent;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.DecisionReason;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.RequestState;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.RequestType;
import com.divalhr.core.integrations.partner.privacy.PrivacyHoldStore.HoldRow;
import com.divalhr.core.integrations.partner.privacy.PrivacyQuotas.Quota;
import com.divalhr.core.integrations.partner.privacy.PrivacyRequestStore.RequestRow;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Platform privacy operations on employees' data-rights requests (LAUNCH-001, L7). The queue shows
 * a masked contact and never the private referral choices, the disclosed values or the operational
 * note. A decision never deletes anything: a purge is its own previewed and confirmed operation.
 * Tenant administrators have no route here.
 */
@Service
public class PlatformPrivacyRequestService {

  /** Operation names. */
  public static final String LIST = "privacy.request.platform.list";

  public static final String GET = "privacy.request.platform.get";
  public static final String TRANSITION = "privacy.request.platform.transition";

  private static final IdempotentOperation.Spec TRANSITION_SPEC =
      new IdempotentOperation.Spec(TRANSITION, "privacy_request", "decide", "decided", 200);

  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 50;

  private final IdempotentOperation operations;
  private final PrivacyRequestStore store;
  private final PrivacyHoldStore holds;
  private final PrivacyRecorder recorder;
  private final PrivacyQuotas quotas;
  private final PartnerCrypto crypto;
  private final CursorCodec cursors;
  private final JsonMapper json;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param store request storage
   * @param holds hold storage
   * @param recorder audit and outbox
   * @param quotas privacy quotas
   * @param crypto seals notes and opens the disclosure for masking only
   * @param cursors signed cursors
   * @param json JSON mapper
   * @param clock server clock
   */
  public PlatformPrivacyRequestService(
      IdempotentOperation operations,
      PrivacyRequestStore store,
      PrivacyHoldStore holds,
      PrivacyRecorder recorder,
      PrivacyQuotas quotas,
      PartnerCrypto crypto,
      CursorCodec cursors,
      JsonMapper json,
      Clock clock) {
    this.operations = operations;
    this.store = store;
    this.holds = holds;
    this.recorder = recorder;
    this.quotas = quotas;
    this.crypto = crypto;
    this.cursors = cursors;
    this.json = json;
    this.clock = clock;
  }

  /** The verified platform administrator. */
  public record Caller(String subject, String correlationId) {}

  private record Decision(
      int expectedVersion,
      RequestState to,
      DecisionReason reason,
      String employeeMessage,
      String note) {}

  /**
   * The platform queue.
   *
   * @param subject verified platform administrator
   * @param state raw state filter or {@code null}
   * @param type raw type filter or {@code null}
   * @param cursor opaque cursor or {@code null}
   * @param limit raw page size or {@code null}
   * @return the page
   */
  @Transactional(readOnly = true)
  public PlatformRequestPage list(
      String subject, String state, String type, String cursor, String limit) {
    int size = PrivacyCommands.limit(limit, DEFAULT_LIMIT, MAX_LIMIT);
    String stateFilter = null;
    if (state != null) {
      try {
        stateFilter = RequestState.valueOf(state).name();
      } catch (IllegalArgumentException unknown) {
        throw PrivacyCommands.fail("state", "FORMAT");
      }
    }
    String typeFilter = null;
    if (type != null) {
      try {
        typeFilter = RequestType.valueOf(type).name();
      } catch (IllegalArgumentException unknown) {
        throw PrivacyCommands.fail("type", "FORMAT");
      }
    }
    Map<String, String> filters = new TreeMap<>();
    filters.put("role", "platform-admin");
    filters.put("subject", subject);
    filters.put("limit", Integer.toString(size));
    if (stateFilter != null) {
      filters.put("state", stateFilter);
    }
    if (typeFilter != null) {
      filters.put("type", typeFilter);
    }
    CursorScope scope =
        new CursorScope(LIST, new TenantId(PrivacyRecorder.PLATFORM_SCOPE), filters);
    PrivacyRequestStore.Position after = null;
    if (cursor != null) {
      KeysetPosition position = cursors.decode(cursor, scope);
      RequestRow at =
          store
              .byIdAnyTenant(position.id())
              .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
      after = new PrivacyRequestStore.Position(at.createdAt(), at.id());
    }
    List<RequestRow> rows = store.page(stateFilter, typeFilter, after, size + 1);
    List<RequestRow> shown = rows.subList(0, Math.min(rows.size(), size));
    String next =
        rows.size() > size
            ? cursors.encode(scope, new KeysetPosition("PQP", shown.get(shown.size() - 1).id()))
            : null;
    return new PlatformRequestPage(shown.stream().map(this::view).toList(), next);
  }

  /**
   * One request; the read is audited by reference.
   *
   * @param caller verified platform administrator
   * @param reference raw request reference
   * @return the request
   */
  @Transactional
  public PlatformRequest get(Caller caller, String reference) {
    RequestRow row =
        store.byReference(checked(reference)).orElseThrow(PlatformPrivacyRequestService::notFound);
    recorder.record(
        caller.subject(),
        "privacy.request.platform-read",
        "integrations.privacy-request.read.v1",
        row.tenantId(),
        "privacy-request",
        row.id(),
        caller.correlationId(),
        Map.of("requestId", row.id().toString(), "actorRole", "PLATFORM"),
        clock.instant());
    return view(row);
  }

  /**
   * Decides or advances a request.
   *
   * @param caller verified platform administrator
   * @param reference raw request reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the request
   */
  public IdempotentOperation.Result<PlatformRequest> transition(
      Caller caller, String reference, String idempotencyKey, JsonNode body) {
    Decision decision =
        operations.validated(
            TRANSITION_SPEC,
            () -> {
              PrivacyCommands.key(idempotencyKey);
              List<Map<String, String>> problems = PrivacyCommands.problems();
              PrivacyCommands.onlyKeys(
                  body,
                  Set.of(
                      "expectedVersion",
                      "toState",
                      "reasonCode",
                      "employeeMessage",
                      "internalNote"),
                  problems);
              Integer version =
                  PrivacyCommands.integer(body, "expectedVersion", 1, Integer.MAX_VALUE, problems);
              RequestState to =
                  PrivacyCommands.enumeration(body, "toState", RequestState.class, problems);
              DecisionReason reason = null;
              JsonNode reasonNode = body.get("reasonCode");
              if (reasonNode == null) {
                problems.add(PrivacyCommands.problem("reasonCode", "REQUIRED"));
              } else if (!reasonNode.isNull()) {
                reason =
                    PrivacyCommands.enumeration(body, "reasonCode", DecisionReason.class, problems);
              }
              String message = optionalText(body, "employeeMessage", 500, problems);
              String note = optionalText(body, "internalNote", 1000, problems);
              PrivacyCommands.throwIfAny(problems);
              return new Decision(version, to, reason, message, note);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("request", reference);
    canonical.put("expectedVersion", decision.expectedVersion());
    canonical.put("toState", decision.to().name());
    canonical.put("reason", decision.reason() == null ? "-" : decision.reason().name());
    canonical.put("message", decision.employeeMessage() == null ? "-" : decision.employeeMessage());
    canonical.put("note", decision.note() == null ? "-" : decision.note());
    try {
      return operations.execute(
          TRANSITION_SPEC,
          caller.subject(),
          idempotencyKey,
          canonical,
          PlatformRequest.class,
          TIMEOUT,
          () -> decided(caller, reference, decision));
    } catch (ApiException refused) {
      if (refused.code() == ErrorCode.IDEMPOTENCY_KEY_REUSED) {
        throw new ApiException(ErrorCode.PRIVACY_IDEMPOTENCY_REUSED, Map.of());
      }
      throw refused;
    }
  }

  private IdempotentOperation.Completed<PlatformRequest> decided(
      Caller caller, String reference, Decision decision) {
    quotas.require(Quota.PLATFORM_MUTATIONS, caller.subject());
    RequestRow row =
        store
            .lockByReference(checked(reference))
            .orElseThrow(PlatformPrivacyRequestService::notFound);
    if (row.version() != decision.expectedVersion()) {
      throw new ApiException(ErrorCode.PRIVACY_REQUEST_VERSION_STALE, Map.of());
    }
    RequestState from = RequestState.valueOf(row.state());
    if (!PrivacyDomain.PLATFORM_TRANSITIONS.get(from).contains(decision.to())) {
      throw new ApiException(ErrorCode.PRIVACY_REQUEST_TRANSITION_INVALID, Map.of());
    }
    if ((decision.to() == RequestState.REFUSED || decision.to() == RequestState.PARTIALLY_APPROVED)
        && decision.reason() == null) {
      throw new ApiException(ErrorCode.PRIVACY_REQUEST_REASON_REQUIRED, Map.of());
    }
    Instant now = clock.instant();
    int sequence = store.events(row.tenantId(), row.id()).size() + 1;
    byte[] sealed = null;
    if (decision.note() != null) {
      sealed =
          crypto.seal(
              decision.note().getBytes(StandardCharsets.UTF_8),
              "privacy-request-note:"
                  + new TenantId(row.tenantId())
                  + ':'
                  + row.id()
                  + ':'
                  + sequence);
    }
    String reason = decision.reason() == null ? null : decision.reason().name();
    store.move(row.tenantId(), row.id(), decision.to().name(), row.version() + 1, reason, now);
    store.insertDecisionEvent(
        row.tenantId(),
        row.id(),
        sequence,
        from.name(),
        decision.to().name(),
        reason,
        decision.employeeMessage(),
        sealed,
        now);
    Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("requestId", row.id().toString());
    fields.put("requestType", row.requestType());
    fields.put("fromState", from.name());
    fields.put("state", decision.to().name());
    fields.put("version", row.version() + 1);
    fields.put("actorRole", "PLATFORM");
    if (reason != null) {
      fields.put("reasonCode", reason);
    }
    recorder.record(
        caller.subject(),
        "privacy.request.decide",
        "integrations.privacy-request.decided.v1",
        row.tenantId(),
        "privacy-request",
        row.id(),
        caller.correlationId(),
        fields,
        now);
    RequestRow updated = store.byReference(reference).orElseThrow();
    return new IdempotentOperation.Completed<>(view(updated), row.id(), Outcome.UPDATED);
  }

  private PlatformRequest view(RequestRow row) {
    List<RequestEvent> history =
        store.events(row.tenantId(), row.id()).stream()
            .map(
                e ->
                    new RequestEvent(
                        e.sequence(), e.fromState(), e.toState(), e.reason(), e.occurredAt()))
            .toList();
    List<HoldView> holdViews =
        holds.holds(row.tenantId(), row.employeeId(), row.referralId()).stream()
            .map(PlatformPrivacyRequestService::holdView)
            .toList();
    return new PlatformRequest(
        row.reference(),
        row.requestType(),
        row.state(),
        row.version(),
        row.tenantId(),
        row.referralReference(),
        contact(row),
        row.decisionReason(),
        store.latestEmployeeMessage(row.tenantId(), row.id()),
        store.notePresent(row.tenantId(), row.id()),
        row.createdAt(),
        row.updatedAt(),
        history,
        holdViews);
  }

  static HoldView holdView(HoldRow h) {
    return new HoldView(
        h.reference(),
        h.referralReference(),
        h.reason(),
        h.reviewDate(),
        h.externalReference(),
        h.noteRecorded(),
        h.state(),
        h.placedAt(),
        h.releasedAt());
  }

  /** The masked contact of the employee, or {@code ***} when no disclosure remains. */
  private String contact(RequestRow row) {
    return store
        .latestDisclosure(row.tenantId(), row.employeeId(), row.referralId())
        .map(
            d -> {
              JsonNode values =
                  json.readTree(
                      crypto.open(
                          d.sealed(),
                          "partner-referral:"
                              + new TenantId(row.tenantId())
                              + ":"
                              + d.referralId()));
              return maskEmail(values.path("EMPLOYEE_EMAIL").asString(""));
            })
        .orElse("***");
  }

  /** {@code a***@example.test}: first character of the local part and the domain. */
  static String maskEmail(String email) {
    int at = email.indexOf('@');
    if (at < 1) {
      return "***";
    }
    return email.charAt(0) + "***" + email.substring(at);
  }

  private static String optionalText(
      JsonNode body, String field, int max, List<Map<String, String>> problems) {
    JsonNode node = body.get(field);
    if (node == null) {
      problems.add(PrivacyCommands.problem(field, "REQUIRED"));
      return null;
    }
    if (node.isNull()) {
      return null;
    }
    return PrivacyCommands.string(body, field, 1, max, problems);
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
}
