package com.divalhr.core.platform.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * In-process limits for anonymous endpoints. They are defense in depth for development and
 * single-instance pilots only: with several Core API instances each instance counts separately, so
 * production deployments must also enforce limits at the ingress or WAF (architecture amendment
 * A4).
 *
 * @param perClientRequests requests allowed per client address per window
 * @param globalRequests requests allowed across all clients per window
 * @param window counting window
 * @param maxTrackedClients bound on remembered client keys (memory safety)
 * @param trustedProxies exact IP addresses of reverse proxies whose {@code X-Forwarded-For} may be
 *     used; empty (the default) means forwarding headers are never trusted
 */
@ConfigurationProperties("divalhr.rate-limit")
public record RateLimitProperties(
    Integer perClientRequests,
    Integer globalRequests,
    Duration window,
    Integer maxTrackedClients,
    List<String> trustedProxies) {

  /** Applies safe defaults. */
  public RateLimitProperties {
    perClientRequests = perClientRequests == null ? 10 : perClientRequests;
    globalRequests = globalRequests == null ? 600 : globalRequests;
    window = window == null ? Duration.ofMinutes(1) : window;
    maxTrackedClients = maxTrackedClients == null ? 10_000 : maxTrackedClients;
    trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    if (perClientRequests < 1 || globalRequests < 1 || maxTrackedClients < 1) {
      throw new IllegalStateException("divalhr.rate-limit values must be positive");
    }
    if (window.isNegative() || window.isZero()) {
      throw new IllegalStateException("divalhr.rate-limit.window must be positive");
    }
  }
}
