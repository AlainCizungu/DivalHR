package com.divalhr.core.documents.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A new draft version (MVP-030). Mirrors {@code CreateContractTemplateVersion}. Values stay raw
 * JSON values until the service validates them; they are never logged.
 */
@Schema(name = "CreateContractTemplateVersion")
public class CreateContractTemplateVersionRequest extends StrictRequest {

  private Object locale;
  private Object title;
  private Object body;

  /**
   * Language code.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getLocale() {
    return locale;
  }

  /**
   * Sets: language code.
   *
   * @param locale raw value
   */
  public void setLocale(Object locale) {
    this.locale = locale;
  }

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
