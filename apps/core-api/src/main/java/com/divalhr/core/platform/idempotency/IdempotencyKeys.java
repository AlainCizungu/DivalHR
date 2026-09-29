package com.divalhr.core.platform.idempotency;

import java.util.regex.Pattern;

/** Contract rules for the {@code Idempotency-Key} header (docs/API-SPEC.yaml). */
public final class IdempotencyKeys {

  /** Header name. */
  public static final String HEADER = "Idempotency-Key";

  /** Response header present when a stored response is replayed. */
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");

  private IdempotencyKeys() {}

  /**
   * Checks the key against the contract pattern.
   *
   * @param key candidate
   * @return whether it is well formed
   */
  public static boolean isWellFormed(String key) {
    return key != null && ALLOWED.matcher(key).matches();
  }
}
