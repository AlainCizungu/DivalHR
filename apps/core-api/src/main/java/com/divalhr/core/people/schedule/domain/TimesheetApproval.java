package com.divalhr.core.people.schedule.domain;

import com.divalhr.core.people.schedule.domain.Timesheet.Totals;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MVP-070B/C (Issue #130, T70BC-1 to T70BC-7; ADR 0019) and MVP-070D (Issue #132, T70D-1; ADR
 * 0020): a weekly timesheet's submission, decision and reopening lifecycle. The current state is
 * the state of the latest action: none or {@link Kind#RETURNED} is a {@link Status#DRAFT}, {@link
 * Kind#SUBMITTED} is {@link Status#SUBMITTED}, {@link Kind#APPROVED} is {@link Status#APPROVED},
 * {@link Kind#REOPENED} is {@link Status#REOPENED} until the organization's refresh gives the
 * timesheet its next revision as a {@link Status#DRAFT}. Only a clean revision is submitted: at
 * least one READY row and no blocking row.
 */
public final class TimesheetApproval {

  /** Fewest code points of a return reason. */
  public static final int REASON_MIN = 10;

  /** Most code points of a return reason. */
  public static final int REASON_MAX = 500;

  private TimesheetApproval() {}

  /** The timesheet's current state. */
  public enum Status {
    /** Never submitted, or returned: the administrators' refresh applies; the employee submits. */
    DRAFT,
    /** Awaiting its routed manager's decision. */
    SUBMITTED,
    /** Approved: never changed; a material attendance correction reopens it (MVP-070D). */
    APPROVED,
    /**
     * Approved, then reopened by a correction: the organization refreshes it before resubmission.
     */
    REOPENED
  }

  /** An append-only action. */
  public enum Kind {
    /** The employee submitted the current revision as the next cycle. */
    SUBMITTED,
    /** The routed manager returned the cycle with a reason: a DRAFT again. */
    RETURNED,
    /** The routed manager approved the cycle. */
    APPROVED,
    /** A committed attendance correction materially changed the approved cycle's sources. */
    REOPENED;

    /**
     * The timesheet's state after this action.
     *
     * @return the state
     */
    public Status state() {
      return switch (this) {
        case SUBMITTED -> Status.SUBMITTED;
        case APPROVED -> Status.APPROVED;
        case RETURNED -> Status.DRAFT;
        case REOPENED -> Status.REOPENED;
      };
    }
  }

  /** What keeps a draft from being submitted now (never names a manager). */
  public enum Blocker {
    /** A slot without attendance: resolve it with the organization. */
    MISSING_ATTENDANCE,
    /** A record without check-out: resolve it with the organization. */
    OPEN_ATTENDANCE,
    /** A slot no longer included since the previous revision. */
    SOURCE_CHANGED,
    /** No READY row to submit. */
    NO_READY_ROWS,
    /** No single eligible manager is effective for the week. */
    APPROVAL_ROUTE_UNAVAILABLE,
    /** Reopened after approval: the organization refreshes the week before a new submission. */
    REFRESH_REQUIRED
  }

  /**
   * One immutable action.
   *
   * @param id action
   * @param timesheetId timesheet
   * @param sequence 1, 2, ... per timesheet
   * @param kind the action
   * @param cycle its submission cycle
   * @param submissionActionId a decision's submission, or {@code null}
   * @param revision the bound revision
   * @param generationVersion the generation version when it happened
   * @param sourceDigest the revision's source digest
   * @param totals the revision's totals
   * @param employeeId employee
   * @param employmentId the qualifying employment of the revision's rows
   * @param managerEmployeeId the routed (submission), deciding (decision) or approving (reopening)
   *     manager
   * @param returnReason a return's reason, or {@code null}
   * @param occurredAt instant
   * @param supersededActionId a reopening's superseded approval, or {@code null}
   * @param afterSourceDigest a reopening's source digest after the correction, or {@code null}
   * @param correctionId a reopening's attendance correction, or {@code null}
   */
  public record Action(
      UUID id,
      UUID timesheetId,
      int sequence,
      Kind kind,
      int cycle,
      UUID submissionActionId,
      int revision,
      int generationVersion,
      String sourceDigest,
      Totals totals,
      UUID employeeId,
      UUID employmentId,
      UUID managerEmployeeId,
      String returnReason,
      Instant occurredAt,
      UUID supersededActionId,
      String afterSourceDigest,
      UUID correctionId) {

    /** Requires the identity, kind and digest. */
    public Action {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(sourceDigest, "sourceDigest");
    }

    /**
     * A submission or decision (no reopening facts).
     *
     * @param id action
     * @param timesheetId timesheet
     * @param sequence 1, 2, ... per timesheet
     * @param kind the action
     * @param cycle its submission cycle
     * @param submissionActionId a decision's submission, or {@code null}
     * @param revision the bound revision
     * @param generationVersion the generation version when it happened
     * @param sourceDigest the revision's source digest
     * @param totals the revision's totals
     * @param employeeId employee
     * @param employmentId the qualifying employment of the revision's rows
     * @param managerEmployeeId the routed (submission) or deciding (decision) manager
     * @param returnReason a return's reason, or {@code null}
     * @param occurredAt instant
     */
    public Action(
        UUID id,
        UUID timesheetId,
        int sequence,
        Kind kind,
        int cycle,
        UUID submissionActionId,
        int revision,
        int generationVersion,
        String sourceDigest,
        Totals totals,
        UUID employeeId,
        UUID employmentId,
        UUID managerEmployeeId,
        String returnReason,
        Instant occurredAt) {
      this(
          id,
          timesheetId,
          sequence,
          kind,
          cycle,
          submissionActionId,
          revision,
          generationVersion,
          sourceDigest,
          totals,
          employeeId,
          employmentId,
          managerEmployeeId,
          returnReason,
          occurredAt,
          null,
          null,
          null);
    }

    /**
     * The reopening of an approval: its exact evidence, manager and cycle, the source digest after
     * the correction, the correction and the instant (T70D-3).
     *
     * @param approval the superseded approval (the timesheet's latest action)
     * @param afterSourceDigest the source digest after the correction
     * @param correctionId the committed attendance correction
     * @param occurredAt instant
     * @return the reopening
     */
    public static Action reopening(
        Action approval, String afterSourceDigest, UUID correctionId, Instant occurredAt) {
      if (approval.kind() != Kind.APPROVED || approval.sourceDigest().equals(afterSourceDigest)) {
        throw new IllegalArgumentException("only a materially changed approval is reopened");
      }
      return new Action(
          UUID.randomUUID(),
          approval.timesheetId(),
          approval.sequence() + 1,
          Kind.REOPENED,
          approval.cycle(),
          null,
          approval.revision(),
          approval.generationVersion(),
          approval.sourceDigest(),
          approval.totals(),
          approval.employeeId(),
          approval.employmentId(),
          approval.managerEmployeeId(),
          null,
          occurredAt,
          approval.id(),
          Objects.requireNonNull(afterSourceDigest, "afterSourceDigest"),
          Objects.requireNonNull(correctionId, "correctionId"));
    }

    /**
     * Identifiers and kind only: never the reason or the manager in a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "TimesheetApproval.Action[" + kind + ", cycle=" + cycle + "]";
    }
  }

  /**
   * What keeps a DRAFT revision with these totals from being submitted, before the route.
   *
   * @param totals the current revision's totals
   * @return the row blockers, in a fixed order
   */
  public static List<Blocker> rowBlockers(Totals totals) {
    List<Blocker> blockers = new ArrayList<>();
    if (totals.missing() > 0) {
      blockers.add(Blocker.MISSING_ATTENDANCE);
    }
    if (totals.open() > 0) {
      blockers.add(Blocker.OPEN_ATTENDANCE);
    }
    if (totals.changed() > 0) {
      blockers.add(Blocker.SOURCE_CHANGED);
    }
    if (totals.ready() == 0) {
      blockers.add(Blocker.NO_READY_ROWS);
    }
    return List.copyOf(blockers);
  }

  /**
   * The date the approval route is resolved on: the site-local Sunday, the last included local date
   * of the Monday-to-Sunday week (T70BC-3).
   *
   * @param weekStart the site-local Monday
   * @return the Sunday
   */
  public static LocalDate routeDate(LocalDate weekStart) {
    return weekStart.plusDays(6);
  }

  /**
   * A bounded band of worked minutes for audit metadata: never the exact total.
   *
   * @param minutes worked minutes
   * @return the band
   */
  public static String workedMinuteBand(int minutes) {
    if (minutes <= 0) {
      return "0";
    }
    if (minutes <= 480) {
      return "1-480";
    }
    if (minutes <= 2400) {
      return "481-2400";
    }
    return minutes <= 3600 ? "2401-3600" : "3601+";
  }
}
