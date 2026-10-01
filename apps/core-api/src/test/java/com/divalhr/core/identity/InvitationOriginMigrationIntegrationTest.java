package com.divalhr.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MVP-014 V9 (architect decision on #38, A6): existing invitations become TENANT_ADMIN and nothing
 * else changes; the previous application's inserts keep working; origin is immutable; a bootstrap
 * is always tenant-admin; only a bootstrap acceptance can end as superseded; no development data;
 * and the manual rollback restores the V8 transition function and constraints exactly.
 */
@IntegrationTest
class InvitationOriginMigrationIntegrationTest {

  private static final String SIGNATURE =
      """
      SELECT md5(pg_get_functiondef('identity.invitation_transition_allowed'::regproc))
          || '|' || (SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                       ORDER BY conname)
                     FROM pg_constraint WHERE conrelid = 'identity.invitation'::regclass)
          || '|' || (SELECT string_agg(indexname || ':' || indexdef, ',' ORDER BY indexname)
                     FROM pg_indexes WHERE schemaname = 'identity')
          || '|' || (SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY column_name)
                     FROM information_schema.columns
                     WHERE table_schema = 'identity' AND table_name = 'invitation')
      """;

  private static final String ROWS =
      """
      SELECT (SELECT count(*) FROM identity.invitation) || '/'
          || (SELECT count(*) FROM identity.tenant_membership) || '/'
          || (SELECT count(*) FROM tenant.organization) || '/'
          || (SELECT count(*) FROM platform.audit_event) || '/'
          || (SELECT count(*) FROM platform.outbox_event)
      """;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  @Test
  void v9IsAdditiveGuardsOriginAndItsRollbackRestoresV8Exactly() {
    String database = "origin9_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      migrate(url, "8");
      JdbcTemplate db =
          new JdbcTemplate(
              new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
      UUID tenant = UUID.randomUUID();
      db.update(
          """
          INSERT INTO tenant.organization
            (id, name, country_code, default_locale, timezone, created_at, created_by)
          VALUES (?, 'Origin Org', 'CD', 'fr', 'Africa/Kinshasa', now(), 'sub-origin')
          """,
          tenant);
      UUID legacy = insert(db, tenant, "tenant-admin", null);
      String v8 = db.queryForObject(SIGNATURE, String.class);
      String rowsBefore = db.queryForObject(ROWS, String.class);

      migrate(url, "9");
      // Existing rows become TENANT_ADMIN; no row is added (no development data).
      assertThat(db.queryForObject(ROWS, String.class)).isEqualTo(rowsBefore);
      assertThat(origin(db, legacy)).isEqualTo("TENANT_ADMIN");
      // The previous application's insert (no origin column) still works.
      UUID previous = insert(db, tenant, "employee", null);
      assertThat(origin(db, previous)).isEqualTo("TENANT_ADMIN");

      // Origin is immutable; a bootstrap is always a tenant administrator.
      assertThatThrownBy(
              () ->
                  db.update(
                      "UPDATE identity.invitation SET origin = 'PLATFORM_BOOTSTRAP' WHERE id = ?",
                      legacy))
          .hasMessageContaining("origin are immutable");
      assertThatThrownBy(() -> insert(db, tenant, "employee", "PLATFORM_BOOTSTRAP"))
          .hasMessageContaining("invitation_bootstrap_is_tenant_admin");

      // Only a bootstrap acceptance may end as superseded.
      assertThatThrownBy(
              () ->
                  db.update(
                      "UPDATE identity.invitation SET state = 'REVOKED', token_sha256 = NULL,"
                          + " revoked_at = now(), revoked_by = 'system:bootstrap-superseded',"
                          + " terminal_at = now() WHERE id = ?",
                      legacy))
          .hasMessageContaining("invitation_superseded_is_bootstrap");
      accepting(db, legacy);
      assertThatThrownBy(() -> supersede(db, legacy))
          .hasMessageContaining("ACCEPTING -> REVOKED is not allowed");
      db.update(
          "UPDATE identity.invitation SET state = 'PENDING', acceptance_lease_owner = NULL,"
              + " acceptance_lease_until = NULL WHERE id = ?",
          legacy);
      db.update(
          "UPDATE identity.invitation SET state = 'REVOKED', token_sha256 = NULL,"
              + " revoked_at = now(), revoked_by = 'sub-origin', terminal_at = now() WHERE id = ?",
          legacy);
      UUID bootstrap = insert(db, tenant, "tenant-admin", "PLATFORM_BOOTSTRAP");
      accepting(db, bootstrap);
      supersede(db, bootstrap);
      assertThat(
              db.queryForObject(
                  "SELECT state FROM identity.invitation WHERE id = ?", String.class, bootstrap))
          .isEqualTo("REVOKED");

      // The rollback refuses while a bootstrap is open, then restores V8 exactly.
      UUID open = insert(db, tenant, "tenant-admin", "PLATFORM_BOOTSTRAP");
      db.update(
          """
          INSERT INTO platform.idempotency_record
            (operation, principal, idempotency_key, request_fingerprint, state, response_status,
             response_body, resource_id, created_at, expires_at)
          VALUES ('tenant-admin-bootstrap.create', 'sub-origin', 'origin-key-00000001',
                  repeat('c', 64), 'COMPLETED', 201, '{}'::jsonb, gen_random_uuid(), now(),
                  now() + interval '7 days')
          """);
      String rollback = read("db/rollback/V9__rollback.sql");
      assertThatThrownBy(() -> db.execute(rollback))
          .hasMessageContaining("open bootstrap invitations exist");
      db.update(
          "UPDATE identity.invitation SET state = 'REVOKED', token_sha256 = NULL,"
              + " revoked_at = now(), revoked_by = 'sub-origin', terminal_at = now() WHERE id = ?",
          open);
      db.execute(rollback);
      assertThat(db.queryForObject(SIGNATURE, String.class)).isEqualTo(v8);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM platform.idempotency_record WHERE operation LIKE"
                      + " 'tenant-admin-bootstrap.%'",
                  Integer.class))
          .isZero();
      // V9 applies again after the rollback.
      db.update("DELETE FROM flyway_schema_history WHERE version = '9'");
      migrate(url, "9");
      assertThat(origin(db, bootstrap)).isEqualTo("TENANT_ADMIN");
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }

  private static UUID insert(JdbcTemplate db, UUID tenant, String role, String origin) {
    UUID id = UUID.randomUUID();
    String columns = origin == null ? "" : ", origin";
    String value = origin == null ? "" : ", '" + origin + "'";
    db.update(
        "INSERT INTO identity.invitation (id, tenant_id, email, email_lookup, role, locale, state,"
            + " token_sha256, token_issued_at, expires_at, issue_count, delivery_state,"
            + " delivery_updated_at, created_at, created_by"
            + columns
            + ") VALUES (?, ?, ?, decode(md5(random()::text) || md5(random()::text), 'hex'), ?,"
            + " 'fr', 'PENDING', decode(md5(random()::text) || md5(random()::text), 'hex'), now(),"
            + " now() + interval '1 day', 1, 'QUEUED', now(), now(), 'sub-origin'"
            + value
            + ")",
        id,
        tenant,
        "m." + id.toString().substring(0, 8) + "@example.test",
        role);
    return id;
  }

  private static void accepting(JdbcTemplate db, UUID id) {
    db.update(
        "UPDATE identity.invitation SET state = 'ACCEPTING', acceptance_lease_owner ="
            + " gen_random_uuid(), acceptance_lease_until = now() + interval '1 minute' WHERE id ="
            + " ?",
        id);
  }

  private static void supersede(JdbcTemplate db, UUID id) {
    db.update(
        "UPDATE identity.invitation SET state = 'REVOKED', token_sha256 = NULL,"
            + " acceptance_lease_owner = NULL, acceptance_lease_until = NULL, revoked_at = now(),"
            + " revoked_by = 'system:bootstrap-superseded', terminal_at = now() WHERE id = ?",
        id);
  }

  private static String origin(JdbcTemplate db, UUID id) {
    return db.queryForObject(
        "SELECT origin FROM identity.invitation WHERE id = ?", String.class, id);
  }

  private static String read(String resource) {
    try {
      return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void migrate(String url, String target) {
    Flyway.configure()
        .dataSource(url, postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }
}
