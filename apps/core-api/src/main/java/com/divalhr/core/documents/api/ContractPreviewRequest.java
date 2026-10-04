package com.divalhr.core.documents.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A contract to preview (MVP-030). Mirrors {@code ContractPreviewCommand}. Values stay raw JSON
 * values until the service validates them; they are never logged.
 */
@Schema(name = "ContractPreviewCommand")
public class ContractPreviewRequest extends StrictRequest {

  private Object templateVersionId;
  private Object startDate;
  private Object endDate;

  /**
   * Approved template version.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getTemplateVersionId() {
    return templateVersionId;
  }

  /**
   * Sets: approved template version.
   *
   * @param templateVersionId raw value
   */
  public void setTemplateVersionId(Object templateVersionId) {
    this.templateVersionId = templateVersionId;
  }

  /**
   * Contract start (ISO date).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getStartDate() {
    return startDate;
  }

  /**
   * Sets: contract start (ISO date).
   *
   * @param startDate raw value
   */
  public void setStartDate(Object startDate) {
    this.startDate = startDate;
  }

  /**
   * Contract end (ISO date), or null.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEndDate() {
    return endDate;
  }

  /**
   * Sets: contract end (ISO date), or null.
   *
   * @param endDate raw value
   */
  public void setEndDate(Object endDate) {
    this.endDate = endDate;
  }
}
