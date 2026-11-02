package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPaymentSubmissionRequest;
import com.divalhr.core.payroll.api.PaymentResponses.MethodCounts;
import com.divalhr.core.payroll.api.PaymentSubmissionResponses.AttemptPage;
import com.divalhr.core.payroll.api.PaymentSubmissionResponses.AttemptView;
import com.divalhr.core.payroll.api.PaymentSubmissionResponses.ProviderView;
import com.divalhr.core.payroll.api.PaymentSubmissionResponses.SubmissionPreview;
import com.divalhr.core.payroll.api.PaymentSubmissionResponses.SubmissionReceipt;
import com.divalhr.core.payroll.api.PaymentSubmissionResponses.SubmissionView;
import com.divalhr.core.payroll.application.PaymentSubmissionCommands.Commit;
import com.divalhr.core.payroll.domain.PaymentMethod;
import com.divalhr.core.payroll.domain.PaymentSubmissionBlocker;
import com.divalhr.core.payroll.domain.PaymentSubmissionDigests;
import com.divalhr.core.payroll.domain.PaymentSubmissionPayload;
import com.divalhr.core.payroll.domain.PaymentSubmissionProfile;
import com.divalhr.core.payroll.domain.PaymentSubmissionState;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.BatchRow;
import com.divalhr.core.payroll.internal.JdbcPaymentSubmissionRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentSubmissionRepository.AttemptRow;
import com.divalhr.core.payroll.internal.JdbcPaymentSubmissionRepository.NewSubmission;
import com.divalhr.core.payroll.internal.JdbcPaymentSubmissionRepository.SubmissionRow;
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
 * Provider submission of a prepared payment batch (MVP-095B, ADR 0037): the preview, the idempotent
 * creation of exactly one submission per batch, the current state and the attempt history, for
 * tenant administrators with privileged MFA. Authorization has run before this; the tenant, the
 * actor and the legal entity scope never come from the request.
 *
 * <p>Boundary: a submission is a durable, queued handoff of instructions to a controlled provider
 * connector. This service performs no network access; the delivery worker does. "Accepted for
 * processing" never means paid. Restricted financial and HR data: amounts, destinations, digests and
 * provider references never enter logs, metrics, audit metadata, outbox events, idempotency receipts,
 * cursors or error bodies.
 */
@Service
public class PaymentSubmissionService {

  /** Previews (interceptor operation). */
  public static final String PREVIEW = PaymentSubmissionRecorder.PREVIEW;

  /** Creation (idempotency operation, interceptor operation and audit action). */
  public static final String CREATE = PaymentSubmissionRecorder.CREATE;

  /** Reads and history (interceptor operation, cursor binding and audit action). */
  public static final String READ = PaymentSubmissionRecorder.READ;

  /** Per-subject and per-tenant previews. */
  public static final String PREVIEW_BUCKET = "payment-submission-preview";

  /** Per-subject and per-tenant creations. */
  public static final String WRITE_BUCKET = "payment-submission-write";

