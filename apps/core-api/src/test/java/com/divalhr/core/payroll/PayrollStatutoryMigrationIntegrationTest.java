package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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
 * MVP-094B V45 directly against PostgreSQL in a database of its own (D94B-1, D94B-8, ADR 0034
 * sections 2, 4, 5 and 6): the seed (exactly one unverified DRAFT pack with six unhashed official
 * sources), the refusing rollback that restores V44 exactly, and every rule the database enforces
 * whatever the application checks: the closed enums, the allow-listed official hosts, the forward
 * only pack status, the evidence a VERIFIED pack needs, the immutability of rules and sources, the
 * activation of a VERIFIED pack only, the append-only profile, the arithmetic and the deferred
 * totals of a calculation, one DRAFT per run and pack, the supersession order, and the absence of
 * any delete or truncate. Each refusal is proved by the constraint it names. Synthetic data only:
 * the VERIFIED packs here are {@code SYNTHETIC-TEST-*}, created through the owner connection, and
 * never appear in a migration or a seed.
 */
@IntegrationTest
class PayrollStatutoryMigrationIntegrationTest {

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

  private static final String PACK =
      "INSERT INTO payroll.statutory_rule_pack (id, code, version, country, currency, status,"
          + " effective_from, effective_to, method_version, rules, created_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, CAST(? AS date), CAST(? AS date), ?, CAST(? AS jsonb),"
          + " now())";
  private static final String SOURCE =
      "INSERT INTO payroll.statutory_rule_pack_source (pack_id, seq, source_key, title, url,"
          + " sha256, retrieved_on) VALUES (?, ?, ?, ?, ?, ?, DATE '2026-10-29')";
  private static final String VERIFY =
      "UPDATE payroll.statutory_rule_pack SET status = 'VERIFIED',"
          + " reviewer_name = 'Synthetic Test Reviewer', reviewer_organization = 'Test Only',"
          + " reviewed_on = DATE '2026-10-01', attested = true, verified_at = now() WHERE id = ?";
  private static final String RETIRE =
      "UPDATE payroll.statutory_rule_pack SET status = 'RETIRED', retired_at = now()"
          + " WHERE id = ?";
  private static final String ACTIVATION =
      "INSERT INTO payroll.statutory_activation (tenant_id, legal_entity_id, id, sequence,"
          + " pack_id, from_month, acknowledged, activated_at, activated_by)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, now(), 'subject')";
  private static final String PROFILE =
      "INSERT INTO payroll.statutory_profile (tenant_id, employee_id, id, sequence,"
          + " effective_from, country, classification, cnss_subject, irpp_subject, evidence_code,"
          + " created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), 'subject')";
  private static final String POLICY =
      "INSERT INTO payroll.run_policy (tenant_id, legal_entity_id, id, sequence, effective_from,"
          + " pay_day_kind, pay_day, proration_method, created_at, created_by)"
          + " VALUES (?, ?, ?, 1, DATE '2026-01-01', 'LAST_DAY', NULL, 'CALENDAR_DAYS', now(),"
          + " 'subject')";
  private static final String RUN =
      "INSERT INTO payroll.pay_run (tenant_id, legal_entity_id, id, period_start, currency,"
          + " version, status, policy_id, policy_sequence, pay_day_kind, pay_day, proration_method,"
          + " employee_count, total_gross_minor, input_digest, created_at, created_by)"
          + " VALUES (?, ?, ?, DATE '2026-09-01', ?, 1, 'DRAFT', ?, 1, 'LAST_DAY', NULL,"
          + " 'CALENDAR_DAYS', ?, ?, repeat('a', 64), now(), 'subject')";
  private static final String SOURCE_LINE =
      "INSERT INTO payroll.pay_run_line (tenant_id, run_id, id, employee_id, employee_number,"
          + " eligible_days, denominator, full_month, monthly_minor, gross_minor,"
          + " explanation_codes) VALUES (?, ?, ?, ?, ?, 30, NULL, true, ?, ?, ARRAY['FULL_MONTH'])";
  private static final String RANGE =
      "INSERT INTO payroll.pay_run_line_range (tenant_id, run_id, line_id, seq, range_from,"
          + " range_to) VALUES (?, ?, ?, 1, DATE '2026-09-01', DATE '2026-09-30')";
  private static final String RUN_SOURCE =
      "INSERT INTO payroll.pay_run_line_source (tenant_id, run_id, line_id, seq, kind, source_id,"
          + " source_version, valid_from, valid_to) VALUES (?, ?, ?, ?, ?, ?, 0, DATE"
          + " '2026-01-01', NULL)";
  private static final String CALCULATION =
      "INSERT INTO payroll.statutory_calculation (tenant_id, legal_entity_id, id, run_id,"
          + " run_version, period_start, currency, pack_id, activation_id, version, status,"
          + " supported_count, excluded_count, blocked_count, gross_total_minor,"
          + " employee_deductions_total_minor, employer_contributions_total_minor,"
          + " preview_net_total_minor, employer_cost_total_minor, source_run_digest,"
          + " profile_digest, rule_pack_digest, calculation_digest, created_at, created_by)"
          + " VALUES (?, ?, ?, ?, 1, DATE '2026-09-01', ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?,"
          + " repeat('a', 64), repeat('b', 64), repeat('c', 64), repeat('d', 64), now(),"
          + " 'subject')";
  private static final String CALCULATION_LINE =
      "INSERT INTO payroll.statutory_calculation_line (tenant_id, calculation_id, id, run_id,"
          + " source_line_id, employee_id, employee_number, gross_minor,"
          + " employee_deductions_minor, employer_contributions_minor, preview_net_minor,"
          + " employer_cost_minor, excluded) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
  private static final String COMPONENT =
      "INSERT INTO payroll.statutory_calculation_component (tenant_id, calculation_id, line_id,"
          + " seq, component_id, party, category, base_minor, amount_minor, explanation_codes,"
          + " trace) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, string_to_array(?, ','), CAST(? AS jsonb))";

  /** The first source of the reference pack and the one its IRPP component cites. */
  private static final String CNSS_URL = "https://cnss.cd/?page_id=1388";

  private static final String DGI_URL =
      "https://dgi.gouv.cd/impot-sur-le-revenu-des-personnes-physiques-irpp/";
  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcConnectionDetails connection;

  private record Db(String url, JdbcTemplate jdbc) {}

  /**
   * Two tenants with a legal entity each, three employees, one CDF run of the first tenant with two
   * full-month lines (162,000 and 500,000), and four synthetic packs (VERIFIED for 2026, DRAFT,
   * RETIRED, VERIFIED for January to June) of which the first and the last are activated.
   */
  private record World(
      UUID tenant,
      UUID entity,
      UUID otherTenant,
      UUID otherEntity,
      UUID employeeOne,
      UUID employeeTwo,
      UUID foreignEmployee,
      UUID policy,
      UUID run,
      UUID lineOne,
      UUID lineTwo,
      UUID verified,
      UUID draft,
      UUID retired,
      UUID firstHalf,
      UUID activation,
      UUID halfActivation) {}

