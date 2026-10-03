package com.divalhr.core.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MVP-013 V12 (D1, A13-4, A13-5): the typed append-only denial table, its database checks, and a
 * guarded rollback that restores V11 exactly but refuses while evidence exists.
 */
@IntegrationTest
class AuthorizationDenialMigrationIntegrationTest {

  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'platform'), '')
          || '|' || coalesce((SELECT string_agg(proname, ',' ORDER BY proname)
                              FROM pg_proc WHERE pronamespace = 'platform'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(tgname, ',' ORDER BY tgname) FROM pg_trigger t
                              JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'platform'::regnamespace
                                AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(indexname, ',' ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'platform'), '')
      """;

  private static final String INSERT =
      """
      INSERT INTO platform.authorization_denial
        (id, occurred_at, actor_subject, action, operation, scope, stage, tenant_id, correlation_id)
      VALUES (?, now(), ?, ?, ?, ?, ?, ?, ?)
      """;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private void withDatabase(Consumer<JdbcTemplate> body) {
    String database = "denial12_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      migrate(url, "11");
      body.accept(
          new JdbcTemplate(
              new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword())) {
            @Override
            public String toString() {
              return url;
            }
          });
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
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

  private static String rollbackScript() throws Exception {
    return new ClassPathResource("db/rollback/V12__rollback.sql")
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private static void insert(
      JdbcTemplate db,
      String actor,
      String action,
      String operation,
      String scope,
      String stage,
      UUID tenant,
      String correlation) {
    db.update(
        INSERT, UUID.randomUUID(), actor, action, operation, scope, stage, tenant, correlation);
  }

  @Test
  void v12AddsOnlyTheDenialTableAndItsChecksMirrorTheApplication() {
    withDatabase(
        db -> {
          String v11 = db.queryForObject(SIGNATURE, String.class);
          migrate(db.toString(), "12");
          assertThat(db.queryForObject(SIGNATURE, String.class))
              .contains("authorization_denial")
              .contains("authorization_denial_append_only")
              .contains("authorization_denial_no_update_delete")
              .contains("authorization_denial_no_truncate")
              .isNotEqualTo(v11);
          UUID tenant = UUID.randomUUID();
          String ok = "corr-00000001";
          // Valid rows: any non-empty subject of any length, unchanged.
          insert(
              db,
              "s".repeat(4000),
              "authorization.denied",
              "site.list",
              "tenant",
              "role",
              null,
              ok);
          insert(
              db,
              " padded ",
              "authorization.denied",
              "site.list",
              "tenant",
              "membership",
              null,
              ok);
          insert(db, "s", "authorization.denied", "site.list", "tenant", "rate_limit", tenant, ok);
          insert(
              db,
              "s",
              "authorization.denied",
              "site.list",
              "tenant",
              "method_security",
              tenant,
              ok);
          insert(
              db, "s", "authorization.denied", "organization.create", "platform", "mfa", null, ok);
          insert(
              db,
              "s",
              "authorization.denied",
              "organization.create",
              "platform",
              "method_security",
              null,
              ok);
          assertThat(
                  db.queryForObject(
                      "SELECT char_length(actor_subject) FROM platform.authorization_denial"
                          + " ORDER BY char_length(actor_subject) DESC LIMIT 1",
                      Integer.class))
              .isEqualTo(4000);

          // Rejected: each check.
          assertRejected(
              () ->
                  insert(db, "", "authorization.denied", "site.list", "tenant", "role", null, ok));
          assertRejected(
              () -> insert(db, "s", "access-review.read", "site.list", "tenant", "role", null, ok));
          assertRejected(
              () ->
                  insert(db, "s", "authorization.denied", "Site.List", "tenant", "role", null, ok));
          assertRejected(
              () ->
                  insert(db, "s", "authorization.denied", "site.list", "global", "role", null, ok));
          assertRejected(
              () ->
                  insert(
                      db,
                      "s",
                      "authorization.denied",
                      "site.list",
                      "tenant",
                      "subject_missing",
                      null,
                      ok));
          assertRejected(
              () ->
                  insert(
                      db,
                      "s",
                      "authorization.denied",
                      "organization.create",
                      "platform",
                      "membership",
                      null,
                      ok));
          assertRejected(
              () ->
                  insert(
                      db,
                      "s",
                      "authorization.denied",
                      "organization.create",
                      "platform",
                      "role",
                      tenant,
                      ok));
          assertRejected(
              () ->
                  insert(
                      db, "s", "authorization.denied", "site.list", "tenant", "role", tenant, ok));
          assertRejected(
              () ->
                  insert(
                      db,
                      "s",
                      "authorization.denied",
                      "site.list",
                      "tenant",
                      "rate_limit",
                      null,
                      ok));
          assertRejected(
              () ->
                  insert(
                      db,
                      "s",
                      "authorization.denied",
                      "site.list",
                      "tenant",
                      "role",
                      null,
                      "short"));
          assertRejected(
              () ->
                  insert(
                      db,
                      "s",
                      "authorization.denied",
                      "site.list",
                      "tenant",
                      "role",
                      null,
                      "has space ok"));

          // Append-only.
          assertRejected(() -> db.update("UPDATE platform.authorization_denial SET stage = 'mfa'"));
          assertRejected(() -> db.update("DELETE FROM platform.authorization_denial"));
          assertRejected(() -> db.execute("TRUNCATE platform.authorization_denial"));
          assertThat(
                  db.queryForObject(
                      "SELECT count(*) FROM platform.authorization_denial", Integer.class))
              .isEqualTo(6);
        });
  }

  @Test
  void theRollbackRefusesWhileEvidenceExists() {
    withDatabase(
        db -> {
          migrate(db.toString(), "12");
          String v12 = db.queryForObject(SIGNATURE, String.class);
          insert(
              db,
              "s",
              "authorization.denied",
              "site.list",
              "tenant",
              "role",
              null,
              "corr-00000001");
          assertThatThrownBy(() -> db.execute(rollbackScript()))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V12 rollback refused");
          assertThat(db.queryForObject(SIGNATURE, String.class)).isEqualTo(v12);
          assertThat(
                  db.queryForObject(
                      "SELECT count(*) FROM platform.authorization_denial", Integer.class))
              .isEqualTo(1);
        });
  }

  @Test
  void anEmptyTableRollsBackToV11ExactlyAndMigratesAgain() {
    withDatabase(
        db -> {
          String v11 = db.queryForObject(SIGNATURE, String.class);
          migrate(db.toString(), "12");
          String v12 = db.queryForObject(SIGNATURE, String.class);
          try {
            db.execute(rollbackScript());
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          assertThat(db.queryForObject(SIGNATURE, String.class)).isEqualTo(v11);
          db.update("DELETE FROM flyway_schema_history WHERE version = '12'");
          migrate(db.toString(), "12");
          assertThat(db.queryForObject(SIGNATURE, String.class)).isEqualTo(v12);
        });
  }

  private static void assertRejected(Runnable statement) {
    assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class);
  }
}
