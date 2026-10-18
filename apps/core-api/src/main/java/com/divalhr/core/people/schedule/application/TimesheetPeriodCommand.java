package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.api.LockTimesheetPeriodRequest;
import com.divalhr.core.people.schedule.api.TimesheetPeriodScopeRequest;
import com.divalhr.core.people.schedule.api.TimesheetPeriodUnlockPreviewRequest;
import com.divalhr.core.people.schedule.api.UnlockTimesheetPeriodRequest;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.UnlockReason;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MVP-071 request validation (L71-3, L71-4, L71-6): the legal entity, its site and the site-local
 * Monday; for a lock the reviewed generation and lock versions, the preview digest and the
 * confirmation; for an unlock the closed reason, the explanation (the attendance-correction
 * grammar: trimmed, 10 to 500 characters, no markup, link, control or format character), the
 * current lock version and action, the preview digest and the confirmation. A malformed legal
 * entity or site is the same {@code 404 TIMESHEET_TARGET_NOT_FOUND} as an unknown one, after every
 * shape check. Nothing about tenant, readiness, lock state or boundaries is ever taken from the
 * request.
 */
public final class TimesheetPeriodCommand {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

  private TimesheetPeriodCommand() {}

  /**
   * A legal entity, one of its sites and a site-local Monday.
   *
   * @param legalEntityId legal entity
   * @param siteId site
   * @param weekStart the Monday
   */
  public record Target(UUID legalEntityId, UUID siteId, LocalDate weekStart) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("legalEntityId", legalEntityId.toString());
      canonical.put("siteId", siteId.toString());
      canonical.put("weekStart", weekStart.toString());
      return canonical;
    }
  }

  /**
   * A validated lock.
   *
   * @param target the scope
   * @param generationVersion the reviewed generation version
   * @param lockVersion the reviewed lock version
   * @param previewDigest the reviewed preview digest
   */
  public record Lock(Target target, int generationVersion, int lockVersion, String previewDigest) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = target.canonical(tenant);
      canonical.put("generationVersion", generationVersion);
      canonical.put("lockVersion", lockVersion);
      canonical.put("previewDigest", previewDigest);
      canonical.put("confirm", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "TimesheetPeriodCommand.Lock[week=" + target.weekStart() + "]";
    }
  }

  /**
   * A validated unlock reason: the closed code and the trimmed explanation.
   *
   * @param target the scope
   * @param reason the closed reason
   * @param explanation the trimmed explanation (Restricted)
   */
  public record Reasoned(Target target, UnlockReason reason, String explanation) {

    /**
     * The explanation's digest: the only form of it that enters a preview or audit digest.
     *
     * @return lowercase hex SHA-256
     */
    public String explanationDigest() {
      return TimesheetPeriod.explanationDigest(explanation);
    }

    /** The scope and the reason only. */
    @Override
    public String toString() {
      return "TimesheetPeriodCommand.Reasoned[week=" + target.weekStart() + ", " + reason + "]";
    }
  }

  /**
   * A validated unlock.
   *
   * @param reasoned the scope, reason and explanation
   * @param lockVersion the reviewed current lock version
   * @param lockActionId the reviewed current LOCKED action
   * @param previewDigest the reviewed preview digest
   */
  public record Unlock(
      Reasoned reasoned, int lockVersion, UUID lockActionId, String previewDigest) {

    /** The canonical payload (the explanation only as its digest). */
    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = reasoned.target().canonical(tenant);
      canonical.put("reasonCode", reasoned.reason().name());
      canonical.put("explanationDigest", reasoned.explanationDigest());
      canonical.put("lockVersion", lockVersion);
      canonical.put("lockActionId", lockActionId.toString());
      canonical.put("previewDigest", previewDigest);
      canonical.put("confirm", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "TimesheetPeriodCommand.Unlock[" + reasoned + "]";
    }
  }

  /**
   * Validates a lock preview's scope.
   *
   * @param request body
   * @return the target
   */
  static Target target(TimesheetPeriodScopeRequest request) {
    FieldErrors errors = new FieldErrors();
    Target target = target(request, errors);
    errors.throwIfAny();
    return found(target);
  }

  /**
   * Validates a lock.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the lock
   */
  static Lock lock(String idempotencyKey, LockTimesheetPeriodRequest request) {
    FieldErrors errors = new FieldErrors();
    TimesheetCommand.key(idempotencyKey, errors);
    Target target = target(request, errors);
    Integer generationVersion =
        version(request.getGenerationVersion(), "generationVersion", 1, errors);
    Integer lockVersion = version(request.getLockVersion(), "lockVersion", 0, errors);
    String digest = digest(request.getPreviewDigest(), errors);
    TimesheetCommand.confirm(request.getConfirm(), errors);
    errors.throwIfAny();
    return new Lock(found(target), generationVersion, lockVersion, digest);
  }

  /**
   * Validates an unlock preview.
   *
   * @param request body
   * @return the scope, reason and explanation
   */
  static Reasoned reasoned(TimesheetPeriodUnlockPreviewRequest request) {
    FieldErrors errors = new FieldErrors();
    Reasoned reasoned = reasoned(request, errors);
    errors.throwIfAny();
    return new Reasoned(found(reasoned.target()), reasoned.reason(), reasoned.explanation());
  }

  /**
   * Validates an unlock.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the unlock
   */
  static Unlock unlock(String idempotencyKey, UnlockTimesheetPeriodRequest request) {
    FieldErrors errors = new FieldErrors();
    TimesheetCommand.key(idempotencyKey, errors);
    Reasoned reasoned = reasoned(request, errors);
    Integer lockVersion = version(request.getLockVersion(), "lockVersion", 1, errors);
    UUID lockAction = null;
    Object raw = request.getLockActionId();
    if (raw == null) {
      errors.add("lockActionId", Constraint.REQUIRED);
    } else if (!(raw instanceof String text) || !UUID_SHAPE.matcher(text).matches()) {
      errors.add("lockActionId", Constraint.FORMAT);
    } else {
      lockAction = UUID.fromString(text.toLowerCase(Locale.ROOT));
    }
    String digest = digest(request.getPreviewDigest(), errors);
    TimesheetCommand.confirm(request.getConfirm(), errors);
    errors.throwIfAny();
    return new Unlock(
        new Reasoned(found(reasoned.target()), reasoned.reason(), reasoned.explanation()),
        lockVersion,
        lockAction,
        digest);
  }

  // ------------------------------------------------------------------------------------------

  private static Target target(TimesheetPeriodScopeRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    TimesheetCommand.Target period = TimesheetCommand.target(request, errors);
    UUID legalEntity = null;
    if (request.getLegalEntityId() == null) {
      errors.add("legalEntityId", Constraint.REQUIRED);
    } else if (request.getLegalEntityId() instanceof String text
        && UUID_SHAPE.matcher(text).matches()) {
      legalEntity = UUID.fromString(text.toLowerCase(Locale.ROOT));
    }
    // A malformed legal entity or site is decided after every shape check (the same 404).
    return new Target(legalEntity, period.siteId(), period.weekStart());
  }

  private static Reasoned reasoned(
      TimesheetPeriodUnlockPreviewRequest request, FieldErrors errors) {
    Target target = target(request, errors);
    UnlockReason reason = null;
    Object raw = request.getReasonCode();
    if (raw == null) {
      errors.add("reasonCode", Constraint.REQUIRED);
    } else if (raw instanceof String text) {
      try {
        reason = UnlockReason.valueOf(text);
      } catch (IllegalArgumentException unknown) {
        errors.add("reasonCode", Constraint.FORMAT);
      }
    } else {
      errors.add("reasonCode", Constraint.FORMAT);
    }
    String explanation = AttendanceCorrectionCommand.explanation(request.getExplanation(), errors);
    return new Reasoned(target, reason, explanation);
  }

  private static Target found(Target target) {
    if (target.legalEntityId() == null || target.siteId() == null) {
      throw TimesheetCommand.notFound();
    }
    return target;
  }

  private static Integer version(Object raw, String field, int minimum, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof Integer value)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    if (value < minimum) {
      errors.add(field, Constraint.RANGE);
      return null;
    }
    return value;
  }

  private static String digest(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("previewDigest", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !DIGEST.matcher(text).matches()) {
      errors.add("previewDigest", Constraint.FORMAT);
      return null;
    }
    return text;
  }
}
