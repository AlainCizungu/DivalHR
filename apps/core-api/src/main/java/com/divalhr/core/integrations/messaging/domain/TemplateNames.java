package com.divalhr.core.integrations.messaging.domain;

import java.util.regex.Pattern;

/**
 * The bounded grammar of a Meta template name (D111-7): a lower-case letter, then lower-case
 * letters, digits and underscores, at most 512 characters. Names are configuration, never message
 * content, and never appear in audit, logs, metrics or errors.
 */
public final class TemplateNames {

  private static final Pattern GRAMMAR = Pattern.compile("^[a-z][a-z0-9_]{0,511}$");

  private TemplateNames() {}

  /**
   * @param value candidate
   * @return whether it is an acceptable template name
   */
  public static boolean valid(Object value) {
    return value instanceof String text && GRAMMAR.matcher(text).matches();
  }
}
