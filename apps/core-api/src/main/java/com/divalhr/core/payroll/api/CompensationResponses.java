package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Responses of monthly base compensation (MVP-093A, ADR 0032). The amount is a canonical decimal
 * string next to its currency code; currencies are never converted, compared as one total or
 * combined. Restricted financial and HR data: no record here prints an amount or an explanation,
 * and the commit receipt carries neither (it is what the idempotency store keeps). Recording
 * compensation does not calculate payroll or initiate payment.
 */
public final class CompensationResponses {

  private CompensationResponses() {}

  /**
   * An amount with its currency.
   *
   * @param currency {@code CDF} or {@code USD}
   * @param amount canonical decimal text
   */
  @Schema(name = "CompensationMoney")
  public record MoneyView(String currency, String amount) {

    @Override
    public String toString() {
      return "CompensationMoney[" + currency + "]";
    }
  }

  /**
   * One active effective interval.
   *
   * @param originChangeId the change that introduced the value and start
   * @param money the value
   * @param frequency always {@code MONTHLY}
   * @param effectiveFrom first day
   * @param effectiveTo inclusive last day, or {@code null} while open
   * @param cancellable whether it is an unstarted {@code CHANGED} interval (administrators only)
   */
  @Schema(name = "CompensationInterval")
  public record IntervalView(
      UUID originChangeId,
      MoneyView money,
      String frequency,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      boolean cancellable) {

    @Override
    public String toString() {
      return "CompensationInterval[" + originChangeId + "]";
    }
  }

  /**
   * One immutable recorded change.
   *
   * @param changeId change
   * @param sequence sequence within the employee
   * @param action action
   * @param money the value it introduced (for a cancellation, the cancelled value)
   * @param effectiveFrom first day of that value
   * @param reasonCode correction reason, or {@code null}
   * @param explanation administrator explanation (administrator reads only), or {@code null}
   * @param targetChangeId the change acted on, or {@code null}
   * @param retroactive whether the date was before the business date of the recording
   * @param recordedAt when it was recorded
   */
  @Schema(name = "CompensationChangeView")
  public record ChangeView(
      UUID changeId,
      int sequence,
      String action,
      MoneyView money,
      LocalDate effectiveFrom,
      String reasonCode,
      String explanation,
      UUID targetChangeId,
      boolean retroactive,
      Instant recordedAt) {

    @Override
    public String toString() {
      return "CompensationChangeView[" + changeId + "]";
    }
  }

  /**
   * An employee's compensation, as an administrator reads it.
   *
   * @param version compensation version (0 when none)
   * @param businessDate the organization's business date
   * @param current the interval in force on that date, or {@code null}
   * @param scheduled intervals that start after that date
   * @param changes a page of changes, newest first
   * @param nextCursor cursor of the next page, or {@code null}
   */
  @Schema(name = "CompensationRecord")
  public record RecordView(
      int version,
      LocalDate businessDate,
      IntervalView current,
      List<IntervalView> scheduled,
      List<ChangeView> changes,
      String nextCursor) {

    /** Copies the lists. */
    public RecordView {
      scheduled = List.copyOf(scheduled);
      changes = List.copyOf(changes);
    }

    @Override
    public String toString() {
      return "CompensationRecord[" + version + "]";
    }
  }

  /**
   * The caller's own compensation (no explanation, nothing cancellable).
   *
   * @param businessDate the organization's business date
   * @param current the interval in force on that date, or {@code null}
   * @param scheduled intervals that start after that date
   * @param changes a page of changes, newest first
   * @param nextCursor cursor of the next page, or {@code null}
   */
  @Schema(name = "MyCompensation")
  public record Mine(
      LocalDate businessDate,
      IntervalView current,
      List<IntervalView> scheduled,
      List<ChangeView> changes,
      String nextCursor) {

    /** Copies the lists. */
    public Mine {
      scheduled = List.copyOf(scheduled);
      changes = List.copyOf(changes);
    }

    @Override
    public String toString() {
      return "MyCompensation[" + businessDate + "]";
    }
  }

  /**
   * A previewed command; with a blocker there is no digest and no expiry.
   *
   * @param action action
   * @param money the submitted value
   * @param effectiveFrom first day
   * @param before the active intervals the command touches, as they are now
   * @param after the same span after the command
   * @param warnings stable warning codes
   * @param blockers stable blocker codes
   * @param requiresReason whether the commit needs a reason code and explanation
   * @param requiresAcknowledgement whether the commit must acknowledge the retroactive effect
   * @param version compensation version to repeat as {@code expectedVersion}
   * @param employmentDigest digest of the employee's employments
   * @param previewDigest digest to repeat, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   */
  @Schema(name = "CompensationPreview")
  public record Preview(
      String action,
      MoneyView money,
      LocalDate effectiveFrom,
      List<IntervalView> before,
      List<IntervalView> after,
      List<String> warnings,
      List<String> blockers,
      boolean requiresReason,
      boolean requiresAcknowledgement,
      int version,
      String employmentDigest,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the lists. */
    public Preview {
      before = List.copyOf(before);
      after = List.copyOf(after);
      warnings = List.copyOf(warnings);
      blockers = List.copyOf(blockers);
    }

    @Override
    public String toString() {
      return "CompensationPreview[" + action + "]";
    }
  }

  /**
   * A previewed cancellation of a scheduled change.
   *
   * @param changeId the change to cancel
   * @param before the active intervals the cancellation touches, as they are now
   * @param after the same span after the cancellation
   * @param warnings stable warning codes
   * @param blockers stable blocker codes
   * @param version compensation version to repeat as {@code expectedVersion}
   * @param employmentDigest digest of the employee's employments
   * @param cancellationDigest digest to repeat, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   */
  @Schema(name = "CompensationCancellationPreview")
  public record CancellationPreview(
      UUID changeId,
      List<IntervalView> before,
      List<IntervalView> after,
      List<String> warnings,
      List<String> blockers,
      int version,
      String employmentDigest,
      String cancellationDigest,
      Instant expiresAt) {

    /** Copies the lists. */
    public CancellationPreview {
      before = List.copyOf(before);
      after = List.copyOf(after);
      warnings = List.copyOf(warnings);
      blockers = List.copyOf(blockers);
    }

    @Override
    public String toString() {
      return "CompensationCancellationPreview[" + changeId + "]";
    }
  }

  /**
   * The amount-free and text-free receipt of a recorded change (what the idempotency store keeps).
   *
   * @param changeId the recorded change
   * @param sequence its sequence
   * @param action its action
   * @param effectiveFrom the first day of the value it introduced or cancelled
   * @param version the compensation version after it
   */
  @Schema(name = "CompensationReceipt")
  public record Receipt(
      UUID changeId, int sequence, String action, LocalDate effectiveFrom, int version) {}
}
