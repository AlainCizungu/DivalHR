package com.divalhr.core.platform.operation;

import java.util.regex.Pattern;

/**
 * The single grammar for operation names (idempotency scopes) and audit actions: lower-case
 * hyphenated segments joined by dots, at least two segments, e.g. {@code legal-entity.create}.
 *
 * <p>The database enforces the same expression ({@code db/migration/V4__...}); a contract test
 * keeps the two identical. Do not define another copy of this expression.
 */
public final class OperationName {

  /** Approved grammar (Issue #17). */
  public static final String GRAMMAR = "^[a-z]+(-[a-z]+)*(\\.[a-z]+(-[a-z]+)*)+$";

  private static final Pattern PATTERN = Pattern.compile(GRAMMAR);

  private OperationName() {}

  /**
   * Whether a name follows the grammar.
   *
   * @param name candidate
   * @return true when valid
   */
  public static boolean isValid(String name) {
    return name != null && PATTERN.matcher(name).matches();
  }

  /**
   * Returns the name if it follows the grammar.
   *
   * @param name candidate (a code constant, never user input)
   * @param what what the name identifies, for the error message
   * @return the name
   * @throws IllegalArgumentException if the name is malformed (the value is not echoed)
   */
  public static String require(String name, String what) {
    if (!isValid(name)) {
      throw new IllegalArgumentException(what + " does not follow the operation-name grammar");
    }
    return name;
  }
}
