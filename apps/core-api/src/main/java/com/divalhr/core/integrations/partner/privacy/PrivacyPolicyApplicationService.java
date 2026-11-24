package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.ApplicationItem;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.ApplicationPreview;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.ApplicationResult;
import com.divalhr.core.integrations.partner.privacy.PrivacyGovernanceStore.ActivePolicy;
import com.divalhr.core.integrations.partner.privacy.PrivacyGovernanceStore.PreviewRow;
import com.divalhr.core.integrations.partner.privacy.PrivacyGovernanceStore.ReferralRow;
import com.divalhr.core.integrations.partner.privacy.PrivacyQuotas.Quota;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Assigns an ACTIVE policy version to referrals that are still POLICY_PENDING (LAUNCH-001, L5). A
 * preview counts, per country, the pending referrals an ACTIVE version now covers; the confirmation
 * revalidates it under locks and stamps each still-pending referral with the version and its rules.
 * A referral that already carries a stamp is never touched: a stamped decision is never shortened
 * or rewritten.
 */
@Service
public class PrivacyPolicyApplicationService {

  /** Operation names. */
  public static final String PREVIEW = "privacy.policy-application.preview";

  public static final String COMMIT = "privacy.policy-application.commit";

  private static final IdempotentOperation.Spec PREVIEW_SPEC =
      new IdempotentOperation.Spec(
          PREVIEW, "privacy_policy_application_preview", "preview", "previewed", 200);
  private static final IdempotentOperation.Spec COMMIT_SPEC =
      new IdempotentOperation.Spec(
          COMMIT, "privacy_policy_application", "commit", "committed", 200);

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

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
  public PrivacyPolicyApplicationService(
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

  private record Coverage(ActivePolicy policy, int pending) {}

  /**
   * Previews an application; changes nothing but the preview record.
   *
   * @param caller verified platform administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the preview
   */
  public IdempotentOperation.Result<ApplicationPreview> preview(
      Caller caller, String idempotencyKey, JsonNode body) {
    String country =
        operations.validated(
            PREVIEW_SPEC,
            () -> {
              PrivacyCommands.key(idempotencyKey);
              List<Map<String, String>> problems = PrivacyCommands.problems();
              PrivacyCommands.onlyKeys(body, Set.of("countryCode"), problems);
              String value = PrivacyCommands.pattern(body, "countryCode", COUNTRY, problems);
              PrivacyCommands.throwIfAny(problems);
              return value;
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("country", country);
    try {
      return operations.execute(
          PREVIEW_SPEC,
          caller.subject(),
          idempotencyKey,
          canonical,
          ApplicationPreview.class,
          TIMEOUT,
          () -> previewed(caller, country));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<ApplicationPreview> previewed(
      Caller caller, String country) {
    quotas.require(Quota.PLATFORM_MUTATIONS, caller.subject());
    Instant now = clock.instant();
    List<Coverage> coverage = coverage(country, now, false);
    String digest = digest(country, coverage);
    UUID id = UUID.randomUUID();
    String reference = PrivacyCommands.newReference("PV-");
    store.insertPreview(
        id, reference, "POLICY_APPLICATION", caller.subject(), digest, now, null, country);
    List<ApplicationItem> items = new ArrayList<>();
    int total = 0;
    for (Coverage c : coverage) {
      total += c.pending();
      items.add(new ApplicationItem(c.policy().country(), c.policy().reference(), c.pending()));
    }
    return new IdempotentOperation.Completed<>(
        new ApplicationPreview(reference, digest, now.plusSeconds(900), total, items),
        id,
        Outcome.CREATED);
  }

  /**
   * Confirms an application.
   *
   * @param caller verified platform administrator (exact MFA)
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the evidence
   */
  public IdempotentOperation.Result<ApplicationResult> commit(
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
    try {
      return operations.execute(
          COMMIT_SPEC,
          caller.subject(),
          idempotencyKey,
          new TreeMap<>(command),
          ApplicationResult.class,
          TIMEOUT,
          () -> committed(caller, command.get("previewId"), command.get("previewDigest")));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<ApplicationResult> committed(
      Caller caller, String previewReference, String suppliedDigest) {
    quotas.require(Quota.PLATFORM_MUTATIONS, caller.subject());
    Instant now = clock.instant();
    PreviewRow preview =
        store
            .lockPreview(previewReference)
            .filter(
                p -> "POLICY_APPLICATION".equals(p.kind()) && p.actor().equals(caller.subject()))
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
    // Revalidate under locks: the pending referrals and the ACTIVE versions must be what was
    // previewed.
    List<Coverage> coverage = coverage(preview.country(), now, true);
    if (!digest(preview.country(), coverage).equals(preview.fingerprint())) {
      throw new ApiException(ErrorCode.PRIVACY_PREVIEW_STALE, Map.of());
    }
    store.usePreview(preview.id(), now);
    int applied = 0;
    String first = null;
    for (Coverage c : coverage) {
      UUID application = UUID.randomUUID();
      String reference = PrivacyCommands.newReference("PM-");
      List<ReferralRow> pending = store.pending(c.policy().country(), true);
      store.insertApplication(
          application,
          reference,
          c.policy().id(),
          preview.id(),
          caller.subject(),
          pending.size(),
          now);
      store.applicationContext(application);
      for (ReferralRow row : pending) {
        store.insertApplicationItem(application, row.tenantId(), row.id());
        store.stamp(row.tenantId(), row.id(), c.policy().id(), now);
      }
      applied += pending.size();
      if (first == null) {
        first = reference;
      }
      recorder.record(
          caller.subject(),
          "privacy.policy-application.commit",
          "integrations.privacy-policy-application.completed.v1",
          PrivacyRecorder.PLATFORM_SCOPE,
          "privacy-policy-application",
          application,
          caller.correlationId(),
          Map.of(
              "applicationId", application.toString(),
              "policyId", c.policy().id().toString(),
              "stampedCount", pending.size(),
              "actorRole", "PLATFORM"),
          now);
    }
    if (first == null) {
      throw new ApiException(ErrorCode.PRIVACY_PREVIEW_STALE, Map.of());
    }
    return new IdempotentOperation.Completed<>(
        new ApplicationResult(first, preview.reference(), applied, now),
        preview.id(),
        Outcome.CREATED);
  }

  private List<Coverage> coverage(String country, Instant now, boolean lock) {
    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    List<Coverage> out = new ArrayList<>();
    for (ActivePolicy policy : store.activePolicies(country, today)) {
      int pending = store.pending(policy.country(), lock).size();
      if (pending > 0) {
        out.add(new Coverage(policy, pending));
      }
    }
    return out;
  }

  private static String digest(String country, List<Coverage> coverage) {
    StringBuilder text =
        new StringBuilder("application-v1|").append(country == null ? "-" : country);
    for (Coverage c : coverage) {
      text.append('|')
          .append(c.policy().country())
          .append(':')
          .append(c.policy().reference())
          .append(':')
          .append(c.pending());
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
