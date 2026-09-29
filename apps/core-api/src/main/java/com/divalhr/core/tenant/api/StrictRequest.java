package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import java.util.TreeSet;

/**
 * Captures (names of) properties that are not in the contract so validators can reject them.
 * Callers can never set ids, tenants, organizations or authors through the body.
 */
public abstract class StrictRequest {

  private final Set<String> unknownProperties = new TreeSet<>();

  @JsonAnySetter
  void captureUnknown(String property, Object ignoredValue) {
    unknownProperties.add(property);
  }

  /**
   * Names of properties not in the contract (never echoed to clients or logs).
   *
   * @return unknown property names
   */
  @Schema(hidden = true)
  @JsonIgnore
  public Set<String> unknownProperties() {
    return Set.copyOf(unknownProperties);
  }
}
