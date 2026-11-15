package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Responses of statutory pack governance (MVP-097A, ADR 0040). Technical validation is not legal
 * approval, and nothing here claims legal compliance. No record prints a rate, a formula, a
 * reference value, a reviewer or submitter identity, a reviewer note or a subject: history entries
 * say only whether the caller performed them.
 */
public final class StatutoryPackResponses {

  private StatutoryPackResponses() {}

  /**
   * A pack version, summarized.
   *
   * @param packCode pack code
   * @param version integer version
   * @param semanticVersion semantic version
   * @param country country code
   * @param applicability applicability key
   * @param state governed lifecycle state
   * @param effectiveFrom first day of the pack interval
   * @param effectiveTo last day of the pack interval, or {@code null}
   * @param activeFrom first day of applicability once scheduled or active, or {@code null}
   * @param evidenceComplete whether the source evidence is complete
   * @param lastValidation outcome of the latest validation, or {@code null}
   * @param rowVersion optimistic version for the next command
   * @param approvedForOperationalPayroll whether the state is ACTIVE
   */
  @Schema(name = "StatutoryPackVersionSummary")
  public record VersionSummary(
      String packCode,
      int version,
      String semanticVersion,
      String country,
      String applicability,
      String state,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      LocalDate activeFrom,
      boolean evidenceComplete,
      String lastValidation,
      int rowVersion,
      boolean approvedForOperationalPayroll) {}

  /**
   * A page of pack versions.
   *
   * @param items the versions
   * @param nextCursor cursor of the next page, or {@code null}
   */
  @Schema(name = "StatutoryPackVersionPage")
  public record VersionPage(List<VersionSummary> items, String nextCursor) {

    /** Copies the list. */
    public VersionPage {
      items = List.copyOf(items);
    }
  }

  /**
   * The immutable source evidence metadata (no document is stored).
   *
   * @param sourceAuthority authority name
   * @param sourceTitle document title
   * @param sourcePublishedOn publication date
   * @param sourceReference informational URL or reference id (never fetched)
   * @param sourceBundleSha256 SHA-256 of the archived source bundle
   * @param evidenceSchemaVersion evidence schema version
   */
  @Schema(name = "StatutoryPackEvidence")
  public record Evidence(
      String sourceAuthority,
      String sourceTitle,
      LocalDate sourcePublishedOn,
      String sourceReference,
      String sourceBundleSha256,
      Integer evidenceSchemaVersion) {}

  /**
   * The digests bound at validation.
   *
   * @param rulesSha256 SHA-256 of the pack rules
   * @param packSha256 pack digest recorded at validation, or {@code null}
   * @param fixtureSha256 reference-fixture digest recorded at validation, or {@code null}
   */
  @Schema(name = "StatutoryPackDigests")
  public record Digests(String rulesSha256, String packSha256, String fixtureSha256) {}

  /**
   * One check of a validation run.
   *
   * @param code stable check code
   * @param passed whether it passed
   */
  @Schema(name = "StatutoryPackValidationCheck")
  public record ValidationCheck(String code, boolean passed) {}

  /**
   * One validation run.
   *
   * @param runId run
   * @param ranAt time
   * @param outcome PASSED or FAILED
   * @param checkCount checks
   * @param failedCount failed checks
   * @param checks the bounded check summary
   * @param engineVersion validator version
   * @param ranByCaller whether the caller ran it
   */
  @Schema(name = "StatutoryPackValidationRun")
  public record ValidationRun(
      UUID runId,
      Instant ranAt,
      String outcome,
      int checkCount,
      int failedCount,
      List<ValidationCheck> checks,
      String engineVersion,
      boolean ranByCaller) {

    /** Copies the list. */
    public ValidationRun {
      checks = List.copyOf(checks);
    }
  }

  /**
   * The independent review decision.
   *
   * @param decision APPROVED or REJECTED
   * @param decidedAt time
   * @param reasonCode bounded rejection reason, or {@code null}
   * @param decidedByCaller whether the caller decided
   */
  @Schema(name = "StatutoryPackReview")
  public record Review(
      String decision, Instant decidedAt, String reasonCode, boolean decidedByCaller) {}

  /**
   * One lifecycle history entry.
   *
   * @param seq position
   * @param kind VALIDATE, SUBMIT, APPROVE, REJECT, SCHEDULE, ACTIVATE or RETIRE
   * @param fromState previous state
   * @param toState new state
   * @param occurredAt time
   * @param byCaller whether the caller performed it
   */
  @Schema(name = "StatutoryPackTransition")
  public record Transition(
      int seq,
      String kind,
      String fromState,
      String toState,
      Instant occurredAt,
      boolean byCaller) {}

  /**
   * A pack version in detail.
   *
   * @param summary the summary
   * @param evidence the source evidence
   * @param digests the digests
   * @param sourceCount declared sources
   * @param caseCount reference cases
   * @param submittedAt submission time, or {@code null}
   * @param submittedByCaller whether the caller submitted it
   * @param retireReason retirement reason, or {@code null}
   * @param supersedesVersion version this one corrects, or {@code null}
   * @param validationRuns latest validation runs, newest first (at most 10)
   * @param review the review decision, or {@code null}
   * @param history lifecycle history, oldest first
   */
  @Schema(name = "StatutoryPackVersionDetail")
  public record VersionDetail(
      VersionSummary summary,
      Evidence evidence,
      Digests digests,
      int sourceCount,
      int caseCount,
      Instant submittedAt,
      boolean submittedByCaller,
      String retireReason,
      Integer supersedesVersion,
      List<ValidationRun> validationRuns,
      Review review,
      List<Transition> history) {

    /** Copies the lists. */
    public VersionDetail {
      validationRuns = List.copyOf(validationRuns);
      history = List.copyOf(history);
    }
  }

  /**
   * The receipt of a lifecycle command (what the idempotency store keeps).
   *
   * @param packCode pack code
   * @param version integer version
   * @param state the state after the command
   * @param rowVersion the version for the next command
   * @param validationOutcome PASSED or FAILED for a validation, otherwise {@code null}
   * @param revisionVersion the new DRAFT version a rejection created, or {@code null}
   */
  @Schema(name = "StatutoryPackCommandReceipt")
  public record CommandReceipt(
      String packCode,
      int version,
      String state,
      int rowVersion,
      String validationOutcome,
      Integer revisionVersion) {}

  /**
   * The applicable pack of a legal entity and period, for tenant administrators (read-only public
   * provenance: no formula, rate, reviewer or raw document).
   *
   * @param legalEntityId legal entity
   * @param payrollPeriod {@code YYYY-MM}
   * @param country the legal entity's country
   * @param applicable whether exactly one ACTIVE pack applies
   * @param packCode pack code, or {@code null}
   * @param version integer version, or {@code null}
   * @param semanticVersion semantic version, or {@code null}
   * @param state ACTIVE, or {@code null}
   * @param effectiveFrom first day of applicability, or {@code null}
   * @param effectiveTo last day of applicability, or {@code null}
   * @param sourceAuthority public source authority, or {@code null}
   * @param sourceTitle public source title, or {@code null}
   * @param sourcePublishedOn public source publication date, or {@code null}
   * @param sourceReference public source reference, or {@code null}
   */
  @Schema(name = "StatutoryPackApplicability")
  public record Applicability(
      UUID legalEntityId,
      String payrollPeriod,
      String country,
      boolean applicable,
      String packCode,
      Integer version,
      String semanticVersion,
      String state,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String sourceAuthority,
      String sourceTitle,
      LocalDate sourcePublishedOn,
      String sourceReference) {}
}
