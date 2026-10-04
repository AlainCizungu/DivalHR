package com.divalhr.core.people.domain.history;

import java.io.Serial;

/** A business rule of the temporal model refused a plan. */
public final class TimelineRuleException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final TimelineRule rule;
  private final transient AssignmentKind kind;

  /**
   * Creates the exception.
   *
   * @param rule rule
   * @param kind the kind concerned, or {@code null}
   */
  public TimelineRuleException(TimelineRule rule, AssignmentKind kind) {
    super(rule.name(), null, false, false);
    this.rule = rule;
    this.kind = kind;
  }

  /**
   * The rule.
   *
   * @return rule
   */
  public TimelineRule rule() {
    return rule;
  }

  /**
   * The kind concerned.
   *
   * @return kind, or {@code null}
   */
  public AssignmentKind kind() {
    return kind;
  }
}
