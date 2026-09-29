package com.divalhr.core.platform.pagination;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pagination settings.
 *
 * @param cursorSigningKey HMAC key for cursors (at least 32 bytes). Rotating it invalidates every
 *     outstanding cursor; clients then restart from the first page.
 */
@ConfigurationProperties("divalhr.pagination")
public record CursorProperties(String cursorSigningKey) {

  @Override
  public String toString() {
    return "CursorProperties[cursorSigningKey=<redacted>]";
  }
}
