package com.divalhr.keycloak.provisioning;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extension configuration, from Keycloak SPI options ({@code
 * spi-realm-restapi-extension--divalhr-provisioning--<option>}). Validated at start-up: an invalid
 * value stops Keycloak (fail fast).
 *
 * @param realms realms where the extension answers; empty means nowhere (fail closed)
 * @param provisionerClientId the only client allowed to call
 * @param webClientId client of the setup-email action link
 * @param webRedirectUri redirect after the setup actions
 * @param actionLifespanSeconds validity of the setup-email link
 */
public record ProvisioningConfig(
    Set<String> realms,
    String provisionerClientId,
    String webClientId,
    String webRedirectUri,
    int actionLifespanSeconds) {

  /** Audience the caller's token must carry. */
  public static final String AUDIENCE = "divalhr-provisioning";

  /** Client holding the capability role (also the audience). */
  public static final String CAPABILITY_CLIENT = "divalhr-provisioning";

  /** The dedicated capability. */
  public static final String CAPABILITY_ROLE = "provision-invitations";

  /** Defensive copy and validation. */
  public ProvisioningConfig {
    realms = Set.copyOf(realms);
    require(provisionerClientId != null && !provisionerClientId.isBlank(), "provisioner-client-id");
    require(webClientId != null && !webClientId.isBlank(), "web-client-id");
    require(actionLifespanSeconds >= 60 && actionLifespanSeconds <= 7 * 24 * 3600, "lifespan");
    if (!realms.isEmpty()) {
      require(webRedirectUri != null, "web-redirect-uri");
      URI uri = URI.create(webRedirectUri);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      require(
          uri.isAbsolute()
              && uri.getHost() != null
              && uri.getRawUserInfo() == null
              && uri.getRawFragment() == null
              && (scheme.equals("https") || scheme.equals("http")),
          "web-redirect-uri");
    }
  }

  /**
   * Parses the comma-separated realm list.
   *
   * @param value option value, may be null
   * @return realm names
   */
  public static Set<String> parseRealms(String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(name -> !name.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static void require(boolean condition, String option) {
    if (!condition) {
      throw new IllegalStateException("divalhr-provisioning: invalid option " + option);
    }
  }
}
