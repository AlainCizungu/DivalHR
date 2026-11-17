package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.View;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.ConsentRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.HandoffRow;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Shared read helpers of the handoff services (MVP-120B): the state filter and the full view of one
 * handoff.
 */
final class SalaryAdvanceHandoffReads {

  private static final Set<String> STATES =
      Set.of("QUEUED", "SENDING", "RECEIVED", "DELIVERY_UNKNOWN", "FAILED", "WITHDRAWN");

  private SalaryAdvanceHandoffReads() {}

  /**
   * Validates a delivery-state filter.
   *
   * @param raw raw value, or {@code null}
   * @param errors collector
   * @return the state, or {@code null}
   */
  static String stateFilter(String raw, FieldErrors errors) {
    if (raw == null) {
      return null;
    }
    if (STATES.contains(raw)) {
      return raw;
    }
    errors.add("state", Constraint.FORMAT);
    return null;
  }

  /**
   * One handoff with its consent, history and acknowledgement.
   *
   * @param handoffs storage
   * @param tenant verified tenant
   * @param id handoff
   * @param administrator whether the administrator-only fields are included
   * @return the view
   */
  static View view(
      JdbcSalaryAdvanceHandoffRepository handoffs,
      TenantId tenant,
      UUID id,
      boolean administrator) {
    HandoffRow row =
        handoffs
            .handoff(tenant, id)
            .orElseThrow(() -> new IllegalStateException("handoff vanished"));
    ConsentRow consent =
        handoffs
            .consent(tenant, id)
            .orElseThrow(() -> new IllegalStateException("handoff consent missing"));
    List<JdbcSalaryAdvanceHandoffRepository.EventRow> events = handoffs.events(tenant, id);
    return SalaryAdvanceHandoffViews.view(
        row,
        consent,
        events,
        handoffs.reviewEvents(tenant, id),
        administrator ? handoffs.attempts(tenant, id) : List.of(),
        handoffs.acknowledgedAt(tenant, id).orElse(null),
        administrator);
  }
}
