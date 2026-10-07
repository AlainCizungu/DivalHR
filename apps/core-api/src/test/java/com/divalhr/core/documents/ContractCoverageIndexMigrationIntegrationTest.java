package com.divalhr.core.documents;

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

/**
 * MVP-031A V17 (Issue #73, A31A-5): one partial covering index only, no data change, and an exact
 * rollback to V16.
 */
@IntegrationTest
class ContractCoverageIndexMigrationIntegrationTest {

  private static final String SIGNATURE =
      """
      SELECT (SELECT string_agg(indexname || ':' || indexdef, ',' ORDER BY indexname)
              FROM pg_indexes WHERE schemaname = 'documents')
          || '|' || (SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                       ORDER BY conname)
                     FROM pg_constraint WHERE conrelid = 'documents.contract'::regclass)
          || '|' || (SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY column_name)
                     FROM information_schema.columns
                     WHERE table_schema = 'documents' AND table_name = 'contract')
      """;

  private static final String ROWS =
      """
      SELECT (SELECT count(*) FROM documents.contract) || '/'
          || (SELECT count(*) FROM documents.contract_template) || '/'
          || (SELECT count(*) FROM people.employment) || '/'
          || (SELECT count(*) FROM platform.audit_event)
      """;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  @Test
  void v17AddsOnlyTheCoverageIndexAndItsRollbackRestoresV16Exactly() throws Exception {
    String database = "coverage17_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      migrate(url, "16");
      JdbcTemplate db =
          new JdbcTemplate(
              new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
      String v16 = db.queryForObject(SIGNATURE, String.class);
      String rows = db.queryForObject(ROWS, String.class);

      migrate(url, "17");
      assertThat(db.queryForObject(ROWS, String.class)).isEqualTo(rows);
      String v17 = db.queryForObject(SIGNATURE, String.class);
      // Every field the coverage-head walk reads is in the index, the contract UUID included.
      assertThat(v17)
          .contains(
              "contract_coverage_order:CREATE INDEX contract_coverage_order ON documents.contract"
                  + " USING btree (tenant_id, employment_id, start_date) INCLUDE (end_date,"
                  + " employee_id, id) WHERE (state <> 'VOID'::text)");
      assertThat(v17).isNotEqualTo(v16);

      db.execute(
          new ClassPathResource("db/rollback/V17__rollback.sql")
              .getContentAsString(StandardCharsets.UTF_8));
      assertThat(db.queryForObject(SIGNATURE, String.class)).isEqualTo(v16);
      assertThat(db.queryForObject(ROWS, String.class)).isEqualTo(rows);
      db.update("DELETE FROM flyway_schema_history WHERE version = '17'");
      migrate(url, "17");
      assertThat(db.queryForObject(SIGNATURE, String.class)).isEqualTo(v17);
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
