package com.divalhr.core.payroll.domain;

/** Why an accounting journal cannot be generated (MVP-096, ADR 0039 section 5). */
public enum AccountingJournalBlocker {
  /** The package was voided. */
  PACKAGE_VOIDED,
  /** The package is not APPROVED. */
  PACKAGE_NOT_APPROVED,
  /** The package already has a journal. */
  JOURNAL_ALREADY_EXISTS,
  /** No mapping applies to the package month and currency. */
  MAPPING_NOT_FOUND,
  /** More than 10,000 entries before or 2,000 lines after aggregation. */
  TOO_LARGE,
  /** The frozen amounts do not balance or do not equal the package totals. */
  UNBALANCED
}
