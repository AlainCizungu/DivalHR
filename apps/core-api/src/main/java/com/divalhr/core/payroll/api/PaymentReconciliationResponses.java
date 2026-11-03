package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PaymentSubmissionResponses.ProviderView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Responses of payment reconciliations (MVP-095C, ADR 0038). Restricted financial data: no
 * destination, holder, employee identifier, amount, signature, provider text or raw payment request
 * ID is ever returned (only a short fingerprint). {@code CONFIRMED_ACCEPTED_FOR_PROCESSING} means
 * the provider's authenticated answer says it accepted the request for processing; it never means
 * an employee was paid or that funds settled.
 */
public final class PaymentReconciliationResponses {

  private PaymentReconciliationResponses() {}

  /**
   * The latest conclusion of an earlier investigation.
   *
   * @param state reconciliation state
   * @param outcomeCode allow-listed outcome code, or {@code null}
   * @param updatedAt last change
   */
  @Schema(name = "PaymentReconciliationConclusion")
  public record Conclusion(String state, String outcomeCode, Instant updatedAt) {}

  /**
   * What an investigation would ask.
   *
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param submissionId submission
   * @param submissionState submission state
   * @param submissionVersion submission version
   * @param submissionOutcomeCode submission outcome code, or {@code null}
   * @param provider the provider, or {@code null} when none is available
   * @param requestFingerprint 12 hex characters derived from the payment request ID
   * @param previous the earlier investigation's conclusion, or {@code null}
   * @param status {@code READY} or {@code BLOCKED}
   * @param blockers stable blocker codes
   * @param previewDigest digest to send back, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   */
  @Schema(name = "PaymentReconciliationPreview")
  public record Preview(
      UUID legalEntityId,
      UUID batchId,
      UUID submissionId,
      String submissionState,
      int submissionVersion,
      String submissionOutcomeCode,
      ProviderView provider,
      String requestFingerprint,
      Conclusion previous,
      String status,
      List<String> blockers,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the list. */
    public Preview {
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * The receipt of an opened investigation (amount-free).
   *
   * @param reconciliationId reconciliation
   * @param submissionId submission
   * @param batchId batch
   * @param legalEntityId legal entity
   * @param providerCode provider profile code
   * @param state always {@code OPEN}
   * @param version reconciliation version
   * @param createdAt creation instant
   */
  @Schema(name = "PaymentReconciliationReceipt")
  public record Receipt(
      UUID reconciliationId,
      UUID submissionId,
      UUID batchId,
      UUID legalEntityId,
      String providerCode,
      String state,
      int version,
      Instant createdAt) {}

  /**
   * The current state of a submission's investigation.
   *
   * @param id reconciliation
   * @param submissionId submission
   * @param batchId batch
   * @param legalEntityId legal entity
   * @param provider the provider
   * @param state closed state
   * @param version reconciliation version
   * @param submissionState the submission's own (unchanged) state
   * @param requestFingerprint 12 hex characters derived from the payment request ID
   * @param attemptCount inquiry attempts started
   * @param outcomeCode allow-listed outcome code, or {@code null}
   * @param nextAttemptAt when an open investigation is next due, or {@code null}
   * @param createdAt creation instant
   * @param updatedAt last change
   */
  @Schema(name = "PaymentReconciliationView")
  public record View(
      UUID id,
      UUID submissionId,
      UUID batchId,
      UUID legalEntityId,
      ProviderView provider,
      String state,
      int version,
      String submissionState,
      String requestFingerprint,
      int attemptCount,
      String outcomeCode,
      Instant nextAttemptAt,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * One evidence row of an investigation.
   *
   * @param sequence sequence (1 is the opening)
   * @param attemptNumber inquiry attempt number
   * @param state state after the change
   * @param outcomeCode outcome code, or {@code null}
   * @param authenticated whether the provider's answer carried a valid signature
   * @param occurredAt instant
   */
  @Schema(name = "PaymentReconciliationInquiry")
  public record InquiryView(
      int sequence,
      int attemptNumber,
      String state,
      String outcomeCode,
      boolean authenticated,
      Instant occurredAt) {}

  /**
   * A page of evidence rows, newest first.
   *
   * @param items rows
   * @param nextCursor cursor, or {@code null}
   */
  @Schema(name = "PaymentReconciliationInquiryPage")
  public record InquiryPage(List<InquiryView> items, String nextCursor) {

    /** Copies the list. */
    public InquiryPage {
      items = List.copyOf(items);
    }
  }
}
