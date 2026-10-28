package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * MVP-081 V42 directly against PostgreSQL in a database of its own (D81-14 item 14): the refusing
 * rollback that restores V41 exactly, the 100 open expenses per employee and 5,000 per tenant
 * limits (the database is the final authority, whatever the application checks), and the query
 * plans of the three list shapes (an index, never a sort of the tenant's expenses).
 */
@IntegrationTest
class ExpenseMigrationIntegrationTest {

  /** The operations schema: tables, columns, indexes, constraints, triggers and functions. */
  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'operations'), '')
          || '|' || coalesce((SELECT string_agg(table_name || '.' || column_name || ':'
                                  || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, ''),
                                  ',' ORDER BY table_name, column_name)
                              FROM information_schema.columns
                              WHERE table_schema = 'operations'), '')
          || '|' || coalesce((SELECT string_agg(indexname || ':' || indexdef, ','
                                  ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'operations'), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY conname)
                              FROM pg_constraint
                              WHERE connamespace = 'operations'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(pg_get_triggerdef(t.oid), ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'operations'::regnamespace
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ','
                                  ORDER BY proname) FROM pg_proc
                              WHERE pronamespace = 'operations'::regnamespace), '')
      """;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcConnectionDetails connection;

  private record Db(String url, JdbcTemplate jdbc) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "expense81_" + UUID.randomUUID().toString().replace("-", "");
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

  private static String resource(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Runs a script as written (no JDBC escape processing, as Flyway sends it). */
  private static void script(Db db, String sql) {
    db.jdbc()
        .execute(
            (org.springframework.jdbc.core.ConnectionCallback<Void>)
                c -> {
                  try (java.sql.Statement statement = c.createStatement()) {
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

  private static void exec(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      statement.execute();
    }
  }

  private static UUID organization(JdbcTemplate db) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone,"
            + " status, created_at, created_by) VALUES (?, 'Org', 'CD', 'fr', 'Africa/Kinshasa',"
            + " 'ACTIVE', now(), 'test')",
        id);
    return id;
  }

  private static UUID employee(JdbcTemplate db, UUID tenant) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,"
            + " search_key, created_at, created_by) VALUES (?, ?, ?, 'Ana', 'Mbuyi',"
            + " ' ana mbuyi ', now(), 'test')",
        id,
        tenant,
        "E-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT));
    return id;
  }

  private static final String EXPENSE =
      "INSERT INTO operations.expense (tenant_id, id, employee_id, state, cycle, current_revision,"
          + " version, business_date, currency, amount_minor, category, receipt_present,"
          + " created_at, created_by, last_action_at) VALUES (?, ?, ?, 'DRAFT', 1, 1, 1,"
          + " current_date, 'USD', 1250, 'TRANSPORT', false, now(), 'subject', now())";
  private static final String REVISION =
      "INSERT INTO operations.expense_revision (tenant_id, expense_id, revision, business_date,"
          + " currency, exponent, amount_minor, category, merchant, description, created_at)"
          + " VALUES (?, ?, 1, current_date, 'USD', 2, 1250, 'TRANSPORT', 'Taxi Gombe',"
          + " 'Course aller-retour vers le client', now())";
  private static final String ACTION =
      "INSERT INTO operations.expense_action (tenant_id, expense_id, seq, action, occurred_at,"
          + " actor_subject, actor_role, actor_employee_id, from_state, to_state, revision, cycle,"
          + " receipt_present) VALUES (?, ?, 1, 'CREATED', now(), 'subject', 'employee', ?, NULL,"
          + " 'DRAFT', 1, 1, false)";

  private void createExpense(Db db, UUID tenant, UUID employee) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection c = connect(db)) {
      exec(c, EXPENSE, tenant, id, employee);
      exec(c, REVISION, tenant, id);
      exec(c, ACTION, tenant, id, employee);
      c.commit();
    }
  }

  @Test
  void theRollbackRestoresV41ExactlyAndRefusesOnceAnExpenseExists() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "41");
          String v41 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "42");
          String v42 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v42).isNotEqualTo(v41).contains("expense_action");
          int recorded =
              db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class);
          script(db, resource("db/rollback/V42__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v41);
          assertThat(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
              .isEqualTo(recorded);

          // With no rows the rollback ran; the migration can be applied again.
          script(db, resource("db/migration/V42__operations_expense_lifecycle.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v42);

          // Once an expense exists, the rollback refuses and changes nothing.
          UUID tenant = organization(db.jdbc());
          UUID who = employee(db.jdbc(), tenant);
          try {
            createExpense(db, tenant, who);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> script(db, resource("db/rollback/V42__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V42 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v42);
          assertThat(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM operations.expense", Integer.class))
              .isEqualTo(1);
        });
  }

  @Test
  void openExpensesAreLimitedPerEmployeeAndPerTenantByTheDatabase() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "42");
          UUID tenant = organization(db.jdbc());
          UUID one = employee(db.jdbc(), tenant);
          String bulk =
              "INSERT INTO operations.expense (tenant_id, id, employee_id, state, cycle,"
                  + " current_revision, version, business_date, currency, amount_minor, category,"
                  + " receipt_present, created_at, created_by, last_action_at)"
                  + " SELECT ?, gen_random_uuid(), ?, 'DRAFT', 1, 1, 1, current_date, 'USD', 100,"
                  + " 'OTHER', false, now(), 'subject', now() FROM generate_series(1, ?)";
          // 100 open expenses of one employee pass; the 101st is refused by name.
          try (Connection c = connect(db)) {
            exec(c, bulk, tenant, one, 100);
            assertThatThrownBy(() -> exec(c, bulk, tenant, one, 1))
                .isInstanceOfSatisfying(
                    PSQLException.class,
                    e ->
                        assertThat(e.getServerErrorMessage().getConstraint())
                            .isEqualTo("operations_expense_employee_limit"));
            c.rollback();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          // 50 employees at 100 each fill the tenant's 5,000; one more employee is refused.
          try (Connection c = connect(db)) {
            for (int i = 0; i < 50; i++) {
              exec(c, bulk, tenant, employee(db.jdbc(), tenant), 100);
            }
            UUID extra = employee(db.jdbc(), tenant);
            assertThatThrownBy(() -> exec(c, bulk, tenant, extra, 1))
                .isInstanceOfSatisfying(
                    PSQLException.class,
                    e ->
                        assertThat(e.getServerErrorMessage().getConstraint())
                            .isEqualTo("operations_expense_tenant_limit"));
            c.rollback();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Test
  void theListShapesUseTheirIndexes() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "42");
          UUID tenant = organization(db.jdbc());
          UUID who = employee(db.jdbc(), tenant);
          String bulk =
              "INSERT INTO operations.expense (tenant_id, id, employee_id, state, cycle,"
                  + " current_revision, version, business_date, currency, amount_minor, category,"
                  + " receipt_present, created_at, created_by, last_action_at)"
                  + " SELECT ?, gen_random_uuid(), ?, 'DRAFT', 1, 1, 1, current_date, 'USD', 100,"
                  + " 'OTHER', false, now() - make_interval(secs => g), 'subject', now()"
                  + " FROM generate_series(1, 100) g";
          try (Connection c = connect(db);
              java.sql.Statement s = c.createStatement()) {
            // Uncommitted rows are enough for the planner; everything is rolled back.
            exec(c, bulk, tenant, who);
            for (int i = 0; i < 29; i++) {
              exec(c, bulk, tenant, employee(db.jdbc(), tenant));
            }
            s.execute("ANALYZE operations.expense");
            s.execute("SET LOCAL enable_seqscan = off");
            String ids = "tenant_id = '" + tenant + "' ";
            assertThat(
                    plan(
                        s,
                        "SELECT id FROM operations.expense WHERE "
                            + ids
                            + "AND employee_id = '"
                            + who
                            + "' ORDER BY created_at DESC, id DESC LIMIT 26"))
                .contains("operations_expense_employee_order")
                .doesNotContain("Sort");
            assertThat(
                    plan(
                        s,
                        "SELECT id FROM operations.expense WHERE "
                            + ids
                            + "ORDER BY created_at DESC, id DESC LIMIT 26"))
                .contains("operations_expense_tenant_order")
                .doesNotContain("Sort");
            // The manager queue: with the general indexes out of the way (rolled back below),
            // the partial index alone serves the submitted-only, oldest-first shape.
            s.execute("DROP INDEX operations.operations_expense_employee_order");
            s.execute("DROP INDEX operations.operations_expense_tenant_order");
            assertThat(
                    plan(
                        s,
                        "SELECT id FROM operations.expense WHERE "
                            + ids
                            + "AND employee_id = '"
                            + who
                            + "' AND state = 'SUBMITTED' ORDER BY submitted_at, id LIMIT 26"))
                .contains("operations_expense_queue")
                .doesNotContain("Sort");
            c.rollback();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static String plan(java.sql.Statement s, String sql) throws SQLException {
    StringBuilder text = new StringBuilder();
    try (java.sql.ResultSet rows = s.executeQuery("EXPLAIN " + sql)) {
      while (rows.next()) {
        text.append(rows.getString(1)).append('\n');
      }
    }
    return text.toString();
  }
}
