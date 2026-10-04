package com.divalhr.core.people.domain.separation;

/** What happens to every affected direct-report interval (D22-9). */
public enum ReportAction {
  /** A replacement manager takes the interval. */
  REASSIGN,
  /** The interval has no manager. */
  CLEAR
}
