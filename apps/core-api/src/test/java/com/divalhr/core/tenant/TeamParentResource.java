package com.divalhr.core.tenant;

/** The two team parent types, for tests parameterized over both. */
enum TeamParentResource {
  DEPARTMENT(
      "/api/v1/departments",
      "departmentId",
      "costCenterId",
      "department",
      "tenant.department",
      "DEPARTMENT_NOT_FOUND",
      "TEAM_PERIOD_OUTSIDE_DEPARTMENT",
      "department_id"),
  COST_CENTER(
      "/api/v1/cost-centers",
      "costCenterId",
      "departmentId",
      "cost-center",
      "tenant.cost_center",
      "COST_CENTER_NOT_FOUND",
      "TEAM_PERIOD_OUTSIDE_COST_CENTER",
      "cost_center_id");

  final String parentPath;
  final String field;
  final String otherField;
  final String parentType;
  final String parentTable;
  final String notFoundCode;
  final String periodCode;
  final String column;

  TeamParentResource(
      String parentPath,
      String field,
      String otherField,
      String parentType,
      String parentTable,
      String notFoundCode,
      String periodCode,
      String column) {
    this.parentPath = parentPath;
    this.field = field;
    this.otherField = otherField;
    this.parentType = parentType;
    this.parentTable = parentTable;
    this.notFoundCode = notFoundCode;
    this.periodCode = periodCode;
    this.column = column;
  }

  TeamParentResource other() {
    return this == DEPARTMENT ? COST_CENTER : DEPARTMENT;
  }

  /** The database constraint raised by the containment trigger for this parent. */
  String periodConstraint() {
    return this == DEPARTMENT ? "team_period_within_department" : "team_period_within_cost_center";
  }
}
