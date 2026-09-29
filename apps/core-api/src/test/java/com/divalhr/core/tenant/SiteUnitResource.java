package com.divalhr.core.tenant;

/** Test-side description of the two site-child resources (MVP-002 Increment 2). */
enum SiteUnitResource {
  DEPARTMENT(
      "/api/v1/departments",
      "department",
      "tenant.department",
      "DUPLICATE_DEPARTMENT_CODE",
      "DEPARTMENT_PERIOD_OUTSIDE_SITE",
      "departmentId"),
  COST_CENTER(
      "/api/v1/cost-centers",
      "cost-center",
      "tenant.cost_center",
      "DUPLICATE_COST_CENTER_CODE",
      "COST_CENTER_PERIOD_OUTSIDE_SITE",
      "costCenterId");

  final String path;
  final String resource;
  final String table;
  final String duplicateCode;
  final String periodCode;
  final String idField;

  SiteUnitResource(
      String path,
      String resource,
      String table,
      String duplicateCode,
      String periodCode,
      String idField) {
    this.path = path;
    this.resource = resource;
    this.table = table;
    this.duplicateCode = duplicateCode;
    this.periodCode = periodCode;
    this.idField = idField;
  }

  String createOperation() {
    return resource + ".create";
  }

  String listOperation() {
    return resource + ".list";
  }

  String eventType() {
    return "tenant." + resource + "-created.v1";
  }

  /** The table name without schema, as PostgreSQL reports it in constraint names. */
  String bareTable() {
    return table.substring("tenant.".length());
  }
}
