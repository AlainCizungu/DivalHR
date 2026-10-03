package com.divalhr.core.people.api;

import com.divalhr.core.people.domain.history.AssignmentKind;
import com.divalhr.core.people.domain.history.ChangeReason;
import com.divalhr.core.people.domain.history.ChangeTiming;
import com.divalhr.core.people.domain.history.ChangeType;
import com.divalhr.core.people.domain.history.CompensationBasis;
import com.divalhr.core.people.domain.history.ContractClassification;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of the employee directory and employment history (MVP-021), mirroring the
 * contract schemas. They carry Confidential and Restricted HR data: served only with {@code
 * Cache-Control: private, no-store}, never logged ({@code toString} shows IDs only).
 */
public final class EmploymentHistoryResponses {

  private EmploymentHistoryResponses() {}

  /** Employment status on the business date. */
  public enum EmploymentStatus {
    /** The employment covers the business date. */
    CURRENT,
    /** It starts after the business date. */
    NOT_STARTED,
    /** It ended before the business date. */
    ENDED
  }

  /** Row status relative to the business date. */
  public enum AssignmentStatus {
    /** Covers the business date. */
    CURRENT,
    /** Starts after it. */
    SCHEDULED,
    /** Ended before it. */
    ENDED
  }

  /** Change state. */
  public enum ChangeState {
    /** Recorded and in force. */
    ACTIVE,
    /** Cancelled by a later cancellation. */
    CANCELLED
  }

  /** Preview warnings. */
  public enum PreviewWarning {
    /** A later row of the same kind bounds a new row. */
    LATER_CHANGE_LIMITS_PERIOD
  }

  /**
   * A directory entry.
   *
   * @param id employee ID
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   * @param employmentStatus status on the business date
   */
  @Schema(name = "EmployeeSummary")
  public record EmployeeSummary(
      UUID id,
      String employeeNumber,
      String givenNames,
      String familyName,
      EmploymentStatus employmentStatus) {

    @Override
    public String toString() {
      return "EmployeeSummary[" + id + "]";
    }
  }

  /**
   * A directory page.
   *
   * @param items entries
   * @param nextCursor continuation, or {@code null}
   */
  @Schema(name = "EmployeePage")
  public record EmployeePage(
      List<EmployeeSummary> items, @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {

    /** Copies the items. */
    public EmployeePage {
      items = List.copyOf(items);
    }
  }

  /**
   * An employment.
   *
   * @param id employment ID
   * @param startDate first day
   * @param endDate last day, or {@code null}
   * @param status status on the business date
   * @param version timeline version
   */
  @Schema(name = "Employment")
  public record Employment(
      UUID id,
      LocalDate startDate,
      @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate endDate,
      EmploymentStatus status,
      long version) {}

  /**
   * An organizational unit reference.
   *
   * @param id unit ID
   * @param code code
   * @param name name
   */
  @Schema(name = "UnitRef")
  public record UnitRef(UUID id, String code, String name) {}

  /**
   * A placement value.
   *
   * @param legalEntity legal entity
   * @param site site
   * @param department department, or {@code null}
   * @param costCenter cost center, or {@code null}
   * @param team team, or {@code null}
   */
  @Schema(name = "PlacementValue")
  public record PlacementValue(
      UnitRef legalEntity,
      UnitRef site,
      @JsonInclude(JsonInclude.Include.ALWAYS) UnitRef department,
      @JsonInclude(JsonInclude.Include.ALWAYS) UnitRef costCenter,
      @JsonInclude(JsonInclude.Include.ALWAYS) UnitRef team) {}

  /**
   * A manager value.
   *
   * @param employeeId manager's employee ID
   * @param employeeNumber manager's employee number
   * @param givenNames manager's given names
   * @param familyName manager's family name
   */
  @Schema(name = "ManagerValue")
  public record ManagerValue(
      UUID employeeId, String employeeNumber, String givenNames, String familyName) {

    @Override
    public String toString() {
      return "ManagerValue[" + employeeId + "]";
    }
  }

  /**
   * A stored assignment row.
   *
   * @param id row ID
   * @param kind kind
   * @param effectiveFrom first day
   * @param effectiveTo last day, or {@code null}
   * @param status status on the business date
   * @param changeId the change that recorded it
   * @param supersededAt when it was replaced, or {@code null}
   * @param placement placement value (PLACEMENT rows), else {@code null}
   * @param manager manager value (MANAGER rows), else {@code null}
   * @param contractClassification contract code (CONTRACT rows), else {@code null}
   * @param compensationBasis compensation code (COMPENSATION rows), else {@code null}
   */
  @Schema(name = "Assignment")
  public record Assignment(
      UUID id,
      AssignmentKind kind,
      LocalDate effectiveFrom,
      @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate effectiveTo,
      AssignmentStatus status,
      UUID changeId,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant supersededAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) PlacementValue placement,
      @JsonInclude(JsonInclude.Include.ALWAYS) ManagerValue manager,
      @JsonInclude(JsonInclude.Include.ALWAYS) ContractClassification contractClassification,
      @JsonInclude(JsonInclude.Include.ALWAYS) CompensationBasis compensationBasis) {

    @Override
    public String toString() {
      return "Assignment[" + id + "]";
    }
  }

