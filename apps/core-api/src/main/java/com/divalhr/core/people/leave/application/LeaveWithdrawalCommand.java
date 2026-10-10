package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.leave.api.WithdrawLeaveRequest;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A validated withdrawal of the caller's own approved leave (MVP-041F, D41F-3), parsed exactly as a
 * cancellation (the shared reason parser and grammar). Deterministic: it depends on the request
 * alone, so it runs before the idempotency decision; the ownership and state rules run inside the
 * transaction. Problems are {@code {field, constraint}} pairs only: the reason is never echoed. The
 * reason follows the decision-reason grammar version 1 ({@link LeaveReasonGrammar}), validated
 * exactly as a decision's.
 *
 * @param requestId the request (from the path)
 * @param reasonLocale {@code en} or {@code fr}
 * @param reason the reason in its stored form
 */
public record LeaveWithdrawalCommand(UUID requestId, String reasonLocale, String reason) {

  /**
   * Identifiers only: the reason never reaches a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveWithdrawalCommand[requestId=" + requestId + "]";
  }

  /**
   * Validates the withdrawal.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param requestId path value
   * @param request body
   * @return the command
   * @throws com.divalhr.core.platform.error.ApiException {@code 404 LEAVE_REQUEST_NOT_FOUND} for a
   *     path that is not a request ID; {@code VALIDATION_FAILED} listing every body problem
   */
  static LeaveWithdrawalCommand from(
      String idempotencyKey, String requestId, WithdrawLeaveRequest request) {
    UUID id = LeaveDecisionCommand.requestId(requestId);
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
    return new LeaveWithdrawalCommand(id, locale, reason);
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
    canonical.put("requestId", requestId.toString());
    canonical.put("reasonLocale", reasonLocale);
    canonical.put("reason", reason);
    return canonical;
  }
}
