package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PaymentDestinationDraft;
import com.divalhr.core.payroll.domain.PaymentMethod;
import com.divalhr.core.payroll.domain.PaymentSealException;
import com.divalhr.core.payroll.domain.PaymentSubmissionPayload;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.DestinationRow;
import com.divalhr.core.payroll.internal.JdbcPaymentSubmissionRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentSubmissionRepository.PayloadLine;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the outbound provider bytes of a batch (MVP-095B, ADR 0037 section 4). Destination
 * plaintext is opened here, only while the bytes are built, and is never stored or returned: the
 * caller wipes the buffer after use. One unreadable or unmappable line fails the whole build
 * before any network access.
 */
@Component
public class PaymentSubmissionPayloads {

  /** A line that cannot be mapped or authenticated; carries no value. */
  public static final class Unreadable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Unreadable() {
      super("a destination cannot be read", null, false, false);
    }
  }

  private final JdbcPaymentSubmissionRepository submissions;
  private final JdbcPaymentRepository payments;
  private final PaymentSecrets secrets;

  /**
   * Creates the builder.
   *
   * @param submissions submission storage (batch lines)
   * @param payments payment storage (sealed destinations)
   * @param secrets the destination cipher
   */
  public PaymentSubmissionPayloads(
      JdbcPaymentSubmissionRepository submissions,
      JdbcPaymentRepository payments,
      PaymentSecrets secrets) {
    this.submissions = submissions;
    this.payments = payments;
    this.secrets = secrets;
  }

  /**
   * Builds the canonical bytes.
   *
   * @param tenant verified tenant
   * @param entity legal entity
   * @param batchId batch
   * @param requestId stable provider request ID
   * @param batchDigest batch digest
   * @param currency batch currency
   * @param totalMinor batch total
   * @return the bytes (the caller wipes them)
   * @throws Unreadable when a destination cannot be opened, authenticated or mapped
   */
  public byte[] build(
      TenantId tenant,
      UUID entity,
      UUID batchId,
      UUID requestId,
      String batchDigest,
      String currency,
      long totalMinor) {
    if (!secrets.available()) {
      throw new Unreadable();
    }
    List<PaymentSubmissionPayload.Line> lines = new ArrayList<>();
    for (PayloadLine line : submissions.payloadLines(tenant, entity, batchId)) {
      lines.add(map(tenant, line));
    }
    try {
      return PaymentSubmissionPayload.canonical(requestId, batchDigest, currency, totalMinor, lines);
    } catch (IllegalArgumentException | ArithmeticException unmappable) {
      throw new Unreadable();
    }
  }

  private PaymentSubmissionPayload.Line map(TenantId tenant, PayloadLine line) {
    PaymentMethod method = PaymentMethod.parse(line.method()).orElse(null);
    if (method == null
        || method == PaymentMethod.MANUAL
        || line.destinationId() == null
        || line.destinationSequence() == null
        || line.providerCode() == null
        || line.country() == null) {
      throw new Unreadable();
    }
    DestinationRow row =
        payments.byId(tenant, line.employeeId(), line.destinationId()).orElse(null);
    if (row == null
        || row.sealedHex() == null
        || row.sequence() != line.destinationSequence()
        || !method.name().equals(row.method())) {
      throw new Unreadable();
    }
    String text;
    try {
      text =
          secrets.open(
              row.sealed(), tenant.value(), row.employeeId(), row.id(), row.sequence());
    } catch (PaymentSealException tampered) {
      throw new Unreadable();
    }
    Map<String, String> fields = PaymentDestinationDraft.parseSealed(text).orElse(null);
    if (fields == null) {
      throw new Unreadable();
    }
    String holder = fields.get("holder");
    String account = fields.get("account");
    String phone = fields.get("phone");
    if (holder == null
        || (method == PaymentMethod.BANK_ACCOUNT && account == null)
        || (method == PaymentMethod.MOBILE_MONEY && phone == null)) {
      throw new Unreadable();
    }
    return new PaymentSubmissionPayload.Line(
        line.id(),
        line.net(),
        method,
        line.providerCode(),
        line.country(),
        account,
        fields.get("branch"),
        phone,
        holder);
  }
}