  /**
   * Current rows per kind.
   *
   * @param placement placement row, or {@code null}
   * @param manager manager row, or {@code null}
   * @param contract contract row, or {@code null}
   * @param compensation compensation row, or {@code null}
   */
  @Schema(name = "CurrentAssignments")
  public record CurrentAssignments(
      @JsonInclude(JsonInclude.Include.ALWAYS) Assignment placement,
      @JsonInclude(JsonInclude.Include.ALWAYS) Assignment manager,
      @JsonInclude(JsonInclude.Include.ALWAYS) Assignment contract,
      @JsonInclude(JsonInclude.Include.ALWAYS) Assignment compensation) {}

  /**
   * An employee profile.
   *
   * @param id employee ID
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   * @param businessDate today in the organization's time zone
   * @param employment the employment
   * @param current current rows per kind
   */
  @Schema(name = "EmployeeProfile")
  public record EmployeeProfile(
      UUID id,
      String employeeNumber,
      String givenNames,
      String familyName,
      LocalDate businessDate,
      Employment employment,
      CurrentAssignments current) {

    @Override
    public String toString() {
      return "EmployeeProfile[" + id + "]";
    }
  }

  /**
   * A page of assignment rows.
   *
   * @param items rows
   * @param nextCursor continuation, or {@code null}
   */
  @Schema(name = "AssignmentPage")
  public record AssignmentPage(
      List<Assignment> items, @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {

    /** Copies the items. */
    public AssignmentPage {
      items = List.copyOf(items);
    }
  }

  /**
   * A recorded change.
   *
   * @param id change ID
   * @param type type
   * @param effectiveFrom effective date
   * @param kinds kinds
   * @param reasonCode reason, or {@code null}
   * @param timing timing, or {@code null} for the hire
   * @param state state
   * @param cancelsChangeId cancelled change, or {@code null}
   * @param recordedAt when it was recorded
   */
  @Schema(name = "EmploymentChange")
  public record EmploymentChange(
      UUID id,
      ChangeType type,
      LocalDate effectiveFrom,
      List<AssignmentKind> kinds,
      @JsonInclude(JsonInclude.Include.ALWAYS) ChangeReason reasonCode,
      @JsonInclude(JsonInclude.Include.ALWAYS) ChangeTiming timing,
      ChangeState state,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID cancelsChangeId,
      Instant recordedAt) {

    /** Copies the kinds. */
    public EmploymentChange {
      kinds = List.copyOf(kinds);
    }
  }

  /**
   * A page of changes.
   *
   * @param items changes
   * @param nextCursor continuation, or {@code null}
   */
  @Schema(name = "EmploymentChangePage")
  public record EmploymentChangePage(
      List<EmploymentChange> items, @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {

    /** Copies the items. */
    public EmploymentChangePage {
      items = List.copyOf(items);
    }
  }

  /**
   * A row before or after a change.
   *
   * @param assignmentId stored row ID, or {@code null} for a row to create
   * @param effectiveFrom first day
   * @param effectiveTo last day, or {@code null}
   * @param placement placement value, or {@code null}
   * @param manager manager value, or {@code null}
   * @param contractClassification contract code, or {@code null}
   * @param compensationBasis compensation code, or {@code null}
   */
  @Schema(name = "AssignmentPeriod")
  public record AssignmentPeriod(
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID assignmentId,
      LocalDate effectiveFrom,
      @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate effectiveTo,
      @JsonInclude(JsonInclude.Include.ALWAYS) PlacementValue placement,
      @JsonInclude(JsonInclude.Include.ALWAYS) ManagerValue manager,
      @JsonInclude(JsonInclude.Include.ALWAYS) ContractClassification contractClassification,
      @JsonInclude(JsonInclude.Include.ALWAYS) CompensationBasis compensationBasis) {}

  /**
   * One kind's rows before and after.
   *
   * @param kind kind
   * @param before rows replaced
   * @param after rows inserted
   */
  @Schema(name = "PreviewKind")
  public record PreviewKind(
      AssignmentKind kind, List<AssignmentPeriod> before, List<AssignmentPeriod> after) {

    /** Copies the rows. */
    public PreviewKind {
      before = List.copyOf(before);
      after = List.copyOf(after);
    }
  }

  /**
   * A change preview.
   *
   * @param expectedVersion employment version to repeat
   * @param timing timing
   * @param requiresReason whether a reason is needed
   * @param requiresAcknowledgement whether acknowledgeRetroactive must be true
   * @param kinds rows before and after per kind
   * @param warnings warnings
   * @param previewDigest digest to repeat
   */
  @Schema(name = "EmploymentChangePreview")
  public record EmploymentChangePreview(
      long expectedVersion,
      ChangeTiming timing,
      boolean requiresReason,
      boolean requiresAcknowledgement,
      List<PreviewKind> kinds,
      List<PreviewWarning> warnings,
      String previewDigest) {

    /** Copies the lists. */
    public EmploymentChangePreview {
      kinds = List.copyOf(kinds);
      warnings = List.copyOf(warnings);
    }
  }

  /**
   * A cancellation preview.
   *
   * @param expectedVersion employment version to repeat
   * @param kinds rows removed and restored per kind
   * @param cancellationDigest digest to repeat
   */
  @Schema(name = "EmploymentChangeCancellationPreview")
  public record EmploymentChangeCancellationPreview(
      long expectedVersion, List<PreviewKind> kinds, String cancellationDigest) {

    /** Copies the kinds. */
    public EmploymentChangeCancellationPreview {
      kinds = List.copyOf(kinds);
    }
  }

  /**
   * The result of a recorded change or cancellation.
   *
   * @param change the recorded change (or the cancellation)
   * @param employmentVersion the employment's new version
   */
  @Schema(name = "EmploymentChangeResult")
  public record EmploymentChangeResult(EmploymentChange change, long employmentVersion) {}
}
