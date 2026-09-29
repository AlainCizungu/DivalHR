package com.divalhr.core.tenant.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** Stable hierarchy codes: trimmed, upper-cased, 2-20 of A-Z, 0-9, hyphen and underscore. */
public final class HierarchyCode {

  private static final Pattern NORMALIZED = Pattern.compile("^[A-Z0-9_-]{2,20}$");

  private HierarchyCode() {}

  /**
   * Normalizes a submitted code.
   *
   * @param raw submitted value
   * @return trimmed, upper-cased code (validity checked separately)
   */
  public static String normalize(String raw) {
    return raw.strip().toUpperCase(Locale.ROOT);
  }

  /**
   * Whether a normalized code is well formed.
   *
   * @param normalized normalized code
   * @return true when valid
   */
  public static boolean isValid(String normalized) {
    return NORMALIZED.matcher(normalized).matches();
  }
}
