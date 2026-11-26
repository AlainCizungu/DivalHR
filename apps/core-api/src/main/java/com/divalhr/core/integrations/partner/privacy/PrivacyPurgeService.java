package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.ClassDecision;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PurgeClassResult;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PurgeItemResult;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PurgePreview;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PurgePreviewItem;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PurgeResult;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.Action;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.DataClass;
import com.divalhr.core.integrations.partner.privacy.PrivacyGovernanceStore.PreviewRow;
import com.divalhr.core.integrations.partner.privacy.PrivacyGovernanceStore.ReferralRow;
import com.divalhr.core.integrations.partner.privacy.PrivacyGovernanceStore.StampRule;
import com.divalhr.core.integrations.partner.privacy.PrivacyQuotas.Quota;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Previewed, confirmed minimization and erasure of closed partner-referral data (LAUNCH-001, L9).
 * Nothing is purged by a clock or a background process. A purge is a preview (what is eligible and
 * why not), then an exact-MFA confirmation that revalidates the digest and every eligibility
 * condition under locks (referral rows first, in id order), then the controlled deletion that the
 * database authorizes item by item, then immutable value-free evidence.
 */
@Service
public class PrivacyPurgeService {

  /** Operation names. */
  public static final String PREVIEW = "privacy.purge.preview";

  public static final String COMMIT = "privacy.purge.commit";

  private static final IdempotentOperation.Spec PREVIEW_SPEC =
      new IdempotentOperation.Spec(PREVIEW, "privacy_purge_preview", "preview", "previewed", 200);
  private static final IdempotentOperation.Spec COMMIT_SPEC =
      new IdempotentOperation.Spec(COMMIT, "privacy_purge", "commit", "committed", 200);

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  private static final Set<String> CLOSED = Set.of("DELIVERED", "DELIVERY_UNKNOWN", "WITHDRAWN");

  private final IdempotentOperation operations;
  private final PrivacyGovernanceStore store;
  private final PrivacyRecorder recorder;
  private final PrivacyQuotas quotas;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param store governance storage
   * @param recorder audit and outbox
   * @param quotas privacy quotas
   * @param clock server clock
   */
  public PrivacyPurgeService(
      IdempotentOperation operations,
      PrivacyGovernanceStore store,
      PrivacyRecorder recorder,
      PrivacyQuotas quotas,
      Clock clock) {
    this.operations = operations;
    this.store = store;
    this.recorder = recorder;
    this.quotas = quotas;
    this.clock = clock;
  }

  /** The verified platform administrator. */
  public record Caller(String subject, String correlationId) {}

  private record PreviewCommand(List<String> referrals, Action action) {}

