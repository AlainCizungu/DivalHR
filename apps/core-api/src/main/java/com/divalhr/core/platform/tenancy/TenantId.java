package com.divalhr.core.platform.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable tenant identifier.
 *
 * @param value tenant UUID
 */
public record TenantId(UUID value) {

  /** Requires a value. */
  public TenantId {
    Objects.requireNonNull(value, "tenant id");
  }

  /**
   * Parses a tenant ID.
   *
   * @param raw textual UUID
   * @return the tenant ID
   * @throws IllegalArgumentException if the value is not a UUID
   */
  public static TenantId parse(String raw) {
    return new TenantId(UUID.fromString(raw));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
