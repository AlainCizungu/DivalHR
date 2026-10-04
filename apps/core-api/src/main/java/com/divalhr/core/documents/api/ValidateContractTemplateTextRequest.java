package com.divalhr.core.documents.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Template text to validate without writing (MVP-030). Mirrors {@code
 * ValidateContractTemplateText}. Values stay raw JSON values until the service validates them; they
 * are never logged.
 */
@Schema(name = "ValidateContractTemplateText")
public class ValidateContractTemplateTextRequest extends StrictRequest {

  private Object title;
  private Object body;

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
}
