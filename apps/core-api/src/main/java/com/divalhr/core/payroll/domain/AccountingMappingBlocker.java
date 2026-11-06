package com.divalhr.core.payroll.domain;

/** Why an accounting mapping version cannot be appended (MVP-096, ADR 0039 section 5). */
public enum AccountingMappingBlocker {
  /** The effective date is not strictly later than the latest version's. */
  OVERLAP,
  /** The chain already holds ten versions. */
  LIMIT_REACHED
}
