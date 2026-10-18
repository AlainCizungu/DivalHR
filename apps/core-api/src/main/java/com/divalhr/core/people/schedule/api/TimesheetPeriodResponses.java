package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.LockState;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MVP-071 response bodies (L71-3, L71-4, L71-6, L71-9): the locked scope, the readiness counts by
 * state, the lock state, the lock and unlock receipts and the immutable history. No name, employee
 * number, subject or unlock explanation appears in any of them; the explanation is stored and never
 * returned.
 */
public final class TimesheetPeriodResponses {

  private TimesheetPeriodResponses() {}

  /**
   * The current lock state of a generated week.
   *
   * @param state OPEN or LOCKED
   * @param version the number of lock and unlock transitions so far (0 before the first lock)
   * @param currentActionId the current LOCKED or UNLOCKED action, or {@code null}
   * @param updatedAt the latest transition instant, or {@code null}
   */
  @Schema(name = "TimesheetPeriodLockState")
  public record LockStateView(
      @Schema(allowableValues = {"OPEN", "LOCKED"}) String state,
      int version,
      @Schema(nullable = true) UUID currentActionId,
      @Schema(nullable = true) Instant updatedAt) {

    /**
     * The view of a state.
     *
     * @param state the state
     * @return the view
     */
    public static LockStateView of(LockState state) {
      return new LockStateView(
          state.state().name(), state.version(), state.currentActionId(), state.updatedAt());
    }
  }

