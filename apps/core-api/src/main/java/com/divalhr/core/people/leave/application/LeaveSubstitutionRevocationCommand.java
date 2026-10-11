package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.leave.api.RevokeLeaveApprovalSubstitutionRequest;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A validated revocation of a leave approval substitute appointment (MVP-041G, D41G-3), parsed with
 * the shared reason parser and grammar. Deterministic; the appointment's existence and state are
 * checked inside the transaction under the tenant's manager-graph lock.
 *
 * @param substitutionId the appointment (from the path)
 * @param reasonLocale {@code en} or {@code fr}
 * @param reason the reason in its stored form
 */
public record LeaveSubstitutionRevocationCommand(
    UUID substitutionId, String reasonLocale, String reason) {

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  /**
   * Identifiers only: the reason never reaches a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveSubstitutionRevocationCommand[substitutionId=" + substitutionId + "]";
  }

  /**
   * Validates the revocation.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param substitutionId path value
   * @param request body
   * @return the command
   * @throws ApiException {@code 404 LEAVE_APPROVAL_SUBSTITUTION_NOT_FOUND} for a path that is not
   *     an ID; {@code VALIDATION_FAILED} listing every body problem
   */
  static LeaveSubstitutionRevocationCommand from(
      String idempotencyKey,
      String substitutionId,
      RevokeLeaveApprovalSubstitutionRequest request) {
    UUID id = substitutionId(substitutionId);
    FieldErrors errors = new FieldErrors();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!request.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    String locale = LeaveDecisionCommand.reasonLocale(errors, request.getReasonLocale());
    String reason = LeaveDecisionCommand.reason(errors, request.getReason());
    errors.throwIfAny();
    return new LeaveSubstitutionRevocationCommand(id, locale, reason);
  }

  /**
   * The normalized command and its scope, fingerprinted for idempotency.
   *
   * @param tenant verified tenant
   * @return key-sorted canonical form
   */
  Map<String, Object> canonical(TenantId tenant) {
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", tenant.toString());
    canonical.put("substitutionId", substitutionId.toString());
    canonical.put("reasonLocale", reasonLocale);
    canonical.put("reason", reason);
    return canonical;
  }

  /**
   * The appointment id of a path, or the one not-found answer.
   *
   * @param raw path value
   * @return the id
   */
  static UUID substitutionId(String raw) {
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      throw notFound();
    }
    return UUID.fromString(raw.toLowerCase(Locale.ROOT));
  }

  /**
   * The one answer for an appointment the caller may not see: unknown and another tenant's alike.
   *
   * @return {@code 404 LEAVE_APPROVAL_SUBSTITUTION_NOT_FOUND} with empty params
   */
  static ApiException notFound() {
    return new ApiException(ErrorCode.LEAVE_APPROVAL_SUBSTITUTION_NOT_FOUND, Map.of());
  }
}
