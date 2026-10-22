package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-101: the briefing request. The only field is the requested language; tenant, actor, scope,
 * filters and data are never fields of the body. The value is kept raw so that the service answers
 * a stable {@code OPERATIONAL_SUMMARY_LANGUAGE_INVALID} for anything but exactly {@code en} or
 * {@code fr}; unknown properties are captured and rejected.
 */
@Schema(name = "GenerateOperationalSummaryRequest")
public class GenerateOperationalSummaryRequest extends StrictRequest {
  private Object language;

  /**
   * The requested language.
   *
   * @return raw value
   */
  @Schema(
      type = "string",
      allowableValues = {"en", "fr"})
  public Object getLanguage() {
    return language;
  }

  /**
   * Sets the raw language.
   *
   * @param language raw value
   */
  public void setLanguage(Object language) {
    this.language = language;
  }
}
