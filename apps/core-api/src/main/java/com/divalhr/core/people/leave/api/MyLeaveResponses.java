package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.leave.api.LeavePolicyResponses.Names;
import com.divalhr.core.people.leave.domain.ApprovalRoute;
import com.divalhr.core.people.leave.domain.BalanceMode;
import com.divalhr.core.people.leave.domain.LeaveCancellation;
import com.divalhr.core.people.leave.domain.LeaveDecision;
import com.divalhr.core.people.leave.domain.LeavePolicy;
import com.divalhr.core.people.leave.domain.LeavePolicyStatus;
import com.divalhr.core.people.leave.domain.LeaveRequest;
import com.divalhr.core.people.leave.domain.LeaveRequestState;
import com.divalhr.core.people.leave.domain.LeaveUnit;
import com.divalhr.core.people.leave.domain.PayrollEffect;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Employee self-service leave responses (MVP-041A; decisions MVP-041B; cancellations MVP-041C). */
public final class MyLeaveResponses {

  private MyLeaveResponses() {}

  /**
   * The employee-safe view of a policy that can be requested.
   *
   * @param id policy
   * @param versionId current version
   * @param code code
   * @param versionNumber version number
   * @param names names
   * @param unit unit of a request's amount
   * @param balanceMode balance mode (no balance is calculated yet)
   * @param annualEntitlement entitlement (two decimals) or {@code null}
   * @param minimumServiceDays calendar days of service before a request may start
   * @param approvalRoute who will approve (approval comes later)
   * @param payrollEffect payroll description (descriptive only)
   * @param effectiveFrom first day
   * @param effectiveTo last day or {@code null}
   * @param status status on {@code asOf}
   */
  @Schema(name = "MyLeavePolicy")
  public record Policy(
      UUID id,
      UUID versionId,
      String code,
      int versionNumber,
      Names names,
      LeaveUnit unit,
      BalanceMode balanceMode,
      BigDecimal annualEntitlement,
      int minimumServiceDays,
      ApprovalRoute approvalRoute,
      PayrollEffect payrollEffect,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      LeavePolicyStatus status) {

    /**
     * The view of a policy on a business date.
     *
     * @param policy policy
     * @param asOf business date
     * @return the view
     */
    public static Policy of(LeavePolicy policy, LocalDate asOf) {
      return new Policy(
          policy.id(),
          policy.versionId(),
          policy.code(),
          policy.versionNumber(),
          new Names(policy.nameEn(), policy.nameFr()),
          policy.unit(),
          policy.balanceMode(),
          policy.annualEntitlement() == null
              ? null
              : policy.annualEntitlement().setScale(2, RoundingMode.UNNECESSARY),
          policy.minimumServiceDays(),
          policy.approvalRoute(),
          policy.payrollEffect(),
          policy.effectiveFrom(),
          policy.effectiveTo(),
          LeavePolicyStatus.of(policy.effectiveFrom(), policy.effectiveTo(), asOf));
    }

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "MyLeavePolicy[id=" + id + "]";
    }
  }

  /**
   * One page of requestable (planned or active) policies, by code, then id.
   *
   * @param items policies
   * @param nextCursor continuation or {@code null}
   * @param asOf business date of every status (pinned by the cursor across pages)
   * @param timezone the organization's IANA time zone
   */
  @Schema(name = "MyLeavePolicyPage")
  public record PolicyPage(List<Policy> items, String nextCursor, LocalDate asOf, String timezone) {

    /** Keeps an unmodifiable copy of the items. */
    public PolicyPage {
      items = List.copyOf(items);
    }
  }

  /**
   * One of the caller's own leave requests.
   *
   * @param id request
   * @param policyId policy
   * @param policyVersionId the policy version it was made under
   * @param policyCode policy code
   * @param policyNames policy names
   * @param unit unit of the amount
   * @param amount requested amount (two decimals)
   * @param startDate first day (inclusive)
   * @param endDate last day (inclusive)
   * @param state current state
   * @param submittedAt submission time
   * @param decision the decision and its reason once decided, otherwise {@code null}; never the
   *     deciding person
   * @param cancellation the cancellation and its reason once cancelled, otherwise {@code null};
   *     never the cancelling subject
   */
  @Schema(name = "MyLeaveRequest")
  public record Request(
      UUID id,
      UUID policyId,
      UUID policyVersionId,
      String policyCode,
      Names policyNames,
      LeaveUnit unit,
      BigDecimal amount,
      LocalDate startDate,
      LocalDate endDate,
      LeaveRequestState state,
      Instant submittedAt,
      Decision decision,
      Cancellation cancellation) {

    /**
     * The view of a request.
     *
     * @param request request
     * @return the view
     */
    public static Request of(LeaveRequest request) {
      return new Request(
          request.id(),
          request.policyId(),
          request.policyVersionId(),
          request.policyCode(),
          new Names(request.nameEn(), request.nameFr()),
          request.unit(),
          request.amount().setScale(2, RoundingMode.UNNECESSARY),
          request.startDate(),
          request.endDate(),
          request.state(),
          request.submittedAt(),
          request.decision() == null ? null : Decision.of(request.decision()),
          request.cancellation() == null ? null : Cancellation.of(request.cancellation()));
    }

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "MyLeaveRequest[id=" + id + "]";
    }
  }

  /**
   * The decision on one of the caller's own requests (MVP-041B): the outcome and the reason in the
   * language it was written in. The deciding subject and manager are never included.
   *
   * @param id decision
   * @param outcome {@code APPROVED} or {@code REJECTED}
   * @param reasonLocale {@code en} or {@code fr}
   * @param reason the reason, as written (plain text)
   * @param decidedAt decision time
   */
  @Schema(name = "MyLeaveDecision")
  public record Decision(
      UUID id, LeaveRequestState outcome, String reasonLocale, String reason, Instant decidedAt) {

    /**
     * The employee-safe view of a decision.
     *
     * @param decision decision
     * @return the view
     */
    public static Decision of(LeaveDecision decision) {
      return new Decision(
          decision.id(),
          decision.outcome(),
          decision.reasonLocale(),
          decision.reason(),
          decision.decidedAt());
    }

    /**
     * Identifiers and outcome only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "MyLeaveDecision[id=" + id + ", outcome=" + outcome + "]";
    }
  }

  /**
   * The cancellation of one of the caller's own requests (MVP-041C): the reason in the language it
   * was written in. The cancelling subject is never included.
   *
   * @param id cancellation
   * @param reasonLocale {@code en} or {@code fr}
   * @param reason the reason, as written (plain text)
   * @param cancelledAt cancellation time
   */
  @Schema(name = "MyLeaveCancellation")
  public record Cancellation(UUID id, String reasonLocale, String reason, Instant cancelledAt) {

    /**
     * The employee-safe view of a cancellation.
     *
     * @param cancellation cancellation
     * @return the view
     */
    public static Cancellation of(LeaveCancellation cancellation) {
      return new Cancellation(
          cancellation.id(),
          cancellation.reasonLocale(),
          cancellation.reason(),
          cancellation.cancelledAt());
    }

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "MyLeaveCancellation[id=" + id + "]";
    }
  }

  /**
   * The minimal receipt of a cancellation (MVP-041C): identifiers, the resulting state and the
   * time. Never the reason (it is not stored in the idempotency response either).
   *
   * @param requestId the cancelled request
   * @param cancellationId its cancellation
   * @param state {@code CANCELLED}
   * @param cancelledAt cancellation time
   */
  @Schema(name = "LeaveCancellationReceipt")
  public record CancellationReceipt(
      UUID requestId, UUID cancellationId, LeaveRequestState state, Instant cancelledAt) {}

  /**
   * One page of the caller's own requests, newest first.
   *
   * @param items requests
   * @param nextCursor continuation or {@code null}
   */
  @Schema(name = "MyLeaveRequestPage")
  public record RequestPage(List<Request> items, String nextCursor) {

    /** Keeps an unmodifiable copy of the items. */
    public RequestPage {
      items = List.copyOf(items);
    }
  }
}
