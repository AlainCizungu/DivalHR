package com.divalhr.core.payroll.domain;

import java.util.Objects;

/**
 * A statutory rule pack that does not parse or does not validate (MVP-094B, ADR 0034 section 3). A
 * pack that raises it is never applicable. The exception carries a stable {@link Reason} and
 * nothing of the input: neither the message nor any field echoes a byte of the document.
 */
public final class StatutoryRulesException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why the rules were refused; stable codes. */
  public enum Reason {
    /** The text is not a JSON document of the supported grammar. */
    MALFORMED_JSON,
    /** The document is longer than 65,536 characters. */
    TOO_LARGE,
    /** The document is nested more than 8 levels deep. */
    TOO_DEEP,
    /** An object has the same field twice. */
    DUPLICATE_FIELD,
    /** An object has a field the schema does not define. */
    UNKNOWN_FIELD,
    /** An object lacks a required field. */
    MISSING_FIELD,
    /** A value has the wrong JSON type. */
    WRONG_TYPE,
    /** The schema version is not 1. */
    UNSUPPORTED_SCHEMA_VERSION,
    /** There are not 1 to 12 components. */
    INVALID_COMPONENT_COUNT,
    /** A component id does not match the closed id pattern. */
    INVALID_COMPONENT_ID,
    /** Two components have the same id. */
    DUPLICATE_COMPONENT_ID,
    /** The component orders are not unique and ascending. */
    INVALID_ORDER,
    /** A value is not in its closed vocabulary. */
    UNKNOWN_VALUE,
    /** A number is not an integer of the supported size. */
    INVALID_NUMBER,
    /** A rate is not 0 to 10000 basis points. */
    INVALID_RATE,
    /** The periods per year are not 1 or 12. */
    INVALID_PERIODS,
    /** The cap is not 0 to 10000 basis points. */
    INVALID_CAP,
    /** The brackets are not ascending with an open last bracket. */
    INVALID_BRACKETS,
    /** A base refers to nothing of a lower order. */
    INVALID_BASE_DEPENDENCY,
    /** A source key is not one of the closed source keys, or not among the pack's sources. */
    UNKNOWN_SOURCE_KEY
  }

  private final Reason reason;

  /**
   * Creates the exception.
   *
   * @param reason the stable reason
   */
  public StatutoryRulesException(Reason reason) {
    super(Objects.requireNonNull(reason, "reason").name());
    this.reason = reason;
  }

  /**
   * The stable reason.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}
