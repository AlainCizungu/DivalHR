package com.divalhr.core.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorrelationIdTest {

  @Test
  void acceptsSafeCallerValue() {
    assertThat(CorrelationId.resolve("req-12345678")).isEqualTo("req-12345678");
  }

  @Test
  void replacesMissingValue() {
    assertThat(UUID.fromString(CorrelationId.resolve(null))).isNotNull();
  }

  @Test
  void replacesUnsafeValuesSoTheyAreNeverLoggedOrEchoed() {
    String injected = "abc\r\nSet-Cookie: x=1";
    String resolved = CorrelationId.resolve(injected);
    assertThat(resolved).isNotEqualTo(injected);
    assertThat(UUID.fromString(resolved)).isNotNull();
    assertThat(CorrelationId.resolve("short")).isNotEqualTo("short");
    assertThat(CorrelationId.resolve("x".repeat(65))).hasSize(36);
  }
}
