package com.divalhr.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Issue #25 compatibility: V8 is additive (no existing row, table or constraint changes), the
 * previous application's statements keep working against V8, and the manual rollback restores V7
 * and purges the invitation idempotency records.
 */
@IntegrationTest
class InvitationMigrationCompatibilityIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  @Test
  void v8IsAdditiveAndTheManualRollbackRestoresV7() throws Exception {
    String database = "compat8_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      migrate(url, "7");
      JdbcTemplate db =
          new JdbcTemplate(
              new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
      UUID tenant = UUID.randomUUID();
      db.update(
          """
          INSERT INTO tenant.organization
            (id, name, country_code, default_locale, timezone, created_at, created_by)
          VALUES (?, 'Compat Org', 'CD', 'fr', 'Africa/Kinshasa', now(), 'sub-compat')
          """,
          tenant);
      db.update(
          """
          INSERT INTO platform.idempotency_record
            (operation, principal, idempotency_key, request_fingerprint, state, response_status,
             response_body, resource_id, created_at, expires_at)
          VALUES ('team.create', 'sub-compat', 'compat-key-00000001', repeat('a', 64),
                  'COMPLETED', 201, '{}'::jsonb, gen_random_uuid(), now(),
                  now() + interval '7 days')
          """);
      String snapshot =
          "SELECT string_agg(j, ',' ORDER BY j) FROM ("
              + "SELECT to_jsonb(o)::text j FROM tenant.organization o UNION ALL"
              + " SELECT to_jsonb(r)::text FROM platform.idempotency_record r) rows";
      String constraints =
          "SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ',' ORDER BY conname)"
              + " FROM pg_constraint WHERE connamespace <> 'identity'::regnamespace";
      String before = db.queryForObject(snapshot, String.class);
      String constraintsBefore = db.queryForObject(constraints, String.class);

      migrate(url, "8");
      assertThat(
              db.queryForObject(
                  "SELECT max(version::int) FROM flyway_schema_history WHERE success",
                  Integer.class))
          .isEqualTo(8);
      assertThat(db.queryForObject(snapshot, String.class)).isEqualTo(before);
      assertThat(db.queryForObject(constraints, String.class)).isEqualTo(constraintsBefore);

      db.update(
          """
          INSERT INTO platform.idempotency_record
            (operation, principal, idempotency_key, request_fingerprint, state, response_status,
             response_body, resource_id, created_at, expires_at)
          VALUES ('invitation.create', 'sub-compat', 'compat-key-00000002', repeat('b', 64),
                  'COMPLETED', 201, '{}'::jsonb, gen_random_uuid(), now(),
                  now() + interval '7 days')
          """);
      db.execute(read("db/rollback/V8__rollback.sql"));
      assertThat(db.queryForObject("SELECT to_regclass('identity.invitation')::text", String.class))
          .isNull();
      assertThat(
              db.queryForObject(
                  "SELECT to_regclass('identity.tenant_membership')::text", String.class))
          .isNull();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM platform.idempotency_record WHERE operation LIKE"
                      + " 'invitation.%'",
                  Integer.class))
          .isZero();
      assertThat(db.queryForObject(snapshot, String.class)).isEqualTo(before);
      db.update("DELETE FROM flyway_schema_history WHERE version = '8'");
      migrate(url, "8");
      assertThat(db.queryForObject("SELECT to_regclass('identity.invitation')::text", String.class))
          .isEqualTo("identity.invitation");
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }

  @Test
  void thePreviousApplicationKeepsWorkingAgainstV8() throws Exception {
    // The previous release only uses tenant/platform tables; a full hierarchy flow through the
    // current API (unchanged endpoints) exercises the same statements against V8.
    UUID tenant = Hierarchy.newTenant(mvc);
    UUID legal = Hierarchy.newLegalEntity(mvc, tenant, Hierarchy.code("le"), "2026-01-01", null);
    Hierarchy.newSite(mvc, tenant, legal, Hierarchy.code("st"), "2026-01-01", null);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tenant.site WHERE tenant_id = ?", Integer.class, tenant))
        .isEqualTo(1);
  }

  private static String read(String resource) {
    try {
      return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void migrate(String url, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(url, postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration");
    if (target != null) {
      configuration = configuration.target(target);
    }
    configuration.load().migrate();
  }
}
