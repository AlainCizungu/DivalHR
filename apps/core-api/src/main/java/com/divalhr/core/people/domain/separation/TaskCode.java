package com.divalhr.core.people.domain.separation;

/** Follow-up reminders created with every separation (D22-15). */
public enum TaskCode {
  /** Return assigned assets: a reminder, never an asset record. */
  RETURN_ASSIGNED_ASSETS,
  /** Collect or archive documents: a reminder, never a document record. */
  COLLECT_OR_ARCHIVE_DOCUMENTS
}
