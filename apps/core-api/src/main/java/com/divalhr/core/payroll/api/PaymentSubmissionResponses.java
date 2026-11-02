package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PaymentResponses.MethodCounts;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Responses of provider submissions (MVP-095B, ADR 0037). Restricted financial and HR data: no
 * destination, holder, employee identifier or signature is ever returned; amounts are integer
 * minor units next to the currency code. {@code ACCEPTED_FOR_PROCESSING} means a provider accepted
 * the instructions for processing; it never means an employee was paid or that funds settled.
 */
public final class PaymentSubmissionResponses {

  private PaymentSubmissionResponses() {}

  /**
   * The controlled provider a submission would go to.
   *
   * @param code profile code
   * @param name display label
   * @param synthetic {@code true} for the synthetic test provider (no real money moves)
   */
  @Schema(name = "PaymentSubmissionProvider")
  public record ProviderView(String code, String name, boolean synthetic) {}

  /**
   * What submitting a prepared batch would send.
   *
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param batchVersion batch version
   * @param batchDigest batch digest
   * @param provider the provider, or {@code null} when none is available
   * @param status {@code READY} or {@code BLOCKED}
   * @param blockers stable blocker codes
   * @param currency currency
   * @param lineCount lines
   * @param methodCounts lines by method
   * @param totalNetMinor total net in minor units
   * @param previewDigest digest to send back, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   */
  @Schema(name = "PaymentSubmissionPreview")
  public record SubmissionPreview(
      UUID legalEntityId,
      UUID batchId,
      int batchVersion,
      String batchDigest,
      ProviderView provider,
      String status,
      List<String> blockers,
      String currency,
      int lineCount,
      MethodCounts methodCounts,
      long totalNetMinor,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the list. */
    public SubmissionPreview {
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * The receipt of a created submission (amount-free).
   *
   * @param submissionId submission
   * @param batchId batch
   * @param legalEntityId legal entity
   * @param providerCode provider profile code
   * @param state always {@code QUEUED}
   * @param version submission version
   * @param createdAt creation instant
   */
  @Schema(name = "PaymentSubmissionReceipt")
  public record SubmissionReceipt(
      UUID submissionId,
      UUID batchId,
      UUID legalEntityId,
      String providerCode,
      String state,
      int version,
      Instant createdAt) {}

  /**
   * The current state of a batch's submission.
   *
   * @param id submission
   * @param batchId batch
   * @param legalEntityId legal entity
   * @param provider the provider
   * @param state closed state
   * @param version submission version
   * @param batchVersion batch version submitted
   * @param currency currency
   * @param lineCount lines
   * @param methodCounts lines by method
   * @param totalNetMinor total net in minor units
   * @param attemptCount attempts started
   * @param outcomeCode allow-listed outcome code, or {@code null}
   * @param providerReference bounded provider reference, or {@code null}
   * @param nextAttemptAt when a queued submission is next due, or {@code null}
   * @param createdAt creation instant
   * @param updatedAt last change
   */
  @Schema(name = "PaymentSubmissionView")
  public record SubmissionView(
      UUID id,
      UUID batchId,
      UUID legalEntityId,
      ProviderView provider,
      String state,
      int version,
      int batchVersion,
      String currency,
      int lineCount,
      MethodCounts methodCounts,
      long totalNetMinor,
      int attemptCount,
      String outcomeCode,
      String providerReference,
      Instant nextAttemptAt,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * One evidence row of a submission.
   *
   * @param sequence sequence (1 is the creation)
   * @param attemptNumber attempt number
   * @param state state after the change
   * @param outcomeCode outcome code, or {@code null}
   * @param providerReference bounded provider reference, or {@code null}
   * @param occurredAt instant
   */
  @Schema(name = "PaymentSubmissionAttempt")
  public record AttemptView(
      int sequence,
      int attemptNumber,
      String state,
      String outcomeCode,
      String providerReference,
      Instant occurredAt) {}

  /**
   * A page of evidence rows, newest first.
   *
   * @param items rows
   * @param nextCursor cursor, or {@code null}
   */
  @Schema(name = "PaymentSubmissionAttemptPage")
  public record AttemptPage(List<AttemptView> items, String nextCursor) {

    /** Copies the list. */
    public AttemptPage {
      items = List.copyOf(items);
    }
  }
}
