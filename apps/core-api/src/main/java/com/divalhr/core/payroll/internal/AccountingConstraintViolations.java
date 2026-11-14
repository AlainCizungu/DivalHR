package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.error.ErrorCode;
import java.util.Optional;
import java.util.Set;

/**
 * The named V50 constraints the accounting module maps to stable business answers (MVP-096, ADR
 * 0039). The services check everything under the locks, so a guard that still fires is a lost race
 * (or a defect), except the version limit, which is a business answer. Anything not listed here, in
 * particular the three immutability guards, is a defect and stays unmapped (a safe internal error).
 * Nothing from the database message is ever exposed or logged.
 */
public final class AccountingConstraintViolations {

  /** The effective date is not strictly later than the previous version's. */
  public static final Set<String> MAPPING_OVERLAP =
      Set.of("accounting_mapping_overlap", "accounting_mapping_effective_unique");

  /** The chain already holds ten versions. */
  public static final Set<String> MAPPING_LIMIT = Set.of("accounting_mapping_version_range");

  /** Another command moved the chain under the preview. */
  public static final Set<String> MAPPING_STALE =
      Set.of(
          "accounting_mapping_chain",
          "accounting_mapping_version_unique",
          "accounting_mapping_time");

  /** The package already has a journal. */
  public static final Set<String> JOURNAL_EXISTS =
      Set.of("accounting_journal_one_per_package", "accounting_journal_version_unique");

  /** The package was voided (or left APPROVED) under the commit. */
  public static final Set<String> JOURNAL_SOURCE_STATE = Set.of("accounting_journal_source_state");

  /** The package, entity or mapping moved under the preview. */
  public static final Set<String> JOURNAL_STALE =
      Set.of(
          "accounting_journal_source_exact",
          "accounting_journal_totals_exact",
          "accounting_journal_entity_exact",
          "accounting_journal_mapping_exact");

  /** The lines do not balance (the domain refuses this first; the database says it again). */
  public static final Set<String> JOURNAL_UNBALANCED =
      Set.of("accounting_journal_balanced", "accounting_journal_lines_balanced");

  private AccountingConstraintViolations() {}

  /**
   * The stable business answer of a mapping constraint, if it has one.
   *
   * @param constraint a constraint name
   * @return the error code, or empty when the violation is a defect
   */
  public static Optional<ErrorCode> mappingAnswer(String constraint) {
    if (MAPPING_OVERLAP.contains(constraint)) {
      return Optional.of(ErrorCode.ACCOUNTING_MAPPING_OVERLAP);
    }
    if (MAPPING_LIMIT.contains(constraint)) {
      return Optional.of(ErrorCode.ACCOUNTING_MAPPING_LIMIT_REACHED);
    }
    if (MAPPING_STALE.contains(constraint)) {
      return Optional.of(ErrorCode.ACCOUNTING_MAPPING_PREVIEW_STALE);
    }
    return Optional.empty();
  }

  /**
   * The stable business answer of a journal constraint, if it has one.
   *
   * @param constraint a constraint name
   * @return the error code, or empty when the violation is a defect
   */
  public static Optional<ErrorCode> journalAnswer(String constraint) {
    if (JOURNAL_EXISTS.contains(constraint)) {
      return Optional.of(ErrorCode.ACCOUNTING_JOURNAL_ALREADY_EXISTS);
    }
    if (JOURNAL_SOURCE_STATE.contains(constraint)) {
      return Optional.of(ErrorCode.ACCOUNTING_JOURNAL_SOURCE_VOIDED);
    }
    if (JOURNAL_STALE.contains(constraint)) {
      return Optional.of(ErrorCode.ACCOUNTING_JOURNAL_PREVIEW_STALE);
    }
    if (JOURNAL_UNBALANCED.contains(constraint)) {
      return Optional.of(ErrorCode.ACCOUNTING_JOURNAL_UNBALANCED);
    }
    return Optional.empty();
  }
}