  private record Evaluation(
      ReferralRow referral, Map<DataClass, ClassDecision> classes, List<String> holds) {}

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Previews a minimization or erasure; changes nothing.
   *
   * @param caller verified platform administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the preview
   */
  public IdempotentOperation.Result<PurgePreview> preview(
      Caller caller, String idempotencyKey, JsonNode body) {
    PreviewCommand command =
        operations.validated(
            PREVIEW_SPEC,
            () -> {
              PrivacyCommands.key(idempotencyKey);
              List<Map<String, String>> problems = PrivacyCommands.problems();
              PrivacyCommands.onlyKeys(body, Set.of("referralIds", "action"), problems);
              Action action = PrivacyCommands.enumeration(body, "action", Action.class, problems);
              if (action == Action.HOLD) {
                problems.add(PrivacyCommands.problem("action", "FORMAT"));
              }
              Set<String> referrals = new LinkedHashSet<>();
              JsonNode ids = body.get("referralIds");
              if (ids == null || !ids.isArray()) {
                problems.add(PrivacyCommands.problem("referralIds", "REQUIRED"));
              } else if (ids.size() < 1 || ids.size() > 25) {
                problems.add(PrivacyCommands.problem("referralIds", "LENGTH"));
              } else {
                for (JsonNode id : ids) {
                  if (!id.isString()
                      || !PrivacyCommands.REFERRAL.matcher(id.asString()).matches()) {
                    problems.add(PrivacyCommands.problem("referralIds", "FORMAT"));
                    break;
                  }
                  if (!referrals.add(id.asString())) {
                    problems.add(PrivacyCommands.problem("referralIds", "DUPLICATE"));
                    break;
                  }
                }
              }
              PrivacyCommands.throwIfAny(problems);
              return new PreviewCommand(new ArrayList<>(referrals), action);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("action", command.action().name());
    canonical.put("referrals", String.join(",", command.referrals().stream().sorted().toList()));
    try {
      return operations.execute(
          PREVIEW_SPEC,
          caller.subject(),
          idempotencyKey,
          canonical,
          PurgePreview.class,
          TIMEOUT,
          () -> previewed(caller, command));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<PurgePreview> previewed(
      Caller caller, PreviewCommand command) {
    quotas.require(Quota.PURGE_PREVIEWS, caller.subject());
    Instant now = clock.instant();
    List<ReferralRow> rows = new ArrayList<>();
    for (String reference : command.referrals()) {
      rows.add(
          store
              .referralByReference(reference)
              .orElseThrow(() -> new ApiException(ErrorCode.PRIVACY_REFERRAL_NOT_FOUND, Map.of())));
    }
    rows.sort(Comparator.comparing(ReferralRow::id));
    for (ReferralRow row : rows) {
      store.lockReferral(row.tenantId(), row.id());
    }
    List<Evaluation> evaluations = new ArrayList<>();
    for (ReferralRow row : rows) {
      evaluations.add(evaluate(row, command.action(), now));
    }
    String digest = digest(command.action(), evaluations);
    UUID id = UUID.randomUUID();
    String reference = PrivacyCommands.newReference("PV-");
    store.insertPreview(
        id, reference, "PURGE", caller.subject(), digest, now, command.action().name(), null);
    for (ReferralRow row : rows) {
      store.insertPreviewItem(id, row.tenantId(), row.id());
    }
    List<PurgePreviewItem> items = new ArrayList<>();
    int eligible = 0;
    for (Evaluation e : evaluations) {
      List<ClassDecision> classes = new ArrayList<>(e.classes().values());
      eligible += (int) classes.stream().filter(ClassDecision::eligible).count();
      items.add(new PurgePreviewItem(e.referral().reference(), classes, e.holds()));
    }
    PurgePreview preview =
        new PurgePreview(
            reference, digest, now.plusSeconds(900), command.action().name(), eligible, items);
    return new IdempotentOperation.Completed<>(preview, id, Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Commit
  // ------------------------------------------------------------------------------------------

  /**
   * Confirms a previewed purge.
   *
   * @param caller verified platform administrator (exact MFA)
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the immutable evidence
   */
  public IdempotentOperation.Result<PurgeResult> commit(
      Caller caller, String idempotencyKey, JsonNode body) {
    Map<String, String> command =
        operations.validated(
            COMMIT_SPEC,
            () -> {
              PrivacyCommands.key(idempotencyKey);
              List<Map<String, String>> problems = PrivacyCommands.problems();
              PrivacyCommands.onlyKeys(body, Set.of("previewId", "previewDigest"), problems);
              String preview =
                  PrivacyCommands.pattern(body, "previewId", PrivacyCommands.PREVIEW, problems);
              String digest = PrivacyCommands.pattern(body, "previewDigest", DIGEST, problems);
              PrivacyCommands.throwIfAny(problems);
              return Map.of("previewId", preview, "previewDigest", digest);
            });
    Map<String, Object> canonical = new TreeMap<>(command);
    try {
      return operations.execute(
          COMMIT_SPEC,
          caller.subject(),
          idempotencyKey,
          canonical,
          PurgeResult.class,
          TIMEOUT,
          () -> committed(caller, command.get("previewId"), command.get("previewDigest")));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<PurgeResult> committed(
      Caller caller, String previewReference, String suppliedDigest) {
    quotas.require(Quota.PURGE_COMMITS, caller.subject());
    Instant now = clock.instant();
    PreviewRow preview =
        store
            .lockPreview(previewReference)
            .filter(p -> "PURGE".equals(p.kind()) && p.actor().equals(caller.subject()))
            .orElseThrow(() -> new ApiException(ErrorCode.PRIVACY_PREVIEW_NOT_FOUND, Map.of()));
    if (!"OPEN".equals(preview.state())) {
      throw new ApiException(ErrorCode.PRIVACY_PREVIEW_ALREADY_USED, Map.of());
    }
    if (now.isAfter(preview.expiresAt())) {
      throw new ApiException(ErrorCode.PRIVACY_PREVIEW_EXPIRED, Map.of());
    }
    if (!preview.fingerprint().equals(suppliedDigest)) {
      throw new ApiException(ErrorCode.PRIVACY_PREVIEW_STALE, Map.of());
    }
    Action action = Action.valueOf(preview.action());
    List<ReferralRow> rows = store.previewReferrals(preview.id());
    for (ReferralRow row : rows) {
      store.lockReferral(row.tenantId(), row.id());
    }
    // Revalidate every eligibility condition under the locks; any change since the preview is
    // stale.
    List<Evaluation> evaluations = new ArrayList<>();
    for (ReferralRow row : rows) {
      evaluations.add(evaluate(row, action, now));
    }
    List<Evaluation> byReference =
        evaluations.stream().sorted(Comparator.comparing(e -> e.referral().reference())).toList();
    if (!digest(action, byReference).equals(preview.fingerprint())) {
      throw new ApiException(ErrorCode.PRIVACY_PREVIEW_STALE, Map.of());
    }
    int eligible =
        evaluations.stream()
            .mapToInt(
                e -> (int) e.classes().values().stream().filter(ClassDecision::eligible).count())
            .sum();
    if (eligible == 0) {
      throw new ApiException(ErrorCode.PRIVACY_PURGE_NOTHING_ELIGIBLE, Map.of());
    }
    store.usePreview(preview.id(), now);
    UUID purge = UUID.randomUUID();
    String reference = PrivacyCommands.newReference("PG-");
    store.insertPurge(purge, reference, preview.id(), caller.subject(), now);
    store.purgeContext(purge);
    // Authorize first (the database checks every condition again), then remove, consent last.
    for (Evaluation e : evaluations) {
      for (ClassDecision d : e.classes().values()) {
        if (d.eligible()) {
          store.insertPurgeItem(
              purge, e.referral().tenantId(), e.referral().id(), d.dataClass(), action.name(), now);
        }
      }
    }
    List<PurgeItemResult> items = new ArrayList<>();
    for (Evaluation e : byReference) {
      items.add(new PurgeItemResult(e.referral().reference(), execute(purge, e, action, now)));
    }
    store.completePurge(purge, eligible, now);
    recorder.record(
        caller.subject(),
        "privacy.purge.commit",
        "integrations.privacy-purge.completed.v1",
        PrivacyRecorder.PLATFORM_SCOPE,
        "privacy-purge",
        purge,
        caller.correlationId(),
        Map.of(
            "purgeId",
            purge.toString(),
            "itemCount",
            eligible,
            "state",
            "COMPLETED",
            "actorRole",
            "PLATFORM"),
        now);
    return new IdempotentOperation.Completed<>(
        new PurgeResult(reference, preview.reference(), action.name(), now, items),
        purge,
        Outcome.CREATED);
  }

  /** Removes what the eligible items authorize, in the order the database requires. */
  private List<PurgeClassResult> execute(UUID purge, Evaluation e, Action action, Instant now) {
    UUID tenant = e.referral().tenantId();
    UUID referral = e.referral().id();
    List<PurgeClassResult> results = new ArrayList<>();
    boolean minimize = action == Action.MINIMIZE;
    if (eligible(e, DataClass.PARTNER_REFERRAL_DISCLOSURE)) {
      if (minimize) {
        store.insertResidue(
            purge,
            tenant,
            referral,
            DataClass.PARTNER_REFERRAL_DISCLOSURE.name(),
            "{\"field_count\":" + store.disclosureFieldCount(tenant, referral) + "}",
            now);
      }
      results.add(
          purged(DataClass.PARTNER_REFERRAL_DISCLOSURE, store.deleteDisclosure(tenant, referral)));
    }
    if (eligible(e, DataClass.PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS)) {
      if (minimize) {
        store.insertResidue(
            purge,
            tenant,
            referral,
            DataClass.PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS.name(),
            "{\"attempt_count\":"
                + store.attemptCount(tenant, referral)
                + ",\"acknowledged\":"
                + store.receiptExists(tenant, referral)
                + "}",
            now);
      }
      results.add(
          purged(
              DataClass.PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS,
              store.deleteDiagnostics(tenant, referral)));
    }
    if (eligible(e, DataClass.PARTNER_REFERRAL_CONSENT)) {
      if (minimize) {
        Map<String, String> facts = store.consentFacts(tenant, referral);
        store.insertResidue(
            purge,
            tenant,
            referral,
            DataClass.PARTNER_REFERRAL_CONSENT.name(),
            "{\"consent_version\":\""
                + facts.get("consent_version")
                + "\",\"language\":\""
                + facts.get("language")
                + "\"}",
            now);
      }
      results.add(
          purged(DataClass.PARTNER_REFERRAL_CONSENT, store.deleteConsent(tenant, referral)));
    }
    if (eligible(e, DataClass.PARTNER_REFERRAL_LIFECYCLE_EVIDENCE)) {
      // R227-1: the detailed history is deleted and the header is scrubbed of the employee, the
      // partner and the
      // product. A MINIMIZE keeps only the counts and the two lifecycle timestamps; an ERASE keeps
      // nothing.
      if (minimize) {
        store.insertResidue(
            purge,
            tenant,
            referral,
            DataClass.PARTNER_REFERRAL_LIFECYCLE_EVIDENCE.name(),
            store.lifecycleResidue(tenant, referral),
            now);
      }
      results.add(
          purged(
              DataClass.PARTNER_REFERRAL_LIFECYCLE_EVIDENCE,
              store.deleteLifecycle(tenant, referral)));
    }
    return results;
  }

  private static PurgeClassResult purged(DataClass dataClass, int rows) {
    return new PurgeClassResult(dataClass.name(), "PURGED", rows);
  }

  private static boolean eligible(Evaluation e, DataClass dataClass) {
    ClassDecision d = e.classes().get(dataClass);
    return d != null && d.eligible();
  }

  // ------------------------------------------------------------------------------------------
  // Eligibility
  // ------------------------------------------------------------------------------------------

  private Evaluation evaluate(ReferralRow row, Action action, Instant now) {
    String stamp = store.stampStatus(row.tenantId(), row.id());
    Map<String, StampRule> rules = store.stampRules(row.tenantId(), row.id());
    Set<String> purged = store.purgedClasses(row.tenantId(), row.id());
    Instant closed = store.closedAt(row.tenantId(), row.id());
    List<String> holds = store.activeHolds(row.tenantId(), row.id());
    Map<DataClass, ClassDecision> decisions = new EnumMap<>(DataClass.class);
    for (DataClass dataClass : DataClass.values()) {
      String refusal =
          refusal(
              row,
              dataClass,
              action,
              stamp,
              rules.get(dataClass.name()),
              purged,
              closed,
              holds,
              now);
      decisions.put(dataClass, new ClassDecision(dataClass.name(), refusal == null, refusal));
    }
    // The consent goes last: its disclosure and delivery evidence must be gone or go with it.
    ClassDecision consent = decisions.get(DataClass.PARTNER_REFERRAL_CONSENT);
    if (consent.eligible()
        && !(goneOrGoing(decisions, purged, DataClass.PARTNER_REFERRAL_DISCLOSURE)
            && goneOrGoing(decisions, purged, DataClass.PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS))) {
      decisions.put(
          DataClass.PARTNER_REFERRAL_CONSENT,
          new ClassDecision(
              DataClass.PARTNER_REFERRAL_CONSENT.name(), false, "ACTION_NOT_PERMITTED"));
    }
    // The lifecycle evidence goes very last: every other class must be gone or go with it, so that
    // no disclosure,
    // consent or delivery evidence outlives the link that lets the employee reach it.
    ClassDecision lifecycle = decisions.get(DataClass.PARTNER_REFERRAL_LIFECYCLE_EVIDENCE);
    if (lifecycle.eligible()
        && !(goneOrGoing(decisions, purged, DataClass.PARTNER_REFERRAL_DISCLOSURE)
            && goneOrGoing(decisions, purged, DataClass.PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS)
            && goneOrGoing(decisions, purged, DataClass.PARTNER_REFERRAL_CONSENT))) {
      decisions.put(
          DataClass.PARTNER_REFERRAL_LIFECYCLE_EVIDENCE,
          new ClassDecision(
              DataClass.PARTNER_REFERRAL_LIFECYCLE_EVIDENCE.name(), false, "ACTION_NOT_PERMITTED"));
    }
    return new Evaluation(row, decisions, holds);
  }

  private static boolean goneOrGoing(
      Map<DataClass, ClassDecision> decisions, Set<String> purged, DataClass dataClass) {
    return purged.contains(dataClass.name()) || decisions.get(dataClass).eligible();
  }

  private static String refusal(
      ReferralRow row,
      DataClass dataClass,
      Action action,
      String stamp,
      StampRule rule,
      Set<String> purged,
      Instant closed,
      List<String> holds,
      Instant now) {
    if (!"STAMPED".equals(stamp) || rule == null) {
      return "POLICY_PENDING";
    }
    if (purged.contains(dataClass.name())) {
      return "ALREADY_PURGED";
    }
    if (!CLOSED.contains(row.state())) {
      return "NOT_CLOSED";
    }
    if (!holds.isEmpty()) {
      return "HOLD_ACTIVE";
    }
    if ("HOLD".equals(rule.action()) || !rule.action().equals(action.name())) {
      return "ACTION_NOT_PERMITTED";
    }
    Instant base = "REFERRAL_CREATED".equals(rule.trigger()) ? row.createdAt() : closed;
    if (base == null || base.plus(Duration.ofDays(rule.days())).isAfter(now)) {
      return "NOT_YET_ELIGIBLE";
    }
    return null;
  }

  private static String digest(Action action, List<Evaluation> evaluations) {
    List<Evaluation> sorted =
        evaluations.stream().sorted(Comparator.comparing(e -> e.referral().reference())).toList();
    StringBuilder text = new StringBuilder("purge-v1|").append(action.name());
    for (Evaluation e : sorted) {
      text.append('|').append(e.referral().reference());
      Map<String, String> classes = new LinkedHashMap<>();
      e.classes().forEach((k, v) -> classes.put(k.name(), v.eligible() ? "ELIGIBLE" : v.refusal()));
      classes.forEach((k, v) -> text.append(';').append(k).append('=').append(v));
      text.append(";holds=").append(String.join(",", e.holds()));
    }
    return Fingerprints.sha256(text.toString());
  }

  private static ApiException translate(ApiException refused) {
    if (refused.code() == ErrorCode.IDEMPOTENCY_KEY_REUSED) {
      return new ApiException(ErrorCode.PRIVACY_IDEMPOTENCY_REUSED, Map.of());
    }
    return refused;
  }
}
