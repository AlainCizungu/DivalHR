package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.PartnerCrypto;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.HoldView;
import com.divalhr.core.integrations.partner.privacy.PrivacyDomain.HoldReason;
import com.divalhr.core.integrations.partner.privacy.PrivacyHoldStore.HoldRow;
import com.divalhr.core.integrations.partner.privacy.PrivacyHoldStore.ReferralKey;
import com.divalhr.core.integrations.partner.privacy.PrivacyQuotas.Quota;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Holds on partner referrals (LAUNCH-001, L6). A hold has a closed reason, a review date and an
 * optional external case reference; its operational note is stored encrypted and never returned.
 * While any hold is active, nothing about the referral is minimized or erased. Placing and
 * releasing are append-only and audited by identifier and closed code only.
 */
@Service
public class PrivacyHoldService {

  /** Operation names. */
  public static final String PLACE = "privacy.hold.place";

  public static final String RELEASE = "privacy.hold.release";

  private static final IdempotentOperation.Spec PLACE_SPEC =
      new IdempotentOperation.Spec(PLACE, "privacy_hold", "place", "placed", 201);
  private static final IdempotentOperation.Spec RELEASE_SPEC =
      new IdempotentOperation.Spec(RELEASE, "privacy_hold", "release", "released", 200);

  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final Pattern EXTERNAL = Pattern.compile("^[A-Za-z0-9._/-]{1,80}$");

  private final IdempotentOperation operations;
  private final PrivacyHoldStore store;
  private final PrivacyRecorder recorder;
  private final PrivacyQuotas quotas;
  private final PartnerCrypto crypto;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param store hold storage
   * @param recorder audit and outbox
   * @param quotas privacy quotas
   * @param crypto seals the notes
   * @param clock server clock
   */
  public PrivacyHoldService(
      IdempotentOperation operations,
      PrivacyHoldStore store,
      PrivacyRecorder recorder,
      PrivacyQuotas quotas,
      PartnerCrypto crypto,
      Clock clock) {
    this.operations = operations;
    this.store = store;
    this.recorder = recorder;
    this.quotas = quotas;
    this.crypto = crypto;
    this.clock = clock;
  }

  /** The verified platform administrator. */
  public record Caller(String subject, String correlationId) {}

  private record Place(HoldReason reason, LocalDate reviewDate, String external, String note) {}

