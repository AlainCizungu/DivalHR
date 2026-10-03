package com.divalhr.core.people.domain;

/** Lifecycle of an employee import (MVP-020): only {@code VALIDATED} imports are open. */
public enum ImportStatus {
  /** Uploaded and staged; can be committed or discarded until it expires. */
  VALIDATED,
  /** The valid rows were created. */
  COMMITTED,
  /** Discarded by an administrator; staged values erased. */
  DISCARDED,
  /** Not committed in time; staged values erased. */
  EXPIRED
}
