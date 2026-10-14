package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.api.ApproveTimesheetRequest;
import com.divalhr.core.people.schedule.api.ReturnTimesheetRequest;
import com.divalhr.core.people.schedule.api.SubmitTimesheetRequest;
import com.divalhr.core.people.schedule.domain.TimesheetApproval;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MVP-070B/C request validation (T70BC-2, T70BC-6, T70BC-7): the reviewed revision, generation
 * version, cycle and submission, the confirmation and the return reason (trimmed; 10 to 500
 * characters; no control or format character, markup or link, as V33's {@code
 * people.timesheet_return_reason_valid}, which also refuses what this refuses). A malformed
 * timesheet is the same {@code 404 TIMESHEET_TARGET_NOT_FOUND} as an unknown one, after every shape
 * check. The reason enters a fingerprint, audit or digest only as its own digest.
 */
public final class TimesheetApprovalCommand {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern LINK = Pattern.compile("(?i)(https?://|ftp://|www\\.|mailto:)");

  private TimesheetApprovalCommand() {}

  /**
   * A validated submission.
   *
   * @param timesheetId timesheet
   * @param revision reviewed revision
   * @param generationVersion reviewed generation version
   */
  public record Submit(UUID timesheetId, int revision, int generationVersion) {

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("timesheetId", timesheetId.toString());
      canonical.put("revision", revision);
      canonical.put("generationVersion", generationVersion);
      canonical.put("confirm", true);
      return canonical;
    }

