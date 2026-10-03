package com.divalhr.core.platform.ratelimit;

import com.divalhr.core.platform.error.ErrorCode;
import java.io.Serial;

/**
 * A per-subject {@code 429 RATE_LIMITED} (MVP-012B) that also says whether it is the subject's
 * first refusal in the current window (MVP-013, D6): only that refusal may become denial evidence,
 * so a limited caller cannot turn repeated refusals into audit writes.
 */
public class SubjectRateLimitedException extends RateLimitedException {

  @Serial private static final long serialVersionUID = 1L;

  private final boolean firstInWindow;

  /**
   * Creates the exception.
   *
   * @param retryAfterSeconds seconds left in the window
   * @param firstInWindow whether this is the subject's first refusal in the window
   */
  public SubjectRateLimitedException(long retryAfterSeconds, boolean firstInWindow) {
    super(ErrorCode.RATE_LIMITED, retryAfterSeconds);
    this.firstInWindow = firstInWindow;
  }

  /**
   * Whether this is the subject's first refusal in the window. Refusals because the tracking table
   * is full are never first.
   *
   * @return true for the first refusal
   */
  public boolean firstInWindow() {
    return firstInWindow;
  }
}
