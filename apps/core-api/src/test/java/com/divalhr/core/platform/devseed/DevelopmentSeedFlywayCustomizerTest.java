package com.divalhr.core.platform.devseed;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

class DevelopmentSeedFlywayCustomizerTest {

  private static List<String> locationsFor(String environment) {
    FluentConfiguration configuration = Flyway.configure().locations("classpath:db/migration");
    new DevelopmentSeedFlywayCustomizer(environment).customize(configuration);
    return Arrays.stream(configuration.getLocations()).map(Object::toString).toList();
  }

  @Test
  void fixturesAreAddedOnlyInDevelopment() {
    assertThat(locationsFor("development")).anyMatch(l -> l.contains("db/dev-seed"));
    assertThat(new DevelopmentSeedFlywayCustomizer("development").enabled()).isTrue();
    for (String env : new String[] {"test", "staging", "production", "dev", ""}) {
      assertThat(locationsFor(env)).as(env).noneMatch(l -> l.contains("dev-seed"));
      assertThat(new DevelopmentSeedFlywayCustomizer(env).enabled()).isFalse();
    }
    assertThat(new DevelopmentSeedFlywayCustomizer(null).enabled()).isFalse();
  }

  @Test
  void versionedMigrationsLocationMatchesApplicationConfiguration() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
    assertThat(yaml).contains("locations: " + DevelopmentSeedFlywayCustomizer.MIGRATIONS);
  }

  @Test
  void fixturesAreIdempotentObviousAndWriteNoAuditOrOutbox() throws Exception {
    Path seed =
        Path.of("src/main/resources/db/dev-seed/afterMigrate__dev_only_fixture_organizations.sql");
    String sql = Files.readString(seed);
    assertThat(sql).contains("DEV-ONLY Fixture Tenant A").contains("DEV-ONLY Fixture Tenant B");
    assertThat(sql.split("ON CONFLICT", -1)).hasSize(3);
    assertThat(sql).doesNotContain("audit_event").doesNotContain("outbox_event");
  }
}