  /**
   * Places a hold, or replays an identical earlier placement.
   *
   * @param caller verified platform administrator
   * @param referral raw referral reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the hold
   */
  public IdempotentOperation.Result<HoldView> place(
      Caller caller, String referral, String idempotencyKey, JsonNode body) {
    Place command =
        operations.validated(
            PLACE_SPEC,
            () -> {
              PrivacyCommands.key(idempotencyKey);
              List<Map<String, String>> problems = PrivacyCommands.problems();
              PrivacyCommands.onlyKeys(
                  body,
                  Set.of("reasonCode", "reviewDate", "externalReference", "internalNote"),
                  problems);
              HoldReason reason =
                  PrivacyCommands.enumeration(body, "reasonCode", HoldReason.class, problems);
              LocalDate review = PrivacyCommands.date(body, "reviewDate", false, problems);
              String external = nullableText(body, "externalReference", 80, problems);
              if (external != null && !EXTERNAL.matcher(external).matches()) {
                problems.add(PrivacyCommands.problem("externalReference", "FORMAT"));
              }
              String note = nullableText(body, "internalNote", 1000, problems);
              if (reason == HoldReason.OTHER_DOCUMENTED && note == null) {
                problems.add(PrivacyCommands.problem("internalNote", "REQUIRED"));
              }
              PrivacyCommands.throwIfAny(problems);
              return new Place(reason, review, external, note);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("referral", referral);
    canonical.put("reason", command.reason().name());
    canonical.put("reviewDate", command.reviewDate().toString());
    canonical.put("external", command.external() == null ? "-" : command.external());
    canonical.put("note", command.note() == null ? "-" : command.note());
    try {
      return operations.execute(
          PLACE_SPEC,
          caller.subject(),
          idempotencyKey,
          canonical,
          HoldView.class,
          TIMEOUT,
          () -> placed(caller, referral, command));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<HoldView> placed(
      Caller caller, String referral, Place command) {
    quotas.require(Quota.PLATFORM_MUTATIONS, caller.subject());
    Instant now = clock.instant();
    if (command.reviewDate().isBefore(now.atZone(ZoneOffset.UTC).toLocalDate())) {
      throw PrivacyCommands.fail("reviewDate", "RANGE");
    }
    // The referral row is locked first: a purge item in flight holds the same lock.
    ReferralKey key =
        store
            .lockReferral(referralReference(referral))
            .orElseThrow(() -> new ApiException(ErrorCode.PRIVACY_REFERRAL_NOT_FOUND, Map.of()));
    UUID id = UUID.randomUUID();
    byte[] sealed = null;
    if (command.note() != null) {
      sealed =
          crypto.seal(
              command.note().getBytes(StandardCharsets.UTF_8),
              "privacy-hold-note:" + new TenantId(key.tenantId()) + ':' + id);
    }
    store.insert(
        key,
        id,
        PrivacyCommands.newReference("PH-"),
        command.reason().name(),
        sealed,
        caller.subject(),
        command.reviewDate(),
        command.external(),
        now);
    store.insertEvent(id, 1, "PLACED", now);
    recorder.record(
        caller.subject(),
        "privacy.hold.place",
        "integrations.privacy-hold.placed.v1",
        key.tenantId(),
        "privacy-hold",
        id,
        caller.correlationId(),
        Map.of(
            "holdId", id.toString(),
            "referralId", key.id().toString(),
            "holdReason", command.reason().name(),
            "state", "ACTIVE",
            "actorRole", "PLATFORM"),
        now);
    HoldRow row = store.byId(id).orElseThrow();
    return new IdempotentOperation.Completed<>(
        PlatformPrivacyRequestService.holdView(row), id, Outcome.CREATED);
  }

  /**
   * Releases a hold.
   *
   * @param caller verified platform administrator
   * @param referral raw referral reference
   * @param hold raw hold reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body raw request object
   * @return the hold
   */
  public IdempotentOperation.Result<HoldView> release(
      Caller caller, String referral, String hold, String idempotencyKey, JsonNode body) {
    String note =
        operations.validated(
            RELEASE_SPEC,
            () -> {
              PrivacyCommands.key(idempotencyKey);
              List<Map<String, String>> problems = PrivacyCommands.problems();
              PrivacyCommands.onlyKeys(body, Set.of("internalNote"), problems);
              String value = nullableText(body, "internalNote", 1000, problems);
              PrivacyCommands.throwIfAny(problems);
              return value == null ? "" : value;
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("referral", referral);
    canonical.put("hold", hold);
    canonical.put("note", note.isEmpty() ? "-" : note);
    try {
      return operations.execute(
          RELEASE_SPEC,
          caller.subject(),
          idempotencyKey,
          canonical,
          HoldView.class,
          TIMEOUT,
          () -> released(caller, referral, hold, note));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<HoldView> released(
      Caller caller, String referral, String hold, String note) {
    quotas.require(Quota.PLATFORM_MUTATIONS, caller.subject());
    Instant now = clock.instant();
    ReferralKey key =
        store
            .lockReferral(referralReference(referral))
            .orElseThrow(() -> new ApiException(ErrorCode.PRIVACY_REFERRAL_NOT_FOUND, Map.of()));
    HoldRow row =
        store
            .lockOf(key.id(), holdReference(hold))
            .orElseThrow(() -> new ApiException(ErrorCode.PRIVACY_HOLD_NOT_FOUND, Map.of()));
    if (!"ACTIVE".equals(row.state())) {
      throw new ApiException(ErrorCode.PRIVACY_HOLD_ALREADY_RELEASED, Map.of());
    }
    byte[] sealed = null;
    if (!note.isEmpty()) {
      sealed =
          crypto.seal(
              note.getBytes(StandardCharsets.UTF_8),
              "privacy-hold-release:" + new TenantId(key.tenantId()) + ':' + row.id());
    }
    store.release(row.id(), sealed, caller.subject(), now);
    store.insertEvent(row.id(), 2, "RELEASED", now);
    recorder.record(
        caller.subject(),
        "privacy.hold.release",
        "integrations.privacy-hold.released.v1",
        key.tenantId(),
        "privacy-hold",
        row.id(),
        caller.correlationId(),
        Map.of(
            "holdId", row.id().toString(),
            "referralId", key.id().toString(),
            "holdReason", row.reason(),
            "state", "RELEASED",
            "actorRole", "PLATFORM"),
        now);
    HoldRow updated = store.byId(row.id()).orElseThrow();
    return new IdempotentOperation.Completed<>(
        PlatformPrivacyRequestService.holdView(updated), row.id(), Outcome.UPDATED);
  }

  private static String nullableText(
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

  private static String referralReference(String raw) {
    if (raw == null || !PrivacyCommands.REFERRAL.matcher(raw).matches()) {
      throw new ApiException(ErrorCode.PRIVACY_REFERRAL_NOT_FOUND, Map.of());
    }
    return raw;
  }

  private static String holdReference(String raw) {
    if (raw == null || !PrivacyCommands.HOLD.matcher(raw).matches()) {
      throw new ApiException(ErrorCode.PRIVACY_HOLD_NOT_FOUND, Map.of());
    }
    return raw;
  }

  private static ApiException translate(ApiException refused) {
    if (refused.code() == ErrorCode.IDEMPOTENCY_KEY_REUSED) {
      return new ApiException(ErrorCode.PRIVACY_IDEMPOTENCY_REUSED, Map.of());
    }
    return refused;
  }
}
