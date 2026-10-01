package com.divalhr.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ProvisioningConfigTest {

  @Test
  void realmsAreExplicitAndEmptyMeansNowhere() {
    assertThat(ProvisioningConfig.parseRealms(null)).isEmpty();
    assertThat(ProvisioningConfig.parseRealms(" divalhr-dev , prod ,"))
        .containsExactlyInAnyOrder("divalhr-dev", "prod");
    new ProvisioningConfig(Set.of(), "p", "w", null, 3600);
  }

  @Test
  void invalidOptionsStopStartUp() {
    assertThatThrownBy(() -> new ProvisioningConfig(Set.of("r"), "p", "w", null, 3600))
        .hasMessageContaining("web-redirect-uri");
    assertThatThrownBy(
            () -> new ProvisioningConfig(Set.of("r"), "p", "w", "javascript:alert(1)", 3600))
        .hasMessageContaining("web-redirect-uri");
    assertThatThrownBy(
            () -> new ProvisioningConfig(Set.of("r"), "p", "w", "https://u:p@x.test/cb", 3600))
        .hasMessageContaining("web-redirect-uri");
    assertThatThrownBy(
            () -> new ProvisioningConfig(Set.of("r"), "p", "w", "https://x.test/cb", 8 * 24 * 3600))
        .hasMessageContaining("lifespan");
    assertThatThrownBy(
            () -> new ProvisioningConfig(Set.of("r"), "", "w", "https://x.test/cb", 3600))
        .hasMessageContaining("provisioner-client-id");
    new ProvisioningConfig(Set.of("r"), "p", "w", "https://x.test/cb", 3600);
  }
}
