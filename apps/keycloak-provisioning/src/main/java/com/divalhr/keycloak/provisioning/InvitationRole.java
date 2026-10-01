package com.divalhr.keycloak.provisioning;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The two tenant roles an invitation can carry, with their closed mapping to a role group, the
 * credentials the role needs and the required actions that set them up. There is deliberately no
 * value for {@code platform-admin} or the MFA marker role.
 */
public enum InvitationRole {
  /** Password only. */
  EMPLOYEE("employee", "divalhr-role-employee", List.of(Setup.PASSWORD)),
  /** Password and an authenticator app (MVP-011). */
  TENANT_ADMIN("tenant-admin", "divalhr-role-tenant-admin", List.of(Setup.PASSWORD, Setup.OTP));

  /** A credential the role needs and the required action that creates it. */
  public enum Setup {
    /** Password credential, set by {@code UPDATE_PASSWORD}. */
    PASSWORD("password", "UPDATE_PASSWORD"),
    /** TOTP credential, set by {@code CONFIGURE_TOTP}. */
    OTP("otp", "CONFIGURE_TOTP");

    private final String credentialType;
    private final String requiredAction;

    Setup(String credentialType, String requiredAction) {
      this.credentialType = credentialType;
      this.requiredAction = requiredAction;
    }

    /**
     * Keycloak credential type.
     *
     * @return type
     */
    public String credentialType() {
      return credentialType;
    }

    /**
     * Keycloak required action alias.
     *
     * @return alias
     */
    public String requiredAction() {
      return requiredAction;
    }
  }

  private final String wireName;
  private final String groupName;
  private final List<Setup> setups;

  InvitationRole(String wireName, String groupName, List<Setup> setups) {
    this.wireName = wireName;
    this.groupName = groupName;
    this.setups = List.copyOf(setups);
  }

  /**
   * Parses the contract value; anything else is absent.
   *
   * @param value wire value
   * @return the role, if allowed
   */
  public static Optional<InvitationRole> fromWire(String value) {
    for (InvitationRole role : values()) {
      if (role.wireName.equals(value)) {
        return Optional.of(role);
      }
    }
    return Optional.empty();
  }

  /**
   * The role whose group has this name.
   *
   * @param groupName top-level group name
   * @return the role, if the group is a role group
   */
  public static Optional<InvitationRole> fromGroup(String groupName) {
    for (InvitationRole role : values()) {
      if (role.groupName.equals(groupName)) {
        return Optional.of(role);
      }
    }
    return Optional.empty();
  }

  /**
   * Contract value.
   *
   * @return wire name
   */
  public String wireName() {
    return wireName;
  }

  /**
   * Top-level role group carrying the realm role.
   *
   * @return group name
   */
  public String groupName() {
    return groupName;
  }

  /**
   * Credentials the role needs, in setup order.
   *
   * @return setups
   */
  public List<Setup> setups() {
    return List.copyOf(setups);
  }

  /**
   * Required actions a new identity of this role starts with.
   *
   * @return required action aliases, in setup order
   */
  public List<String> requiredActions() {
    return setups.stream().map(Setup::requiredAction).toList();
  }

  /**
   * Credential types the role may hold.
   *
   * @return types
   */
  public Set<String> credentialTypes() {
    return Set.copyOf(setups.stream().map(Setup::credentialType).toList());
  }
}
