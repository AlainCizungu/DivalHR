package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.api.AttendanceCorrectionRequest;
import com.divalhr.core.people.schedule.api.CommitAttendanceCorrectionRequest;
import com.divalhr.core.people.schedule.api.CommitMissingAttendanceCorrectionRequest;
import com.divalhr.core.people.schedule.api.MissingAttendanceCorrectionRequest;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Operation;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Reason;
import com.divalhr.core.people.schedule.domain.AttendanceCorrection.Times;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic validation of an attendance correction (MVP-062, C62-2, C62-3, C62-5): shapes only,
 * never a lookup. Errors name fields and fixed constraints, never a submitted value. A malformed
 * target is the same {@code 404 ATTENDANCE_CORRECTION_TARGET_NOT_FOUND} as an unknown one, after
 * the body's own shape checks.
 *
 * <p>Instants are RFC 3339 with an explicit offset and at most six fractional digits (the stored
 * microsecond precision), parsed exactly as MVP-061A's device time: no clamping, reordering or
 * conversion beyond the instant itself. The explanation is trimmed of surrounding whitespace, then
 * must hold 10 to 500 characters (Unicode code points) with no control or format character, no
 * markup ({@code <} or {@code >}) and no link.
 */
public final class AttendanceCorrectionCommand {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern LINK = Pattern.compile("(?i)(https?://|ftp://|www\\.|mailto:)");

  private AttendanceCorrectionCommand() {}

