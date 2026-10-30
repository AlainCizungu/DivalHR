package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.PayrollCalculator.Employee;
import com.divalhr.core.payroll.domain.PayrollCalculator.Employment;
import com.divalhr.core.payroll.domain.PayrollCalculator.Fact;
import com.divalhr.core.payroll.domain.PayrollCalculator.Placement;
import com.divalhr.core.payroll.domain.PayrollPeriod;
import com.divalhr.core.payroll.domain.PayrollPolicy;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The stateless digests of payroll previews (MVP-094A, D94A-5; ADR 0007, ADR 0033): a SHA-256 over
 * a versioned canonical text. The input digest covers the tenant, legal entity, month, currency,
 * the policy (identifier and sequence) and every source record read for the calculation (each
 * employment, placement and compensation fact of every member of the population, by identifier and
 * version); the preview digest binds the command, the version, the input digest and the expiry. A
 * create recomputes both from the current state; any difference is a stale preview. The digests are
 * only compared and hashed, never logged, audited with their input or stored beside an amount.
 */
final class PayrollDigests {

  private PayrollDigests() {}

  /**
   * The digest of everything that fed a run calculation.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param period the month
   * @param currency the currency
   * @param policy the policy in force on the last day of the month
   * @param employees the population with all its source records
   * @return the digest
   */
  static String input(
      TenantId tenant,
      UUID legalEntityId,
      PayrollPeriod period,
      CompensationCurrency currency,
      PayrollPolicy policy,
      List<Employee> employees) {
    StringBuilder text = new StringBuilder("DIVALHR-PAYROLL-RUN-INPUT\nversion=1\n");
    text.append("tenant=").append(tenant).append('\n');
    text.append("entity=").append(legalEntityId).append('\n');
    text.append("period=").append(period.text()).append('\n');
    text.append("currency=").append(currency).append('\n');
    text.append("policy=").append(policy.id()).append(';').append(policy.sequence()).append('\n');
    List<Employee> ordered = new ArrayList<>(employees);
    ordered.sort(Comparator.comparing(Employee::id));
    for (Employee employee : ordered) {
      text.append("employee=").append(employee.id()).append(';').append(employee.number());
      text.append('\n');
      List<Employment> employments = new ArrayList<>(employee.employments());
      employments.sort(Comparator.comparing(Employment::start).thenComparing(Employment::id));
      for (Employment employment : employments) {
        text.append("employment=")
            .append(employment.id())
            .append(';')
            .append(employment.version())
            .append(';')
            .append(employment.start())
            .append(';')
            .append(day(employment.end()))
            .append('\n');
      }
      List<Placement> placements = new ArrayList<>(employee.placements());
      placements.sort(Comparator.comparing(Placement::start).thenComparing(Placement::id));
      for (Placement placement : placements) {
        text.append("placement=")
            .append(placement.id())
            .append(';')
            .append(placement.legalEntityId())
            .append(';')
            .append(placement.start())
            .append(';')
            .append(day(placement.end()))
            .append('\n');
      }
      List<Fact> facts = new ArrayList<>(employee.facts());
      facts.sort(Comparator.comparing(Fact::from).thenComparing(Fact::id));
      for (Fact fact : facts) {
        text.append("fact=")
            .append(fact.id())
            .append(';')
            .append(fact.originChangeId())
            .append(';')
            .append(fact.version())
            .append(';')
            .append(fact.currency())
            .append(';')
            .append(fact.minor())
            .append(';')
            .append(fact.from())
            .append(';')
            .append(day(fact.to()))
            .append('\n');
      }
    }
    return Fingerprints.sha256(text.toString());
  }

  /**
   * The digest of a previewed run command.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param period the month
   * @param currency the currency
   * @param expectedVersion the chain version
   * @param inputDigest the input digest
   * @param expiresAt expiry
   * @return the digest
   */
  static String run(
      TenantId tenant,
      UUID legalEntityId,
      PayrollPeriod period,
      CompensationCurrency currency,
      int expectedVersion,
      String inputDigest,
      Instant expiresAt) {
    String text =
        "DIVALHR-PAYROLL-RUN-PREVIEW\nversion=1\ntenant="
            + tenant
            + "\nentity="
            + legalEntityId
            + "\nperiod="
            + period.text()
            + "\ncurrency="
            + currency
            + "\nexpectedVersion="
            + expectedVersion
            + "\ninput="
            + inputDigest
            + "\nexpiresAt="
            + expiresAt
            + "\n";
    return Fingerprints.sha256(text);
  }

  /**
   * The digest of a previewed policy command.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param draft the command
   * @param version the policy version
   * @param expiresAt expiry
   * @return the digest
   */
  static String policy(
      TenantId tenant,
      UUID legalEntityId,
      PayrollCommands.PolicyDraft draft,
      int version,
      Instant expiresAt) {
    String text =
        "DIVALHR-PAYROLL-POLICY-PREVIEW\nversion=1\ntenant="
            + tenant
            + "\nentity="
            + legalEntityId
            + "\neffectiveFrom="
            + draft.effectiveFrom()
            + "\npayDay="
            + draft.payDay().kind()
            + ';'
            + (draft.payDay().day() == null ? "-" : draft.payDay().day().toString())
            + "\nmethod="
            + draft.method()
            + "\npolicyVersion="
            + version
            + "\nexpiresAt="
            + expiresAt
            + "\n";
    return Fingerprints.sha256(text);
  }

  private static String day(LocalDate date) {
    return date == null ? "-" : date.toString();
  }
}
