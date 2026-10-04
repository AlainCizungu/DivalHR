package com.divalhr.core.documents.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A draft version's new text (MVP-030). Mirrors {@code UpdateContractTemplateVersion}. Values stay
 * raw JSON values until the service validates them; they are never logged.
 */
@Schema(name = "UpdateContractTemplateVersion")
public class UpdateContractTemplateVersionRequest extends StrictRequest {

  private Object title;
  private Object body;
  private Object expectedVersion;

  /**
   * Title.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getTitle() {
    return title;
  }

  /**
   * Sets: title.
   *
   * @param title raw value
   */
  public void setTitle(Object title) {
    this.title = title;
  }

  /**
   * Grammar v1 body.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getBody() {
    return body;
  }

  /**
   * Sets: grammar v1 body.
   *
   * @param body raw value
   */
  public void setBody(Object body) {
    this.body = body;
  }

  /**
   * Expected row version.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedVersion() {
    return expectedVersion;
  }

  /**
   * Sets: expected row version.
   *
   * @param expectedVersion raw value
   */
  public void setExpectedVersion(Object expectedVersion) {
    this.expectedVersion = expectedVersion;
  }
}
