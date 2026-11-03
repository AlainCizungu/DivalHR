package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPaymentReconciliationRequest;
import com.divalhr.core.payroll.api.PaymentReconciliationResponses.Conclusion;
import com.divalhr.core.payroll.api.PaymentReconciliationResponses.InquiryPage;
import com.divalhr.core.payroll.api.PaymentReconciliationResponses.InquiryView;
import com.divalhr.core.payroll.api.PaymentReconciliationResponses.Preview;
import com.divalhr.core.payroll.api.PaymentReconciliationResponses.Receipt;
import com.divalhr.core.payroll.api.PaymentReconciliationResponses.View;
import com.divalhr.core.payroll.api.PaymentSubmissionResponses.ProviderView;
import com.divalhr.core.payroll.application.PaymentReconciliationCommands.Commit;
import com.divalhr.core.payroll.domain.PaymentReconciliationBlocker;
import com.divalhr.core.payroll.domain.PaymentReconciliationDigests;
import com.divalhr.core.payroll.domain.PaymentReconciliationState;
import com.divalhr.core.payroll.domain.PaymentSubmissionProfile;
import com.divalhr.core.payroll.domain.PaymentSubmissionState;
import com.divalhr.core.payroll.internal.JdbcPaymentReconciliationRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentReconciliationRepository.InquiryRow;
import com.divalhr.core.payroll.internal.JdbcPaymentReconciliationRepository.NewReconciliation;
import com.divalhr.core.payroll.internal.JdbcPaymentReconciliationRepository.ReconciliationRow;
import com.divalhr.core.payroll.internal.JdbcPaymentSubmissionRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentSubmissionRepository.SubmissionRow;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
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
 * Reconciliation of uncertain provider submissions (MVP-095C, ADR 0038): the preview, the
 * idempotent creation of exactly one reconciliation per submission, the current state and the
 * inquiry history, for tenant administrators with privileged MFA. Authorization has run before
 * this; the tenant, the actor and the legal entity scope never come from the request.
 *
 * <p>Boundary: this service never contacts a provider and never touches the submission, its
 * attempts or its request ID. It only opens an investigation that the inquiry worker carries out,
 * and the inquiry is never a payment instruction. "Accepted for processing" never means paid. Every
 * unknown, malformed, foreign or submission-less identifier is one safe 404.
 */
@Service
public class PaymentReconciliationService {

  /** Previews (interceptor operation). */
  public static final String PREVIEW = PaymentReconciliationRecorder.PREVIEW;

  /** Creation (idempotency operation, interceptor operation and audit action). */
  public static final String CREATE = PaymentReconciliationRecorder.CREATE;

  /** Reads and history (interceptor operation, cursor binding and audit action). */
  public static final String READ = PaymentReconciliationRecorder.READ;

  /** Per-subject and per-tenant previews. */
  public static final String PREVIEW_BUCKET = "payment-reconciliation-preview";

  /** Per-subject and per-tenant creations. */
  public static final String WRITE_BUCKET = "payment-reconciliation-write";

