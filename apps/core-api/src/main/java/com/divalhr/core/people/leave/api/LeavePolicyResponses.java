package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.leave.domain.ApprovalRoute;
import com.divalhr.core.people.leave.domain.BalanceMode;
import com.divalhr.core.people.leave.domain.LeavePolicy;
import com.divalhr.core.people.leave.domain.LeavePolicyStatus;
import com.divalhr.core.people.leave.domain.LeaveUnit;
import com.divalhr.core.people.leave.domain.PayrollEffect;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Leave policy responses (MVP-040A). Every response carries the organization's business date
 * ({@code asOf}) and time zone the statuses were computed for.
 */
public final class LeavePolicyResponses {

  private LeavePolicyResponses() {}

  /**
   * Both names.
   *
   * @param en English name
   * @param fr French name
   */
  @Schema(name = "LeavePolicyNames")
  public record Names(String en, String fr) {}

  /**
   * One policy with its current version and status.
   *
   * @param id policy id
   * @param code code
   * @param versionNumber version number
   * @param names names
   * @param unit unit
   * @param balanceMode balance mode
   * @param annualEntitlement entitlement (two decimals) or {@code null}
   * @param minimumServiceDays service days before eligibility
   * @param approvalRoute approval route
   * @param payrollEffect payroll effect (descriptive only)
   * @param effectiveFrom first day
   * @param effectiveTo last day or {@code null}
   * @param status status on {@code asOf}
   * @param createdAt creation time
   */
  @Schema(name = "LeavePolicy")
  public record Policy(
      UUID id,
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
      LeavePolicyStatus status,
      Instant createdAt) {

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
          LeavePolicyStatus.of(policy.effectiveFrom(), policy.effectiveTo(), asOf),
          policy.createdAt());
    }

    /**
     * Identifiers only: names never reach a log line through {@code toString}.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Policy[id=" + id + ", versionNumber=" + versionNumber + "]";
    }
  }

  /**
   * The created policy.
   *
   * @param policy the policy
   * @param asOf business date of the status
   * @param timezone the organization's IANA time zone
   */
  @Schema(name = "LeavePolicyResult")
  public record Result(Policy policy, LocalDate asOf, String timezone) {}

  /**
   * One page of policies ordered by code, then id.
   *
   * @param items policies
   * @param nextCursor continuation or {@code null}
   * @param asOf business date of every status (pinned by the cursor across pages)
   * @param timezone the organization's IANA time zone
   */
  @Schema(name = "LeavePolicyPage")
  public record Page(List<Policy> items, String nextCursor, LocalDate asOf, String timezone) {

    /** Keeps an unmodifiable copy of the items. */
    public Page {
      items = List.copyOf(items);
    }
  }
}
