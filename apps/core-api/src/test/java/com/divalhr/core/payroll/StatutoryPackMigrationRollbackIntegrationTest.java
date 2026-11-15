package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PaymentReconciliationFixtures.exec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * MVP-097A (Issue #200, ADR 0040 section 7): the manual V51 rollback script, directly against
 * PostgreSQL in a database of its own. It restores the V50 schema exactly while no reference case,
 * validation run, review, transition or touched governance row exists (the untouched DRAFT rows V51
 * itself creates for existing packs do not count), and it refuses, changing nothing, as soon as any
 * one of those exists: that evidence is never erased by a schema rollback. Synthetic data only.
 */
@IntegrationTest
class StatutoryPackMigrationRollbackIntegrationTest {

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
          || '|' || coalesce((SELECT string_agg(conrelid::regclass::text || '.' || conname, ','
                                  ORDER BY conrelid::regclass::text, conname)
                              FROM pg_constraint
                              WHERE connamespace = 'payroll'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(p.proname, ',' ORDER BY p.proname)
                              FROM pg_proc p
                              WHERE p.pronamespace = 'payroll'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(tgrelid::regclass::text || '.' || tgname, ','
                                  ORDER BY tgrelid::regclass::text, tgname)
                              FROM pg_trigger WHERE NOT tgisinternal
                              AND tgrelid::regclass::text LIKE 'payroll.%'), '')
      """;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcConnectionDetails connection;

  private record Db(String url, JdbcTemplate jdbc) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "payroll97_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url = connection.getJdbcUrl().replaceAll("/[^/?]+(\\?.*)?$", "/" + database + "$1");
      body.accept(
          new Db(
              url,
              new JdbcTemplate(
                  new DriverManagerDataSource(
                      url, connection.getUsername(), connection.getPassword()))));
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }

  private void migrate(String url, String target) {
    Flyway.configure()
        .dataSource(url, connection.getUsername(), connection.getPassword())
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private static String rollbackScript() {
    try {
      return new ClassPathResource("db/rollback/V51__rollback.sql")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void script(Db db, String sql) {
    db.jdbc()
        .execute(
            (org.springframework.jdbc.core.ConnectionCallback<Void>)
                c -> {
                  try (Statement statement = c.createStatement()) {
                    statement.setEscapeProcessing(false);
                    statement.execute(sql);
                  }
                  return null;
                });
  }

  private Connection connect(Db db) throws SQLException {
    Connection c =
        DriverManager.getConnection(db.url(), connection.getUsername(), connection.getPassword());
    c.setAutoCommit(false);
    return c;
  }

  private static int count(Db db, String sql) {
    return java.util.Objects.requireNonNull(db.jdbc().queryForObject(sql, Integer.class));
  }

  private void rollbackRefused(Db db) throws SQLException {
    try (Connection c = connect(db)) {
      try (Statement statement = c.createStatement()) {
        statement.setEscapeProcessing(false);
        assertThatThrownBy(() -> statement.execute(rollbackScript()))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V51 rollback refused");
      } finally {
        c.rollback();
      }
    }
  }

  private static UUID seededPack(Db db) {
    return java.util.Objects.requireNonNull(
        db.jdbc()
            .queryForObject(
                "SELECT id FROM payroll.statutory_rule_pack WHERE code = 'CD-SALARY-PREVIEW'",
                UUID.class));
  }

  @FunctionalInterface
  private interface Rows {
    void write(Connection c, UUID pack) throws SQLException;
  }

  private void refusesWith(Rows rows, String table) {
    withDatabase(
        db -> {
          migrate(db.url(), "51");
          try (Connection c = connect(db)) {
            exec(c, "SET LOCAL session_replication_role = replica");
            rows.write(c, seededPack(db));
            exec(c, "SET LOCAL session_replication_role = origin");
            c.commit();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          String before = db.jdbc().queryForObject(SIGNATURE, String.class);
          try {
            rollbackRefused(db);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(before);
          assertThat(count(db, "SELECT count(*) FROM payroll." + table)).isEqualTo(1);
        });
  }

  @Test
  void theRollbackRestoresV50ExactlyWhileOnlyUntouchedDraftRowsExist() {
    withDatabase(
        db -> {
          migrate(db.url(), "50");
          String v50 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "51");
          String v51 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v51).isNotEqualTo(v50).contains("statutory_pack_governance");
          // V51 only gives the existing, DRAFT pack its governance row: no case, run, review or history.
          assertThat(count(db, "SELECT count(*) FROM payroll.statutory_pack_governance"))
              .isEqualTo(count(db, "SELECT count(*) FROM payroll.statutory_rule_pack"));
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM payroll.statutory_pack_governance WHERE state <> 'DRAFT'"))
              .isZero();
          assertThat(count(db, "SELECT count(*) FROM payroll.statutory_reference_case")).isZero();
          assertThat(count(db, "SELECT count(*) FROM payroll.statutory_pack_validation_run"))
              .isZero();
          assertThat(count(db, "SELECT count(*) FROM payroll.statutory_pack_review")).isZero();
          assertThat(count(db, "SELECT count(*) FROM payroll.statutory_pack_transition")).isZero();
          script(db, rollbackScript());
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v50);
          // The rollback does not edit the Flyway history.
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history WHERE version = '51'"))
              .isEqualTo(1);
        });
  }

  @Test
  void theRollbackRefusesWhileAReferenceCaseExistsAndChangesNothing() {
    refusesWith(
        (c, pack) ->
            exec(
                c,
                "INSERT INTO payroll.statutory_reference_case (pack_id, seq, case_key, inputs,"
                    + " expected) VALUES (?, 1, 'SYNTHETIC-CASE', '{}'::jsonb, '{}'::jsonb)",
                pack),
        "statutory_reference_case");
  }

  @Test
  void theRollbackRefusesWhileAValidationRunExistsAndChangesNothing() {
    refusesWith(
        (c, pack) ->
            exec(
                c,
                "INSERT INTO payroll.statutory_pack_validation_run (id, pack_id, run_by, run_at,"
                    + " outcome, check_count, failed_count, summary, pack_sha256, fixture_sha256,"
                    + " engine_version) VALUES (?, ?, 'synthetic', now(), 'PASSED', 1, 0,"
                    + " '[{\"code\":\"PACK_PARSES\",\"passed\":true}]'::jsonb, repeat('a', 64),"
                    + " repeat('b', 64), 'pack-validator-1')",
                UUID.randomUUID(),
                pack),
        "statutory_pack_validation_run");
  }

  @Test
  void theRollbackRefusesWhileAReviewExistsAndChangesNothing() {
    refusesWith(
        (c, pack) ->
            exec(
                c,
                "INSERT INTO payroll.statutory_pack_review (pack_id, decision, reviewer_subject,"
                    + " decided_at, confirmed, mfa_verified) VALUES (?, 'APPROVED', 'synthetic',"
                    + " now(), true, true)",
                pack),
        "statutory_pack_review");
  }

  @Test
  void theRollbackRefusesWhileAHistoryRowExistsAndChangesNothing() {
    refusesWith(
        (c, pack) ->
            exec(
                c,
                "INSERT INTO payroll.statutory_pack_transition (pack_id, seq, kind, from_state,"
                    + " to_state, actor, occurred_at) VALUES (?, 1, 'VALIDATE', 'DRAFT',"
                    + " 'VALIDATED', 'synthetic', now())",
                pack),
        "statutory_pack_transition");
  }

  @Test
  void theRollbackRefusesWhileAGovernanceRowIsTouchedAndChangesNothing() {
    withDatabase(
        db -> {
          migrate(db.url(), "51");
          db.jdbc()
              .update(
                  "UPDATE payroll.statutory_pack_governance SET source_authority = 'Synthetic test"
                      + " authority', row_version = row_version + 1, transition_actor = 'synthetic',"
                      + " transition_at = now() WHERE pack_id = ?",
                  seededPack(db));
          String before = db.jdbc().queryForObject(SIGNATURE, String.class);
          try {
            rollbackRefused(db);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(before);
          // No override and no deletion exist.
          assertThatThrownBy(() -> script(db, rollbackScript())).isInstanceOf(Exception.class);
        });
  }
}
