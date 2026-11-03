package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPaymentReconciliationRequest;
import com.divalhr.core.payroll.domain.PaymentSubmissionState;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Parsing of payment reconciliation commands (MVP-095C). Every raw value is validated here, before
 * any state is read; a wrong type, a missing field or an unknown property is a field error that
 * never echoes the value.
 */
final class PaymentReconciliationCommands {

  /** Default page of the inquiry history. */
  static final int DEFAULT_INQUIRY_LIMIT = 25;

  /** Largest page of the inquiry history. */
  static final int MAX_INQUIRY_LIMIT = 100;

  private PaymentReconciliationCommands() {}

  /**
   * A well-formed investigation commit.
   *
   * @param submissionVersion the submission version the preview showed
   * @param submissionState the submission state the preview showed
   * @param previewDigest the preview digest the preview returned
   * @param expiresAt the expiry the preview returned
   */
  record Commit(
      int submissionVersion,
      PaymentSubmissionState submissionState,
      String previewDigest,
      Instant expiresAt) {

    Map<String, Object> canonical(UUID tenant, UUID legalEntityId, UUID batchId) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("batchId", batchId.toString());
      canonical.put("expectedSubmissionVersion", submissionVersion);
      canonical.put("expectedSubmissionState", submissionState.name());
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("understandInquiryDoesNotResend", true);
      canonical.put("understandAcceptedIsNotPaid", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "PaymentReconciliationCommit[redacted]";
    }
  }

  /** One safe 404 for every unknown, malformed, foreign or undisclosable identifier. */
  static UUID id(String raw) {
    return PayrollCommands.id(raw, ErrorCode.PAYMENT_RECONCILIATION_NOT_FOUND);
  }

  static Commit commit(String idempotencyKey, CommitPaymentReconciliationRequest request) {
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
            request.getExpectedSubmissionVersion(), "expectedSubmissionVersion", 1, errors);
    PaymentSubmissionState state = eligibleState(request.getExpectedSubmissionState(), errors);
    String preview =
        PayrollPackageCommands.digest(request.getPreviewDigest(), "previewDigest", errors);
    Instant expiresAt = PayrollPackageCommands.instant(request.getExpiresAt(), "expiresAt", errors);
    PayrollPackageCommands.confirmed(
        request.getUnderstandInquiryDoesNotResend(), "understandInquiryDoesNotResend", errors);
    PayrollPackageCommands.confirmed(
        request.getUnderstandAcceptedIsNotPaid(), "understandAcceptedIsNotPaid", errors);
    errors.throwIfAny();
    return new Commit(version, state, preview, expiresAt);
  }

  private static PaymentSubmissionState eligibleState(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("expectedSubmissionState", Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text) {
      PaymentSubmissionState state = PaymentSubmissionState.parse(text).orElse(null);
      if (state == PaymentSubmissionState.DELIVERY_UNKNOWN
          || state == PaymentSubmissionState.MANUAL_INTERVENTION) {
        return state;
      }
    }
    errors.add("expectedSubmissionState", Constraint.FORMAT);
    return null;
  }

  /**
   * The page size of the inquiry history.
   *
   * @param raw raw value, or {@code null}
   * @param errors collector
   * @return the size
   */
  static int inquiryLimit(String raw, FieldErrors errors) {
    return PayrollCommands.limit(raw, DEFAULT_INQUIRY_LIMIT, MAX_INQUIRY_LIMIT, errors);
  }

  static ApiException notFound() {
    return new ApiException(ErrorCode.PAYMENT_RECONCILIATION_NOT_FOUND, Map.of());
  }
}
