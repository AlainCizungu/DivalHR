package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.CompensationResponses.MoneyView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Responses of monthly payroll policies and runs (MVP-094A, ADR 0033). Money is the canonical
 * decimal string next to its currency code; currencies are never converted or combined. Contractual
 * gross is the monthly base compensation after the proration policy: it is not taxable gross, net
 * pay or money owed, and a draft is not statutory payroll. Restricted financial and HR data: no
 * record here prints an amount, an employee number or a digest, and the commit receipts carry none
 * (they are what the idempotency store keeps).
 */
public final class PayrollResponses {

  private PayrollResponses() {}

  /**
   * A pay day.
   *
   * @param kind {@code DAY} or {@code LAST_DAY}
   * @param day 1 to 28 for {@code DAY}, else {@code null}
   */
  @Schema(name = "PayrollPayDay")
  public record PayDayView(String kind, Integer day) {}

  /**
   * One effective-dated employer policy.
   *
   * @param policyId policy
   * @param sequence sequence within the legal entity
   * @param effectiveFrom first day in force
   * @param effectiveTo inclusive last day (derived), or {@code null} for the last policy
   * @param payDay pay day
   * @param prorationMethod proration method
   */
  @Schema(name = "PayrollPolicyEntry")
  public record PolicyEntryView(
      UUID policyId,
      int sequence,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      PayDayView payDay,
      String prorationMethod) {}

  /**
   * The policy timeline of a legal entity.
   *
   * @param legalEntityId legal entity
   * @param version the last sequence (0 when none)
   * @param businessDate the organization's business date
   * @param current the policy in force on that date, or {@code null}
   * @param policies the policies, newest first
   */
  @Schema(name = "PayrollPolicyView")
  public record PolicyView(
      UUID legalEntityId,
      int version,
      LocalDate businessDate,
      PolicyEntryView current,
      List<PolicyEntryView> policies) {

    /** Copies the list. */
    public PolicyView {
      policies = List.copyOf(policies);
    }
  }