  /** Per-subject and per-tenant reads and history. */
  public static final String READ_BUCKET = "payment-submission-read";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payment-submission");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration COMMIT_LIMIT = Duration.ofSeconds(25);
  private static final String ATTEMPT_CODE = "PAYSUBATT";
  private static final int MAX_LINES = 5000;
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "payment_submission", "create", "created", 201);

  private final IdempotentOperation operations;
  private final JdbcPaymentRepository payments;
  private final JdbcPaymentSubmissionRepository submissions;
  private final JdbcPayrollPackageRepository packages;
  private final JdbcPayrollRunRepository runs;
  private final JdbcStatutoryRepository statutory;
  private final PayrollEntities entities;
  private final PaymentSubmissionProfiles profiles;
  private final PaymentSubmissionSecrets keys;
  private final PaymentSubmissionPayloads payloads;
  private final CompensationCalendar calendar;
  private final PaymentSubmissionRecorder recorder;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param payments payment storage (batches, locks)
   * @param submissions submission storage
   * @param packages payroll package storage (locks)
   * @param runs payroll storage (transaction limits)
   * @param statutory statutory storage (locks)
   * @param entities legal entity resolver
   * @param profiles operator-controlled provider profiles
   * @param keys the signing key holder
   * @param payloads the outbound payload builder
   * @param calendar clock and preview window
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   */
  public PaymentSubmissionService(
      IdempotentOperation operations,
      JdbcPaymentRepository payments,
      JdbcPaymentSubmissionRepository submissions,
      JdbcPayrollPackageRepository packages,
      JdbcPayrollRunRepository runs,
      JdbcStatutoryRepository statutory,
      PayrollEntities entities,
      PaymentSubmissionProfiles profiles,
      PaymentSubmissionSecrets keys,
      PaymentSubmissionPayloads payloads,
      CompensationCalendar calendar,
      PaymentSubmissionRecorder recorder,
      PayrollOperations plumbing,
      CursorCodec cursors) {
    this.operations = operations;
    this.payments = payments;
    this.submissions = submissions;
    this.packages = packages;
    this.runs = runs;
    this.statutory = statutory;
    this.entities = entities;
    this.profiles = profiles;
    this.keys = keys;
    this.payloads = payloads;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
    this.cursors = cursors;
  }

  // ------------------------------------------------------------------------------------------
  // Evaluation
  // ------------------------------------------------------------------------------------------

  private record Evaluation(
      BatchRow batch,
      PaymentSubmissionProfile profile,
      List<PaymentSubmissionBlocker> blockers) {
    boolean ready() {
      return blockers.isEmpty();
    }
  }

  /** Reads and decides everything a submission depends on; writes nothing. */
  private Evaluation evaluate(TenantId tenant, UUID entity, BatchRow batch) {
    List<PaymentSubmissionBlocker> blockers = new ArrayList<>();
    if (!"PREPARED".equals(batch.status())) {
      blockers.add(PaymentSubmissionBlocker.BATCH_NOT_PREPARED);
    }
    PackageRow pack = packages.pack(tenant, entity, batch.packageId()).orElse(null);
    if (pack == null || "VOIDED".equals(pack.status())) {
      blockers.add(PaymentSubmissionBlocker.PACKAGE_VOIDED);
    } else if (!"APPROVED".equals(pack.status())) {
      blockers.add(PaymentSubmissionBlocker.PACKAGE_NOT_APPROVED);
    } else if (pack.version()
        != payments.latestPackageVersion(tenant, entity, pack.periodStart(), pack.currency())) {
      blockers.add(PaymentSubmissionBlocker.PACKAGE_NOT_LATEST);
    }
    if (submissions.existsForBatch(tenant, batch.id())) {
      blockers.add(PaymentSubmissionBlocker.SUBMISSION_EXISTS);
    }
    PaymentSubmissionProfile profile = profiles.forBatch(entity, batch.currency()).orElse(null);
    if (profile == null) {
      blockers.add(PaymentSubmissionBlocker.PROVIDER_NOT_AVAILABLE);
    } else if (batch.manual() > 0
        || (batch.bank() > 0 && !profile.methods().contains(
            PaymentMethod.BANK_ACCOUNT))
        || (batch.mobile() > 0 && !profile.methods().contains(
            PaymentMethod.MOBILE_MONEY))) {
      blockers.add(PaymentSubmissionBlocker.PROVIDER_UNSUPPORTED);
    }
    if (profile != null && !keys.available()) {
      blockers.add(PaymentSubmissionBlocker.PROVIDER_KEY_UNAVAILABLE);
    }
    if (batch.lineCount() > MAX_LINES || batch.lineCount() < 1) {
      blockers.add(PaymentSubmissionBlocker.LIMIT_REACHED);
    }
    if (blockers.isEmpty()) {
      // Dry run of the exact mapping the worker will perform: every destination is opened,
      // authenticated and mapped; the bytes are discarded at once.
      byte[] bytes = null;
      try {
        bytes =
            payloads.build(
                tenant,
                entity,
                batch.id(),
                new UUID(0, 0),
                batch.batchDigest(),
                batch.currency(),
                batch.totalNet());
      } catch (PaymentSubmissionPayloads.Unreadable unreadable) {
        blockers.add(PaymentSubmissionBlocker.DESTINATION_UNREADABLE);
      } finally {
        PaymentSubmissionPayload.wipe(bytes);
      }
    }
    return new Evaluation(batch, profile, blockers);
  }

  private static String previewDigest(
      TenantId tenant, UUID entity, BatchRow batch, PaymentSubmissionProfile profile, Instant at) {
    return PaymentSubmissionDigests.preview(
        tenant.value(),
        entity,
        batch.id(),
        batch.version(),
        batch.batchDigest(),
        profile.code(),
        profile.keyVersion(),
        batch.lineCount(),
        batch.currency(),
        batch.totalNet(),
        at);
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Reviews what submitting a batch would send, without writing anything but the disclosure audit.
   * A preview never fails for a blocker: its status is {@code READY} or {@code BLOCKED}.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @return the preview, after its disclosure audit committed
   */
  public SubmissionPreview preview(PayrollCaller caller, String legalEntityId, String batchId) {
    return plumbing.observed(
        LOG,
        "payment_submission_preview",
        PREVIEW,
        () -> {
          UUID entityId = PayrollCommands.id(legalEntityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
          UUID id = PaymentSubmissionCommands.batchId(batchId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
                BatchRow batch = requireBatch(tenant, entity, id);
                Instant now = calendar.now();
                Evaluation evaluation = evaluate(tenant, entity, batch);
                boolean ready = evaluation.ready();
                Instant expiresAt = ready ? CompensationCalendar.expiry(now) : null;
                SubmissionPreview preview =
                    new SubmissionPreview(
                        entity,
                        batch.id(),
                        batch.version(),
                        batch.batchDigest(),
                        evaluation.profile() == null ? null : provider(evaluation.profile()),
                        ready ? "READY" : "BLOCKED",
                        evaluation.blockers().stream().map(PaymentSubmissionBlocker::name).toList(),
                        batch.currency(),
                        batch.lineCount(),
                        new MethodCounts(batch.bank(), batch.mobile(), batch.manual()),
                        batch.totalNet(),
                        ready ? previewDigest(tenant, entity, batch, evaluation.profile(), expiresAt) : null,
                        expiresAt);
                recorder.disclosed(
                    caller,
                    PaymentSubmissionRecorder.PREVIEW,
                    batch.id(),
                    Map.of("legalEntityId", entity.toString(), "batchId", batch.id().toString()),
                    "preview",
                    true,
                    batch.lineCount(),
                    now);
                return preview;
              });
        },
        preview -> "BLOCKED".equals(preview.status()) ? Outcome.STATE_CONFLICT : Outcome.LISTED);
  }

  private static ProviderView provider(PaymentSubmissionProfile profile) {
    return new ProviderView(profile.code(), profile.label(), profile.synthetic());
  }

  // ------------------------------------------------------------------------------------------
  // Create
  // ------------------------------------------------------------------------------------------

  /**
   * Submits a previewed batch, or replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the evidence the preview returned and the two confirmations
   * @return the amount-free receipt
   */
  public IdempotentOperation.Result<SubmissionReceipt> submit(
      PayrollCaller caller,
      String legalEntityId,
      String batchId,
      String idempotencyKey,
      CommitPaymentSubmissionRequest body) {
    return plumbing.command(
        CREATE,
        () -> {
          record Parsed(UUID entity, UUID batch, Commit command) {}
          Parsed parsed =
              operations.validated(
                  CREATE_SPEC,
                  () ->
                      new Parsed(
                          PayrollCommands.id(legalEntityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND),
                          PaymentSubmissionCommands.batchId(batchId),
                          PaymentSubmissionCommands.commit(idempotencyKey, body)));
          return operations.execute(
              CREATE_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.command().canonical(caller.tenant().value(), parsed.entity(), parsed.batch()),
              SubmissionReceipt.class,
              WRITE_TIMEOUT,
              () -> created(caller, parsed.entity(), parsed.batch(), parsed.command()));
        });
  }

  private IdempotentOperation.Completed<SubmissionReceipt> created(
      PayrollCaller caller, UUID entityId, UUID batchId, Commit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, COMMIT_LIMIT);
    UUID entity = entities.require(tenant, entityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
    Instant now = calendar.now();
    BatchRow first = requireBatch(tenant, entity, batchId);
    // Lock order of ADR 0036 section 8 extended by ADR 0037 section 5: the tenant statutory lock
    // shared, the entity's package lock, the package row, the tenant payment lock exclusive, the
    // batch row, then the batch's own submission lock; everything is re-read after.
    statutory.lockStatutory(tenant, true);
    packages.lockPackage(tenant, entity);
    if (!packages.lockPackageRow(tenant, entity, first.packageId())) {
      throw new ApiException(ErrorCode.PAYROLL_PACKAGE_NOT_FOUND, Map.of());
    }
    payments.lockPayments(tenant, false);
    if (!payments.lockBatch(tenant, entity, batchId)) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_NOT_FOUND, Map.of());
    }
    submissions.lockSubmission(tenant, batchId);
    BatchRow batch = requireBatch(tenant, entity, batchId);
    Evaluation evaluation = evaluate(tenant, entity, batch);
    requireSubmittable(tenant, entity, evaluation, command, now);
    PaymentSubmissionProfile profile = evaluation.profile();
    UUID id = UUID.randomUUID();
    try {
      submissions.insert(
          tenant,
          new NewSubmission(
              id,
              entity,
              batch.id(),
              batch.packageId(),
              UUID.randomUUID(),
              profile.code(),
              profile.keyVersion(),
              batch.version(),
              batch.batchDigest(),
              batch.currency(),
              batch.lineCount(),
              batch.bank(),
              batch.mobile(),
              batch.manual(),
              batch.totalNet(),
              now,
              caller.subject()));
      submissions.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.created(
        caller,
        new PaymentSubmissionRecorder.Change(
            entity,
            batch.id(),
            id,
            profile.code(),
            PaymentSubmissionState.QUEUED.name(),
            1,
            batch.lineCount(),
            0,
            null,
            batch.batchDigest(),
            now));
    LOG.atInfo()
        .addKeyValue("operation", CREATE)
        .addKeyValue("provider", profile.code())
        .addKeyValue("state", PaymentSubmissionState.QUEUED.name())
        .addKeyValue("lines", PaymentViews.countBucket(batch.lineCount()))
        .log("payment_submission_created");
    return new IdempotentOperation.Completed<>(
        new SubmissionReceipt(
            id, batch.id(), entity, profile.code(), PaymentSubmissionState.QUEUED.name(), 1, now),
        id,
        Outcome.CREATED);
  }

  /** The contract's check order; nothing is written before every check passed. */
  private static void requireSubmittable(
      TenantId tenant, UUID entity, Evaluation evaluation, Commit command, Instant now) {
    BatchRow batch = evaluation.batch();
    List<PaymentSubmissionBlocker> blockers = evaluation.blockers();
    // 1. The batch moved since the preview.
    if (batch.version() != command.batchVersion()
        || !batch.batchDigest().equals(command.batchDigest())) {
      throw new ApiException(ErrorCode.PAYMENT_SUBMISSION_VERSION_CONFLICT, Map.of());
    }
    // 2. A submission already exists for the batch.
    if (blockers.contains(PaymentSubmissionBlocker.SUBMISSION_EXISTS)) {
      throw new ApiException(ErrorCode.PAYMENT_SUBMISSION_EXISTS, Map.of());
    }
    // 3. The provider is not available or does not support the batch.
    if (blockers.contains(PaymentSubmissionBlocker.PROVIDER_NOT_AVAILABLE)
        || blockers.contains(PaymentSubmissionBlocker.PROVIDER_UNSUPPORTED)) {
      throw new ApiException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, Map.of());
    }
    // 4. The signing key is not available: nothing is written, nothing leaves.
    if (blockers.contains(PaymentSubmissionBlocker.PROVIDER_KEY_UNAVAILABLE)) {
      throw new ApiException(ErrorCode.PAYMENT_SUBMISSION_KEY_UNAVAILABLE, Map.of());
    }
    // 5. Any other blocker.
    if (!blockers.isEmpty()) {
      throw new ApiException(ErrorCode.PAYMENT_SUBMISSION_BLOCKED, Map.of());
    }
    // 6. Expiry and any difference of the preview digest.
    Instant expiresAt = command.expiresAt();
    String digest = previewDigest(tenant, entity, batch, evaluation.profile(), expiresAt);
    if (!expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !digest.equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.PAYMENT_SUBMISSION_PREVIEW_STALE, Map.of());
    }
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * The current state of a batch's submission.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @return the submission, after its disclosure audit committed
   */
  public SubmissionView get(PayrollCaller caller, String legalEntityId, String batchId) {
    return plumbing.observed(
        LOG,
        "payment_submission_read",
        READ,
        () -> {
          UUID entityId = PayrollCommands.id(legalEntityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
          UUID id = PaymentSubmissionCommands.batchId(batchId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
                requireBatch(tenant, entity, id);
                SubmissionRow row = requireSubmission(tenant, entity, id);
                recorder.disclosed(
                    caller,
                    PaymentSubmissionRecorder.READ,
                    row.id(),
                    Map.of("legalEntityId", entity.toString(), "batchId", id.toString()),
                    "submission",
                    true,
                    1,
                    calendar.now());
                return view(row);
              });
        });
  }

  /**
   * A page of the submission's evidence, newest first.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public AttemptPage attempts(
      PayrollCaller caller, String legalEntityId, String batchId, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "payment_submission_read",
        READ,
        () -> {
          UUID entityId = PayrollCommands.id(legalEntityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
          UUID id = PaymentSubmissionCommands.batchId(batchId);
          FieldErrors errors = new FieldErrors();
          int size = PaymentSubmissionCommands.attemptLimit(limit, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.PAYMENT_BATCH_NOT_FOUND);
                requireBatch(tenant, entity, id);
                SubmissionRow row = requireSubmission(tenant, entity, id);
                CursorScope scope = attemptScope(caller, entity, id, size);
                Integer before = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  if (!ATTEMPT_CODE.equals(position.code())
                      || position.id().getMostSignificantBits() != 0L) {
                    throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
                  }
                  long sequence = position.id().getLeastSignificantBits();
                  before =
                      submissions
                          .attempt(tenant, row.id(), (int) sequence)
                          .map(AttemptRow::seq)
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                }
                List<AttemptRow> rows = submissions.attemptPage(tenant, row.id(), before, size + 1);
                List<AttemptRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope,
                          new KeysetPosition(
                              ATTEMPT_CODE, new UUID(0L, shown.get(shown.size() - 1).seq())));
                }
                recorder.disclosed(
                    caller,
                    PaymentSubmissionRecorder.READ,
                    row.id(),
                    Map.of("legalEntityId", entity.toString(), "batchId", id.toString()),
                    "attempts",
                    cursor == null,
                    shown.size(),
                    calendar.now());
                return new AttemptPage(
                    shown.stream()
                        .map(
                            a ->
                                new AttemptView(
                                    a.seq(),
                                    a.attemptNumber(),
                                    a.state(),
                                    a.outcomeCode(),
                                    a.reference(),
                                    a.occurredAt()))
                        .toList(),
                    next);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  private BatchRow requireBatch(TenantId tenant, UUID entity, UUID batchId) {
    return payments
        .batch(tenant, entity, batchId)
        .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_BATCH_NOT_FOUND, Map.of()));
  }

  private SubmissionRow requireSubmission(TenantId tenant, UUID entity, UUID batchId) {
    return submissions
        .byBatch(tenant, entity, batchId)
        .orElseThrow(PaymentSubmissionCommands::notFound);
  }

  private SubmissionView view(SubmissionRow row) {
    PaymentSubmissionProfile profile = profiles.byCode(row.providerCode()).orElse(null);
    ProviderView provider =
        profile == null
            ? new ProviderView(row.providerCode(), row.providerCode(), false)
            : provider(profile);
    return new SubmissionView(
        row.id(),
        row.batchId(),
        row.legalEntityId(),
        provider,
        row.state(),
        row.version(),
        row.batchVersion(),
        row.currency(),
        row.lineCount(),
        new MethodCounts(row.bank(), row.mobile(), row.manual()),
        row.totalNetMinor(),
        row.attemptCount(),
        row.outcomeCode(),
        row.reference(),
        row.nextAttemptAt(),
        row.createdAt(),
        row.updatedAt());
  }

  private static CursorScope attemptScope(PayrollCaller caller, UUID entity, UUID batch, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "attempts");
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
            .flatMap(PaymentConstraintViolations::submissionAnswer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
