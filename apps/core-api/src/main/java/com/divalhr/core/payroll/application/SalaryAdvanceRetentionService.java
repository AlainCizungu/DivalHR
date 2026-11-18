package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceRetentionBodies.ConfigureRetention;
import com.divalhr.core.payroll.api.SalaryAdvanceRetentionBodies.ConfirmPurge;
import com.divalhr.core.payroll.api.SalaryAdvanceRetentionResponses.Policy;
import com.divalhr.core.payroll.api.SalaryAdvanceRetentionResponses.PurgePreview;
import com.divalhr.core.payroll.api.SalaryAdvanceRetentionResponses.PurgeResult;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRetentionRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRetentionRepository.Eligible;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRetentionRepository.PolicyRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRetentionRepository.PreviewRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceRetentionRepository.RecordRow;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Evidence retention of the controlled partner handoff (MVP-120C, ADR 0042 amendment D120C-8 to
 * D120C-12). Only the tenant administrator with exact MFA reaches it. Retention is disabled until
 * an organization policy of 30 to 3,650 days exists; the policy only applies to handoffs that
 * become terminal afterwards. A purge is never automatic: a preview stores the exact eligible set
 * and a digest, and one explicit confirmation deletes only those previewed delivery diagnostics,
 * under the database guard. Requests, confirmations, consent receipts, statuses, history, audit and
 * every proof that an ambiguous delivery was not resent are never deleted.
 */
@Service
public class SalaryAdvanceRetentionService {

  /** Policy read (interceptor operation). */
  public static final String POLICY_READ = "salary-advance-retention.read";

  /** Policy configuration (interceptor operation). */
  public static final String POLICY_CONFIGURE = "salary-advance-retention.configure";

  /** Purge preview (interceptor operation). */
  public static final String PURGE_PREVIEW = "salary-advance-retention.purge-preview";

  /** Purge confirmation (interceptor operation). */
  public static final String PURGE_CONFIRM = "salary-advance-retention.purge-confirm";

  /** Lowest accepted retention period. */
  public static final int MIN_DAYS = 30;

  /** Highest accepted retention period. */
  public static final int MAX_DAYS = 3650;

  /** How long a preview can be confirmed. */
  static final Duration PREVIEW_TTL = Duration.ofMinutes(15);

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.salary-advance-retention");
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final JdbcSalaryAdvanceRetentionRepository retention;
  private final SalaryAdvanceHandoffRecorder recorder;
  private final CompensationCalendar calendar;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param retention retention storage
   * @param recorder audit
   * @param calendar business time
   * @param plumbing transactions, metrics and logs
   */
  public SalaryAdvanceRetentionService(
      JdbcSalaryAdvanceRetentionRepository retention,
      SalaryAdvanceHandoffRecorder recorder,
      CompensationCalendar calendar,
      PayrollOperations plumbing) {
    this.retention = retention;
    this.recorder = recorder;
    this.calendar = calendar;
    this.plumbing = plumbing;
  }

  /**
   * The organization retention policy.
   *
   * @param caller verified tenant administrator
   * @return the policy (not configured means retention is disabled)
   */
  public Policy policy(PayrollCaller caller) {
    return plumbing.observed(
        LOG,
        "salary_advance_retention_read",
        POLICY_READ,
        () ->
            plumbing.inReadTransaction(
                caller.tenant(),
                TIMEOUT,
                () -> {
                  Instant now = calendar.now();
                  Policy view = view(retention.latestPolicy(caller.tenant()).orElse(null));
                  recorder.retention(
                      caller, POLICY_READ, caller.tenant().value(), Map.of("view", "policy"), now);
                  return view;
                }));
  }

