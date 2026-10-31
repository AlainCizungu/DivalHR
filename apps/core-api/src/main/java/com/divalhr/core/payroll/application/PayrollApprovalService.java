package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPayrollApprovalRequest;
import com.divalhr.core.payroll.api.PayrollApprovalPreviewRequest;
import com.divalhr.core.payroll.api.PayrollPackageResponses.ApprovalLine;
import com.divalhr.core.payroll.api.PayrollPackageResponses.ApprovalPreview;
import com.divalhr.core.payroll.api.PayrollPackageResponses.ApprovalReceipt;
import com.divalhr.core.payroll.api.PayrollPackageResponses.RulePack;
import com.divalhr.core.payroll.application.PayrollApprovalEvidence.Loaded;
import com.divalhr.core.payroll.application.PayrollPackageCommands.ApprovalCommit;
import com.divalhr.core.payroll.domain.PackageDigests;
import com.divalhr.core.payroll.domain.PackageDigests.ApprovalBasis;
import com.divalhr.core.payroll.domain.PayrollApprovalBlocker;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.Result;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.Totals;
import com.divalhr.core.payroll.domain.PayrollApprovalWarning;
import com.divalhr.core.payroll.domain.PayslipSnapshot;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.NewPackage;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.NewSlip;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.PackageRow;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.RunRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.CalculationRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.ComponentRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.LineRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.PackRow;
import com.divalhr.core.payroll.internal.PayrollPackageConstraintViolations;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Approval of a frozen payroll run and its statutory calculation, publishing one canonical payslip
 * per employee, for tenant administrators with privileged MFA (MVP-094C, Issue #181, ADR 0035): the
 * preview and the commit. Authorization has run before this; tenant, actor and scope never come
 * from the request. The approver is the verified token subject, never a request value.
 *
 * <p>Boundary: approving payroll freezes a calculation and publishes payslips. It is not a payment,
 * a filing or a claim of legal compliance. Nothing is recalculated except the read-only source
 * proof (a fresh payroll calculation of the month must still equal the frozen run); the amounts are
 * copied from the frozen statutory calculation lines and proved to add up.
 *
 * <p>A commit runs in one idempotent transaction in the lock order of ADR 0035 section 8: the
 * idempotency reservation, the tenant statutory lock exclusive, the entity's run, calculation and
 * package locks, the run and calculation rows {@code FOR UPDATE}, the entity's package rows {@code
 * FOR UPDATE}, and only then everything is re-read and decided. The checks run in the order the
 * contract fixes. Restricted financial and HR data: amounts, employee numbers, snapshots, digests
 * and subjects never enter logs, metrics, audit metadata, outbox events, idempotency receipts,
 * cursors or error bodies.
 */
@Service
public class PayrollApprovalService {

  /** Previews (interceptor operation; disclosed as a package read). */
  public static final String PREVIEW = "payroll-approval.preview";

  /** Approval (idempotency operation, interceptor operation and audit action). */
  public static final String COMMIT = PayrollPackageRecorder.APPROVE;

  /** Per-subject previews (shared with void previews). */
  public static final String SUBJECT_PREVIEW_BUCKET = "payroll-approval-preview";

  /** Per-subject approvals and voids. */
  public static final String SUBJECT_COMMIT_BUCKET = "payroll-approval-write";

  /** Per-tenant previews. */
  public static final String TENANT_PREVIEW_BUCKET = "payroll-approval-preview";

  /** Per-tenant approvals and voids. */
  public static final String TENANT_COMMIT_BUCKET = "payroll-approval-write";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payroll-approval");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration COMMIT_LIMIT = Duration.ofSeconds(20);
  private static final IdempotentOperation.Spec COMMIT_SPEC =
      new IdempotentOperation.Spec(COMMIT, "payroll_package", "approve", "approved", 201);

