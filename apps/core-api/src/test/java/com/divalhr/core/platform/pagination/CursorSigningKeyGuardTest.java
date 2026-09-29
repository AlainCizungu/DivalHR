package com.divalhr.core.platform.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CursorSigningKeyGuardTest {

  private static final String DEV_KEY = "dev-only-cursor-signing-key-not-a-secret-0001";
  private static final String REAL_KEY = "unit-test-non-placeholder-key-000001";

  @Test
  void failsClosedWhenMissingOrShort() {
    for (String env : new String[] {"development", "test", "staging", "production"}) {
      assertThatThrownBy(() -> CursorSigningKeyGuard.validate(env, null))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> CursorSigningKeyGuard.validate(env, "   "))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () -> CursorSigningKeyGuard.validate(env, "short-key-31-bytes-long-0000000"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("short-key");
    }
  }

  @Test
  void developmentPlaceholderIsAcceptedOnlyInDevelopment() {
    assertThat(CursorSigningKeyGuard.validate("development", DEV_KEY)).hasSizeGreaterThan(31);
    for (String env : new String[] {"test", "staging", "production", "", "Production"}) {
      assertThatThrownBy(() -> CursorSigningKeyGuard.validate(env, DEV_KEY))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining(DEV_KEY);
      assertThatThrownBy(
              () -> CursorSigningKeyGuard.validate(env, DEV_KEY.toUpperCase(java.util.Locale.ROOT)))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void acceptsSufficientlyLongNonPlaceholderKeys() {
    assertThat(CursorSigningKeyGuard.validate("production", REAL_KEY)).hasSize(REAL_KEY.length());
  }

  @Test
  void examplePlaceholderIsTheDevelopmentValue() throws Exception {
    String example =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(System.getProperty("divalhr.repoRoot", "../.."), ".env.example"));
    assertThat(example).contains("DIVALHR_CURSOR_SIGNING_KEY=" + DEV_KEY);
  }
}
