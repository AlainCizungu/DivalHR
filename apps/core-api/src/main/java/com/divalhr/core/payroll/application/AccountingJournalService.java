package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.AccountingResponses.JournalList;
import com.divalhr.core.payroll.api.AccountingResponses.JournalPreview;
import com.divalhr.core.payroll.api.AccountingResponses.JournalView;
import com.divalhr.core.payroll.api.AccountingResponses.Line;
import com.divalhr.core.payroll.api.AccountingResponses.MappingRef;
import com.divalhr.core.payroll.api.AccountingResponses.Receipt;
import com.divalhr.core.payroll.api.AccountingResponses.Summary;
import com.divalhr.core.payroll.api.CommitAccountingJournalRequest;
import com.divalhr.core.payroll.application.AccountingCommands.JournalCommit;
import com.divalhr.core.payroll.domain.AccountingCsv;
import com.divalhr.core.payroll.domain.AccountingDigests;
import com.divalhr.core.payroll.domain.AccountingDigests.JournalFacts;
import com.divalhr.core.payroll.domain.AccountingJournalBlocker;
import com.divalhr.core.payroll.domain.AccountingJournalBuilder;
import com.divalhr.core.payroll.domain.AccountingLine;
import com.divalhr.core.payroll.internal.AccountingConstraintViolations;
import com.divalhr.core.payroll.internal.JdbcAccountingRepository;
import com.divalhr.core.payroll.internal.JdbcAccountingRepository.JournalRow;
import com.divalhr.core.payroll.internal.JdbcAccountingRepository.MappingRow;
import com.divalhr.core.payroll.internal.JdbcAccountingRepository.NewJournal;
import com.divalhr.core.payroll.internal.JdbcAccountingRepository.PackageRow;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.PayrollPackageConstraintViolations;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Accounting journals (MVP-096, ADR 0039 sections 2, 4, 5 and 6): the preview, the idempotent
 * generation of exactly one immutable, balanced journal per approved payroll package, the history
 * of a package and the journal itself, for tenant administrators with privileged MFA. Authorization
 * has run before this; the tenant and the actor never come from the request.
 *
 * <p>Boundary: the journal is derived once from the frozen approval evidence of the package (its
 * header and the four amount columns of its payslips) and the applicable mapping version; nothing
 * is recalculated, no currency is converted and no payroll, payslip, payment or reconciliation row
 * is written. It is an accounting-ready export: not posted anywhere and not proof of payment.
 * Amounts, account codes and digests never enter an audit record, an event, a log or an error.
 */
@Service
public class AccountingJournalService {

  /** Journal previews (interceptor operation). */
  public static final String PREVIEW = AccountingRecorder.JOURNAL_PREVIEW;

  /** Journal generation (idempotency operation, interceptor operation and audit action). */
  public static final String GENERATE = AccountingRecorder.JOURNAL_GENERATE;

  /** Journal reads and history (interceptor operation and audit action). */
  public static final String READ = AccountingRecorder.JOURNAL_READ;

  /** Per-subject previews (mapping and journal previews share it). */
  public static final String PREVIEW_BUCKET = AccountingMappingService.PREVIEW_BUCKET;

  /** Per-subject burst and per-tenant hourly generations. */
  public static final String WRITE_BUCKET = "accounting-journal-write";

  /** Per-subject and per-tenant reads (shared with the mapping reads and the downloads). */
  public static final String READ_BUCKET = AccountingMappingService.READ_BUCKET;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.accounting-journal");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration COMMIT_LIMIT = Duration.ofSeconds(25);
  private static final int MAX_PAYSLIPS = 5_000;
  private static final IdempotentOperation.Spec GENERATE_SPEC =
      new IdempotentOperation.Spec(GENERATE, "accounting_journal", "generate", "generated", 201);

