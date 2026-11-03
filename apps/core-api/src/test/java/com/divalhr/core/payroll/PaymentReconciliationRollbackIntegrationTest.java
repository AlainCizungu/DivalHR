package com.divalhr.core.payroll;

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
 * MVP-095C (Issue #190, ADR 0038): the manual V49 rollback script, directly against PostgreSQL in a
 * database of its own. It restores the V48 schema exactly while no reconciliation exists, and it
 * refuses (changing nothing) as soon as a reconciliation or an inquiry row exists: that evidence is
 * never erased by a schema rollback. Synthetic data only.
 */
@IntegrationTest
class PaymentReconciliationRollbackIntegrationTest {

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
    String database = "payroll95c_" + UUID.randomUUID().toString().replace("-", "");
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
      return new ClassPathResource("db/rollback/V49__rollback.sql")
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
            .hasMessageContaining("V49 rollback refused");
      } finally {
        c.rollback();
      }
    }
  }

  @Test
  void theRollbackRestoresV48ExactlyWhileNoReconciliationExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "48");
          String v48 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "49");
          String v49 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v49).isNotEqualTo(v48).contains("payment_reconciliation_inquiry");
          script(db, rollbackScript());
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v48);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM information_schema.tables WHERE table_schema ="
                          + " 'payroll' AND table_name LIKE 'payment_reconciliation%'"))
              .isZero();
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM pg_proc WHERE pronamespace = 'payroll'::regnamespace"
                          + " AND proname LIKE 'payment_reconciliation%'"))
              .isZero();
          // V49 seeded nothing and the rollback does not edit the Flyway history.
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history WHERE version = '49'"))
              .isEqualTo(1);
        });
  }

  @Test
  void theRollbackRefusesWhileAReconciliationExistsAndChangesNothing() {
    withDatabase(
        db -> {
          migrate(db.url(), "49");
          try (Connection c = connect(db)) {
            PaymentReconciliationFixtures.Submission s =
                PaymentReconciliationFixtures.submission(c, UUID.randomUUID(), "DELIVERY_UNKNOWN");
            PaymentReconciliationFixtures.open(c, s);
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
          assertThat(count(db, "SELECT count(*) FROM payroll.payment_reconciliation")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM payroll.payment_reconciliation_inquiry"))
              .isEqualTo(1);
        });
  }

  @Test
  void theRollbackStillRefusesAfterTheReconciliationConcluded() {
    withDatabase(
        db -> {
          migrate(db.url(), "49");
          try (Connection c = connect(db)) {
            PaymentReconciliationFixtures.Submission s =
                PaymentReconciliationFixtures.submission(
                    c, UUID.randomUUID(), "MANUAL_INTERVENTION");
            PaymentReconciliationFixtures.Reconciliation r =
                PaymentReconciliationFixtures.open(c, s);
            PaymentReconciliationFixtures.concludeAs(c, r, "CONFIRMED_ACCEPTED_FOR_PROCESSING");
            c.commit();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          try {
            rollbackRefused(db);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThat(count(db, "SELECT count(*) FROM payroll.payment_reconciliation_inquiry"))
              .isEqualTo(3);
        });
  }
}
