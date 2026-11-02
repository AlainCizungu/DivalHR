package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPaymentSubmissionRequest;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Parsing of provider submission commands (MVP-095B). Every raw value is validated here, before any
 * state is read; a wrong type, a missing field or an unknown property is a field error that never
 * echoes the value.
 */
final class PaymentSubmissionCommands {

  /** Default page of the attempt history. */
  static final int DEFAULT_ATTEMPT_LIMIT = 25;

  /** Largest page of the attempt history. */
  static final int MAX_ATTEMPT_LIMIT = 100;

  private PaymentSubmissionCommands() {}

  /**
   * A well-formed submission commit.
   *
   * @param batchVersion the batch version the preview showed
   * @param batchDigest the batch digest the preview returned
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record Commit(int batchVersion, String batchDigest, String previewDigest, Instant expiresAt) {

    Map<String, Object> canonical(UUID tenant, UUID legalEntityId, UUID batchId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("batchId", batchId.toString());
      canonical.put("expectedBatchVersion", batchVersion);
      canonical.put("batchDigest", batchDigest);
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("reviewedProviderAndTotals", true);
      canonical.put("understandAcceptedIsNotPaid", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "PaymentSubmissionCommit[redacted]";
    }
  }

  static UUID batchId(String raw) {
    return PayrollCommands.id(raw, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
  }

  static Commit commit(String idempotencyKey, CommitPaymentSubmissionRequest request) {
    FieldErrors errors = new FieldErrors();
    PayrollPackageCommands.key(idempotencyKey, errors);
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Integer version =
        PayrollPackageCommands.version(
            request.getExpectedBatchVersion(), "expectedBatchVersion", 1, errors);
    String batch = PayrollPackageCommands.digest(request.getBatchDigest(), "batchDigest", errors);
    String preview =
        PayrollPackageCommands.digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = PayrollPackageCommands.instant(request.getExpiresAt(), "expiresAt", errors);
    PayrollPackageCommands.confirmed(
        request.getReviewedProviderAndTotals(), "reviewedProviderAndTotals", errors);
    PayrollPackageCommands.confirmed(
        request.getUnderstandAcceptedIsNotPaid(), "understandAcceptedIsNotPaid", errors);
    errors.throwIfAny();
    return new Commit(version, batch, preview, expiresAt);
  }

  /**
   * The page size of the attempt history.
   *
   * @param raw raw value, or {@code null}
   * @param errors collector
   * @return the size
   */
  static int attemptLimit(String raw, FieldErrors errors) {
    return PayrollCommands.limit(raw, DEFAULT_ATTEMPT_LIMIT, MAX_ATTEMPT_LIMIT, errors);
  }

  static ApiException notFound() {
    return new ApiException(ErrorCode.PAYMENT_SUBMISSION_NOT_FOUND, Map.of());
  }
}
