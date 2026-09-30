package com.divalhr.core.platform.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Determines the client address used only as a rate-limit key. {@code X-Forwarded-For} is used only
 * when the direct peer is a configured trusted proxy; otherwise it is ignored (fail closed), so a
 * client cannot choose its own key. The address is never logged, stored or put in metrics.
 */
@Component
public class ClientAddressResolver {

  /** Forwarding header written by trusted reverse proxies. */
  public static final String FORWARDED_FOR = "X-Forwarded-For";

  private final Set<String> trustedProxies;

  /**
   * Creates the resolver.
   *
   * @param properties rate-limit settings (trusted proxies)
   */
  public ClientAddressResolver(RateLimitProperties properties) {
    this.trustedProxies = Set.copyOf(properties.trustedProxies());
  }

  /**
   * Resolves the client address.
   *
   * @param request current request
   * @return the direct peer, or the nearest untrusted address in {@code X-Forwarded-For} when the
   *     peer is a trusted proxy
   */
  public String resolve(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    if (peer == null || !trustedProxies.contains(peer)) {
      return peer == null ? "unknown" : peer;
    }
    String forwarded = request.getHeader(FORWARDED_FOR);
    if (forwarded == null || forwarded.isBlank()) {
      return peer;
    }
    List<String> hops = List.of(forwarded.split(","));
    for (int i = hops.size() - 1; i >= 0; i--) {
      String hop = hops.get(i).trim();
      if (hop.isEmpty() || hop.length() > 64) {
        return peer;
      }
      if (!trustedProxies.contains(hop)) {
        return hop;
      }
    }
    return peer;
  }
}
