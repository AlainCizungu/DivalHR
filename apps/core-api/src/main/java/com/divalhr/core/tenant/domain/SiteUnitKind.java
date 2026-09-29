package com.divalhr.core.tenant.domain;

/**
 * The two kinds of site child (MVP-002 Increment 2). Carries domain-level semantics only: operation
 * names, event types and the audit resource type. Persistence identifiers live in the persistence
 * adapter ({@code tenant.internal}).
 */
public enum SiteUnitKind {
  /** A department of a site. */
  DEPARTMENT("department", "departmentId"),
  /** A cost center of a site. */
  COST_CENTER("cost-center", "costCenterId");

  private final String resource;
  private final String idField;

  SiteUnitKind(String resource, String idField) {
    this.resource = resource;
    this.idField = idField;
  }

  /**
   * Audit resource type and operation prefix, e.g. {@code cost-center}.
   *
   * @return resource name
   */
  public String resource() {
    return resource;
  }

  /**
   * Idempotency, audit and metric operation for creation.
   *
   * @return e.g. {@code department.create}
   */
  public String createOperation() {
    return resource + ".create";
  }

  /**
   * Metric and cursor operation for listing.
   *
   * @return e.g. {@code department.list}
   */
  public String listOperation() {
    return resource + ".list";
  }

  /**
   * Outbox event type for creation.
   *
   * @return e.g. {@code tenant.department-created.v1}
   */
  public String createdEventType() {
    return "tenant." + resource + "-created.v1";
  }

  /**
   * Name of the created entity's id in event data.
   *
   * @return {@code departmentId} or {@code costCenterId}
   */
  public String idField() {
    return idField;
  }
}
