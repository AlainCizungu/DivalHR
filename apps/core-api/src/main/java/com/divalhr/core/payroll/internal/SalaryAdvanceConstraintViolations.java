package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.error.ErrorCode;
import java.util.Map;
import java.util.Optional;

/**
 * The named V52 constraints the payroll module maps to stable business answers (MVP-120A). The
 * service checks the policy, the payroll reference, the limits and the lifecycle under the locks,
 * so a guard that still fires is a lost race (or a defect). Nothing from the database message is
 * ever exposed or logged.
 */
public final class SalaryAdvanceConstraintViolations {

  private static final Map<String, ErrorCode> ANSWERS =
      Map.ofEntries(
          Map.entry(
              "payroll_salary_advance_policy_period_overlap",
              ErrorCode.SALARY_ADVANCE_POLICY_OVERLAP),
          Map.entry(
              "payroll_salary_advance_policy_sequence",
              ErrorCode.SALARY_ADVANCE_POLICY_PREVIEW_STALE),
          Map.entry(
              "payroll_salary_advance_policy_sequence_unique",
              ErrorCode.SALARY_ADVANCE_POLICY_PREVIEW_STALE),
          Map.entry(
              "payroll_salary_advance_policy_sequence_range",
              ErrorCode.SALARY_ADVANCE_POLICY_INVALID),
          Map.entry(
              "payroll_salary_advance_policy_immutable",
              ErrorCode.SALARY_ADVANCE_TRANSITION_NOT_ALLOWED),
          Map.entry("payroll_salary_advance_request_policy", ErrorCode.SALARY_ADVANCE_NOT_ELIGIBLE),
          Map.entry("payroll_salary_advance_request_owner", ErrorCode.SALARY_ADVANCE_NOT_ELIGIBLE),
          Map.entry(
              "payroll_salary_advance_request_employment", ErrorCode.SALARY_ADVANCE_NOT_ELIGIBLE),
          Map.entry(
              "payroll_salary_advance_decision_transition",
              ErrorCode.SALARY_ADVANCE_TRANSITION_NOT_ALLOWED),
          Map.entry(
              "payroll_salary_advance_request_placement", ErrorCode.SALARY_ADVANCE_NOT_ELIGIBLE),
          Map.entry(
              "payroll_salary_advance_decision_self_review",
              ErrorCode.SALARY_ADVANCE_SELF_REVIEW_FORBIDDEN),
          Map.entry("payroll_salary_advance_request_tenure", ErrorCode.SALARY_ADVANCE_NOT_ELIGIBLE),
          Map.entry(
              "payroll_salary_advance_request_rolling_limit",
              ErrorCode.SALARY_ADVANCE_NOT_ELIGIBLE),
          Map.entry(
              "payroll_salary_advance_request_reference",
              ErrorCode.SALARY_ADVANCE_PAYROLL_REFERENCE_MISSING),
          Map.entry(
              "payroll_salary_advance_request_amount",
              ErrorCode.SALARY_ADVANCE_AMOUNT_OUT_OF_RANGE),
          Map.entry(
              "payroll_salary_advance_request_open_limit",
              ErrorCode.SALARY_ADVANCE_OPEN_LIMIT_REACHED),
          Map.entry(
              "payroll_salary_advance_request_waiting", ErrorCode.SALARY_ADVANCE_WAITING_PERIOD),
          Map.entry(
              "payroll_salary_advance_request_transition",
              ErrorCode.SALARY_ADVANCE_TRANSITION_NOT_ALLOWED),
          Map.entry(
              "payroll_salary_advance_decision_state",
              ErrorCode.SALARY_ADVANCE_TRANSITION_NOT_ALLOWED),
          Map.entry(
              "payroll_salary_advance_event_transition",
              ErrorCode.SALARY_ADVANCE_TRANSITION_NOT_ALLOWED),
          // MVP-120B (V53): the service checks the source request, the consent and the lifecycle
          // under the locks,
          // so a guard that still fires is a lost race (or a defect).
          Map.entry(
              "payroll_salary_advance_handoff_request_unique",
              ErrorCode.SALARY_ADVANCE_HANDOFF_EXISTS),
          Map.entry(
              "payroll_salary_advance_handoff_request_withdraw",
              ErrorCode.SALARY_ADVANCE_HANDOFF_EXISTS),
          Map.entry(
              "payroll_salary_advance_handoff_source",
              ErrorCode.SALARY_ADVANCE_EMPLOYER_CONFIRMATION_REQUIRED),
          Map.entry(
              "payroll_salary_advance_handoff_tenant",
              ErrorCode.SALARY_ADVANCE_EMPLOYER_CONFIRMATION_REQUIRED),
          Map.entry("payroll_salary_advance_handoff_owner", ErrorCode.SALARY_ADVANCE_NOT_ELIGIBLE),
          Map.entry(
              "payroll_salary_advance_handoff_actor",
              ErrorCode.SALARY_ADVANCE_SELF_REVIEW_FORBIDDEN),
          Map.entry(
              "payroll_salary_advance_handoff_consent_binding",
              ErrorCode.SALARY_ADVANCE_HANDOFF_PREVIEW_STALE),
          Map.entry(
              "payroll_salary_advance_handoff_transition",
              ErrorCode.SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED),
          Map.entry(
              "payroll_salary_advance_handoff_event_transition",
              ErrorCode.SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED),
          Map.entry(
              "payroll_salary_advance_handoff_attempt_state",
              ErrorCode.SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED),
          Map.entry(
              "payroll_salary_advance_handoff_ack_state",
              ErrorCode.SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED),
          Map.entry(
              "payroll_salary_advance_handoff_callback_state",
              ErrorCode.SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED),
          Map.entry(
              "payroll_salary_advance_handoff_review_event_transition",
              ErrorCode.SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED));

  private SalaryAdvanceConstraintViolations() {}

  /**
   * The violated constraint of an integrity failure.
   *
   * @param failure any failure
   * @return the constraint name, if the failure is a named PostgreSQL integrity violation
   */
  public static Optional<String> constraint(Throwable failure) {
    return PayrollConstraintViolations.constraint(failure);
  }

  /**
   * The stable business answer of a constraint, if it has one.
   *
   * @param constraint a constraint name
   * @return the error code, or empty when the violation is a defect
   */
  public static Optional<ErrorCode> answer(String constraint) {
    return Optional.ofNullable(ANSWERS.get(constraint));
  }
}
