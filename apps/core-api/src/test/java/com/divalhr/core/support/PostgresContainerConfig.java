package com.divalhr.core.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL matching the Compose image, shared across the test context cache. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

  /**
   * Same pinned image as infrastructure/docker/compose.yaml (enforced by RuntimeBaselineTest).
   */
  public static final String IMAGE =
      "postgres:17.11@sha256:d74eeac9a635390a49bc21bd49fccd973de707e2a53a76ac49b552b8712ec46f";

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer(
        DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
  }
}
