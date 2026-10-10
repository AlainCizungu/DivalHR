package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.leave.api.DecideLeaveRequest;
import com.divalhr.core.people.leave.domain.LeaveRequestState;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A validated decision on a leave request (MVP-041B, D41B-4). Deterministic: it depends on the
 * request alone, so it runs before the idempotency decision; every state, route and relationship
 * rule runs inside the transaction. Problems are {@code {field, constraint}} pairs only: the reason
 * is never echoed.
 *
 * @param requestId the request (from the path)
 * @param outcome {@code APPROVED} or {@code REJECTED}
 * @param reasonLocale {@code en} or {@code fr}
 * @param reason the reason in its stored form ({@link LeaveReasonGrammar}, version 1)
 */
public record LeaveDecisionCommand(
    UUID requestId, LeaveRequestState outcome, String reasonLocale, String reason) {

  private static final Set<String> LOCALES = Set.of("en", "fr");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  /**
   * Identifiers and outcome only: the reason never reaches a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveDecisionCommand[requestId=" + requestId + ", outcome=" + outcome + "]";
  }

  /**
   * Validates the decision.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param requestId path value
   * @param request body
   * @return the command
   * @throws ApiException {@code 404 LEAVE_REQUEST_NOT_FOUND} for a path that is not a request ID;
   *     {@code VALIDATION_FAILED} listing every body problem
   */
  static LeaveDecisionCommand from(
      String idempotencyKey, String requestId, DecideLeaveRequest request) {
    UUID id = requestId(requestId);
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
    LeaveRequestState outcome = null;
    Object rawDecision = request.getDecision();
    if (rawDecision == null) {
      errors.add("decision", Constraint.REQUIRED);
    } else if (!(rawDecision instanceof String text)
        || !(text.equals("APPROVED") || text.equals("REJECTED"))) {
      errors.add("decision", Constraint.FORMAT);
    } else {
      outcome = LeaveRequestState.valueOf(text);
    }
    String locale = reasonLocale(errors, request.getReasonLocale());
    String reason = reason(errors, request.getReason());
    errors.throwIfAny();
    return new LeaveDecisionCommand(id, outcome, locale, reason);
  }

  /**
   * The reason in its stored form, or {@code null} after a problem: required, a string, no
   * forbidden code point ({@code FORMAT}), then 2 to 500 code points ({@code LENGTH}), exactly as
   * {@link LeaveReasonGrammar} and the database decide. Shared by decisions and cancellations
   * (MVP-041C): one grammar, one validation.
   *
   * @param errors collected problems
   * @param raw submitted value
   * @return the stored form, or {@code null}
   */
  static String reason(FieldErrors errors, Object raw) {
    if (raw == null) {
      errors.add("reason", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add("reason", Constraint.FORMAT);
      return null;
    }
    String stored = LeaveReasonGrammar.normalize(text);
    if (stored.isEmpty()) {
      errors.add("reason", Constraint.REQUIRED);
      return null;
    }
    if (!LeaveReasonGrammar.allowedCodePoints(stored)) {
      errors.add("reason", Constraint.FORMAT);
      return null;
    }
    if (!LeaveReasonGrammar.isValid(stored)) {
      errors.add("reason", Constraint.LENGTH);
      return null;
    }
    return stored;
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
    canonical.put("decision", outcome.name());
    canonical.put("reasonLocale", reasonLocale);
    canonical.put("reason", reason);
    return canonical;
  }

  /**
   * The reason language, or {@code null} after a problem: required, exactly {@code en} or {@code
   * fr}. Shared by decisions and cancellations (MVP-041C).
   *
   * @param errors collected problems
   * @param raw submitted value
   * @return the locale, or {@code null}
   */
  static String reasonLocale(FieldErrors errors, Object raw) {
    if (raw == null) {
      errors.add("reasonLocale", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !LOCALES.contains(text)) {
      errors.add("reasonLocale", Constraint.FORMAT);
      return null;
    }
    return text;
  }

  /**
   * The request id of a path, or {@code 404 LEAVE_REQUEST_NOT_FOUND} for anything else.
   *
   * @param requestId path value
   * @return the request id
   */
  static UUID requestId(String requestId) {
    if (requestId == null || !UUID_SHAPE.matcher(requestId).matches()) {
      throw notFound();
    }
    return UUID.fromString(requestId.toLowerCase(Locale.ROOT));
  }

  /**
   * The one answer for a request the caller may not see or decide.
   *
   * @return {@code 404 LEAVE_REQUEST_NOT_FOUND} with empty params
   */
  static ApiException notFound() {
    return new ApiException(ErrorCode.LEAVE_REQUEST_NOT_FOUND, Map.of());
  }
}