  /**
   * A validated proposal.
   *
   * @param operation the operation
   * @param recordId the record (REPLACE_TIMES), or {@code null}
   * @param slotId the slot (CREATE_MISSING_RECORD), or {@code null}
   * @param employeeId the reviewed effective assignee (CREATE_MISSING_RECORD), or {@code null}
   * @param after the proposed values
   * @param reason the reason code
   * @param explanation the trimmed explanation
   */
  public record Proposal(
      Operation operation,
      UUID recordId,
      UUID slotId,
      UUID employeeId,
      Times after,
      Reason reason,
      String explanation) {

    /**
     * The explanation's digest: the only form of it that enters a preview or audit digest.
     *
     * @return hex SHA-256
     */
    public String explanationDigest() {
      return Fingerprints.sha256("DIVALHR-ATTENDANCE-CORRECTION-EXPLANATION\n" + explanation);
    }

    /**
     * The normalized proposal and its scope, fingerprinted for idempotency (with the commit
     * fields). The explanation enters only as its digest.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("operation", operation.name());
      canonical.put("attendanceRecordId", recordId == null ? "" : recordId.toString());
      canonical.put("slotId", slotId == null ? "" : slotId.toString());
      canonical.put("employeeId", employeeId == null ? "" : employeeId.toString());
      canonical.put("checkedInAt", after.checkedInAt().toString());
      canonical.put(
          "checkedOutAt", after.checkedOutAt() == null ? "" : after.checkedOutAt().toString());
      canonical.put("reasonCode", reason.name());
      canonical.put("explanationDigest", explanationDigest());
      return canonical;
    }

    /**
     * The operation only: never an identifier, instant or the explanation in a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "AttendanceCorrectionCommand.Proposal[" + operation + ", " + reason + "]";
    }
  }

  /**
   * A validated commit: the proposal, the previewed target version and the preview digest.
   *
   * @param proposal the proposal
   * @param targetVersion the previewed target version
   * @param previewDigest the preview's digest
   */
  public record Commit(Proposal proposal, int targetVersion, String previewDigest) {

    /**
     * The normalized commit and its scope, fingerprinted for idempotency.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = proposal.canonical(tenant);
      canonical.put("targetVersion", targetVersion);
      canonical.put("previewDigest", previewDigest);
      canonical.put("confirm", true);
      return canonical;
    }

    /**
     * The operation only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "AttendanceCorrectionCommand.Commit[" + proposal.operation() + "]";
    }
  }

  /**
   * Validates a REPLACE_TIMES preview.
   *
   * @param recordId path value
   * @param request body
   * @return the proposal
   */
  static Proposal replace(String recordId, AttendanceCorrectionRequest request) {
    FieldErrors errors = new FieldErrors();
    Proposal proposal = replace(recordId, request, errors);
    errors.throwIfAny();
    return target(proposal);
  }

  /**
   * Validates a CREATE_MISSING_RECORD preview.
   *
   * @param request body
   * @return the proposal
   */
  static Proposal missing(MissingAttendanceCorrectionRequest request) {
    FieldErrors errors = new FieldErrors();
    Proposal proposal = missing(request, errors);
    errors.throwIfAny();
    return target(proposal);
  }

  /**
   * Validates a REPLACE_TIMES commit.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param recordId path value
   * @param request body
   * @return the commit
   */
  static Commit commitReplace(
      String idempotencyKey, String recordId, CommitAttendanceCorrectionRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    Proposal proposal = replace(recordId, request, errors);
    Integer version = version(request == null ? null : request.getTargetVersion(), errors);
    String digest = digest(request, errors);
    errors.throwIfAny();
    return new Commit(target(proposal), version, digest);
  }

  /**
   * Validates a CREATE_MISSING_RECORD commit.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the commit
   */
  static Commit commitMissing(
      String idempotencyKey, CommitMissingAttendanceCorrectionRequest request) {
    FieldErrors errors = new FieldErrors();
    key(idempotencyKey, errors);
    Proposal proposal = missing(request, errors);
    Integer version = version(request == null ? null : request.getTargetVersion(), errors);
    String digest = digest(request, errors);
    errors.throwIfAny();
    return new Commit(target(proposal), version, digest);
  }

  /**
   * A record path value, or the one safe 404.
   *
   * @param raw path value
   * @return the record ID
   */
  static UUID recordId(String raw) {
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      throw new ApiException(ErrorCode.ATTENDANCE_CORRECTION_TARGET_NOT_FOUND, Map.of());
    }
    return UUID.fromString(raw.toLowerCase(Locale.ROOT));
  }

  // ------------------------------------------------------------------------------------------

  private static Proposal replace(
      String recordId, AttendanceCorrectionRequest request, FieldErrors errors) {
    Values values = values(request, errors);
    UUID record =
        recordId != null && UUID_SHAPE.matcher(recordId).matches()
            ? UUID.fromString(recordId.toLowerCase(Locale.ROOT))
            : null;
    return values == null
        ? null
        : new Proposal(
            Operation.REPLACE_TIMES,
            record,
            null,
            null,
            values.times(),
            values.reason(),
            values.explanation());
  }

  private static Proposal missing(MissingAttendanceCorrectionRequest request, FieldErrors errors) {
    Values values = values(request, errors);
    if (request != null) {
      if (request.getSlotId() == null) {
        errors.add("slotId", Constraint.REQUIRED);
      }
      if (request.getEmployeeId() == null) {
        errors.add("employeeId", Constraint.REQUIRED);
      }
    }
    if (values == null) {
      return null;
    }
    return new Proposal(
        Operation.CREATE_MISSING_RECORD,
        null,
        uuid(request.getSlotId()),
        uuid(request.getEmployeeId()),
        values.times(),
        values.reason(),
        values.explanation());
  }

  /** After every shape check: a malformed target is the same 404 as an unknown one. */
  private static Proposal target(Proposal proposal) {
    boolean malformed =
        proposal.operation() == Operation.REPLACE_TIMES
            ? proposal.recordId() == null
            : proposal.slotId() == null || proposal.employeeId() == null;
    if (malformed) {
      throw new ApiException(ErrorCode.ATTENDANCE_CORRECTION_TARGET_NOT_FOUND, Map.of());
    }
    return proposal;
  }

  private record Values(Times times, Reason reason, String explanation) {}

  private static Values values(AttendanceCorrectionRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    Instant in = null;
    Object rawIn = request.getCheckedInAt();
    if (rawIn == null) {
      errors.add("checkedInAt", Constraint.REQUIRED);
    } else {
      OffsetDateTime parsed = OfflineAttendanceCommand.occurred(rawIn);
      if (parsed == null) {
        errors.add("checkedInAt", Constraint.FORMAT);
      } else {
        in = parsed.toInstant();
      }
    }
    Instant out = null;
    Object rawOut = request.getCheckedOutAt();
    boolean outValid = true;
    if (rawOut != null) {
      OffsetDateTime parsed = OfflineAttendanceCommand.occurred(rawOut);
      if (parsed == null) {
        errors.add("checkedOutAt", Constraint.FORMAT);
        outValid = false;
      } else {
        out = parsed.toInstant();
      }
    }
    if (in != null && out != null && !out.isAfter(in)) {
      // Strictly after the corrected check-in; never reordered.
      errors.add("checkedOutAt", Constraint.RANGE);
      outValid = false;
    }
    Reason reason = null;
    Object rawReason = request.getReasonCode();
    if (rawReason == null) {
      errors.add("reasonCode", Constraint.REQUIRED);
    } else {
      reason = reason(rawReason);
      if (reason == null) {
        errors.add("reasonCode", Constraint.FORMAT);
      }
    }
    String explanation = explanation(request.getExplanation(), errors);
    if (in == null || !outValid || reason == null || explanation == null) {
      return null;
    }
    return new Values(new Times(in, out), reason, explanation);
  }

  private static Reason reason(Object raw) {
    if (raw instanceof String text) {
      for (Reason reason : Reason.values()) {
        if (reason.name().equals(text)) {
          return reason;
        }
      }
    }
    return null;
  }

  /**
   * The normalized explanation, or {@code null} after recording its problem.
   *
   * @param raw raw value
   * @param errors collector
   * @return the trimmed explanation
   */
  static String explanation(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("explanation", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add("explanation", Constraint.FORMAT);
      return null;
    }
    String trimmed = text.strip();
    if (trimmed.isEmpty()) {
      errors.add("explanation", Constraint.REQUIRED);
      return null;
    }
    int length = trimmed.codePointCount(0, trimmed.length());
    if (length < AttendanceCorrection.EXPLANATION_MIN
        || length > AttendanceCorrection.EXPLANATION_MAX) {
      errors.add("explanation", Constraint.LENGTH);
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
      errors.add("explanation", Constraint.FORMAT);
      return null;
    }
    return trimmed;
  }

  private static UUID uuid(Object raw) {
    return raw instanceof String text && UUID_SHAPE.matcher(text).matches()
        ? UUID.fromString(text.toLowerCase(Locale.ROOT))
        : null;
  }

  private static void key(String idempotencyKey, FieldErrors errors) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }

  private static Integer version(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("targetVersion", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof Integer value)) {
      errors.add(
          "targetVersion",
          raw instanceof Long || raw instanceof java.math.BigInteger
              ? Constraint.RANGE
              : Constraint.FORMAT);
      return null;
    }
    if (value < 0) {
      errors.add("targetVersion", Constraint.RANGE);
      return null;
    }
    return value;
  }

  private static String digest(Object request, FieldErrors errors) {
    Object raw;
    Object confirm;
    if (request instanceof CommitAttendanceCorrectionRequest commit) {
      raw = commit.getPreviewDigest();
      confirm = commit.getConfirm();
    } else if (request instanceof CommitMissingAttendanceCorrectionRequest commit) {
      raw = commit.getPreviewDigest();
      confirm = commit.getConfirm();
    } else {
      return null;
    }
    String digest = null;
    if (raw == null) {
      errors.add("previewDigest", Constraint.REQUIRED);
    } else if (!(raw instanceof String text) || !DIGEST.matcher(text).matches()) {
      errors.add("previewDigest", Constraint.FORMAT);
    } else {
      digest = text;
    }
    if (!Boolean.TRUE.equals(confirm)) {
      errors.add("confirm", Constraint.REQUIRED);
    }
    return digest;
  }
}
