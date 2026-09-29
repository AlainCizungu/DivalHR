package com.divalhr.core.platform.devseed;

import java.util.Locale;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;

/**
 * Adds the development-only fixture location ({@code db/dev-seed}, an idempotent {@code
 * afterMigrate} callback) to Flyway when, and only when, {@code divalhr.environment} is exactly
 * {@code development}. In test, staging and production Flyway sees only the versioned migrations.
 */
@Component
public class DevelopmentSeedFlywayCustomizer implements FlywayConfigurationCustomizer {

  /** Versioned migrations; must match {@code spring.flyway.locations}. */
  public static final String MIGRATIONS = "classpath:db/migration";

  /** Development-only fixtures. */
  public static final String DEV_SEED = "classpath:db/dev-seed";

  private final boolean development;

  /**
   * Creates the customizer.
   *
   * @param environment deployment environment
   */
  public DevelopmentSeedFlywayCustomizer(@Value("${divalhr.environment}") String environment) {
    this.development =
        environment != null && "development".equals(environment.trim().toLowerCase(Locale.ROOT));
  }

  @Override
  public void customize(FluentConfiguration configuration) {
    if (development) {
      configuration.locations(MIGRATIONS, DEV_SEED);
    }
  }

  /**
   * Whether fixtures are enabled (for start-up tests).
   *
   * @return true only in development
   */
  public boolean enabled() {
    return development;
  }
}
