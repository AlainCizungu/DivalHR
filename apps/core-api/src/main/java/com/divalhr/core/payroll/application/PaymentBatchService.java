package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPaymentBatchCancelRequest;
import com.divalhr.core.payroll.api.CommitPaymentBatchRequest;
import com.divalhr.core.payroll.api.PaymentBatchCancelCommandRequest;
import com.divalhr.core.payroll.api.PaymentResponses.BatchDetail;
import com.divalhr.core.payroll.api.PaymentResponses.BatchLine;
import com.divalhr.core.payroll.api.PaymentResponses.BatchPage;
import com.divalhr.core.payroll.api.PaymentResponses.BatchPreview;
import com.divalhr.core.payroll.api.PaymentResponses.BatchReceipt;
import com.divalhr.core.payroll.api.PaymentResponses.CancelPreview;
import com.divalhr.core.payroll.api.PaymentResponses.CancelReceipt;
import com.divalhr.core.payroll.api.PaymentResponses.LinePage;
import com.divalhr.core.payroll.application.PaymentBatchEvidence.Loaded;
import com.divalhr.core.payroll.application.PaymentCommands.BatchCommit;
import com.divalhr.core.payroll.application.PaymentCommands.CancelCommit;
import com.divalhr.core.payroll.domain.PaymentBatchBlocker;
import com.divalhr.core.payroll.domain.PaymentBatchCancelBlocker;
import com.divalhr.core.payroll.domain.PaymentBatchCancelReason;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Evaluation;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Line;
import com.divalhr.core.payroll.domain.PaymentBatchWarning;
import com.divalhr.core.payroll.domain.PaymentDigests;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.BatchPosition;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.BatchRow;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.LineRow;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.NewBatch;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.NewCancel;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.PackageRow;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository;
import com.divalhr.core.payroll.internal.PaymentConstraintViolations;
import com.divalhr.core.payroll.internal.PayrollPackageConstraintViolations;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Payment batches of approved payroll packages (MVP-095A, ADR 0036): the preview and the idempotent
 * preparation, the history, the lines, and the preview and the idempotent cancellation, for tenant
 * administrators with privileged MFA. Authorization has run before this; the tenant, the actor and
 * the legal entity scope never come from the request.
 *
 * <p>Boundary: a prepared batch records instructions to pay from an approved package. It is not a
 * payment, a transmission to a bank or provider, a reconciliation or a claim of legal compliance.
 * Nothing is recalculated and no currency is converted; the amounts are the approved nets, copied
 * with their payslips. A PREPARED batch blocks voiding its package; cancelling releases it.
 *
 * <p>A preparation runs in one idempotent transaction in the lock order of ADR 0036 section 8: the
 * idempotency reservation, the tenant statutory lock shared, the entity's package lock, the package
 * row {@code FOR UPDATE}, the tenant payment lock exclusive; then everything is re-read and decided
 * in the contract's order before anything is written. Restricted financial and HR data: amounts,
 * employee numbers, destinations and digests never enter logs, metrics, audit metadata, outbox
 * events, idempotency receipts, cursors or error bodies.
 */
@Service
public class PaymentBatchService {

  /** Reads (interceptor operation, cursor binding and audit action). */
  public static final String READ = "payment-batch.read";

  /** Preparation previews (interceptor operation). */
  public static final String PREVIEW = "payment-batch.preview";

  /** Preparation (idempotency operation, interceptor operation and audit action). */
  public static final String PREPARE = PaymentRecorder.BATCH_PREPARE;

  /** Cancellation previews (interceptor operation). */
  public static final String CANCEL_PREVIEW = "payment-batch.cancel-preview";

  /** Cancellation (idempotency operation, interceptor operation and audit action). */
  public static final String CANCEL = PaymentRecorder.BATCH_CANCEL;

  /** Per-subject reads. */
  public static final String SUBJECT_READ_BUCKET = "payment-read";

  /** Per-subject previews (preparation and cancellation). */
  public static final String SUBJECT_PREVIEW_BUCKET = "payment-batch-preview";

  /** Per-subject preparations and cancellations. */
  public static final String SUBJECT_COMMIT_BUCKET = "payment-batch-write";

  /** Per-tenant previews. */
  public static final String TENANT_PREVIEW_BUCKET = "payment-batch-preview";

