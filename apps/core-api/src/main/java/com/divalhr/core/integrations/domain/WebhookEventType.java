package com.divalhr.core.integrations.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The closed public event catalogue (D110-7). A new entry is a separate reviewed contract change
 * together with {@code packages/shared-contracts/schemas/webhook-event.schema.json}.
 */
public enum WebhookEventType {
  /** A leave request was approved (any routing path). */
  LEAVE_REQUEST_APPROVED(
      "leave.request.approved.v1", "leave-request", "APPROVED", "/admin/leave-approvals"),
  /** A leave request was rejected (any routing path). */
  LEAVE_REQUEST_REJECTED(
      "leave.request.rejected.v1", "leave-request", "REJECTED", "/admin/leave-approvals"),
  /** A timesheet was approved. */
  TIMESHEET_APPROVED("timesheet.approved.v1", "timesheet", "APPROVED", "/admin/timesheets"),
  /** An attendance record was corrected. */
  ATTENDANCE_CORRECTED(
      "attendance.corrected.v1", "attendance-record", "CORRECTED", "/admin/attendance-review"),
  /** A payroll input run was locked. */
  PAYROLL_INPUT_LOCKED(
      "payroll.input.locked.v1", "payroll-input-run", "LOCKED", "/admin/payroll-preparation"),
  /** A payroll input export was created. */
  PAYROLL_INPUT_EXPORT_CREATED(
      "payroll.input-export.created.v1",
      "payroll-input-export",
      "CREATED",
      "/admin/payroll-preparation"),
  /** Test event; only produced by the test-delivery command. */
  WEBHOOK_TEST("webhook.test.v1", "webhook-test", "TEST", null);

  /** Every internal outbox event type that maps to a public event. */
  public static final Set<String> INTERNAL_TYPES =
      Set.of(
          "people.leave-request.approved.v1",
          "people.leave-request.routing-exception-approved.v1",
          "people.leave-request.substitute-approved.v1",
          "people.leave-request.rejected.v1",
          "people.leave-request.routing-exception-rejected.v1",
          "people.leave-request.substitute-rejected.v1",
          "people.timesheet.approved.v1",
          "people.attendance.corrected.v1",
          "payroll.payroll-input.locked.v1",
          "payroll.payroll-input-export.created.v1");

  private final String code;
  private final String resourceKind;
  private final String resourceStatus;
  private final String link;

  WebhookEventType(String code, String resourceKind, String resourceStatus, String link) {
    this.code = code;
    this.resourceKind = resourceKind;
    this.resourceStatus = resourceStatus;
    this.link = link;
  }

  /**
   * @return the public event type string
   */
  public String code() {
    return code;
  }

  /**
   * @return the allow-listed resource kind
   */
  public String resourceKind() {
    return resourceKind;
  }

  /**
   * @return the closed resource status
   */
  public String resourceStatus() {
    return resourceStatus;
  }

  /**
   * @return the single allow-listed relative path, or empty for the test event
   */
  public Optional<String> link() {
    return Optional.ofNullable(link);
  }

  /**
   * @return whether administrators may subscribe to it (everything but the test event)
   */
  public boolean subscribable() {
    return this != WEBHOOK_TEST;
  }

  /**
   * Looks a public code up.
   *
   * @param value public event type string
   * @return the type, or empty when it is not in the catalogue
   */
  public static Optional<WebhookEventType> of(String value) {
    return Arrays.stream(values()).filter(t -> t.code.equals(value)).findFirst();
  }

  /**
   * @return the codes an administrator may subscribe to
   */
  public static Set<String> subscribableCodes() {
    return Arrays.stream(values())
        .filter(WebhookEventType::subscribable)
        .map(WebhookEventType::code)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Maps an internal outbox event type to the public event it produces. Routing-exception and
   * substitute outcomes map to the same public events as normal ones (the public payload never says
   * which internal path produced the outcome).
   *
   * @param internalType internal outbox event type
   * @return the public event, or empty when the internal event is not public
   */
  public static Optional<WebhookEventType> fromInternal(String internalType) {
    if (internalType == null) {
      return Optional.empty();
    }
    return switch (internalType) {
      case "people.leave-request.approved.v1",
          "people.leave-request.routing-exception-approved.v1",
          "people.leave-request.substitute-approved.v1" ->
          Optional.of(LEAVE_REQUEST_APPROVED);
      case "people.leave-request.rejected.v1",
          "people.leave-request.routing-exception-rejected.v1",
          "people.leave-request.substitute-rejected.v1" ->
          Optional.of(LEAVE_REQUEST_REJECTED);
      case "people.timesheet.approved.v1" -> Optional.of(TIMESHEET_APPROVED);
      case "people.attendance.corrected.v1" -> Optional.of(ATTENDANCE_CORRECTED);
      case "payroll.payroll-input.locked.v1" -> Optional.of(PAYROLL_INPUT_LOCKED);
      case "payroll.payroll-input-export.created.v1" -> Optional.of(PAYROLL_INPUT_EXPORT_CREATED);
      default -> Optional.empty();
    };
  }
}
