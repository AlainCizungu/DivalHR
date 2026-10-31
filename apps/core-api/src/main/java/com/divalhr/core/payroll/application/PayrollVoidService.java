package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPayrollVoidRequest;
import com.divalhr.core.payroll.api.PayrollPackageResponses.VoidPreview;
import com.divalhr.core.payroll.api.PayrollPackageResponses.VoidReceipt;
import com.divalhr.core.payroll.api.PayrollVoidCommandRequest;
import com.divalhr.core.payroll.application.PayrollPackageCommands.VoidCommit;
import com.divalhr.core.payroll.application.PayrollPackageCommands.VoidDraft;
import com.divalhr.core.payroll.domain.PackageDigests;
import com.divalhr.core.payroll.domain.PayrollVoidBlocker;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.NewVoid;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.PackageRow;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository;
import com.divalhr.core.platform.access.PayrollPaymentGuard;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Voiding an approved payroll package, for tenant administrators with privileged MFA (MVP-094C,
 * Issue #181, ADR 0035 section 9): the preview and the commit. A void is the only change a package
 * ever undergoes (APPROVED to VOIDED, after its append-only void evidence); the payslip snapshots
 * are never touched and stay downloadable as visibly VOIDED copies. A void releases the source lock
 * of the run and the calculation, but a pair that was ever voided can never be approved again.
 *
 * <p>A commit runs in one idempotent transaction in the lock order of ADR 0035 section 8: the
 * idempotency reservation, the tenant statutory lock shared, the entity's package lock and the
 * package row {@code FOR UPDATE}. The payment guard is consulted by the preview and again by the
 * commit, which fails closed. Restricted financial and HR data: the explanation of {@code
 * OTHER_CONTROLLED} is stored only in the void evidence and never enters logs, metrics, audit
 * metadata, outbox events, idempotency receipts, cursors or error bodies (the idempotency
 * fingerprint holds only its SHA-256).
 */
@Service
public class PayrollVoidService {

  /** Previews (interceptor operation; disclosed as a package read). */
  public static final String PREVIEW = "payroll-void.preview";

  /** Void (idempotency operation, interceptor operation and audit action). */
  public static final String COMMIT = PayrollPackageRecorder.VOID;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payroll-void");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration COMMIT_LIMIT = Duration.ofSeconds(20);
  private static final IdempotentOperation.Spec COMMIT_SPEC =
      new IdempotentOperation.Spec(COMMIT, "payroll_package_void", "void", "voided", 201);

  private final IdempotentOperation operations;
  private final JdbcPayrollPackageRepository packages;
  private final JdbcPayrollRunRepository runs;
  private final JdbcStatutoryRepository statutory;
  private final PayrollPaymentGuard payments;
  private final PayrollEntities entities;
  private final CompensationCalendar calendar;
  private final PayrollPackageRecorder recorder;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param packages payroll package storage
   * @param runs payroll storage (limits)
   * @param statutory statutory storage (the tenant lock)
   * @param payments the payment guard port
   * @param entities legal entity resolver
   * @param calendar business date and preview window
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   */
  public PayrollVoidService(
      IdempotentOperation operations,
      JdbcPayrollPackageRepository packages,
      JdbcPayrollRunRepository runs,
      JdbcStatutoryRepository statutory,
      PayrollPaymentGuard payments,
      PayrollEntities entities,
      CompensationCalendar calendar,
      PayrollPackageRecorder recorder,
      PayrollOperations plumbing) {
    this.operations = operations;
    this.packages = packages;
    this.runs = runs;
    this.statutory = statutory;
    this.payments = payments;
    this.entities = entities;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Validates a void and reports whether it can proceed. A preview never fails for a blocker: its
   * status is {@code READY} or {@code BLOCKED}.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageId raw path value
   * @param body the reason and explanation
   * @return the preview, after its disclosure audit committed
   */
  public VoidPreview preview(
      PayrollCaller caller,
      String legalEntityId,
      String packageId,
      PayrollVoidCommandRequest body) {
    return plumbing.observed(
        LOG,
        "payroll_void_preview",
        PREVIEW,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          UUID id = PayrollPackageCommands.packageId(packageId);
          VoidDraft draft = PayrollPackageCommands.voidDraft(body);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                PackageRow row =
                    packages
                        .pack(tenant, entity, id)
                        .orElseThrow(
                            () -> new ApiException(ErrorCode.PAYROLL_PACKAGE_NOT_FOUND, Map.of()));
                Instant now = calendar.now();
                List<String> blockers = new ArrayList<>();
                if (!"APPROVED".equals(row.status())) {
                  blockers.add(PayrollVoidBlocker.ALREADY_VOIDED.name());
                }
                if (payments.paymentExists(tenant, entity, id)) {
                  blockers.add(PayrollVoidBlocker.PAYMENT_EXISTS.name());
                }
                boolean ready = blockers.isEmpty();
                Instant expiresAt = ready ? CompensationCalendar.expiry(now) : null;
                VoidPreview preview =
                    new VoidPreview(
                        entity,
                        row.id(),
                        row.version(),
                        StatutoryViews.month(row.periodStart()),
                        row.currency(),
                        row.status(),
                        ready ? "READY" : "BLOCKED",
                        blockers,
                        draft.reason().name(),
                        row.payslipCount(),
                        row.approvalDigest(),
                        ready ? previewDigest(tenant, entity, row, draft, expiresAt) : null,
                        expiresAt);
                recorder.disclosed(
                    caller,
                    PayrollPackageRecorder.PACKAGE_READ,
                    "payroll-package",
                    row.id(),
                    Map.of("legalEntityId", entity.toString(), "packageId", row.id().toString()),
                    "void-preview",
                    true,
                    1,
                    now);
                return preview;
              });
        },
        preview -> "BLOCKED".equals(preview.status()) ? Outcome.STATE_CONFLICT : Outcome.LISTED);
  }

  private static String previewDigest(
      TenantId tenant, UUID entity, PackageRow row, VoidDraft draft, Instant expiresAt) {
    return PackageDigests.voidPreview(
        tenant.value(),
        entity,
        row.id(),
        row.version(),
        row.approvalDigest(),
        draft.reason(),
        draft.explanation(),
        expiresAt);
  }

  // ------------------------------------------------------------------------------------------
  // Commit
  // ------------------------------------------------------------------------------------------

  /**
   * Voids a previewed package, or replays an identical void.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param packageId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the evidence the preview returned and the no-payment confirmation
   * @return the amount-free receipt
   */
  public IdempotentOperation.Result<VoidReceipt> voidPackage(
      PayrollCaller caller,
      String legalEntityId,
      String packageId,
      String idempotencyKey,
      CommitPayrollVoidRequest body) {
    return plumbing.command(
        COMMIT,
        () -> {
          record Parsed(UUID entity, UUID packageId, VoidCommit command) {}
          Parsed parsed =
              operations.validated(
                  COMMIT_SPEC,
                  () ->
                      new Parsed(
                          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND),
                          PayrollPackageCommands.packageId(packageId),
                          PayrollPackageCommands.voidCommit(idempotencyKey, body)));
          return operations.execute(
              COMMIT_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.command().canonical(caller.tenant(), parsed.entity(), parsed.packageId()),
              VoidReceipt.class,
              WRITE_TIMEOUT,
              () -> voided(caller, parsed.entity(), parsed.packageId(), parsed.command()));
        });
  }

  private IdempotentOperation.Completed<VoidReceipt> voided(
      PayrollCaller caller, UUID entity, UUID packageId, VoidCommit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, COMMIT_LIMIT);
    entities.require(tenant, entity, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
    Instant now = calendar.now();
    // Lock order of ADR 0035 section 8: the tenant statutory lock shared, the entity's package
    // lock, then the package row.
    statutory.lockStatutory(tenant, true);
    packages.lockPackage(tenant, entity);
    if (!packages.lockPackageRow(tenant, entity, packageId)) {
      throw new ApiException(ErrorCode.PAYROLL_PACKAGE_NOT_FOUND, Map.of());
    }
    PackageRow row =
        packages
            .pack(tenant, entity, packageId)
            .orElseThrow(() -> new ApiException(ErrorCode.PAYROLL_PACKAGE_NOT_FOUND, Map.of()));
    // 3. Already voided.
    if (!"APPROVED".equals(row.status())) {
      throw new ApiException(ErrorCode.PAYROLL_PACKAGE_VOIDED, Map.of());
    }
    // 4. A different package version or approval digest.
    if (row.version() != command.packageVersion()
        || !row.approvalDigest().equals(command.approvalDigest())) {
      throw new ApiException(ErrorCode.PAYROLL_PACKAGE_VERSION_CONFLICT, Map.of());
    }
    // 5. A payment or disbursement record exists: the guard fails closed.
    if (payments.paymentExists(tenant, entity, packageId)) {
      throw new ApiException(ErrorCode.PAYROLL_VOID_NOT_ALLOWED, Map.of());
    }
    // 6. Expiry or a different preview digest.
    Instant expiresAt = command.expiresAt();
    if (!expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !previewDigest(tenant, entity, row, command.draft(), expiresAt)
            .equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.PAYROLL_VOID_PREVIEW_STALE, Map.of());
    }

    try {
      packages.insertVoid(
          tenant,
          entity,
          packageId,
          new NewVoid(
              UUID.randomUUID(),
              command.draft().reason().name(),
              command.draft().explanation(),
              row.approvalDigest(),
              now,
              caller.subject()));
      if (packages.markVoided(tenant, entity, packageId, now) != 1) {
        throw new ApiException(ErrorCode.PAYROLL_PACKAGE_VOIDED, Map.of());
      }
      packages.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw PayrollApprovalService.integrity(violated);
    }
    String period = StatutoryViews.month(row.periodStart());
    recorder.voided(
        caller,
        new PayrollPackageRecorder.VoidRecorded(
            entity,
            packageId,
            row.version(),
            period,
            row.payslipCount(),
            row.approvalDigest(),
            now));
    LOG.atInfo()
        .addKeyValue("operation", COMMIT)
        .addKeyValue("outcome", "recorded")
        .addKeyValue("payslips", PayrollPackageViews.countBucket(row.payslipCount()))
        .log("payroll_package_voided");
    return new IdempotentOperation.Completed<>(
        new VoidReceipt(
            packageId,
            entity,
            row.version(),
            period,
            PayrollPackageViews.CURRENCY,
            "VOIDED",
            command.draft().reason().name(),
            row.payslipCount(),
            now),
        packageId,
        Outcome.UPDATED);
  }
}
