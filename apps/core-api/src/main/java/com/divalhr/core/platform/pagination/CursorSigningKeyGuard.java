package com.divalhr.core.platform.pagination;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Fails closed on unsafe cursor signing keys. Every environment needs a key of at least 32 bytes;
 * outside development, the published development placeholder (any {@code dev-only-} value) is
 * rejected.
 */
public final class CursorSigningKeyGuard {

  /** Minimum key length in bytes. */
  public static final int MIN_BYTES = 32;

  private static final Set<String> PLACEHOLDER_ALLOWED = Set.of("development");

  private CursorSigningKeyGuard() {}

  /**
   * Validates the key and returns its bytes.
   *
   * @param environment deployment environment
   * @param key configured key
   * @return key bytes
   * @throws IllegalStateException if the key is unsafe; the message never contains the key
   */
  public static byte[] validate(String environment, String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("divalhr.pagination.cursor-signing-key is required");
    }
    byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_BYTES) {
      throw new IllegalStateException(
          "divalhr.pagination.cursor-signing-key must be at least " + MIN_BYTES + " bytes");
    }
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    if (!PLACEHOLDER_ALLOWED.contains(env)
        && key.toLowerCase(Locale.ROOT).startsWith("dev-only-")) {
      throw new IllegalStateException(
          "The development cursor signing key must not be used in environment " + env);
    }
    return bytes;
  }
}
