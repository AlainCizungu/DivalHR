package com.divalhr.core.platform.ratelimit;

import com.divalhr.core.platform.error.ErrorCode;
import java.io.Serial;

/**
 * A per-tenant {@code 429 RATE_LIMITED} (MVP-020, A20-4) that says whether it is the tenant's first
 * refusal in the bucket's current window: only that refusal may become denial evidence (MVP-013).
 */
public class TenantRateLimitedException extends RateLimitedException {

  @Serial private static final long serialVersionUID = 1L;

  private final boolean firstInWindow;

  /**
   * Creates the exception.
   *
   * @param retryAfterSeconds seconds left in the window
   * @param firstInWindow whether this is the tenant's first refusal in the window
   */
  public TenantRateLimitedException(long retryAfterSeconds, boolean firstInWindow) {
    super(ErrorCode.RATE_LIMITED, retryAfterSeconds);
    this.firstInWindow = firstInWindow;
  }

  /**
   * Whether this is the tenant's first refusal in the window.
   *
   * @return true for the first refusal
   */
  public boolean firstInWindow() {
    return firstInWindow;
  }
}
