package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.leave.api.LeavePolicyResponses.Names;
import com.divalhr.core.people.leave.domain.ApprovalRoute;
import com.divalhr.core.people.leave.domain.LeaveApprovalItem;
import com.divalhr.core.people.leave.domain.LeaveRequestState;
import com.divalhr.core.people.leave.domain.LeaveUnit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Leave approval responses (MVP-041B): inbox items and the decision receipt. */
public final class LeaveApprovalResponses {

  private LeaveApprovalResponses() {}

  /**
   * The requesting employee, as an approver needs them (plain text).
   *
   * @param id employee
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   */
  @Schema(name = "LeaveApprovalEmployee")
  public record Employee(UUID id, String employeeNumber, String givenNames, String familyName) {

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "LeaveApprovalEmployee[id=" + id + "]";
    }
  }

  /**
   * A pending request awaiting the caller's decision.
   *
   * @param id request
   * @param submittedAt submission time
   * @param employee requesting employee
   * @param policyId policy
   * @param policyVersionId policy version
   * @param policyCode policy code
   * @param policyNames both policy names
   * @param unit unit of the amount
   * @param amount requested amount (two decimals)
   * @param startDate first day (inclusive)
   * @param endDate last day (inclusive)
   * @param approvalRoute the route it is decided under
   * @param state {@code PENDING}
   */
  @Schema(name = "LeaveApproval")
  public record Item(
      UUID id,
      Instant submittedAt,
      Employee employee,
      UUID policyId,
      UUID policyVersionId,
      String policyCode,
      Names policyNames,
      LeaveUnit unit,
      BigDecimal amount,
      LocalDate startDate,
      LocalDate endDate,
      ApprovalRoute approvalRoute,
      LeaveRequestState state) {

    /**
     * The view of a pending request.
     *
     * @param item request
     * @return the view
     */
    public static Item of(LeaveApprovalItem item) {
      return new Item(
          item.id(),
          item.submittedAt(),
          new Employee(
              item.employeeId(), item.employeeNumber(), item.givenNames(), item.familyName()),
          item.policyId(),
          item.policyVersionId(),
          item.policyCode(),
          new Names(item.nameEn(), item.nameFr()),
          item.unit(),
          item.amount().setScale(2, RoundingMode.UNNECESSARY),
          item.startDate(),
          item.endDate(),
          item.route(),
          item.state());
    }

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "LeaveApproval[id=" + id + "]";
    }
  }

  /** Why a request is a routing exception (MVP-041E): a fixed, safe code. */
  @Schema(name = "LeaveRoutingExceptionReason")
  public enum ExceptionReason {
    /**
     * The request is {@code MANAGER}-routed and no active, non-superseded MANAGER line covers its
     * employment on its first day.
     */
    NO_QUALIFYING_MANAGER
  }

  /**
   * A routing exception (MVP-041E): the same safe fields as an approval-inbox item and the fixed
   * reason code. The route stays {@code MANAGER}.
   *
   * @param id request
   * @param submittedAt submission time
   * @param employee requesting employee
   * @param policyId policy
   * @param policyVersionId policy version the request was made under
   * @param policyCode policy code
   * @param policyNames policy names
   * @param unit unit of the amount
   * @param amount requested amount (two decimals)
   * @param startDate first day (inclusive)
   * @param endDate last day (inclusive)
   * @param approvalRoute {@code MANAGER} (the policy route; never rewritten)
   * @param state {@code PENDING}
   * @param exceptionReason {@code NO_QUALIFYING_MANAGER}
   */
  @Schema(name = "LeaveRoutingException")
  public record ExceptionItem(
      UUID id,
      Instant submittedAt,
      Employee employee,
      UUID policyId,
      UUID policyVersionId,
      String policyCode,
      Names policyNames,
      LeaveUnit unit,
      BigDecimal amount,
      LocalDate startDate,
      LocalDate endDate,
      ApprovalRoute approvalRoute,
      LeaveRequestState state,
      ExceptionReason exceptionReason) {

    /**
     * The routing-exception view of a queue item.
     *
     * @param item the item
     * @return the view
     */
    public static ExceptionItem of(Item item) {
      return new ExceptionItem(
          item.id(),
          item.submittedAt(),
          item.employee(),
          item.policyId(),
          item.policyVersionId(),
          item.policyCode(),
          item.policyNames(),
          item.unit(),
          item.amount(),
          item.startDate(),
          item.endDate(),
          item.approvalRoute(),
          item.state(),
          ExceptionReason.NO_QUALIFYING_MANAGER);
    }

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "LeaveRoutingException[id=" + id + "]";
    }
  }

  /**
   * One page of routing exceptions, newest first (MVP-041E).
   *
   * @param items exceptions
   * @param nextCursor continuation or {@code null}
   */
  @Schema(name = "LeaveRoutingExceptionPage")
  public record ExceptionPage(List<ExceptionItem> items, String nextCursor) {

    /** Keeps an unmodifiable copy of the items. */
    public ExceptionPage {
      items = List.copyOf(items);
    }
  }

  /**
   * One page of pending requests awaiting the caller's decision, newest first.
   *
   * @param items requests
   * @param nextCursor continuation or {@code null}
   */
  @Schema(name = "LeaveApprovalPage")
  public record Page(List<Item> items, String nextCursor) {

    /** Keeps an unmodifiable copy of the items. */
    public Page {
      items = List.copyOf(items);
    }
  }

  /**
   * The minimal receipt of a decision: never the reason (the requesting employee reads it in their
   * own history).
   *
   * @param requestId request
   * @param decisionId decision
   * @param state the request's resulting state
   * @param decidedAt decision time
   */
  @Schema(name = "LeaveDecisionReceipt")
  public record Receipt(
      UUID requestId, UUID decisionId, LeaveRequestState state, Instant decidedAt) {}
}
