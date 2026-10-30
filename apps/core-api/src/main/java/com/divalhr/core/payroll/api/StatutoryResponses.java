package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.CompensationResponses.MoneyView;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Responses of the DRC statutory preview (MVP-094B, ADR 0034). Money is the canonical decimal
 * string next to its currency code (CDF whole francs). A statutory preview is not a payslip, a
 * payment, an approval or a filing and makes no legal-compliance claim. Restricted financial and HR
 * data: no record here prints an amount, an employee number, a flag, a classification, a digest or
 * a reviewer identity, and the commit receipts carry none (they are what the idempotency store
 * keeps).
 */
public final class StatutoryResponses {

  private StatutoryResponses() {}

  /**
   * A rule pack, summarized.
   *
   * @param packId pack
   * @param code pack code
   * @param version pack version
   * @param country {@code CD}
   * @param currency {@code CDF}
   * @param status {@code DRAFT}, {@code VERIFIED} or {@code RETIRED}
   * @param effectiveFrom first day of the interval
   * @param effectiveTo inclusive last day, or {@code null} when open
   * @param methodVersion calculation-method version
   * @param rulesDigest SHA-256 of the structured rules
   * @param sourceCount number of official sources
   */
  @Schema(name = "StatutoryRulePackSummary")
  public record PackSummary(
      UUID packId,
      String code,
      int version,
      String country,
      String currency,
      String status,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      int methodVersion,
      String rulesDigest,
      int sourceCount) {}

  /**
   * A page of rule packs.
   *
   * @param items the packs
   * @param nextCursor cursor of the next page, or {@code null}
   */
  @Schema(name = "StatutoryRulePackPage")
  public record PackPage(List<PackSummary> items, String nextCursor) {

    /** Copies the list. */
    public PackPage {
      items = List.copyOf(items);
    }
  }

  /**
   * One official source of a rule pack.
   *
   * @param key closed source key
   * @param title title
   * @param url https URL on an allow-listed official host
   * @param sha256 SHA-256 of the retrieved document, or {@code null} while DRAFT
   * @param retrievedOn retrieval date
   */
  @Schema(name = "StatutorySource")
  public record SourceView(
      String key, String title, String url, String sha256, LocalDate retrievedOn) {}

  /**
   * One bracket of a progressive schedule.
   *
   * @param upToAnnual inclusive annual upper bound in whole francs as a decimal string, or {@code
   *     null} for the open last bracket
   * @param rateBp rate in basis points
   */
  @Schema(name = "StatutoryRuleBracket")
  public record RuleBracket(String upToAnnual, int rateBp) {}

  /**
   * How a rule computes its amount; fields of the other type are absent.
   *
   * @param type {@code RATE} or {@code PROGRESSIVE}
   * @param rateBp flat rate in basis points ({@code RATE})
   * @param periodsPerYear 1 or 12 ({@code PROGRESSIVE})
   * @param capBp cap in basis points of the base, when the schedule has one
   * @param brackets the brackets ({@code PROGRESSIVE})
   */
  @Schema(name = "StatutoryRuleMethod")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RuleMethod(
      String type,
      Integer rateBp,
      Integer periodsPerYear,
      Integer capBp,
      List<RuleBracket> brackets) {

    /** Copies the list. */
    public RuleMethod {
      brackets = brackets == null ? null : List.copyOf(brackets);
    }
  }

  /**
   * One structured rule of a pack.
   *
   * @param id stable rule id
   * @param party {@code EMPLOYEE} or {@code EMPLOYER}
   * @param category {@code SOCIAL_CONTRIBUTION} or {@code INCOME_TAX}
   * @param order position
   * @param requires profile flag that makes the rule apply
   * @param base {@code GROSS} or {@code GROSS_LESS_EMPLOYEE_SOCIAL}
   * @param sourceKey the official source it cites
   * @param rounding {@code HALF_UP}, {@code DOWN} or {@code UP}
   * @param method how the amount is computed
   */
  @Schema(name = "StatutoryRuleComponent")
  public record RuleComponent(
      String id,
      String party,
      String category,
      int order,
      String requires,
      String base,
      String sourceKey,
      String rounding,
      RuleMethod method) {}

