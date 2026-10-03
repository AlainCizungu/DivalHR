package com.divalhr.core.people.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Commit command (MVP-020): repeats the reviewed preview. Mirrors {@code CommitEmployeeImport}.
 * Values are validated by the service; unknown properties are rejected.
 */
@Schema(name = "CommitEmployeeImport")
public class CommitEmployeeImportRequest extends StrictRequest {

  private Object previewDigest;
  private Object validRows;
  private Object acknowledgeInvalidRows;

  /**
   * Preview digest.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getPreviewDigest() {
    return previewDigest;
  }

  /**
   * Sets the preview digest.
   *
   * @param previewDigest raw value
   */
  public void setPreviewDigest(Object previewDigest) {
    this.previewDigest = previewDigest;
  }

  /**
   * Valid rows the administrator reviewed.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getValidRows() {
    return validRows;
  }

  /**
   * Sets the valid row count.
   *
   * @param validRows raw value
   */
  public void setValidRows(Object validRows) {
    this.validRows = validRows;
  }

  /**
   * Whether invalid rows are knowingly left out.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getAcknowledgeInvalidRows() {
    return acknowledgeInvalidRows;
  }

  /**
   * Sets the acknowledgement.
   *
   * @param acknowledgeInvalidRows raw value
   */
  public void setAcknowledgeInvalidRows(Object acknowledgeInvalidRows) {
    this.acknowledgeInvalidRows = acknowledgeInvalidRows;
  }
}
