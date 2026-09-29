package com.divalhr.core.tenant.internal;

import com.divalhr.core.tenant.domain.SiteUnitKind;

/**
 * Persistence identifiers for each site-child kind. SQL identifiers are compile-time constants
 * selected only by the closed {@link SiteUnitKind} enum, never by request data.
 */
enum SiteUnitTable {
  DEPARTMENT("tenant.department", "department_code_ci_unique"),
  COST_CENTER("tenant.cost_center", "cost_center_code_ci_unique");

  private final String table;
  private final String codeConstraint;

  SiteUnitTable(String table, String codeConstraint) {
    this.table = table;
    this.codeConstraint = codeConstraint;
  }

  String table() {
    return table;
  }

  String codeConstraint() {
    return codeConstraint;
  }

  static SiteUnitTable of(SiteUnitKind kind) {
    return switch (kind) {
      case DEPARTMENT -> DEPARTMENT;
      case COST_CENTER -> COST_CENTER;
    };
  }
}
