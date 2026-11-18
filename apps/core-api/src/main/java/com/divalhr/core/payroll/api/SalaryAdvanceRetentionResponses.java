package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * MVP-120C response bodies of the evidence-retention API. Counts and identifiers of the retention
 * objects only: never a handoff id, an employee, an amount, an attempt detail or a raw payload.
 */
public final class SalaryAdvanceRetentionResponses {

  private SalaryAdvanceRetentionResponses() {}

  /**
   * The organization retention policy. Retention is disabled while {@code configured} is false.
   *
   * @param configured whether a policy exists
   * @param version the latest version, or {@code null}
   * @param retentionDays days, or {@code null}
   * @param effectiveAt when the latest version took effect, or {@code null}
   * @param minimumDays the lowest accepted period
   * @param maximumDays the highest accepted period
   */
  @Schema(name = "SalaryAdvanceRetentionPolicy")
  public record Policy(
      boolean configured,
      Integer version,
      Integer retentionDays,
      Instant effectiveAt,
      int minimumDays,
      int maximumDays) {}

  /**
   * A short-lived preview of the delivery diagnostics a purge would delete (counts only).
   *
   * @param previewId the preview
   * @param policyVersion the policy version it was computed under
   * @param handoffCount handoffs whose diagnostics are eligible
   * @param attemptCount diagnostic records that would be deleted
   * @param digest server-generated digest the confirmation must repeat
   * @param createdAt creation instant
   * @param expiresAt expiry instant
   */
  @Schema(name = "SalaryAdvancePurgePreview")
  public record PurgePreview(
      UUID previewId,
      int policyVersion,
      int handoffCount,
      int attemptCount,
      String digest,
      Instant createdAt,
      Instant expiresAt) {}

  /**
   * The result of a confirmed purge.
   *
   * @param previewId the preview that was confirmed
   * @param handoffCount handoffs whose diagnostics were deleted
   * @param attemptCount diagnostic records deleted
   * @param completedAt when the purge was confirmed
   * @param replayed whether this answer repeats an earlier confirmation (nothing deleted now)
   */
  @Schema(name = "SalaryAdvancePurgeResult")
  public record PurgeResult(
      UUID previewId, int handoffCount, int attemptCount, Instant completedAt, boolean replayed) {}
}
