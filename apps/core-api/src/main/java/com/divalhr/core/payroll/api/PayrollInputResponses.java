package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.domain.PayrollInputs;
import com.divalhr.core.payroll.domain.PayrollInputs.Blocker;
import com.divalhr.core.payroll.domain.PayrollInputs.EmployeeInput;
import com.divalhr.core.payroll.domain.PayrollInputs.Exclusion;
import com.divalhr.core.payroll.domain.PayrollInputs.Run;
import com.divalhr.core.payroll.domain.PayrollInputs.SourceWeek;
import com.divalhr.core.payroll.domain.PayrollLifecycle;
import com.divalhr.core.platform.access.LockedApprovedTime.Week;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response shapes of MVP-090A (payroll inputs). Identifiers, codes, dates, counts and minutes only:
 * no name of a person, no employee number, no subject, no amount.
 */
public final class PayrollInputResponses {

  private PayrollInputResponses() {}

  /**
   * The selected scope.
   *
   * @param legalEntityId legal entity
   * @param legalEntityCode its code, or {@code null}
   * @param legalEntityName its name, or {@code null}
   * @param periodStart first date (inclusive)
   * @param periodEnd last date (inclusive)
   * @param businessTimezone the organization's business time zone
   */
  @Schema(name = "PayrollInputScope")
  public record ScopeView(
      UUID legalEntityId,
      @Schema(nullable = true) String legalEntityCode,
      @Schema(nullable = true) String legalEntityName,
      LocalDate periodStart,
      LocalDate periodEnd,
      String businessTimezone) {

    /**
     * The view of a scope.
     *
     * @param scope scope
     * @param legalEntity the legal entity, or {@code null}
     * @return the view
     */
    public static ScopeView of(PayrollInputs.Scope scope, UnitView legalEntity) {
      return new ScopeView(
          scope.legalEntityId(),
          legalEntity == null ? null : legalEntity.code(),
          legalEntity == null ? null : legalEntity.name(),
          scope.periodStart(),
          scope.periodEnd(),
          scope.businessTimezone());
    }
  }

  /**
   * Counts and the total worked minutes.
   *
   * @param timesheets timesheets
   * @param employees employees (employee and employment pairs)
   * @param rows rows
   * @param workedMinutes total approved worked minutes
   */
  @Schema(name = "PayrollInputCounts")
  public record Counts(int timesheets, int employees, int rows, int workedMinutes) {

    /**
     * The view of totals.
     *
     * @param totals totals
     * @return the view
     */
    public static Counts of(PayrollInputs.Totals totals) {
      return new Counts(
          totals.timesheets(), totals.employees(), totals.rows(), totals.workedMinutes());
    }
  }

  /**
   * One stable code with a count.
   *
   * @param code the code
   * @param count how many
   */
  @Schema(name = "PayrollInputCodeCount")
  public record CodeCount(String code, int count) {}

