package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceResponses.ConsentDocument;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.EligibilityEntry;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.PolicyRef;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.SalaryAdvanceReason;
import com.divalhr.core.payroll.domain.SalaryAdvanceRules;
import com.divalhr.core.payroll.domain.SalaryAdvanceRules.Facts;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.PolicyRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.ReferenceRow;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The deterministic, closed employer-policy eligibility of an employee (MVP-120A, D120A-4 and
 * D120A-5). It reads the employee's own facts, applies {@link SalaryAdvanceRules} and returns the
 * stable reason codes. It makes no credit decision, reads no bureau and no external source, and
 * never returns the salary. The V52 guard recomputes the same facts at submission.
 */
@Component
class SalaryAdvanceEligibility {

  /**
   * What an evaluation found.
   *
   * @param currency currency
   * @param reasons closed reasons, in enum order
   * @param policy the policy in force, or {@code null}
   * @param reference the operational payroll reference, or {@code null}
   * @param legalEntityId the placement legal entity, or {@code null}
   * @param employmentStart covering employment start, or {@code null}
   * @param tenureMonths completed months
   * @param ceilingMinor the employer-policy ceiling, or {@code null} when unknown
   * @param openRequests open requests
   * @param waitingUntil first day a request is allowed again, or {@code null}
   */
  record Evaluation(
      CompensationCurrency currency,
      List<SalaryAdvanceReason> reasons,
      PolicyRow policy,
      ReferenceRow reference,
      UUID legalEntityId,
      LocalDate employmentStart,
      int tenureMonths,
      Long ceilingMinor,
      int openRequests,
      LocalDate waitingUntil) {

    boolean eligible() {
      return reasons.isEmpty();
    }

    @Override
    public String toString() {
      return "SalaryAdvanceEvaluation[" + currency + "]";
    }
  }

  private final JdbcSalaryAdvanceRepository repository;

  SalaryAdvanceEligibility(JdbcSalaryAdvanceRepository repository) {
    this.repository = repository;
  }

  /**
   * Evaluates one currency for one employee.
   *
   * @param tenant verified tenant
   * @param employeeId the employee
   * @param currency currency
   * @param today the tenant business date
   * @param now server instant
   * @param requestedMinor the requested amount, or {@code null} for the plain eligibility
   * @return the evaluation
   */
  Evaluation evaluate(
      TenantId tenant,
      UUID employeeId,
      CompensationCurrency currency,
      LocalDate today,
      Instant now,
      Long requestedMinor) {
    String code = currency.name();
    Optional<LocalDate> start = repository.employmentStart(tenant, employeeId, today);
    Optional<UUID> entity =
        start.isPresent()
            ? repository.placementEntity(tenant, employeeId, today)
            : Optional.empty();
    PolicyRow policy =
        entity.flatMap(id -> repository.applicablePolicy(tenant, id, code, today)).orElse(null);
    ReferenceRow reference =
        entity
            .flatMap(id -> repository.reference(tenant, employeeId, id, code))
            .filter(ReferenceRow::operational)
            .orElse(null);
    int tenure = start.map(date -> SalaryAdvanceRules.tenureMonths(date, today)).orElse(0);
    int open = repository.openCount(tenant, employeeId);
    int recent =
        repository.submittedSince(
            tenant, employeeId, now.atOffset(ZoneOffset.UTC).minusMonths(12).toInstant());
    LocalDate waitingUntil = null;
    if (policy != null && entity.isPresent()) {
      waitingUntil =
          repository
              .lastBlockedOn(tenant, employeeId, entity.get(), code)
              .map(date -> date.plusDays(policy.waitingDays()))
              .orElse(null);
    }
    Long ceiling = null;
    if (policy != null && reference != null) {
      ceiling =
          SalaryAdvanceRules.ceiling(
              reference.netMinor(), policy.maxPercent(), policy.absoluteMaxMinor());
    }
    Facts facts =
        new Facts(
            policy != null,
            start.isPresent(),
            entity.isPresent() && policy != null,
            tenure,
            policy == null ? 0 : policy.minTenureMonths(),
            reference != null,
            true,
            open,
            policy == null ? SalaryAdvanceRules.MAX_OPEN_REQUESTS : policy.maxOpenRequests(),
            recent,
            waitingUntil,
            today);
    Set<SalaryAdvanceReason> reasons = EnumSet.noneOf(SalaryAdvanceReason.class);
    reasons.addAll(SalaryAdvanceRules.reasons(facts));
    if (policy != null && ceiling != null) {
      if (requestedMinor != null) {
        SalaryAdvanceRules.amountReason(requestedMinor, policy.minRequestMinor(), ceiling)
            .ifPresent(reasons::add);
      } else if (ceiling < policy.minRequestMinor()) {
        reasons.add(SalaryAdvanceReason.AMOUNT_OUT_OF_RANGE);
      }
    }
    return new Evaluation(
        currency,
        List.copyOf(reasons),
        policy,
        reference,
        entity.orElse(null),
        start.orElse(null),
        tenure,
        ceiling,
        open,
        waitingUntil);
  }

  /**
   * The response entry of an evaluation. The ceiling is the employer-policy ceiling, shown only
   * when the employee is eligible or the only blockers concern the amount; the salary never.
   *
   * @param evaluation the evaluation
   * @return the entry
   */
  static EligibilityEntry entry(Evaluation evaluation) {
    PolicyRow policy = evaluation.policy();
    boolean amountOnly =
        evaluation.reasons().stream().allMatch(r -> r == SalaryAdvanceReason.AMOUNT_OUT_OF_RANGE);
    boolean showCeiling =
        evaluation.ceilingMinor() != null && evaluation.ceilingMinor() >= 1 && amountOnly;
    String code = evaluation.currency().name();
    ConsentDocument consent =
        policy != null && showCeiling
            ? SalaryAdvanceConsent.document(policy.consentVersion()).orElse(null)
            : null;
    return new EligibilityEntry(
        code,
        evaluation.eligible() ? "ELIGIBLE" : "NOT_ELIGIBLE",
        evaluation.reasons().stream().map(SalaryAdvanceReason::name).toList(),
        policy == null
            ? null
            : new PolicyRef(
                policy.id(),
                policy.sequence(),
                policy.maxOpenRequests(),
                policy.waitingDays(),
                policy.consentVersion(),
                policy.partnerCode()),
        evaluation.reference() == null || policy == null
            ? null
            : SalaryAdvanceViews.month(evaluation.reference().periodStart()),
        showCeiling ? SalaryAdvanceViews.money(code, evaluation.ceilingMinor()) : null,
        policy == null ? null : SalaryAdvanceViews.money(code, policy.minRequestMinor()),
        evaluation.openRequests(),
        evaluation.waitingUntil(),
        consent);
  }
}