  /**
   * Configures the retention period as a new immutable policy version (prospective only). The same
   * period as the latest version changes nothing.
   *
   * @param caller verified tenant administrator
   * @param body the requested period
   * @return the policy after the change
   */
  public Policy configure(PayrollCaller caller, ConfigureRetention body) {
    return plumbing.observed(
        LOG,
        "salary_advance_retention_configured",
        POLICY_CONFIGURE,
        () -> {
          int days = days(body);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                Instant now = calendar.now();
                retention.lockPolicies(tenant);
                PolicyRow latest = retention.latestPolicy(tenant).orElse(null);
                if (latest != null && latest.retentionDays() == days) {
                  return view(latest);
                }
                int version = latest == null ? 1 : latest.version() + 1;
                retention.insertPolicy(tenant, version, days, caller.subject(), now);
                recorder.retention(
                    caller,
                    POLICY_CONFIGURE,
                    tenant.value(),
                    Map.of("policyVersion", version, "retentionDays", days),
                    now);
                return view(new PolicyRow(version, days, now));
              });
        });
  }

  /**
   * Previews the delivery diagnostics a purge would delete and stores the exact set.
   *
   * @param caller verified tenant administrator
   * @return the preview (counts and the digest only)
   */
  public PurgePreview preview(PayrollCaller caller) {
    return plumbing.observed(
        LOG,
        "salary_advance_purge_previewed",
        PURGE_PREVIEW,
        () ->
            plumbing.inReadTransaction(
                caller.tenant(),
                TIMEOUT,
                () -> {
                  TenantId tenant = caller.tenant();
                  Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
                  PolicyRow policy =
                      retention
                          .latestPolicy(tenant)
                          .orElseThrow(
                              () ->
                                  new ApiException(
                                      ErrorCode.SALARY_ADVANCE_RETENTION_POLICY_MISSING,
                                      Map.of()));
                  List<Eligible> eligible =
                      retention.eligible(
                          tenant, now, JdbcSalaryAdvanceRetentionRepository.MAX_PREVIEW_HANDOFFS);
                  int attempts = eligible.stream().mapToInt(Eligible::attemptCount).sum();
                  PreviewRow row =
                      new PreviewRow(
                          UUID.randomUUID(),
                          policy.version(),
                          digest(policy.version(), eligible),
                          eligible.size(),
                          attempts,
                          now,
                          now.plus(PREVIEW_TTL));
                  retention.insertPreview(tenant, row, caller.subject(), eligible);
                  recorder.retention(
                      caller,
                      PURGE_PREVIEW,
                      row.id(),
                      Map.of(
                          "policyVersion", row.policyVersion(),
                          "handoffCount", row.handoffCount(),
                          "attemptCount", row.attemptCount()),
                      now);
                  return new PurgePreview(
                      row.id(),
                      row.policyVersion(),
                      row.handoffCount(),
                      row.attemptCount(),
                      row.digest(),
                      row.createdAt(),
                      row.expiresAt());
                }));
  }

  /**
   * Confirms exactly one preview: re-verifies every previewed handoff under the preview lock and
   * deletes only those diagnostics. A stale preview deletes nothing; a repeated confirmation
   * replays the stored result.
   *
   * @param caller verified tenant administrator
   * @param previewId raw path value
   * @param body the digest the administrator saw and the confirmation
   * @return the result
   */
  public PurgeResult confirm(PayrollCaller caller, String previewId, ConfirmPurge body) {
    return plumbing.observed(
        LOG,
        "salary_advance_purge_confirmed",
        PURGE_CONFIRM,
        () -> {
          UUID id =
              SalaryAdvanceCommands.id(previewId, ErrorCode.SALARY_ADVANCE_PURGE_PREVIEW_NOT_FOUND);
          String digest = confirmation(body);
          return plumbing.inReadTransaction(
              caller.tenant(), TIMEOUT, () -> confirmed(caller, id, digest));
        });
  }

  private PurgeResult confirmed(PayrollCaller caller, UUID id, String digest) {
    TenantId tenant = caller.tenant();
    Instant now = calendar.now().truncatedTo(ChronoUnit.MICROS);
    PreviewRow preview =
        retention
            .lockPreview(tenant, id)
            .orElseThrow(
                () -> new ApiException(ErrorCode.SALARY_ADVANCE_PURGE_PREVIEW_NOT_FOUND, Map.of()));
    RecordRow done = retention.record(tenant, id).orElse(null);
    if (done != null) {
      if (!preview.digest().equals(digest)) {
        throw new ApiException(ErrorCode.SALARY_ADVANCE_PURGE_ALREADY_COMPLETED, Map.of());
      }
      return new PurgeResult(id, done.handoffCount(), done.attemptCount(), done.confirmedAt(), true);
    }
    if (!preview.digest().equals(digest) || !now.isBefore(preview.expiresAt())) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_PURGE_PREVIEW_STALE, Map.of());
    }
    PolicyRow policy = retention.latestPolicy(tenant).orElse(null);
    List<Eligible> previewed = retention.items(tenant, id);
    if (policy == null
        || policy.version() != preview.policyVersion()
        || !previewed.equals(
            retention.eligible(
                tenant, now, JdbcSalaryAdvanceRetentionRepository.MAX_PREVIEW_HANDOFFS))
        || !digest(preview.policyVersion(), previewed).equals(preview.digest())) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_PURGE_PREVIEW_STALE, Map.of());
    }
    int attempts = previewed.stream().mapToInt(Eligible::attemptCount).sum();
    int deleted;
    try {
      retention.insertRecord(tenant, id, caller.subject(), previewed.size(), attempts, now);
      deleted =
          retention.deleteAttempts(tenant, id, previewed.stream().map(Eligible::handoffId).toList());
    } catch (DataIntegrityViolationException refused) {
      // The database guard or a concurrent confirmation: nothing is deleted (the transaction rolls
      // back).
      throw new ApiException(ErrorCode.SALARY_ADVANCE_PURGE_PREVIEW_STALE, Map.of());
    }
    if (deleted != attempts) {
      // Never delete more or less than previewed.
      throw new ApiException(ErrorCode.SALARY_ADVANCE_PURGE_PREVIEW_STALE, Map.of());
    }
    recorder.retention(
        caller,
        PURGE_CONFIRM,
        id,
        Map.of("handoffCount", previewed.size(), "attemptCount", attempts),
        now);
    return new PurgeResult(id, previewed.size(), attempts, now, false);
  }

  private static Policy view(PolicyRow row) {
    return row == null
        ? new Policy(false, null, null, null, MIN_DAYS, MAX_DAYS)
        : new Policy(
            true, row.version(), row.retentionDays(), row.effectiveAt(), MIN_DAYS, MAX_DAYS);
  }

  private static int days(ConfigureRetention body) {
    FieldErrors errors = new FieldErrors();
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Object raw = body.getRetentionDays();
    if (raw == null) {
      errors.add("retentionDays", Constraint.REQUIRED);
    } else if (!(raw instanceof Integer || raw instanceof Long)) {
      errors.add("retentionDays", Constraint.FORMAT);
    }
    errors.throwIfAny();
    long value = ((Number) raw).longValue();
    if (value < MIN_DAYS || value > MAX_DAYS) {
      throw new ApiException(ErrorCode.SALARY_ADVANCE_RETENTION_PERIOD_INVALID, Map.of());
    }
    return (int) value;
  }

  private static String confirmation(ConfirmPurge body) {
    FieldErrors errors = new FieldErrors();
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    String digest = SalaryAdvanceCommands.digest(body.getPreviewDigest(), "previewDigest", errors);
    SalaryAdvanceCommands.confirmed(body.getConfirmed(), "confirmed", errors);
    errors.throwIfAny();
    return digest;
  }

  /** The digest over the exact sorted eligible set and the policy version. */
  static String digest(int policyVersion, List<Eligible> eligible) {
    StringBuilder canonical =
        new StringBuilder("DIVALHR-SALARY-ADVANCE-PURGE\nversion=1\npolicy=")
            .append(policyVersion)
            .append('\n');
    for (Eligible item : eligible) {
      canonical.append(item.handoffId()).append(':').append(item.attemptCount()).append('\n');
    }
    return Fingerprints.sha256(canonical.toString());
  }
}
