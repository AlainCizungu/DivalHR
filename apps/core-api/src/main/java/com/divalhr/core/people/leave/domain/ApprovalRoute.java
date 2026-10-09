package com.divalhr.core.people.leave.domain;

/** Who will approve requests under a leave policy once requests exist (MVP-040A). */
public enum ApprovalRoute {
  /** The employee's manager. */
  MANAGER,
  /** A tenant administrator. */
  TENANT_ADMIN
}
