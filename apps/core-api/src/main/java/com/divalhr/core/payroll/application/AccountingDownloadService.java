package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.AccountingCsv;
import com.divalhr.core.payroll.domain.AccountingDigests;
import com.divalhr.core.payroll.domain.AccountingFileNames;
import com.divalhr.core.payroll.domain.AccountingLine;
import com.divalhr.core.payroll.domain.AccountingManifest;
import com.divalhr.core.payroll.internal.JdbcAccountingRepository;
import com.divalhr.core.payroll.internal.JdbcAccountingRepository.JournalRow;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The deterministic CSV and canonical JSON manifest of an accounting journal (MVP-096, ADR 0039
 * section 9), for tenant administrators with privileged MFA. Every download is a fail-closed
 * disclosure audit written before any byte is returned, and is rate limited per subject and per
 * tenant. The CSV is regenerated from the stored lines on every download and must equal the SHA-256
 * and the size stored at generation; a difference is an integrity failure (a safe internal error).
 * Neither file contains the source status or an employee, so both are byte-identical before and
 * after the package is voided. The file name is generated, never built from user text. Nothing of
 * the content is logged.
 */
@Service
public class AccountingDownloadService {

  /** Downloads (interceptor operation and audit action). */
  public static final String DOWNLOAD = AccountingRecorder.JOURNAL_DOWNLOAD;

  /** Per-subject and per-tenant downloads (shared with the reads). */
  public static final String READ_BUCKET = AccountingMappingService.READ_BUCKET;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.accounting-download");
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  /** A generated journal file: the content and a generated safe filename. */
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
      return "AccountingDownload[" + filename + "]";
    }
  }

  private final JdbcAccountingRepository accounting;
  private final PayrollEntities entities;
  private final CompensationCalendar calendar;
  private final AccountingRecorder recorder;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param accounting accounting storage
   * @param entities legal entity resolver
   * @param calendar business instant
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   */
  public AccountingDownloadService(
      JdbcAccountingRepository accounting,
      PayrollEntities entities,
      CompensationCalendar calendar,
      AccountingRecorder recorder,
      PayrollOperations plumbing) {
    this.accounting = accounting;
    this.entities = entities;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
  }

  /**
   * The CSV of a journal.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param journalId raw path value
   * @return the CSV, after its disclosure audit committed
   */
  public Download csv(PayrollCaller caller, String legalEntityId, String journalId) {
    return download(caller, legalEntityId, journalId, false);
  }

  /**
   * The JSON manifest of a journal.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param journalId raw path value
   * @return the manifest, after its disclosure audit committed
   */
  public Download manifest(PayrollCaller caller, String legalEntityId, String journalId) {
    return download(caller, legalEntityId, journalId, true);
  }

  private Download download(
      PayrollCaller caller, String legalEntityId, String journalId, boolean manifest) {
    return plumbing.observed(
        LOG,
        "accounting_journal_download",
        DOWNLOAD,
        () -> {
          UUID entityId = AccountingCommands.journalScopeId(legalEntityId);
          UUID id = AccountingCommands.journalScopeId(journalId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, entityId, ErrorCode.ACCOUNTING_JOURNAL_NOT_FOUND);
                JournalRow row =
                    accounting
                        .journal(tenant, entity, id)
                        .orElseThrow(AccountingCommands::journalNotFound);
                List<AccountingLine> lines = accounting.lines(tenant, row.id());
                if (lines.size() != row.lineCount()) {
                  throw new IllegalStateException("accounting journal lines do not match");
                }
                byte[] csv =
                    AccountingCsv.csv(row.id(), row.packageId(), entity, row.currency(), lines);
                if (csv.length != row.csvBytes()
                    || !AccountingDigests.sha256(csv).equals(row.csvSha256())) {
                  throw new IllegalStateException("accounting journal CSV does not match");
                }
                String period = YearMonth.from(row.periodStart()).toString();
                String csvName =
                    AccountingFileNames.csv(
                        row.legalEntityCode(), period, row.currency(), row.version());
                Download file =
                    manifest
                        ? new Download(
                            AccountingManifest.manifest(facts(row, entity, period, csvName)),
                            AccountingFileNames.manifest(
                                row.legalEntityCode(), period, row.currency(), row.version()))
                        : new Download(csv, csvName);
                recorder.disclosed(
                    caller,
                    DOWNLOAD,
                    row.id(),
                    Map.of(
                        "legalEntityId",
                        entity.toString(),
                        "journalId",
                        row.id().toString(),
                        "currency",
                        row.currency()),
                    manifest ? "manifest" : "csv",
                    lines.size(),
                    calendar.now());
                return file;
              });
        });
  }

  private static AccountingManifest.Facts facts(
      JournalRow row, UUID entity, String period, String csvName) {
    return new AccountingManifest.Facts(
        row.id(),
        row.version(),
        row.packageId(),
        row.packageVersion(),
        row.calculationId(),
        row.calculationVersion(),
        entity,
        row.legalEntityCode(),
        period,
        row.currency(),
        row.mappingId(),
        row.mappingVersion(),
        row.mappingDigest(),
        row.lineCount(),
        row.totalDebitsMinor(),
        row.totalCreditsMinor(),
        row.generatedAt(),
        csvName,
        row.csvBytes(),
        row.csvSha256());
  }
}