  private final IdempotentOperation operations;
  private final JdbcAccountingRepository accounting;
  private final JdbcPayrollRunRepository runs;
  private final PayrollEntities entities;
  private final CompensationCalendar calendar;
  private final AccountingQuotas quotas;
  private final AccountingRecorder recorder;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param accounting accounting storage
   * @param runs payroll storage (transaction limits)
   * @param entities legal entity resolver
   * @param calendar clock and preview window
   * @param quotas the hourly preview quota
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   */
  public AccountingJournalService(
      IdempotentOperation operations,
      JdbcAccountingRepository accounting,
      JdbcPayrollRunRepository runs,
      PayrollEntities entities,
      CompensationCalendar calendar,
      AccountingQuotas quotas,
      AccountingRecorder recorder,
      PayrollOperations plumbing) {
    this.operations = operations;
    this.accounting = accounting;
    this.runs = runs;
    this.entities = entities;
    this.calendar = calendar;
    this.quotas = quotas;
    this.recorder = recorder;
    this.plumbing = plumbing;
  }

  /** Everything a journal depends on, read and decided; writes nothing. */
  private record Evaluation(
      PackageRow source,
      MappingRow mapping,
      AccountingJournalBuilder.Result built,
      List<AccountingJournalBlocker> blockers) {
    boolean ready() {
      return blockers.isEmpty();
    }
  }

  private Evaluation evaluate(TenantId tenant, UUID entity, PackageRow source) {
    List<AccountingJournalBlocker> blockers = new ArrayList<>();
    boolean eligible = true;
    if (source.voided()) {
      blockers.add(AccountingJournalBlocker.PACKAGE_VOIDED);
      eligible = false;
    } else if (!"APPROVED".equals(source.status())) {
      blockers.add(AccountingJournalBlocker.PACKAGE_NOT_APPROVED);
      eligible = false;
    }
    if (accounting.journalExists(tenant, source.id())) {
      blockers.add(AccountingJournalBlocker.JOURNAL_ALREADY_EXISTS);
    }
    MappingRow mapping =
        accounting
            .applicableMapping(tenant, entity, source.currency(), source.periodStart())
            .orElse(null);
    if (mapping == null) {
      blockers.add(AccountingJournalBlocker.MAPPING_NOT_FOUND);
    }
    AccountingJournalBuilder.Result built = null;
    if (eligible && mapping != null) {
      built = build(tenant, entity, source, mapping);
      if (built.refusal() == AccountingJournalBuilder.Refusal.TOO_LARGE) {
        blockers.add(AccountingJournalBlocker.TOO_LARGE);
      } else if (built.refusal() == AccountingJournalBuilder.Refusal.UNBALANCED) {
        blockers.add(AccountingJournalBlocker.UNBALANCED);
      }
    }
    return new Evaluation(source, mapping, built, blockers);
  }

  /** The aggregated lines of the package's frozen amounts, or the domain's refusal. */
  private AccountingJournalBuilder.Result build(
      TenantId tenant, UUID entity, PackageRow source, MappingRow mapping) {
    List<AccountingJournalBuilder.Row> rows =
        accounting.payslipAmounts(tenant, entity, source.id(), MAX_PAYSLIPS + 1);
    if (rows.size() != source.payslipCount()) {
      return new AccountingJournalBuilder.Result(
          AccountingJournalBuilder.Refusal.UNBALANCED, 0, List.of(), 0L, 0L);
    }
    return AccountingJournalBuilder.build(rows, mapping.accounts(), source.totals());
  }

  private static JournalFacts facts(
      TenantId tenant,
      UUID entity,
      PackageRow source,
      MappingRow mapping,
      AccountingJournalBuilder.Result built) {
    return new JournalFacts(
        tenant.value(),
        entity,
        source.id(),
        source.version(),
        source.approvalDigest(),
        source.calculationDigest(),
        mapping.id(),
        mapping.version(),
        mapping.mappingDigest(),
        source.currency(),
        built.lines().size(),
        built.totalDebitsMinor(),
        built.totalCreditsMinor());
  }

  private static String previewDigest(
      TenantId tenant, UUID entity, Evaluation evaluation, Instant expiresAt) {
    return AccountingDigests.journalPreview(
        facts(tenant, entity, evaluation.source(), evaluation.mapping(), evaluation.built()),
        expiresAt);
  }

  private static String period(PackageRow source) {
    return YearMonth.from(source.periodStart()).toString();
  }

  private static List<Line> lines(AccountingJournalBuilder.Result built) {
    return built.lines().stream().map(AccountingJournalService::line).toList();
  }

