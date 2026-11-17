package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceHandoffBodies.CommitHandoff;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffBodies.PartnerCallback;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffBodies.VersionedBody;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffReviewState;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Parsing and canonical forms of the MVP-120B commands. Parsing is strict: a wrong type, an unknown
 * property or an unchecked affirmation is a stable answer, and the canonical text that fingerprints
 * an idempotent command never holds a digest or an amount in the clear.
 */
final class SalaryAdvanceHandoffCommands {

  private static final Set<String> CONSENT_KEYS = Set.of("version", "language", "affirmed");
  private static final Set<String> REASONS =
      Set.of("ADDITIONAL_DOCUMENTS", "CLARIFICATION", "PARTNER_POLICY", "SYNTHETIC_TEST_SCENARIO");

  /** Most handoff versions the contract accepts. */
  static final int MAX_VERSION = 400;

  private SalaryAdvanceHandoffCommands() {}

  /**
   * A well-formed authorization.
   *
   * @param expectedRequestVersion the request version the employee saw
   * @param previewDigest the digest the preview returned
   * @param expiresAt the expiry the preview returned
   * @param consentVersion the version the employee affirmed
   * @param language {@code en} or {@code fr}
   */
  record Authorization(
      int expectedRequestVersion,
      String previewDigest,
      Instant expiresAt,
      String consentVersion,
      String language) {

    Map<String, Object> canonical(TenantId tenant, UUID requestId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("requestId", requestId.toString());
      canonical.put("expectedRequestVersion", expectedRequestVersion);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("consentVersion", consentVersion);
      canonical.put("language", language);
      canonical.put("affirmed", true);
      canonical.put("understandsSyntheticTest", true);
      return canonical;
    }
  }

  /**
   * A well-formed versioned command (withdrawal or reconcile).
   *
   * @param expectedVersion the handoff version the caller saw
   */
  record Versioned(int expectedVersion) {

    Map<String, Object> canonical(TenantId tenant, String target, UUID id) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put(target, id.toString());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("confirmed", true);
      return canonical;
    }
  }

  /**
   * A well-formed partner callback.
   *
   * @param applicationId the opaque application id
   * @param reviewState the reported review state
   * @param reasonCode the closed reason, or {@code null}
   * @param issuedAt the partner's issue instant
   */
  record Callback(
      UUID applicationId,
      SalaryAdvanceHandoffReviewState reviewState,
      String reasonCode,
      Instant issuedAt) {}

  static Authorization authorization(String idempotencyKey, CommitHandoff body) {
    FieldErrors errors = new FieldErrors();
    SalaryAdvanceCommands.key(idempotencyKey, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Integer version =
        SalaryAdvanceCommands.integer(
            body.getExpectedRequestVersion(), "expectedRequestVersion", 1, 3, errors);
    String digest = SalaryAdvanceCommands.digest(body.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = SalaryAdvanceCommands.instant(body.getExpiresAt(), "expiresAt", errors);
    String consentVersion = null;
    String language = null;
    boolean affirmed = false;
    Map<String, Object> consent =
        SalaryAdvanceCommands.object(body.getConsent(), "consent", CONSENT_KEYS, errors);
    if (consent != null) {
      consentVersion =
          SalaryAdvanceCommands.text(
              consent.get("version"), "consent.version", SalaryAdvanceCommands.VERSION, errors);
      language = SalaryAdvanceCommands.language(consent.get("language"), errors);
      Object raw = consent.get("affirmed");
      if (raw == null) {
        errors.add("consent.affirmed", Constraint.REQUIRED);
      } else if (!(raw instanceof Boolean value)) {
        errors.add("consent.affirmed", Constraint.FORMAT);
      } else {
        affirmed = value;
      }
    }
    Boolean understands =
        SalaryAdvanceCommands.bool(
            body.getUnderstandsSyntheticTest(), "understandsSyntheticTest", errors);
    errors.throwIfAny();
    if (!affirmed || !Boolean.TRUE.equals(understands)) {
      // The affirmations are explicit and never pre-checked: their absence is a stable answer of
      // its own.
      throw new ApiException(ErrorCode.SALARY_ADVANCE_HANDOFF_CONSENT_REQUIRED, Map.of());
    }
    return new Authorization(version, digest, expiresAt, consentVersion, language);
  }

  static Versioned versioned(String idempotencyKey, VersionedBody body) {
    FieldErrors errors = new FieldErrors();
    SalaryAdvanceCommands.key(idempotencyKey, errors);
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Integer version =
        SalaryAdvanceCommands.integer(
            body.getExpectedVersion(), "expectedVersion", 1, MAX_VERSION, errors);
    SalaryAdvanceCommands.confirmed(body.getConfirmed(), "confirmed", errors);
    errors.throwIfAny();
    return new Versioned(version);
  }

  /**
   * Parses a callback body. Every failure is the one generic invalid-callback answer: the caller
   * learns nothing about which part was wrong.
   *
   * @param body the body
   * @return the callback
   */
  static Callback callback(PartnerCallback body) {
    if (body == null || !body.unknownProperties().isEmpty()) {
      throw invalid();
    }
    FieldErrors errors = new FieldErrors();
    UUID application = null;
    if (body.getApplicationId() instanceof String text) {
      try {
        application = UUID.fromString(text);
      } catch (IllegalArgumentException malformed) {
        errors.add("applicationId", Constraint.FORMAT);
      }
    } else {
      errors.add("applicationId", Constraint.FORMAT);
    }
    SalaryAdvanceHandoffReviewState state = null;
    if (body.getReviewState() instanceof String text) {
      state = SalaryAdvanceHandoffReviewState.parse(text).orElse(null);
    }
    if (state == null || state == SalaryAdvanceHandoffReviewState.RECEIVED) {
      errors.add("reviewState", Constraint.FORMAT);
    }
    String reason = null;
    if (body.getReasonCode() != null) {
      if (body.getReasonCode() instanceof String text && REASONS.contains(text)) {
        reason = text;
      } else {
        errors.add("reasonCode", Constraint.FORMAT);
      }
    }
    Instant issuedAt = SalaryAdvanceCommands.instant(body.getIssuedAt(), "issuedAt", errors);
    boolean reasonShape =
        state == SalaryAdvanceHandoffReviewState.UNDER_REVIEW ? reason == null : reason != null;
    if (!errors.isEmpty() || !reasonShape) {
      throw invalid();
    }
    return new Callback(application, state, reason, issuedAt);
  }

  /**
   * The one generic callback failure.
   *
   * @return the exception (HTTP 400, no detail)
   */
  static ApiException invalid() {
    return new ApiException(ErrorCode.PARTNER_CALLBACK_INVALID, Map.of());
  }
}
