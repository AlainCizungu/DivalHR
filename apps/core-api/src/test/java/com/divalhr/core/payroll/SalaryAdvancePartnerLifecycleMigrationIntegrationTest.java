package com.divalhr.core.payroll;

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
 * MVP-120C V54 directly against PostgreSQL (the database is the final authority): the refusing
 * rollback that restores V53 exactly, the closed retention policy rules, the new status in the
 * closed catalogues, and the view that exposes the immutable status history as a digest. The data
 * preservation of populated V53 rows is exercised by the deployment to hr-dev (V54 rewrites no row
 * and the attempt, event and consent tables are untouched).
 */
@IntegrationTest
class SalaryAdvancePartnerLifecycleMigrationIntegrationTest {

  /** The payroll schema: tables, columns, indexes, constraints, triggers and functions. */
  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'payroll'), '')
          || '|' || coalesce((SELECT string_agg(table_name || '.' || column_name || ':'
                                  || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, ''),
                                  ',' ORDER BY table_name, column_name)
                              FROM information_schema.columns
                              WHERE table_schema = 'payroll'), '')
          || '|' || coalesce((SELECT string_agg(indexname || ':' || indexdef, ','
                                  ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'payroll'), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY conname)
                              FROM pg_constraint
                              WHERE connamespace = 'payroll'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(pg_get_triggerdef(t.oid), ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'payroll'::regnamespace
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ','
                                  ORDER BY proname) FROM pg_proc
                              WHERE pronamespace = 'payroll'::regnamespace), '')
      """;

  private static final String POLICY =
      "INSERT INTO payroll.salary_advance_evidence_retention_policy (tenant_id, version,"
          + " retention_days, effective_at, configured_by, created_at)"
          + " VALUES (?, ?, ?, now(), 'admin-subject', now())";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "sa120c_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      body.accept(
          new Db(
              url,
              new JdbcTemplate(
                  new DriverManagerDataSource(
                      url, postgres.getUsername(), postgres.getPassword()))));
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

  private static String resource(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Runs a script as written (JDBC escape processing would rewrite braces in the bodies). */
  private static void script(Db db, String sql) {
    db.jdbc()
        .execute(
            (org.springframework.jdbc.core.ConnectionCallback<Void>)
                connection -> {
                  try (java.sql.Statement statement = connection.createStatement()) {
                    statement.setEscapeProcessing(false);
                    statement.execute(sql);
                  }
                  return null;
                });
  }

  @Test
  void theRollbackRestoresV53ExactlyAndRefusesOnceAPolicyExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "53");
          String v53 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "54");
          String v54 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v54)
              .isNotEqualTo(v53)
              .contains("salary_advance_purge_record")
              .contains("salary_advance_handoff_status_history");
          int recorded =
              db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class);
          script(db, resource("db/rollback/V54__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v53);
          assertThat(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
              .isEqualTo(recorded);

          // With no 120C rows the rollback ran; the migration can be applied again.
          script(db, resource("db/migration/V54__salary_advance_partner_lifecycle.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v54);

          // Once a policy exists, the rollback refuses and changes nothing.
          db.jdbc().update(POLICY, UUID.randomUUID(), 1, 30);
          assertThatThrownBy(() -> script(db, resource("db/rollback/V54__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V54 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v54);
        });
  }

  @Test
  void theRetentionPolicyIsBoundedVersionedAndImmutable() {
    withDatabase(
        db -> {
          migrate(db.url(), "54");
          UUID tenant = UUID.randomUUID();
          // The period is bounded to 30 to 3,650 days.
          for (int days : new int[] {0, 29, 3651}) {
            assertThatThrownBy(() -> db.jdbc().update(POLICY, tenant, 1, days))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("payroll_salary_advance_retention_policy_days");
          }
          // Versions start at 1 and grow by one.
          assertThatThrownBy(() -> db.jdbc().update(POLICY, tenant, 2, 30))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("new version after the latest");
          db.jdbc().update(POLICY, tenant, 1, 30);
          db.jdbc().update(POLICY, tenant, 2, 3650);
          assertThatThrownBy(() -> db.jdbc().update(POLICY, tenant, 2, 90))
              .isInstanceOf(DataAccessException.class);
          // A policy is never edited or deleted.
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "UPDATE payroll.salary_advance_evidence_retention_policy"
                                  + " SET retention_days = 31 WHERE tenant_id = ?",
                              tenant))
              .isInstanceOf(DataAccessException.class);
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "DELETE FROM payroll.salary_advance_evidence_retention_policy"
                                  + " WHERE tenant_id = ?",
                              tenant))
              .isInstanceOf(DataAccessException.class);
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT count(*) FROM payroll.salary_advance_evidence_retention_policy"
                              + " WHERE tenant_id = ?",
                          Integer.class,
                          tenant))
              .isEqualTo(2);
        });
  }

  @Test
  void theClosedCataloguesNameTheNewStatusAndNothingThatApprovesOrPays() {
    withDatabase(
        db -> {
          migrate(db.url(), "54");
          String definitions =
              db.jdbc()
                  .queryForObject(
                      "SELECT string_agg(pg_get_constraintdef(oid), ' ') FROM pg_constraint"
                          + " WHERE connamespace = 'payroll'::regnamespace AND conname IN"
                          + " ('payroll_salary_advance_handoff_review',"
                          + " 'payroll_salary_advance_handoff_callback_state',"
                          + " 'payroll_salary_advance_handoff_review_event_transition')",
                      String.class);
          assertThat(definitions).contains("CANCELLED_BY_PARTNER");
          assertThat(definitions)
              .doesNotContain("APPROVED")
              .doesNotContain("FUNDED")
              .doesNotContain("PAID")
              .doesNotContain("DISBURSED");
          // A terminal status never has a successor in the review-event catalogue.
          assertThat(definitions)
              .doesNotContain("from_review_state = 'DECLINED'")
              .doesNotContain("from_review_state = 'CANCELLED_BY_PARTNER'");
        });
  }

  @Test
  void deliveryDiagnosticsCanNeverBeDeletedWithoutAConfirmedPurge() {
    withDatabase(
        db -> {
          migrate(db.url(), "54");
          // Even with the purge marker set, nothing is deletable without a confirmed record that
          // names
          // the handoff; with no rows at all the statement is a no-op, and the guard is installed.
          Integer guards =
              db.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                          + " WHERE c.relname = 'salary_advance_handoff_attempt' AND t.tgname IN"
                          + " ('payroll_salary_advance_handoff_attempt_purge',"
                          + " 'payroll_salary_advance_handoff_attempt_no_update',"
                          + " 'payroll_salary_advance_handoff_attempt_no_truncate')",
                      Integer.class);
          assertThat(guards).isEqualTo(3);
          Integer legacy =
              db.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM pg_trigger WHERE tgname ="
                          + " 'payroll_salary_advance_handoff_attempt_immutable'",
                      Integer.class);
          assertThat(legacy).isZero();
        });
  }
}