  /**
   * One site-local week the period touches, with its lock status.
   *
   * @param siteId site
   * @param siteCode its code, or {@code null}
   * @param siteName its name, or {@code null}
   * @param weekStart site-local Monday
   * @param timezone the site's zone
   * @param status LOCKED (usable), NOT_GENERATED, NOT_LOCKED, EVIDENCE_CHANGED or EMPTY (a
   *     generation without any timesheet, nothing to pay)
   * @param generationId the generation, or {@code null}
   * @param generationVersion its version, or {@code null}
   * @param lockActionId the current LOCKED action, or {@code null}
   * @param lockVersion the lock version, or {@code null}
   * @param counts the week's rows within the period, or {@code null} when not usable
   */
  @Schema(name = "PayrollInputWeek")
  public record WeekView(
      UUID siteId,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) String siteName,
      LocalDate weekStart,
      String timezone,
      @Schema(
              allowableValues = {
                "LOCKED",
                "NOT_GENERATED",
                "NOT_LOCKED",
                "EVIDENCE_CHANGED",
                "EMPTY"
              })
          String status,
      @Schema(nullable = true) UUID generationId,
      @Schema(nullable = true) Integer generationVersion,
      @Schema(nullable = true) UUID lockActionId,
      @Schema(nullable = true) Integer lockVersion,
      @Schema(nullable = true) Counts counts) {

    /**
     * The view of a week the port returned, with the counts of its rows in the period.
     *
     * @param week the week
     * @param source its source week when it is locked and holds rows in the period, else null
     * @param site the site, or {@code null}
     * @return the view
     */
    public static WeekView of(Week week, SourceWeek source, UnitView site) {
      var generation = week.generation();
      String status;
      if (generation == null) {
        status = "NOT_GENERATED";
      } else if (generation.timesheets() == 0) {
        status = "EMPTY";
      } else if (!generation.locked()) {
        status = "NOT_LOCKED";
      } else if (!generation.lock().evidenceDigest().equals(generation.evidenceDigest())
          || generation.lock().generationVersion() != generation.version()) {
        status = "EVIDENCE_CHANGED";
      } else {
        status = "LOCKED";
      }
      boolean locked = "LOCKED".equals(status);
      return new WeekView(
          week.siteId(),
          site == null ? null : site.code(),
          site == null ? null : site.name(),
          week.weekStart(),
          week.timezone(),
          status,
          generation == null ? null : generation.id(),
          generation == null ? null : generation.version(),
          locked ? generation.lock().actionId() : null,
          locked ? generation.lock().version() : null,
          source == null ? null : Counts.of(source.totals()));
    }
  }

  /**
   * What a run of the scope would hold now (nothing is written by a preview).
   *
   * @param scope scope
   * @param weeks every week the period touches, by site and week
   * @param eligible the rows within the period
   * @param excluded exclusion code to count (every code, possibly 0)
   * @param blockers blocker code to count (only the present ones; empty when ready)
   * @param ready whether the period can be generated now
   * @param sourceVersionsDigest the digest of the locked weeks' generation and lock versions
   * @param previewDigest the preview's digest (the evidence, the versions, the expiry)
   * @param expiresAt when the preview expires
   * @param existingRunId the run of this legal entity and period, if one exists
   * @param existingRunUnchanged whether that run's evidence equals the evidence now, or {@code
   *     null} without a run
   */
  @Schema(name = "PayrollInputPreview")
  public record Preview(
      ScopeView scope,
      List<WeekView> weeks,
      Counts eligible,
      List<CodeCount> excluded,
      List<CodeCount> blockers,
      boolean ready,
      String sourceVersionsDigest,
      String previewDigest,
      Instant expiresAt,
      @Schema(nullable = true) UUID existingRunId,
      @Schema(nullable = true) Boolean existingRunUnchanged) {

    /** Copies the lists. */
    public Preview {
      weeks = List.copyOf(weeks);
      excluded = List.copyOf(excluded);
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * A generated run: its facts.
   *
   * @param runId run
   * @param version run version
   * @param status always GENERATED
   * @param schemaVersion the payroll-input schema version
   * @param scope scope
   * @param weekCount locked weeks
   * @param counts the rows
   * @param excluded exclusion code to count
   * @param evidenceDigest the source evidence digest
   * @param generatedAt instant
   * @param lifecycle the version chain link, the OPEN or LOCKED state and the export (MVP-092)
   */
  @Schema(name = "PayrollInputRun")
  public record Summary(
      UUID runId,
      int version,
      @Schema(allowableValues = {"GENERATED"}) String status,
      int schemaVersion,
      ScopeView scope,
      int weekCount,
      Counts counts,
      List<CodeCount> excluded,
      String evidenceDigest,
      Instant generatedAt,
      Lifecycle lifecycle) {

    /** Copies the list. */
    public Summary {
      excluded = List.copyOf(excluded);
    }

    /**
     * The view of a stored run.
     *
     * @param run the run
     * @param legalEntity the legal entity, or {@code null}
     * @param state the run's lifecycle state
     * @return the view
     */
    public static Summary of(Run run, UnitView legalEntity, PayrollLifecycle.State state) {
      return new Summary(
          run.id(),
          run.version(),
          "GENERATED",
          PayrollInputs.SCHEMA_VERSION,
          ScopeView.of(run.scope(), legalEntity),
          run.weekCount(),
          Counts.of(run.totals()),
          exclusions(run.exclusions()),
          run.evidenceDigest(),
          run.generatedAt(),
          Lifecycle.of(state));
    }
  }

  /**
   * Where a run stands in its version chain (MVP-092). The explanation of a supersession is shown
   * only in the version chain, never here.
   *
   * @param state OPEN, or LOCKED (final; never unlocked in this MVP)
   * @param latest whether no later version exists
   * @param supersedesRunId the run this one replaces, or {@code null} for version 1
   * @param reasonCode why it replaces it, or {@code null}
   * @param lockActionId the LOCKED action, or {@code null} while OPEN
   * @param lockedAt when it was locked, or {@code null}
   * @param exportId the run's worked-time export, or {@code null}
   */
  @Schema(name = "PayrollInputLifecycle")
  public record Lifecycle(
      @Schema(allowableValues = {"OPEN", "LOCKED"}) String state,
      boolean latest,
      @Schema(nullable = true) UUID supersedesRunId,
      @Schema(
              nullable = true,
              allowableValues = {
                "UPSTREAM_CORRECTION",
                "UPSTREAM_REAPPROVAL",
                "SCOPE_CORRECTION",
                "OTHER"
              })
          String reasonCode,
      @Schema(nullable = true) UUID lockActionId,
      @Schema(nullable = true) Instant lockedAt,
      @Schema(nullable = true) UUID exportId) {

    /**
     * The view of a state.
     *
     * @param state the state
     * @return the view
     */
    public static Lifecycle of(PayrollLifecycle.State state) {
      return new Lifecycle(
          state.locked() ? "LOCKED" : "OPEN",
          state.latest(),
          state.supersedesRunId(),
          state.reason() == null ? null : state.reason().name(),
          state.lock() == null ? null : state.lock().id(),
          state.lock() == null ? null : state.lock().lockedAt(),
          state.exportId());
    }
  }

  /**
   * One locked week a run was built from.
   *
   * @param siteId site
   * @param siteCode its code, or {@code null}
   * @param siteName its name, or {@code null}
   * @param weekStart site-local Monday
   * @param timezone the site's zone
   * @param generationId generation
   * @param generationVersion its version
   * @param lockActionId the LOCKED action
   * @param lockVersion the lock version
   * @param lockEvidenceDigest the evidence digest the lock bound
   * @param counts the week's rows within the period
   */
  @Schema(name = "PayrollInputSourceWeek")
  public record SourceWeekView(
      UUID siteId,
      @Schema(nullable = true) String siteCode,
      @Schema(nullable = true) String siteName,
      LocalDate weekStart,
      String timezone,
      UUID generationId,
      int generationVersion,
      UUID lockActionId,
      int lockVersion,
      String lockEvidenceDigest,
      Counts counts) {

    /**
     * The view of a source week.
     *
     * @param week the week
     * @param site the site, or {@code null}
     * @return the view
     */
    public static SourceWeekView of(SourceWeek week, UnitView site) {
      return new SourceWeekView(
          week.siteId(),
          site == null ? null : site.code(),
          site == null ? null : site.name(),
          week.weekStart(),
          week.timezone(),
          week.generationId(),
          week.generationVersion(),
          week.lockActionId(),
          week.lockVersion(),
          week.lockEvidenceDigest(),
          Counts.of(week.totals()));
    }
  }

  /**
   * One employee and employment's inputs: identifiers, dates, counts and minutes; never a name.
   *
   * @param employeeId employee
   * @param employmentId employment
   * @param firstWorkDate first work date in the period
   * @param lastWorkDate last work date in the period
   * @param counts the rows
   */
  @Schema(name = "PayrollInputEmployee")
  public record EmployeeView(
      UUID employeeId,
      UUID employmentId,
      LocalDate firstWorkDate,
      LocalDate lastWorkDate,
      Counts counts) {

    /**
     * The view of an employee input.
     *
     * @param input the input
     * @return the view
     */
    public static EmployeeView of(EmployeeInput input) {
      return new EmployeeView(
          input.employeeId(),
          input.employmentId(),
          input.firstWorkDate(),
          input.lastWorkDate(),
          Counts.of(input.totals()));
    }
  }

  /**
   * One run with its source weeks and employee inputs.
   *
   * @param run the run
   * @param weeks the locked weeks it was built from, by site and week
   * @param employees the employee inputs, by employee
   */
  @Schema(name = "PayrollInputRunDetail")
  public record Detail(Summary run, List<SourceWeekView> weeks, List<EmployeeView> employees) {

    /** Copies the lists. */
    public Detail {
      weeks = List.copyOf(weeks);
      employees = List.copyOf(employees);
    }
  }

  /**
   * A new run, or the existing run of identical evidence.
   *
   * @param outcome GENERATED or EXISTING
   * @param run the run
   */
  @Schema(name = "PayrollInputResult")
  public record Result(
      @Schema(allowableValues = {"GENERATED", "EXISTING"}) String outcome, Summary run) {}

  /**
   * One page of runs, newest first.
   *
   * @param items runs
   * @param nextCursor opaque cursor of the next page, or {@code null}
   */
  @Schema(name = "PayrollInputRunPage")
  public record Page(List<Summary> items, @Schema(nullable = true) String nextCursor) {

    /** Copies the list. */
    public Page {
      items = List.copyOf(items);
    }
  }

  /**
   * Exclusion counts as code-count pairs, in code order.
   *
   * @param exclusions exclusion to count
   * @return the pairs
   */
  public static List<CodeCount> exclusions(Map<Exclusion, Integer> exclusions) {
    return exclusions.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> new CodeCount(e.getKey().name(), e.getValue()))
        .toList();
  }

  /**
   * Blocker counts as code-count pairs, in code order.
   *
   * @param blockers blocker to count
   * @return the pairs
   */
  public static List<CodeCount> blockers(Map<Blocker, Integer> blockers) {
    return blockers.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> new CodeCount(e.getKey().name(), e.getValue()))
        .toList();
  }
}
