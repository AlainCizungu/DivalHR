package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceBodies.CancelPolicy;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.CommitPolicy;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.PolicyCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.ChainVersion;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.PolicyEntry;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.PolicyPreview;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.PolicyReceipt;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.PolicyView;
import com.divalhr.core.payroll.application.SalaryAdvanceCommands.PolicyCancel;
import com.divalhr.core.payroll.application.SalaryAdvanceCommands.PolicyCommit;
import com.divalhr.core.payroll.application.SalaryAdvanceCommands.PolicyDraft;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRepository.PolicyRow;
import com.divalhr.core.payroll.internal.SalaryAdvanceConstraintViolations;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Employer participation policies of a legal entity for tenant administrators with privileged MFA
 * (MVP-120A, Issue #204, ADR 0041): the timeline, previews, commits and the one-time cancellation
 * of a version that has not taken effect. Authorization has run before this; tenant, actor and
 * scope never come from the request. A policy is an employer participation rule, never a credit
 * product: it holds no interest, fee, repayment term or credit criterion, is never enabled
 * automatically and carries no employee-specific exception. Versions are appended, never edited or
 * deleted.
 *
 * <p>A command runs in one idempotent transaction: the idempotency reservation, the chain lock of
 * the legal entity and currency, the checks, the audit record and the outbox event.
 */
@Service
public class SalaryAdvancePolicyService {

  /** Timeline read (interceptor operation). */
  public static final String READ = "salary-advance-policy.read";

  /** Preview (interceptor operation). */
  public static final String PREVIEW = "salary-advance-policy.preview";

  /** Commit (idempotency operation and interceptor operation). */
  public static final String COMMIT = "salary-advance-policy.commit";

  /** Cancellation (idempotency operation and interceptor operation). */
  public static final String CANCEL = "salary-advance-policy.cancel";

  /** Per-subject reads. */
  public static final String SUBJECT_READ_BUCKET = "salary-advance-policy-read";

  /** Per-subject previews. */
  public static final String SUBJECT_PREVIEW_BUCKET = "salary-advance-policy-preview";

  /** Per-subject commits and cancellations. */
  public static final String SUBJECT_WRITE_BUCKET = "salary-advance-policy-write";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.salary-advance-policy");
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final int MAX_VERSIONS = 25;
  private static final IdempotentOperation.Spec COMMIT_SPEC =
      new IdempotentOperation.Spec(COMMIT, "salary_advance_policy", "commit", "committed", 201);
  private static final IdempotentOperation.Spec CANCEL_SPEC =
      new IdempotentOperation.Spec(CANCEL, "salary_advance_policy", "cancel", "cancelled", 200);

  private final IdempotentOperation operations;
  private final JdbcSalaryAdvanceRepository repository;
  private final PayrollEntities entities;
  private final CompensationCalendar calendar;
  private final SalaryAdvanceRecorder recorder;
  private final SalaryAdvanceQuotas quotas;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param repository salary-advance storage
   * @param entities legal entity resolver
   * @param calendar business date and preview window
   * @param recorder audit and outbox
   * @param quotas hourly quotas
   * @param plumbing transactions, metrics and logs
   */
  public SalaryAdvancePolicyService(
      IdempotentOperation operations,
      JdbcSalaryAdvanceRepository repository,
      PayrollEntities entities,
      CompensationCalendar calendar,
      SalaryAdvanceRecorder recorder,
      SalaryAdvanceQuotas quotas,
      PayrollOperations plumbing) {
    this.operations = operations;
    this.repository = repository;
    this.entities = entities;
    this.calendar = calendar;
    this.recorder = recorder;
    this.quotas = quotas;
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
   * @param currency the raw {@code currency} filter, or {@code null}
   * @return the timeline, after its disclosure audit committed
   */
  public PolicyView read(PayrollCaller caller, String legalEntityId, String currency) {
    return plumbing.observed(
        LOG,
        "salary_advance_policy_read",
        READ,
        () -> {
          UUID entityId = SalaryAdvanceCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          FieldErrors errors = new FieldErrors();
          CompensationCurrency filter = SalaryAdvanceCommands.currencyFilter(currency, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                Instant now = calendar.now();
                quotas.requireCounted(
                    "policy-read", SalaryAdvanceQuotas.READS_PER_HOUR, tenant, caller.subject(), now);
                LocalDate today = calendar.today(tenant);
                List<PolicyRow> rows = repository.policies(tenant, entity);
                List<PolicyEntry> items =
                    rows.stream()
                        .filter(row -> filter == null || row.currency().equals(filter.name()))
                        .map(row -> SalaryAdvanceViews.policy(row, today))
                        .toList();
                recorder.disclosed(
                    caller,
                    SalaryAdvanceRecorder.POLICY_READ,
                    "salary-advance-policy",
                    entity,
                    "timeline",
                    true,
                    items.size(),
                    now);
                return new PolicyView(
                    entity, today, SalaryAdvanceResponses.NOTICE, versions(rows), items);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Validates a policy without writing: the chain before and after and the stable blockers.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param body the policy
   * @return the preview, after its disclosure audit committed
   */
  public PolicyPreview preview(PayrollCaller caller, String legalEntityId, PolicyCommand body) {
    return plumbing.observed(
        LOG,
        "salary_advance_policy_preview",
        PREVIEW,
        () -> {
          UUID entityId = SalaryAdvanceCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          PolicyDraft draft = SalaryAdvanceCommands.policyDraft(body);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                Instant now = calendar.now();
                quotas.requireCounted(
                    "policy-preview",
                    SalaryAdvanceQuotas.POLICY_PREVIEWS_PER_HOUR,
                    tenant,
                    caller.subject(),
                    now);
                LocalDate today = calendar.today(tenant);
                List<PolicyRow> chain = chain(repository.policies(tenant, entity), draft);
                int version = chainVersion(chain);
                List<String> blockers = blockers(chain, draft, today);
                PolicyRow candidate = candidate(entity, UUID.randomUUID(), version + 1, draft, now);
                List<PolicyEntry> before =
                    chain.stream().map(row -> SalaryAdvanceViews.policy(row, today)).toList();
                List<PolicyEntry> after = new ArrayList<>(before);
                after.add(SalaryAdvanceViews.policy(candidate, today));
                Instant expiresAt = CompensationCalendar.expiry(now);
                String digest =
                    blockers.isEmpty()
                        ? SalaryAdvanceDigests.policy(tenant, entity, draft, version, expiresAt)
                        : null;
                recorder.disclosed(
                    caller,
                    SalaryAdvanceRecorder.POLICY_PREVIEW,
                    "salary-advance-policy",
                    entity,
                    "preview",
                    true,
                    1,
                    now);
                return new PolicyPreview(
                    SalaryAdvanceViews.command(draft),
                    before,
                    blockers.isEmpty() ? after : before,
                    blockers,
                    version,
                    digest,
                    digest == null ? null : expiresAt,
                    SalaryAdvanceResponses.NOTICE);
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
      PayrollCaller caller, String legalEntityId, String idempotencyKey, CommitPolicy body) {
    return plumbing.command(
        COMMIT,
        () -> {
          record Parsed(UUID entity, PolicyCommit commit) {}
          Parsed parsed =
              operations.validated(
                  COMMIT_SPEC,
                  () ->
                      new Parsed(
                          SalaryAdvanceCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND),
                          SalaryAdvanceCommands.policyCommit(idempotencyKey, body)));
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
      PayrollCaller caller, UUID entityId, PolicyCommit command) {
    TenantId tenant = caller.tenant();
    repository.limits(tenant, TIMEOUT);
    UUID entity = entities.require(tenant, entityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
    Instant now = calendar.now();
    quotas.requirePolicyCommand(tenant, caller.subject(), now);
    PolicyDraft draft = command.draft();
    LocalDate today = calendar.today(tenant);
    repository.lockChain(tenant, entity, draft.currency().name());
    List<PolicyRow> chain = chain(repository.policies(tenant, entity), draft);
    int version = chainVersion(chain);
    if (version != command.expectedVersion()) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_VERSION_CONFLICT, Map.of());
    }
    List<String> blockers = blockers(chain, draft, today);
    if (!blockers.isEmpty()) {
      throw new ApiException(
          blockers.contains("PERIOD_OVERLAP")
              ? ErrorCode.SALARY_ADVANCE_POLICY_OVERLAP
              : ErrorCode.SALARY_ADVANCE_POLICY_INVALID,
          Map.of());
    }
    requireFresh(tenant, entity, draft, version, command, now);
    PolicyRow row = candidate(entity, UUID.randomUUID(), version + 1, draft, now);
    try {
      repository.insertPolicy(tenant, row, caller.subject());
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.policyCreated(
        caller, entity, row.id(), row.sequence(), row.currency(), now);
    LOG.atInfo()
        .addKeyValue("operation", COMMIT)
        .addKeyValue("outcome", "recorded")
        .log("salary_advance_policy_recorded");
    return new IdempotentOperation.Completed<>(
        new PolicyReceipt(
            row.id(),
            row.sequence(),
            row.currency(),
            row.effectiveFrom(),
            row.sequence(),
            SalaryAdvanceResponses.NOTICE),
        row.id(),
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Cancel
  // ------------------------------------------------------------------------------------------

  /**
   * Cancels a policy version that has not taken effect, or replays an identical cancellation.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param policyId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the version being cancelled and the confirmation
   * @return the cancelled version
   */
  public IdempotentOperation.Result<PolicyEntry> cancel(
      PayrollCaller caller,
      String legalEntityId,
      String policyId,
      String idempotencyKey,
      CancelPolicy body) {
    return plumbing.command(
        CANCEL,
        () -> {
          record Parsed(UUID entity, UUID policy, PolicyCancel cancel) {}
          Parsed parsed =
              operations.validated(
                  CANCEL_SPEC,
                  () ->
                      new Parsed(
                          SalaryAdvanceCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND),
                          SalaryAdvanceCommands.id(policyId, ErrorCode.SALARY_ADVANCE_POLICY_NOT_FOUND),
                          SalaryAdvanceCommands.policyCancel(idempotencyKey, body)));
          return operations.execute(
              CANCEL_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.cancel().canonical(caller.tenant(), parsed.entity(), parsed.policy()),
              PolicyEntry.class,
              TIMEOUT,
              () -> cancelled(caller, parsed.entity(), parsed.policy(), parsed.cancel()));
        });
  }

  private IdempotentOperation.Completed<PolicyEntry> cancelled(
      PayrollCaller caller, UUID entityId, UUID policyId, PolicyCancel command) {
    TenantId tenant = caller.tenant();
    repository.limits(tenant, TIMEOUT);
    UUID entity = entities.require(tenant, entityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
    Instant now = calendar.now();
    quotas.requirePolicyCommand(tenant, caller.subject(), now);
    PolicyRow found =
        repository
            .policy(tenant, entity, policyId)
            .orElseThrow(() -> new ApiException(ErrorCode.SALARY_ADVANCE_POLICY_NOT_FOUND, Map.of()));
    repository.lockChain(tenant, entity, found.currency());
    PolicyRow policy =
        repository
            .policy(tenant, entity, policyId)
            .orElseThrow(() -> new ApiException(ErrorCode.SALARY_ADVANCE_POLICY_NOT_FOUND, Map.of()));
    if (policy.sequence() != command.expectedSequence()) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_VERSION_CONFLICT, Map.of());
    }
    LocalDate today = calendar.today(tenant);
    if (policy.cancelledAt() != null || !policy.effectiveFrom().isAfter(today)) {
      // Only a version that has not taken effect, and has not been cancelled, can be cancelled.
      throw new ApiException(ErrorCode.SALARY_ADVANCE_TRANSITION_NOT_ALLOWED, Map.of());
    }
    int changed;
    try {
      changed = repository.cancelPolicy(tenant, entity, policyId, now, caller.subject());
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    if (changed != 1) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_TRANSITION_NOT_ALLOWED, Map.of());
    }
    recorder.policyCancelled(caller, entity, policyId, policy.sequence(), policy.currency(), now);
    PolicyRow after =
        repository
            .policy(tenant, entity, policyId)
            .orElseThrow(() -> new IllegalStateException("cancelled policy vanished"));
    return new IdempotentOperation.Completed<>(
        SalaryAdvanceViews.policy(after, today), policyId, Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Parts
  // ------------------------------------------------------------------------------------------

  private static List<ChainVersion> versions(List<PolicyRow> rows) {
    List<ChainVersion> versions = new ArrayList<>();
    for (CompensationCurrency currency : CompensationCurrency.values()) {
      int last =
          rows.stream()
              .filter(row -> row.currency().equals(currency.name()))
              .mapToInt(PolicyRow::sequence)
              .max()
              .orElse(0);
      versions.add(new ChainVersion(currency.name(), last));
    }
    return versions;
  }

  /** The versions of the draft's currency chain, in sequence order. */
  private static List<PolicyRow> chain(List<PolicyRow> all, PolicyDraft draft) {
    return all.stream()
        .filter(row -> row.currency().equals(draft.currency().name()))
        .sorted(Comparator.comparingInt(PolicyRow::sequence))
        .toList();
  }

  private static int chainVersion(List<PolicyRow> chain) {
    return chain.stream().mapToInt(PolicyRow::sequence).max().orElse(0);
  }

  private static PolicyRow candidate(
      UUID entity, UUID id, int sequence, PolicyDraft draft, Instant now) {
    return new PolicyRow(
        id,
        entity,
        draft.currency().name(),
        sequence,
        draft.code(),
        draft.effectiveFrom(),
        draft.effectiveTo(),
        draft.enabled(),
        draft.minTenureMonths(),
        draft.maxPercent(),
        draft.absoluteMaxMinor(),
        draft.minRequestMinor(),
        draft.maxOpenRequests(),
        draft.waitingDays(),
        draft.consentVersion(),
        draft.partnerCode(),
        now,
        null);
  }

  /** The stable blockers of appending a draft to its chain, in a fixed order. */
  static List<String> blockers(List<PolicyRow> chain, PolicyDraft draft, LocalDate today) {
    List<String> blockers = new ArrayList<>();
    if (chain.size() >= MAX_VERSIONS) {
      blockers.add("LIMIT_REACHED");
    }
    boolean datesInvalid =
        draft.effectiveFrom().isBefore(today)
            || (draft.effectiveTo() != null && draft.effectiveTo().isBefore(draft.effectiveFrom()));
    if (datesInvalid) {
      blockers.add("EFFECTIVE_DATE_INVALID");
    }
    if (draft.absoluteMaxMinor() != null && draft.absoluteMaxMinor() < draft.minRequestMinor()) {
      blockers.add("AMOUNT_BOUNDS_INVALID");
    }
    if (!SalaryAdvanceConsent.known(draft.consentVersion())) {
      blockers.add("CONSENT_VERSION_UNKNOWN");
    }
    if (!datesInvalid && overlaps(chain, draft)) {
      blockers.add("PERIOD_OVERLAP");
    }
    return blockers;
  }

  private static boolean overlaps(List<PolicyRow> chain, PolicyDraft draft) {
    for (PolicyRow row : chain) {
      if (row.cancelledAt() != null) {
        continue;
      }
      boolean startsBeforeOtherEnds =
          row.effectiveTo() == null || !draft.effectiveFrom().isAfter(row.effectiveTo());
      boolean otherStartsBeforeEnd =
          draft.effectiveTo() == null || !row.effectiveFrom().isAfter(draft.effectiveTo());
      if (startsBeforeOtherEnds && otherStartsBeforeEnd) {
        return true;
      }
    }
    return false;
  }

  /** The expiry and the digest, under the lock. */
  private static void requireFresh(
      TenantId tenant,
      UUID entity,
      PolicyDraft draft,
      int version,
      PolicyCommit command,
      Instant now) {
    Instant expiresAt = command.expiresAt();
    if (!expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !SalaryAdvanceDigests.policy(tenant, entity, draft, version, expiresAt)
            .equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_POLICY_PREVIEW_STALE, Map.of());
    }
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  private static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        SalaryAdvanceConstraintViolations.constraint(violated)
            .flatMap(SalaryAdvanceConstraintViolations::answer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