  /**
   * The locked scope: the legal entity, its site and the generated week with its exact boundaries.
   *
   * @param legalEntityId legal entity
   * @param legalEntityCode its code, or {@code null} when no longer resolvable
   * @param legalEntityName its name, or {@code null} when no longer resolvable
   * @param siteId site
   * @param siteCode site code, or {@code null} when no longer resolvable
   * @param siteName site name, or {@code null} when no longer resolvable
   * @param timezone the site's time zone as generated
   * @param weekStart the local Monday
   * @param weekEnd the next Monday (exclusive)
   * @param lastDay the local Sunday
   * @param periodStartAt Monday 00:00 at the site
   * @param periodEndAt the next Monday 00:00 at the site
   */
  @Schema(name = "TimesheetPeriodScope")
  public record ScopeView(
      UUID legalEntityId,
      @Schema(nullable = true) String legalEntityCode,
      @Schema(nullable = true) String legalEntityName,
      UUID siteId,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) String siteName,
      String timezone,
      LocalDate weekStart,
      LocalDate weekEnd,
      LocalDate lastDay,
      Instant periodStartAt,
      Instant periodEndAt) {

    /**
     * The view of a scope.
     *
     * @param scope the scope
     * @param legalEntity the legal entity, or {@code null}
     * @param site the site, or {@code null}
     * @return the view
     */
    public static ScopeView of(TimesheetPeriod.Scope scope, UnitView legalEntity, UnitView site) {
      Timesheet.Period period = scope.period();
      return new ScopeView(
          scope.legalEntityId(),
          legalEntity == null ? null : legalEntity.code(),
          legalEntity == null ? null : legalEntity.name(),
          period.siteId(),
          site == null ? null : site.code(),
          site == null ? null : site.name(),
          period.timezone(),
          period.weekStart(),
          period.weekEnd(),
          period.lastDay(),
          period.startAt(),
          period.endAt());
    }
  }

  /**
   * The generation's timesheets by state.
   *
   * @param draft DRAFT (never submitted, returned, or refreshed after a reopening)
   * @param submitted SUBMITTED (awaiting a decision)
   * @param approved APPROVED
   * @param reopened REOPENED (an approval superseded by a correction)
   */
  @Schema(name = "TimesheetPeriodStates")
  public record States(int draft, int submitted, int approved, int reopened) {

    /**
     * The view of counts by status.
     *
     * @param states status to count
     * @return the view
     */
    public static States of(Map<String, Integer> states) {
      return new States(
          states.getOrDefault("DRAFT", 0),
          states.getOrDefault("SUBMITTED", 0),
          states.getOrDefault("APPROVED", 0),
          states.getOrDefault("REOPENED", 0));
    }
  }

  /**
   * Counts and total of the week's timesheets.
   *
   * @param timesheets timesheets
   * @param employees employees
   * @param rows rows
   * @param workedMinutes total worked minutes
   */
  @Schema(name = "TimesheetPeriodCounts")
  public record Counts(int timesheets, int employees, int rows, int workedMinutes) {

    /**
     * The view of totals.
     *
     * @param totals totals
     * @return the view
     */
    public static Counts of(TimesheetPeriod.Totals totals) {
      return new Counts(
          totals.timesheets(), totals.employees(), totals.rows(), totals.workedMinutes());
    }
  }

  /**
   * Whether the week is ready to lock, and what it would lock (L71-3).
   *
   * @param scope the scope
   * @param generationId the week's generation
   * @param generationVersion its current version
   * @param lock the current lock state
   * @param counts the week's timesheets, employees, rows and minutes
   * @param states the week's timesheets by state
   * @param ready whether the lock command would succeed now
   * @param blockers the stable reasons it would not (EMPTY, NOT_ALL_APPROVED, APPROVAL_NOT_CURRENT,
   *     ALREADY_LOCKED)
   * @param exported whether the week's current approved evidence was exported (MVP-070E)
   * @param previewDigest the digest to send with the lock command
   * @param expiresAt the instant after which the preview is stale
   */
  @Schema(name = "TimesheetPeriodLockPreview")
  public record LockPreview(
      ScopeView scope,
      UUID generationId,
      int generationVersion,
      LockStateView lock,
      Counts counts,
      States states,
      boolean ready,
      List<String> blockers,
      boolean exported,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the list. */
    public LockPreview {
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * One immutable lock or unlock.
   *
   * @param actionId action
   * @param sequence the lock version after the transition
   * @param action LOCKED or UNLOCKED
   * @param generationVersion the bound generation version
   * @param counts the locked counts and total
   * @param evidenceDigest the locked approval evidence digest
   * @param lockedActionId for an unlock, the reversed lock; else {@code null}
   * @param unlockReason for an unlock, the closed reason code; else {@code null}
   * @param occurredAt instant
   */
  @Schema(name = "TimesheetPeriodAction")
  public record ActionView(
      UUID actionId,
      int sequence,
      @Schema(allowableValues = {"LOCKED", "UNLOCKED"}) String action,
      int generationVersion,
      Counts counts,
      String evidenceDigest,
      @Schema(nullable = true) UUID lockedActionId,
      @Schema(
              nullable = true,
              allowableValues = {
                "ATTENDANCE_CORRECTION_REQUIRED",
                "TIMESHEET_EVIDENCE_ERROR",
                "ORGANIZATION_REQUEST",
                "OTHER"
              })
          String unlockReason,
      Instant occurredAt) {

    /**
     * The view of a stored action (the explanation never leaves the database).
     *
     * @param action the action
     * @return the view
     */
    public static ActionView of(TimesheetPeriod.Action action) {
      return new ActionView(
          action.id(),
          action.sequence(),
          action.kind().name(),
          action.scope().generationVersion(),
          Counts.of(action.totals()),
          action.evidenceDigest(),
          action.lockedActionId(),
          action.unlockReason() == null ? null : action.unlockReason().name(),
          action.occurredAt());
    }
  }

  /**
   * A lock or unlock command's result.
   *
   * @param outcome LOCKED, UNLOCKED or UNCHANGED (a lock of an already locked week by its current
   *     lock action)
   * @param scope the scope
   * @param generationId the generation
   * @param lock the lock state after the command
   * @param action the current action
   */
  @Schema(name = "TimesheetPeriodResult")
  public record Result(
      @Schema(allowableValues = {"LOCKED", "UNLOCKED", "UNCHANGED"}) String outcome,
      ScopeView scope,
      UUID generationId,
      LockStateView lock,
      ActionView action) {}

  /**
   * What an unlock would do (L71-6): the period, its current lock receipt and the reviewed reason.
   *
   * @param scope the scope
   * @param generationId the generation
   * @param lock the current lock state
   * @param currentLock the current LOCKED action
   * @param reasonCode the reviewed reason code
   * @param warnings neutral facts: EXPORTS_UNCHANGED (earlier exports stay as they are),
   *     APPROVALS_UNCHANGED (unlocking alone changes no approved timesheet)
   * @param previewDigest the digest to send with the unlock command
   * @param expiresAt the instant after which the preview is stale
   */
  @Schema(name = "TimesheetPeriodUnlockPreview")
  public record UnlockPreview(
      ScopeView scope,
      UUID generationId,
      LockStateView lock,
      ActionView currentLock,
      @Schema(
              allowableValues = {
                "ATTENDANCE_CORRECTION_REQUIRED",
                "TIMESHEET_EVIDENCE_ERROR",
                "ORGANIZATION_REQUEST",
                "OTHER"
              })
          String reasonCode,
      List<String> warnings,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the list. */
    public UnlockPreview {
      warnings = List.copyOf(warnings);
    }
  }

  /**
   * A generated week's lock state and immutable history, newest first.
   *
   * @param scope the scope
   * @param generationId the generation
   * @param generationVersion its current version
   * @param lock the current lock state
   * @param actions every lock and unlock, newest first
   */
  @Schema(name = "TimesheetPeriodHistory")
  public record History(
      ScopeView scope,
      UUID generationId,
      int generationVersion,
      LockStateView lock,
      List<ActionView> actions) {

    /** Copies the list. */
    public History {
      actions = List.copyOf(actions);
    }
  }
}