  private static Line line(AccountingLine line) {
    return new Line(
        line.lineNumber(),
        line.accountCode(),
        line.direction().name(),
        line.amountMinor(),
        line.costCenterCode(),
        line.siteCode());
  }

  private static MappingRef mappingRef(UUID id, int version, LocalDate effectiveFrom) {
    return new MappingRef(id, version, effectiveFrom);
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Reviews the journal a package would produce, without writing anything but the disclosure
   * audit. A preview never fails for a blocker: its status is {@code READY} or {@code BLOCKED}.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageId raw path value
   * @return the preview, after its disclosure audit committed
   */
  public JournalPreview preview(PayrollCaller caller, String legalEntityId, String packageId) {
    return plumbing.observed(
        LOG,
        "accounting_journal_preview",
        PREVIEW,
        () -> {
          UUID entityId = AccountingCommands.packageScopeId(legalEntityId);
          UUID id = AccountingCommands.packageScopeId(packageId);
          quotas.requirePreview(caller.tenant(), caller.subject(), calendar.now());
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, entityId, ErrorCode.PAYROLL_PACKAGE_NOT_FOUND);
                PackageRow source =
                    accounting
                        .payrollPackage(tenant, entity, id)
                        .orElseThrow(AccountingCommands::packageNotFound);
                Instant now = calendar.now();
                Evaluation evaluation = evaluate(tenant, entity, source);
                boolean ready = evaluation.ready();
                Instant expiresAt = ready ? CompensationCalendar.expiry(now) : null;
                AccountingJournalBuilder.Result built = evaluation.built();
                List<Line> lines = built == null ? List.of() : lines(built);
                MappingRow mapping = evaluation.mapping();
                JournalPreview preview =
                    new JournalPreview(
                        entity,
                        source.id(),
                        source.version(),
                        period(source),
                        source.currency(),
                        mapping == null
                            ? null
                            : mappingRef(mapping.id(), mapping.version(), mapping.effectiveFrom()),
                        ready ? "READY" : "BLOCKED",
                        evaluation.blockers().stream().map(AccountingJournalBlocker::name).toList(),
                        built == null ? 0 : built.sourceEntryCount(),
                        lines.size(),
                        built == null ? 0L : built.totalDebitsMinor(),
                        built == null ? 0L : built.totalCreditsMinor(),
                        lines,
                        ready ? previewDigest(tenant, entity, evaluation, expiresAt) : null,
                        expiresAt);
                recorder.disclosed(
                    caller,
                    AccountingRecorder.JOURNAL_PREVIEW,
                    source.id(),
                    Map.of(
                        "legalEntityId",
                        entity.toString(),
                        "packageId",
                        source.id().toString(),
                        "currency",
                        source.currency()),
                    "preview",
                    lines.size(),
                    now);
                return preview;
              });
        },
        preview -> "BLOCKED".equals(preview.status()) ? Outcome.STATE_CONFLICT : Outcome.LISTED);
  }

  // ------------------------------------------------------------------------------------------
  // Generate
  // ------------------------------------------------------------------------------------------

  /**
   * Generates the journal of a previewed package, or replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the evidence the preview returned and the confirmation
   * @return the amount-free receipt
   */
  public IdempotentOperation.Result<Receipt> generate(
      PayrollCaller caller,
      String legalEntityId,
      String packageId,
      String idempotencyKey,
      CommitAccountingJournalRequest body) {
    return plumbing.command(
        GENERATE,
        () -> {
          record Parsed(UUID entity, UUID source, JournalCommit command) {}
          Parsed parsed =
              operations.validated(
                  GENERATE_SPEC,
                  () ->
                      new Parsed(
                          AccountingCommands.packageScopeId(legalEntityId),
                          AccountingCommands.packageScopeId(packageId),
                          AccountingCommands.journalCommit(idempotencyKey, body)));
          return operations.execute(
              GENERATE_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed
                  .command()
                  .canonical(caller.tenant().value(), parsed.entity(), parsed.source()),
              Receipt.class,
              WRITE_TIMEOUT,
              () -> generated(caller, parsed.entity(), parsed.source(), parsed.command()));
        });
  }

  private IdempotentOperation.Completed<Receipt> generated(
      PayrollCaller caller, UUID entityId, UUID packageId, JournalCommit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, COMMIT_LIMIT);
    UUID entity = entities.require(tenant, entityId, ErrorCode.PAYROLL_PACKAGE_NOT_FOUND);
    String entityCode = entities.code(tenant, entity, ErrorCode.PAYROLL_PACKAGE_NOT_FOUND);
    Instant now = calendar.now();
    requirePackage(tenant, entity, packageId);
    // Lock order: the package's journal lock (the key the database guard takes), then the package
    // and everything else are re-read.
    accounting.lockJournal(tenant, packageId);
    PackageRow source = requirePackage(tenant, entity, packageId);
    Evaluation evaluation = evaluate(tenant, entity, source);
    requireGeneratable(tenant, entity, evaluation, command, now);
    MappingRow mapping = evaluation.mapping();
    AccountingJournalBuilder.Result built = evaluation.built();
    List<AccountingLine> lines = built.lines();
    UUID id = UUID.randomUUID();
    int version = 1;
    byte[] csv = AccountingCsv.csv(id, source.id(), entity, source.currency(), lines);
    String csvSha256 = AccountingDigests.sha256(csv);
    JournalFacts facts = facts(tenant, entity, source, mapping, built);
    String digest =
        AccountingDigests.journal(id, version, facts, AccountingDigests.lines(lines), csvSha256);
    try {
      accounting.insertJournal(
          tenant,
          new NewJournal(
              id,
              entity,
              source.id(),
              source.currency(),
              version,
              source.periodStart(),
              entityCode,
              source.version(),
              source.approvalDigest(),
              source.calculationId(),
              source.calculationVersion(),
              source.calculationDigest(),
              mapping.id(),
              mapping.version(),
              mapping.mappingDigest(),
              source.payslipCount(),
              built.sourceEntryCount(),
              lines.size(),
              source.totals(),
              built.totalDebitsMinor(),
              built.totalCreditsMinor(),
              csvSha256,
              csv.length,
              digest,
              now,
              caller.subject()));
      accounting.insertLines(tenant, id, lines);
      accounting.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.journalGenerated(
        caller,
        new AccountingRecorder.JournalChange(
            entity, source.id(), id, source.currency(), version, lines.size(), now));
    LOG.atInfo()
        .addKeyValue("operation", GENERATE)
        .addKeyValue("currency", source.currency())
        .addKeyValue("lineCount", lines.size())
        .log("accounting_journal_generated");
    return new IdempotentOperation.Completed<>(
        new Receipt(id, entity, source.id(), source.currency(), version, lines.size(), now),
        id,
        Outcome.CREATED);
  }

  private PackageRow requirePackage(TenantId tenant, UUID entity, UUID packageId) {
    return accounting
        .payrollPackage(tenant, entity, packageId)
        .orElseThrow(AccountingCommands::packageNotFound);
  }

  /** The contract's check order; nothing is written before every check passed. */
  private static void requireGeneratable(
      TenantId tenant, UUID entity, Evaluation evaluation, JournalCommit command, Instant now) {
    List<AccountingJournalBlocker> blockers = evaluation.blockers();
    // 1. The package was voided, or is not approved.
    if (blockers.contains(AccountingJournalBlocker.PACKAGE_VOIDED)) {
      throw new ApiException(ErrorCode.ACCOUNTING_JOURNAL_SOURCE_VOIDED, Map.of());
    }
    if (blockers.contains(AccountingJournalBlocker.PACKAGE_NOT_APPROVED)) {
      throw new ApiException(ErrorCode.ACCOUNTING_JOURNAL_SOURCE_NOT_APPROVED, Map.of());
    }
    // 2. A journal already exists for the package (one per package, forever).
    if (blockers.contains(AccountingJournalBlocker.JOURNAL_ALREADY_EXISTS)) {
      throw new ApiException(ErrorCode.ACCOUNTING_JOURNAL_ALREADY_EXISTS, Map.of());
    }
    // 3. No mapping applies to the package month and currency.
    if (blockers.contains(AccountingJournalBlocker.MAPPING_NOT_FOUND)) {
      throw new ApiException(ErrorCode.ACCOUNTING_MAPPING_NOT_FOUND, Map.of());
    }
    // 4. The frozen amounts do not balance or do not equal the package totals.
    if (blockers.contains(AccountingJournalBlocker.UNBALANCED)) {
      throw new ApiException(ErrorCode.ACCOUNTING_JOURNAL_UNBALANCED, Map.of());
    }
    // 5. Too many entries or lines.
    if (blockers.contains(AccountingJournalBlocker.TOO_LARGE)) {
      throw new ApiException(ErrorCode.ACCOUNTING_JOURNAL_TOO_LARGE, Map.of());
    }
    // 6. The package or mapping moved since the preview, expiry, and any difference of the digest.
    Instant expiresAt = command.expiresAt();
    String digest = previewDigest(tenant, entity, evaluation, expiresAt);
    if (evaluation.source().version() != command.packageVersion()
        || evaluation.mapping().version() != command.mappingVersion()
        || !expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !digest.equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.ACCOUNTING_JOURNAL_PREVIEW_STALE, Map.of());
    }
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * The journals of a package (at most one in this story).
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageId raw path value
   * @return the history, after its disclosure audit committed
   */
  public JournalList list(PayrollCaller caller, String legalEntityId, String packageId) {
    return plumbing.observed(
        LOG,
        "accounting_journal_read",
        READ,
        () -> {
          UUID entityId = AccountingCommands.packageScopeId(legalEntityId);
          UUID id = AccountingCommands.packageScopeId(packageId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, entityId, ErrorCode.PAYROLL_PACKAGE_NOT_FOUND);
                PackageRow source = requirePackage(tenant, entity, id);
                List<Summary> items =
                    accounting.journalsOfPackage(tenant, entity, source.id()).stream()
                        .map(AccountingJournalService::summary)
                        .toList();
                recorder.disclosed(
                    caller,
                    AccountingRecorder.JOURNAL_READ,
                    source.id(),
                    Map.of("legalEntityId", entity.toString(), "packageId", source.id().toString()),
                    "list",
                    items.size(),
                    calendar.now());
                return new JournalList(items);
              });
        });
  }

  private static Summary summary(JournalRow row) {
    return new Summary(
        row.id(),
        row.legalEntityId(),
        row.packageId(),
        row.version(),
        YearMonth.from(row.periodStart()).toString(),
        row.currency(),
        row.lineCount(),
        row.generatedAt(),
        row.sourceVoided());
  }

  /**
   * One journal with its lines.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param journalId raw path value
   * @return the journal, after its disclosure audit committed
   */
  public JournalView get(PayrollCaller caller, String legalEntityId, String journalId) {
    return plumbing.observed(
        LOG,
        "accounting_journal_read",
        READ,
        () -> {
          UUID entityId = AccountingCommands.journalScopeId(legalEntityId);
          UUID id = AccountingCommands.journalScopeId(journalId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
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
                recorder.disclosed(
                    caller,
                    AccountingRecorder.JOURNAL_READ,
                    row.id(),
                    Map.of(
                        "legalEntityId",
                        entity.toString(),
                        "journalId",
                        row.id().toString(),
                        "currency",
                        row.currency()),
                    "journal",
                    lines.size(),
                    calendar.now());
                return new JournalView(
                    row.id(),
                    row.legalEntityId(),
                    row.legalEntityCode(),
                    row.packageId(),
                    row.packageVersion(),
                    row.version(),
                    YearMonth.from(row.periodStart()).toString(),
                    row.currency(),
                    mappingRef(row.mappingId(), row.mappingVersion(), row.mappingEffectiveFrom()),
                    row.mappingDigest(),
                    row.payslipCount(),
                    row.sourceEntryCount(),
                    row.lineCount(),
                    row.totalDebitsMinor(),
                    row.totalCreditsMinor(),
                    row.csvSha256(),
                    row.csvBytes(),
                    row.journalDigest(),
                    row.schemaVersion(),
                    row.generatedAt(),
                    row.sourceVoided(),
                    lines.stream().map(AccountingJournalService::line).toList());
              });
        });
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        PayrollPackageConstraintViolations.constraint(violated)
            .flatMap(AccountingConstraintViolations::journalAnswer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
