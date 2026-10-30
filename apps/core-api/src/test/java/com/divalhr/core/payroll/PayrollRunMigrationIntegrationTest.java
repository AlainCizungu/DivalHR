package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
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
 * MVP-094A V44 directly against PostgreSQL in a database of its own (D94A-6, D94A-8): the refusing
 * rollback that restores V43 exactly, and every rule the database enforces whatever the application
 * checks: the append-only policy sequence, the legal pay day and method, the next DRAFT version,
 * the frozen policy in force, exactly one DRAFT per chain, the supersession by the successor only,
 * the gross recomputed by the database (HALF_UP once, calendar or fixed 30-day denominator), the
 * exact ranges and sources, the exact totals and the immutability of every row. Each refusal is
 * proved by the constraint it names. Synthetic data only.
 */
@IntegrationTest
class PayrollRunMigrationIntegrationTest {

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
      "INSERT INTO payroll.run_policy (tenant_id, legal_entity_id, id, sequence, effective_from,"
          + " pay_day_kind, pay_day, proration_method, created_at, created_by)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), 'subject')";
  private static final String RUN =
      "INSERT INTO payroll.pay_run (tenant_id, legal_entity_id, id, period_start, currency,"
          + " version, status, policy_id, policy_sequence, pay_day_kind, pay_day, proration_method,"
          + " employee_count, total_gross_minor, input_digest, created_at, created_by)"
          + " VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, 'LAST_DAY', NULL, ?, ?, ?, repeat('a', 64),"
          + " now(), 'subject')";
  private static final String LINE =
      "INSERT INTO payroll.pay_run_line (tenant_id, run_id, id, employee_id, employee_number,"
          + " eligible_days, denominator, full_month, monthly_minor, gross_minor, explanation_codes)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, string_to_array(?, ','))";
  private static final String RANGE =
      "INSERT INTO payroll.pay_run_line_range (tenant_id, run_id, line_id, seq, range_from,"
          + " range_to) VALUES (?, ?, ?, ?, ?, ?)";
  private static final String SOURCE =
      "INSERT INTO payroll.pay_run_line_source (tenant_id, run_id, line_id, seq, kind, source_id,"
          + " source_version, valid_from, valid_to) VALUES (?, ?, ?, ?, ?, ?, 0, ?, NULL)";

  private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
  private static final LocalDate APR = LocalDate.of(2026, 4, 1);
  private static final LocalDate SEP = LocalDate.of(2026, 9, 1);
  private static final LocalDate FEB = LocalDate.of(2026, 2, 1);
  private static final LocalDate MAR = LocalDate.of(2026, 3, 1);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcConnectionDetails connection;

  private record Db(String url, JdbcTemplate jdbc) {}

  /** A tenant and its legal entity, with the first policy (CALENDAR_DAYS from JAN) committed. */
  private record Shape(UUID tenant, UUID entity, UUID policy) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "payroll94_" + UUID.randomUUID().toString().replace("-", "");
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

  private static UUID legalEntity(JdbcTemplate db, UUID tenant) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO tenant.legal_entity (id, tenant_id, code, name, country_code, effective_from,"
            + " created_at, created_by) VALUES (?, ?, ?, 'Societe', 'CD', DATE '2020-01-01', now(),"
            + " 'test')",
        id,
        tenant,
        "LE-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT));
    return id;
  }

