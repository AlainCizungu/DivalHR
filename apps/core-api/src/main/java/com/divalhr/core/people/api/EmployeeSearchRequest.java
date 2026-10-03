package com.divalhr.core.people.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Employee search (MVP-021). Mirrors {@code EmployeeSearch}. The query is never logged, audited or
 * echoed.
 */
@Schema(name = "EmployeeSearch")
public class EmployeeSearchRequest extends StrictRequest {

  private Object query;
  private Object cursor;
  private Object limit;

  /**
   * Employee-number prefix or name words.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getQuery() {
    return query;
  }

  /**
   * Sets: employee-number prefix or name words.
   *
   * @param query raw value
   */
  public void setQuery(Object query) {
    this.query = query;
  }

  /**
   * Continuation, or null.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCursor() {
    return cursor;
  }

  /**
   * Sets: continuation, or null.
   *
   * @param cursor raw value
   */
  public void setCursor(Object cursor) {
    this.cursor = cursor;
  }

  /**
   * Page size, or null.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getLimit() {
    return limit;
  }

  /**
   * Sets: page size, or null.
   *
   * @param limit raw value
   */
  public void setLimit(Object limit) {
    this.limit = limit;
  }
}
