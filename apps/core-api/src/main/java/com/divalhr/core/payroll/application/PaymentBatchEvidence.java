package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PaymentBatchEvaluator;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Destination;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Evaluation;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Slip;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Source;
import com.divalhr.core.payroll.domain.PaymentDestinationDraft;
import com.divalhr.core.payroll.domain.PaymentDigests;
import com.divalhr.core.payroll.domain.PaymentMasking;
import com.divalhr.core.payroll.domain.PaymentMethod;
import com.divalhr.core.payroll.domain.PaymentSealException;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.DestinationRow;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.SlipFacts;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.PackageRow;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Loads and evaluates the evidence of a payment batch (MVP-095A, D95A-4/5): the approved package,
 * its payslips, the destination effective on the package's approval date for each payable employee,
 * and the state of the batch chain. Nothing is recalculated and no currency is converted: the
 * amounts are the approved nets, copied. Every destination is authenticated and checked against its
 * plain columns, so a tampered row is a blocker, never a payable line. The caller holds the locks
 * it needs; this class takes none. Restricted financial and HR data: nothing it returns prints a
 * value.
 */
@Component
class PaymentBatchEvidence {

  private final JdbcPaymentRepository payments;
  private final JdbcPayrollPackageRepository packages;
  private final CompensationCalendar calendar;
  private final PaymentSecrets secrets;

  PaymentBatchEvidence(
      JdbcPaymentRepository payments,
      JdbcPayrollPackageRepository packages,
      CompensationCalendar calendar,
      PaymentSecrets secrets) {
    this.payments = payments;
    this.packages = packages;
    this.calendar = calendar;
    this.secrets = secrets;
  }

  /**
   * The evaluated evidence.
   *
   * @param pack the package
   * @param evaluation the evaluation
   * @param chainVersion the highest batch version of the package
   * @param destinationsDigest digest of the destinations used
   * @param linesDigest digest of the lines
   */
  record Loaded(
      PackageRow pack,
      Evaluation evaluation,
      int chainVersion,
      String destinationsDigest,
      String linesDigest) {

    @Override
    public String toString() {
      return "PaymentBatchEvidence[" + pack.id() + "]";
    }
  }

  /**
   * Loads and evaluates a package.
   *
   * @param tenant verified tenant
   * @param entity legal entity
   * @param packageId package
   * @return the evidence
   * @throws ApiException 404 {@code PAYROLL_PACKAGE_NOT_FOUND} for an unknown package, 503 when a
   *     sealed destination needs a key that is not available
   */
  Loaded load(TenantId tenant, UUID entity, UUID packageId) {
    PackageRow pack =
        packages
            .pack(tenant, entity, packageId)
            .orElseThrow(() -> new ApiException(ErrorCode.PAYROLL_PACKAGE_NOT_FOUND, Map.of()));
    LocalDate approvalDate = pack.approvedAt().atZone(calendar.zone(tenant)).toLocalDate();
    LocalDate today = calendar.today(tenant);
    List<Slip> slips = new ArrayList<>();
    for (SlipFacts slip : payments.slips(tenant, entity, packageId)) {
      slips.add(
          new Slip(
              slip.id(),
              slip.employeeId(),
              slip.employeeNumber(),
              slip.grossMinor(),
              slip.deductionsMinor(),
              slip.netMinor(),
              slip.excluded()));
    }
    Map<UUID, DestinationRow> atApproval =
        payments.effectiveForPackage(tenant, packageId, approvalDate);
    Map<UUID, DestinationRow> now =
        approvalDate.equals(today)
            ? atApproval
            : payments.effectiveForPackage(tenant, packageId, today);
    Map<UUID, Destination> destinations = new HashMap<>();
    for (Map.Entry<UUID, DestinationRow> entry : atApproval.entrySet()) {
      DestinationRow row = entry.getValue();
      DestinationRow latest = now.get(entry.getKey());
      boolean changed = latest != null && !latest.id().equals(row.id());
      destinations.put(entry.getKey(), destination(tenant, row, changed));
    }
    int latestVersion =
        payments.latestPackageVersion(tenant, entity, pack.periodStart(), pack.currency());
    int chain = payments.chainVersion(tenant, packageId);
    Source source =
        new Source(
            "APPROVED".equals(pack.status()),
            pack.version() == latestVersion,
            payments.preparedExists(tenant, entity, packageId),
            chain);
    Evaluation evaluation = PaymentBatchEvaluator.evaluate(source, slips, destinations);
    return new Loaded(
        pack,
        evaluation,
        chain,
        PaymentDigests.destinations(evaluation.lines()),
        PaymentDigests.lines(evaluation.lines()));
  }

  private Destination destination(TenantId tenant, DestinationRow row, boolean changed) {
    PaymentMethod method = PaymentMethod.parse(row.method()).orElse(null);
    boolean authentic = method != null && authentic(tenant, row, method);
    return new Destination(
        row.id(),
        row.sequence(),
        method == null ? PaymentMethod.MANUAL : method,
        row.country(),
        row.providerCode(),
        row.providerName(),
        row.masked(),
        row.fingerprint(),
        authentic,
        changed);
  }

  /** Whether the envelope authenticates and its content matches the plain columns. */
  private boolean authentic(TenantId tenant, DestinationRow row, PaymentMethod method) {
    if (!method.hasAccount()) {
      return row.sealedHex() == null;
    }
    if (row.sealedHex() == null) {
      return false;
    }
    if (!secrets.available()) {
      throw new ApiException(ErrorCode.PAYMENT_DESTINATION_KEY_UNAVAILABLE, Map.of());
    }
    String text;
    try {
      text = secrets.open(row.sealed(), tenant.value(), row.employeeId(), row.id(), row.sequence());
    } catch (PaymentSealException tampered) {
      return false;
    }
    Map<String, String> fields = PaymentDestinationDraft.parseSealed(text).orElse(null);
    if (fields == null) {
      return false;
    }
    String identifier =
        method == PaymentMethod.BANK_ACCOUNT ? fields.get("account") : fields.get("phone");
    if (identifier == null || identifier.length() < 3 || row.providerCode() == null) {
      return false;
    }
    String masked =
        method == PaymentMethod.BANK_ACCOUNT
            ? PaymentMasking.account(identifier)
            : PaymentMasking.phone(identifier);
    String fingerprint =
        secrets.fingerprint(
            tenant.value(),
            "identifier",
            method.name() + ":" + row.providerCode() + ":" + identifier);
    return masked.equals(row.masked()) && fingerprint.equals(row.fingerprint());
  }
}
