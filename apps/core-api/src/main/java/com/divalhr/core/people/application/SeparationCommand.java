package com.divalhr.core.people.application;

import com.divalhr.core.people.api.CancelSeparationRequest;
import com.divalhr.core.people.api.CreateSeparationRequest;
import com.divalhr.core.people.api.SeparationFields;
import com.divalhr.core.people.api.UpdateSeparationTaskRequest;
import com.divalhr.core.people.domain.separation.AccessTiming;
import com.divalhr.core.people.domain.separation.Acknowledgement;
import com.divalhr.core.people.domain.separation.ReportAction;
import com.divalhr.core.people.domain.separation.SeparationReason;
import com.divalhr.core.people.domain.separation.TaskStatus;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A validated separation command (MVP-022). Raw JSON values are checked here so that types, formats
 * and unknown properties give stable field errors; business rules are checked by {@link
 * EmployeeSeparationService}.
 *
 * @param lastDay inclusive last day
 * @param reason reason
 * @param accessTiming access timing
 * @param reportAction direct-report action, or {@code null} when no plan was given
 * @param replacementManagerId replacement manager (REASSIGN), else {@code null}
 */
record SeparationCommand(
    LocalDate lastDay,
    SeparationReason reason,
    AccessTiming accessTiming,
    ReportAction reportAction,
    UUID replacementManagerId) {

  private static final Set<String> PLAN_KEYS = Set.of("action", "managerEmployeeId");

  /**
   * Parses the command fields.
   *
   * @param request body
   * @param errors collected errors
   * @return the command, or {@code null} when invalid
   */
  static SeparationCommand parse(SeparationFields request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      return null;
    }
    for (String unknown : request.unknownProperties()) {
      errors.add(unknown, Constraint.UNKNOWN_PROPERTY);
    }
    LocalDate lastDay = EmploymentChangeCommand.date(request.getLastDay(), "lastDay", errors);
    SeparationReason reason = null;
    if (request.getReasonCode() == null) {
      errors.add("reasonCode", Constraint.REQUIRED);
    } else {
      reason =
          EmploymentChangeCommand.enumValue(
              SeparationReason.class, request.getReasonCode(), "reasonCode", errors);
    }
    AccessTiming timing = null;
    if (request.getAccessTiming() == null) {
      errors.add("accessTiming", Constraint.REQUIRED);
    } else {
      timing =
          EmploymentChangeCommand.enumValue(
              AccessTiming.class, request.getAccessTiming(), "accessTiming", errors);
    }
    ReportAction action = null;
    UUID replacement = null;
    Object plan = request.getReportPlan();
    if (plan != null) {
      if (!(plan instanceof Map<?, ?> map)) {
        errors.add("reportPlan", Constraint.FORMAT);
      } else {
        for (Object key : map.keySet()) {
          if (!PLAN_KEYS.contains(String.valueOf(key))) {
            errors.add("reportPlan." + key, Constraint.UNKNOWN_PROPERTY);
          }
        }
        if (map.get("action") == null) {
          errors.add("reportPlan.action", Constraint.REQUIRED);
        } else {
          action =
              EmploymentChangeCommand.enumValue(
                  ReportAction.class, map.get("action"), "reportPlan.action", errors);
        }
        Object manager = map.get("managerEmployeeId");
        if (action == ReportAction.REASSIGN) {
          if (manager == null) {
            errors.add("reportPlan.managerEmployeeId", Constraint.REQUIRED);
          } else {
            replacement =
                EmploymentChangeCommand.uuid(manager, "reportPlan.managerEmployeeId", errors);
          }
        } else if (action == ReportAction.CLEAR && manager != null) {
          errors.add("reportPlan.managerEmployeeId", Constraint.FORMAT);
        }
      }
    }
    if (!errors.isEmpty()) {
      return null;
    }
    return new SeparationCommand(lastDay, reason, timing, action, replacement);
  }

  /**
   * Stable text of the command (idempotency fingerprint and digests).
   *
   * @return canonical text
   */
  String canonical() {
    return "lastDay="
        + lastDay
        + ";reason="
        + reason.name()
        + ";accessTiming="
        + accessTiming.name()
        + ";reportAction="
        + (reportAction == null ? "-" : reportAction.name())
        + ";replacement="
        + (replacementManagerId == null ? "-" : replacementManagerId.toString());
  }

  /**
   * What the commit repeats from the preview.
   *
   * @param expectedVersion employment version
   * @param previewDigest digest
   * @param acknowledgements acknowledgements given
   */
  record Confirmation(
      long expectedVersion, String previewDigest, Set<Acknowledgement> acknowledgements) {

    /** Copies the acknowledgements. */
    Confirmation {
      acknowledgements =
          acknowledgements.isEmpty()
              ? EnumSet.noneOf(Acknowledgement.class)
              : EnumSet.copyOf(acknowledgements);
    }

    /**
     * Canonical values for the idempotency fingerprint.
     *
     * @return key-sorted map
     */
    Map<String, Object> canonical() {
      Map<String, Object> values = new TreeMap<>();
      values.put("expectedVersion", expectedVersion);
      values.put("previewDigest", previewDigest);
      values.put("acknowledgements", acknowledgements.stream().map(Enum::name).sorted().toList());
      return values;
    }
  }

  /**
   * Parses the confirmation of a commit.
   *
   * @param request body
   * @param errors collected errors
   * @return the confirmation, or {@code null} when invalid
   */
  static Confirmation confirmation(CreateSeparationRequest request, FieldErrors errors) {
    if (request == null) {
      return null;
    }
    Long version =
        EmploymentChangeCommand.version(request.getExpectedVersion(), "expectedVersion", errors);
    String digest =
        EmploymentChangeCommand.digest(request.getPreviewDigest(), "previewDigest", errors);
    Set<Acknowledgement> acknowledgements = EnumSet.noneOf(Acknowledgement.class);
    Object raw = request.getAcknowledgements();
    if (raw == null) {
      errors.add("acknowledgements", Constraint.REQUIRED);
    } else if (!(raw instanceof List<?> list) || list.size() > 3) {
      errors.add("acknowledgements", Constraint.FORMAT);
    } else {
      for (Object item : list) {
        Acknowledgement value =
            EmploymentChangeCommand.enumValue(
                Acknowledgement.class, item, "acknowledgements", errors);
        if (value != null && !acknowledgements.add(value)) {
          errors.add("acknowledgements", Constraint.DUPLICATE);
        }
      }
    }
    if (!errors.isEmpty() || version == null || digest == null) {
      return null;
    }
    return new Confirmation(version, digest, acknowledgements);
  }

  /**
   * A cancellation confirmation.
   *
   * @param expectedVersion employment version
   * @param cancellationDigest digest
   */
  record CancellationConfirmation(long expectedVersion, String cancellationDigest) {}

  /**
   * Parses a cancellation confirmation.
   *
   * @param request body
   * @param errors collected errors
   * @return the confirmation, or {@code null} when invalid
   */
  static CancellationConfirmation cancellation(
      CancelSeparationRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      return null;
    }
    for (String unknown : request.unknownProperties()) {
      errors.add(unknown, Constraint.UNKNOWN_PROPERTY);
    }
    Long version =
        EmploymentChangeCommand.version(request.getExpectedVersion(), "expectedVersion", errors);
    String digest =
        EmploymentChangeCommand.digest(
            request.getCancellationDigest(), "cancellationDigest", errors);
    if (!errors.isEmpty() || version == null || digest == null) {
      return null;
    }
    return new CancellationConfirmation(version, digest);
  }

  /**
   * A task status command.
   *
   * @param status OPEN, DONE or NOT_APPLICABLE
   * @param expectedVersion task version
   */
  record TaskUpdate(TaskStatus status, long expectedVersion) {}

  /**
   * Parses a task status command.
   *
   * @param request body
   * @param errors collected errors
   * @return the command, or {@code null} when invalid
   */
  static TaskUpdate taskUpdate(UpdateSeparationTaskRequest request, FieldErrors errors) {
    if (request == null) {
      errors.add("body", Constraint.REQUIRED);
      return null;
    }
    for (String unknown : request.unknownProperties()) {
      errors.add(unknown, Constraint.UNKNOWN_PROPERTY);
    }
    TaskStatus status = null;
    if (request.getStatus() == null) {
      errors.add("status", Constraint.REQUIRED);
    } else {
      status =
          EmploymentChangeCommand.enumValue(
              TaskStatus.class, request.getStatus(), "status", errors);
      if (status == TaskStatus.CANCELLED) {
        errors.add("status", Constraint.FORMAT);
      }
    }
    Long version =
        EmploymentChangeCommand.version(request.getExpectedVersion(), "expectedVersion", errors);
    if (!errors.isEmpty() || status == null || version == null) {
      return null;
    }
    return new TaskUpdate(status, version);
  }
}
