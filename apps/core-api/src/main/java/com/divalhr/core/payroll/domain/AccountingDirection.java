package com.divalhr.core.payroll.domain;

/**
 * The side of a journal line (MVP-096, ADR 0039 section 4). The declaration order is the journal
 * order: a debit sorts before a credit on the same account.
 */
public enum AccountingDirection {
  /** A debit. */
  DEBIT,
  /** A credit. */
  CREDIT
}
