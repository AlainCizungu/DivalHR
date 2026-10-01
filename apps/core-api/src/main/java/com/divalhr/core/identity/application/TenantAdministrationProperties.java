package com.divalhr.core.identity.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MVP-014 tenant-administration settings ({@code divalhr.tenant-administration}). Bounds are
 * enforced at start-up.
 *
 * @param lockTimeout longest wait for an organization's tenant-administration lock before the
 *     operation fails safely (default 5 seconds; 100 ms to 60 s)
 * @param bootstrapPerActorPerHour bootstrap invitations one platform administrator may create per
 *     rolling hour across all organizations (default 5; 1 to 50; architect decision on #38, A8)
 */
@ConfigurationProperties("divalhr.tenant-administration")
public record TenantAdministrationProperties(
    Duration lockTimeout, Integer bootstrapPerActorPerHour) {

  /** Applies defaults and enforces bounds. */
  public TenantAdministrationProperties {
    lockTimeout = lockTimeout == null ? Duration.ofSeconds(5) : lockTimeout;
    bootstrapPerActorPerHour = Objects.requireNonNullElse(bootstrapPerActorPerHour, 5);
    if (lockTimeout.compareTo(Duration.ofMillis(100)) < 0
        || lockTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
      throw new IllegalStateException(
          "divalhr.tenant-administration.lock-timeout must be 100ms..60s");
    }
    if (bootstrapPerActorPerHour < 1 || bootstrapPerActorPerHour > 50) {
      throw new IllegalStateException(
          "divalhr.tenant-administration.bootstrap-per-actor-per-hour must be 1..50");
    }
  }
}
