package com.divalhr.core.documents.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Filters and position of the contract expiration queue (MVP-031A, Issue #73). Every field is
 * optional. Values are kept raw so the service rejects wrong types field by field. The search text
 * is never logged.
 */
@Schema(name = "ContractExpirationSearch")
public class ContractExpirationSearchRequest extends StrictRequest {

  private Object query;
  private Object unitId;
  private Object categories;
  private Object cursor;
  private Object limit;

  @Schema(type = "string")
  public Object getQuery() {
    return query;
  }

  public void setQuery(Object query) {
    this.query = query;
  }

  @Schema(type = "string", format = "uuid")
  public Object getUnitId() {
    return unitId;
  }

  public void setUnitId(Object unitId) {
    this.unitId = unitId;
  }

  @Schema(type = "array")
  public Object getCategories() {
    return categories;
  }

  public void setCategories(Object categories) {
    this.categories = categories;
  }

  @Schema(type = "string")
  public Object getCursor() {
    return cursor;
  }

  public void setCursor(Object cursor) {
    this.cursor = cursor;
  }

  @Schema(type = "integer")
  public Object getLimit() {
    return limit;
  }

  public void setLimit(Object limit) {
    this.limit = limit;
  }

  @Override
  public String toString() {
    return "ContractExpirationSearchRequest[redacted]";
  }
}