  private static UUID policy(
      Connection c,
      UUID tenant,
      UUID entity,
      int sequence,
      LocalDate from,
      String kind,
      Integer day,
      String method)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(c, POLICY, tenant, entity, id, sequence, from, kind, day, method);
    return id;
  }

  private static void run(
      Connection c,
      Shape s,
      UUID id,
      LocalDate period,
      String currency,
      int version,
      UUID policy,
      int policySequence,
      String method,
      int count,
      long total)
      throws SQLException {
    exec(
        c,
        RUN,
        s.tenant(),
        s.entity(),
        id,
        period,
        currency,
        version,
        policy,
        policySequence,
        method,
        count,
        total);
  }

  private static void line(
      Connection c,
      Shape s,
      UUID run,
      UUID id,
      String number,
      int days,
      Integer denominator,
      boolean full,
      long monthly,
      long gross)
      throws SQLException {
    exec(
        c,
        LINE,
        s.tenant(),
        run,
        id,
        UUID.randomUUID(),
        number,
        days,
        denominator,
        full,
        monthly,
        gross,
        full ? "FULL_MONTH" : "PRORATED_CALENDAR_DAYS,STARTED_IN_MONTH");
  }

  private static void evidence(
      Connection c, Shape s, UUID run, UUID line, LocalDate from, LocalDate to)
      throws SQLException {
    exec(c, RANGE, s.tenant(), run, line, 1, from, to);
    int seq = 1;
    for (String kind : new String[] {"EMPLOYMENT", "PLACEMENT", "COMPENSATION"}) {
      exec(c, SOURCE, s.tenant(), run, line, seq++, kind, UUID.randomUUID(), JAN);
    }
  }

  /** A line with its range (the last {@code days} days of the month) and sources. */
  private static UUID complete(
      Connection c,
      Shape s,
      UUID run,
      LocalDate period,
      int days,
      Integer denominator,
      boolean full,
      long monthly,
      long gross)
      throws SQLException {
    UUID id = UUID.randomUUID();
    line(
        c,
        s,
        run,
        id,
        "E-" + id.toString().substring(0, 6),
        days,
        denominator,
        full,
        monthly,
        gross);
    LocalDate last = period.plusMonths(1).minusDays(1);
    evidence(c, s, run, id, last.minusDays(days - 1), last);
    return id;
  }

  /** A tenant and entity with the CALENDAR_DAYS policy from JAN, committed. */
  private Shape seeded(Db db) throws SQLException {
    UUID tenant = organization(db.jdbc());
    UUID entity = legalEntity(db.jdbc(), tenant);
    try (Connection c = connect(db)) {
      UUID policy = policy(c, tenant, entity, 1, JAN, "LAST_DAY", null, "CALENDAR_DAYS");
      c.commit();
      return new Shape(tenant, entity, policy);
    }
  }

  /** A committed DRAFT of September with one partial line: 20 of 30 days of 1,250.00. */
  private UUID seededRun(Db db, Shape s) throws SQLException {
    UUID run = UUID.randomUUID();
    try (Connection c = connect(db)) {
      run(c, s, run, SEP, "USD", 1, s.policy(), 1, "CALENDAR_DAYS", 1, 83333);
      complete(c, s, run, SEP, 20, 30, false, 125_000, 83333);
      c.commit();
    }
    return run;
  }

  private void refuses(Db db, String constraint, SqlWork work) {
    refusesOneOf(db, List.of(constraint), work);
  }

  private void refusesOneOf(Db db, List<String> constraints, SqlWork work) {
    assertThatThrownBy(
            () -> {
              try (Connection c = connect(db)) {
                try {
                  work.run(c);
                  exec(c, "SET CONSTRAINTS ALL IMMEDIATE");
                } finally {
                  c.rollback();
                }
              }
            })
        .as(constraints.toString())
        .isInstanceOfSatisfying(
            PSQLException.class,
            e -> assertThat(e.getServerErrorMessage().getConstraint()).isIn(constraints));
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV43ExactlyAndRefusesOnceAPolicyOrRunExists() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "43");
          String v43 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "44");
          String v44 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v44).isNotEqualTo(v43).contains("pay_run_line_source");
          int recorded =
              java.util.Objects.requireNonNull(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
          script(db, resource("db/rollback/V44__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v43);
          assertThat(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
              .isEqualTo(recorded);

          // With no rows the rollback ran; the migration can be applied again.
          script(db, resource("db/migration/V44__payroll_monthly_runs.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v44);

          // Once a policy exists, the rollback refuses and changes nothing.
          try {
            seeded(db);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> script(db, resource("db/rollback/V44__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V44 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v44);
          assertThat(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM payroll.run_policy", Integer.class))
              .isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Policy
  // ------------------------------------------------------------------------------------------

  @Test
  void thePolicyIsAppendOnlyWithTheNextSequenceAndALegalShape() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "44");
          try {
            Shape s = seeded(db);
            refuses(
                db,
                "payroll_run_policy_sequence",
                c -> policy(c, s.tenant(), s.entity(), 3, APR, "LAST_DAY", null, "NONE"));
            refuses(
                db,
                "payroll_run_policy_sequence",
                c -> policy(c, s.tenant(), s.entity(), 1, APR, "LAST_DAY", null, "NONE"));
            refuses(
                db,
                "payroll_run_policy_effective_unique",
                c -> policy(c, s.tenant(), s.entity(), 2, JAN, "LAST_DAY", null, "NONE"));
            refuses(
                db,
                "payroll_run_policy_pay_day",
                c -> policy(c, s.tenant(), s.entity(), 2, APR, "DAY", 29, "NONE"));
            refuses(
                db,
                "payroll_run_policy_pay_day",
                c -> policy(c, s.tenant(), s.entity(), 2, APR, "LAST_DAY", 5, "NONE"));
            refuses(
                db,
                "payroll_run_policy_method",
                c -> policy(c, s.tenant(), s.entity(), 2, APR, "DAY", 5, "HOURLY"));
            refuses(
                db,
                "payroll_run_policy_legal_entity",
                c -> policy(c, s.tenant(), UUID.randomUUID(), 1, APR, "DAY", 5, "NONE"));
            refuses(
                db,
                "payroll_run_immutable",
                c -> exec(c, "UPDATE payroll.run_policy SET pay_day = 3"));
            refuses(db, "payroll_run_immutable", c -> exec(c, "DELETE FROM payroll.run_policy"));
            refuses(
                db, "payroll_run_immutable", c -> exec(c, "TRUNCATE payroll.run_policy CASCADE"));
            try (Connection c = connect(db)) {
              policy(c, s.tenant(), s.entity(), 2, APR, "DAY", 5, "FIXED_30_DAYS");
              c.commit();
            }
            // The sixtieth policy is the last one.
            try (Connection c = connect(db)) {
              for (int i = 3; i <= 60; i++) {
                policy(
                    c,
                    s.tenant(),
                    s.entity(),
                    i,
                    APR.plusDays(i),
                    "DAY",
                    5,
                    i % 2 == 0 ? "NONE" : "CALENDAR_DAYS");
              }
              c.commit();
            }
            refuses(
                db,
                "payroll_run_policy_sequence_range",
                c -> policy(c, s.tenant(), s.entity(), 61, APR.plusDays(61), "DAY", 5, "NONE"));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Runs and chains
  // ------------------------------------------------------------------------------------------

  @Test
  void aRunIsTheNextDraftVersionOfItsChainWithTheFrozenPolicyInForce() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "44");
          try {
            Shape s = seeded(db);
            refuses(
                db,
                "payroll_pay_run_version",
                c ->
                    run(
                        c,
                        s,
                        UUID.randomUUID(),
                        SEP,
                        "USD",
                        2,
                        s.policy(),
                        1,
                        "CALENDAR_DAYS",
                        1,
                        1));
            refuses(
                db,
                "payroll_pay_run_policy_in_force",
                c ->
                    run(
                        c,
                        s,
                        UUID.randomUUID(),
                        SEP,
                        "USD",
                        1,
                        s.policy(),
                        1,
                        "FIXED_30_DAYS",
                        1,
                        1));
            refuses(
                db,
                "payroll_pay_run_policy_in_force",
                c ->
                    run(
                        c,
                        s,
                        UUID.randomUUID(),
                        SEP,
                        "USD",
                        1,
                        s.policy(),
                        2,
                        "CALENDAR_DAYS",
                        1,
                        1));
            refuses(
                db,
                "payroll_pay_run_policy_in_force",
                c ->
                    run(
                        c,
                        s,
                        UUID.randomUUID(),
                        LocalDate.of(2025, 12, 1),
                        "USD",
                        1,
                        s.policy(),
                        1,
                        "CALENDAR_DAYS",
                        1,
                        1));
            refuses(
                db,
                "payroll_pay_run_month",
                c ->
                    run(
                        c,
                        s,
                        UUID.randomUUID(),
                        SEP.plusDays(1),
                        "USD",
                        1,
                        s.policy(),
                        1,
                        "CALENDAR_DAYS",
                        1,
                        1));
            refuses(
                db,
                "payroll_pay_run_currency",
                c ->
                    run(
                        c,
                        s,
                        UUID.randomUUID(),
                        SEP,
                        "EUR",
                        1,
                        s.policy(),
                        1,
                        "CALENDAR_DAYS",
                        1,
                        1));
            refuses(
                db,
                "payroll_pay_run_employee_count",
                c ->
                    run(
                        c,
                        s,
                        UUID.randomUUID(),
                        SEP,
                        "USD",
                        1,
                        s.policy(),
                        1,
                        "CALENDAR_DAYS",
                        0,
                        0));
            refuses(
                db,
                "payroll_pay_run_total",
                c ->
                    run(
                        c,
                        s,
                        UUID.randomUUID(),
                        SEP,
                        "USD",
                        1,
                        s.policy(),
                        1,
                        "CALENDAR_DAYS",
                        1,
                        50_000_000_001L));
            // A run with no lines, or a total that is not the lines' total, is refused at commit.
            refuses(
                db,
                "payroll_pay_run_exact",
                c ->
                    run(
                        c,
                        s,
                        UUID.randomUUID(),
                        SEP,
                        "USD",
                        1,
                        s.policy(),
                        1,
                        "CALENDAR_DAYS",
                        1,
                        83333));
            refuses(
                db,
                "payroll_pay_run_exact",
                c -> {
                  UUID id = UUID.randomUUID();
                  run(c, s, id, SEP, "USD", 1, s.policy(), 1, "CALENDAR_DAYS", 1, 83334);
                  complete(c, s, id, SEP, 20, 30, false, 125_000, 83333);
                });
            // Another tenant's entity or policy cannot be named.
            refuses(
                db,
                "payroll_pay_run_policy_in_force",
                c ->
                    exec(
                        c,
                        RUN,
                        s.tenant(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        SEP,
                        "USD",
                        1,
                        s.policy(),
                        1,
                        "CALENDAR_DAYS",
                        1,
                        1));
            seededRun(db, s);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Test
  void aRecalculationSupersedesTheDraftByItsSuccessorAndNothingElseMoves() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "44");
          try {
            Shape s = seeded(db);
            UUID first = seededRun(db, s);
            // A second DRAFT of the chain cannot exist next to the first.
            refuses(
                db,
                "payroll_pay_run_one_draft",
                c -> {
                  UUID id = UUID.randomUUID();
                  run(c, s, id, SEP, "USD", 2, s.policy(), 1, "CALENDAR_DAYS", 1, 83333);
                  complete(c, s, id, SEP, 20, 30, false, 125_000, 83333);
                });
            // Supersession names a successor that must exist at commit.
            refusesOneOf(
                db,
                List.of("payroll_pay_run_successor", "payroll_pay_run_chain"),
                c ->
                    exec(
                        c,
                        "UPDATE payroll.pay_run SET status = 'SUPERSEDED', superseded_at = now(),"
                            + " superseded_by_run_id = gen_random_uuid() WHERE id = ?",
                        first));
            // Nothing but the supersession is a legal update.
            refuses(
                db,
                "payroll_pay_run_supersession_only",
                c ->
                    exec(
                        c, "UPDATE payroll.pay_run SET total_gross_minor = 1 WHERE id = ?", first));
            refuses(
                db,
                "payroll_pay_run_supersession_only",
                c -> exec(c, "UPDATE payroll.pay_run SET employee_count = 2 WHERE id = ?", first));
            refuses(
                db,
                "payroll_pay_run_supersession_only",
                c ->
                    exec(
                        c,
                        "UPDATE payroll.pay_run SET excluded_other_currency_count = 1"
                            + " WHERE id = ?",
                        first));
            refuses(
                db,
                "payroll_run_immutable",
                c -> exec(c, "DELETE FROM payroll.pay_run WHERE id = ?", first));
            refuses(db, "payroll_run_immutable", c -> exec(c, "TRUNCATE payroll.pay_run CASCADE"));
            // The legal order: supersede first, naming the successor, then insert it.
            UUID second = UUID.randomUUID();
            try (Connection c = connect(db)) {
              exec(
                  c,
                  "UPDATE payroll.pay_run SET status = 'SUPERSEDED', superseded_at = now(),"
                      + " superseded_by_run_id = ? WHERE id = ?",
                  second,
                  first);
              run(c, s, second, SEP, "USD", 2, s.policy(), 1, "CALENDAR_DAYS", 1, 83333);
              complete(c, s, second, SEP, 20, 30, false, 125_000, 83333);
              c.commit();
            }
            assertThat(
                    db.jdbc()
                        .queryForObject(
                            "SELECT status FROM payroll.pay_run WHERE id = ?", String.class, first))
                .isEqualTo("SUPERSEDED");
            // A superseded run is never superseded again, and takes no new line.
            refuses(
                db,
                "payroll_pay_run_supersession_only",
                c ->
                    exec(
                        c,
                        "UPDATE payroll.pay_run SET status = 'SUPERSEDED', superseded_at = now(),"
                            + " superseded_by_run_id = ? WHERE id = ?",
                        second,
                        first));
            refuses(
                db,
                "payroll_pay_run_line_run_state",
                c -> line(c, s, first, UUID.randomUUID(), "E-LATE", 20, 30, false, 125_000, 83333));
            // A superseded version that does not name its real successor breaks the chain.
            refuses(
                db,
                "payroll_pay_run_chain",
                c -> {
                  UUID third = UUID.randomUUID();
                  exec(
                      c,
                      "UPDATE payroll.pay_run SET status = 'SUPERSEDED', superseded_at = now(),"
                          + " superseded_by_run_id = ? WHERE id = ?",
                      first,
                      second);
                  run(c, s, third, SEP, "USD", 3, s.policy(), 1, "CALENDAR_DAYS", 1, 83333);
                  complete(c, s, third, SEP, 20, 30, false, 125_000, 83333);
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Lines
  // ------------------------------------------------------------------------------------------

  @Test
  void theDatabaseRecomputesTheGrossAndAcceptsTheReferenceVectors() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "44");
          try {
            Shape s = seeded(db);
            // March, CALENDAR_DAYS: 10 of 31 days of 1,250.00 is 403.23 (HALF_UP once).
            try (Connection c = connect(db)) {
              UUID run = UUID.randomUUID();
              run(c, s, run, MAR, "USD", 1, s.policy(), 1, "CALENDAR_DAYS", 1, 40323);
              complete(c, s, run, MAR, 10, 31, false, 125_000, 40323);
              c.commit();
            }
            UUID second = UUID.randomUUID();
            try (Connection c = connect(db)) {
              policy(c, s.tenant(), s.entity(), 2, APR, "LAST_DAY", null, "FIXED_30_DAYS");
              c.commit();
            }
            // April, FIXED_30_DAYS: 10 of 30 days is 416.67.
            Shape fixed = new Shape(s.tenant(), s.entity(), policyId(db, s, 2));
            try (Connection c = connect(db)) {
              run(c, fixed, second, APR, "USD", 1, fixed.policy(), 2, "FIXED_30_DAYS", 1, 41667);
              complete(c, fixed, second, APR, 10, 30, false, 125_000, 41667);
              c.commit();
            }
            // A wrong gross, an early rounding or a hand-made denominator never reach the table:
            // February (28 days), 10 days of 1,250.00 is 446.43.
            for (long wrong : new long[] {44642, 44644, 40000, 125_000}) {
              refuses(
                  db,
                  "payroll_pay_run_line_gross",
                  c -> {
                    UUID run = UUID.randomUUID();
                    run(c, s, run, FEB, "USD", 1, s.policy(), 1, "CALENDAR_DAYS", 1, wrong);
                    complete(c, s, run, FEB, 10, 28, false, 125_000, wrong);
                  });
            }
            try (Connection c = connect(db)) {
              UUID run = UUID.randomUUID();
              run(c, s, run, FEB, "USD", 1, s.policy(), 1, "CALENDAR_DAYS", 1, 44643);
              complete(c, s, run, FEB, 10, 28, false, 125_000, 44643);
              c.commit();
            }
            refuses(
                db,
                "payroll_pay_run_line_denominator_rule",
                c -> {
                  UUID run = UUID.randomUUID();
                  run(c, fixed, run, SEP, "CDF", 1, fixed.policy(), 2, "FIXED_30_DAYS", 1, 1);
                  line(c, fixed, run, UUID.randomUUID(), "E-1", 20, 31, false, 450_000, 300_000);
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static UUID policyId(Db db, Shape s, int sequence) {
    return db.jdbc()
        .queryForObject(
            "SELECT id FROM payroll.run_policy WHERE tenant_id = ? AND sequence = ?",
            UUID.class,
            s.tenant(),
            sequence);
  }

  @Test
  void aLineIsFullMonthExactlyWhenEveryDayIsEligibleAndNoneRefusesPartialMonths() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "44");
          try {
            Shape s = seeded(db);
            refuses(
                db,
                "payroll_pay_run_line_full_month",
                c -> {
                  UUID run = UUID.randomUUID();
                  run(c, s, run, SEP, "USD", 1, s.policy(), 1, "CALENDAR_DAYS", 1, 125_000);
                  line(c, s, run, UUID.randomUUID(), "E-1", 29, null, true, 125_000, 125_000);
                });
            refuses(
                db,
                "payroll_pay_run_line_month",
                c -> {
                  UUID run = UUID.randomUUID();
                  run(c, s, run, SEP, "USD", 1, s.policy(), 1, "CALENDAR_DAYS", 1, 125_000);
                  line(c, s, run, UUID.randomUUID(), "E-1", 31, null, true, 125_000, 125_000);
                });
            refuses(
                db,
                "payroll_pay_run_line_scale",
                c -> {
                  UUID run = UUID.randomUUID();
                  run(c, s, run, SEP, "USD", 1, s.policy(), 1, "CALENDAR_DAYS", 1, 10_000_001);
                  line(c, s, run, UUID.randomUUID(), "E-1", 30, null, true, 10_000_001, 10_000_001);
                });
            // A full month is the exact amount, with no denominator.
            try (Connection c = connect(db)) {
              UUID run = UUID.randomUUID();
              run(c, s, run, SEP, "USD", 1, s.policy(), 1, "CALENDAR_DAYS", 1, 125_000);
              complete(c, s, run, SEP, 30, null, true, 125_000, 125_000);
              c.commit();
            }
            try (Connection c = connect(db)) {
              policy(c, s.tenant(), s.entity(), 2, APR, "LAST_DAY", null, "NONE");
              c.commit();
            }
            Shape none = new Shape(s.tenant(), s.entity(), policyId(db, s, 2));
            refuses(
                db,
                "payroll_pay_run_line_partial_none",
                c -> {
                  UUID run = UUID.randomUUID();
                  run(
                      c,
                      none,
                      run,
                      LocalDate.of(2026, 5, 1),
                      "USD",
                      1,
                      none.policy(),
                      2,
                      "NONE",
                      1,
                      83333);
                  line(c, none, run, UUID.randomUUID(), "E-1", 20, 31, false, 125_000, 83333);
                });
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Test
  void rangesAndSourcesMustBeExactAndEveryRowIsImmutable() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "44");
          try {
            Shape s = seeded(db);
            UUID run = seededRun(db, s);
            // Ranges that do not add up to the eligible days.
            refuses(
                db,
                "payroll_pay_run_line_ranges_exact",
                c -> {
                  UUID id = UUID.randomUUID();
                  UUID next = UUID.randomUUID();
                  exec(
                      c,
                      "UPDATE payroll.pay_run SET status = 'SUPERSEDED', superseded_at = now(),"
                          + " superseded_by_run_id = ? WHERE id = ?",
                      next,
                      run);
                  run(c, s, next, SEP, "USD", 2, s.policy(), 1, "CALENDAR_DAYS", 1, 83333);
                  line(c, s, next, id, "E-1", 20, 30, false, 125_000, 83333);
                  exec(c, RANGE, s.tenant(), next, id, 1, SEP, SEP.plusDays(18));
                  for (int i = 0; i < 3; i++) {
                    exec(
                        c,
                        SOURCE,
                        s.tenant(),
                        next,
                        id,
                        i + 1,
                        new String[] {"EMPLOYMENT", "PLACEMENT", "COMPENSATION"}[i],
                        UUID.randomUUID(),
                        JAN);
                  }
                });
            // A missing kind of source.
            refuses(
                db,
                "payroll_pay_run_line_sources_exact",
                c -> {
                  UUID id = UUID.randomUUID();
                  UUID next = UUID.randomUUID();
                  exec(
                      c,
                      "UPDATE payroll.pay_run SET status = 'SUPERSEDED', superseded_at = now(),"
                          + " superseded_by_run_id = ? WHERE id = ?",
                      next,
                      run);
                  run(c, s, next, SEP, "USD", 2, s.policy(), 1, "CALENDAR_DAYS", 1, 83333);
                  line(c, s, next, id, "E-1", 20, 30, false, 125_000, 83333);
                  exec(c, RANGE, s.tenant(), next, id, 1, SEP.plusDays(10), SEP.plusDays(29));
                  exec(c, SOURCE, s.tenant(), next, id, 1, "EMPLOYMENT", UUID.randomUUID(), JAN);
                });
            // Overlapping or out-of-month ranges.
            refuses(
                db,
                "payroll_pay_run_line_ranges_order",
                c -> {
                  UUID id = UUID.randomUUID();
                  UUID next = UUID.randomUUID();
                  exec(
                      c,
                      "UPDATE payroll.pay_run SET status = 'SUPERSEDED', superseded_at = now(),"
                          + " superseded_by_run_id = ? WHERE id = ?",
                      next,
                      run);
                  run(c, s, next, SEP, "USD", 2, s.policy(), 1, "CALENDAR_DAYS", 1, 83333);
                  line(c, s, next, id, "E-1", 20, 30, false, 125_000, 83333);
                  exec(c, RANGE, s.tenant(), next, id, 1, SEP.plusDays(15), SEP.plusDays(34));
                  for (int i = 0; i < 3; i++) {
                    exec(
                        c,
                        SOURCE,
                        s.tenant(),
                        next,
                        id,
                        i + 1,
                        new String[] {"EMPLOYMENT", "PLACEMENT", "COMPENSATION"}[i],
                        UUID.randomUUID(),
                        JAN);
                  }
                });
            refuses(
                db,
                "payroll_run_immutable",
                c -> exec(c, "UPDATE payroll.pay_run_line SET gross_minor = 1"));
            refuses(db, "payroll_run_immutable", c -> exec(c, "DELETE FROM payroll.pay_run_line"));
            refuses(
                db,
                "payroll_run_immutable",
                c -> exec(c, "UPDATE payroll.pay_run_line_range SET range_to = range_to"));
            refuses(
                db,
                "payroll_run_immutable",
                c -> exec(c, "DELETE FROM payroll.pay_run_line_source"));
            refuses(
                db,
                "payroll_run_immutable",
                c -> exec(c, "TRUNCATE payroll.pay_run_line_range CASCADE"));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