  /**
   * The professional-review evidence of a VERIFIED or RETIRED pack (restricted operational
   * evidence).
   *
   * @param reviewerName reviewer
   * @param reviewerOrganization reviewer organization
   * @param reviewedOn review date
   * @param attested always true on a stored verification
   * @param verifiedAt verification instant
   */
  @Schema(name = "StatutoryVerification")
  public record Verification(
      String reviewerName,
      String reviewerOrganization,
      LocalDate reviewedOn,
      boolean attested,
      Instant verifiedAt) {

    @Override
    public String toString() {
      return "StatutoryVerification";
    }
  }

  /**
   * A rule pack with its sources, structured rules and verification evidence.
   *
   * @param pack the summary
   * @param sources official sources
   * @param components structured rules, ascending by order
   * @param verification the evidence, or {@code null} while DRAFT
   */
  @Schema(name = "StatutoryRulePackDetail")
  public record PackDetail(
      PackSummary pack,
      List<SourceView> sources,
      List<RuleComponent> components,
      Verification verification) {

    /** Copies the lists. */
    public PackDetail {
      sources = List.copyOf(sources);
      components = List.copyOf(components);
    }
  }

  /**
   * One activation of a legal entity.
   *
   * @param activationId activation
   * @param sequence sequence within the legal entity
   * @param fromMonth first month, {@code YYYY-MM}
   * @param packId activated pack
   * @param packCode its code
   * @param packVersion its version
   * @param activatedAt activation instant
   */
  @Schema(name = "StatutoryActivationEntry")
  public record ActivationEntry(
      UUID activationId,
      int sequence,
      String fromMonth,
      UUID packId,
      String packCode,
      int packVersion,
      Instant activatedAt) {}

  /**
   * The activation and pack in effect for a month.
   *
   * @param activationId activation
   * @param sequence its sequence
   * @param fromMonth its first month, {@code YYYY-MM}
   * @param pack the pack
   */
  @Schema(name = "StatutoryInEffect")
  public record InEffect(UUID activationId, int sequence, String fromMonth, PackSummary pack) {}

  /**
   * The activations of a legal entity and the pack in effect for a month.
   *
   * @param legalEntityId legal entity
   * @param month the requested month, {@code YYYY-MM}
   * @param version number of activations, to repeat as {@code expectedVersion}
   * @param verificationRequired true when no VERIFIED applicable pack is in effect
   * @param inEffect the activation in effect, or {@code null}
   * @param activations newest first
   */
  @Schema(name = "StatutoryActivationView")
  public record ActivationView(
      UUID legalEntityId,
      String month,
      int version,
      boolean verificationRequired,
      InEffect inEffect,
      List<ActivationEntry> activations) {

    /** Copies the list. */
    public ActivationView {
      activations = List.copyOf(activations);
    }
  }

  /**
   * The receipt of an activation (identifiers and codes only).
   *
   * @param activationId activation
   * @param legalEntityId legal entity
   * @param packId pack
   * @param packCode its code
   * @param packVersion its version
   * @param fromMonth first month, {@code YYYY-MM}
   * @param sequence its sequence
   */
  @Schema(name = "StatutoryActivationReceipt")
  public record ActivationReceipt(
      UUID activationId,
      UUID legalEntityId,
      UUID packId,
      String packCode,
      int packVersion,
      String fromMonth,
      int sequence) {}

  /**
   * One effective-dated entry of an employee's statutory profile (restricted HR data).
   *
   * @param profileId profile row
   * @param sequence sequence within the employee
   * @param effectiveFrom first day in force
   * @param country {@code CD}
   * @param classification {@code STANDARD_RESIDENT} or {@code UNSUPPORTED}
   * @param cnssSubject whether subject to CNSS
   * @param irppSubject whether subject to the IRPP salary withholding
   * @param evidenceCode closed evidence code
   * @param createdAt creation instant
   */
  @Schema(name = "StatutoryProfileEntry")
  public record ProfileEntry(
      UUID profileId,
      int sequence,
      LocalDate effectiveFrom,
      String country,
      String classification,
      boolean cnssSubject,
      boolean irppSubject,
      String evidenceCode,
      Instant createdAt) {

    @Override
    public String toString() {
      return "StatutoryProfileEntry[" + profileId + "]";
    }
  }

