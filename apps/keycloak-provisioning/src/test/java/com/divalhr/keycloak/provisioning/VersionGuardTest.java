package com.divalhr.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VersionGuardTest {

  @Test
  void theBuildMetadataNamesTheCompiledKeycloakVersion() {
    String expected = System.getProperty("divalhr.keycloakVersion", "26.7.4");
    assertThat(VersionGuard.builtFor()).isEqualTo(expected);
  }

  @Test
  void anyOtherRunningVersionIsRefused() {
    VersionGuard.require("26.7.4", "26.7.4");
    for (String running : new String[] {"26.7.5", "26.7.4-SNAPSHOT", "27.0.0", "", null}) {
      assertThatThrownBy(() -> VersionGuard.require("26.7.4", running))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("refuses to run");
    }
  }
}
