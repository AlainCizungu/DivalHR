package com.divalhr.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** MVP-011 A3: only a scalar {@code acr} string exactly equal to the MFA level proves MFA. */
class AssuranceEvidenceTest {

  @Test
  void acceptsOnlyTheExactScalarValue() {
    assertThat(AssuranceEvidence.provesMfa(jwt("urn:divalhr:loa:mfa"))).isTrue();
    for (Object acr :
        List.of(
            "urn:divalhr:loa:pwd",
            "URN:DIVALHR:LOA:MFA",
            "urn:divalhr:loa:mfa\u0000",
            "1",
            "2",
            true,
            2L,
            List.of("urn:divalhr:loa:mfa"))) {
      assertThat(AssuranceEvidence.provesMfa(jwt(acr))).as("acr %s", acr).isFalse();
    }
    assertThat(AssuranceEvidence.provesMfa(jwt(null))).isFalse();
    assertThat(AssuranceEvidence.provesMfa(null)).isFalse();
  }

  @Test
  void onlyPrivilegedRolesRequireIt() {
    assertThat(AssuranceEvidence.requiredFor("platform-admin")).isTrue();
    assertThat(AssuranceEvidence.requiredFor("tenant-admin")).isTrue();
    assertThat(AssuranceEvidence.requiredFor("employee")).isFalse();
    assertThat(AssuranceEvidence.requiredFor("divalhr-privileged-mfa")).isFalse();
    assertThat(AssuranceEvidence.requiredFor("")).isFalse();
  }

  private static Jwt jwt(Object acr) {
    Jwt.Builder builder =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("s")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
    if (acr != null) {
      builder.claim("acr", acr);
    }
    return builder.build();
  }
}
