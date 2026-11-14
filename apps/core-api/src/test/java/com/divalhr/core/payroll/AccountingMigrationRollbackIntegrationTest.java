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
 * MVP-096 (Issue #194, ADR 0039 section 7): the manual V50 rollback script, directly against
 * PostgreSQL in a database of its own. It restores the V49 schema exactly while no mapping, journal
 * or line exists, and it refuses (changing nothing) as soon as any one of those rows exists: that
 * evidence is never erased by a schema rollback. Synthetic data only.
 */
@IntegrationTest
class AccountingMigrationRollbackIntegrationTest {

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
    String database = "payroll96_" + UUID.randomUUID().toString().replace("-", "");
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
      return new ClassPathResource("db/rollback/V50__rollback.sql")
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
            .hasMessageContaining("V50 rollback refused");
      } finally {
        c.rollback();
      }
    }
  }

  // ---- rows, written with replica role in the caller's transaction (these tests exercise the
  // rollback script, not the V50 guards, which AccountingConstraintIntegrationTest proves) ----

  private static void mapping(Connection c, UUID tenant) throws SQLException {
    exec(c, "SET LOCAL session_replication_role = replica");
    exec(
        c,
        "INSERT INTO payroll.accounting_mapping (tenant_id, legal_entity_id, id, currency, version,"
            + " effective_from, salary_expense, employee_deduction_liability,"
            + " employer_contribution_expense, employer_contribution_liability,"
            + " net_salary_payable, mapping_digest, created_at, created_by_subject) VALUES (?, ?,"
            + " ?, 'CDF', 1, DATE '2026-01-01', '6100', '4310', '6450', '4320', '4210',"
            + " repeat('a', 64), now(), 'sub-test')",
        tenant,
        UUID.randomUUID(),
        UUID.randomUUID());
    exec(c, "SET LOCAL session_replication_role = origin");
  }

  private static UUID journal(Connection c, UUID tenant) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(c, "SET LOCAL session_replication_role = replica");
    exec(
        c,
        "INSERT INTO payroll.accounting_journal (tenant_id, legal_entity_id, id, package_id,"
            + " currency, version, period_start, legal_entity_code, package_version,"
            + " package_approval_digest, calculation_id, calculation_version,"
            + " calculation_digest, mapping_id, mapping_version, mapping_digest, payslip_count,"
            + " source_entry_count, line_count, gross_total_minor,"
            + " employee_deductions_total_minor, employer_contributions_total_minor,"
            + " net_total_minor, total_debits_minor, total_credits_minor, csv_sha256, csv_bytes,"
            + " journal_digest, schema_version, generated_at, generated_by_subject) VALUES (?, ?,"
            + " ?, ?, 'CDF', 1, DATE '2026-11-01', 'ENT-TEST', 1, repeat('a', 64), ?, 1,"
            + " repeat('b', 64), ?, 1, repeat('c', 64), 1, 5, 5, 162000, 12960, 21060, 149040,"
            + " 183060, 183060, repeat('d', 64), 100, repeat('e', 64), 1, now(), 'sub-test')",
        tenant,
        UUID.randomUUID(),
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID());
    exec(c, "SET LOCAL session_replication_role = origin");
    return id;
  }

  private static void line(Connection c, UUID tenant, UUID journal) throws SQLException {
    exec(c, "SET LOCAL session_replication_role = replica");
    exec(
        c,
        "INSERT INTO payroll.accounting_journal_line (tenant_id, journal_id, line_number,"
            + " account_code, direction, amount_minor) VALUES (?, ?, 1, '6100', 'DEBIT', 162000)",
        tenant,
        journal);
    exec(c, "SET LOCAL session_replication_role = origin");
  }

  @FunctionalInterface
  private interface Rows {
    void write(Connection c, UUID tenant) throws SQLException;
  }

  private void refusesWith(Rows rows, String table) {
    withDatabase(
        db -> {
          migrate(db.url(), "50");
          try (Connection c = connect(db)) {
            rows.write(c, UUID.randomUUID());
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
  void theRollbackRestoresV49ExactlyWhileNoAccountingRowExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "49");
          String v49 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "50");
          String v50 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v50).isNotEqualTo(v49).contains("accounting_journal_line");
          // V50 seeds nothing.
          assertThat(count(db, "SELECT count(*) FROM payroll.accounting_mapping")).isZero();
          assertThat(count(db, "SELECT count(*) FROM payroll.accounting_journal")).isZero();
          assertThat(count(db, "SELECT count(*) FROM payroll.accounting_journal_line")).isZero();
          script(db, rollbackScript());
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v49);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM information_schema.tables WHERE table_schema ="
                          + " 'payroll' AND table_name LIKE 'accounting_%'"))
              .isZero();
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM pg_proc WHERE pronamespace = 'payroll'::regnamespace"
                          + " AND proname LIKE 'accounting_%'"))
              .isZero();
          // The rollback does not edit the Flyway history.
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history WHERE version = '50'"))
              .isEqualTo(1);
        });
  }

  @Test
  void theRollbackRefusesWhileAMappingExistsAndChangesNothing() {
    refusesWith(AccountingMigrationRollbackIntegrationTest::mapping, "accounting_mapping");
  }

  @Test
  void theRollbackRefusesWhileAJournalExistsAndChangesNothing() {
    refusesWith((c, tenant) -> journal(c, tenant), "accounting_journal");
  }

  @Test
  void theRollbackRefusesWhileOnlyALineExistsAndChangesNothing() {
    refusesWith((c, tenant) -> line(c, tenant, UUID.randomUUID()), "accounting_journal_line");
  }

  @Test
  void theRollbackStillRefusesWithAMappingAJournalAndALineAndThenSucceedsOnlyWhenEmpty() {
    withDatabase(
        db -> {
          migrate(db.url(), "50");
          try (Connection c = connect(db)) {
            UUID tenant = UUID.randomUUID();
            mapping(c, tenant);
            line(c, tenant, journal(c, tenant));
            c.commit();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          try {
            rollbackRefused(db);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThat(count(db, "SELECT count(*) FROM payroll.accounting_mapping")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM payroll.accounting_journal")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM payroll.accounting_journal_line"))
              .isEqualTo(1);
          // No override and no deletion exist: the append-only guards refuse to empty the tables.
          assertThatThrownBy(() -> script(db, "DELETE FROM payroll.accounting_journal_line"))
              .isInstanceOf(Exception.class);
          assertThatThrownBy(() -> script(db, rollbackScript())).isInstanceOf(Exception.class);
          assertThat(count(db, "SELECT count(*) FROM payroll.accounting_journal_line"))
              .isEqualTo(1);
        });
  }
}
