package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.Checklist;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PolicyEvent;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PolicyHistory;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PolicyPage;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PolicyView;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.Rule;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.Action;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.DataClass;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.PolicyState;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.Trigger;
import com.divalhr.core.integrations.partner.privacy.PrivacyPolicyStore.PolicyRow;
import com.divalhr.core.integrations.partner.privacy.PrivacyQuotas.Quota;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Governed privacy-policy versions (LAUNCH-001, L3 and L4). A version is created as a DRAFT with
 * four rules whose durations the operator enters; DivalHR supplies none. Approval needs the
 * complete checklist with the review reference. An approved, scheduled, active or retired version
 * is never edited. This is a governance record, not a statement of legal compliance.
 */
@Service
public class PrivacyPolicyService {

  /** Operation names. */
  public static final String CREATE = "privacy.policy.create";

  public static final String LIST = "privacy.policy.list";
  public static final String GET = "privacy.policy.get";
  public static final String CHECKLIST = "privacy.policy.checklist";
  public static final String TRANSITION = "privacy.policy.transition";
  public static final String HISTORY = "privacy.policy.history";

  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "privacy_policy", "create", "created", 201);
  private static final IdempotentOperation.Spec CHECKLIST_SPEC =
      new IdempotentOperation.Spec(CHECKLIST, "privacy_policy_checklist", "put", "saved", 200);
  private static final IdempotentOperation.Spec TRANSITION_SPEC =
      new IdempotentOperation.Spec(TRANSITION, "privacy_policy", "transition", "transitioned", 200);

  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 50;
  private static final int MAX_DAYS = 36500;
  private static final Set<String> CREATE_KEYS =
      Set.of(
          "countryCode",
          "wordingVersion",
          "sourceReference",
          "reviewDate",
          "effectiveFrom",
          "effectiveTo",
          "rules");
  private static final Set<String> RULE_KEYS =
      Set.of("dataClass", "purpose", "action", "trigger", "durationDays");
  private static final Set<String> CHECKLIST_KEYS =
      Set.of(
          "jurisdictionIdentified",
          "sourceReferenceRecorded",
          "counselReviewRecorded",
          "dataClassesCovered",
          "lifecycleTriggerDefined",
          "durationsOrHoldSet",
          "employeeWordingVersionRecorded",
          "rollbackReviewComplete",
          "reviewReference");

  private final IdempotentOperation operations;
  private final PrivacyPolicyStore store;
  private final PrivacyRecorder recorder;
  private final PrivacyQuotas quotas;
  private final CursorCodec cursors;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param store policy storage
   * @param recorder audit and outbox
   * @param quotas privacy quotas
   * @param cursors signed cursors
   * @param clock server clock
   */
  public PrivacyPolicyService(
      IdempotentOperation operations,
      PrivacyPolicyStore store,
      PrivacyRecorder recorder,
      PrivacyQuotas quotas,
      CursorCodec cursors,
      Clock clock) {
    this.operations = operations;
    this.store = store;
    this.recorder = recorder;
    this.quotas = quotas;
    this.cursors = cursors;
    this.clock = clock;
  }

  /** The verified platform administrator. */
  public record Caller(String subject, String correlationId) {}

  private record Draft(
      String country,
      String wording,
      String source,
      LocalDate reviewDate,
      LocalDate from,
      LocalDate to,
      List<Rule> rules) {

    Map<String, Object> canonical() {
      Map<String, Object> map = new TreeMap<>();
      map.put("country", country);
      map.put("wording", wording);
      map.put("source", source);
      map.put("reviewDate", reviewDate.toString());
      map.put("from", from.toString());
      map.put("to", to == null ? "-" : to.toString());
      List<String> parts = new ArrayList<>();
      for (Rule r : rules) {
        parts.add(
            r.dataClass()
                + '|'
                + r.purpose()
                + '|'
                + r.action()
                + '|'
                + r.trigger()
                + '|'
                + (r.durationDays() == null ? "-" : r.durationDays()));
      }
      map.put("rules", String.join(";", parts));
      return map;
    }
  }

  // ------------------------------------------------------------------------------------------
  // Create
  // ------------------------------------------------------------------------------------------

  /**
   * Creates a DRAFT version, or replays an identical earlier creation.
   *
   * @param caller verified platform administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the version
   */
  public IdempotentOperation.Result<PolicyView> create(
      Caller caller, String idempotencyKey, JsonNode body) {
    Draft draft =
        operations.validated(
            CREATE_SPEC,
            () -> {
              PrivacyCommands.key(idempotencyKey);
              return parseDraft(body);
            });
    try {
      return operations.execute(
          CREATE_SPEC,
          caller.subject(),
          idempotencyKey,
          draft.canonical(),
          PolicyView.class,
          TIMEOUT,
          () -> created(caller, draft));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<PolicyView> created(Caller caller, Draft draft) {
    quotas.require(Quota.PLATFORM_MUTATIONS, caller.subject());
    Instant now = clock.instant();
    UUID id = UUID.randomUUID();
    String reference = PrivacyCommands.newReference("PL-");
    store.insert(
        id,
        reference,
        draft.country(),
        draft.wording(),
        draft.source(),
        draft.reviewDate(),
        draft.from(),
        draft.to(),
        caller.subject(),
        now);
    for (Rule rule : draft.rules()) {
      store.insertRule(id, rule);
    }
    store.insertChecklist(id, now);
    store.insertEvent(id, 1, null, PolicyState.DRAFT.name(), now);
    recorder.record(
        caller.subject(),
        "privacy.policy.create",
        "integrations.privacy-policy.created.v1",
        PrivacyRecorder.PLATFORM_SCOPE,
        "privacy-policy",
        id,
        caller.correlationId(),
        Map.of("policyId", id.toString(), "state", "DRAFT", "version", 1),
        now);
    return new IdempotentOperation.Completed<>(view(id), id, Outcome.CREATED);
  }

  private Draft parseDraft(JsonNode body) {
    List<Map<String, String>> problems = PrivacyCommands.problems();
    PrivacyCommands.onlyKeys(body, CREATE_KEYS, problems);
    String country = PrivacyCommands.string(body, "countryCode", 2, 2, problems);
    if (country != null && !country.matches("^[A-Z]{2}$")) {
      problems.add(PrivacyCommands.problem("countryCode", "FORMAT"));
    }
    String wording = PrivacyCommands.string(body, "wordingVersion", 1, 40, problems);
    if (wording != null && !wording.matches("^[A-Za-z0-9._-]{1,40}$")) {
      problems.add(PrivacyCommands.problem("wordingVersion", "FORMAT"));
    }
    String source = PrivacyCommands.string(body, "sourceReference", 3, 300, problems);
    LocalDate review = PrivacyCommands.date(body, "reviewDate", false, problems);
    LocalDate from = PrivacyCommands.date(body, "effectiveFrom", false, problems);
    LocalDate to = PrivacyCommands.date(body, "effectiveTo", true, problems);
    List<Rule> rules = new ArrayList<>();
    JsonNode array = body.get("rules");
    if (array == null || array.isNull()) {
      problems.add(PrivacyCommands.problem("rules", "REQUIRED"));
    } else if (!array.isArray() || array.size() != 4) {
      problems.add(PrivacyCommands.problem("rules", "LENGTH"));
    } else {
      for (JsonNode item : array) {
        if (!item.isObject()) {
          problems.add(PrivacyCommands.problem("rules", "FORMAT"));
          continue;
        }
        PrivacyCommands.onlyKeys(item, RULE_KEYS, problems);
        DataClass dataClass =
            PrivacyCommands.enumeration(item, "dataClass", DataClass.class, problems);
        PrivacyDomain.Purpose purpose =
            PrivacyCommands.enumeration(item, "purpose", PrivacyDomain.Purpose.class, problems);
        Action action = PrivacyCommands.enumeration(item, "action", Action.class, problems);
        Trigger trigger = PrivacyCommands.enumeration(item, "trigger", Trigger.class, problems);
        Integer days = null;
        JsonNode daysNode = item.get("durationDays");
        if (daysNode == null) {
          problems.add(PrivacyCommands.problem("durationDays", "REQUIRED"));
        } else if (!daysNode.isNull()) {
          days = PrivacyCommands.integer(item, "durationDays", 1, MAX_DAYS, problems);
        }
        if (dataClass != null && purpose != null && action != null && trigger != null) {
          rules.add(
              new Rule(dataClass.name(), purpose.name(), action.name(), trigger.name(), days));
        }
      }
    }
    PrivacyCommands.throwIfAny(problems);
    if (to != null && to.isBefore(from)) {
      throw invalid();
    }
    checkRules(rules);
    return new Draft(country, wording, source, review, from, to, List.copyOf(rules));
  }

  /** The semantic rules of a rule set; each refusal is {@code PRIVACY_POLICY_INVALID}. */
  static void checkRules(List<Rule> rules) {
    Map<DataClass, Rule> byClass = new EnumMap<>(DataClass.class);
    for (Rule rule : rules) {
      DataClass dataClass = DataClass.valueOf(rule.dataClass());
      Action action = Action.valueOf(rule.action());
      if (byClass.put(dataClass, rule) != null
          || !dataClass.purpose().name().equals(rule.purpose())
          || (action == Action.HOLD) != (rule.durationDays() == null)
          || (dataClass == DataClass.PARTNER_REFERRAL_LIFECYCLE_EVIDENCE
              && action == Action.ERASE)) {
        throw invalid();
      }
    }
    if (byClass.size() != 4) {
      throw invalid();
    }
    Rule disclosure = byClass.get(DataClass.PARTNER_REFERRAL_DISCLOSURE);
    Rule consent = byClass.get(DataClass.PARTNER_REFERRAL_CONSENT);
    Rule diagnostics = byClass.get(DataClass.PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS);
    if (Action.valueOf(consent.action()) == Action.ERASE
        && (!orderedBefore(disclosure, consent) || !orderedBefore(diagnostics, consent))) {
      throw invalid();
    }
  }

  /** Whether the evidence rule is purged no later than the consent, from the same trigger. */
  private static boolean orderedBefore(Rule evidence, Rule consent) {
    return Action.valueOf(evidence.action()) != Action.HOLD
        && evidence.trigger().equals(consent.trigger())
        && evidence.durationDays() <= consent.durationDays();
  }

  // ------------------------------------------------------------------------------------------
  // Checklist
  // ------------------------------------------------------------------------------------------

  /**
   * Replaces the checklist of a DRAFT.
   *
   * @param caller verified platform administrator
   * @param reference raw policy reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the version
   */
  public IdempotentOperation.Result<PolicyView> putChecklist(
      Caller caller, String reference, String idempotencyKey, JsonNode body) {
    record Command(int expectedVersion, Checklist checklist) {}
    Command command =
        operations.validated(
            CHECKLIST_SPEC,
            () -> {
              PrivacyCommands.key(idempotencyKey);
              List<Map<String, String>> problems = PrivacyCommands.problems();
              PrivacyCommands.onlyKeys(body, Set.of("expectedVersion", "checklist"), problems);
              Integer version =
                  PrivacyCommands.integer(body, "expectedVersion", 1, Integer.MAX_VALUE, problems);
              JsonNode node = body.get("checklist");
              Checklist checklist = null;
              if (node == null || !node.isObject()) {
                problems.add(PrivacyCommands.problem("checklist", "REQUIRED"));
              } else {
                PrivacyCommands.onlyKeys(node, CHECKLIST_KEYS, problems);
                Boolean a = PrivacyCommands.bool(node, "jurisdictionIdentified", problems);
                Boolean b = PrivacyCommands.bool(node, "sourceReferenceRecorded", problems);
                Boolean c = PrivacyCommands.bool(node, "counselReviewRecorded", problems);
                Boolean d = PrivacyCommands.bool(node, "dataClassesCovered", problems);
                Boolean e = PrivacyCommands.bool(node, "lifecycleTriggerDefined", problems);
                Boolean f = PrivacyCommands.bool(node, "durationsOrHoldSet", problems);
                Boolean g = PrivacyCommands.bool(node, "employeeWordingVersionRecorded", problems);
                Boolean h = PrivacyCommands.bool(node, "rollbackReviewComplete", problems);
                String ref = null;
                JsonNode refNode = node.get("reviewReference");
                if (refNode == null) {
                  problems.add(PrivacyCommands.problem("reviewReference", "REQUIRED"));
                } else if (!refNode.isNull()) {
                  ref = PrivacyCommands.string(node, "reviewReference", 1, 120, problems);
                }
                if (problems.isEmpty()) {
                  checklist = new Checklist(a, b, c, d, e, f, g, h, ref);
                }
              }
              PrivacyCommands.throwIfAny(problems);
              return new Command(version, checklist);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("policy", reference);
    canonical.put("expectedVersion", command.expectedVersion());
    canonical.put("checklist", command.checklist().toString());
    try {
      return operations.execute(
          CHECKLIST_SPEC,
          caller.subject(),
          idempotencyKey,
          canonical,
          PolicyView.class,
          TIMEOUT,
          () -> {
            quotas.require(Quota.PLATFORM_MUTATIONS, caller.subject());
            Instant now = clock.instant();
            PolicyRow row = lock(reference);
            if (row.version() != command.expectedVersion()) {
              throw new ApiException(ErrorCode.PRIVACY_POLICY_VERSION_STALE, Map.of());
            }
            if (!PolicyState.DRAFT.name().equals(row.state())) {
              throw new ApiException(ErrorCode.PRIVACY_POLICY_IMMUTABLE, Map.of());
            }
            store.updateChecklist(row.id(), command.checklist(), now);
            store.move(row.id(), row.state(), row.version() + 1, now);
            recorder.record(
                caller.subject(),
                "privacy.policy.checklist",
                "integrations.privacy-policy.checklist-saved.v1",
                PrivacyRecorder.PLATFORM_SCOPE,
                "privacy-policy",
                row.id(),
                caller.correlationId(),
                Map.of(
                    "policyId", row.id().toString(),
                    "state", row.state(),
                    "version", row.version() + 1),
                now);
            return new IdempotentOperation.Completed<>(view(row.id()), row.id(), Outcome.UPDATED);
          });
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  // ------------------------------------------------------------------------------------------
  // Transition
  // ------------------------------------------------------------------------------------------

  /**
   * Moves a version along its closed lifecycle.
   *
   * @param caller verified platform administrator
   * @param reference raw policy reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the version
   */
  public IdempotentOperation.Result<PolicyView> transition(
      Caller caller, String reference, String idempotencyKey, JsonNode body) {
    record Command(int expectedVersion, PolicyState to) {}
    Command command =
        operations.validated(
            TRANSITION_SPEC,
            () -> {
              PrivacyCommands.key(idempotencyKey);
              List<Map<String, String>> problems = PrivacyCommands.problems();
              PrivacyCommands.onlyKeys(body, Set.of("expectedVersion", "toState"), problems);
              Integer version =
                  PrivacyCommands.integer(body, "expectedVersion", 1, Integer.MAX_VALUE, problems);
              PolicyState to =
                  PrivacyCommands.enumeration(body, "toState", PolicyState.class, problems);
              PrivacyCommands.throwIfAny(problems);
              return new Command(version, to);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("policy", reference);
    canonical.put("expectedVersion", command.expectedVersion());
    canonical.put("toState", command.to().name());
    try {
      return operations.execute(
          TRANSITION_SPEC,
          caller.subject(),
          idempotencyKey,
          canonical,
          PolicyView.class,
          TIMEOUT,
          () -> moved(caller, reference, command.expectedVersion(), command.to()));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<PolicyView> moved(
      Caller caller, String reference, int expectedVersion, PolicyState to) {
    quotas.require(Quota.PLATFORM_MUTATIONS, caller.subject());
    Instant now = clock.instant();
    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    PolicyRow row = lock(reference);
    if (row.version() != expectedVersion) {
      throw new ApiException(ErrorCode.PRIVACY_POLICY_VERSION_STALE, Map.of());
    }
    PolicyState from = PolicyState.valueOf(row.state());
    if (!PrivacyDomain.POLICY_TRANSITIONS.get(from).contains(to)) {
      throw new ApiException(ErrorCode.PRIVACY_POLICY_TRANSITION_INVALID, Map.of());
    }
    switch (to) {
      case APPROVED -> {
        if (!store.checklist(row.id()).complete()) {
          throw new ApiException(ErrorCode.PRIVACY_POLICY_CHECKLIST_INCOMPLETE, Map.of());
        }
      }
      case SCHEDULED -> {
        if (!row.effectiveFrom().isAfter(today)) {
          throw new ApiException(ErrorCode.PRIVACY_POLICY_TRANSITION_INVALID, Map.of());
        }
      }
      case ACTIVE -> {
        if (row.effectiveFrom().isAfter(today)
            || (row.effectiveTo() != null && row.effectiveTo().isBefore(today))) {
          throw new ApiException(ErrorCode.PRIVACY_POLICY_TRANSITION_INVALID, Map.of());
        }
        if (store.overlapsActive(
            row.id(), row.countryCode(), row.effectiveFrom(), row.effectiveTo())) {
          throw new ApiException(ErrorCode.PRIVACY_POLICY_OVERLAP, Map.of());
        }
      }
      default -> {
        // RETIRED needs no further condition.
      }
    }
    int sequence = store.events(row.id()).size() + 1;
    store.move(row.id(), to.name(), row.version() + 1, now);
    store.insertEvent(row.id(), sequence, from.name(), to.name(), now);
    recorder.record(
        caller.subject(),
        "privacy.policy.transition",
        "integrations.privacy-policy.transitioned.v1",
        PrivacyRecorder.PLATFORM_SCOPE,
        "privacy-policy",
        row.id(),
        caller.correlationId(),
        Map.of(
            "policyId", row.id().toString(),
            "fromState", from.name(),
            "state", to.name(),
            "version", row.version() + 1),
        now);
    return new IdempotentOperation.Completed<>(view(row.id()), row.id(), Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * A page of versions, newest first.
   *
   * @param subject verified platform administrator
   * @param cursor opaque cursor or {@code null}
   * @param limit raw page size or {@code null}
   * @return the page
   */
  @Transactional(readOnly = true)
  public PolicyPage list(String subject, String cursor, String limit) {
    int size = PrivacyCommands.limit(limit, DEFAULT_LIMIT, MAX_LIMIT);
    Map<String, String> filters = new TreeMap<>();
    filters.put("role", "platform-admin");
    filters.put("subject", subject);
    filters.put("limit", Integer.toString(size));
    CursorScope scope =
        new CursorScope(LIST, new TenantId(PrivacyRecorder.PLATFORM_SCOPE), filters);
    PrivacyPolicyStore.Position after = null;
    if (cursor != null) {
      KeysetPosition position = cursors.decode(cursor, scope);
      PolicyRow at =
          store
              .byId(position.id())
              .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
      after = new PrivacyPolicyStore.Position(at.createdAt(), at.id());
    }
    List<PolicyRow> rows = store.page(after, size + 1);
    List<PolicyRow> shown = rows.subList(0, Math.min(rows.size(), size));
    String next =
        rows.size() > size
            ? cursors.encode(scope, new KeysetPosition("PLC", shown.get(shown.size() - 1).id()))
            : null;
    return new PolicyPage(shown.stream().map(this::view).toList(), next);
  }

  /**
   * One version.
   *
   * @param reference raw policy reference
   * @return the version
   */
  @Transactional(readOnly = true)
  public PolicyView get(String reference) {
    return view(store.byReference(checked(reference)).orElseThrow(PrivacyPolicyService::notFound));
  }

  /**
   * The append-only history of one version.
   *
   * @param reference raw policy reference
   * @return the history
   */
  @Transactional(readOnly = true)
  public PolicyHistory history(String reference) {
    PolicyRow row =
        store.byReference(checked(reference)).orElseThrow(PrivacyPolicyService::notFound);
    List<PolicyEvent> events =
        store.events(row.id()).stream()
            .map(e -> new PolicyEvent(e.sequence(), e.fromState(), e.toState(), e.occurredAt()))
            .toList();
    return new PolicyHistory(row.reference(), events);
  }

  // ------------------------------------------------------------------------------------------

  private PolicyView view(UUID id) {
    return view(store.byId(id).orElseThrow(PrivacyPolicyService::notFound));
  }

  private PolicyView view(PolicyRow row) {
    return new PolicyView(
        row.reference(),
        row.countryCode(),
        row.state(),
        row.version(),
        row.wordingVersion(),
        row.sourceReference(),
        row.reviewDate(),
        row.effectiveFrom(),
        row.effectiveTo(),
        store.rules(row.id()),
        store.checklist(row.id()),
        row.createdAt(),
        row.updatedAt());
  }

  private PolicyRow lock(String reference) {
    return store.lockByReference(checked(reference)).orElseThrow(PrivacyPolicyService::notFound);
  }

  private static String checked(String raw) {
    if (raw == null || !PrivacyCommands.POLICY.matcher(raw).matches()) {
      throw notFound();
    }
    return raw;
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.PRIVACY_POLICY_NOT_FOUND, Map.of());
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.PRIVACY_POLICY_INVALID, Map.of());
  }

  private static ApiException translate(ApiException refused) {
    if (refused.code() == ErrorCode.IDEMPOTENCY_KEY_REUSED) {
      return new ApiException(ErrorCode.PRIVACY_IDEMPOTENCY_REUSED, Map.of());
    }
    return refused;
  }
}
