package com.divalhr.core.identity.domain;

import java.util.Optional;

/**
 * A tenant-scoped role an invitation may assign (MVP-010). Closed on purpose: there is no member
 * for {@code platform-admin}, so no code path can ever assign it through a tenant invitation.
 */
public enum TenantRole {
  /** Tenant administrator. */
  TENANT_ADMIN("tenant-admin"),
  /** Employee. */
  EMPLOYEE("employee");

  private final String wireName;

  TenantRole(String wireName) {
    this.wireName = wireName;
  }

  /**
   * The contract value.
   *
   * @return {@code tenant-admin} or {@code employee}
   */
  public String wireName() {
    return wireName;
  }

  /**
   * Parses a contract value; anything else (including {@code platform-admin}) is empty.
   *
   * @param raw submitted value
   * @return the role, or empty
   */
  public static Optional<TenantRole> fromWire(String raw) {
    for (TenantRole role : values()) {
      if (role.wireName.equals(raw)) {
        return Optional.of(role);
      }
    }
    return Optional.empty();
  }
}
