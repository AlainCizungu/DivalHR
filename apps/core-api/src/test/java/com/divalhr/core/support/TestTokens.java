package com.divalhr.core.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mints signed test access tokens. Test-only keys; never used outside tests. */
public final class TestTokens {

  /** Issuer configured for tests. */
  public static final String ISSUER = "http://localhost:8180/realms/divalhr-dev";

  /** Audience configured for tests. */
  public static final String AUDIENCE = "divalhr-core-api";

  /** Tenant A (test-only). */
  public static final UUID TENANT_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");

  /** Tenant B (test-only). */
  public static final UUID TENANT_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");

  private static final RSAKey KEY = generate();

  private TestTokens() {}

  /**
   * Public key matching the signing key.
   *
   * @return RSA public key
   */
  public static RSAPublicKey publicKey() {
    try {
      return KEY.toRSAPublicKey();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Builder for a valid token that tests can then break in one dimension.
   *
   * @return a builder
   */
  public static Builder token() {
    return new Builder();
  }

  private static RSAKey generate() {
    try {
      return new RSAKeyGenerator(2048).keyID("test-key").generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Fluent token builder. */
  public static final class Builder {
    private String issuer = ISSUER;
    private String audience = AUDIENCE;
    private UUID tenant = TENANT_A;
    private List<String> roles = List.of("employee");
    private Instant expiresAt = Instant.now().plusSeconds(300);

    /**
     * Sets the issuer.
     *
     * @param value issuer
     * @return this builder
     */
    public Builder issuer(String value) {
      this.issuer = value;
      return this;
    }

    /**
     * Sets the audience.
     *
     * @param value audience
     * @return this builder
     */
    public Builder audience(String value) {
      this.audience = value;
      return this;
    }

    /**
     * Sets the tenant claim; {@code null} omits it.
     *
     * @param value tenant
     * @return this builder
     */
    public Builder tenant(UUID value) {
      this.tenant = value;
      return this;
    }

    /**
     * Sets realm roles.
     *
     * @param value roles
     * @return this builder
     */
    public Builder roles(List<String> value) {
      this.roles = List.copyOf(value);
      return this;
    }

    /**
     * Sets expiry.
     *
     * @param value expiry
     * @return this builder
     */
    public Builder expiresAt(Instant value) {
      this.expiresAt = value;
      return this;
    }

    /**
     * Signs the token.
     *
     * @return compact serialized JWT
     */
    public String build() {
      JWTClaimsSet.Builder claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .audience(audience)
              .subject(UUID.randomUUID().toString())
              .issueTime(Date.from(Instant.now().minusSeconds(5)))
              .expirationTime(Date.from(expiresAt))
              .claim("realm_access", Map.of("roles", roles));
      if (tenant != null) {
        claims.claim("tenant_id", tenant.toString());
      }
      try {
        SignedJWT jwt =
            new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                claims.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
      } catch (JOSEException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
