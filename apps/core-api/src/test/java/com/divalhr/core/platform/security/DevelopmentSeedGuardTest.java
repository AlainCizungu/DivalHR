package com.divalhr.core.platform.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DevelopmentSeedGuardTest {

  private static final String DEV_ISSUER = "http://localhost:8180/realms/divalhr-dev";

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
  }

  @Test
  void rejectsUnknownEnvironment() {
    assertThatThrownBy(() -> DevelopmentSeedGuard.check("prod", "https://id.divalhr.com/realms/x"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void allowsRealRealmInProduction() {
    assertThatCode(
            () -> DevelopmentSeedGuard.check("production", "https://id.divalhr.com/realms/divalhr"))
        .doesNotThrowAnyException();
  }
}
