package com.divalhr.core.platform.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explicit browser origins allowed to call the Core API. Wildcards are rejected at startup.
 *
 * @param allowedOrigins exact origins such as {@code http://localhost:5173}
 */
@ConfigurationProperties("divalhr.cors")
public record CorsProperties(List<String> allowedOrigins) {

  /** Validates that only explicit origins are configured. */
  public CorsProperties {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    for (String origin : allowedOrigins) {
      if (origin.contains("*")) {
        throw new IllegalArgumentException("Wildcard CORS origins are not permitted: " + origin);
      }
    }
  }
}