  @FunctionalInterface
  private interface DbWork {
    void run(Db db) throws Exception;
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  private void withDatabase(DbWork body) {
    String database = "payroll94b_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url = connection.getJdbcUrl().replaceAll("/[^/?]+(\\?.*)?$", "/" + database + "$1");
      body.run(
          new Db(
              url,
              new JdbcTemplate(
                  new DriverManagerDataSource(
                      url, connection.getUsername(), connection.getPassword()))));
    } catch (Exception e) {
      throw new IllegalStateException(e);
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

  private void commit(Db db, SqlWork work) throws SQLException {
    try (Connection c = connect(db)) {
      work.run(c);
      c.commit();
    }
  }

  private void refuses(Db db, String constraint, SqlWork work) {
    refusesOneOf(db, List.of(constraint), work);
  }

  private void refusesOneOf(Db db, List<String> constraints, SqlWork work) {
    assertThatThrownBy(() -> attempt(db, work))
        .as(constraints.toString())
        .isInstanceOfSatisfying(
            PSQLException.class,
            e -> assertThat(e.getServerErrorMessage().getConstraint()).isIn(constraints));
  }

  private void refusesWithState(Db db, String sqlState, SqlWork work) {
    assertThatThrownBy(() -> attempt(db, work))
        .isInstanceOfSatisfying(
            PSQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(sqlState));
  }

  /** Runs the work, judges every deferred constraint, and always rolls back. */
  private void attempt(Db db, SqlWork work) throws SQLException {
    try (Connection c = connect(db)) {
      try {
        work.run(c);
        exec(c, "SET CONSTRAINTS ALL IMMEDIATE");
      } finally {
        c.rollback();
      }
    }
  }

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

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

  /** The rules of the seeded reference pack, as the text of a jsonb value. */
  private String seedRules(Db db) {
    return db.jdbc()
        .queryForObject(
            "SELECT rules::text FROM payroll.statutory_rule_pack WHERE code = 'CD-SALARY-PREVIEW'",
            String.class);
  }

  /**
   * A synthetic pack with the rules of the seed and two hashed sources (the two keys its components
   * cite), brought to the given final status through the legal updates.
   */
  private void syntheticPack(
      Connection c, String rules, UUID id, String code, String from, String to, String status)
      throws SQLException {
    exec(c, PACK, id, code, 1, "CD", "CDF", "DRAFT", from, to, "1", rules);
    exec(c, SOURCE, id, 1, "CNSS_RATES", "CNSS rates", CNSS_URL, HASH_A);
    exec(c, SOURCE, id, 2, "DGI_IRPP", "DGI IRPP", DGI_URL, HASH_B);
    if (!status.equals("DRAFT")) {
      exec(c, VERIFY, id);
    }
    if (status.equals("RETIRED")) {
      exec(c, RETIRE, id);
    }
  }

  private World world(Db db) throws SQLException {
    UUID tenant = organization(db.jdbc());
    UUID other = organization(db.jdbc());
    UUID entity = legalEntity(db.jdbc(), tenant);
    UUID otherEntity = legalEntity(db.jdbc(), other);
    UUID one = employee(db.jdbc(), tenant);
    UUID two = employee(db.jdbc(), tenant);
    UUID foreign = employee(db.jdbc(), other);
    UUID policy = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID lineOne = UUID.randomUUID();
    UUID lineTwo = UUID.randomUUID();
    UUID verified = UUID.randomUUID();
    UUID draft = UUID.randomUUID();
    UUID retired = UUID.randomUUID();
    UUID half = UUID.randomUUID();
    UUID activation = UUID.randomUUID();
    UUID halfActivation = UUID.randomUUID();
    String rules = seedRules(db);
    commit(
        db,
        c -> {
          String year = "2026-12-31";
          syntheticPack(c, rules, verified, "SYNTHETIC-TEST-V", "2026-01-01", year, "VERIFIED");
          syntheticPack(c, rules, draft, "SYNTHETIC-TEST-D", "2026-01-01", year, "DRAFT");
          syntheticPack(c, rules, retired, "SYNTHETIC-TEST-R", "2026-01-01", year, "RETIRED");
          String june = "2026-06-30";
          syntheticPack(c, rules, half, "SYNTHETIC-TEST-H1", "2026-01-01", june, "VERIFIED");
        });
    commit(
        db,
        c -> {
          exec(c, POLICY, tenant, entity, policy);
          exec(c, RUN, tenant, entity, run, "CDF", policy, 2, 662_000L);
          sourceLine(c, tenant, run, lineOne, one, "E-1", 162_000);
          sourceLine(c, tenant, run, lineTwo, two, "E-2", 500_000);
        });
    commit(
        db,
        c -> {
          LocalDate january = LocalDate.of(2026, 1, 1);
          exec(c, ACTIVATION, tenant, entity, activation, 1, verified, january, true);
          LocalDate march = LocalDate.of(2026, 3, 1);
          exec(c, ACTIVATION, tenant, entity, halfActivation, 2, half, march, true);
        });
    return new World(
        tenant,
        entity,
        other,
        otherEntity,
        one,
        two,
        foreign,
        policy,
        run,
        lineOne,
        lineTwo,
        verified,
        draft,
        retired,
        half,
        activation,
        halfActivation);
  }

  private static void sourceLine(
      Connection c, UUID tenant, UUID run, UUID line, UUID employee, String number, long gross)
      throws SQLException {
    exec(c, SOURCE_LINE, tenant, run, line, employee, number, gross, gross);
    exec(c, RANGE, tenant, run, line);
    int seq = 1;
    for (String kind : new String[] {"EMPLOYMENT", "PLACEMENT", "COMPENSATION"}) {
      exec(c, RUN_SOURCE, tenant, run, line, seq++, kind, UUID.randomUUID());
    }
  }

  /**
   * A calculation of the first run: one supported line (162,000) and one excluded line (500,000).
   * The supported line: employee CNSS 8,100 and IRPP 4,860 (deductions 12,960); employer 8,100 +
   * 2,430 + 10,530 (contributions 21,060); net 149,040; cost 183,060. Totals: gross 662,000,
   * deductions 12,960, contributions 21,060, net 649,040, cost 683,060. {@code fault} breaks one
   * rule at a time.
   */
  private static void calculation(
      Connection c, World w, UUID id, UUID pack, UUID activation, int version, String fault)
      throws SQLException {
    UUID supported = UUID.randomUUID();
    UUID excluded = UUID.randomUUID();
    boolean deductionsFault = fault.equals("deductions");
    boolean grossFault = fault.equals("grossTotal");
    boolean lineFault = fault.equals("lineGross");
    long deductions = deductionsFault ? 12_961L : 12_960L;
    long grossTotal = grossFault ? 662_001L : 662_000L;
    long netTotal = 662_000L - deductions;
    if (fault.equals("arithmetic")) {
      netTotal++;
    }
    if (grossFault) {
      netTotal++;
    }
    long costTotal = grossFault ? 683_061L : 683_060L;
    long excludedGross = lineFault ? 500_001L : 500_000L;
    exec(
        c,
        CALCULATION,
        w.tenant(),
        w.entity(),
        id,
        w.run(),
        fault.equals("usd") ? "USD" : "CDF",
        pack,
        activation,
        version,
        "DRAFT",
        fault.equals("count") ? 2 : 1,
        fault.equals("blocked") ? 1 : 0,
        grossTotal,
        deductions,
        21_060L,
        netTotal,
        costTotal);
    exec(
        c,
        CALCULATION_LINE,
        w.tenant(),
        id,
        supported,
        w.run(),
        w.lineOne(),
        w.employeeOne(),
        "E-1",
        162_000L,
        deductions,
        21_060L,
        162_000L - deductions,
        183_060L,
        false);
    exec(
        c,
        CALCULATION_LINE,
        w.tenant(),
        id,
        excluded,
        w.run(),
        w.lineTwo(),
        w.employeeTwo(),
        "E-2",
        excludedGross,
        0L,
        0L,
        excludedGross,
        excludedGross,
        true);
    if (fault.equals("excludedComponent")) {
      exec(
          c,
          COMPONENT,
          w.tenant(),
          id,
          excluded,
          1,
          "CNSS_EMPLOYEE_PENSION",
          "EMPLOYEE",
          "SOCIAL_CONTRIBUTION",
          0L,
          0L,
          "NOT_SUBJECT",
          null);
    }
    component(c, w, id, supported, 1, "CNSS_EMPLOYEE_PENSION", "EMPLOYEE", 8_100L);
    component(c, w, id, supported, 2, "CNSS_EMPLOYER_PENSION", "EMPLOYER", 8_100L);
    component(c, w, id, supported, 3, "CNSS_EMPLOYER_OCCUPATIONAL_RISK", "EMPLOYER", 2_430L);
    if (!fault.equals("missingComponent")) {
      String family =
          fault.equals("wrongRule") ? "IRPP_SALARY_WITHHOLDING" : "CNSS_EMPLOYER_FAMILY_BENEFITS";
      component(c, w, id, supported, 4, family, "EMPLOYER", 10_530L);
    }
    exec(
        c,
        COMPONENT,
        w.tenant(),
        id,
        supported,
        5,
        "IRPP_SALARY_WITHHOLDING",
        "EMPLOYEE",
        "INCOME_TAX",
        162_000L,
        4_860L,
        "PROGRESSIVE_APPLIED,BASE_GROSS",
        "{\"annualBase\": 1944000, \"numerator\": 583200000, \"capApplied\": false}");
  }

  private static void component(
      Connection c,
      World w,
      UUID calculation,
      UUID line,
      int seq,
      String id,
      String party,
      long amount)
      throws SQLException {
    exec(
        c,
        COMPONENT,
        w.tenant(),
        calculation,
        line,
        seq,
        id,
        party,
        "SOCIAL_CONTRIBUTION",
        162_000L,
        amount,
        "RATE_APPLIED,BASE_GROSS",
        null);
  }

  private UUID committedCalculation(Db db, World w, int version) throws SQLException {
    UUID id = UUID.randomUUID();
    commit(db, c -> calculation(c, w, id, w.verified(), w.activation(), version, ""));
    return id;
  }

  // ------------------------------------------------------------------------------------------
  // Seed
  // ------------------------------------------------------------------------------------------

  @Test
  void theSeedIsExactlyOneUnverifiedDraftPackWithItsSixUnhashedOfficialSources() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          assertThat(count(db, "payroll.statutory_rule_pack")).isEqualTo(1);
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT code || '|' || version || '|' || country || '|' || currency"
                              + " || '|' || status || '|' || effective_from || '|' || effective_to"
                              + " || '|' || method_version FROM payroll.statutory_rule_pack",
                          String.class))
              .isEqualTo("CD-SALARY-PREVIEW|1|CD|CDF|DRAFT|2026-01-01|2026-12-31|1");
          // No reviewer, no review date, no attestation, no verification or retirement time.
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT count(*) FROM payroll.statutory_rule_pack WHERE status <> 'DRAFT'"
                              + " OR reviewer_name IS NOT NULL OR reviewer_organization IS NOT NULL"
                              + " OR reviewed_on IS NOT NULL OR attested OR verified_at IS NOT NULL"
                              + " OR retired_at IS NOT NULL",
                          Integer.class))
              .isEqualTo(0);
          // The digest is the SHA-256 of the jsonb text, generated by the database.
          String rules = seedRules(db);
          String digest =
              HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest(rules.getBytes(StandardCharsets.UTF_8)));
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT rules_digest FROM payroll.statutory_rule_pack", String.class))
              .isEqualTo(digest);
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT jsonb_array_length(rules -> 'components') || ':'"
                              + " || (rules ->> 'schemaVersion') FROM payroll.statutory_rule_pack",
                          String.class))
              .isEqualTo("5:1");
          // The six sources of the issue, in order, with no hash, retrieved on 2026-10-29.
          List<String> sources =
              db.jdbc()
                  .queryForList(
                      "SELECT seq || '|' || source_key || '|' || url || '|' || retrieved_on || '|'"
                          + " || coalesce(sha256, 'NULL') FROM payroll.statutory_rule_pack_source"
                          + " ORDER BY seq",
                      String.class);
          assertThat(sources)
              .containsExactly(
                  "1|DGI_IRPP|https://dgi.gouv.cd/impot-sur-le-revenu-des-personnes-physiques-irpp/"
                      + "|2026-10-29|NULL",
                  "2|DGI_NOTICE_2026|https://dgi.gouv.cd/wp-content/uploads/2026/02/"
                      + "Communique%CC%81-du-15-fe%CC%81vrier-2026.pdf|2026-10-29|NULL",
                  "3|DGI_PORTAL|https://desec.dgi.gouv.cd/|2026-10-29|NULL",
                  "4|CNSS_RATES|https://cnss.cd/?page_id=1388|2026-10-29|NULL",
                  "5|CNSS_DECLARATION|https://cnss.cd/?page_id=1698|2026-10-29|NULL",
                  "6|BUDGET_2026|https://budget.gouv.cd/budget-2026/|2026-10-29|NULL");
          for (String table :
              List.of(
                  "statutory_activation",
                  "statutory_profile",
                  "statutory_calculation",
                  "statutory_calculation_line",
                  "statutory_calculation_component")) {
            assertThat(count(db, "payroll." + table)).as(table).isEqualTo(0);
          }
        });
  }

  private static int count(Db db, String table) {
    return java.util.Objects.requireNonNull(
        db.jdbc().queryForObject("SELECT count(*) FROM " + table, Integer.class));
  }

  @Test
  void theSeededPackCannotBeVerifiedBecauseItsSourcesCarryNoHash() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          UUID seed =
              db.jdbc()
                  .queryForObject(
                      "SELECT id FROM payroll.statutory_rule_pack WHERE code = 'CD-SALARY-PREVIEW'",
                      UUID.class);
          refuses(db, "payroll_statutory_rule_pack_sources", c -> exec(c, VERIFY, seed));
        });
  }

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV44ExactlyAndDropsOnlyTheSeedOfAnEmptyDataSet() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "44");
          String v44 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "45");
          String v45 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v45).isNotEqualTo(v44).contains("statutory_calculation_component");
          int recorded =
              java.util.Objects.requireNonNull(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
          script(db, resource("db/rollback/V45__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v44);
          assertThat(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
              .isEqualTo(recorded);
          // The seed went with the tables; the migration can be applied again, seed included.
          script(db, resource("db/migration/V45__payroll_statutory_preview.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v45);
          assertThat(count(db, "payroll.statutory_rule_pack")).isEqualTo(1);
        });
  }

  @Test
  void theRollbackRefusesWhileAProfileActivationOrCalculationExists() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          String v45 = db.jdbc().queryForObject(SIGNATURE, String.class);
          UUID tenant = organization(db.jdbc());
          UUID employee = employee(db.jdbc(), tenant);
          commit(
              db,
              c ->
                  exec(
                      c,
                      PROFILE,
                      tenant,
                      employee,
                      UUID.randomUUID(),
                      1,
                      LocalDate.of(2026, 1, 1),
                      "CD",
                      "STANDARD_RESIDENT",
                      true,
                      true,
                      "EMPLOYMENT_CONTRACT"));
          assertThatThrownBy(() -> script(db, resource("db/rollback/V45__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V45 rollback refused: statutory activation, profile");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v45);
          assertThat(count(db, "payroll.statutory_profile")).isEqualTo(1);
          assertThat(count(db, "payroll.statutory_rule_pack")).isEqualTo(1);
        });
  }

  @Test
  void theRollbackNeverErasesVerificationEvidenceOrAnotherPack() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          String v45 = db.jdbc().queryForObject(SIGNATURE, String.class);
          String rules = seedRules(db);
          // A DRAFT pack other than the seed is evidence of work done: refused.
          commit(
              db,
              c ->
                  syntheticPack(
                      c,
                      rules,
                      UUID.randomUUID(),
                      "SYNTHETIC-TEST-X",
                      "2026-01-01",
                      "2026-12-31",
                      "DRAFT"));
          assertThatThrownBy(() -> script(db, resource("db/rollback/V45__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V45 rollback refused: rule pack evidence");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v45);
          // A verified pack, with its reviewer and hashed sources, is refused as well.
          commit(
              db,
              c ->
                  syntheticPack(
                      c,
                      rules,
                      UUID.randomUUID(),
                      "SYNTHETIC-TEST-Y",
                      "2026-01-01",
                      "2026-12-31",
                      "VERIFIED"));
          assertThatThrownBy(() -> script(db, resource("db/rollback/V45__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V45 rollback refused: rule pack evidence");
          assertThat(count(db, "payroll.statutory_rule_pack")).isEqualTo(3);
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT reviewer_name FROM payroll.statutory_rule_pack"
                              + " WHERE code = 'SYNTHETIC-TEST-Y'",
                          String.class))
              .isEqualTo("Synthetic Test Reviewer");
        });
  }

  // ------------------------------------------------------------------------------------------
  // Rule pack
  // ------------------------------------------------------------------------------------------

  private static final String NAME = "payroll_statutory_rule_pack_";
  private static final String MINIMAL = "{\"schemaVersion\": 1, \"components\": [{}]}";
  private static final String VERIFY_WITH =
      "UPDATE payroll.statutory_rule_pack SET status = 'VERIFIED', reviewer_name = ?,"
          + " reviewer_organization = ?, reviewed_on = CAST(? AS date), attested = ?,"
          + " verified_at = now() WHERE id = ?";

  private static void insertPack(
      Connection c,
      String code,
      String country,
      String currency,
      String status,
      String from,
      String to,
      String method,
      String rules)
      throws SQLException {
    exec(c, PACK, UUID.randomUUID(), code, 1, country, currency, status, from, to, method, rules);
  }

  private static void draft(Connection c, String code, String rules) throws SQLException {
    insertPack(c, code, "CD", "CDF", "DRAFT", "2026-01-01", null, "1", rules);
  }

  @Test
  void aPackIsCreatedDraftWithClosedValuesAnIntervalAndMinimalRules() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          String ok = MINIMAL;
          String code = "SYNTHETIC-TEST-1";
          commit(db, c -> draft(c, "SYNTHETIC-TEST-OK", ok));
          refuses(
              db,
              NAME + "forward",
              c -> insertPack(c, code, "CD", "CDF", "VERIFIED", "2026-01-01", null, "1", ok));
          refuses(
              db,
              NAME + "forward",
              c -> insertPack(c, code, "CD", "CDF", "RETIRED", "2026-01-01", null, "1", ok));
          // The creation guard (a BEFORE trigger) answers before the CHECK constraint does.
          refusesOneOf(
              db,
              List.of(NAME + "status", NAME + "forward"),
              c -> insertPack(c, code, "CD", "CDF", "ACTIVE", "2026-01-01", null, "1", ok));
          refuses(db, NAME + "code_version_unique", c -> draft(c, "SYNTHETIC-TEST-OK", ok));
          refuses(db, NAME + "code", c -> draft(c, "lower-case", ok));
          refuses(
              db,
              NAME + "country",
              c -> insertPack(c, code, "FR", "CDF", "DRAFT", "2026-01-01", null, "1", ok));
          refuses(
              db,
              NAME + "currency",
              c -> insertPack(c, code, "CD", "USD", "DRAFT", "2026-01-01", null, "1", ok));
          refuses(
              db,
              NAME + "interval",
              c -> insertPack(c, code, "CD", "CDF", "DRAFT", "2026-01-01", "2025-12-31", "1", ok));
          refuses(
              db,
              NAME + "interval",
              c -> insertPack(c, code, "CD", "CDF", "DRAFT", "1999-12-31", null, "1", ok));
          refuses(
              db,
              NAME + "method",
              c -> insertPack(c, code, "CD", "CDF", "DRAFT", "2026-01-01", null, "2", ok));
          for (String rules :
              List.of(
                  "{\"schemaVersion\": 2, \"components\": [{}]}",
                  "{\"components\": [{}]}",
                  "{\"schemaVersion\": 1}",
                  "{\"schemaVersion\": 1, \"components\": []}",
                  "[1]",
                  "{\"schemaVersion\": 1, \"components\": ["
                      + "{},{},{},{},{},{},{},{},{},{},{},{},{}]}")) {
            refuses(db, NAME + "rules", c -> draft(c, code, rules));
          }
          // A DRAFT pack carries no evidence at all.
          refuses(
              db,
              NAME + "evidence",
              c ->
                  exec(
                      c,
                      PACK.replace("created_at)", "created_at, reviewer_name)")
                          .replace("now())", "now(), 'Someone')"),
                      UUID.randomUUID(),
                      code,
                      1,
                      "CD",
                      "CDF",
                      "DRAFT",
                      "2026-01-01",
                      null,
                      "1",
                      ok));
          // The digest is generated: it cannot be written.
          refusesWithState(
              db,
              "428C9",
              c ->
                  exec(
                      c,
                      "UPDATE payroll.statutory_rule_pack SET rules_digest = repeat('a', 64)"
                          + " WHERE code = 'SYNTHETIC-TEST-OK'"));
        });
  }

  @Test
  void aPackIsVerifiedOnlyWithItsEvidenceItsHashedSourcesAndCitedSources() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          String rules = seedRules(db);
          UUID id = UUID.randomUUID();
          commit(
              db,
              c -> {
                exec(
                    c,
                    PACK,
                    id,
                    "SYNTHETIC-TEST-E",
                    1,
                    "CD",
                    "CDF",
                    "DRAFT",
                    "2026-01-01",
                    "2026-12-31",
                    "1",
                    rules);
                exec(c, SOURCE, id, 1, "CNSS_RATES", "CNSS rates", CNSS_URL, HASH_A);
                exec(c, SOURCE, id, 2, "DGI_IRPP", "DGI IRPP", DGI_URL, HASH_B);
              });
          String name = "Synthetic Test Reviewer";
          String organization = "Test Only";
          // Each element of the evidence is required, and bounded.
          refuses(
              db,
              NAME + "evidence",
              c ->
                  exec(
                      c,
                      "UPDATE payroll.statutory_rule_pack SET status = 'VERIFIED'"
                          + " WHERE id = ?",
                      id));
          refuses(
              db,
              NAME + "evidence",
              c -> exec(c, VERIFY_WITH, null, organization, "2026-10-01", true, id));
          refuses(
              db,
              NAME + "evidence",
              c -> exec(c, VERIFY_WITH, "", organization, "2026-10-01", true, id));
          refuses(
              db,
              NAME + "evidence",
              c -> exec(c, VERIFY_WITH, "R".repeat(201), organization, "2026-10-01", true, id));
          refuses(
              db, NAME + "evidence", c -> exec(c, VERIFY_WITH, name, null, "2026-10-01", true, id));
          refuses(
              db, NAME + "evidence", c -> exec(c, VERIFY_WITH, name, "", "2026-10-01", true, id));
          refuses(
              db,
              NAME + "evidence",
              c -> exec(c, VERIFY_WITH, name, "O".repeat(201), "2026-10-01", true, id));
          refuses(
              db, NAME + "evidence", c -> exec(c, VERIFY_WITH, name, organization, null, true, id));
          refuses(
              db,
              NAME + "evidence",
              c -> exec(c, VERIFY_WITH, name, organization, "2026-10-01", false, id));
          refuses(
              db,
              NAME + "evidence",
              c ->
                  exec(
                      c,
                      "UPDATE payroll.statutory_rule_pack SET status = 'VERIFIED',"
                          + " reviewer_name = 'R', reviewer_organization = 'O',"
                          + " reviewed_on = DATE '2026-10-01', attested = true WHERE id = ?",
                      id));
          // RETIRED straight from DRAFT is not a legal move.
          refuses(
              db,
              NAME + "forward",
              c ->
                  exec(
                      c,
                      "UPDATE payroll.statutory_rule_pack SET status = 'RETIRED',"
                          + " reviewer_name = 'R', reviewer_organization = 'O',"
                          + " reviewed_on = DATE '2026-10-01', attested = true,"
                          + " verified_at = now(), retired_at = now() WHERE id = ?",
                      id));
          // 200 characters are allowed.
          commit(
              db,
              c -> exec(c, VERIFY_WITH, "R".repeat(200), "O".repeat(200), "2026-10-01", true, id));
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT status FROM payroll.statutory_rule_pack WHERE id = ?",
                          String.class,
                          id))
              .isEqualTo("VERIFIED");
          // Once verified: the evidence, the content and the sources are frozen, forward only.
          refuses(
              db,
              NAME + "evidence_immutable",
              c ->
                  exec(
                      c,
                      "UPDATE payroll.statutory_rule_pack SET reviewer_name = 'Other'"
                          + " WHERE id = ?",
                      id));
          refusesOneOf(
              db,
              List.of(NAME + "forward", NAME + "evidence_immutable"),
              c ->
                  exec(
                      c,
                      "UPDATE payroll.statutory_rule_pack SET status = 'DRAFT',"
                          + " reviewer_name = NULL, reviewer_organization = NULL, reviewed_on = NULL,"
                          + " attested = false, verified_at = NULL WHERE id = ?",
                      id));
          refuses(
              db,
              NAME + "immutable",
              c ->
                  exec(
                      c,
                      "UPDATE payroll.statutory_rule_pack SET effective_to = DATE"
                          + " '2027-01-01' WHERE id = ?",
                      id));
          refuses(
              db,
              NAME + "immutable",
              c ->
                  exec(
                      c,
                      "UPDATE payroll.statutory_rule_pack SET code = 'SYNTHETIC-TEST-Z'"
                          + " WHERE id = ?",
                      id));
          refuses(
              db,
              NAME + "immutable",
              c ->
                  exec(
                      c,
                      "UPDATE payroll.statutory_rule_pack SET rules ="
                          + " '{\"schemaVersion\": 1, \"components\": [{\"a\": 1}]}' WHERE id = ?",
                      id));
          refuses(
              db,
              NAME + "source_state",
              c ->
                  exec(
                      c,
                      SOURCE,
                      id,
                      3,
                      "BUDGET_2026",
                      "Budget",
                      "https://budget.gouv.cd/b",
                      HASH_A));
          // Retirement needs its time, then is final.
          refuses(
              db,
              NAME + "evidence",
              c ->
                  exec(
                      c,
                      "UPDATE payroll.statutory_rule_pack SET status = 'RETIRED'" + " WHERE id = ?",
                      id));
          commit(db, c -> exec(c, RETIRE, id));
          refuses(
              db,
              NAME + "forward",
              c ->
                  exec(
                      c,
                      "UPDATE payroll.statutory_rule_pack SET status = 'VERIFIED',"
                          + " retired_at = NULL WHERE id = ?",
                      id));
        });
  }

  @Test
  void aVerifiedPackNeedsSourcesEveryOneHashedAndEveryCitedSourcePresent() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          String rules = seedRules(db);
          UUID none = UUID.randomUUID();
          UUID unhashed = UUID.randomUUID();
          UUID missingKey = UUID.randomUUID();
          UUID complete = UUID.randomUUID();
          commit(
              db,
              c -> {
                exec(
                    c,
                    PACK,
                    none,
                    "SYNTHETIC-TEST-N",
                    1,
                    "CD",
                    "CDF",
                    "DRAFT",
                    "2026-01-01",
                    "2026-12-31",
                    "1",
                    rules);
                exec(
                    c,
                    PACK,
                    unhashed,
                    "SYNTHETIC-TEST-U",
                    1,
                    "CD",
                    "CDF",
                    "DRAFT",
                    "2026-01-01",
                    "2026-12-31",
                    "1",
                    rules);
                exec(c, SOURCE, unhashed, 1, "CNSS_RATES", "CNSS rates", CNSS_URL, HASH_A);
                exec(c, SOURCE, unhashed, 2, "DGI_IRPP", "DGI IRPP", DGI_URL, null);
                exec(
                    c,
                    PACK,
                    missingKey,
                    "SYNTHETIC-TEST-K",
                    1,
                    "CD",
                    "CDF",
                    "DRAFT",
                    "2026-01-01",
                    "2026-12-31",
                    "1",
                    rules);
                exec(c, SOURCE, missingKey, 1, "CNSS_RATES", "CNSS rates", CNSS_URL, HASH_A);
                exec(
                    c,
                    PACK,
                    complete,
                    "SYNTHETIC-TEST-C",
                    1,
                    "CD",
                    "CDF",
                    "DRAFT",
                    "2026-01-01",
                    "2026-12-31",
                    "1",
                    rules);
              });
          refuses(db, NAME + "sources", c -> exec(c, VERIFY, none));
          refuses(db, NAME + "sources", c -> exec(c, VERIFY, unhashed));
          refuses(db, NAME + "source_keys", c -> exec(c, VERIFY, missingKey));
          // The checks are deferred: sources added in the same transaction count.
          commit(
              db,
              c -> {
                exec(c, SOURCE, complete, 1, "CNSS_RATES", "CNSS rates", CNSS_URL, HASH_A);
                exec(c, SOURCE, complete, 2, "DGI_IRPP", "DGI IRPP", DGI_URL, HASH_B);
                exec(c, VERIFY, complete);
              });
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT status FROM payroll.statutory_rule_pack WHERE id = ?",
                          String.class,
                          complete))
              .isEqualTo("VERIFIED");
        });
  }

  @Test
  void sourcesAreAllowListedOfficialHttpsAddressesAndNothingIsEverDeleted() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          String rules = seedRules(db);
          String source = NAME + "source_";
          UUID id = UUID.randomUUID();
          for (String url :
              List.of(
                  "http://dgi.gouv.cd/x",
                  "https://evil.com/x",
                  "https://dgi.gouv.cd.evil.com/x",
                  "https://dgi.gouv.cd@evil.com/x",
                  "https://dgi.gouv.cd:8443/x",
                  "https://sub.dgi.gouv.cd/x",
                  "https://dgi.gouv.cd/a b")) {
            refuses(
                db,
                source + "url",
                c -> {
                  exec(
                      c,
                      PACK,
                      id,
                      "SYNTHETIC-TEST-S",
                      1,
                      "CD",
                      "CDF",
                      "DRAFT",
                      "2026-01-01",
                      null,
                      "1",
                      rules);
                  exec(c, SOURCE, id, 1, "DGI_IRPP", "Title", url, null);
                });
          }
          refuses(
              db,
              source + "key",
              c -> sourceOnNewPack(c, id, rules, 1, "OTHER", "Title", DGI_URL, null));
          refuses(
              db,
              source + "sha256",
              c -> sourceOnNewPack(c, id, rules, 1, "DGI_IRPP", "Title", DGI_URL, "ABC"));
          refuses(
              db,
              source + "title",
              c -> sourceOnNewPack(c, id, rules, 1, "DGI_IRPP", "", DGI_URL, null));
          refuses(
              db,
              source + "pack",
              c -> exec(c, SOURCE, UUID.randomUUID(), 1, "DGI_IRPP", "Title", DGI_URL, null));
          refuses(
              db,
              source + "key_unique",
              c -> {
                sourceOnNewPack(c, id, rules, 1, "DGI_IRPP", "Title", DGI_URL, null);
                exec(c, SOURCE, id, 2, "DGI_IRPP", "Title", "https://cnss.cd/", null);
              });
          // The official portals are accepted.
          commit(
              db,
              c -> {
                String portal = "https://desec.dgi.gouv.cd/";
                sourceOnNewPack(c, id, rules, 1, "DGI_PORTAL", "Portal", portal, null);
                exec(c, SOURCE, id, 2, "CNSS_RATES", "Rates", "https://cnss.cd", null);
                exec(c, SOURCE, id, 3, "BUDGET_2026", "Budget", "https://budget.gouv.cd/b", null);
              });
          // Nothing is updated, deleted or truncated, the seed included.
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "UPDATE payroll.statutory_rule_pack_source SET title = 'x'"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "DELETE FROM payroll.statutory_rule_pack_source"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "TRUNCATE payroll.statutory_rule_pack_source CASCADE"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "DELETE FROM payroll.statutory_rule_pack"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "TRUNCATE payroll.statutory_rule_pack CASCADE"));
        });
  }

  private static void sourceOnNewPack(
      Connection c,
      UUID id,
      String rules,
      int seq,
      String key,
      String title,
      String url,
      String hash)
      throws SQLException {
    exec(c, PACK, id, "SYNTHETIC-TEST-S", 1, "CD", "CDF", "DRAFT", "2026-01-01", null, "1", rules);
    exec(c, SOURCE, id, seq, key, title, url, hash);
  }

  // ------------------------------------------------------------------------------------------
  // Activation
  // ------------------------------------------------------------------------------------------

  private static void activate(
      Connection c, World w, int sequence, UUID pack, LocalDate month, boolean acknowledged)
      throws SQLException {
    exec(
        c,
        ACTIVATION,
        w.tenant(),
        w.entity(),
        UUID.randomUUID(),
        sequence,
        pack,
        month,
        acknowledged);
  }

  @Test
  void onlyAVerifiedPackCoveringTheMonthIsActivatedOnceAndForever() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          World w = world(db);
          String name = "payroll_statutory_activation_";
          LocalDate january = LocalDate.of(2026, 1, 1);
          LocalDate april = LocalDate.of(2026, 4, 1);
          refuses(db, name + "pack_verified", c -> activate(c, w, 3, w.draft(), april, true));
          refuses(db, name + "pack_verified", c -> activate(c, w, 3, w.retired(), april, true));
          refuses(
              db,
              name + "interval",
              c -> activate(c, w, 3, w.verified(), LocalDate.of(2027, 1, 1), true));
          refuses(
              db,
              name + "interval",
              c -> activate(c, w, 3, w.verified(), LocalDate.of(2025, 12, 1), true));
          refuses(
              db,
              name + "interval",
              c -> activate(c, w, 3, w.firstHalf(), LocalDate.of(2026, 7, 1), true));
          refuses(
              db,
              name + "month",
              c -> activate(c, w, 3, w.verified(), LocalDate.of(2026, 4, 15), true));
          refuses(db, name + "acknowledged", c -> activate(c, w, 3, w.verified(), april, false));
          refuses(db, name + "sequence", c -> activate(c, w, 5, w.verified(), april, true));
          refuses(db, name + "sequence", c -> activate(c, w, 1, w.verified(), april, true));
          refuses(db, name + "month_unique", c -> activate(c, w, 3, w.verified(), january, true));
          refuses(
              db,
              name + "legal_entity",
              c ->
                  exec(
                      c,
                      ACTIVATION,
                      w.tenant(),
                      w.otherEntity(),
                      UUID.randomUUID(),
                      1,
                      w.verified(),
                      april,
                      true));
          refuses(db, name + "pack", c -> activate(c, w, 3, UUID.randomUUID(), april, true));
          commit(db, c -> activate(c, w, 3, w.verified(), april, true));
          assertThat(count(db, "payroll.statutory_activation")).isEqualTo(3);
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "UPDATE payroll.statutory_activation SET acknowledged = true"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "DELETE FROM payroll.statutory_activation"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "TRUNCATE payroll.statutory_activation CASCADE"));
        });
  }

  // ------------------------------------------------------------------------------------------
  // Profile
  // ------------------------------------------------------------------------------------------

  private static void profile(
      Connection c,
      UUID tenant,
      UUID employee,
      int sequence,
      LocalDate from,
      String country,
      String classification,
      boolean cnss,
      boolean irpp,
      String evidence)
      throws SQLException {
    exec(
        c,
        PROFILE,
        tenant,
        employee,
        UUID.randomUUID(),
        sequence,
        from,
        country,
        classification,
        cnss,
        irpp,
        evidence);
  }

  private void refusesProfile(
      Db db,
      String constraint,
      UUID tenant,
      UUID employee,
      int sequence,
      LocalDate from,
      String country,
      String classification,
      boolean cnss,
      boolean irpp,
      String evidence) {
    refuses(
        db,
        constraint,
        c ->
            profile(
                c,
                tenant,
                employee,
                sequence,
                from,
                country,
                classification,
                cnss,
                irpp,
                evidence));
  }

  @Test
  void aProfileIsAppendOnlyClosedAndBelongsToAnEmployeeOfTheTenant() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          World w = world(db);
          String name = "payroll_statutory_profile_";
          LocalDate day = LocalDate.of(2026, 1, 1);
          String standard = "STANDARD_RESIDENT";
          String contract = "EMPLOYMENT_CONTRACT";
          UUID t = w.tenant();
          UUID e = w.employeeOne();
          refusesProfile(db, name + "sequence", t, e, 2, day, "CD", standard, true, true, contract);
          refusesProfile(
              db, name + "sequence", t, e, 101, day, "CD", standard, true, true, contract);
          refusesProfile(
              db, name + "unsupported", t, e, 1, day, "CD", "UNSUPPORTED", true, false, contract);
          refusesProfile(
              db, name + "unsupported", t, e, 1, day, "CD", "UNSUPPORTED", false, true, contract);
          refusesProfile(db, name + "country", t, e, 1, day, "FR", standard, true, true, contract);
          refusesProfile(
              db, name + "classification", t, e, 1, day, "CD", "EXPAT", true, true, contract);
          refusesProfile(db, name + "evidence", t, e, 1, day, "CD", standard, true, true, "NOTE");
          refusesProfile(
              db,
              name + "effective_range",
              t,
              e,
              1,
              LocalDate.of(1999, 12, 31),
              "CD",
              standard,
              true,
              true,
              contract);
          refusesProfile(
              db,
              name + "employee",
              w.otherTenant(),
              e,
              1,
              day,
              "CD",
              standard,
              true,
              true,
              contract);
          commit(
              db,
              c -> {
                profile(c, t, e, 1, day, "CD", standard, true, true, contract);
                profile(
                    c,
                    t,
                    w.employeeTwo(),
                    1,
                    day,
                    "CD",
                    standard,
                    false,
                    false,
                    "PAYROLL_FILE_REVIEW");
              });
          commit(
              db,
              c ->
                  profile(
                      c, t, e, 2, day, "CD", "UNSUPPORTED", false, false, "PROFESSIONAL_REVIEW"));
          assertThat(count(db, "payroll.statutory_profile")).isEqualTo(3);
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "UPDATE payroll.statutory_profile SET cnss_subject = false"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "DELETE FROM payroll.statutory_profile"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "TRUNCATE payroll.statutory_profile CASCADE"));
        });
  }

  // ------------------------------------------------------------------------------------------
  // Calculation
  // ------------------------------------------------------------------------------------------

  private static final String CALC = "payroll_statutory_calculation_";

  private void refusesCalculation(
      Db db, World w, String constraint, UUID pack, UUID activation, int version, String fault) {
    refuses(
        db,
        constraint,
        c -> calculation(c, w, UUID.randomUUID(), pack, activation, version, fault));
  }

  @Test
  void aCalculationNeedsAVerifiedPackAnActivationAndExactArithmetic() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          World w = world(db);
          UUID v = w.verified();
          UUID a = w.activation();
          refusesOneOf(
              db,
              List.of(CALC + "pack_state", CALC + "activation_rule"),
              c -> calculation(c, w, UUID.randomUUID(), w.draft(), a, 1, ""));
          refusesOneOf(
              db,
              List.of(CALC + "pack_state", CALC + "activation_rule"),
              c -> calculation(c, w, UUID.randomUUID(), w.retired(), a, 1, ""));
          UUID h = w.halfActivation();
          refusesCalculation(db, w, CALC + "pack_interval", w.firstHalf(), h, 1, "");
          refusesCalculation(db, w, CALC + "activation_rule", v, h, 1, "");
          refusesCalculation(db, w, CALC + "version", v, a, 2, "");
          refusesCalculation(db, w, CALC + "run_state", v, a, 1, "usd");
          refusesCalculation(db, w, CALC + "counts", v, a, 1, "blocked");
          refusesCalculation(db, w, CALC + "arithmetic", v, a, 1, "arithmetic");
          // Deferred: judged when every row of the calculation is in place.
          refusesCalculation(db, w, CALC + "exact", v, a, 1, "count");
          refusesCalculation(db, w, CALC + "exact", v, a, 1, "grossTotal");
          refusesCalculation(db, w, CALC + "line_sums", v, a, 1, "deductions");
          refusesCalculation(db, w, CALC + "line_gross", v, a, 1, "lineGross");
          refusesCalculation(db, w, CALC + "component_rule", v, a, 1, "excludedComponent");
          refusesCalculation(db, w, CALC + "component_rule", v, a, 1, "wrongRule");
          refusesCalculation(db, w, CALC + "line_components", v, a, 1, "missingComponent");
          // Another tenant cannot reference the run, and digests are 64 hexadecimal characters.
          String raw =
              "INSERT INTO payroll.statutory_calculation (tenant_id, legal_entity_id, id, run_id,"
                  + " run_version, period_start, currency, pack_id, activation_id, version,"
                  + " status, supported_count, excluded_count, blocked_count, gross_total_minor,"
                  + " employee_deductions_total_minor, employer_contributions_total_minor,"
                  + " preview_net_total_minor, employer_cost_total_minor, source_run_digest,"
                  + " profile_digest, rule_pack_digest, calculation_digest, created_at,"
                  + " created_by) VALUES (?, ?, ?, ?, 1, DATE '2026-09-01', 'CDF', ?, ?, 1,"
                  + " 'DRAFT', 1, 0, 0, 1, 0, 0, 1, 1, ?, repeat('a', 64), repeat('a', 64),"
                  + " repeat('a', 64), now(), 'subject')";
          refuses(
              db,
              CALC + "run",
              c ->
                  exec(
                      c,
                      raw,
                      w.otherTenant(),
                      w.otherEntity(),
                      UUID.randomUUID(),
                      w.run(),
                      v,
                      a,
                      HASH_A));
          refuses(
              db,
              CALC + "digests",
              c -> exec(c, raw, w.tenant(), w.entity(), UUID.randomUUID(), w.run(), v, a, "zz"));
          // The complete, exact calculation is accepted.
          UUID id = committedCalculation(db, w, 1);
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT status || ':' || version FROM payroll.statutory_calculation"
                              + " WHERE id = ?",
                          String.class,
                          id))
              .isEqualTo("DRAFT:1");
        });
  }

  @Test
  void aCalculationIsSupersededInOrderNeverEditedAndNeverDeleted() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          World w = world(db);
          UUID first = committedCalculation(db, w, 1);
          UUID second = UUID.randomUUID();
          String table = "payroll.statutory_calculation";
          // One DRAFT per run and pack.
          refusesCalculation(db, w, CALC + "one_draft", w.verified(), w.activation(), 2, "");
          refuses(
              db,
              CALC + "supersession_only",
              c -> exec(c, "UPDATE " + table + " SET gross_total_minor = 1"));
          // The successor must exist when the transaction ends.
          refuses(
              db,
              CALC + "successor",
              c ->
                  exec(
                      c,
                      "UPDATE "
                          + table
                          + " SET status = 'SUPERSEDED', superseded_at = now(),"
                          + " superseded_by_id = ? WHERE id = ?",
                      UUID.randomUUID(),
                      first));
          refuses(db, "payroll_statutory_immutable", c -> exec(c, "DELETE FROM " + table));
          refuses(
              db, "payroll_statutory_immutable", c -> exec(c, "TRUNCATE " + table + " CASCADE"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "UPDATE payroll.statutory_calculation_line SET excluded = false"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "DELETE FROM payroll.statutory_calculation_component"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "UPDATE payroll.statutory_calculation_component SET trace = NULL"));
          refuses(
              db,
              "payroll_statutory_immutable",
              c -> exec(c, "TRUNCATE payroll.statutory_calculation_line CASCADE"));
          // Legal: supersede the first and insert the second in the same transaction.
          commit(
              db,
              c -> {
                exec(
                    c,
                    "UPDATE "
                        + table
                        + " SET status = 'SUPERSEDED', superseded_at = now(),"
                        + " superseded_by_id = ? WHERE id = ?",
                    second,
                    first);
                calculation(c, w, second, w.verified(), w.activation(), 2, "");
              });
          assertThat(
                  db.jdbc()
                      .queryForList(
                          "SELECT version || ':' || status FROM " + table + " ORDER BY version",
                          String.class))
              .containsExactly("1:SUPERSEDED", "2:DRAFT");
          // A superseded calculation is final, takes no line, and the chain cannot loop back.
          refuses(
              db,
              CALC + "supersession_only",
              c ->
                  exec(
                      c,
                      "UPDATE "
                          + table
                          + " SET status = 'SUPERSEDED', superseded_at = now(),"
                          + " superseded_by_id = ? WHERE id = ?",
                      second,
                      first));
          refuses(
              db,
              CALC + "line_state",
              c ->
                  exec(
                      c,
                      CALCULATION_LINE,
                      w.tenant(),
                      first,
                      UUID.randomUUID(),
                      w.run(),
                      w.lineOne(),
                      w.employeeOne(),
                      "E-1",
                      162_000L,
                      0L,
                      0L,
                      162_000L,
                      162_000L,
                      false));
          refuses(
              db,
              CALC + "chain",
              c ->
                  exec(
                      c,
                      "UPDATE "
                          + table
                          + " SET status = 'SUPERSEDED', superseded_at = now(),"
                          + " superseded_by_id = ? WHERE id = ?",
                      first,
                      second));
        });
  }

  @Test
  void lineAndComponentRowsRefuseInconsistentAmountsAndOpenValues() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "45");
          World w = world(db);
          UUID calculation = committedCalculation(db, w, 1);
          UUID supported =
              db.jdbc()
                  .queryForObject(
                      "SELECT id FROM payroll.statutory_calculation_line"
                          + " WHERE calculation_id = ? AND NOT excluded",
                      UUID.class,
                      calculation);
          String line = CALC + "line_";
          String component = CALC + "component_";
          UUID t = w.tenant();
          UUID run = w.run();
          // Lines: amounts, arithmetic, the excluded shape, the source employee, one per source.
          refuses(
              db,
              line + "amounts",
              c ->
                  exec(
                      c,
                      CALCULATION_LINE,
                      t,
                      calculation,
                      UUID.randomUUID(),
                      run,
                      w.lineOne(),
                      w.employeeOne(),
                      "E-1",
                      162_000L,
                      162_001L,
                      0L,
                      -1L,
                      162_000L,
                      false));
          refuses(
              db,
              line + "arithmetic",
              c ->
                  exec(
                      c,
                      CALCULATION_LINE,
                      t,
                      calculation,
                      UUID.randomUUID(),
                      run,
                      w.lineOne(),
                      w.employeeOne(),
                      "E-1",
                      162_000L,
                      100L,
                      0L,
                      162_000L,
                      162_000L,
                      false));
          refuses(
              db,
              line + "arithmetic",
              c ->
                  exec(
                      c,
                      CALCULATION_LINE,
                      t,
                      calculation,
                      UUID.randomUUID(),
                      run,
                      w.lineOne(),
                      w.employeeOne(),
                      "E-1",
                      162_000L,
                      0L,
                      100L,
                      162_000L,
                      162_000L,
                      false));
          refuses(
              db,
              line + "excluded",
              c ->
                  exec(
                      c,
                      CALCULATION_LINE,
                      t,
                      calculation,
                      UUID.randomUUID(),
                      run,
                      w.lineOne(),
                      w.employeeOne(),
                      "E-1",
                      162_000L,
                      100L,
                      0L,
                      161_900L,
                      162_000L,
                      true));
          refuses(
              db,
              line + "gross",
              c ->
                  exec(
                      c,
                      CALCULATION_LINE,
                      t,
                      calculation,
                      UUID.randomUUID(),
                      run,
                      w.lineOne(),
                      w.employeeTwo(),
                      "E-1",
                      162_000L,
                      0L,
                      0L,
                      162_000L,
                      162_000L,
                      false));
          refuses(
              db,
              line + "source_unique",
              c ->
                  exec(
                      c,
                      CALCULATION_LINE,
                      t,
                      calculation,
                      UUID.randomUUID(),
                      run,
                      w.lineOne(),
                      w.employeeOne(),
                      "E-1",
                      162_000L,
                      0L,
                      0L,
                      162_000L,
                      162_000L,
                      false));
          // Components: closed values, amounts, one trace shape.
          UUID anyLine = UUID.randomUUID();
          String pension = "CNSS_EMPLOYEE_PENSION";
          String social = "SOCIAL_CONTRIBUTION";
          refuses(
              db,
              component + "amounts",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      anyLine,
                      1,
                      pension,
                      "EMPLOYEE",
                      social,
                      100L,
                      101L,
                      "RATE_APPLIED",
                      null));
          refuses(
              db,
              component + "amounts",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      anyLine,
                      1,
                      pension,
                      "EMPLOYEE",
                      social,
                      -1L,
                      0L,
                      "RATE_APPLIED",
                      null));
          refuses(
              db,
              component + "not_subject",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      anyLine,
                      1,
                      pension,
                      "EMPLOYEE",
                      social,
                      100L,
                      5L,
                      "NOT_SUBJECT",
                      null));
          refuses(
              db,
              component + "explanation",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      anyLine,
                      1,
                      pension,
                      "EMPLOYEE",
                      social,
                      100L,
                      5L,
                      "BOGUS",
                      null));
          refuses(
              db,
              component + "explanation",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      anyLine,
                      1,
                      pension,
                      "EMPLOYEE",
                      social,
                      100L,
                      5L,
                      "",
                      null));
          refuses(
              db,
              component + "trace",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      anyLine,
                      1,
                      pension,
                      "EMPLOYEE",
                      social,
                      100L,
                      5L,
                      "RATE_APPLIED",
                      "[1]"));
          refuses(
              db,
              component + "party",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      anyLine,
                      1,
                      pension,
                      "OTHER",
                      social,
                      100L,
                      5L,
                      "RATE_APPLIED",
                      null));
          refuses(
              db,
              component + "category",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      anyLine,
                      1,
                      pension,
                      "EMPLOYEE",
                      "OTHER",
                      100L,
                      5L,
                      "RATE_APPLIED",
                      null));
          refuses(
              db,
              component + "id_format",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      anyLine,
                      1,
                      "bad id",
                      "EMPLOYEE",
                      social,
                      100L,
                      5L,
                      "RATE_APPLIED",
                      null));
          refuses(
              db,
              component + "seq",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      anyLine,
                      13,
                      pension,
                      "EMPLOYEE",
                      social,
                      100L,
                      5L,
                      "RATE_APPLIED",
                      null));
          refuses(
              db,
              component + "line",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      anyLine,
                      1,
                      pension,
                      "EMPLOYEE",
                      social,
                      100L,
                      5L,
                      "RATE_APPLIED",
                      null));
          refuses(
              db,
              component + "pkey",
              c ->
                  exec(
                      c,
                      COMPONENT,
                      t,
                      calculation,
                      supported,
                      1,
                      pension,
                      "EMPLOYEE",
                      social,
                      162_000L,
                      8_100L,
                      "RATE_APPLIED",
                      null));
        });
  }
}