  /** Per-subject and per-tenant reads and history. */
  public static final String READ_BUCKET = "payment-reconciliation-read";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payment-reconciliation");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration COMMIT_LIMIT = Duration.ofSeconds(25);
  private static final String INQUIRY_CODE = "PAYRECINQ";
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "payment_reconciliation", "create", "created", 201);

  private final IdempotentOperation operations;
  private final JdbcPaymentSubmissionRepository submissions;
  private final JdbcPaymentReconciliationRepository reconciliations;
  private final JdbcPayrollRunRepository runs;
  private final PayrollEntities entities;
  private final PaymentSubmissionProfiles profiles;
  private final PaymentSubmissionSecrets keys;
  private final CompensationCalendar calendar;
  private final PaymentReconciliationRecorder recorder;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param submissions submission storage (read and lock only)
   * @param reconciliations reconciliation storage
   * @param runs payroll storage (transaction limits)
   * @param entities legal entity resolver
   * @param profiles operator-controlled provider profiles
   * @param keys the signing key holder
   * @param calendar clock and preview window
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   */
  public PaymentReconciliationService(
      IdempotentOperation operations,
      JdbcPaymentSubmissionRepository submissions,
      JdbcPaymentReconciliationRepository reconciliations,
      JdbcPayrollRunRepository runs,
      PayrollEntities entities,
      PaymentSubmissionProfiles profiles,
      PaymentSubmissionSecrets keys,
      CompensationCalendar calendar,
      PaymentReconciliationRecorder recorder,
      PayrollOperations plumbing,
      CursorCodec cursors) {
    this.operations = operations;
    this.submissions = submissions;
    this.reconciliations = reconciliations;
    this.runs = runs;
    this.entities = entities;
    this.profiles = profiles;
    this.keys = keys;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
    this.cursors = cursors;
  }

  private record Evaluation(
      SubmissionRow submission,
      PaymentSubmissionProfile profile,
      ReconciliationRow existing,
      List<PaymentReconciliationBlocker> blockers) {
    boolean ready() {
      return blockers.isEmpty();
    }
  }

  /** Reads and decides everything an investigation depends on; writes nothing. */
  private Evaluation evaluate(TenantId tenant, SubmissionRow submission) {
    List<PaymentReconciliationBlocker> blockers = new ArrayList<>();
    PaymentSubmissionState state = PaymentSubmissionState.parse(submission.state()).orElse(null);
    if (state != PaymentSubmissionState.DELIVERY_UNKNOWN
        && state != PaymentSubmissionState.MANUAL_INTERVENTION) {
      blockers.add(PaymentReconciliationBlocker.SUBMISSION_NOT_ELIGIBLE);
    }
    ReconciliationRow existing = reconciliations.bySubmission(tenant, submission.id()).orElse(null);
    if (existing != null) {
      blockers.add(PaymentReconciliationBlocker.RECONCILIATION_EXISTS);
    }
    PaymentSubmissionProfile profile = profiles.byCode(submission.providerCode()).orElse(null);
    if (profile == null) {
      blockers.add(PaymentReconciliationBlocker.PROVIDER_NOT_AVAILABLE);
    } else if (!keys.available()) {
      blockers.add(PaymentReconciliationBlocker.PROVIDER_KEY_UNAVAILABLE);
    }
    return new Evaluation(submission, profile, existing, blockers);
  }

  private static String previewDigest(
      TenantId tenant,
      UUID entity,
      SubmissionRow submission,
      PaymentSubmissionProfile profile,
      Instant expiresAt) {
    return PaymentReconciliationDigests.preview(
        tenant.value(),
        entity,
        submission.batchId(),
        submission.id(),
        submission.version(),
        submission.state(),
        profile.code(),
        profile.keyVersion(),
        expiresAt);
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Reviews what an investigation would do, without writing anything but the disclosure audit. A
   * preview never fails for a blocker: its status is {@code READY} or {@code BLOCKED}.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @return the preview, after its disclosure audit committed
   */
  public Preview preview(PayrollCaller caller, String legalEntityId, String batchId) {
    return plumbing.observed(
        LOG,
        "payment_reconciliation_preview",
        PREVIEW,
        () -> {
          UUID entityId = PaymentReconciliationCommands.id(legalEntityId);
          UUID id = PaymentReconciliationCommands.id(batchId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, notFoundCode());
                SubmissionRow submission = requireSubmission(tenant, entity, id);
                Instant now = calendar.now();
                Evaluation evaluation = evaluate(tenant, submission);
                boolean ready = evaluation.ready();
                Instant expiresAt = ready ? CompensationCalendar.expiry(now) : null;
                Conclusion previous =
                    evaluation.existing() == null
                        ? null
                        : new Conclusion(
                            evaluation.existing().state(),
                            evaluation.existing().outcomeCode(),
                            evaluation.existing().updatedAt());
                Preview preview =
                    new Preview(
                        entity,
                        submission.batchId(),
                        submission.id(),
                        submission.state(),
                        submission.version(),
                        submission.outcomeCode(),
                        evaluation.profile() == null ? null : provider(evaluation.profile()),
                        PaymentReconciliationDigests.requestFingerprint(submission.requestId()),
                        previous,
                        ready ? "READY" : "BLOCKED",
                        evaluation.blockers().stream()
                            .map(PaymentReconciliationBlocker::name)
                            .toList(),
                        ready
                            ? previewDigest(
                                tenant, entity, submission, evaluation.profile(), expiresAt)
                            : null,
                        expiresAt);
                recorder.disclosed(
                    caller,
                    PaymentReconciliationRecorder.PREVIEW,
                    submission.id(),
                    Map.of(
                        "legalEntityId",
                        entity.toString(),
                        "batchId",
                        submission.batchId().toString(),
                        "submissionId",
                        submission.id().toString()),
                    "preview",
                    true,
                    1,
                    now);
                return preview;
              });
        },
        preview -> "BLOCKED".equals(preview.status()) ? Outcome.STATE_CONFLICT : Outcome.LISTED);
  }

  private static ProviderView provider(PaymentSubmissionProfile profile) {
    return new ProviderView(profile.code(), profile.label(), profile.synthetic());
  }

  private static ErrorCode notFoundCode() {
    return ErrorCode.PAYMENT_RECONCILIATION_NOT_FOUND;
  }

  // ------------------------------------------------------------------------------------------
  // Create
  // ------------------------------------------------------------------------------------------

  /**
   * Opens an investigation of a previewed submission, or replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the evidence the preview returned and the two confirmations
   * @return the amount-free receipt
   */
  public IdempotentOperation.Result<Receipt> open(
      PayrollCaller caller,
      String legalEntityId,
      String batchId,
      String idempotencyKey,
      CommitPaymentReconciliationRequest body) {
    return plumbing.command(
        CREATE,
        () -> {
          record Parsed(UUID entity, UUID batch, Commit command) {}
          Parsed parsed =
              operations.validated(
                  CREATE_SPEC,
                  () ->
                      new Parsed(
                          PaymentReconciliationCommands.id(legalEntityId),
                          PaymentReconciliationCommands.id(batchId),
                          PaymentReconciliationCommands.commit(idempotencyKey, body)));
          return operations.execute(
              CREATE_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.command().canonical(caller.tenant().value(), parsed.entity(), parsed.batch()),
              Receipt.class,
              WRITE_TIMEOUT,
              () -> created(caller, parsed.entity(), parsed.batch(), parsed.command()));
        });
  }

  private IdempotentOperation.Completed<Receipt> created(
      PayrollCaller caller, UUID entityId, UUID batchId, Commit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, COMMIT_LIMIT);
    UUID entity = entities.require(tenant, entityId, notFoundCode());
    Instant now = calendar.now();
    requireSubmission(tenant, entity, batchId);
    // Lock order (ADR 0038 section 6): the batch's submission lock, then the submission's
    // reconciliation lock; the submission is re-read after both.
    submissions.lockSubmission(tenant, batchId);
    SubmissionRow first = requireSubmission(tenant, entity, batchId);
    reconciliations.lockReconciliation(tenant, first.id());
    SubmissionRow submission = requireSubmission(tenant, entity, batchId);
    Evaluation evaluation = evaluate(tenant, submission);
    requireOpenable(tenant, entity, evaluation, command, now);
    PaymentSubmissionProfile profile = evaluation.profile();
    UUID id = UUID.randomUUID();
    try {
      reconciliations.insert(
          tenant,
          new NewReconciliation(
              id,
              entity,
              submission.id(),
              submission.batchId(),
              submission.requestId(),
              profile.code(),
              profile.keyVersion(),
              UUID.randomUUID(),
              submission.state(),
              submission.version(),
              now,
              caller.subject()));
      reconciliations.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.created(
        caller,
        new PaymentReconciliationRecorder.Change(
            entity,
            submission.batchId(),
            submission.id(),
            id,
            profile.code(),
            PaymentReconciliationState.OPEN.name(),
            1,
            0,
            null,
            now));
    LOG.atInfo()
        .addKeyValue("operation", CREATE)
        .addKeyValue("provider", profile.code())
        .addKeyValue("state", PaymentReconciliationState.OPEN.name())
        .log("payment_reconciliation_created");
    return new IdempotentOperation.Completed<>(
        new Receipt(
            id,
            submission.id(),
            submission.batchId(),
            entity,
            profile.code(),
            PaymentReconciliationState.OPEN.name(),
            1,
            now),
        id,
        Outcome.CREATED);
  }

  /** The contract's check order; nothing is written before every check passed. */
  private static void requireOpenable(
      TenantId tenant, UUID entity, Evaluation evaluation, Commit command, Instant now) {
    SubmissionRow submission = evaluation.submission();
    List<PaymentReconciliationBlocker> blockers = evaluation.blockers();
    // 1. An investigation already exists for the submission (one per submission, forever).
    if (blockers.contains(PaymentReconciliationBlocker.RECONCILIATION_EXISTS)) {
      throw new ApiException(ErrorCode.PAYMENT_RECONCILIATION_EXISTS, Map.of());
    }
    // 2. The submission is not in an uncertain state.
    if (blockers.contains(PaymentReconciliationBlocker.SUBMISSION_NOT_ELIGIBLE)) {
      throw new ApiException(ErrorCode.PAYMENT_RECONCILIATION_NOT_ELIGIBLE, Map.of());
    }
    // 3. The provider is not available.
    if (blockers.contains(PaymentReconciliationBlocker.PROVIDER_NOT_AVAILABLE)) {
      throw new ApiException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, Map.of());
    }
    // 4. The signing key is not available: nothing is written, nothing leaves.
    if (blockers.contains(PaymentReconciliationBlocker.PROVIDER_KEY_UNAVAILABLE)) {
      throw new ApiException(ErrorCode.PAYMENT_SUBMISSION_KEY_UNAVAILABLE, Map.of());
    }
    // 5. The submission moved since the preview, expiry, and any difference of the digest.
    Instant expiresAt = command.expiresAt();
    String digest = previewDigest(tenant, entity, submission, evaluation.profile(), expiresAt);
    if (submission.version() != command.submissionVersion()
        || !submission.state().equals(command.submissionState().name())
        || !expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !digest.equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.PAYMENT_RECONCILIATION_PREVIEW_STALE, Map.of());
    }
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * The current state of a submission's investigation.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @return the reconciliation, after its disclosure audit committed
   */
  public View get(PayrollCaller caller, String legalEntityId, String batchId) {
    return plumbing.observed(
        LOG,
        "payment_reconciliation_read",
        READ,
        () -> {
          UUID entityId = PaymentReconciliationCommands.id(legalEntityId);
          UUID id = PaymentReconciliationCommands.id(batchId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, notFoundCode());
                SubmissionRow submission = requireSubmission(tenant, entity, id);
                ReconciliationRow row = requireReconciliation(tenant, submission);
                recorder.disclosed(
                    caller,
                    PaymentReconciliationRecorder.READ,
                    row.id(),
                    Map.of(
                        "legalEntityId",
                        entity.toString(),
                        "batchId",
                        id.toString(),
                        "reconciliationId",
                        row.id().toString()),
                    "reconciliation",
                    true,
                    1,
                    calendar.now());
                return view(row, submission);
              });
        });
  }

  /**
   * A page of the investigation's evidence, newest first.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param batchId raw path value
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public InquiryPage inquiries(
      PayrollCaller caller, String legalEntityId, String batchId, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "payment_reconciliation_read",
        READ,
        () -> {
          UUID entityId = PaymentReconciliationCommands.id(legalEntityId);
          UUID id = PaymentReconciliationCommands.id(batchId);
          FieldErrors errors = new FieldErrors();
          int size = PaymentReconciliationCommands.inquiryLimit(limit, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, notFoundCode());
                SubmissionRow submission = requireSubmission(tenant, entity, id);
                ReconciliationRow row = requireReconciliation(tenant, submission);
                CursorScope scope = inquiryScope(caller, entity, id, size);
                Integer before = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  if (!INQUIRY_CODE.equals(position.code())
                      || position.id().getMostSignificantBits() != 0L) {
                    throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
                  }
                  long sequence = position.id().getLeastSignificantBits();
                  before =
                      reconciliations
                          .inquiry(tenant, row.id(), (int) sequence)
                          .map(InquiryRow::seq)
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                }
                List<InquiryRow> rows =
                    reconciliations.inquiryPage(tenant, row.id(), before, size + 1);
                List<InquiryRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope,
                          new KeysetPosition(
                              INQUIRY_CODE, new UUID(0L, shown.get(shown.size() - 1).seq())));
                }
                recorder.disclosed(
                    caller,
                    PaymentReconciliationRecorder.READ,
                    row.id(),
                    Map.of(
                        "legalEntityId",
                        entity.toString(),
                        "batchId",
                        id.toString(),
                        "reconciliationId",
                        row.id().toString()),
                    "inquiries",
                    cursor == null,
                    shown.size(),
                    calendar.now());
                return new InquiryPage(
                    shown.stream()
                        .map(
                            a ->
                                new InquiryView(
                                    a.seq(),
                                    a.attemptNumber(),
                                    a.state(),
                                    a.outcomeCode(),
                                    a.authenticated(),
                                    a.occurredAt()))
                        .toList(),
                    next);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  private SubmissionRow requireSubmission(TenantId tenant, UUID entity, UUID batchId) {
    return submissions
        .byBatch(tenant, entity, batchId)
        .orElseThrow(PaymentReconciliationCommands::notFound);
  }

  private ReconciliationRow requireReconciliation(TenantId tenant, SubmissionRow submission) {
    return reconciliations
        .bySubmission(tenant, submission.id())
        .orElseThrow(PaymentReconciliationCommands::notFound);
  }

  private View view(ReconciliationRow row, SubmissionRow submission) {
    PaymentSubmissionProfile profile = profiles.byCode(row.providerCode()).orElse(null);
    ProviderView provider =
        profile == null
            ? new ProviderView(row.providerCode(), row.providerCode(), false)
            : provider(profile);
    return new View(
        row.id(),
        row.submissionId(),
        row.batchId(),
        row.legalEntityId(),
        provider,
        row.state(),
        row.version(),
        submission.state(),
        PaymentReconciliationDigests.requestFingerprint(row.requestId()),
        row.attemptCount(),
        row.outcomeCode(),
        row.nextAttemptAt(),
        row.createdAt(),
        row.updatedAt());
  }

  private static CursorScope inquiryScope(PayrollCaller caller, UUID entity, UUID batch, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "inquiries");
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
            .flatMap(PaymentConstraintViolations::reconciliationAnswer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