  /**
   * The profile timeline of an employee.
   *
   * @param employeeId employee
   * @param version number of entries
   * @param businessDate the organization's business date
   * @param current the entry in force on that date, or {@code null}
   * @param timeline entries, newest first
   */
  @Schema(name = "StatutoryProfileView")
  public record ProfileView(
      UUID employeeId,
      int version,
      LocalDate businessDate,
      ProfileEntry current,
      List<ProfileEntry> timeline) {

    /** Copies the list. */
    public ProfileView {
      timeline = List.copyOf(timeline);
    }

    @Override
    public String toString() {
      return "StatutoryProfileView[" + employeeId + "]";
    }
  }

  /**
   * A previewed profile entry; with a blocker there is no digest and no expiry.
   *
   * @param employeeId employee
   * @param version number of entries now
   * @param effectiveFrom first day in force
   * @param classification classification
   * @param cnssSubject whether subject to CNSS
   * @param irppSubject whether subject to the IRPP salary withholding
   * @param evidenceCode evidence code
   * @param before the timeline now, newest first
   * @param after the timeline after the entry, newest first
   * @param blockers stable blocker codes
   * @param previewDigest digest to repeat, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   */
  @Schema(name = "StatutoryProfilePreview")
  public record ProfilePreview(
      UUID employeeId,
      int version,
      LocalDate effectiveFrom,
      String classification,
      boolean cnssSubject,
      boolean irppSubject,
      String evidenceCode,
      List<ProfileEntry> before,
      List<ProfileEntry> after,
      List<String> blockers,
      String previewDigest,
      Instant expiresAt) {

    /** Copies the lists. */
    public ProfilePreview {
      before = List.copyOf(before);
      after = List.copyOf(after);
      blockers = List.copyOf(blockers);
    }

    @Override
    public String toString() {
      return "StatutoryProfilePreview[" + employeeId + "]";
    }
  }

  /**
   * The receipt of a recorded profile entry (no classification or flag).
   *
   * @param profileId recorded row
   * @param employeeId employee
   * @param sequence its sequence
   * @param effectiveFrom its first day
   * @param version the number of entries after it
   */
  @Schema(name = "StatutoryProfileReceipt")
  public record ProfileReceipt(
      UUID profileId, UUID employeeId, int sequence, LocalDate effectiveFrom, int version) {}

  /**
   * Sums over the calculated lines, in CDF.
   *
   * @param contractualGross total gross
   * @param employeeDeductions total employee deductions
   * @param employerContributions total employer contributions
   * @param previewNet total preview net
   * @param employerCost total employer cost
   */
  @Schema(name = "StatutoryTotals")
  public record Totals(
      MoneyView contractualGross,
      MoneyView employeeDeductions,
      MoneyView employerContributions,
      MoneyView previewNet,
      MoneyView employerCost) {

    @Override
    public String toString() {
      return "StatutoryTotals";
    }
  }

  /**
   * One slice of the annual base of a progressive component; integers are decimal strings.
   *
   * @param from annual lower bound
   * @param to annual upper bound
   * @param rateBp bracket rate in basis points
   * @param amount the part of the annual base inside the slice
   */
  @Schema(name = "StatutoryTraceSlice")
  public record TraceSlice(String from, String to, int rateBp, String amount) {}

  /**
   * The intermediate values of one component; a flat-rate component has an empty trace.
   *
   * @param annualBase monthly base times the periods per year
   * @param slices the slices
   * @param numerator sum of slice amount times rate in basis points
   * @param capApplied whether the cap was used
   */
  @Schema(name = "StatutoryTrace")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Trace(
      String annualBase, List<TraceSlice> slices, String numerator, Boolean capApplied) {

    /** Copies the list. */
    public Trace {
      slices = slices == null ? null : List.copyOf(slices);
    }
  }

