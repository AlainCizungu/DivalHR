package com.divalhr.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

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

/** MVP-012B V11 (R6): two keyset indexes only, no data change, and an exact rollback to V10. */
@IntegrationTest
class AccessReviewIndexMigrationIntegrationTest {

  private static final String SIGNATURE =
      """
      SELECT (SELECT string_agg(indexname || ':' || indexdef, ',' ORDER BY indexname)
              FROM pg_indexes WHERE schemaname = 'identity')
          || '|' || (SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                       ORDER BY conname)
                     FROM pg_constraint WHERE conrelid = 'identity.tenant_membership'::regclass)
          || '|' || (SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY column_name)
                     FROM information_schema.columns
                     WHERE table_schema = 'identity' AND table_name = 'tenant_membership')
      """;

  private static final String ROWS =
      """
      SELECT (SELECT count(*) FROM identity.tenant_membership) || '/'
          || (SELECT count(*) FROM identity.invitation) || '/'
          || (SELECT count(*) FROM tenant.organization) || '/'
          || (SELECT count(*) FROM platform.audit_event)
      """;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  @Test
  void v11AddsOnlyTheTwoReviewIndexesAndItsRollbackRestoresV10Exactly() throws Exception {
    String database = "review11_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      migrate(url, "10");
      JdbcTemplate db =
          new JdbcTemplate(
              new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
      String v10 = db.queryForObject(SIGNATURE, String.class);
      String rows = db.queryForObject(ROWS, String.class);

      migrate(url, "11");
      assertThat(db.queryForObject(ROWS, String.class)).isEqualTo(rows);
      String v11 = db.queryForObject(SIGNATURE, String.class);
      assertThat(v11)
          .contains(
              "tenant_membership_review_order:CREATE INDEX tenant_membership_review_order ON"
                  + " identity.tenant_membership USING btree (tenant_id, created_at DESC, id DESC)")
          .contains(
              "tenant_membership_review_role:CREATE INDEX tenant_membership_review_role ON"
                  + " identity.tenant_membership USING btree (tenant_id, role, created_at DESC, id"
                  + " DESC)");
      assertThat(v11.replace(",tenant_membership_review_order", "")).isNotEqualTo(v10);

      db.execute(
          new ClassPathResource("db/rollback/V11__rollback.sql")
              .getContentAsString(StandardCharsets.UTF_8));
      assertThat(db.queryForObject(SIGNATURE, String.class)).isEqualTo(v10);
      assertThat(db.queryForObject(ROWS, String.class)).isEqualTo(rows);
      db.update("DELETE FROM flyway_schema_history WHERE version = '11'");
      migrate(url, "11");
      assertThat(db.queryForObject(SIGNATURE, String.class)).isEqualTo(v11);
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
}
