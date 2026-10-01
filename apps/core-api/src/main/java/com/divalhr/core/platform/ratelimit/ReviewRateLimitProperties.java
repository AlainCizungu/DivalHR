package com.divalhr.core.platform.ratelimit;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-subject limit of the access review ({@code divalhr.access-review}; MVP-012B, architect
 * decision D5 and R2). In-process and per instance: the effective cluster ceiling is the limit
 * times the number of active instances. A shared or durable limiter is a production-scaling
 * follow-up.
 *
 * @param requestsPerMinute review requests one subject may make per fixed one-minute window across
 *     all access-review operations (default 30; 1 to 120)
 * @param maxTrackedSubjects subjects tracked per window before new ones are refused (default
 *     10,000; 100 to 1,000,000)
 */
@ConfigurationProperties("divalhr.access-review")
public record ReviewRateLimitProperties(Integer requestsPerMinute, Integer maxTrackedSubjects) {

  /** Applies defaults and enforces bounds. */
  public ReviewRateLimitProperties {
    requestsPerMinute = Objects.requireNonNullElse(requestsPerMinute, 30);
    maxTrackedSubjects = Objects.requireNonNullElse(maxTrackedSubjects, 10_000);
    if (requestsPerMinute < 1 || requestsPerMinute > 120) {
      throw new IllegalStateException("divalhr.access-review.requests-per-minute must be 1..120");
    }
    if (maxTrackedSubjects < 100 || maxTrackedSubjects > 1_000_000) {
      throw new IllegalStateException(
          "divalhr.access-review.max-tracked-subjects must be 100..1000000");
    }
  }
}
