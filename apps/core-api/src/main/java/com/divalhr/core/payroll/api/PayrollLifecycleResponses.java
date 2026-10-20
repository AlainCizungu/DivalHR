package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PayrollInputResponses.Counts;
import com.divalhr.core.payroll.api.PayrollInputResponses.ScopeView;
import com.divalhr.core.payroll.api.PayrollInputResponses.SourceWeekView;
import com.divalhr.core.payroll.api.PayrollInputResponses.Summary;
import com.divalhr.core.payroll.api.PayrollInputResponses.WeekView;
import com.divalhr.core.payroll.domain.PayrollInputs;
import com.divalhr.core.payroll.domain.PayrollLifecycle;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes of MVP-092 (versions, lock and worked-time export). Identifiers, codes, dates,
 * counts and minutes only: no name of a person, no employee number, no subject and no amount. The
 * fixed monthly-salary notices are part of the contract's description and of the localized
 * surfaces; no response carries a calculated pay figure.
 */
public final class PayrollLifecycleResponses {

  private PayrollLifecycleResponses() {}

  /**
   * What a superseding version would hold now (nothing is written by a preview).
   *
   * @param current the run that would be replaced
   * @param weeks every week the period touches
   * @param eligible the rows the new version would hold
   * @param blockers stable blocker codes (empty when ready)
   * @param ready whether a version can be created now
   * @param previewDigest the preview's digest (bound to the run, the evidence and the expiry)
   * @param expiresAt when the preview expires
   */
  @Schema(name = "PayrollInputSupersedePreview")
  public record SupersedePreview(
      Summary current,
      List<WeekView> weeks,
      Counts eligible,
      List<String> blockers,
      boolean ready,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the lists. */
    public SupersedePreview {
      weeks = List.copyOf(weeks);
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * A new version that replaced its predecessor.
   *
   * @param outcome always SUPERSEDED
   * @param run the new version
   * @param supersedesRunId the replaced run
   */
  @Schema(name = "PayrollInputSupersedeResult")
  public record SupersedeResult(
      @Schema(allowableValues = {"SUPERSEDED"}) String outcome,
      Summary run,
      UUID supersedesRunId) {}

  /**
   * What locking a run would make final.
   *
   * @param run the run
   * @param weeks the locked source weeks
   * @param counts employees, rows and worked minutes
   * @param workedMinuteBand the band of the minutes
   * @param supersedesRunId the earlier version this run supersedes, or {@code null}
   * @param supersedesVersion that version, or {@code null}
   * @param blockers stable blocker codes (empty when ready)
   * @param ready whether the run can be locked now
   * @param previewDigest the preview's digest
   * @param expiresAt when the preview expires
   */
  @Schema(name = "PayrollInputLockPreview")
  public record LockPreview(
      Summary run,
      List<SourceWeekView> weeks,
      Counts counts,
      String workedMinuteBand,
      @Schema(nullable = true) UUID supersedesRunId,
      @Schema(nullable = true) Integer supersedesVersion,
      List<String> blockers,
      boolean ready,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the lists. */
    public LockPreview {
      weeks = List.copyOf(weeks);
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * A locked run.
   *
   * @param outcome always LOCKED
   * @param run the run
   */
  @Schema(name = "PayrollInputLockResult")
  public record LockResult(@Schema(allowableValues = {"LOCKED"}) String outcome, Summary run) {}

  /**
   * One version of the chain.
   *
   * @param run the version
   * @param explanation the bounded explanation of OTHER, or {@code null}
   */
  @Schema(name = "PayrollInputVersion")
  public record Version(Summary run, @Schema(nullable = true) String explanation) {}

  /**
   * The whole chain of one scope, oldest first. Nothing is hidden, edited or deleted.
   *
   * @param items the versions
   */
  @Schema(name = "PayrollInputVersions")
  public record Versions(List<Version> items) {

    /** Copies the list. */
    public Versions {
      items = List.copyOf(items);
    }
  }

  /**
   * A worked-time export: facts only; the bytes are the downloads.
   *
   * @param exportId export
   * @param schemaVersion the export schema version
   * @param runId the locked run
   * @param runVersion its version
   * @param scope the scope
   * @param lockActionId the run's LOCKED action
   * @param lockVersion the lock version
   * @param employees employee and employment pairs
   * @param rows rows
   * @param workedMinutes total approved worked minutes
   * @param workedMinuteBand the band of the minutes
   * @param csvSha256 SHA-256 of the exact CSV bytes
   * @param createdAt creation instant
   */
  @Schema(name = "PayrollInputExport")
  public record ExportView(
      UUID exportId,
      int schemaVersion,
      UUID runId,
      int runVersion,
      ScopeView scope,
      UUID lockActionId,
      int lockVersion,
      int employees,
      int rows,
      int workedMinutes,
      String workedMinuteBand,
      String csvSha256,
      Instant createdAt) {

    /**
     * The view of a stored export.
     *
     * @param export the export
     * @param legalEntity the legal entity, or {@code null}
     * @return the view
     */
    public static ExportView of(PayrollLifecycle.Export export, UnitView legalEntity) {
      return new ExportView(
          export.id(),
          PayrollLifecycle.EXPORT_SCHEMA_VERSION,
          export.runId(),
          export.runVersion(),
          ScopeView.of(export.scope(), legalEntity),
          export.lockId(),
          export.lockVersion(),
          export.employees(),
          export.rows(),
          export.workedMinutes(),
          PayrollInputs.minuteBand(export.workedMinutes()),
          export.csvSha256(),
          export.createdAt());
    }
  }

  /**
   * A new export, or the run's existing one.
   *
   * @param outcome CREATED or EXISTING
   * @param export the export
   */
  @Schema(name = "PayrollInputExportResult")
  public record ExportResult(
      @Schema(allowableValues = {"CREATED", "EXISTING"}) String outcome, ExportView export) {}

  /**
   * One page of exports, newest first.
   *
   * @param items exports
   * @param nextCursor opaque cursor of the next page, or {@code null}
   */
  @Schema(name = "PayrollInputExportPage")
  public record ExportPage(List<ExportView> items, @Schema(nullable = true) String nextCursor) {

    /** Copies the list. */
    public ExportPage {
      items = List.copyOf(items);
    }
  }

  /**
   * A downloadable file: the bytes and the approved file name.
   *
   * @param filename the attachment name
   * @param bytes the exact bytes
   */
  public record Download(String filename, byte[] bytes) {

    /** Copies the bytes. */
    public Download {
      bytes = bytes.clone();
    }

    /**
     * The bytes (a copy).
     *
     * @return bytes
     */
    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
