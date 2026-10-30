package com.divalhr.core.payroll.domain;

/**
 * The stable run-level blockers of a statutory preview (MVP-094B, ADR 0034 section 6). Any one of
 * them blocks the whole calculation: the lines then carry no amount. The declaration order is the
 * order they are reported in.
 */
public enum StatutoryRunBlocker {
  /** The source run is not the current DRAFT of its chain. */
  SOURCE_RUN_NOT_CURRENT,
  /** The run currency is not CDF (no exchange-rate policy exists). */
  CURRENCY_NOT_SUPPORTED,
  /** No rule pack is activated for the legal entity and month. */
  RULE_PACK_MISSING,
  /** The pack in effect is not VERIFIED, or does not apply to the month. */
  RULE_PACK_NOT_VERIFIED
}
