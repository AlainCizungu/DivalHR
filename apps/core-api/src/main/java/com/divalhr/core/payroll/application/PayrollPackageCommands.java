package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPayrollApprovalRequest;
import com.divalhr.core.payroll.api.CommitPayrollVoidRequest;
import com.divalhr.core.payroll.api.PayrollApprovalPreviewRequest;
import com.divalhr.core.payroll.api.PayrollVoidCommandRequest;
import com.divalhr.core.payroll.domain.PackageDigests;
import com.divalhr.core.payroll.domain.PayrollVoidReason;
import com.divalhr.core.payroll.domain.PayslipLocale;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation of the payroll approval, void and payslip requests (MVP-094C): shapes first (one field
 * error per problem, every unknown property named), then the stable domain errors the services
 * raise under the locks. Malformed path identifiers are the same safe 404 as unknown ones. The void
 * explanation is restricted: it is never echoed in an error, a field name or a canonical map (only
 * its SHA-256 enters the idempotency fingerprint). Restricted financial and HR data: no record here
 * prints a digest or an explanation.
 */
final class PayrollPackageCommands {

  /** Largest page of packages. */
  static final int MAX_PACKAGE_LIMIT = 100;

  /** Default page of packages. */
  static final int DEFAULT_PACKAGE_LIMIT = 25;

  /** Largest page of payslips of a package. */
  static final int MAX_SLIP_LIMIT = 200;

  /** Default page of payslips of a package. */
  static final int DEFAULT_SLIP_LIMIT = 50;

  /** Most versions of a package chain. */
  static final int MAX_VERSION = 25;

  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

  private PayrollPackageCommands() {}

  /**
   * A well-formed approval commit.
   *
   * @param expectedVersion the chain version the preview showed
   * @param sourceRunDigest the source-run digest the preview returned
   * @param calculationDigest the calculation digest the preview returned
   * @param profileDigest the profile digest the preview returned
   * @param rulePackDigest the rule-pack digest the preview returned
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record ApprovalCommit(
      int expectedVersion,
      String sourceRunDigest,
      String calculationDigest,
      String profileDigest,
      String rulePackDigest,
      String previewDigest,
      Instant expiresAt) {

    Map<String, Object> canonical(
        TenantId tenant, UUID legalEntityId, UUID runId, UUID calculationId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("runId", runId.toString());
      canonical.put("calculationId", calculationId.toString());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("sourceRunDigest", sourceRunDigest);
      canonical.put("calculationDigest", calculationDigest);
      canonical.put("profileDigest", profileDigest);
      canonical.put("rulePackDigest", rulePackDigest);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("reviewedTotals", true);
      canonical.put("reviewedExceptions", true);
      canonical.put("understandsNoPayment", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "PayrollApprovalCommit[redacted]";
    }
  }

  /**
   * A well-formed void draft (the preview body).
   *
   * @param reason the closed reason
   * @param explanation the explanation of OTHER_CONTROLLED, or {@code null}
   */
  record VoidDraft(PayrollVoidReason reason, String explanation) {

    @Override
    public String toString() {
      return "PayrollVoidDraft[" + reason + "]";
    }
  }

  /**
   * A well-formed void commit.
   *
   * @param draft the reason and explanation
   * @param packageVersion the package version the preview showed
   * @param approvalDigest the approval digest the preview returned
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record VoidCommit(
      VoidDraft draft,
      int packageVersion,
      String approvalDigest,
      String previewDigest,
      Instant expiresAt) {

    Map<String, Object> canonical(TenantId tenant, UUID legalEntityId, UUID packageId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("packageId", packageId.toString());
      canonical.put("reasonCode", draft.reason().name());
      canonical.put(
          "explanationSha256",
          draft.explanation() == null ? "" : PackageDigests.sha256(draft.explanation()));
      canonical.put("packageVersion", packageVersion);
      canonical.put("approvalDigest", approvalDigest);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("noPaymentAcknowledged", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "PayrollVoidCommit[redacted]";
    }
  }

  // ------------------------------------------------------------------------------------------
  // Entry points
  // ------------------------------------------------------------------------------------------

  static UUID packageId(String raw) {
    return PayrollCommands.id(raw, ErrorCode.PAYROLL_PACKAGE_NOT_FOUND);
  }

  static UUID payslipId(String raw) {
    return PayrollCommands.id(raw, ErrorCode.PAYSLIP_NOT_FOUND);
  }

  /**
   * The package status filter of a list.
   *
   * @param raw the raw value, or {@code null}
   * @param errors collector
   * @return {@code APPROVED}, {@code VOIDED} or {@code null} for no filter
   */
  static String statusFilter(String raw, FieldErrors errors) {
    if (raw == null) {
      return null;
    }
    if ("APPROVED".equals(raw) || "VOIDED".equals(raw)) {
      return raw;
    }
    errors.add("status", Constraint.FORMAT);
    return null;
  }

