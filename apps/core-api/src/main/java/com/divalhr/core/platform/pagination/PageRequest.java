package com.divalhr.core.platform.pagination;

/**
 * Validated page size.
 *
 * @param limit rows per page (1-200, default 50)
 */
public record PageRequest(int limit) {

  /** Default page size. */
  public static final int DEFAULT_LIMIT = 50;

  /** Maximum page size. */
  public static final int MAX_LIMIT = 200;

  /**
   * Parses the {@code limit} query parameter.
   *
   * @param raw raw value or {@code null}
   * @return page request, or {@code null} if the value is invalid (the caller reports the field)
   */
  public static PageRequest parse(String raw) {
    if (raw == null || raw.isEmpty()) {
      return new PageRequest(DEFAULT_LIMIT);
    }
    if (!raw.matches("^[0-9]{1,3}$")) {
      return null;
    }
    int value = Integer.parseInt(raw);
    return value < 1 || value > MAX_LIMIT ? null : new PageRequest(value);
  }
}
