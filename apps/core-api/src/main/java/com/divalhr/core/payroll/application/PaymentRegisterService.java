package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PaymentMethod;
import com.divalhr.core.payroll.domain.PaymentRegister;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.BatchRow;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.LineRow;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantId;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The deterministic payment register of a batch: a UTF-8 CSV and a canonical JSON manifest (MVP-095A,
 * D95A-7), for tenant administrators with privileged MFA. Every download is a fail-closed
 * disclosure audit (counts only) and is rate limited per subject and per tenant. The register
 * states masked destinations and approved nets; it is an instruction-preparation artifact, not
 * proof of transmission or payment. The filename is generated from the period and the batch ID
 * prefix, never from a name, a number or an amount. Restricted financial and HR data: nothing of
 * the content is logged.
 */
@Service
public class PaymentRegisterService {

  /** Downloads (interceptor operation and audit action). */
  public static final String DOWNLOAD = PaymentRecorder.REGISTER_DOWNLOAD;

  /** Per-subject downloads. */
  public static final String SUBJECT_BUCKET = "payment-register-download";

  /** Per-tenant downloads. */
  public static final String TENANT_BUCKET = "payment-register-download";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payment-register");
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  /** A generated register file: the content and a generated safe filename. */
  public static final class Download {

    private final byte[] bytes;
    private final String filename;

    Download(byte[] bytes, String filename) {
      this.bytes = bytes.clone();
      this.filename = filename;
    }

    /**
     * The content.
     *
     * @return a copy of the bytes
     */
    public byte[] bytes() {
      return bytes.clone();
    }

    /**
     * The generated filename.
     *
     * @return the filename
     */
    public String filename() {
      return filename;
    }

    @Override
    public String toString() {
      return "PaymentRegisterDownload[" + filename + "]";
    }
  }

  private final JdbcPaymentRepository payments;
  private final PayrollEntities entities;
  private final CompensationCalendar calendar;
  private final PaymentRecorder recorder;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param payments payment storage
   * @param entities legal entity resolver
   * @param calendar business instant
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   */
  public PaymentRegisterService(
      JdbcPaymentRepository payments,
      PayrollEntities entities,
      CompensationCalendar calendar,
      PaymentRecorder recorder,
      PayrollOperations plumbing) {
    this.payments = payments;
    this.entities = entities;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
  }

  /**
   * The CSV register of a batch.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @return the CSV, after its disclosure audit committed
   */
  public Download csv(PayrollCaller caller, String legalEntityId, String batchId) {
    return download(caller, legalEntityId, batchId, false);
  }

  /**
   * The JSON manifest of a batch (counts, totals, the source digest and the CSV SHA-256).
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @return the manifest, after its disclosure audit committed
   */
  public Download manifest(PayrollCaller caller, String legalEntityId, String batchId) {
    return download(caller, legalEntityId, batchId, true);
  }

  private Download download(
      PayrollCaller caller, String legalEntityId, String batchId, boolean manifest) {
    return plumbing.observed(
        LOG,
        "payment_register_download",
        DOWNLOAD,
        () -> {
          UUID entityId = PayrollCommands.id(legalEntityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
          UUID id = PaymentCommands.batchId(batchId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
                BatchRow batch =
                    payments
                        .batch(tenant, entity, id)
                        .orElseThrow(
                            () -> new ApiException(ErrorCode.PAYMENT_BATCH_NOT_FOUND, Map.of()));
                List<PaymentRegister.Row> rows = new ArrayList<>();
                for (LineRow line : payments.linePage(tenant, entity, id, null, batch.lineCount() + 1)) {
                  rows.add(
                      new PaymentRegister.Row(
                          line.employeeNumber(),
                          PaymentMethod.parse(line.method()).orElseThrow(),
                          line.providerCode(),
                          line.masked(),
                          line.net()));
                }
                if (rows.size() != batch.lineCount()) {
                  throw new IllegalStateException("payment batch lines do not match the batch");
                }
                String period = PaymentViews.month(batch.periodStart());
                PaymentRegister.Header header =
                    new PaymentRegister.Header(
                        entity,
                        batch.packageId(),
                        batch.packageVersion(),
                        batch.packageDigest(),
                        batch.id(),
                        batch.version(),
                        batch.status(),
                        period,
                        batch.currency(),
                        batch.lineCount(),
                        batch.zeroNetExcluded(),
                        batch.bank(),
                        batch.mobile(),
                        batch.manual(),
                        batch.totalNet());
                byte[] csv = PaymentRegister.csv(header, rows);
                String stem = "payment-register-" + period + "-" + id.toString().substring(0, 8);
                Download file =
                    manifest
                        ? new Download(
                            PaymentRegister.manifest(header, sha256(csv)), stem + ".json")
                        : new Download(csv, stem + ".csv");
                recorder.disclosed(
                    caller,
                    DOWNLOAD,
                    "payment-batch",
                    id,
                    Map.of("legalEntityId", entity.toString(), "batchId", id.toString()),
                    manifest ? "manifest" : "csv",
                    true,
                    rows.size(),
                    calendar.now());
                return file;
              });
        });
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
