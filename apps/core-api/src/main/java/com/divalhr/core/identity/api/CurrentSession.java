package com.divalhr.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Minimal, non-personal view of the caller's session. Mirrors {@code CurrentSession} in {@code
 * docs/API-SPEC.yaml}.
 *
 * @param tenantId verified tenant; null only for a tenantless platform administrator
 * @param roles effective roles
 */
public record CurrentSession(
    @JsonInclude(JsonInclude.Include.ALWAYS) String tenantId, List<String> roles) {

  /** Defensively copies roles so the record is immutable. */
  public CurrentSession {
    roles = List.copyOf(roles);
  }
}
