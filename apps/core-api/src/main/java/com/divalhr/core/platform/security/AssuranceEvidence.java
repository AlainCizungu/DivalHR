package com.divalhr.core.platform.security;

import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The only evidence of multifactor authentication the Core API accepts (MVP-011, Issue #29).
 *
 * <p>Keycloak's Level of Authentication (LoA) flow issues {@code acr = urn:divalhr:loa:mfa} in the
 * access token only after the privileged user completed the level-2 OTP step in this SSO session.
 * The claim is part of the signature-verified JWT and computed by Keycloak from the session's
 * achieved level; it is never user-editable. Nothing else counts: not a role (including the
 * internal marker role), not {@code amr}, not enrollment state or required actions, and never a
 * request header or front-end flag.
 */
public final class AssuranceEvidence {

  /** The exact {@code acr} value that proves MFA for the current session. */
  public static final String MFA_ACR = "urn:divalhr:loa:mfa";

  /** Authority granted only for {@link #MFA_ACR}; used by method security as a second layer. */
  public static final String MFA_AUTHORITY = "ASSURANCE_MFA";

  /** Roles whose operations require {@link #MFA_ACR}. */
  public static final Set<String> PRIVILEGED_ROLES = Set.of("platform-admin", "tenant-admin");

  private AssuranceEvidence() {}

  /**
   * Whether the verified token proves MFA: its {@code acr} claim is a scalar string exactly equal
   * to {@link #MFA_ACR}. Missing, differently cased, array, numeric and other values fail closed.
   *
   * @param jwt verified access token
   * @return true only for the exact value
   */
  public static boolean provesMfa(Jwt jwt) {
    return jwt != null && jwt.getClaims().get("acr") instanceof String acr && MFA_ACR.equals(acr);
  }

  /**
   * Whether operations requiring the role also require MFA.
   *
   * @param role required role (may be empty)
   * @return true for platform-admin and tenant-admin
   */
  public static boolean requiredFor(String role) {
    return PRIVILEGED_ROLES.contains(role);
  }
}
