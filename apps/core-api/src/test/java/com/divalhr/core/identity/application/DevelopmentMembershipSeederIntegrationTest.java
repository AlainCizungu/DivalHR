package com.divalhr.core.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.internal.JdbcMembershipRepository;
import com.divalhr.core.platform.devseed.DevelopmentSeedFlywayCustomizer;
import com.divalhr.core.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MVP-012A (M8): the development-only seeder gives the four published tenant seed users their
 * memberships, matching the fixed realm identities, idempotently and without any address; it does
 * nothing outside development. Runs against a separate database with the development fixtures so
 * that the shared test database stays free of development data.
 */
@IntegrationTest
class DevelopmentMembershipSeederIntegrationTest {

  private static final Path REALM =
      Path.of(
          System.getProperty("divalhr.repoRoot", "../.."),
          "infrastructure/docker/keycloak/realm-divalhr-dev.json");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;
  @Autowired private EmailLookup lookups;

  @Test
  void theSeedsMatchTheFixedDevelopmentRealmIdentities() throws Exception {
    JsonNode realm = new ObjectMapper().readTree(REALM.toFile());
    Map<String, JsonNode> users = new HashMap<>();
    for (JsonNode user : realm.path("users")) {
      users.put(user.path("id").asText(), user);
    }
    for (DevelopmentMembershipSeeder.Seed seed : DevelopmentMembershipSeeder.SEEDS) {
      JsonNode user = users.get(seed.subject());
      assertThat(user).as("realm user %s", seed.subject()).isNotNull();
      assertThat(user.path("attributes").path("tenant_id").get(0).asText())
          .isEqualTo(seed.tenant());
      assertThat(user.path("realmRoles").get(0).asText()).isEqualTo(seed.role().wireName());
      assertThat(user.path("email").asText()).isEqualTo(seed.email());
    }
    // No platform-administrator membership.
    assertThat(DevelopmentMembershipSeeder.SEEDS).hasSize(4);
    for (JsonNode user : realm.path("users")) {
      if (user.path("realmRoles").toString().contains("platform-admin")) {
        assertThat(
                DevelopmentMembershipSeeder.SEEDS.stream()
                    .map(DevelopmentMembershipSeeder.Seed::subject)
                    .toList())
            .doesNotContain(user.path("id").asText());
      }
    }
  }

  @Test
  void theSeederRunsOnlyInDevelopmentIdempotentlyAndStoresNoAddress() {
    String database = "seed12a_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      Flyway.configure()
          .dataSource(url, postgres.getUsername(), postgres.getPassword())
          .locations(
              DevelopmentSeedFlywayCustomizer.MIGRATIONS, DevelopmentSeedFlywayCustomizer.DEV_SEED)
          .load()
          .migrate();
      DriverManagerDataSource source =
          new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
      JdbcTemplate db = new JdbcTemplate(source);
      JdbcMembershipRepository repository = new JdbcMembershipRepository(JdbcClient.create(source));
      TransactionTemplate transactions =
          new TransactionTemplate(new DataSourceTransactionManager(source));

      new DevelopmentMembershipSeeder("test", repository, lookups, transactions).run(null);
      new DevelopmentMembershipSeeder("production", repository, lookups, transactions).run(null);
      assertThat(count(db)).isZero();

      DevelopmentMembershipSeeder development =
          new DevelopmentMembershipSeeder("development", repository, lookups, transactions);
      assertThat(development.enabled()).isTrue();
      development.run(null);
      development.run(null);
      assertThat(count(db)).isEqualTo(4);
      List<Map<String, Object>> rows =
          db.queryForList(
              "SELECT subject, tenant_id::text AS tenant, role, email, source_invitation_id"
                  + " FROM identity.tenant_membership ORDER BY subject");
      assertThat(rows)
          .extracting(row -> row.get("subject"))
          .containsExactly(
              "00000000-0000-4000-8000-0000000000a1",
              "00000000-0000-4000-8000-0000000000a2",
              "00000000-0000-4000-8000-0000000000b1",
              "00000000-0000-4000-8000-0000000000b2");
      assertThat(rows).allSatisfy(row -> assertThat(row.get("email")).isNull());
      assertThat(rows).allSatisfy(row -> assertThat(row.get("source_invitation_id")).isNull());
      assertThat(rows)
          .extracting(row -> row.get("role"))
          .containsExactly("tenant-admin", "employee", "tenant-admin", "employee");
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }

  private static int count(JdbcTemplate db) {
    Integer count =
        db.queryForObject("SELECT count(*) FROM identity.tenant_membership", Integer.class);
    return count == null ? 0 : count;
  }
}