  /**
   * A previewed policy; with a blocker there is no digest and no expiry.
   *
   * @param effectiveFrom first day
   * @param payDay pay day
   * @param prorationMethod proration method
   * @param before the timeline now
   * @param after the timeline after the policy
   * @param blockers stable blocker codes ({@code NO_CHANGE}, {@code LIMIT_REACHED})
   * @param version policy version to repeat as {@code expectedVersion}
   * @param previewDigest digest to repeat, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   */
  @Schema(name = "PayrollPolicyPreview")
  public record PolicyPreview(
      LocalDate effectiveFrom,
      PayDayView payDay,
      String prorationMethod,
      List<PolicyEntryView> before,
      List<PolicyEntryView> after,
      List<String> blockers,
      int version,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the lists. */
    public PolicyPreview {
      before = List.copyOf(before);
      after = List.copyOf(after);
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * The receipt of a recorded policy (what the idempotency store keeps).
   *
   * @param policyId the recorded policy
   * @param sequence its sequence
   * @param effectiveFrom its first day
   * @param version the policy version after it
   */
  @Schema(name = "PayrollPolicyReceipt")
  public record PolicyReceipt(UUID policyId, int sequence, LocalDate effectiveFrom, int version) {}

  /**
   * A maximal run of eligible days, inclusive.
   *
   * @param from first day
   * @param to last day
   */
  @Schema(name = "PayrollDateRange")
  public record RangeView(LocalDate from, LocalDate to) {}

  /**
   * A source record that fed a line, by identifier and version only.
   *
   * @param kind {@code EMPLOYMENT}, {@code PLACEMENT} or {@code COMPENSATION}
   * @param sourceId the record
   * @param sourceVersion its version
   * @param validFrom first day
   * @param validTo last day, or {@code null}
   */
  @Schema(name = "PayrollSourceReference")
  public record SourceView(
      String kind, UUID sourceId, long sourceVersion, LocalDate validFrom, LocalDate validTo) {}

  /**
   * The policy applied to a run.
   *
   * @param policyId policy
   * @param sequence sequence
   * @param payDay pay day
   * @param prorationMethod proration method
   */
  @Schema(name = "PayrollPolicyApplied")
  public record PolicyAppliedView(
      UUID policyId, int sequence, PayDayView payDay, String prorationMethod) {}

  /**
   * One employee of a previewed run. With any blocker, denominator, monthly and gross are null.
   *
   * @param employeeId employee
   * @param employeeNumber employee number (Confidential)
   * @param eligibleDays eligible days
   * @param ranges maximal eligible ranges
   * @param denominator divisor, or {@code null} for a full month and when blocked
   * @param fullMonth whether every day is eligible
   * @param monthly monthly base compensation, or {@code null} when blocked
   * @param gross contractual gross, or {@code null} when blocked
   * @param explanationCodes stable explanation codes
   * @param blockers stable employee blockers
   * @param excludedOtherCurrency whether the employee is paid only in the other currency and so is
   *     not part of this run
   */
  @Schema(name = "PayrollPreviewLine")
  public record PreviewLine(
      UUID employeeId,
      String employeeNumber,
      int eligibleDays,
      List<RangeView> ranges,
      Integer denominator,
      boolean fullMonth,
      MoneyView monthly,
      MoneyView gross,
      List<String> explanationCodes,
      List<String> blockers,
      boolean excludedOtherCurrency) {

    /** Copies the lists. */
    public PreviewLine {
      ranges = List.copyOf(ranges);
      explanationCodes = List.copyOf(explanationCodes);
      blockers = List.copyOf(blockers);
    }

    @Override
    public String toString() {
      return "PayrollPreviewLine[" + employeeId + "]";
    }
  }

  /**
   * A run version, summarized.
   *
   * @param runId run
   * @param legalEntityId legal entity
   * @param period {@code YYYY-MM}
   * @param currency currency
   * @param version version in its chain
   * @param status {@code DRAFT} or {@code SUPERSEDED}
   * @param policySequence frozen policy sequence
   * @param employeeCount employees with a gross
   * @param excludedEmployeeCount employees paid only in the other currency, left out of the run
   * @param total total contractual gross
   * @param createdAt creation instant
   * @param supersededAt supersession instant, or {@code null}
   * @param supersededByRunId successor, or {@code null}
   */
  @Schema(name = "PayrollRunSummary")
  public record RunSummary(
      UUID runId,
      UUID legalEntityId,
      String period,
      String currency,
      int version,
      String status,
      int policySequence,
      int employeeCount,
      int excludedEmployeeCount,
      MoneyView total,
      Instant createdAt,
      Instant supersededAt,
      UUID supersededByRunId) {

    @Override
    public String toString() {
      return "PayrollRunSummary[" + runId + "]";
    }
  }

  /**
   * A previewed run; while a blocker exists or when it is identical to the current draft there is
   * no preview digest and no expiry.
   *
   * @param legalEntityId legal entity
   * @param period {@code YYYY-MM}
   * @param currency currency
   * @param policy policy in force on the last day of the month
   * @param expectedVersion current chain version (0 when none) to repeat on creation
   * @param existingRun the current draft, or {@code null}
   * @param status {@code READY}, {@code BLOCKED} (a blocker exists or no employee has a gross) or
   *     {@code NO_CHANGE} (the recalculation equals the current draft)
   * @param employeeCount employees without a blocker
   * @param blockedEmployeeCount employees with a blocker
   * @param excludedEmployeeCount employees paid only in the other currency, left out of this run
   * @param total sum of the contractual gross of the employees without a blocker
   * @param lines one line per member of the population
   * @param inputDigest digest of the inputs
   * @param previewDigest digest to repeat, or {@code null}
   * @param expiresAt expiry, or {@code null}
   */
  @Schema(name = "PayrollRunPreview")
  public record RunPreview(
      UUID legalEntityId,
      String period,
      String currency,
      PolicyAppliedView policy,
      int expectedVersion,
      RunSummary existingRun,
      String status,
      int employeeCount,
      int blockedEmployeeCount,
      int excludedEmployeeCount,
      MoneyView total,
      List<PreviewLine> lines,
      String inputDigest,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the list. */
    public RunPreview {
      lines = List.copyOf(lines);
    }

    @Override
    public String toString() {
      return "PayrollRunPreview[" + legalEntityId + "]";
    }
  }

  /**
   * The amount-free receipt of a created run (what the idempotency store keeps).
   *
   * @param runId the created run
   * @param legalEntityId legal entity
   * @param period {@code YYYY-MM}
   * @param currency currency
   * @param version version in its chain
   * @param status always {@code DRAFT}
   * @param supersededRunId the version it superseded, or {@code null}
   * @param employeeCount employees with a gross
   */
  @Schema(name = "PayrollRunReceipt")
  public record RunReceipt(
      UUID runId,
      UUID legalEntityId,
      String period,
      String currency,
      int version,
      String status,
      UUID supersededRunId,
      int employeeCount) {}

  /**
   * A page of run versions.
   *
   * @param items the runs
   * @param nextCursor cursor of the next page, or {@code null}
   */
  @Schema(name = "PayrollRunPage")
  public record RunPage(List<RunSummary> items, String nextCursor) {

    /** Copies the list. */
    public RunPage {
      items = List.copyOf(items);
    }
  }

  /**
   * One frozen employee line with its evidence.
   *
   * @param employeeId employee
   * @param employeeNumber employee number (Confidential)
   * @param eligibleDays eligible days
   * @param ranges maximal eligible ranges
   * @param denominator divisor, or {@code null} for a full month
   * @param fullMonth whether every day was eligible
   * @param monthly monthly base compensation
   * @param gross contractual gross
   * @param explanationCodes stable explanation codes
   * @param sources source records by identifier and version
   */
  @Schema(name = "PayrollRunLine")
  public record RunLine(
      UUID employeeId,
      String employeeNumber,
      int eligibleDays,
      List<RangeView> ranges,
      Integer denominator,
      boolean fullMonth,
      MoneyView monthly,
      MoneyView gross,
      List<String> explanationCodes,
      List<SourceView> sources) {

    /** Copies the lists. */
    public RunLine {
      ranges = List.copyOf(ranges);
      explanationCodes = List.copyOf(explanationCodes);
      sources = List.copyOf(sources);
    }

    @Override
    public String toString() {
      return "PayrollRunLine[" + employeeId + "]";
    }
  }

  /**
   * A run with a page of its lines.
   *
   * @param run the run
   * @param policy the frozen policy
   * @param inputDigest digest of the inputs
   * @param lines a page of lines by employee number
   * @param nextCursor cursor of the next page, or {@code null}
   */
  @Schema(name = "PayrollRunDetail")
  public record RunDetail(
      RunSummary run,
      PolicyAppliedView policy,
      String inputDigest,
      List<RunLine> lines,
      String nextCursor) {

    /** Copies the list. */
    public RunDetail {
      lines = List.copyOf(lines);
    }

    @Override
    public String toString() {
      return "PayrollRunDetail[" + run + "]";
    }
  }
}