    @Override
    public String toString() {
      return "TimesheetApprovalCommand.Submit[revision=" + revision + "]";
    }
  }

  /**
   * A validated decision.
   *
   * @param timesheetId timesheet
   * @param approve {@code true} to approve, {@code false} to return
   * @param revision reviewed revision
   * @param generationVersion reviewed generation version
   * @param cycle reviewed cycle
   * @param submissionActionId reviewed submission
   * @param reason the trimmed return reason, or {@code null} for an approval
   */
  public record Decide(
      UUID timesheetId,
      boolean approve,
      int revision,
      int generationVersion,
      int cycle,
      UUID submissionActionId,
      String reason) {

    /**
     * The reason's digest: the only form of it that enters a fingerprint or an audit digest.
     *
     * @return hex SHA-256, or the empty string for an approval
     */
    public String reasonDigest() {
      return reason == null
          ? ""
          : Fingerprints.sha256("DIVALHR-TIMESHEET-RETURN-REASON\n" + reason);
    }

    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("timesheetId", timesheetId.toString());
      canonical.put("decision", approve ? "APPROVE" : "RETURN");
      canonical.put("revision", revision);
      canonical.put("generationVersion", generationVersion);
      canonical.put("cycle", cycle);
      canonical.put("submissionActionId", submissionActionId.toString());
      canonical.put("reasonDigest", reasonDigest());
      canonical.put("confirm", true);
      return canonical;
    }

    /**
     * The decision only: never an identifier or the reason in a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "TimesheetApprovalCommand.Decide[" + (approve ? "APPROVE" : "RETURN") + "]";
    }
  }

  /**
   * The queue's optional filters.
   *
   * @param siteId site, or {@code null}
   * @param weekStart site-local Monday, or {@code null}
   */
  public record Filters(UUID siteId, LocalDate weekStart) {}

  /**
   * Validates a submission.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param timesheetId path value
   * @param request body
   * @return the submission
   */
  static Submit submit(String idempotencyKey, String timesheetId, SubmitTimesheetRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    body(request, errors);
    int revision = positive("revision", request.getRevision(), errors);
    int version = positive("generationVersion", request.getGenerationVersion(), errors);
    confirm(request.getConfirm(), errors);
    errors.throwIfAny();
    return new Submit(TimesheetCommand.id(timesheetId), revision, version);
  }

  /**
   * Validates an approval.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param timesheetId path value
   * @param request body
   * @return the decision
   */
  static Decide approve(
      String idempotencyKey, String timesheetId, ApproveTimesheetRequest request) {
    return decide(idempotencyKey, timesheetId, request, true);
  }

  /**
   * Validates a return.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param timesheetId path value
   * @param request body
   * @return the decision
   */
  static Decide giveBack(
      String idempotencyKey, String timesheetId, ReturnTimesheetRequest request) {
    return decide(idempotencyKey, timesheetId, request, false);
  }

  private static Decide decide(
      String idempotencyKey, String timesheetId, ApproveTimesheetRequest request, boolean approve) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    body(request, errors);
    int revision = positive("revision", request.getRevision(), errors);
    int version = positive("generationVersion", request.getGenerationVersion(), errors);
    int cycle = positive("cycle", request.getCycle(), errors);
    UUID submission = null;
    Object raw = request.getSubmissionActionId();
    if (raw == null) {
      errors.add("submissionActionId", Constraint.REQUIRED);
    } else if (!(raw instanceof String text) || !UUID_SHAPE.matcher(text).matches()) {
      errors.add("submissionActionId", Constraint.FORMAT);
    } else {
      submission = UUID.fromString(text.toLowerCase(Locale.ROOT));
    }
    String reason = approve ? null : reason(((ReturnTimesheetRequest) request).getReason(), errors);
    confirm(request.getConfirm(), errors);
    errors.throwIfAny();
    return new Decide(
        TimesheetCommand.id(timesheetId), approve, revision, version, cycle, submission, reason);
  }

  /**
   * Validates the queue's filters.
   *
   * @param siteId raw site, or {@code null}
   * @param weekStart raw Monday, or {@code null}
   * @param errors collector
   * @return the filters
   */
  static Filters filters(String siteId, String weekStart, FieldErrors errors) {
    UUID site = null;
    if (siteId != null) {
      if (UUID_SHAPE.matcher(siteId).matches()) {
        site = UUID.fromString(siteId.toLowerCase(Locale.ROOT));
      } else {
        errors.add("siteId", Constraint.FORMAT);
      }
    }
    LocalDate week = null;
    if (weekStart != null) {
      if (!DATE.matcher(weekStart).matches()) {
        errors.add("weekStart", Constraint.FORMAT);
      } else {
        try {
          week = LocalDate.parse(weekStart);
          if (week.getDayOfWeek() != DayOfWeek.MONDAY) {
            errors.add("weekStart", Constraint.RANGE);
            week = null;
          }
        } catch (DateTimeParseException invalid) {
          errors.add("weekStart", Constraint.FORMAT);
        }
      }
    }
    return new Filters(site, week);
  }

  /**
   * The trimmed return reason, or {@code null} after recording its problem.
   *
   * @param raw raw value
   * @param errors collector
   * @return the reason
   */
  static String reason(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("reason", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add("reason", Constraint.FORMAT);
      return null;
    }
    String trimmed = text.strip();
    if (trimmed.isEmpty()) {
      errors.add("reason", Constraint.REQUIRED);
      return null;
    }
    int length = trimmed.codePointCount(0, trimmed.length());
    if (length < TimesheetApproval.REASON_MIN || length > TimesheetApproval.REASON_MAX) {
      errors.add("reason", Constraint.LENGTH);
      return null;
    }
    boolean clean =
        trimmed
            .codePoints()
            .noneMatch(
                c ->
                    Character.getType(c) == Character.CONTROL
                        || Character.getType(c) == Character.FORMAT
                        || Character.getType(c) == Character.SURROGATE
                        || Character.getType(c) == Character.UNASSIGNED
                        || c == '<'
                        || c == '>');
    if (!clean || LINK.matcher(trimmed).find()) {
      errors.add("reason", Constraint.FORMAT);
      return null;
    }
    return trimmed;
  }

  // ------------------------------------------------------------------------------------------

  private static void body(Object request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    if (request instanceof com.divalhr.core.people.api.StrictRequest strict
        && !strict.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
  }

  private static int positive(String field, Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
    } else if (!(raw instanceof Integer value)) {
      errors.add(field, Constraint.FORMAT);
    } else if (value < 1) {
      errors.add(field, Constraint.RANGE);
    } else {
      return value;
    }
    return 0;
  }

  private static void confirm(Object raw, FieldErrors errors) {
    if (!Boolean.TRUE.equals(raw)) {
      errors.add("confirm", Constraint.REQUIRED);
    }
  }

  private static void key(String idempotencyKey, FieldErrors errors) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }
}
