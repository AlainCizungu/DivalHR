package com.divalhr.core.people.api;

import com.divalhr.core.people.api.EmploymentHistoryResponses.PreviewKind;
import com.divalhr.core.people.domain.history.AssignmentKind;
import com.divalhr.core.people.domain.history.ChangeTiming;
import com.divalhr.core.people.domain.separation.AccessTiming;
import com.divalhr.core.people.domain.separation.Acknowledgement;
import com.divalhr.core.people.domain.separation.ReportAction;
import com.divalhr.core.people.domain.separation.SeparationReason;
import com.divalhr.core.people.domain.separation.SeparationState;
import com.divalhr.core.people.domain.separation.TaskCode;
import com.divalhr.core.people.domain.separation.TaskStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of separations (MVP-022), mirroring the contract schemas. They carry Confidential
 * and Restricted HR data: served only with {@code Cache-Control: private, no-store}, never logged.
 */
public final class SeparationResponses {

  private SeparationResponses() {}

  /** Access consequence shown by a preview. */
  public enum AccessStatus {
    /** An employee-role membership is linked and will be revoked. */
    LINKED,
    /** No DivalHR access is linked. */
    NOT_LINKED,
    /** The linked membership is a tenant administrator: refused. */
    PROTECTED_ADMIN,
    /** The caller's own access: refused. */
    SELF
  }

  /** How a blocker is resolved. */
  public enum BlockerResolution {
    /** Cancel {@code changeId} with the MVP-021 cancellation. */
    CANCEL_CHANGE,
    /** No cancellable change: the effect cannot be removed here. */
    NOT_CANCELLABLE
  }

  /** Separation access state. */
  public enum AccessState {
    /** No access was linked. */
    NOT_LINKED,
    /** Access ends at {@code accessEndsAt}. */
    SCHEDULED,
    /** DivalHR access is denied; sign-in removal is in progress. */
    SIGN_OUT_PENDING,
    /** DivalHR access is denied and sign-in removed. */
    COMPLETED,
    /** DivalHR access is denied; sign-in removal needs an administrator. */
    MANUAL_INTERVENTION,
    /** Cancelled before access ended. */
    CANCELLED
  }

  /**
   * An employee reference (Confidential).
   *
   * @param id employee
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   */
  @Schema(name = "EmployeeRef")
  public record EmployeeRef(UUID id, String employeeNumber, String givenNames, String familyName) {

    @Override
    public String toString() {
      return "EmployeeRef[" + id + "]";
    }
  }

  /**
   * A future effect to cancel first.
   *
   * @param kind kind
   * @param effectiveFrom its start
   * @param resolution resolution
   * @param changeId the change to cancel next, or {@code null}
   */
  @Schema(name = "SeparationBlocker")
  public record Blocker(
      AssignmentKind kind,
      LocalDate effectiveFrom,
      BlockerResolution resolution,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID changeId) {}

  /**
   * One affected direct-report interval.
   *
   * @param effectiveFrom first day (on or after the day after the last day)
   * @param effectiveTo last day, or {@code null}
   */
  @Schema(name = "SeparationReportInterval")
  public record ReportInterval(
      LocalDate effectiveFrom, @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate effectiveTo) {}

  /**
   * A direct report and its affected intervals.
   *
   * @param employee the report
   * @param intervals intervals
   */
  @Schema(name = "SeparationReport")
  public record Report(EmployeeRef employee, List<ReportInterval> intervals) {

    /** Copies the intervals. */
    public Report {
      intervals = List.copyOf(intervals);
    }
  }

  /**
   * The access consequence.
   *
   * @param status status
   * @param accessEndsAt when DivalHR access ends, or {@code null} when nothing is linked
   */
  @Schema(name = "SeparationAccessPreview")
  public record AccessPreview(
      AccessStatus status, @JsonInclude(JsonInclude.Include.ALWAYS) Instant accessEndsAt) {}

  /**
   * One checklist item to create.
   *
   * @param code code
   * @param dueDate due date
   */
  public record ChecklistItem(TaskCode code, LocalDate dueDate) {}

