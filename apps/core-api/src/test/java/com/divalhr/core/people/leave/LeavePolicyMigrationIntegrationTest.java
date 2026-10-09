package com.divalhr.core.people.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MVP-040A V18 directly against PostgreSQL, bypassing the application (the database is the final
 * authority): the refusing rollback that restores V17 exactly, the code, name, enum, entitlement,
 * service-day and period constraints, tenant-composite keys, insert-only rows that are never
 * truncated, the deferred version-1 requirement, and a narrow index assertion for the list query.
 */
@IntegrationTest
class LeavePolicyMigrationIntegrationTest {

  /** The people schema: tables, columns, indexes, constraints, triggers and functions. */
  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'people'), '')
          || '|' || coalesce((SELECT string_agg(table_name || '.' || column_name || ':'
                                  || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, ''),
                                  ',' ORDER BY table_name, column_name)
                              FROM information_schema.columns
                              WHERE table_schema = 'people'), '')
          || '|' || coalesce((SELECT string_agg(indexname || ':' || indexdef, ','
                                  ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'people'), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY conname)
                              FROM pg_constraint
                              WHERE connamespace = 'people'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(c.relname || '.' || t.tgname, ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'people'::regnamespace
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ','
                                  ORDER BY proname) FROM pg_proc
                              WHERE pronamespace = 'people'::regnamespace), '')
      """;

  private static final String POLICY =
      "INSERT INTO people.leave_policy (id, tenant_id, code, created_at, created_by)"
          + " VALUES (?, ?, ?, now(), 'test')";

  private static final String VERSION =
      "INSERT INTO people.leave_policy_version (id, tenant_id, policy_id, version_number,"
          + " name_en, name_fr, unit, balance_mode, annual_entitlement, minimum_service_days,"
          + " approval_route, payroll_effect, effective_from, effective_to, created_at,"
          + " created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), 'test')";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "leave18_" + UUID.randomUUID().toString().replace("-", "");
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

  private Connection connect(Db db) throws SQLException {
    Connection c =
        DriverManager.getConnection(db.url(), postgres.getUsername(), postgres.getPassword());
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

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  private void refused(Db db, String constraint, SqlWork work) throws SQLException {
    try (Connection c = connect(db)) {
      assertThatThrownBy(
              () -> {
                work.run(c);
                c.commit();
              })
          .as(constraint)
          .isInstanceOfSatisfying(
              PSQLException.class,
              e -> {
                org.postgresql.util.ServerErrorMessage message = e.getServerErrorMessage();
                assertThat(message == null ? "unnamed" : String.valueOf(message.getConstraint()))
                    .isEqualTo(constraint);
              });
      c.rollback();
    }
  }

  private void committed(Db db, SqlWork work) throws SQLException {
    try (Connection c = connect(db)) {
      work.run(c);
      c.commit();
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

  /** A valid version row; each test breaks one column. */
  private static Object[] version(UUID tenant, UUID policy) {
    return new Object[] {
      UUID.randomUUID(),
      tenant,
      policy,
      1,
      "Annual leave",
      "Congé annuel",
      "DAYS",
      "TRACKED",
      new BigDecimal("25.00"),
      0,
      "MANAGER",
      "PAID",
      LocalDate.of(2026, 1, 1),
      null
    };
  }

  private static Object[] change(Object[] row, int index, Object value) {
    Object[] copy = row.clone();
    copy[index] = value;
    return copy;
  }

  private static UUID policy(Connection c, UUID tenant, String code) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(c, POLICY, id, tenant, code);
    exec(c, VERSION, version(tenant, id));
    return id;
  }

  private static int history(Db db) {
    return java.util.Objects.requireNonNull(
        db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
  }

  // ------------------------------------------------------------------------------------------
  // Rollback (AC13)
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV17ExactlyAndRefusesOnceAPolicyExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "17");
          String v17 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "18");
          String v18 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v18).isNotEqualTo(v17).contains("leave_policy_version");
          int recorded = history(db);
          db.jdbc().execute(resource("db/rollback/V18__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v17);
          assertThat(history(db)).isEqualTo(recorded);

          // Re-apply V18 by hand (Flyway still records it) and add a policy: refused, no override.
          db.jdbc().execute(resource("db/migration/V18__leave_policy.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v18);
          UUID tenant = organization(db.jdbc());
          try {
            committed(db, c -> policy(c, tenant, "ANNUAL"));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V18__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V18 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v18);
          assertThat(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM people.leave_policy", Integer.class))
              .isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Constraints (AC3, AC4, AC6, AC13)
  // ------------------------------------------------------------------------------------------

  @Test
  void everyRuleIsEnforcedByTheDatabase() {
    withDatabase(
        db -> {
          migrate(db.url(), "18");
          UUID tenant = organization(db.jdbc());
          UUID other = organization(db.jdbc());
          try {
            UUID annual = UUID.randomUUID();
            committed(
                db,
                c -> {
                  exec(c, POLICY, annual, tenant, "ANNUAL");
                  exec(c, VERSION, version(tenant, annual));
                });
            // The same code in another tenant is accepted; in the same tenant it is refused.
            committed(db, c -> policy(c, other, "ANNUAL"));
            refused(db, "leave_policy_code_unique", c -> policy(c, tenant, "ANNUAL"));
            for (String code : List.of("A", "annual", "-AB", "A".repeat(21), "A B", "ÉTÉ")) {
              refused(db, "leave_policy_code_format", c -> policy(c, tenant, code));
            }
            // A policy commits only with its version 1.
            refused(
                db,
                "leave_policy_has_version",
                c -> exec(c, POLICY, UUID.randomUUID(), tenant, "ALONE"));
            // Versions belong to a policy of the same tenant.
            refused(
                db, "leave_policy_version_policy", c -> exec(c, VERSION, version(other, annual)));

            UUID next = UUID.randomUUID();
            SqlWork root = c -> exec(c, POLICY, next, tenant, "NEXT");
            Object[] valid = version(tenant, next);
            record Broken(String constraint, int index, Object value) {}
            List<Broken> broken =
                List.of(
                    new Broken("leave_policy_version_v1", 3, 2),
                    new Broken("leave_policy_version_name_en_valid", 4, "A"),
                    new Broken("leave_policy_version_name_en_valid", 4, " Annual"),
                    new Broken("leave_policy_version_name_en_valid", 4, "x".repeat(101)),
                    new Broken("leave_policy_version_name_fr_valid", 5, "Conge\u0301"),
                    new Broken("leave_policy_version_name_fr_valid", 5, "Congé\nannuel"),
                    new Broken("leave_policy_version_unit_valid", 6, "WEEKS"),
                    new Broken("leave_policy_version_balance_mode_valid", 7, "tracked"),
                    new Broken("leave_policy_version_entitlement_valid", 8, null),
                    new Broken("leave_policy_version_entitlement_valid", 8, BigDecimal.ZERO),
                    new Broken(
                        "leave_policy_version_entitlement_valid", 8, new BigDecimal("10000.01")),
                    new Broken(
                        "leave_policy_version_entitlement_valid", 8, new BigDecimal("1.005")),
                    new Broken("leave_policy_version_service_days_range", 9, -1),
                    new Broken("leave_policy_version_service_days_range", 9, 3651),
                    new Broken("leave_policy_version_approval_route_valid", 10, "HR"),
                    new Broken("leave_policy_version_payroll_effect_valid", 11, "PARTIAL"),
                    new Broken("leave_policy_version_period_valid", 12, LocalDate.of(1899, 12, 31)),
                    new Broken(
                        "leave_policy_version_period_valid", 13, LocalDate.of(2025, 12, 31)));
            for (Broken b : broken) {
              refused(
                  db,
                  b.constraint(),
                  c -> {
                    root.run(c);
                    exec(c, VERSION, change(valid, b.index(), b.value()));
                  });
            }
            // Untracked: no entitlement.
            refused(
                db,
                "leave_policy_version_entitlement_valid",
                c -> {
                  root.run(c);
                  exec(c, VERSION, change(valid, 7, "UNTRACKED"));
                });
            committed(
                db,
                c -> {
                  root.run(c);
                  exec(
                      c,
                      VERSION,
                      change(
                          change(change(valid, 7, "UNTRACKED"), 8, null),
                          13,
                          LocalDate.of(2026, 1, 1)));
                });
            // Version periods of one policy never overlap.
            assertThat(
                    db.jdbc()
                        .queryForObject(
                            "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE conname = 'leave_policy_version_no_overlap'",
                            String.class))
                .isEqualTo(
                    "EXCLUDE USING gist (tenant_id WITH =, policy_id WITH =,"
                        + " daterange(effective_from, effective_to, '[]'::text) WITH &&)");

            // Insert-only and never truncated.
            for (String sql :
                List.of(
                    "UPDATE people.leave_policy SET code = 'OTHER' WHERE id = '" + annual + "'",
                    "DELETE FROM people.leave_policy WHERE id = '" + annual + "'")) {
              refused(db, "leave_policy_immutable", c -> exec(c, sql));
            }
            for (String sql :
                List.of(
                    "UPDATE people.leave_policy_version SET name_en = 'Changed'"
                        + " WHERE policy_id = '"
                        + annual
                        + "'",
                    "UPDATE people.leave_policy_version SET effective_to = DATE '2026-12-31'"
                        + " WHERE policy_id = '"
                        + annual
                        + "'",
                    "DELETE FROM people.leave_policy_version WHERE policy_id = '" + annual + "'")) {
              refused(db, "leave_policy_version_immutable", c -> exec(c, sql));
            }
            for (String table : List.of("leave_policy_version", "leave_policy")) {
              refused(
                  db,
                  "leave_policy_no_truncate",
                  c -> {
                    try (Statement st = c.createStatement()) {
                      st.execute("TRUNCATE people." + table + " CASCADE");
                    }
                  });
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Query plan (narrow index assertion; no large fixture)
  // ------------------------------------------------------------------------------------------

  @Test
  void theListReadsTheKeysetIndexInOrderWithoutSorting() {
    withDatabase(
        db -> {
          migrate(db.url(), "18");
          UUID tenant = organization(db.jdbc());
          try {
            committed(db, c -> policy(c, tenant, "ANNUAL"));
            try (Connection c = connect(db)) {
              exec(c, "SET LOCAL enable_seqscan = off");
              StringBuilder plan = new StringBuilder();
              try (PreparedStatement explain =
                  c.prepareStatement(
                      "EXPLAIN SELECT p.id, p.code, p.created_at, v.* FROM people.leave_policy p"
                          + " CROSS JOIN LATERAL (SELECT * FROM people.leave_policy_version x"
                          + " WHERE x.tenant_id = p.tenant_id AND x.policy_id = p.id"
                          + " ORDER BY x.version_number DESC LIMIT 1) v"
                          + " WHERE p.tenant_id = ? AND (p.code, p.id) > (?, ?)"
                          + " ORDER BY p.code, p.id LIMIT 26")) {
                explain.setObject(1, tenant);
                explain.setString(2, "A");
                explain.setObject(3, UUID.randomUUID());
                try (var rows = explain.executeQuery()) {
                  while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                  }
                }
              }
              c.rollback();
              assertThat(plan.toString())
                  .containsPattern(
                      "Index (Only )?Scan using leave_policy_list_order on leave_policy p")
                  .contains("Index Cond: ((tenant_id = ")
                  .contains("(ROW(code, id) > ROW(")
                  .containsPattern(
                      "Index (Only )?Scan Backward using leave_policy_version_number_unique")
                  .doesNotContain("Sort");
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
