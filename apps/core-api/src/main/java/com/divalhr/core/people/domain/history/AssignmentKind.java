package com.divalhr.core.people.domain.history;

/** The independent effective-dated facts of an employment (MVP-021, H1). */
public enum AssignmentKind {
  /** Legal entity, site, department or cost center, team; always covers the employment. */
  PLACEMENT,
  /** The manager's employee; gaps mean no manager. */
  MANAGER,
  /** Descriptive contract classification; gaps mean not recorded. */
  CONTRACT,
  /** Compensation basis (never an amount); gaps mean not recorded. */
  COMPENSATION
}
