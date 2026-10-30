package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPayrollPolicyRequest;
import com.divalhr.core.payroll.api.PayrollPolicyCommandRequest;
import com.divalhr.core.payroll.api.PayrollResponses.PolicyEntryView;
import com.divalhr.core.payroll.api.PayrollResponses.PolicyPreview;
import com.divalhr.core.payroll.api.PayrollResponses.PolicyReceipt;
import com.divalhr.core.payroll.api.PayrollResponses.PolicyView;
import com.divalhr.core.payroll.application.PayrollCommands.PolicyCommit;
import com.divalhr.core.payroll.application.PayrollCommands.PolicyDraft;
import com.divalhr.core.payroll.domain.PayrollPeriod;
import com.divalhr.core.payroll.domain.PayrollPolicy;
import com.divalhr.core.payroll.domain.PayrollPolicyTimeline;
import com.divalhr.core.payroll.domain.PayrollPolicyTimeline.Blocker;
import com.divalhr.core.payroll.domain.PayrollPolicyTimeline.Plan;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.PayrollRunConstraintViolations;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Effective-dated monthly payroll policies of a legal entity for tenant administrators with
 * privileged MFA (MVP-094A, Issue #177, ADR 0033): the timeline, previews and commits. Authorization
 * (role {@code tenant-admin}, exact MFA, active membership) has run before this; tenant, actor and
 * scope never come from the request. The policies are employer policies, not legal-compliance
 * claims, and are appended, never edited or deleted.
 *
 * <p>A commit runs in one idempotent transaction in the lock order of ADR 0033: the idempotency
 * reservation, the legal entity's run lock (5e), then the checks, the audit record and the outbox
 * event. Under the lock it re-reads the timeline and compares the version, the expiry and the
 * digest before it writes.
 */
@Service
public class PayrollPolicyService {

  /** Read of the timeline (interceptor operation). */
  public static final String READ = "payroll-policy.read";

  /** Preview (interceptor operation; disclosed as a policy read). */
  public static final String PREVIEW = "payroll-policy.preview";

  /** Commit (idempotency operation and interceptor operation). */
  public static final String COMMIT = "payroll-policy.commit";

  /** Per-subject reads. */
  public static final String SUBJECT_READ_BUCKET = "payroll-policy-read";

  /** Per-subject previews. */
  public static final String SUBJECT_PREVIEW_BUCKET = "payroll-policy-preview";

  /** Per-subject commits. */
  public static final String SUBJECT_COMMIT_BUCKET = "payroll-policy-write";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payroll-policy");
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec COMMIT_SPEC =
      new IdempotentOperation.Spec(COMMIT, "payroll_policy", "commit", "committed", 201);

  private final IdempotentOperation operations;
  private final JdbcPayrollRunRepository runs;
  private final PayrollEntities entities;
  private final CompensationCalendar calendar;
  private final PayrollRecorder recorder;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param runs payroll storage
   * @param entities legal entity resolver
   * @param calendar business date and preview window
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   */
  public PayrollPolicyService(
      IdempotentOperation operations,
      JdbcPayrollRunRepository runs,
      PayrollEntities entities,
      CompensationCalendar calendar,
      PayrollRecorder recorder,
      PayrollOperations plumbing) {
    this.operations = operations;
    this.runs = runs;
    this.entities = entities;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
  }

  // ------------------------------------------------------------------------------------------
  // Read
  // ------------------------------------------------------------------------------------------

  /**
   * The policy timeline of a legal entity.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @return the timeline, after its disclosure audit committed
   */
  public PolicyView read(PayrollCaller caller, String legalEntityId) {
    return plumbing.observed(
        LOG,
        "payroll_policy_read",
        READ,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                Instant now = calendar.now();
                LocalDate today = calendar.today(tenant);
                List<PayrollPolicy> policies = runs.policies(tenant, entity);
                List<PolicyEntryView> views = PayrollViews.entries(policies);
                PolicyEntryView current =
                    PayrollPolicyTimeline.inForce(policies, today)
                        .flatMap(
                            policy ->
                                views.stream()
                                    .filter(view -> view.policyId().equals(policy.id()))
                                    .findFirst())
                        .orElse(null);
                recorder.disclosed(
                    caller,
                    PayrollRecorder.POLICY_READ,
                    "payroll-policy",
                    entity,
                    entity,
                    "timeline",
                    true,
                    policies.size(),
                    now);
                return new PolicyView(
                    entity, PayrollPolicyTimeline.version(policies), today, current, views);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Validates a policy without writing: the timeline before and after and the stable blockers.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param body the policy
   * @return the preview, after its disclosure audit committed
   */
  public PolicyPreview preview(
      PayrollCaller caller, String legalEntityId, PayrollPolicyCommandRequest body) {
    return plumbing.observed(
        LOG,
        "payroll_policy_preview",
        PREVIEW,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          PolicyDraft draft = PayrollCommands.policyDraft(body);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                Instant now = calendar.now();
                requireDate(draft.effectiveFrom(), calendar.today(tenant));
                List<PayrollPolicy> existing = runs.policies(tenant, entity);
                int version = PayrollPolicyTimeline.version(existing);
                PayrollPolicy candidate =
                    new PayrollPolicy(
                        previewId(tenant, entity, version + 1, draft),
                        version + 1,
                        draft.effectiveFrom(),
                        draft.payDay(),
                        draft.method());
                Plan plan = PayrollPolicyTimeline.plan(existing, candidate);
                Instant expiresAt = CompensationCalendar.expiry(now);
                String digest =
                    plan.committable()
                        ? PayrollDigests.policy(tenant, entity, draft, version, expiresAt)
                        : null;
                recorder.disclosed(
                    caller,
                    PayrollRecorder.POLICY_READ,
                    "payroll-policy",
                    entity,
                    entity,
                    "preview",
                    true,
                    1,
                    now);
                return new PolicyPreview(
                    draft.effectiveFrom(),
                    PayrollViews.payDay(draft.payDay()),
                    draft.method().name(),
                    PayrollViews.entries(existing),
                    PayrollViews.entries(plan.after()),
                    plan.blockers().stream().map(Blocker::name).toList(),
                    version,
                    digest,
                    digest == null ? null : expiresAt);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Commit
  // ------------------------------------------------------------------------------------------

  /**
   * Records a previewed policy, or replays an identical commit.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the previewed policy with its digest
   * @return the receipt
   */
  public IdempotentOperation.Result<PolicyReceipt> commit(
      PayrollCaller caller,
      String legalEntityId,
      String idempotencyKey,
      CommitPayrollPolicyRequest body) {
    return plumbing.command(
        COMMIT,
        () -> {
          record Parsed(UUID entity, PolicyCommit commit) {}
          Parsed parsed =
              operations.validated(
                  COMMIT_SPEC,
                  () ->
                      new Parsed(
                          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND),
                          PayrollCommands.policyCommit(idempotencyKey, body)));
          return operations.execute(
              COMMIT_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.commit().canonical(caller.tenant(), parsed.entity()),
              PolicyReceipt.class,
              TIMEOUT,
              () -> committed(caller, parsed.entity(), parsed.commit()));
        });
  }

  private IdempotentOperation.Completed<PolicyReceipt> committed(
      PayrollCaller caller, UUID entity, PolicyCommit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, TIMEOUT);
    entities.require(tenant, entity, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
    Instant now = calendar.now();
    requireDate(command.draft().effectiveFrom(), calendar.today(tenant));
    // Lock order 5e: one policy or run command per legal entity at a time.
    runs.lockRun(tenant, entity);
    List<PayrollPolicy> existing = runs.policies(tenant, entity);
    int version = PayrollPolicyTimeline.version(existing);
    if (version != command.expectedVersion()) {
      throw new ApiException(ErrorCode.PAYROLL_POLICY_CONFLICT, Map.of());
    }
    PolicyDraft draft = command.draft();
    PayrollPolicy candidate =
        new PayrollPolicy(
            UUID.randomUUID(), version + 1, draft.effectiveFrom(), draft.payDay(), draft.method());
    Plan plan = PayrollPolicyTimeline.plan(existing, candidate);
    if (!plan.committable()) {
      throw new ApiException(
          plan.blockers().contains(Blocker.LIMIT_REACHED)
              ? ErrorCode.PAYROLL_RUN_LIMIT_REACHED
              : ErrorCode.PAYROLL_POLICY_CONFLICT,
          Map.of());
    }
    requireFresh(tenant, entity, draft, version, command, now);
    try {
      runs.insertPolicy(tenant, entity, candidate, now, caller.subject());
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.policyChanged(
        caller,
        new PayrollRecorder.PolicyRecorded(entity, candidate.id(), candidate.sequence(), now));
    LOG.atInfo()
        .addKeyValue("operation", COMMIT)
        .addKeyValue("outcome", "recorded")
        .log("payroll_policy_recorded");
    return new IdempotentOperation.Completed<>(
        new PolicyReceipt(
            candidate.id(), candidate.sequence(), candidate.effectiveFrom(), candidate.sequence()),
        candidate.id(),
        Outcome.CREATED);
  }

  /** The expiry and the digest, under the lock. */
  private static void requireFresh(
      TenantId tenant, UUID entity, PolicyDraft draft, int version, PolicyCommit command, Instant now) {
    Instant expiresAt = command.expiresAt();
    if (!expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !PayrollDigests.policy(tenant, entity, draft, version, expiresAt)
            .equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.PAYROLL_RUN_PREVIEW_STALE, Map.of());
    }
  }

  private static void requireDate(LocalDate effectiveFrom, LocalDate today) {
    if (!PayrollPeriod.isPolicyDate(effectiveFrom, today)) {
      throw new ApiException(ErrorCode.PAYROLL_PERIOD_INVALID, Map.of());
    }
  }

  private static UUID previewId(TenantId tenant, UUID entity, int sequence, PolicyDraft draft) {
    String seed = "payroll-policy-preview:" + tenant + ":" + entity + ":" + sequence;
    return UUID.nameUUIDFromBytes(
        (seed + ":" + draft.effectiveFrom()).getBytes(StandardCharsets.UTF_8));
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  private static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        PayrollRunConstraintViolations.constraint(violated)
            .flatMap(PayrollRunConstraintViolations::answer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
