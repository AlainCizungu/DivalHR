package com.divalhr.core.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code removeEmployeeAccessLink} (MVP-022): exactly {@code {"linkId",
 * "expectedVersion"}}. Raw values are validated by the service.
 */
@Schema(name = "RemoveEmployeeAccessLink")
public class RemoveEmployeeAccessLinkRequest extends StrictRequest {

  private Object linkId;
  private Object expectedVersion;

  /**
   * The link ID as submitted.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getLinkId() {
    return linkId;
  }

  /**
   * Sets the link ID.
   *
   * @param linkId raw value
   */
  public void setLinkId(Object linkId) {
    this.linkId = linkId;
  }

  /**
   * The expected link version as submitted.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedVersion() {
    return expectedVersion;
  }

  /**
   * Sets the expected link version.
   *
   * @param expectedVersion raw value
   */
  public void setExpectedVersion(Object expectedVersion) {
    this.expectedVersion = expectedVersion;
  }
}
