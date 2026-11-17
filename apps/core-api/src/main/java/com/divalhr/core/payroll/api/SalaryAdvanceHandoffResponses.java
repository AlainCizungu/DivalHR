package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.CompensationResponses.MoneyView;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.ConsentEvidence;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MVP-120B response bodies of the controlled partner handoff. Every response carries both permanent
 * notice codes {@code DIVALHR_NOT_LENDER} and {@code DIVALHR_HANDOFF_SYNTHETIC}. None carries a
 * name, an email, an identifier of the employee beyond the administrator fields, a payload, a
 * signature, a header, an endpoint, a key, a partner response body or any word of lending, approval
 * or funding. Amounts are canonical decimal text with their currency. No status is a credit
 * decision.
 */
public final class SalaryAdvanceHandoffResponses {

  /** The permanent synthetic-handoff notice code. */
  public static final String HANDOFF_NOTICE = "DIVALHR_HANDOFF_SYNTHETIC";

  private SalaryAdvanceHandoffResponses() {}

  /**
   * The canonical, versioned handoff disclosure as stable codes.
   *
   * @param version document version
   * @param contentSha256 SHA-256 of the canonical JSON
   * @param purpose the purpose code
   * @param recipient the partner code
   * @param shared codes of what is shared
   * @param notShared codes of what is never shared
   * @param promise the no-promise code
   * @param withdrawal when withdrawal is possible
   * @param retention the retention statement code
   * @param lender the not-the-lender code
   */
  @Schema(name = "SalaryAdvanceHandoffConsentDocument")
  public record ConsentDocument(
      String version,
      String contentSha256,
      String purpose,
      String recipient,
      List<String> shared,
      List<String> notShared,
      String promise,
      String withdrawal,
      String retention,
      String lender) {

    /** Copies the lists so the record is immutable. */
    public ConsentDocument {
      shared = List.copyOf(shared);
      notShared = List.copyOf(notShared);
    }
  }

  /**
   * The previewed handoff.
   *
   * @param requestId the employer-confirmed request
   * @param requestVersion its row version
   * @param currency currency
   * @param requested the requested amount
   * @param ceiling the employer ceiling
   * @param partnerCode the synthetic partner code
   * @param consent the handoff disclosure
   * @param blockers closed blocker codes
   * @param previewDigest digest, or {@code null} when blocked
   * @param expiresAt expiry, or {@code null} when blocked
   * @param notice permanent notice code
   * @param handoffNotice permanent synthetic-handoff notice code
   */
  @Schema(name = "SalaryAdvanceHandoffPreview")
  public record Preview(
      UUID requestId,
      int requestVersion,
      String currency,
      MoneyView requested,
      MoneyView ceiling,
      String partnerCode,
      ConsentDocument consent,
      List<String> blockers,
      String previewDigest,
      Instant expiresAt,
      String notice,
      String handoffNotice) {

    /** Copies the lists so the record is immutable. */
    public Preview {
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * One delivery event.
   *
   * @param sequence 1-based
   * @param fromState previous state, or {@code null}
   * @param toState new state
   * @param actorRole {@code EMPLOYEE}, {@code TENANT_ADMIN} or {@code SYSTEM}
   * @param occurredAt instant
   * @param outcomeCode allow-listed outcome, or {@code null}
   */
  @Schema(name = "SalaryAdvanceHandoffEvent")
  public record Event(
      int sequence,
      String fromState,
      String toState,
      String actorRole,
      Instant occurredAt,
      String outcomeCode) {}

  /**
   * One partner-review event.
   *
   * @param sequence 1-based
   * @param fromState previous review state
   * @param toState new review state
   * @param reasonCode closed reason, or {@code null}
   * @param occurredAt instant
   */
  @Schema(name = "SalaryAdvanceHandoffReviewEvent")
  public record ReviewEvent(
      int sequence, String fromState, String toState, String reasonCode, Instant occurredAt) {}

  /**
   * One delivery attempt (administrators only).
   *
   * @param attempt attempt number
   * @param startedAt start
   * @param outcomeCode allow-listed outcome
   */
  @Schema(name = "SalaryAdvanceHandoffAttempt")
  public record Attempt(int attempt, Instant startedAt, String outcomeCode) {}

  /**
   * A handoff in a list.
   *
   * @param handoffId handoff
   * @param requestId request
   * @param state delivery state
   * @param reviewState review state, or {@code null}
   * @param version row version
   * @param currency currency
   * @param requested requested amount
   * @param createdAt authorization instant
   * @param employeeId employee (administrators only)
   * @param employeeNumber employee number (administrators only)
   * @param notice permanent notice code
   * @param handoffNotice permanent synthetic-handoff notice code
   */
  @Schema(name = "SalaryAdvanceHandoffSummary")
  public record Summary(
      UUID handoffId,
      UUID requestId,
      String state,
      String reviewState,
      int version,
      String currency,
      MoneyView requested,
      Instant createdAt,
      UUID employeeId,
      String employeeNumber,
      String notice,
      String handoffNotice) {}

  /**
   * A page of handoffs.
   *
   * @param items the page
   * @param nextCursor opaque cursor, or {@code null}
   */
  @Schema(name = "SalaryAdvanceHandoffPage")
  public record Page(List<Summary> items, String nextCursor) {

    /** Copies the lists so the record is immutable. */
    public Page {
      items = List.copyOf(items);
    }
  }

  /**
   * One handoff with its delivery and bounded review status.
   *
   * @param handoffId handoff
   * @param requestId request
   * @param state delivery state
   * @param reviewState review state, or {@code null}
   * @param reviewReason closed review reason, or {@code null}
   * @param version row version
   * @param currency currency
   * @param requested requested amount
   * @param ceiling employer ceiling
   * @param partnerCode the synthetic partner code
   * @param createdAt authorization instant
   * @param receivedAt receipt acknowledgement instant, or {@code null}
   * @param lastOutcomeCode the last allow-listed outcome, or {@code null}
   * @param attemptCount attempts started
   * @param consent the consent evidence
   * @param events delivery history
   * @param reviewEvents review history
   * @param attempts attempts (administrators only; empty for the employee)
   * @param applicationId the opaque application id (administrators only)
   * @param employeeId employee (administrators only)
   * @param employeeNumber employee number (administrators only)
   * @param notice permanent notice code
   * @param handoffNotice permanent synthetic-handoff notice code
   */
  @Schema(name = "SalaryAdvanceHandoffView")
  public record View(
      UUID handoffId,
      UUID requestId,
      String state,
      String reviewState,
      String reviewReason,
      int version,
      String currency,
      MoneyView requested,
      MoneyView ceiling,
      String partnerCode,
      Instant createdAt,
      Instant receivedAt,
      String lastOutcomeCode,
      int attemptCount,
      ConsentEvidence consent,
      List<Event> events,
      List<ReviewEvent> reviewEvents,
      List<Attempt> attempts,
      UUID applicationId,
      UUID employeeId,
      String employeeNumber,
      String notice,
      String handoffNotice) {

    /** Copies the lists so the record is immutable. */
    public View {
      events = List.copyOf(events);
      reviewEvents = List.copyOf(reviewEvents);
      attempts = List.copyOf(attempts);
    }
  }
}
