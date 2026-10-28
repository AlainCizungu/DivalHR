package com.divalhr.core.operations.domain;

/** The closed expense categories (MVP-081, D81-3). */
public enum ExpenseCategory {
  /** Transport. */
  TRANSPORT,
  /** Meals. */
  MEALS,
  /** Lodging. */
  LODGING,
  /** Supplies. */
  SUPPLIES,
  /** Communication. */
  COMMUNICATION,
  /** Training. */
  TRAINING,
  /** Work-related medical expense (a category label only, never a diagnosis). */
  MEDICAL_WORK_RELATED,
  /** Anything else. */
  OTHER
}