  /**
   * The wording locale of a payslip read.
   *
   * @param locale the {@code locale} query value, or {@code null}
   * @param acceptLanguage the {@code Accept-Language} header, or {@code null}
   * @return the locale: the query value when given, else the first supported language of the
   *     header, else English
   * @throws ApiException {@code LOCALE_NOT_SUPPORTED} (400) for any other query value
   */
  static PayslipLocale locale(String locale, String acceptLanguage) {
    if (locale != null) {
      Optional<PayslipLocale> parsed = PayslipLocale.parse(locale);
      if (parsed.isEmpty()) {
        throw new ApiException(ErrorCode.LOCALE_NOT_SUPPORTED, Map.of());
      }
      return parsed.get();
    }
    return PayslipLocale.fromAcceptLanguage(acceptLanguage);
  }

  static void approvalCommand(PayrollApprovalPreviewRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
    } else if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    errors.throwIfAny();
  }

  static ApprovalCommit approvalCommit(
      String idempotencyKey, CommitPayrollApprovalRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Integer version = version(request.getExpectedVersion(), "expectedVersion", 0, errors);
    String source = digest(request.getSourceRunDigest(), "sourceRunDigest", errors);
    String calculation = digest(request.getCalculationDigest(), "calculationDigest", errors);
    String profiles = digest(request.getProfileDigest(), "profileDigest", errors);
    String rulePack = digest(request.getRulePackDigest(), "rulePackDigest", errors);
    String preview = digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = instant(request.getExpiresAt(), "expiresAt", errors);
    confirmed(request.getReviewedTotals(), "reviewedTotals", errors);
    confirmed(request.getReviewedExceptions(), "reviewedExceptions", errors);
    confirmed(request.getUnderstandsNoPayment(), "understandsNoPayment", errors);
    errors.throwIfAny();
    return new ApprovalCommit(version, source, calculation, profiles, rulePack, preview, expiresAt);
  }

  static VoidDraft voidDraft(PayrollVoidCommandRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    VoidDraft draft = draft(request.getReasonCode(), request.getExplanation(), errors);
    errors.throwIfAny();
    return draft;
  }

  static VoidCommit voidCommit(String idempotencyKey, CommitPayrollVoidRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    VoidDraft draft = draft(request.getReasonCode(), request.getExplanation(), errors);
    Integer version = version(request.getPackageVersion(), "packageVersion", 1, errors);
    String approval = digest(request.getApprovalDigest(), "approvalDigest", errors);
    String preview = digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = instant(request.getExpiresAt(), "expiresAt", errors);
    confirmed(request.getNoPaymentAcknowledged(), "noPaymentAcknowledged", errors);
    errors.throwIfAny();
    return new VoidCommit(draft, version, approval, preview, expiresAt);
  }

  // ------------------------------------------------------------------------------------------
  // Parts
  // ------------------------------------------------------------------------------------------

  private static VoidDraft draft(Object rawReason, Object rawExplanation, FieldErrors errors) {
    PayrollVoidReason reason = null;
    if (rawReason == null) {
      errors.add("reasonCode", Constraint.REQUIRED);
    } else if (rawReason instanceof String text
        && text.length() <= 40
        && PayrollVoidReason.parse(text).isPresent()) {
      reason = PayrollVoidReason.parse(text).get();
    } else {
      errors.add("reasonCode", Constraint.FORMAT);
    }
    String explanation = null;
    boolean shaped = true;
    if (rawExplanation instanceof String text) {
      explanation = text;
    } else if (rawExplanation != null) {
      errors.add("explanation", Constraint.FORMAT);
      shaped = false;
    }
    if (reason != null && shaped && !reason.accepts(explanation)) {
      if (explanation == null) {
        errors.add("explanation", Constraint.REQUIRED);
      } else if (!reason.requiresExplanation()) {
        errors.add("explanation", Constraint.FORMAT);
      } else {
        int length = explanation.codePointCount(0, explanation.length());
        boolean ranged =
            length >= PayrollVoidReason.MIN_EXPLANATION
                && length <= PayrollVoidReason.MAX_EXPLANATION;
        errors.add("explanation", ranged ? Constraint.FORMAT : Constraint.LENGTH);
      }
    }
    if (reason == null || !shaped) {
      return null;
    }
    return new VoidDraft(reason, explanation);
  }

  private static Instant instant(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text && text.length() <= 40) {
      try {
        return Instant.parse(text);
      } catch (DateTimeParseException invalid) {
        errors.add(field, Constraint.FORMAT);
        return null;
      }
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

  private static Integer version(Object raw, String field, int min, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof Integer || raw instanceof Long) {
      long value = ((Number) raw).longValue();
      if (value >= min && value <= MAX_VERSION) {
        return (int) value;
      }
      errors.add(field, Constraint.RANGE);
      return null;
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

  private static String digest(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !DIGEST.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return text;
  }

  private static void confirmed(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
    } else if (!(raw instanceof Boolean value)) {
      errors.add(field, Constraint.FORMAT);
    } else if (!value) {
      errors.add(field, Constraint.REQUIRED);
    }
  }

  private static void key(String idempotencyKey, FieldErrors errors) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }
}
