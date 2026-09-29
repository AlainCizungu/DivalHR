package com.divalhr.core.platform.idempotency;

import java.util.Objects;

/**
 * Identity of an idempotent request: the operation, the verified immutable JWT {@code sub} of the
 * caller, and the client-supplied key.
 *
 * @param operation fixed operation name
 * @param principal verified subject
 * @param key client idempotency key
 */
public record IdempotencyScope(String operation, String principal, String key) {

  /** Requires every component. */
  public IdempotencyScope {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(key, "key");
  }
}
