package com.divalhr.core.platform.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DevelopmentSeedGuardTest {

  private static final String DEV_ISSUER = "http://localhost:8180/realms/divalhr-dev";
  private static final String REAL_ISSUER = "https://id.divalhr.com/realms/divalhr";

  @Test
  void allowsDevelopmentRealmInDevelopmentAndTest() {
    assertThatCode(() -> DevelopmentSeedGuard.check("development", DEV_ISSUER))
        .doesNotThrowAnyException();
    assertThatCode(() -> DevelopmentSeedGuard.check("test", DEV_ISSUER)).doesNotThrowAnyException();
  }

  @Test
  void rejectsDevelopmentRealmInSharedEnvironments() {
    assertThatThrownBy(() -> DevelopmentSeedGuard.check("staging", DEV_ISSUER))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> DevelopmentSeedGuard.check("production", DEV_ISSUER))
        .isInstanceOf(IllegalStateException.class);
    // Even over https, the development realm is never trusted outside development and test.
    assertThatThrownBy(
            () ->
                DevelopmentSeedGuard.check(
                    "production", "https://id.divalhr.com/realms/divalhr-dev"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("divalhr-dev");
  }

  @Test
  void rejectsUnknownEnvironment() {
    assertThatThrownBy(() -> DevelopmentSeedGuard.check("prod", REAL_ISSUER))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void allowsRealRealmInProduction() {
    assertThatCode(() -> DevelopmentSeedGuard.check("production", REAL_ISSUER))
        .doesNotThrowAnyException();
    assertThatCode(() -> DevelopmentSeedGuard.check("staging", REAL_ISSUER))
        .doesNotThrowAnyException();
    // The scheme is compared after normalization, so an upper-case HTTPS is accepted.
    assertThatCode(
            () -> DevelopmentSeedGuard.check("production", "HTTPS://id.divalhr.com/realms/divalhr"))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"staging", "production"})
  void sharedEnvironmentsRequireAnHttpsIssuer(String environment) {
    for (String issuer :
        new String[] {
          "http://id.divalhr.com/realms/divalhr",
          "HTTP://id.divalhr.com/realms/divalhr",
          // A prefix check would accept these; the parsed scheme does not.
          "httpsx://id.divalhr.com/realms/divalhr",
          "http://https.divalhr.com/realms/divalhr",
        }) {
      assertThatThrownBy(() -> DevelopmentSeedGuard.check(environment, issuer))
          .as(issuer)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining(issuer);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"development", "test", "staging", "production"})
  void malformedRelativeOrUserInfoIssuersAreRejectedEverywhere(String environment) {
    for (String issuer :
        new String[] {
          "",
          " https://id.divalhr.com/realms/divalhr",
          "https://id divalhr.com/realms/divalhr",
          "/realms/divalhr",
          "id.divalhr.com/realms/divalhr",
          "https:id.divalhr.com/realms/divalhr",
          "https:///realms/divalhr",
          "https://admin:secret@id.divalhr.com/realms/divalhr",
          "https://id.divalhr.com/realms/divalhr?x=1",
          "https://id.divalhr.com/realms/divalhr#fragment",
          "ftp://id.divalhr.com/realms/divalhr",
        }) {
      assertThatThrownBy(() -> DevelopmentSeedGuard.check(environment, issuer))
          .as("[%s]", issuer)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("secret");
    }
  }

  @Test
  void aMissingIssuerIsRejected() {
    assertThatThrownBy(() -> DevelopmentSeedGuard.check("development", null))
        .isInstanceOf(IllegalStateException.class);
  }
}