  /**
   * One component of an employee's statutory line.
   *
   * @param componentId rule id
   * @param party who bears it
   * @param category what it is
   * @param base the base
   * @param amount the amount
   * @param explanationCodes stable explanation codes
   * @param trace the trace
   */
  @Schema(name = "StatutoryComponent")
  public record Component(
      String componentId,
      String party,
      String category,
      MoneyView base,
      MoneyView amount,
      List<String> explanationCodes,
      Trace trace) {

    /** Copies the list. */
    public Component {
      explanationCodes = List.copyOf(explanationCodes);
    }

    @Override
    public String toString() {
      return "StatutoryComponent[" + componentId + "]";
    }
  }

  /**
   * One employee of a previewed run. The amounts after the gross are null, and the components
   * empty, while a run-level or employee blocker exists.
   *
   * @param employeeId employee
   * @param employeeNumber employee number (Confidential)
   * @param blockers stable employee blockers
   * @param excluded whether the employee is subject to neither CNSS nor IRPP
   * @param contractualGross frozen contractual gross
   * @param employeeDeductions employee deductions, or {@code null}
   * @param employerContributions employer contributions, or {@code null}
   * @param previewNet preview net, or {@code null}
   * @param employerCost employer cost, or {@code null}
   * @param components the components in rule order
   */
  @Schema(name = "StatutoryPreviewLine")
  public record PreviewLine(
      UUID employeeId,
      String employeeNumber,
      List<String> blockers,
      boolean excluded,
      MoneyView contractualGross,
      MoneyView employeeDeductions,
      MoneyView employerContributions,
      MoneyView previewNet,
      MoneyView employerCost,
      List<Component> components) {

    /** Copies the lists. */
    public PreviewLine {
      blockers = List.copyOf(blockers);
      components = List.copyOf(components);
    }

    @Override
    public String toString() {
      return "StatutoryPreviewLine[" + employeeId + "]";
    }
  }

  /**
   * A calculation version, summarized.
   *
   * @param calculationId calculation
   * @param runId source run
   * @param runVersion source run version
   * @param packId pack
   * @param packCode its code
   * @param packVersion its version
   * @param period {@code YYYY-MM}
   * @param currency {@code CDF}
   * @param version version in its chain
   * @param status {@code DRAFT} or {@code SUPERSEDED}
   * @param supportedCount supported lines
   * @param excludedCount excluded lines
   * @param totals the totals
   * @param createdAt creation instant
   * @param supersededAt supersession instant, or {@code null}
   * @param supersededById successor, or {@code null}
   */
  @Schema(name = "StatutoryCalculationSummary")
  public record CalculationSummary(
      UUID calculationId,
      UUID runId,
      int runVersion,
      UUID packId,
      String packCode,
      int packVersion,
      String period,
      String currency,
      int version,
      String status,
      int supportedCount,
      int excludedCount,
      Totals totals,
      Instant createdAt,
      Instant supersededAt,
      UUID supersededById) {

    @Override
    public String toString() {
      return "StatutoryCalculationSummary[" + calculationId + "]";
    }
  }

