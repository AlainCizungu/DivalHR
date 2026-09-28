package com.divalhr.core.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CorsPropertiesTest {

  @Test
  void rejectsWildcardOrigins() {
    assertThatThrownBy(() -> new CorsProperties(List.of("*")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CorsProperties(List.of("https://*.divalhr.com")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsExplicitOrigins() {
    assertThat(new CorsProperties(List.of("http://localhost:5173")).allowedOrigins())
        .containsExactly("http://localhost:5173");
  }
}
