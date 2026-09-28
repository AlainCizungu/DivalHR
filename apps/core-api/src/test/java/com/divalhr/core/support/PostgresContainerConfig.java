package com.divalhr.core.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL matching the Compose image, shared across the test context cache. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

  /** Keep in sync with infrastructure/docker/compose.yaml. */
  public static final String IMAGE = "postgres:17.11";

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer(IMAGE);
  }
}
