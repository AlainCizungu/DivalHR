package com.divalhr.core.tenant.domain;

/**
 * The closed set of team parent types (MVP-002 Increment 3B). Carries only the parent relationship:
 * a team belongs to exactly one department or exactly one cost center. Create operations, event
 * types and persistence details of the parents stay in their own adapters.
 */
public enum TeamParentKind {
  /** The team belongs to a department. */
  DEPARTMENT("department"),
  /** The team belongs to a cost center. */
  COST_CENTER("cost-center");

  private final String wireName;

  TeamParentKind(String wireName) {
    this.wireName = wireName;
  }

  /**
   * Stable name used for {@code parentType} in audit metadata, event data and cursor scopes.
   *
   * @return {@code department} or {@code cost-center}
   */
  public String wireName() {
    return wireName;
  }
}
