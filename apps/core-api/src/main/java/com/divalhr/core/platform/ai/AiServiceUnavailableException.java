package com.divalhr.core.platform.ai;

import java.io.Serial;

/**
 * The AI service could not produce a briefing. Carries only a bounded, low-cardinality reason (for
 * example {@code timeout} or {@code unauthorized}); never a body, a token, an address or a prompt.
 */
public class AiServiceUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final String reason;

  /**
   * Creates the exception.
   *
   * @param reason bounded failure class
   */
  public AiServiceUnavailableException(String reason) {
    super(reason);
    this.reason = reason;
  }

  /**
   * The bounded failure class.
   *
   * @return reason
   */
  public String reason() {
    return reason;
  }
}