  /** Per-tenant preparations and cancellations. */
  public static final String TENANT_COMMIT_BUCKET = "payment-batch-write";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payment-batch");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration COMMIT_LIMIT = Duration.ofSeconds(25);
  private static final String BATCH_CODE = "PAYBATCH";
  private static final String LINE_CODE = "PAYLINE";
  private static final IdempotentOperation.Spec PREPARE_SPEC =
      new IdempotentOperation.Spec(PREPARE, "payment_batch", "prepare", "prepared", 201);
  private static final IdempotentOperation.Spec CANCEL_SPEC =
      new IdempotentOperation.Spec(CANCEL, "payment_batch", "cancel", "cancelled", 201);

  private final IdempotentOperation operations;
  private final JdbcPaymentRepository payments;
  private final JdbcPayrollPackageRepository packages;
  private final JdbcPayrollRunRepository runs;
  private final JdbcStatutoryRepository statutory;
  private final PayrollEntities entities;
  private final PaymentBatchEvidence evidence;
  private final CompensationCalendar calendar;
  private final PaymentRecorder recorder;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param payments payment storage
   * @param packages payroll package storage (locks)
   * @param runs payroll storage (transaction limits)
   * @param statutory statutory storage (locks)
   * @param entities legal entity resolver
   * @param evidence the loader and evaluator of a batch
   * @param calendar business date and preview window
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   */
  public PaymentBatchService(
      IdempotentOperation operations,
      JdbcPaymentRepository payments,
      JdbcPayrollPackageRepository packages,
      JdbcPayrollRunRepository runs,
      JdbcStatutoryRepository statutory,
      PayrollEntities entities,
      PaymentBatchEvidence evidence,
      CompensationCalendar calendar,
      PaymentRecorder recorder,
      PayrollOperations plumbing,
      CursorCodec cursors) {
    this.operations = operations;
    this.payments = payments;
    this.packages = packages;
    this.runs = runs;
    this.statutory = statutory;
    this.entities = entities;
    this.evidence = evidence;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
    this.cursors = cursors;
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Reviews what preparing a batch would record, without writing anything but the disclosure audit.
   * A preview never fails for a blocker: its status is {@code READY} or {@code BLOCKED}.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageId raw path value
   * @return the preview, after its disclosure audit committed
   */
  public BatchPreview preview(PayrollCaller caller, String legalEntityId, String packageId) {
    return plumbing.observed(
        LOG,
        "payment_batch_preview",
        PREVIEW,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          UUID pack = PayrollPackageCommands.packageId(packageId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                Instant now = calendar.now();
                Loaded loaded = evidence.load(tenant, entity, pack);
                BatchPreview preview = previewOf(tenant, entity, loaded, now);
                recorder.disclosed(
                    caller,
                    PaymentRecorder.BATCH_READ,
                    "payment-batch",
                    pack,
                    Map.of("legalEntityId", entity.toString(), "packageId", pack.toString()),
                    "prepare-preview",
                    true,
                    preview.lines().size(),
                    now);
                return preview;
              });
        },
        preview -> "BLOCKED".equals(preview.status()) ? Outcome.STATE_CONFLICT : Outcome.LISTED);
  }

  private static BatchPreview previewOf(TenantId tenant, UUID entity, Loaded loaded, Instant now) {
    PackageRow pack = loaded.pack();
    Evaluation result = loaded.evaluation();
    boolean ready = result.ready();
    Instant expiresAt = ready ? CompensationCalendar.expiry(now) : null;
    String previewDigest =
        ready
            ? PaymentDigests.batchPreview(
                tenant.value(),
                entity,
                pack.id(),
                pack.version(),
                pack.approvalDigest(),
                loaded.chainVersion(),
                loaded.destinationsDigest(),
                loaded.linesDigest(),
                expiresAt)
            : null;
    List<BatchLine> lines = new ArrayList<>();
    for (Line line : result.lines()) {
      if (lines.size() < PaymentBatchEvaluator.MAX_LINES) {
        lines.add(PaymentViews.line(line));
      }
    }
    return new BatchPreview(
        entity,
        pack.id(),
        pack.version(),
        PaymentViews.month(pack.periodStart()),
        PaymentViews.CURRENCY,
        pack.status(),
        ready ? "READY" : "BLOCKED",
        result.blockers().stream().map(PaymentBatchBlocker::name).toList(),
        result.warnings().stream().map(PaymentBatchWarning::name).toList(),
        result.lines().size(),
        result.zeroNetExcluded(),
        PaymentViews.counts(result.bank(), result.mobile(), result.manual()),
        result.totalNet(),
        loaded.chainVersion(),
        ready ? loaded.destinationsDigest() : null,
        previewDigest,
        expiresAt,
        lines);
  }

  // ------------------------------------------------------------------------------------------
  // Prepare
  // ------------------------------------------------------------------------------------------

  /**
   * Prepares a previewed batch, or replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the evidence the preview returned and the two confirmations
   * @return the amount-free receipt
   */
  public IdempotentOperation.Result<BatchReceipt> prepare(
      PayrollCaller caller,
      String legalEntityId,
      String packageId,
      String idempotencyKey,
      CommitPaymentBatchRequest body) {
    return plumbing.command(
        PREPARE,
        () -> {
          record Parsed(UUID entity, UUID pack, BatchCommit command) {}
          Parsed parsed =
              operations.validated(
                  PREPARE_SPEC,
                  () ->
                      new Parsed(
                          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND),
                          PayrollPackageCommands.packageId(packageId),
                          PaymentCommands.batchCommit(idempotencyKey, body)));
          return operations.execute(
              PREPARE_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.command().canonical(caller.tenant().value(), parsed.entity(), parsed.pack()),
              BatchReceipt.class,
              WRITE_TIMEOUT,
              () -> prepared(caller, parsed.entity(), parsed.pack(), parsed.command()));
        });
  }

  private IdempotentOperation.Completed<BatchReceipt> prepared(
      PayrollCaller caller, UUID entityId, UUID packageId, BatchCommit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, COMMIT_LIMIT);
    UUID entity = entities.require(tenant, entityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
    Instant now = calendar.now();
    // Lock order of ADR 0036 section 8: the tenant statutory lock shared, the entity's package
    // lock, the package row, then the tenant payment lock exclusive; everything is re-read after.
    statutory.lockStatutory(tenant, true);
    packages.lockPackage(tenant, entity);
    if (!packages.lockPackageRow(tenant, entity, packageId)) {
      throw new ApiException(ErrorCode.PAYROLL_PACKAGE_NOT_FOUND, Map.of());
    }
    payments.lockPayments(tenant, false);
    Loaded loaded = evidence.load(tenant, entity, packageId);
    requirePreparable(tenant, entity, loaded, command, now);
    return write(caller, entity, loaded, now);
  }

  /** The contract's check order; nothing is written before every check passed. */
  private static void requirePreparable(
      TenantId tenant, UUID entity, Loaded loaded, BatchCommit command, Instant now) {
    Evaluation result = loaded.evaluation();
    List<PaymentBatchBlocker> blockers = result.blockers();
    // 1. Another preparation or cancellation moved the package's chain.
    if (loaded.chainVersion() != command.expectedVersion()) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_VERSION_CONFLICT, Map.of());
    }
    // 2. A PREPARED batch already exists for the package.
    if (blockers.contains(PaymentBatchBlocker.BATCH_ALREADY_PREPARED)) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_ALREADY_PREPARED, Map.of());
    }
    // 3. Any other blocker, except the limit (step 5).
    for (PaymentBatchBlocker blocker : blockers) {
      if (blocker != PaymentBatchBlocker.LIMIT_REACHED) {
        throw new ApiException(ErrorCode.PAYMENT_BATCH_BLOCKED, Map.of());
      }
    }
    // 4. Expiry, a different package version and any difference of a digest.
    PackageRow pack = loaded.pack();
    Instant expiresAt = command.expiresAt();
    String previewDigest =
        PaymentDigests.batchPreview(
            tenant.value(),
            entity,
            pack.id(),
            pack.version(),
            pack.approvalDigest(),
            loaded.chainVersion(),
            loaded.destinationsDigest(),
            loaded.linesDigest(),
            expiresAt);
    if (pack.version() != command.packageVersion()
        || !expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !previewDigest.equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_PREVIEW_STALE, Map.of());
    }
    // 5. Five thousand lines or twenty-five batch versions.
    if (blockers.contains(PaymentBatchBlocker.LIMIT_REACHED)) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_LIMIT_REACHED, Map.of());
    }
  }

  private IdempotentOperation.Completed<BatchReceipt> write(
      PayrollCaller caller, UUID entity, Loaded loaded, Instant now) {
    TenantId tenant = caller.tenant();
    PackageRow pack = loaded.pack();
    Evaluation result = loaded.evaluation();
    UUID batchId = UUID.randomUUID();
    int version = loaded.chainVersion() + 1;
    String period = PaymentViews.month(pack.periodStart());
    String batchDigest =
        PaymentDigests.batch(
            tenant.value(),
            entity,
            pack.id(),
            pack.version(),
            pack.approvalDigest(),
            version,
            loaded.destinationsDigest(),
            loaded.linesDigest());
    List<LineRow> lines = new ArrayList<>();
    for (Line line : result.lines()) {
      PaymentBatchEvaluator.Destination destination = line.destination();
      lines.add(
          new LineRow(
              UUID.randomUUID(),
              line.payslipId(),
              line.employeeId(),
              line.employeeNumber(),
              destination.method().name(),
              destination.country(),
              destination.providerCode(),
              destination.providerName(),
              destination.masked(),
              destination.id(),
              destination.sequence(),
              line.net()));
    }
    NewBatch row =
        new NewBatch(
            batchId,
            entity,
            pack.id(),
            version,
            pack.periodStart(),
            PaymentViews.CURRENCY,
            pack.version(),
            pack.approvalDigest(),
            lines.size(),
            result.zeroNetExcluded(),
            result.bank(),
            result.mobile(),
            result.manual(),
            result.totalNet(),
            loaded.destinationsDigest(),
            batchDigest,
            now,
            caller.subject());
    try {
      payments.insertBatch(tenant, row);
      payments.insertLines(tenant, entity, pack.id(), batchId, lines);
      payments.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.prepared(
        caller,
        new PaymentRecorder.BatchRecorded(
            entity, pack.id(), batchId, version, period, lines.size(), batchDigest, now));
    LOG.atInfo()
        .addKeyValue("operation", PREPARE)
        .addKeyValue("outcome", "prepared")
        .addKeyValue("lines", PaymentViews.countBucket(lines.size()))
        .log("payment_batch_prepared");
    return new IdempotentOperation.Completed<>(
        new BatchReceipt(
            batchId,
            pack.id(),
            entity,
            version,
            "PREPARED",
            period,
            PaymentViews.CURRENCY,
            lines.size(),
            now),
        batchId,
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * A page of the legal entity's batches, newest first.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageFilter optional package ID, raw
   * @param status optional status filter
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public BatchPage list(
      PayrollCaller caller,
      String legalEntityId,
      String packageFilter,
      String status,
      String cursor,
      String limit) {
    return plumbing.observed(
        LOG,
        "payment_batch_read",
        READ,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          FieldErrors errors = new FieldErrors();
          int size =
              PayrollCommands.limit(
                  limit,
                  PaymentCommands.DEFAULT_BATCH_LIMIT,
                  PaymentCommands.MAX_BATCH_LIMIT,
                  errors);
          UUID pack = PaymentCommands.packageFilter(packageFilter, errors);
          String filter = PaymentCommands.statusFilter(status, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                CursorScope scope = listScope(caller, entity, pack, filter, size);
                BatchPosition after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  BatchRow at =
                      payments
                          .batch(tenant, entity, position.id())
                          .filter(row -> BATCH_CODE.equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  after = new BatchPosition(at.preparedAt(), at.id());
                }
                List<BatchRow> rows =
                    payments.batchPage(tenant, entity, pack, filter, after, size + 1);
                List<BatchRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope, new KeysetPosition(BATCH_CODE, shown.get(shown.size() - 1).id()));
                }
                recorder.disclosed(
                    caller,
                    PaymentRecorder.BATCH_READ,
                    "payment-batch",
                    entity,
                    Map.of("legalEntityId", entity.toString()),
                    "list",
                    cursor == null,
                    shown.size(),
                    calendar.now());
                return new BatchPage(shown.stream().map(PaymentViews::summary).toList(), next);
              });
        });
  }

  /**
   * One batch with its evidence.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @return the batch, after its disclosure audit committed
   */
  public BatchDetail get(PayrollCaller caller, String legalEntityId, String batchId) {
    return plumbing.observed(
        LOG,
        "payment_batch_read",
        READ,
        () -> {
          UUID entityId = PayrollCommands.id(legalEntityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
          UUID id = PaymentCommands.batchId(batchId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
                BatchRow row = requireBatch(tenant, entity, id);
                recorder.disclosed(
                    caller,
                    PaymentRecorder.BATCH_READ,
                    "payment-batch",
                    id,
                    Map.of("legalEntityId", entity.toString(), "batchId", id.toString()),
                    "batch",
                    true,
                    1,
                    calendar.now());
                return new BatchDetail(
                    PaymentViews.summary(row),
                    row.batchDigest(),
                    row.packageDigest(),
                    row.destinationsDigest());
              });
        });
  }

  /**
   * A page of a batch's lines, by employee ID.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public LinePage lines(
      PayrollCaller caller, String legalEntityId, String batchId, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "payment_batch_read",
        READ,
        () -> {
          UUID entityId = PayrollCommands.id(legalEntityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
          UUID id = PaymentCommands.batchId(batchId);
          FieldErrors errors = new FieldErrors();
          int size =
              PayrollCommands.limit(
                  limit,
                  PaymentCommands.DEFAULT_LINE_LIMIT,
                  PaymentCommands.MAX_LINE_LIMIT,
                  errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
                requireBatch(tenant, entity, id);
                CursorScope scope = lineScope(caller, entity, id, size);
                UUID after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  after =
                      payments
                          .lineOf(tenant, entity, id, position.id())
                          .filter(row -> LINE_CODE.equals(position.code()))
                          .map(LineRow::employeeId)
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                }
                List<LineRow> rows = payments.linePage(tenant, entity, id, after, size + 1);
                List<LineRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope,
                          new KeysetPosition(LINE_CODE, shown.get(shown.size() - 1).employeeId()));
                }
                recorder.disclosed(
                    caller,
                    PaymentRecorder.BATCH_READ,
                    "payment-batch",
                    id,
                    Map.of("legalEntityId", entity.toString(), "batchId", id.toString()),
                    "lines",
                    cursor == null,
                    shown.size(),
                    calendar.now());
                return new LinePage(shown.stream().map(PaymentViews::line).toList(), next);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Cancel
  // ------------------------------------------------------------------------------------------

  /**
   * Reviews what cancelling a batch would do, without writing anything but the disclosure audit.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @param body the closed reason
   * @return the preview, after its disclosure audit committed
   */
  public CancelPreview previewCancel(
      PayrollCaller caller,
      String legalEntityId,
      String batchId,
      PaymentBatchCancelCommandRequest body) {
    return plumbing.observed(
        LOG,
        "payment_batch_cancel_preview",
        CANCEL_PREVIEW,
        () -> {
          UUID entityId = PayrollCommands.id(legalEntityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
          UUID id = PaymentCommands.batchId(batchId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
                BatchRow row = requireBatch(tenant, entity, id);
                PaymentBatchCancelReason reason = PaymentCommands.cancelReason(body);
                Instant now = calendar.now();
                List<PaymentBatchCancelBlocker> blockers = new ArrayList<>();
                if (!"PREPARED".equals(row.status())) {
                  blockers.add(PaymentBatchCancelBlocker.ALREADY_CANCELLED);
                }
                boolean ready = blockers.isEmpty();
                Instant expiresAt = ready ? CompensationCalendar.expiry(now) : null;
                String digest =
                    ready
                        ? PaymentDigests.cancelPreview(
                            tenant.value(),
                            entity,
                            row.id(),
                            row.version(),
                            row.batchDigest(),
                            reason,
                            expiresAt)
                        : null;
                recorder.disclosed(
                    caller,
                    PaymentRecorder.BATCH_READ,
                    "payment-batch",
                    id,
                    Map.of("legalEntityId", entity.toString(), "batchId", id.toString()),
                    "cancel-preview",
                    true,
                    0,
                    now);
                return new CancelPreview(
                    entity,
                    row.packageId(),
                    row.id(),
                    row.version(),
                    row.status(),
                    ready ? "READY" : "BLOCKED",
                    blockers.stream().map(PaymentBatchCancelBlocker::name).toList(),
                    reason.name(),
                    row.lineCount(),
                    row.batchDigest(),
                    digest,
                    expiresAt);
              });
        },
        preview -> "BLOCKED".equals(preview.status()) ? Outcome.STATE_CONFLICT : Outcome.LISTED);
  }

  /**
   * Cancels a previewed batch, or replays an identical command. Cancelling is append-only and
   * final: a cancelled batch is never reactivated.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the reason, the evidence the preview returned and the acknowledgement
   * @return the receipt
   */
  public IdempotentOperation.Result<CancelReceipt> cancel(
      PayrollCaller caller,
      String legalEntityId,
      String batchId,
      String idempotencyKey,
      CommitPaymentBatchCancelRequest body) {
    return plumbing.command(
        CANCEL,
        () -> {
          record Parsed(UUID entity, UUID batch, CancelCommit command) {}
          Parsed parsed =
              operations.validated(
                  CANCEL_SPEC,
                  () ->
                      new Parsed(
                          PayrollCommands.id(legalEntityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND),
                          PaymentCommands.batchId(batchId),
                          PaymentCommands.cancelCommit(idempotencyKey, body)));
          return operations.execute(
              CANCEL_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.command().canonical(caller.tenant().value(), parsed.entity(), parsed.batch()),
              CancelReceipt.class,
              WRITE_TIMEOUT,
              () -> cancelled(caller, parsed.entity(), parsed.batch(), parsed.command()));
        });
  }

  private IdempotentOperation.Completed<CancelReceipt> cancelled(
      PayrollCaller caller, UUID entityId, UUID batchId, CancelCommit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, COMMIT_LIMIT);
    UUID entity = entities.require(tenant, entityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
    Instant now = calendar.now();
    // Lock order of ADR 0036 section 8: the entity's package lock, then the batch row.
    packages.lockPackage(tenant, entity);
    if (!payments.lockBatch(tenant, entity, batchId)) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_NOT_FOUND, Map.of());
    }
    BatchRow row = requireBatch(tenant, entity, batchId);
    // 1. Already cancelled: final, never reactivated.
    if (!"PREPARED".equals(row.status())) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_CANCELLED, Map.of());
    }
    // 2. Expiry and any difference of a digest.
    Instant expiresAt = command.expiresAt();
    String previewDigest =
        PaymentDigests.cancelPreview(
            tenant.value(),
            entity,
            row.id(),
            row.version(),
            row.batchDigest(),
            command.reason(),
            expiresAt);
    if (row.version() != command.batchVersion()
        || !row.batchDigest().equals(command.batchDigest())
        || !expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !previewDigest.equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_PREVIEW_STALE, Map.of());
    }
    try {
      payments.insertCancel(
          tenant,
          entity,
          batchId,
          new NewCancel(
              UUID.randomUUID(),
              command.reason().name(),
              row.batchDigest(),
              now,
              caller.subject()));
      if (payments.markCancelled(tenant, entity, batchId, now) != 1) {
        throw new ApiException(ErrorCode.PAYMENT_BATCH_CANCELLED, Map.of());
      }
      payments.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    String period = PaymentViews.month(row.periodStart());
    recorder.cancelled(
        caller,
        new PaymentRecorder.BatchRecorded(
            entity,
            row.packageId(),
            batchId,
            row.version(),
            period,
            row.lineCount(),
            row.batchDigest(),
            now));
    LOG.atInfo()
        .addKeyValue("operation", CANCEL)
        .addKeyValue("outcome", "cancelled")
        .log("payment_batch_cancelled");
    return new IdempotentOperation.Completed<>(
        new CancelReceipt(
            batchId,
            row.packageId(),
            entity,
            row.version(),
            "CANCELLED",
            command.reason().name(),
            now),
        batchId,
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  BatchRow requireBatch(TenantId tenant, UUID entity, UUID batchId) {
    return payments
        .batch(tenant, entity, batchId)
        .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_BATCH_NOT_FOUND, Map.of()));
  }

  private static CursorScope listScope(
      PayrollCaller caller, UUID entity, UUID pack, String status, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "list");
    filters.put("entity", entity.toString());
    filters.put("package", pack == null ? "" : pack.toString());
    filters.put("status", status == null ? "" : status);
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(READ, caller.tenant(), filters);
  }

  private static CursorScope lineScope(PayrollCaller caller, UUID entity, UUID batch, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "lines");
    filters.put("entity", entity.toString());
    filters.put("batch", batch.toString());
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(READ, caller.tenant(), filters);
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        PayrollPackageConstraintViolations.constraint(violated)
            .flatMap(PaymentConstraintViolations::batchAnswer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
