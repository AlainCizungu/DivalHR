package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.domain.Substitution.Person;
import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.people.schedule.domain.Timesheet.Row;
import com.divalhr.core.people.schedule.domain.Timesheet.Totals;
import com.divalhr.core.people.schedule.domain.TimesheetApproval.Action;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * MVP-070A response bodies (T70A-2, T70A-9): the site-local week with its exact UTC boundaries,
 * counts, generations, drafts, rows and revisions. Employees' own views carry no identifier of the
 * schedule, employment, site, cost center or attendance record, and nothing of who generated them.
 */
public final class TimesheetResponses {

  private TimesheetResponses() {}

  /**
   * The site-local week.
   *
   * @param siteId site
   * @param siteCode site code
   * @param siteName site name
   * @param timezone the site's IANA time zone
   * @param weekStart the local Monday
   * @param weekEnd the next local Monday (exclusive)
   * @param lastDay the local Sunday
   * @param periodStartAt Monday 00:00 at the site
   * @param periodEndAt the next Monday 00:00 at the site
   */
  @Schema(name = "TimesheetPeriod")
  public record Period(
      UUID siteId,
      String siteCode,
      String siteName,
      String timezone,
      LocalDate weekStart,
      LocalDate weekEnd,
      LocalDate lastDay,
      Instant periodStartAt,
      Instant periodEndAt) {

    /**
     * The view of a week at a site.
     *
     * @param period the week
     * @param site the site, or {@code null} when it is no longer resolvable
     * @return the view
     */
    public static Period of(Timesheet.Period period, UnitView site) {
      return new Period(
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
   * Row counts by state and the READY rows' worked minutes.
   *
   * @param rows rows
   * @param readyRows READY rows
   * @param missingRows MISSING_ATTENDANCE rows
   * @param openRows OPEN_ATTENDANCE rows
   * @param changedRows SOURCE_CHANGED rows
   * @param blockedRows rows that block a later submission
   * @param workedMinutes worked minutes of READY rows
   */
  @Schema(name = "TimesheetTotals")
  public record TotalsView(
      int rows,
      int readyRows,
      int missingRows,
      int openRows,
      int changedRows,
      int blockedRows,
      int workedMinutes) {

    /**
     * The view of totals.
     *
     * @param totals totals
     * @return the view
     */
    public static TotalsView of(Totals totals) {
      return new TotalsView(
          totals.rows(),
          totals.ready(),
          totals.missing(),
          totals.open(),
          totals.changed(),
          totals.blocked(),
          totals.workedMinutes());
    }
  }

  /**
   * A generated week.
   *
   * @param generationId generation
   * @param period the week
   * @param status DRAFT
   * @param version run version
   * @param generatedAt generation instant
   * @param refreshedAt latest refresh, or {@code null}
   * @param drafts number of drafts
   * @param totals current totals of every draft
   */
  @Schema(name = "TimesheetGenerationSummary")
  public record GenerationSummary(
      UUID generationId,
      Period period,
      @Schema(allowableValues = "DRAFT") String status,
      int version,
      Instant generatedAt,
      Instant refreshedAt,
      int drafts,
      TotalsView totals) {}

  /**
   * What a generation would hold now.
   *
   * @param period the week
   * @param generation the existing generation, or {@code null}
   * @param drafts drafts it would create
   * @param totals rows it would create
   * @param maxDrafts the per-request draft bound
   * @param maxRows the per-request row bound
   */
  @Schema(name = "TimesheetGenerationPreview")
  public record GenerationPreview(
      Period period,
      @Schema(nullable = true) GenerationSummary generation,
      int drafts,
      TotalsView totals,
      int maxDrafts,
      int maxRows) {}

  /**
   * A generation command's result: newly generated, or the week's existing generation.
   *
   * @param outcome CREATED or EXISTING
   * @param generation the generation
   */
  @Schema(name = "TimesheetGenerationResult")
  public record GenerationResult(
      @Schema(allowableValues = {"CREATED", "EXISTING"}) String outcome,
      GenerationSummary generation) {}

  /**
   * An employee as the administrator sees them.
   *
   * @param id employee
   * @param employeeNumber number
   * @param givenNames given names
   * @param familyName family name
   */
  @Schema(name = "TimesheetEmployee")
  public record EmployeeView(UUID id, String employeeNumber, String givenNames, String familyName) {

    /**
     * The view of a person.
     *
     * @param person person
     * @return the view
     */
    public static EmployeeView of(Person person) {
      return new EmployeeView(
          person.id(), person.employeeNumber(), person.givenNames(), person.familyName());
    }
  }

  /**
   * One draft in a generation.
   *
   * @param timesheetId timesheet
   * @param employee employee
   * @param status DRAFT
   * @param revision current revision
   * @param totals current totals
   * @param updatedAt latest revision instant
   */
  @Schema(name = "TimesheetDraftItem")
  public record DraftItem(
      UUID timesheetId,
      EmployeeView employee,
      @Schema(allowableValues = {"DRAFT", "SUBMITTED", "APPROVED"}) String status,
      int revision,
      TotalsView totals,
      Instant updatedAt) {}

  /**
   * A generated week with its drafts.
   *
   * @param generation the generation
   * @param timesheets its drafts (at most 500)
   */
  @Schema(name = "TimesheetGeneration")
  public record GenerationView(GenerationSummary generation, List<DraftItem> timesheets) {

    /** Copies the list. */
    public GenerationView {
      timesheets = List.copyOf(timesheets);
    }
  }

  /**
   * What a refresh would change now.
   *
   * @param generationId generation
   * @param version the version to send with the commit
   * @param period the week
   * @param previewDigest the digest to send with the commit
   * @param newDrafts drafts it would create
   * @param changedTimesheets drafts that would receive a new revision
   * @param unchangedTimesheets drafts that would keep their revision
   * @param changedRows rows added, removed or changed
   * @param totals every draft's totals after the refresh
   */
  @Schema(name = "TimesheetRefreshPreview")
  public record RefreshPreview(
      UUID generationId,
      int version,
      Period period,
      String previewDigest,
      int newDrafts,
      int changedTimesheets,
      int unchangedTimesheets,
      int changedRows,
      TotalsView totals) {}

  /**
   * A committed refresh.
   *
   * @param generation the generation after the refresh
   * @param newDrafts drafts created
   * @param changedTimesheets drafts that received a new revision
   * @param changedRows rows added, removed or changed
   */
  @Schema(name = "TimesheetRefreshResult")
  public record RefreshResult(
      GenerationSummary generation, int newDrafts, int changedTimesheets, int changedRows) {}

  /**
   * Bilingual labels.
   *
   * @param en English
   * @param fr French
   */
  @Schema(name = "TimesheetLabels")
  public record Labels(String en, String fr) {}

  /**
   * A cost center as the administrator sees it.
   *
   * @param id cost center
   * @param code code
   * @param name name
   */
  @Schema(name = "TimesheetCostCenter")
  public record CostCenter(UUID id, String code, String name) {}

  /**
   * One row as the administrator sees it: the snapshot of the slot and its source facts.
   *
   * @param slotId slot
   * @param scheduleId schedule
   * @param employmentId qualifying employment
   * @param siteId site
   * @param costCenter the placement's cost center, or {@code null}
   * @param labels the slot's labels
   * @param scheduledStartAt scheduled start
   * @param scheduledEndAt scheduled end
   * @param state row state
   * @param attendanceRecordId effective attendance record, or {@code null}
   * @param checkedInAt effective check-in, or {@code null}
   * @param checkedOutAt effective check-out, or {@code null}
   * @param correctionVersion the record's correction version, or {@code null}
   * @param corrected whether the effective values come from an attendance correction
   * @param workedMinutes worked minutes (READY), or {@code null}
   */
  @Schema(name = "TimesheetRow")
  public record RowView(
      UUID slotId,
      UUID scheduleId,
      UUID employmentId,
      UUID siteId,
      @Schema(nullable = true) CostCenter costCenter,
      Labels labels,
      Instant scheduledStartAt,
      Instant scheduledEndAt,
      @Schema(
              allowableValues = {
                "READY",
                "MISSING_ATTENDANCE",
                "OPEN_ATTENDANCE",
                "SOURCE_CHANGED"
              })
          String state,
      @Schema(nullable = true) UUID attendanceRecordId,
      @Schema(nullable = true) Instant checkedInAt,
      @Schema(nullable = true) Instant checkedOutAt,
      @Schema(nullable = true) Integer correctionVersion,
      boolean corrected,
      @Schema(nullable = true) Integer workedMinutes) {

    /**
     * The administrator's view of a row.
     *
     * @param row row
     * @param costCenter the resolved cost center, or {@code null}
     * @return the view
     */
    public static RowView of(Row row, UnitView costCenter) {
      var s = row.source();
      return new RowView(
          s.slotId(),
          s.scheduleId(),
          s.employmentId(),
          s.siteId(),
          s.costCenterId() == null
              ? null
              : new CostCenter(
                  s.costCenterId(),
                  costCenter == null ? null : costCenter.code(),
                  costCenter == null ? null : costCenter.name()),
          new Labels(s.labelEn(), s.labelFr()),
          s.scheduledStartAt(),
          s.scheduledEndAt(),
          row.state().name(),
          s.recordId(),
          s.checkedInAt(),
          s.checkedOutAt(),
          s.correctionVersion(),
          s.correctionVersion() != null && s.correctionVersion() > 0,
          row.workedMinutes());
    }
  }

  /**
   * One revision header.
   *
   * @param revision number
   * @param generationVersion the run version that wrote it
   * @param createdAt instant
   * @param totals its totals
   */
  @Schema(name = "TimesheetRevision")
  public record RevisionView(
      int revision, int generationVersion, Instant createdAt, TotalsView totals) {}

  /**
   * One draft as the administrator sees it.
   *
   * @param timesheetId timesheet
   * @param generationId generation
   * @param employee employee
   * @param period the week
   * @param status DRAFT, SUBMITTED or APPROVED
   * @param revision current revision
   * @param submissionCycle the latest submission cycle (0 before the first)
   * @param totals current totals
   * @param rows the current revision's rows
   * @param revisions every revision, newest first
   * @param actions every submission and decision, newest first
   */
  @Schema(name = "TimesheetDetail")
  public record Detail(
      UUID timesheetId,
      UUID generationId,
      EmployeeView employee,
      Period period,
      @Schema(allowableValues = {"DRAFT", "SUBMITTED", "APPROVED"}) String status,
      int revision,
      int submissionCycle,
      TotalsView totals,
      List<RowView> rows,
      List<RevisionView> revisions,
      List<ActionView> actions) {

    /** Copies the lists. */
    public Detail {
      rows = List.copyOf(rows);
      revisions = List.copyOf(revisions);
      actions = List.copyOf(actions);
    }
  }

  /**
   * One of the employee's own drafts.
   *
   * @param timesheetId timesheet
   * @param period the week
   * @param status DRAFT, SUBMITTED or APPROVED
   * @param revision current revision
   * @param submissionCycle the latest submission cycle (0 before the first)
   * @param totals current totals
   * @param updatedAt latest revision instant
   */
  @Schema(name = "MyTimesheetItem")
  public record MyItem(
      UUID timesheetId,
      Period period,
      @Schema(allowableValues = {"DRAFT", "SUBMITTED", "APPROVED"}) String status,
      int revision,
      int submissionCycle,
      TotalsView totals,
      Instant updatedAt) {}

  /**
   * One page of the employee's own drafts, newest week first.
   *
   * @param items drafts
   * @param nextCursor opaque cursor of the next page, or {@code null}
   */
  @Schema(name = "MyTimesheetPage")
  public record MyPage(List<MyItem> items, @Schema(nullable = true) String nextCursor) {

    /** Copies the list. */
    public MyPage {
      items = List.copyOf(items);
    }
  }

  /**
   * One row as the employee sees it.
   *
   * @param labels the slot's labels
   * @param scheduledStartAt scheduled start
   * @param scheduledEndAt scheduled end
   * @param state row state
   * @param checkedInAt effective check-in, or {@code null}
   * @param checkedOutAt effective check-out, or {@code null}
   * @param corrected whether the organization corrected the attendance
   * @param workedMinutes worked minutes (READY), or {@code null}
   */
  @Schema(name = "MyTimesheetRow")
  public record MyRow(
      Labels labels,
      Instant scheduledStartAt,
      Instant scheduledEndAt,
      @Schema(
              allowableValues = {
                "READY",
                "MISSING_ATTENDANCE",
                "OPEN_ATTENDANCE",
                "SOURCE_CHANGED"
              })
          String state,
      @Schema(nullable = true) Instant checkedInAt,
      @Schema(nullable = true) Instant checkedOutAt,
      boolean corrected,
      @Schema(nullable = true) Integer workedMinutes) {

    /**
     * The employee's view of a row.
     *
     * @param row row
     * @return the view
     */
    public static MyRow of(Row row) {
      var s = row.source();
      return new MyRow(
          new Labels(s.labelEn(), s.labelFr()),
          s.scheduledStartAt(),
          s.scheduledEndAt(),
          row.state().name(),
          s.checkedInAt(),
          s.checkedOutAt(),
          s.correctionVersion() != null && s.correctionVersion() > 0,
          row.workedMinutes());
    }
  }

  /**
   * One of the employee's own drafts with its rows.
   *
   * @param timesheetId timesheet
   * @param period the week
   * @param status DRAFT, SUBMITTED or APPROVED
   * @param revision current revision
   * @param generationVersion the generation's current version
   * @param submissionCycle the latest submission cycle (0 before the first)
   * @param totals current totals
   * @param updatedAt latest revision instant
   * @param rows the current revision's rows
   * @param submission whether it can be submitted now, and what blocks it
   * @param actions every submission and decision, newest first
   */
  @Schema(name = "MyTimesheet")
  public record MyDetail(
      UUID timesheetId,
      Period period,
      @Schema(allowableValues = {"DRAFT", "SUBMITTED", "APPROVED"}) String status,
      int revision,
      int generationVersion,
      int submissionCycle,
      TotalsView totals,
      Instant updatedAt,
      List<MyRow> rows,
      SubmissionView submission,
      List<MyActionView> actions) {

    /** Copies the lists. */
    public MyDetail {
      rows = List.copyOf(rows);
      actions = List.copyOf(actions);
    }
  }

  // ------------------------------------------------------------------------------------------
  // MVP-070B/C: submissions and decisions
  // ------------------------------------------------------------------------------------------

  /**
   * The immutable action a command appended.
   *
   * @param timesheetId timesheet
   * @param actionId action
   * @param action SUBMITTED, RETURNED or APPROVED
   * @param status the timesheet's resulting state
   * @param cycle the action's cycle
   * @param revision the bound revision
   * @param generationVersion the bound generation version
   * @param occurredAt instant
   */
  @Schema(name = "TimesheetActionReceipt")
  public record ActionReceipt(
      UUID timesheetId,
      UUID actionId,
      @Schema(allowableValues = {"SUBMITTED", "RETURNED", "APPROVED"}) String action,
      @Schema(allowableValues = {"DRAFT", "SUBMITTED", "APPROVED"}) String status,
      int cycle,
      int revision,
      int generationVersion,
      Instant occurredAt) {

    /**
     * The receipt of an action.
     *
     * @param action the action
     * @return the receipt
     */
    public static ActionReceipt of(Action action) {
      return new ActionReceipt(
          action.timesheetId(),
          action.id(),
          action.kind().name(),
          action.kind().state().name(),
          action.cycle(),
          action.revision(),
          action.generationVersion(),
          action.occurredAt());
    }
  }

  /**
   * One action as a tenant administrator or the routed manager sees it: never a subject or a
   * manager's identity.
   *
   * @param actionId action
   * @param action SUBMITTED, RETURNED or APPROVED
   * @param cycle cycle
   * @param revision bound revision
   * @param generationVersion bound generation version
   * @param rowCount the revision's rows
   * @param workedMinutes the revision's worked minutes
   * @param returnReason a return's reason, or {@code null}
   * @param occurredAt instant
   */
  @Schema(name = "TimesheetAction")
  public record ActionView(
      UUID actionId,
      @Schema(allowableValues = {"SUBMITTED", "RETURNED", "APPROVED"}) String action,
      int cycle,
      int revision,
      int generationVersion,
      int rowCount,
      int workedMinutes,
      @Schema(nullable = true) String returnReason,
      Instant occurredAt) {

    /**
     * The view of an action.
     *
     * @param action the action
     * @return the view
     */
    public static ActionView of(Action action) {
      return new ActionView(
          action.id(),
          action.kind().name(),
          action.cycle(),
          action.revision(),
          action.generationVersion(),
          action.totals().rows(),
          action.totals().workedMinutes(),
          action.returnReason(),
          action.occurredAt());
    }
  }

  /**
   * One action as the employee sees it: never who decided.
   *
   * @param action SUBMITTED, RETURNED or APPROVED
   * @param cycle cycle
   * @param revision bound revision
   * @param workedMinutes the revision's worked minutes
   * @param returnReason a return's reason, or {@code null}
   * @param occurredAt instant
   */
  @Schema(name = "MyTimesheetAction")
  public record MyActionView(
      @Schema(allowableValues = {"SUBMITTED", "RETURNED", "APPROVED"}) String action,
      int cycle,
      int revision,
      int workedMinutes,
      @Schema(nullable = true) String returnReason,
      Instant occurredAt) {

    /**
     * The employee's view of an action.
     *
     * @param action the action
     * @return the view
     */
    public static MyActionView of(Action action) {
      return new MyActionView(
          action.kind().name(),
          action.cycle(),
          action.revision(),
          action.totals().workedMinutes(),
          action.returnReason(),
          action.occurredAt());
    }
  }

  /**
   * Whether the employee can submit the draft now, and what blocks it (never names a manager).
   *
   * @param eligible whether a submission would be accepted now
   * @param blockers what blocks it
   */
  @Schema(name = "MyTimesheetSubmission")
  public record SubmissionView(
      boolean eligible,
      @ArraySchema(
              schema =
                  @Schema(
                      allowableValues = {
                        "MISSING_ATTENDANCE",
                        "OPEN_ATTENDANCE",
                        "SOURCE_CHANGED",
                        "NO_READY_ROWS",
                        "APPROVAL_ROUTE_UNAVAILABLE"
                      }))
          List<String> blockers) {

    /** Copies the list. */
    public SubmissionView {
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * The submitting employee as the routed manager sees them.
   *
   * @param employeeNumber number
   * @param givenNames current given names
   * @param familyName current family name
   */
  @Schema(name = "TimeApprovalEmployee")
  public record ApprovalEmployee(String employeeNumber, String givenNames, String familyName) {

    /**
     * The manager's view of an employee.
     *
     * @param person employee
     * @return the view
     */
    public static ApprovalEmployee of(Person person) {
      return new ApprovalEmployee(
          person.employeeNumber(), person.givenNames(), person.familyName());
    }
  }

  /**
   * One submitted timesheet in the routed manager's queue.
   *
   * @param timesheetId timesheet
   * @param employee employee
   * @param period the week
   * @param revision submitted revision
   * @param cycle submission cycle
   * @param rowCount rows
   * @param workedMinutes worked minutes
   * @param submittedAt submission instant
   */
  @Schema(name = "TimeApprovalItem")
  public record ApprovalItem(
      UUID timesheetId,
      ApprovalEmployee employee,
      Period period,
      int revision,
      int cycle,
      int rowCount,
      int workedMinutes,
      Instant submittedAt) {}

  /**
   * One page of the routed manager's queue, newest submission first.
   *
   * @param items submitted timesheets
   * @param nextCursor opaque cursor of the next page, or {@code null}
   */
  @Schema(name = "TimeApprovalPage")
  public record ApprovalPage(List<ApprovalItem> items, @Schema(nullable = true) String nextCursor) {

    /** Copies the list. */
    public ApprovalPage {
      items = List.copyOf(items);
    }
  }

  /**
   * A submitted timesheet for its routed manager.
   *
   * @param timesheetId timesheet
   * @param employee employee
   * @param period the week
   * @param status SUBMITTED
   * @param revision submitted revision
   * @param generationVersion the generation version bound by the submission
   * @param cycle submission cycle
   * @param submissionActionId the submission to bind a decision to
   * @param submittedAt submission instant
   * @param totals the submitted revision's totals
   * @param rows the submitted revision's rows
   * @param actions every submission and decision, newest first
   */
  @Schema(name = "TimeApprovalDetail")
  public record ApprovalDetail(
      UUID timesheetId,
      ApprovalEmployee employee,
      Period period,
      @Schema(allowableValues = "SUBMITTED") String status,
      int revision,
      int generationVersion,
      int cycle,
      UUID submissionActionId,
      Instant submittedAt,
      TotalsView totals,
      List<MyRow> rows,
      List<ActionView> actions) {

    /** Copies the lists. */
    public ApprovalDetail {
      rows = List.copyOf(rows);
      actions = List.copyOf(actions);
    }
  }
}
