package com.divalhr.core.payroll.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import java.util.TreeSet;

/**
 * A request body that records the properties it does not know (the contract declares {@code
 * additionalProperties: false}); validation turns them into one field error instead of ignoring
 * them silently (for example a tenant in the body).
 */
public abstract class StrictRequest {

  private final Set<String> unknownProperties = new TreeSet<>();

  @JsonAnySetter
  void captureUnknown(String property, Object ignoredValue) {
    unknownProperties.add(property);
  }

  /**
   * The unknown properties the body carried.
   *
   * @return property names
   */
  @Schema(hidden = true)
  @JsonIgnore
  public Set<String> unknownProperties() {
    return Set.copyOf(unknownProperties);
  }
}
