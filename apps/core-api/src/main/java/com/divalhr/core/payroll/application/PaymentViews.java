package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.PaymentResponses.BatchLine;
import com.divalhr.core.payroll.api.PaymentResponses.BatchSummary;
import com.divalhr.core.payroll.api.PaymentResponses.DestinationView;
import com.divalhr.core.payroll.api.PaymentResponses.MethodCounts;
import com.divalhr.core.payroll.api.PaymentResponses.Provider;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Destination;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Line;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.BatchRow;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.DestinationRow;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.LineRow;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Maps payment rows to the masked response views (MVP-095A). Only masked values reach a view; the
 * account, phone, holder and branch exist only in the sealed envelope.
 */
final class PaymentViews {

  /** The only currency of a payment batch in this release. */
  static final String CURRENCY = "CDF";

  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

  private PaymentViews() {}

  static String month(LocalDate periodStart) {
    return MONTH.format(periodStart);
  }

  static Provider provider(String code, String name) {
    return code == null ? null : new Provider(code, name);
  }

  static DestinationView destination(DestinationRow row) {
    return new DestinationView(
        row.id(),
        row.sequence(),
        row.method(),
        row.country(),
        provider(row.providerCode(), row.providerName()),
        row.masked(),
        row.effectiveFrom(),
        "RECORDED",
        row.reasonCode(),
        row.recordedAt());
  }

  static MethodCounts counts(int bank, int mobile, int manual) {
    return new MethodCounts(bank, mobile, manual);
  }

  static BatchSummary summary(BatchRow row) {
    return new BatchSummary(
        row.id(),
        row.legalEntityId(),
        row.packageId(),
        row.packageVersion(),
        row.version(),
        row.status(),
        month(row.periodStart()),
        row.currency(),
        row.lineCount(),
        row.zeroNetExcluded(),
        counts(row.bank(), row.mobile(), row.manual()),
        row.totalNet(),
        row.preparedAt(),
        row.cancelledAt(),
        row.cancelReason());
  }

  static BatchLine line(LineRow row) {
    return new BatchLine(
        row.payslipId(),
        row.employeeId(),
        row.employeeNumber(),
        row.method(),
        provider(row.providerCode(), row.providerName()),
        row.masked(),
        row.destinationSequence(),
        row.net(),
        null);
  }

  static BatchLine line(Line line) {
    Destination destination = line.destination();
    return new BatchLine(
        line.payslipId(),
        line.employeeId(),
        line.employeeNumber(),
        destination == null ? null : destination.method().name(),
        destination == null
            ? null
            : provider(destination.providerCode(), destination.providerName()),
        destination == null ? null : destination.masked(),
        destination == null ? null : destination.sequence(),
        line.net(),
        line.issue() == null ? null : line.issue().name());
  }

  static String countBucket(int count) {
    if (count == 0) {
      return "0";
    }
    if (count <= 10) {
      return "1-10";
    }
    if (count <= 100) {
      return "11-100";
    }
    if (count <= 1000) {
      return "101-1000";
    }
    return "1001+";
  }
}
