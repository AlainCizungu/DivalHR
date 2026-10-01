package com.divalhr.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Issue #31 (ADR 0006): the extension uses internal Keycloak APIs, so the Keycloak image, the image
 * the container tests run and the version the extension compiles against must be the same release.
 * A Keycloak bump that changes only one of them fails here (and the extension's start-up guard
 * would refuse to run).
 */
class KeycloakImageConsistencyTest {

  private static final Path ROOT = Path.of(System.getProperty("divalhr.repoRoot", "../.."));

  @Test
  void theImageTheTestsAndTheExtensionUseOneKeycloakRelease() throws Exception {
    String dockerfile = Files.readString(ROOT.resolve("infrastructure/docker/keycloak/Dockerfile"));
    Matcher from =
        Pattern.compile("(?m)^FROM (quay\\.io/keycloak/keycloak:([0-9.]+)@sha256:[0-9a-f]{64})$")
            .matcher(dockerfile);
    assertThat(from.find()).as("pinned Keycloak base image").isTrue();
    String image = from.group(1);
    String version = from.group(2);

    assertThat(KeycloakTestStack.KEYCLOAK_IMAGE).isEqualTo(image);

    String build = Files.readString(ROOT.resolve("apps/keycloak-provisioning/build.gradle.kts"));
    Matcher compiled = Pattern.compile("val keycloakVersion = \"([0-9.]+)\"").matcher(build);
    assertThat(compiled.find()).isTrue();
    assertThat(compiled.group(1)).isEqualTo(version);

    String metadata =
        Files.readString(
            ROOT.resolve(
                "apps/keycloak-provisioning/src/main/resources/META-INF/divalhr-provisioning.properties"));
    assertThat(metadata.strip()).isEqualTo("keycloak.version=" + version);

    // Compose builds this image; it never pulls a stock Keycloak image that lacks the extension.
    String compose = Files.readString(ROOT.resolve("infrastructure/docker/compose.yaml"));
    assertThat(compose)
        .contains("dockerfile: infrastructure/docker/keycloak/Dockerfile")
        .doesNotContain("image: quay.io/keycloak/keycloak");
  }
}
