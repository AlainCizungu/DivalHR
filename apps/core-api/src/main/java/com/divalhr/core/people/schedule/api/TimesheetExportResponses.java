package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.domain.Timesheet;
import com.divalhr.core.people.schedule.domain.TimesheetExport;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * MVP-070E response bodies (T70E-2, T70E-5, T70E-6): the exported scope, the preview counts and the
 * export summary (the manifest's facts). No name, employee number, subject, label or attendance
 * instant appears in any of them; the rows exist only in the CSV.
 */
public final class TimesheetExportResponses {

  private TimesheetExportResponses() {}

  /**
   * The exported scope: the legal entity, its site and the generated week with its exact
   * boundaries.
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
  @Schema(name = "TimesheetExportScope")
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
    public static ScopeView of(TimesheetExport.Scope scope, UnitView legalEntity, UnitView site) {
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
   * The generation's timesheets that are not exported, by state.
   *
   * @param draft DRAFT (never submitted, returned, or refreshed after a reopening)
   * @param submitted SUBMITTED (awaiting a decision)
   * @param reopened REOPENED (an approval superseded by a correction)
   */
  @Schema(name = "TimesheetExportExclusions")
  public record Exclusions(int draft, int submitted, int reopened) {}

  /**
   * Counts and total of an export.
   *
   * @param timesheets approved timesheets
   * @param employees employees
   * @param rows rows (one per approved timesheet row)
   * @param workedMinutes total worked minutes
   */
  @Schema(name = "TimesheetExportCounts")
  public record Counts(int timesheets, int employees, int rows, int workedMinutes) {

    /**
     * The view of totals.
     *
     * @param totals totals
     * @return the view
     */
    public static Counts of(TimesheetExport.Totals totals) {
      return new Counts(
          totals.timesheets(), totals.employees(), totals.rows(), totals.workedMinutes());
    }
  }

  /**
   * What an export of the scope would hold now.
   *
   * @param scope the scope
   * @param generationId the week's generation
   * @param generationVersion its current version
   * @param eligible the eligible approved timesheets, employees, rows and minutes
   * @param excluded the week's other timesheets by state
   * @param previewDigest the digest to send with the creation
   * @param expiresAt the instant after which the preview is stale
   * @param existingExportId the export of identical evidence, if one exists
   */
  @Schema(name = "TimesheetExportPreview")
  public record Preview(
      ScopeView scope,
      UUID generationId,
      int generationVersion,
      Counts eligible,
      Exclusions excluded,
      String previewDigest,
      Instant expiresAt,
      @Schema(nullable = true) UUID existingExportId) {}

  /**
   * An export: the manifest's facts.
   *
   * @param exportId export
   * @param status always READY
   * @param schemaVersion the export schema version
   * @param scope the scope
   * @param generationId the generation
   * @param generationVersion the exported generation version
   * @param counts counts and total
   * @param csvSha256 SHA-256 of the CSV
   * @param createdAt creation instant
   */
  @Schema(name = "TimesheetExport")
  public record Summary(
      UUID exportId,
      @Schema(allowableValues = {"READY"}) String status,
      int schemaVersion,
      ScopeView scope,
      UUID generationId,
      int generationVersion,
      Counts counts,
      String csvSha256,
      Instant createdAt) {

    /**
     * The view of a stored export.
     *
     * @param header the export
     * @param legalEntity the legal entity, or {@code null}
     * @param site the site, or {@code null}
     * @return the view
     */
    public static Summary of(TimesheetExport.Header header, UnitView legalEntity, UnitView site) {
      return new Summary(
          header.id(),
          "READY",
          TimesheetExport.SCHEMA_VERSION,
          ScopeView.of(header.scope(), legalEntity, site),
          header.scope().generationId(),
          header.scope().generationVersion(),
          Counts.of(header.totals()),
          header.csvSha256(),
          header.createdAt());
    }
  }

  /**
   * A creation's result: a new export, or the existing export of identical evidence.
   *
   * @param outcome CREATED or EXISTING
   * @param export the export
   */
  @Schema(name = "TimesheetExportResult")
  public record Result(
      @Schema(allowableValues = {"CREATED", "EXISTING"}) String outcome, Summary export) {}

  /**
   * One page of exports, newest first.
   *
   * @param items exports
   * @param nextCursor opaque cursor of the next page, or {@code null}
   */
  @Schema(name = "TimesheetExportPage")
  public record Page(List<Summary> items, @Schema(nullable = true) String nextCursor) {

    /** Copies the list. */
    public Page {
      items = List.copyOf(items);
    }
  }
}
