package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.leave.api.LeavePolicyResponses.Names;
import com.divalhr.core.people.leave.domain.ApprovalRoute;
import com.divalhr.core.people.leave.domain.BalanceMode;
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

/** Employee self-service leave responses (MVP-041A). */
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
   * @param state state
   * @param submittedAt submission time
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
      Instant submittedAt) {

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
          request.submittedAt());
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
