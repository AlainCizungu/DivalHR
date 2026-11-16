package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CompensationResponses.MoneyView;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.ConsentEvidence;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.LifecycleEntry;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.PolicyCommandView;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.PolicyEntry;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestSummary;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.RequestView;
import com.divalhr.core.payroll.application.SalaryAdvanceCommands.PolicyDraft;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.CompensationMoney;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.ConsentRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.DecisionRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.EventRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.PolicyRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.RequestRow;
import java.time.LocalDate;
import java.util.List;

/**
 * Maps stored salary-advance rows to responses (MVP-120A). The employee view never carries the
 * internal note, the employee number or the employee identifier; the administrator view carries
 * them and the note only on the detail. The reference net is never mapped at all.
 */
final class SalaryAdvanceViews {

  private SalaryAdvanceViews() {}

  static MoneyView money(String currency, long minor) {
    CompensationCurrency parsed = CompensationCurrency.valueOf(currency);
    return money(CompensationMoney.ofMinor(parsed, minor));
  }

  static MoneyView money(CompensationMoney money) {
    return new MoneyView(money.currency().name(), money.amount());
  }

  static PolicyEntry policy(PolicyRow row, LocalDate today) {
    return new PolicyEntry(
        row.id(),
        row.sequence(),
        row.currency(),
        row.code(),
        row.effectiveFrom(),
        row.effectiveTo(),
        row.enabled(),
        row.minTenureMonths(),
        row.maxPercent(),
        row.absoluteMaxMinor() == null ? null : money(row.currency(), row.absoluteMaxMinor()),
        money(row.currency(), row.minRequestMinor()),
        row.maxOpenRequests(),
        row.waitingDays(),
        row.consentVersion(),
        row.partnerCode(),
        policyStatus(row, today),
        row.createdAt(),
        row.cancelledAt());
  }

  static String policyStatus(PolicyRow row, LocalDate today) {
    if (row.cancelledAt() != null) {
      return "CANCELLED";
    }
    if (row.effectiveFrom().isAfter(today)) {
      return "SCHEDULED";
    }
    if (row.effectiveTo() != null && row.effectiveTo().isBefore(today)) {
      return "ENDED";
    }
    return "IN_FORCE";
  }

  /** A candidate policy as the preview shows it (never stored). */
  static PolicyEntry candidate(PolicyDraft draft, java.util.UUID id, int sequence, LocalDate today,
      java.time.Instant at) {
    String currency = draft.currency().name();
    return new PolicyEntry(
        id,
        sequence,
        currency,
        draft.code(),
        draft.effectiveFrom(),
        draft.effectiveTo(),
        draft.enabled(),
        draft.minTenureMonths(),
        draft.maxPercent(),
        draft.absoluteMaxMinor() == null ? null : money(currency, draft.absoluteMaxMinor()),
        money(currency, draft.minRequestMinor()),
        draft.maxOpenRequests(),
        draft.waitingDays(),
        draft.consentVersion(),
        draft.partnerCode(),
        draft.effectiveFrom().isAfter(today) ? "SCHEDULED" : "IN_FORCE",
        at,
        null);
  }

  static PolicyCommandView command(PolicyDraft draft) {
    String currency = draft.currency().name();
    return new PolicyCommandView(
        currency,
        draft.code(),
        draft.effectiveFrom(),
        draft.effectiveTo(),
        draft.enabled(),
        draft.minTenureMonths(),
        draft.maxPercent(),
        draft.absoluteMaxMinor() == null ? null : money(currency, draft.absoluteMaxMinor()),
        money(currency, draft.minRequestMinor()),
        draft.maxOpenRequests(),
        draft.waitingDays(),
        draft.consentVersion(),
        draft.partnerCode());
  }

  static String month(LocalDate periodStart) {
    return String.format("%04d-%02d", periodStart.getYear(), periodStart.getMonthValue());
  }

  static RequestSummary summary(RequestRow row, boolean administrator) {
    return new RequestSummary(
        row.id(),
        row.status(),
        row.rowVersion(),
        row.currency(),
        money(row.currency(), row.requestedMinor()),
        row.submittedAt(),
        administrator ? row.employeeId() : null,
        administrator ? row.employeeNumber() : null,
        SalaryAdvanceResponses.NOTICE);
  }

  static RequestView view(
      RequestRow row,
      ConsentRow consent,
      List<EventRow> events,
      DecisionRow decision,
      boolean administrator) {
    return new RequestView(
        row.id(),
        row.status(),
        row.rowVersion(),
        row.currency(),
        money(row.currency(), row.requestedMinor()),
        money(row.currency(), row.ceilingMinor()),
        month(row.referencePeriodStart()),
        row.policySequence(),
        row.partnerCode(),
        row.tenureMonths(),
        row.submittedAt(),
        new ConsentEvidence(
            consent.version(), consent.language(), consent.consentedAt(), consent.disclosureSha256()),
        events.stream()
            .map(
                event ->
                    new LifecycleEntry(
                        event.sequence(),
                        event.fromStatus(),
                        event.toStatus(),
                        event.actorRole(),
                        event.occurredAt(),
                        event.reasonCode()))
            .toList(),
        decision == null ? null : decision.reasonCode(),
        administrator && decision != null ? decision.internalNote() : null,
        administrator ? row.employeeId() : null,
        administrator ? row.employeeNumber() : null,
        SalaryAdvanceResponses.NOTICE);
  }
}
