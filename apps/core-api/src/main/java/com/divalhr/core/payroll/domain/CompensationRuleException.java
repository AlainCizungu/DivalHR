package com.divalhr.core.payroll.domain;

import java.io.Serial;

/**
 * A timeline rule that refuses a command. Carries the stable blocker only; the message never holds
 * an amount, a date or an identifier.
 */
public final class CompensationRuleException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final CompensationBlocker blocker;

  /**
   * Creates the exception.
   *
   * @param blocker the blocker
   */
  public CompensationRuleException(CompensationBlocker blocker) {
    super(blocker.name());
    this.blocker = blocker;
  }

  /**
   * The blocker.
   *
   * @return the stable reason
   */
  public CompensationBlocker blocker() {
    return blocker;
  }
}
