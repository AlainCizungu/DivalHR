package com.divalhr.core.people.domain.separation;

import java.io.Serial;

/** A separation rule refused a plan; mapped to stable Problem codes by the application. */
public final class SeparationRuleException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Rules. */
  public enum Rule {
    /** A row the separation wrote was replaced since (SEPARATION_NOT_CANCELLABLE). */
    HISTORY_CHANGED_SINCE
  }

  private final Rule rule;

  /**
   * Creates the exception.
   *
   * @param rule rule
   */
  public SeparationRuleException(Rule rule) {
    super(rule.name(), null, false, false);
    this.rule = rule;
  }

  /**
   * The rule.
   *
   * @return rule
   */
  public Rule rule() {
    return rule;
  }
}
