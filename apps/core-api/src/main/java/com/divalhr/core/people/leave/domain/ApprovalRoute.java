package com.divalhr.core.people.leave.domain;

/** Who approves requests under a leave policy (MVP-040A; enforced from MVP-041B). */
public enum ApprovalRoute {
  /** The employee's manager. */
  MANAGER,
  /** A tenant administrator. */
  TENANT_ADMIN
}
