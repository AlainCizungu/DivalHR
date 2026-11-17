package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.Attempt;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.ConsentDocument;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.Event;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.ReviewEvent;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.Summary;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.View;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.ConsentEvidence;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.AttemptRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.ConsentRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.EventRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.HandoffRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.ReviewEventRow;
import java.time.Instant;
import java.util.List;

/**
 * Maps stored handoff rows to responses (MVP-120B). The employee view never carries the application
 * id, the attempt diagnostics, the employee number or the employee identifier; the administrator
 * view carries them. The canonical payload, its digest, the partner reference and every signature
 * are never mapped at all, and every response carries both permanent notice codes.
 */
final class SalaryAdvanceHandoffViews {

  private SalaryAdvanceHandoffViews() {}

  static ConsentDocument document(SalaryAdvanceHandoffConsent.Document document) {
    return new ConsentDocument(
        document.version(),
        document.contentSha256(),
        document.purpose(),
        document.recipient(),
        document.shared(),
        document.notShared(),
        document.promise(),
        document.withdrawal(),
        document.retention(),
        document.lender());
  }

  static Summary summary(HandoffRow row, boolean administrator) {
    return new Summary(
        row.id(),
        row.requestId(),
        row.state(),
        row.reviewState(),
        row.version(),
        row.currency(),
        SalaryAdvanceViews.money(row.currency(), row.requestedMinor()),
        row.authorizedAt(),
        administrator ? row.employeeId() : null,
        administrator ? row.employeeNumber() : null,
        SalaryAdvanceResponses.NOTICE,
        SalaryAdvanceHandoffResponses.HANDOFF_NOTICE);
  }

  static View view(
      HandoffRow row,
      ConsentRow consent,
      List<EventRow> events,
      List<ReviewEventRow> reviews,
      List<AttemptRow> attempts,
      Instant receivedAt,
      boolean administrator) {
    String lastOutcome = null;
    for (EventRow event : events) {
      if (event.outcome() != null) {
        lastOutcome = event.outcome();
      }
    }
    return new View(
        row.id(),
        row.requestId(),
        row.state(),
        row.reviewState(),
        row.reviewReason(),
        row.version(),
        row.currency(),
        SalaryAdvanceViews.money(row.currency(), row.requestedMinor()),
        SalaryAdvanceViews.money(row.currency(), row.ceilingMinor()),
        row.partnerCode(),
        row.authorizedAt(),
        receivedAt,
        lastOutcome,
        row.attemptCount(),
        new ConsentEvidence(
            consent.version(),
            consent.language(),
            consent.consentedAt(),
            consent.disclosureSha256()),
        events.stream()
            .map(
                event ->
                    new Event(
                        event.sequence(),
                        event.fromState(),
                        event.toState(),
                        event.actorRole(),
                        event.occurredAt(),
                        event.outcome()))
            .toList(),
        reviews.stream()
            .map(
                review ->
                    new ReviewEvent(
                        review.sequence(),
                        review.fromReviewState(),
                        review.toReviewState(),
                        review.reasonCode(),
                        review.occurredAt()))
            .toList(),
        administrator
            ? attempts.stream()
                .map(
                    attempt ->
                        new Attempt(
                            attempt.attemptNumber(), attempt.startedAt(), attempt.outcome()))
                .toList()
            : List.of(),
        administrator ? row.applicationId() : null,
        administrator ? row.employeeId() : null,
        administrator ? row.employeeNumber() : null,
        SalaryAdvanceResponses.NOTICE,
        SalaryAdvanceHandoffResponses.HANDOFF_NOTICE);
  }
}