  /**
   * A previewed calculation; while the status is BLOCKED or NO_CHANGE the calculation digest and
   * the expiry are null, and the totals are null when a run-level blocker exists.
   *
   * @param legalEntityId legal entity
   * @param runId source run
   * @param runVersion source run version
   * @param period {@code YYYY-MM}
   * @param currency the run currency
   * @param status {@code READY}, {@code BLOCKED} or {@code NO_CHANGE}
   * @param pack the pack in effect, or {@code null}
   * @param runBlockers run-level blockers
   * @param expectedVersion current chain version (0 when none)
   * @param existingCalculation the current draft, or {@code null}
   * @param supportedCount supported lines
   * @param excludedCount excluded lines
   * @param blockedCount blocked lines
   * @param totals the totals, or {@code null}
   * @param lines one per source line
   * @param sourceRunDigest digest of the source run
   * @param profileDigest digest of the profiles
   * @param rulePackDigest digest of the activation and pack, or {@code null}
   * @param calculationDigest digest to repeat, or {@code null}
   * @param expiresAt expiry, or {@code null}
   */
  @Schema(name = "StatutoryCalculationPreview")
  public record CalculationPreview(
      UUID legalEntityId,
      UUID runId,
      int runVersion,
      String period,
      String currency,
      String status,
      PackSummary pack,
      List<String> runBlockers,
      int expectedVersion,
      CalculationSummary existingCalculation,
      int supportedCount,
      int excludedCount,
      int blockedCount,
      Totals totals,
      List<PreviewLine> lines,
      String sourceRunDigest,
      String profileDigest,
      String rulePackDigest,
      String calculationDigest,
      Instant expiresAt) {

    /** Copies the lists. */
    public CalculationPreview {
      runBlockers = List.copyOf(runBlockers);
      lines = List.copyOf(lines);
    }

    @Override
    public String toString() {
      return "StatutoryCalculationPreview[" + runId + "]";
    }
  }

  /**
   * The amount-free receipt of a created calculation (what the idempotency store keeps).
   *
   * @param calculationId created calculation
   * @param runId source run
   * @param legalEntityId legal entity
   * @param packId pack
   * @param packVersion pack version
   * @param period {@code YYYY-MM}
   * @param currency {@code CDF}
   * @param version version in its chain
   * @param status always {@code DRAFT}
   * @param supersededCalculationId the version it superseded, or {@code null}
   * @param supportedCount supported lines
   * @param excludedCount excluded lines
   */
  @Schema(name = "StatutoryCalculationReceipt")
  public record CalculationReceipt(
      UUID calculationId,
      UUID runId,
      UUID legalEntityId,
      UUID packId,
      int packVersion,
      String period,
      String currency,
      int version,
      String status,
      UUID supersededCalculationId,
      int supportedCount,
      int excludedCount) {}

  /**
   * A page of calculation versions.
   *
   * @param items the calculations
   * @param nextCursor cursor of the next page, or {@code null}
   */
  @Schema(name = "StatutoryCalculationPage")
  public record CalculationPage(List<CalculationSummary> items, String nextCursor) {

    /** Copies the list. */
    public CalculationPage {
      items = List.copyOf(items);
    }
  }

  /**
   * One frozen employee line of a calculation.
   *
   * @param employeeId employee
   * @param employeeNumber employee number (Confidential)
   * @param excluded whether the employee is subject to neither CNSS nor IRPP
   * @param contractualGross frozen gross
   * @param employeeDeductions employee deductions
   * @param employerContributions employer contributions
   * @param previewNet preview net
   * @param employerCost employer cost
   * @param components the components in rule order
   */
  @Schema(name = "StatutoryRunLine")
  public record RunLine(
      UUID employeeId,
      String employeeNumber,
      boolean excluded,
      MoneyView contractualGross,
      MoneyView employeeDeductions,
      MoneyView employerContributions,
      MoneyView previewNet,
      MoneyView employerCost,
      List<Component> components) {

    /** Copies the list. */
    public RunLine {
      components = List.copyOf(components);
    }

    @Override
    public String toString() {
      return "StatutoryRunLine[" + employeeId + "]";
    }
  }

  /**
   * A calculation with its pack, sources and a page of its lines.
   *
   * @param calculation the calculation
   * @param pack its pack
   * @param sources the pack's official sources
   * @param lines a page of lines by employee number
   * @param nextCursor cursor of the next page, or {@code null}
   */
  @Schema(name = "StatutoryCalculationDetail")
  public record CalculationDetail(
      CalculationSummary calculation,
      PackSummary pack,
      List<SourceView> sources,
      List<RunLine> lines,
      String nextCursor) {

    /** Copies the lists. */
    public CalculationDetail {
      sources = List.copyOf(sources);
      lines = List.copyOf(lines);
    }

    @Override
    public String toString() {
      return "StatutoryCalculationDetail[" + calculation + "]";
    }
  }
}
