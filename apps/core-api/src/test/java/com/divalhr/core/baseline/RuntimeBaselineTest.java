package com.divalhr.core.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the approved runtime baseline (ADR 0004 and the Issue #12 review): Java 21 for the Core
 * API image and the same pinned PostgreSQL 17 image for Compose and Testcontainers.
 */
class RuntimeBaselineTest {

  private static final Path ROOT = Path.of(System.getProperty("divalhr.repoRoot", "../.."));

  @Test
  void coreApiImagesUseJava21() throws IOException {
    List<String> from =
        Files.readAllLines(ROOT.resolve("apps/core-api/Dockerfile")).stream()
            .filter(line -> line.startsWith("FROM "))
            .toList();
    assertThat(from).hasSize(2);
    assertThat(from)
        .allSatisfy(
            line ->
                assertThat(line)
                    .matches("FROM eclipse-temurin:21\\.[0-9._]+-j(dk|re)@sha256:[0-9a-f]{64}.*"));
  }

  @Test
  void composeAndTestcontainersUseTheSamePinnedPostgres17() throws IOException {
    Matcher matcher =
        Pattern.compile("image: (postgres:[^\\s]+)")
            .matcher(Files.readString(ROOT.resolve("infrastructure/docker/compose.yaml")));
    assertThat(matcher.find()).as("postgres image in compose.yaml").isTrue();
    String compose = matcher.group(1);
    assertThat(compose).matches("postgres:17\\.[0-9]+@sha256:[0-9a-f]{64}");
    assertThat(PostgresContainerConfig.IMAGE).isEqualTo(compose);
  }

  @Test
  void gradleToolchainIsJava21() throws IOException {
    assertThat(Files.readString(ROOT.resolve("apps/core-api/build.gradle.kts")))
        .contains("languageVersion = JavaLanguageVersion.of(21)");
  }
}
