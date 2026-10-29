package com.divalhr.core.payroll.domain;

/**
 * The closed catalogue of compensation changes (MVP-093A, D93-7). The first three are commands;
 * the last records the cancellation of a scheduled change.
 */
public enum CompensationAction {
  /** The first compensation fact of an employee. */
  ESTABLISHED("compensation.establish", "payroll.compensation.established.v1"),
  /** A new effective-dated amount or currency. */
  CHANGED("compensation.change", "payroll.compensation.changed.v1"),
  /** The correction of a previously recorded fact. */
  CORRECTED("compensation.correct", "payroll.compensation.corrected.v1"),
  /** The cancellation of a change before it starts. */
  SCHEDULED_CHANGE_CANCELLED(
      "compensation.scheduled-change-cancel",
      "payroll.compensation.scheduled-change-cancelled.v1");

  private final String auditAction;
  private final String eventType;

  CompensationAction(String auditAction, String eventType) {
    this.auditAction = auditAction;
    this.eventType = eventType;
  }

  /**
   * The audit action of a recorded change.
   *
   * @return audit action
   */
  public String auditAction() {
    return auditAction;
  }

  /**
   * The internal outbox event type of a recorded change.
   *
   * @return event type
   */
  public String eventType() {
    return eventType;
  }

  /**
   * Whether a client may name the action in a command.
   *
   * @return false only for the cancellation
   */
  public boolean isCommand() {
    return this != SCHEDULED_CHANGE_CANCELLED;
  }
}
