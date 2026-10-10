package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.leave.api.AmendLeaveRequest;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A validated amendment of the caller's own pending leave request (MVP-041D, D41DE-3): the
 * replacement's submission fields, validated exactly as a new request's ({@link
 * LeaveRequestCommand}), and the amendment reason, validated exactly as a decision's ({@link
 * LeaveReasonGrammar}). Deterministic: it depends on the request alone, so it runs before the
 * idempotency decision; the business date, ownership, state and eligibility rules run inside the
 * transaction. Problems are {@code {field, constraint}} pairs only: nothing submitted is echoed.
 *
 * @param requestId the original request (from the path)
 * @param replacement the replacement's policy, dates and amount
 * @param reasonLocale {@code en} or {@code fr}
 * @param reason the reason in its stored form
 */
public record LeaveAmendmentCommand(
    UUID requestId, LeaveRequestCommand replacement, String reasonLocale, String reason) {

  /**
   * Identifiers only: no date, amount or reason reaches a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveAmendmentCommand[requestId=" + requestId + "]";
  }

  /**
   * Validates the amendment.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param requestId path value
   * @param request body
   * @return the command
   * @throws com.divalhr.core.platform.error.ApiException {@code 404 LEAVE_REQUEST_NOT_FOUND} for a
   *     path that is not a request ID; {@code VALIDATION_FAILED} listing every body problem
   */
  static LeaveAmendmentCommand from(
      String idempotencyKey, String requestId, AmendLeaveRequest request) {
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
    LeaveRequestCommand replacement =
        LeaveRequestCommand.fields(
            errors,
            request.getPolicyId(),
            request.getStartDate(),
            request.getEndDate(),
            request.getAmount());
    String locale = LeaveDecisionCommand.reasonLocale(errors, request.getReasonLocale());
    String reason = LeaveDecisionCommand.reason(errors, request.getReason());
    errors.throwIfAny();
    return new LeaveAmendmentCommand(id, replacement, locale, reason);
  }

  /**
   * The normalized command and its scope, fingerprinted for idempotency.
   *
   * @param tenant verified tenant
   * @return key-sorted canonical form
   */
  Map<String, Object> canonical(TenantId tenant) {
    Map<String, Object> canonical = new TreeMap<>(replacement.canonical(tenant));
    canonical.put("requestId", requestId.toString());
    canonical.put("reasonLocale", reasonLocale);
    canonical.put("reason", reason);
    return canonical;
  }
}