  /**
   * A separation preview.
   *
   * @param employmentId employment
   * @param expectedVersion employment version to repeat
   * @param businessDate today in the organization's time zone
   * @param lastDay last day
   * @param timing timing of the last day relative to today
   * @param accessTiming access timing
   * @param kinds the employee's own rows before and after
   * @param blockers future effects to cancel first
   * @param reports affected direct reports
   * @param reportPlanRequired whether a plan is required
   * @param access access consequence
   * @param checklist follow-up tasks to create
   * @param requiredAcknowledgements acknowledgements the commit needs
   * @param previewDigest digest to repeat
   */
  @Schema(name = "SeparationPreview")
  public record SeparationPreview(
      UUID employmentId,
      long expectedVersion,
      LocalDate businessDate,
      LocalDate lastDay,
      ChangeTiming timing,
      AccessTiming accessTiming,
      List<PreviewKind> kinds,
      List<Blocker> blockers,
      List<Report> reports,
      boolean reportPlanRequired,
      AccessPreview access,
      List<ChecklistItem> checklist,
      List<Acknowledgement> requiredAcknowledgements,
      String previewDigest) {

    /** Copies the lists. */
    public SeparationPreview {
      kinds = List.copyOf(kinds);
      blockers = List.copyOf(blockers);
      reports = List.copyOf(reports);
      checklist = List.copyOf(checklist);
      requiredAcknowledgements = List.copyOf(requiredAcknowledgements);
    }
  }

  /**
   * A follow-up task.
   *
   * @param id task
   * @param code code
   * @param status status
   * @param dueDate due date
   * @param updatedAt last change
   * @param version version
   */
  @Schema(name = "SeparationTask")
  public record Task(
      UUID id,
      TaskCode code,
      TaskStatus status,
      LocalDate dueDate,
      Instant updatedAt,
      long version) {}

  /**
   * A separation.
   *
   * @param id separation
   * @param employmentId employment
   * @param lastDay last day
   * @param reasonCode reason
   * @param accessTiming access timing
   * @param reportAction direct-report action, or {@code null}
   * @param reportCount reports affected
   * @param intervalCount intervals affected
   * @param state state
   * @param effectiveAt start of the day after the last day
   * @param recordedAt when recorded
   * @param cancelledAt when cancelled, or {@code null}
   * @param cancellable whether it can still be cancelled
   * @param access access state
   * @param accessEndsAt when DivalHR access ends, or {@code null}
   * @param tasks follow-up tasks
   * @param version version
   */
  @Schema(name = "Separation")
  public record Separation(
      UUID id,
      UUID employmentId,
      LocalDate lastDay,
      SeparationReason reasonCode,
      AccessTiming accessTiming,
      @JsonInclude(JsonInclude.Include.ALWAYS) ReportAction reportAction,
      int reportCount,
      int intervalCount,
      SeparationState state,
      Instant effectiveAt,
      Instant recordedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant cancelledAt,
      boolean cancellable,
      AccessState access,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant accessEndsAt,
      List<Task> tasks,
      long version) {

    /** Copies the tasks. */
    public Separation {
      tasks = List.copyOf(tasks);
    }
  }

  /**
   * An employee's separations.
   *
   * @param items separations, newest first
   */
  @Schema(name = "SeparationList")
  public record SeparationList(List<Separation> items) {

    /** Copies the items. */
    public SeparationList {
      items = List.copyOf(items);
    }
  }

  /**
   * The result of a separation or its cancellation.
   *
   * @param separation the separation
   * @param employmentVersion the employment's new version
   */
  @Schema(name = "SeparationResult")
  public record SeparationResult(Separation separation, long employmentVersion) {}

  /**
   * A cancellation preview.
   *
   * @param expectedVersion employment version to repeat
   * @param kinds the employee's rows removed and restored
   * @param reportCount reports whose intervals are restored
   * @param intervalCount intervals restored
   * @param cancellationDigest digest to repeat
   */
  @Schema(name = "SeparationCancellationPreview")
  public record SeparationCancellationPreview(
      long expectedVersion,
      List<PreviewKind> kinds,
      int reportCount,
      int intervalCount,
      String cancellationDigest) {

    /** Copies the kinds. */
    public SeparationCancellationPreview {
      kinds = List.copyOf(kinds);
    }
  }
}