  private final IdempotentOperation operations;
  private final JdbcPayrollPackageRepository packages;
  private final JdbcPayrollRunRepository runs;
  private final JdbcStatutoryRepository statutory;
  private final PayrollEntities entities;
  private final PayrollApprovalEvidence evidence;
  private final CompensationCalendar calendar;
  private final PayrollPackageRecorder recorder;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param packages payroll package storage
   * @param runs payroll storage (locks and limits)
   * @param statutory statutory storage (locks)
   * @param entities legal entity resolver
   * @param evidence the loader and decider of an approval
   * @param calendar business date and preview window
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   */
  public PayrollApprovalService(
      IdempotentOperation operations,
      JdbcPayrollPackageRepository packages,
      JdbcPayrollRunRepository runs,
      JdbcStatutoryRepository statutory,
      PayrollEntities entities,
      PayrollApprovalEvidence evidence,
      CompensationCalendar calendar,
      PayrollPackageRecorder recorder,
      PayrollOperations plumbing) {
    this.operations = operations;
    this.packages = packages;
    this.runs = runs;
    this.statutory = statutory;
    this.entities = entities;
    this.evidence = evidence;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Reviews what approving would publish, without writing anything but the disclosure audit. A
   * preview never fails for a blocker: its status is {@code READY}, {@code BLOCKED} or {@code
   * NO_CHANGE}.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param runId raw path value
   * @param calculationId raw path value
   * @param body the (empty) command
   * @return the preview, after its disclosure audit committed
   */
  public ApprovalPreview preview(
      PayrollCaller caller,
      String legalEntityId,
      String runId,
      String calculationId,
      PayrollApprovalPreviewRequest body) {
    return plumbing.observed(
        LOG,
        "payroll_approval_preview",
        PREVIEW,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          UUID run = PayrollCommands.id(runId, ErrorCode.PAYROLL_RUN_NOT_FOUND);
          UUID calculation =
              PayrollCommands.id(calculationId, ErrorCode.STATUTORY_CALCULATION_NOT_FOUND);
          PayrollPackageCommands.approvalCommand(body);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                Instant now = calendar.now();
                Loaded loaded =
                    evidence.load(tenant, entity, run, calculation, caller.subject(), false);
                ApprovalPreview preview = previewOf(tenant, entity, loaded, now);
                recorder.disclosed(
                    caller,
                    PayrollPackageRecorder.PACKAGE_READ,
                    "payroll-approval",
                    calculation,
                    Map.of("legalEntityId", entity.toString()),
                    "approval-preview",
                    true,
                    preview.lines().size(),
                    now);
                return preview;
              });
        },
        PayrollApprovalService::previewOutcome);
  }

  /** A blocked preview is a state conflict and an unchanged one is unchanged, never a success. */
  private static Outcome previewOutcome(ApprovalPreview preview) {
    return switch (preview.status()) {
      case "BLOCKED" -> Outcome.STATE_CONFLICT;
      case "NO_CHANGE" -> Outcome.UNCHANGED;
      default -> Outcome.LISTED;
    };
  }

  private static ApprovalPreview previewOf(
      TenantId tenant, UUID entity, Loaded loaded, Instant now) {
    RunRow run = loaded.run();
    CalculationRow calculation = loaded.calculation();
    Result result = loaded.result();
    PackRow pack = loaded.pack();
    boolean ready = result.status() == PayrollApprovalEvaluator.Status.READY;
    Instant expiresAt = ready ? CompensationCalendar.expiry(now) : null;
    String previewDigest =
        ready
            ? PackageDigests.approvalPreview(
                tenant.value(),
                entity,
                run.id(),
                run.version(),
                calculation.id(),
                calculation.version(),
                loaded.sourceRunDigest(),
                calculation.calculationDigest(),
                loaded.profileDigest(),
                loaded.rulePackDigest(),
                run.policyId(),
                run.policySequence(),
                loaded.samePreparer(),
                loaded.expectedVersion(),
                expiresAt)
            : null;
    RulePack rulePack =
        pack == null ? null : PayrollPackageViews.rulePack(pack.id(), pack.code(), pack.version());
    List<ApprovalLine> lines = new ArrayList<>();
    for (LineRow line : loaded.lines()) {
      if (lines.size() < PayrollApprovalEvaluator.MAX_PAYSLIPS) {
        lines.add(PayrollPackageViews.line(line));
      }
    }
    return new ApprovalPreview(
        entity,
        run.id(),
        run.version(),
        calculation.id(),
        calculation.version(),
        StatutoryViews.month(run.periodStart()),
        run.currency().name(),
        result.status().name(),
        rulePack,
        run.policySequence(),
        result.blockers().stream().map(PayrollApprovalBlocker::name).toList(),
        result.warnings().stream().map(PayrollApprovalWarning::name).toList(),
        loaded.samePreparer(),
        loaded.expectedVersion(),
        loaded.existing() == null ? null : PayrollPackageViews.summary(loaded.existing()),
        result.payslipCount(),
        result.excludedCount(),
        result.totals() == null ? null : PayrollPackageViews.totals(result.totals()),
        lines,
        loaded.sourceRunDigest(),
        calculation.calculationDigest(),
        loaded.profileDigest(),
        loaded.rulePackDigest(),
        previewDigest,
        expiresAt);
  }

  // ------------------------------------------------------------------------------------------
  // Commit
  // ------------------------------------------------------------------------------------------

  /**
   * Approves a previewed payroll, or replays an identical approval.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param runId raw path value
   * @param calculationId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the evidence the preview returned and the three confirmations
   * @return the amount-free receipt
   */
  public IdempotentOperation.Result<ApprovalReceipt> approve(
      PayrollCaller caller,
      String legalEntityId,
      String runId,
      String calculationId,
      String idempotencyKey,
      CommitPayrollApprovalRequest body) {
    return plumbing.command(
        COMMIT,
        () -> {
          record Parsed(UUID entity, UUID run, UUID calculation, ApprovalCommit command) {}
          Parsed parsed =
              operations.validated(
                  COMMIT_SPEC,
                  () ->
                      new Parsed(
                          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND),
                          PayrollCommands.id(runId, ErrorCode.PAYROLL_RUN_NOT_FOUND),
                          PayrollCommands.id(
                              calculationId, ErrorCode.STATUTORY_CALCULATION_NOT_FOUND),
                          PayrollPackageCommands.approvalCommit(idempotencyKey, body)));
          return operations.execute(
              COMMIT_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed
                  .command()
                  .canonical(caller.tenant(), parsed.entity(), parsed.run(), parsed.calculation()),
              ApprovalReceipt.class,
              WRITE_TIMEOUT,
              () ->
                  approved(
                      caller,
                      parsed.entity(),
                      parsed.run(),
                      parsed.calculation(),
                      parsed.command()));
        });
  }

  private IdempotentOperation.Completed<ApprovalReceipt> approved(
      PayrollCaller caller, UUID entity, UUID runId, UUID calculationId, ApprovalCommit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, COMMIT_LIMIT);
    entities.require(tenant, entity, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
    Instant now = calendar.now();
    // Lock order of ADR 0035 section 8: the tenant statutory lock exclusive, the entity's run,
    // calculation and package locks, the run and calculation rows, then the chain rows (inside the
    // load). An unknown run or calculation is answered by the load, after the locks.
    statutory.lockStatutory(tenant, false);
    runs.lockRun(tenant, entity);
    statutory.lockCalculation(tenant, entity);
    packages.lockPackage(tenant, entity);
    packages.lockSources(tenant, entity, runId, calculationId);
    Loaded loaded = evidence.load(tenant, entity, runId, calculationId, caller.subject(), true);
    requireCommittable(tenant, entity, loaded, command, now);
    return write(caller, entity, loaded, now);
  }

  /** The contract's check order; nothing is written before every check passed. */
  private static void requireCommittable(
      TenantId tenant, UUID entity, Loaded loaded, ApprovalCommit command, Instant now) {
    Result result = loaded.result();
    Set<PayrollApprovalBlocker> blockers = result.blockers();
    // 3. A different package chain version.
    if (loaded.expectedVersion() != command.expectedVersion()) {
      throw new ApiException(ErrorCode.PAYROLL_PACKAGE_VERSION_CONFLICT, Map.of());
    }
    // 4. An APPROVED package of the month from other sources.
    if (blockers.contains(PayrollApprovalBlocker.ALREADY_APPROVED)) {
      throw new ApiException(ErrorCode.PAYROLL_PACKAGE_ALREADY_APPROVED, Map.of());
    }
    // 5. Any other blocker, except drift (answered as a stale preview at step 7) and the limit
    // (step 8).
    Set<PayrollApprovalBlocker> hard = EnumSet.noneOf(PayrollApprovalBlocker.class);
    for (PayrollApprovalBlocker blocker : blockers) {
      if (!blocker.drift() && blocker != PayrollApprovalBlocker.LIMIT_REACHED) {
        hard.add(blocker);
      }
    }
    if (!hard.isEmpty()) {
      throw new ApiException(ErrorCode.PAYROLL_PACKAGE_BLOCKED, Map.of());
    }
    // 6. An APPROVED package for exactly this run and calculation: nothing to record.
    if (exactApproved(loaded)) {
      throw new ApiException(ErrorCode.PAYROLL_APPROVAL_NO_CHANGE, Map.of());
    }
    // 7. Expiry, drift of a source and any difference of a digest.
    RunRow run = loaded.run();
    Instant expiresAt = command.expiresAt();
    String previewDigest =
        PackageDigests.approvalPreview(
            tenant.value(),
            entity,
            run.id(),
            run.version(),
            loaded.calculation().id(),
            loaded.calculation().version(),
            loaded.sourceRunDigest(),
            loaded.calculation().calculationDigest(),
            loaded.profileDigest(),
            loaded.rulePackDigest(),
            run.policyId(),
            run.policySequence(),
            loaded.samePreparer(),
            loaded.expectedVersion(),
            expiresAt);
    if (!PayrollApprovalEvaluator.drift(blockers).isEmpty()
        || !expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !loaded.sourceRunDigest().equals(command.sourceRunDigest())
        || !loaded.calculation().calculationDigest().equals(command.calculationDigest())
        || !loaded.profileDigest().equals(command.profileDigest())
        || !command.rulePackDigest().equals(loaded.rulePackDigest())
        || !previewDigest.equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.PAYROLL_APPROVAL_PREVIEW_STALE, Map.of());
    }
    // 8. Five thousand payslips or twenty-five package versions.
    if (blockers.contains(PayrollApprovalBlocker.LIMIT_REACHED)) {
      throw new ApiException(ErrorCode.PAYROLL_PACKAGE_LIMIT_REACHED, Map.of());
    }
  }

  private static boolean exactApproved(Loaded loaded) {
    for (PackageRow row : loaded.chain()) {
      if ("APPROVED".equals(row.status())
          && row.runId().equals(loaded.run().id())
          && row.calculationId().equals(loaded.calculation().id())) {
        return true;
      }
    }
    return false;
  }

  private IdempotentOperation.Completed<ApprovalReceipt> write(
      PayrollCaller caller, UUID entity, Loaded loaded, Instant now) {
    TenantId tenant = caller.tenant();
    RunRow run = loaded.run();
    CalculationRow calculation = loaded.calculation();
    PackRow pack = loaded.pack();
    Totals totals = loaded.result().totals();
    String period = StatutoryViews.month(run.periodStart());
    UUID packageId = UUID.randomUUID();
    int version = loaded.expectedVersion() + 1;

    List<NewSlip> slips = new ArrayList<>();
    List<PackageDigests.Slip> digests = new ArrayList<>();
    for (LineRow line : loaded.lines()) {
      List<PayslipSnapshot.Component> parts = new ArrayList<>();
      for (ComponentRow component : loaded.components().getOrDefault(line.id(), List.of())) {
        parts.add(
            new PayslipSnapshot.Component(
                component.seq(),
                component.componentId(),
                component.party(),
                component.category(),
                component.baseMinor(),
                component.amountMinor(),
                component.explanationCodes()));
      }
      PayslipSnapshot snapshot =
          PayslipSnapshot.of(
              packageId,
              entity,
              period,
              now,
              pack.code(),
              pack.version(),
              line.employeeId(),
              line.employeeNumber(),
              line.excluded(),
              line.grossMinor(),
              parts);
      if (snapshot.employeeDeductionsMinor() != line.deductionsMinor()
          || snapshot.employerContributionsMinor() != line.contributionsMinor()
          || snapshot.netMinor() != line.netMinor()) {
        throw new ApiException(ErrorCode.PAYROLL_PACKAGE_BLOCKED, Map.of());
      }
      String text = snapshot.canonicalText();
      slips.add(
          new NewSlip(
              UUID.randomUUID(),
              line.employeeId(),
              line.employeeNumber(),
              line.id(),
              line.excluded(),
              line.grossMinor(),
              line.deductionsMinor(),
              line.contributionsMinor(),
              line.netMinor(),
              line.costMinor(),
              text));
      digests.add(
          new PackageDigests.Slip(
              line.employeeId(), line.employeeNumber(), PackageDigests.snapshotDigest(text)));
    }

    String approvalDigest =
        PackageDigests.approval(
            new ApprovalBasis(
                tenant.value(),
                entity,
                period,
                run.id(),
                run.version(),
                run.inputDigest(),
                calculation.id(),
                calculation.version(),
                calculation.calculationDigest(),
                calculation.sourceRunDigest(),
                calculation.profileDigest(),
                calculation.rulePackDigest(),
                pack.id(),
                pack.rulesDigest(),
                run.policyId(),
                run.policySequence(),
                totals.grossMinor(),
                totals.deductionsMinor(),
                totals.contributionsMinor(),
                totals.netMinor(),
                totals.costMinor(),
                slips.size()),
            digests);
    NewPackage row =
        new NewPackage(
            packageId,
            entity,
            run.periodStart(),
            version,
            run.id(),
            run.version(),
            run.inputDigest(),
            calculation.id(),
            calculation.version(),
            calculation.calculationDigest(),
            calculation.sourceRunDigest(),
            calculation.profileDigest(),
            calculation.rulePackDigest(),
            pack.id(),
            pack.code(),
            pack.version(),
            pack.rulesDigest(),
            run.policyId(),
            run.policySequence(),
            slips.size(),
            totals.grossMinor(),
            totals.deductionsMinor(),
            totals.contributionsMinor(),
            totals.netMinor(),
            totals.costMinor(),
            loaded.samePreparer(),
            approvalDigest,
            now,
            caller.subject());
    try {
      packages.insertPackage(tenant, row);
      packages.insertSources(tenant, row);
      packages.insertSlips(tenant, row, slips);
      packages.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.approved(
        caller,
        new PayrollPackageRecorder.ApprovalRecorded(
            entity,
            packageId,
            version,
            period,
            slips.size(),
            run.version(),
            calculation.version(),
            loaded.samePreparer(),
            approvalDigest,
            now));
    LOG.atInfo()
        .addKeyValue("operation", COMMIT)
        .addKeyValue("outcome", "recorded")
        .addKeyValue("payslips", PayrollPackageViews.countBucket(slips.size()))
        .log("payroll_package_recorded");
    return new IdempotentOperation.Completed<>(
        new ApprovalReceipt(
            packageId,
            entity,
            run.id(),
            run.version(),
            calculation.id(),
            calculation.version(),
            pack.id(),
            pack.version(),
            period,
            PayrollPackageViews.CURRENCY,
            version,
            "APPROVED",
            pack.code().startsWith(PayrollApprovalEvidence.SYNTHETIC_PREFIX),
            slips.size(),
            loaded.samePreparer(),
            now),
        packageId,
        Outcome.CREATED);
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        PayrollPackageConstraintViolations.constraint(violated)
            .flatMap(PayrollPackageConstraintViolations::answer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
