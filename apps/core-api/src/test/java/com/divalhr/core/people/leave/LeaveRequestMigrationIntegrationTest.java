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
import java.sql.Timestamp;
import java.time.Instant;
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
 * MVP-041A V19 directly against PostgreSQL, bypassing the application (the database is the final
 * authority): the refusing rollback that restores V18 exactly, tenant-composite keys to the
 * employee's own employment and to the policy version, the date, amount and state rules,
 * insert-only rows that are never truncated, non-overlapping pending requests, and a narrow index
 * assertion for the employee's own history.
 */
@IntegrationTest
class LeaveRequestMigrationIntegrationTest {

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

  private static final LocalDate START = LocalDate.of(2026, 3, 1);

  private static final String REQUEST =
      "INSERT INTO people.leave_request (id, tenant_id, employee_id, employment_id,"
          + " policy_version_id, start_date, end_date, requested_amount, state, submitted_at,"
          + " submitted_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'test')";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  private record Hired(UUID tenant, UUID employee, UUID employment) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "leave19_" + UUID.randomUUID().toString().replace("-", "");
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

  private static Hired hire(Connection c, UUID tenant, String number) throws SQLException {
    Hired h = new Hired(tenant, UUID.randomUUID(), UUID.randomUUID());
    UUID change = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,"
            + " search_key, created_at, created_by) VALUES (?, ?, ?, 'Ana', 'Mbuyi',"
            + " ' ana mbuyi ', now(), 'test')",
        h.employee(),
        tenant,
        number);
    exec(
        c,
        "INSERT INTO people.employment (id, tenant_id, employee_id, effective_from, created_at,"
            + " created_by) VALUES (?, ?, ?, ?, now(), 'test')",
        h.employment(),
        tenant,
        h.employee(),
        START);
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, recorded_at, recorded_by, version_after) VALUES (?, ?, ?,"
            + " ?, 'HIRE', ?, ARRAY['PLACEMENT'], now(), 'test', 0)",
        change,
        tenant,
        h.employee(),
        h.employment(),
        START);
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, legal_entity_id, site_id, created_by_change_id,"
            + " origin_change_id) VALUES (?, ?, ?, ?, 'PLACEMENT', ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        tenant,
        h.employee(),
        h.employment(),
        START,
        UUID.randomUUID(),
        UUID.randomUUID(),
        change,
        change);
    return h;
  }

  /** A policy and its version 1; returns the version id. */
  private static UUID policyVersion(Connection c, UUID tenant, String code) throws SQLException {
    UUID policy = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.leave_policy (id, tenant_id, code, created_at, created_by)"
            + " VALUES (?, ?, ?, now(), 'test')",
        policy,
        tenant,
        code);
    exec(
        c,
        "INSERT INTO people.leave_policy_version (id, tenant_id, policy_id, version_number,"
            + " name_en, name_fr, unit, balance_mode, annual_entitlement, minimum_service_days,"
            + " approval_route, payroll_effect, effective_from, effective_to, created_at,"
            + " created_by) VALUES (?, ?, ?, 1, 'Annual', 'Annuel', 'DAYS', 'UNTRACKED', NULL, 0,"
            + " 'MANAGER', 'PAID', DATE '2026-01-01', NULL, now(), 'test')",
        version,
        tenant,
        policy);
    return version;
  }

  /** A valid request row; each test breaks one column. */
  private static Object[] row(Hired h, UUID version, LocalDate start, LocalDate end) {
    return new Object[] {
      UUID.randomUUID(),
      h.tenant(),
      h.employee(),
      h.employment(),
      version,
      start,
      end,
      new BigDecimal("2.50"),
      "PENDING",
      Timestamp.from(Instant.now())
    };
  }

  private static Object[] change(Object[] row, int index, Object value) {
    Object[] copy = row.clone();
    copy[index] = value;
    return copy;
  }

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV18ExactlyAndRefusesOnceARequestExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "18");
          String v18 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "19");
          String v19 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v19).isNotEqualTo(v18).contains("leave_request");
          int recorded = history(db);
          db.jdbc().execute(resource("db/rollback/V19__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v18);
          assertThat(history(db)).isEqualTo(recorded);

          db.jdbc().execute(resource("db/migration/V19__leave_request.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v19);
          UUID tenant = organization(db.jdbc());
          try {
            committed(
                db,
                c -> {
                  Hired h = hire(c, tenant, "E-1");
                  exec(c, REQUEST, row(h, policyVersion(c, tenant, "AN"), START, START));
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V19__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V19 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v19);
        });
  }

  private static int history(Db db) {
    return java.util.Objects.requireNonNull(
        db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
  }

  // ------------------------------------------------------------------------------------------
  // Constraints
  // ------------------------------------------------------------------------------------------

  @Test
  void everyRuleIsEnforcedByTheDatabase() {
    withDatabase(
        db -> {
          migrate(db.url(), "19");
          UUID tenant = organization(db.jdbc());
          UUID other = organization(db.jdbc());
          try {
            Hired[] people = new Hired[3];
            UUID[] versions = new UUID[2];
            committed(
                db,
                c -> {
                  people[0] = hire(c, tenant, "E-1");
                  people[1] = hire(c, tenant, "E-2");
                  people[2] = hire(c, other, "E-1");
                  versions[0] = policyVersion(c, tenant, "AN");
                  versions[1] = policyVersion(c, other, "AN");
                });
            Hired a = people[0];
            Hired b = people[1];
            Hired foreign = people[2];
            UUID version = versions[0];
            LocalDate d = LocalDate.of(2026, 11, 2);
            Object[] valid = row(a, version, d, d.plusDays(2));
            committed(db, c -> exec(c, REQUEST, valid));

            // Keys: the employment must be the employee's own, everything in one tenant.
            refused(
                db,
                "leave_request_employment",
                c ->
                    exec(
                        c,
                        REQUEST,
                        change(
                            row(a, version, d.plusDays(50), d.plusDays(50)), 3, b.employment())));
            refused(
                db,
                "leave_request_employment",
                c ->
                    exec(
                        c,
                        REQUEST,
                        change(row(a, version, d.plusDays(51), d.plusDays(51)), 1, other)));
            refused(
                db,
                "leave_request_policy_version",
                c -> exec(c, REQUEST, row(a, versions[1], d.plusDays(30), d.plusDays(30))));
            refused(
                db,
                "leave_request_employment",
                c -> exec(c, REQUEST, change(row(foreign, version, d, d), 1, tenant)));

            record Broken(String constraint, int index, Object value) {}
            for (Broken broken :
                List.of(
                    new Broken("leave_request_period_valid", 5, LocalDate.of(1899, 12, 31)),
                    new Broken("leave_request_period_valid", 6, LocalDate.of(3000, 1, 1)),
                    new Broken("leave_request_period_valid", 6, d.plusDays(99)),
                    new Broken("leave_request_amount_valid", 7, BigDecimal.ZERO),
                    new Broken("leave_request_amount_valid", 7, new BigDecimal("10000.01")),
                    new Broken("leave_request_amount_valid", 7, new BigDecimal("1.005")),
                    new Broken("leave_request_state_valid", 8, "APPROVED"))) {
              // Start d + 100: an end at d + 99 is before it.
              Object[] bad =
                  change(
                      row(b, version, d.plusDays(100), d.plusDays(100)),
                      broken.index(),
                      broken.value());
              refused(db, broken.constraint(), c -> exec(c, REQUEST, bad));
            }

            // Pending requests of one employee never overlap (both ends inclusive); another
            // employee's may.
            refused(
                db,
                "leave_request_no_overlap",
                c -> exec(c, REQUEST, row(a, version, d.plusDays(2), d.plusDays(3))));
            committed(db, c -> exec(c, REQUEST, row(a, version, d.plusDays(3), d.plusDays(3))));
            committed(db, c -> exec(c, REQUEST, row(b, version, d, d.plusDays(2))));

            // Insert-only and never truncated.
            for (String sql :
                List.of(
                    "UPDATE people.leave_request SET requested_amount = 1 WHERE id = '"
                        + valid[0]
                        + "'",
                    "UPDATE people.leave_request SET employee_id = employee_id WHERE id = '"
                        + valid[0]
                        + "'",
                    "DELETE FROM people.leave_request WHERE id = '" + valid[0] + "'")) {
              refused(db, "leave_request_immutable", c -> exec(c, sql));
            }
            refused(
                db,
                "leave_request_no_truncate",
                c -> {
                  try (Statement st = c.createStatement()) {
                    st.execute("TRUNCATE people.leave_request");
                  }
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Query plan (narrow index assertion; no large fixture)
  // ------------------------------------------------------------------------------------------

  @Test
  void theHistoryReadsTheSelfIndexInOrderWithoutSorting() {
    withDatabase(
        db -> {
          migrate(db.url(), "19");
          UUID tenant = organization(db.jdbc());
          try {
            Hired[] h = new Hired[1];
            committed(
                db,
                c -> {
                  h[0] = hire(c, tenant, "E-1");
                  exec(
                      c,
                      REQUEST,
                      row(
                          h[0],
                          policyVersion(c, tenant, "AN"),
                          START.plusDays(300),
                          START.plusDays(300)));
                });
            try (Connection c = connect(db)) {
              exec(c, "SET LOCAL enable_seqscan = off");
              StringBuilder plan = new StringBuilder();
              try (PreparedStatement explain =
                  c.prepareStatement(
                      "EXPLAIN SELECT r.id, r.start_date, r.end_date, r.requested_amount,"
                          + " r.submitted_at FROM people.leave_request r"
                          + " WHERE r.tenant_id = ? AND r.employee_id = ?"
                          + " AND (r.submitted_at, r.id) < (?, ?)"
                          + " ORDER BY r.submitted_at DESC, r.id DESC LIMIT 26")) {
                explain.setObject(1, tenant);
                explain.setObject(2, h[0].employee());
                explain.setObject(3, Timestamp.from(Instant.now()));
                explain.setObject(4, UUID.randomUUID());
                try (var rows = explain.executeQuery()) {
                  while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                  }
                }
              }
              c.rollback();
              assertThat(plan.toString())
                  .containsPattern("Index (Only )?Scan using leave_request_self_order")
                  .contains("Index Cond: ((tenant_id = ")
                  .doesNotContain("Sort");
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
