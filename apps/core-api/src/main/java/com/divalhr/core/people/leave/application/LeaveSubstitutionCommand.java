package com.divalhr.core.people.leave.application;

import com.divalhr.core.people.leave.api.CreateLeaveApprovalSubstitutionRequest;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A validated appointment of a leave approval substitute (MVP-041G, D41G-3). Deterministic: it
 * depends on the request alone, so it runs before the idempotency decision; every employee,
 * employment, reporting-line and overlap rule runs inside the transaction under the tenant's
 * manager-graph lock. Problems are {@code {field, constraint}} pairs only: no ID, date or reason is
 * echoed.
 *
 * @param managerEmployeeId the delegating manager
 * @param substituteEmployeeId the substitute
 * @param effectiveFrom first day (inclusive)
 * @param effectiveTo last day (inclusive), not before the first
 * @param reasonLocale {@code en} or {@code fr}
 * @param reason the reason in its stored form ({@link LeaveReasonGrammar}, version 1)
 */
public record LeaveSubstitutionCommand(
    UUID managerEmployeeId,
    UUID substituteEmployeeId,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String reasonLocale,
    String reason) {

  /**
   * Identifiers only: dates and the reason never reach a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveSubstitutionCommand[managerEmployeeId="
        + managerEmployeeId
        + ", substituteEmployeeId="
        + substituteEmployeeId
        + "]";
  }

  /**
   * Validates the appointment.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the command
   * @throws com.divalhr.core.platform.error.ApiException {@code VALIDATION_FAILED} listing every
   *     problem
   */
  static LeaveSubstitutionCommand from(
      String idempotencyKey, CreateLeaveApprovalSubstitutionRequest request) {
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
    UUID manager =
        LeaveRequestCommand.uuid(errors, "managerEmployeeId", request.getManagerEmployeeId());
    UUID substitute =
        LeaveRequestCommand.uuid(errors, "substituteEmployeeId", request.getSubstituteEmployeeId());
    LocalDate from = LeaveRequestCommand.date(errors, "effectiveFrom", request.getEffectiveFrom());
    LocalDate to = LeaveRequestCommand.date(errors, "effectiveTo", request.getEffectiveTo());
    if (from != null && to != null && to.isBefore(from)) {
      errors.add("effectiveTo", Constraint.RANGE);
    }
    String locale = LeaveDecisionCommand.reasonLocale(errors, request.getReasonLocale());
    String reason = LeaveDecisionCommand.reason(errors, request.getReason());
    errors.throwIfAny();
    return new LeaveSubstitutionCommand(manager, substitute, from, to, locale, reason);
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
    canonical.put("managerEmployeeId", managerEmployeeId.toString());
    canonical.put("substituteEmployeeId", substituteEmployeeId.toString());
    canonical.put("effectiveFrom", effectiveFrom.toString());
    canonical.put("effectiveTo", effectiveTo.toString());
    canonical.put("reasonLocale", reasonLocale);
    canonical.put("reason", reason);
    return canonical;
  }
}
