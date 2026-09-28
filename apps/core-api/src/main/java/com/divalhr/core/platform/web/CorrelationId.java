package com.divalhr.core.platform.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Correlation ID conventions shared by HTTP handling and logging. */
public final class CorrelationId {

  /** HTTP header carrying the correlation ID in requests and responses. */
  public static final String HEADER = "X-Correlation-Id";

  /** Logging MDC key for the correlation ID. */
  public static final String MDC_KEY = "correlationId";

  /** Request attribute under which the resolved correlation ID is stored. */
  public static final String REQUEST_ATTRIBUTE = CorrelationId.class.getName();

  private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

  private CorrelationId() {}

  /**
   * Accepts a caller-supplied ID only when it is safe to echo and log; otherwise generates one.
   *
   * @param candidate value received from the caller, possibly {@code null}
   * @return a safe correlation ID
   */
  public static String resolve(String candidate) {
    return Optional.ofNullable(candidate)
        .map(String::trim)
        .filter(value -> ALLOWED.matcher(value).matches())
        .orElseGet(() -> UUID.randomUUID().toString());
  }
}
