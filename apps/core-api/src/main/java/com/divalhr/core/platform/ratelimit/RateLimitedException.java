package com.divalhr.core.platform.ratelimit;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.io.Serial;
import java.util.Map;

/** A 429 response carrying a {@code Retry-After} value outside the (always empty) params. */
public class RateLimitedException extends ApiException {

  @Serial private static final long serialVersionUID = 1L;

  private final long retryAfterSeconds;

  /**
   * Creates the exception.
   *
   * @param code a rate-limit code
   * @param retryAfterSeconds seconds before a retry may succeed (at least 1)
   */
  public RateLimitedException(ErrorCode code, long retryAfterSeconds) {
    super(code, Map.of());
    this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
  }

  /**
   * Seconds before a retry may succeed.
   *
   * @return at least 1
   */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
