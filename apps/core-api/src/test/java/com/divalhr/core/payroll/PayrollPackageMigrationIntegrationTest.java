package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-094C V46 directly against PostgreSQL (D94C-1 to D94C-16, ADR 0035 sections 2 to 5): the
 * objects the migration creates, the digests the database generates and recomputes, every rule the
 * database enforces whatever the application checks (the package guard, the lifecycle APPROVED to
 * VOIDED once, the void evidence, the source binding, the payslip snapshot guard, the immutability
 * and the absence of any delete or truncate, the voided pair) and the refusing rollback that
 * restores V45 exactly. A package is first approved through the public API; each refusal is then
 * proved by the constraint it names, in a transaction that is always rolled back. Synthetic data
 * only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
class PayrollPackageMigrationIntegrationTest {

  /** The signature of the payroll schema (a method: a long constant trips SpotBugs HSC). */
  private static String signature() {
    return """
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
  }

  private static final List<String> PACKAGE_COLUMNS =
      List.of(
          "tenant_id",
          "legal_entity_id",
          "id",
          "period_start",
          "currency",
          "version",
          "status",
          "run_id",
          "run_version",
          "run_digest",
          "calculation_id",
          "calculation_version",
          "calculation_digest",
          "calculation_source_run_digest",
          "calculation_profile_digest",
          "calculation_rule_pack_digest",
          "pack_id",
          "pack_code",
          "pack_version",
          "rules_digest",
          "policy_id",
          "policy_sequence",
          "payslip_count",
          "gross_total_minor",
          "employee_deductions_total_minor",
          "employer_contributions_total_minor",
          "net_total_minor",
          "employer_cost_total_minor",
          "reviewed_totals",
          "reviewed_exceptions",
          "understands_no_payment",
          "same_preparer",
          "approval_digest",
          "approved_at",
          "approved_by_subject",
          "voided_at");

  private static final List<String> SLIP_COLUMNS =
      List.of(
          "tenant_id",
          "legal_entity_id",
          "package_id",
          "id",
          "period_start",
          "approved_at",
          "employee_id",
          "employee_number",
          "calculation_id",
          "calculation_line_id",
          "excluded",
          "gross_minor",
          "employee_deductions_minor",
          "employer_contributions_minor",
          "net_minor",
          "employer_cost_minor",
          "snapshot_version",
          "snapshot_text");

  private static final String ZZ_CODES =
      "(snapshot || jsonb_build_object('explanationCodes', '[\"ZZ\"]'::jsonb))::text";

  private static final String VOID_ROW =
      "INSERT INTO payroll.payroll_package_void (tenant_id, legal_entity_id, package_id, id,"
          + " reason_code, explanation, no_payment_acknowledged, approval_digest, voided_at,"
          + " voided_by_subject) SELECT tenant_id, legal_entity_id, id, gen_random_uuid(), ?, ?, ?,"
          + " ?, approved_at + interval '1 hour', 'sub-migration' FROM payroll.payroll_package"
          + " WHERE id = ?";
  private static final String VOID_PACKAGE =
      "UPDATE payroll.payroll_package SET status = 'VOIDED',"
          + " voided_at = approved_at + interval '%s' WHERE id = ?";
  private static final String SOURCE_COPY =
      "INSERT INTO payroll.payroll_package_source (tenant_id, package_id, kind, source_id,"
          + " source_version, source_digest) SELECT tenant_id, package_id, '%s', source_id,"
          + " source_version + %d, source_digest FROM payroll.payroll_package_source"
          + " WHERE package_id = ? AND kind = 'RUN'";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private JdbcConnectionDetails connection;

  private PayrollPackageWorld w;

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  @FunctionalInterface
  private interface DbWork {
    void run(JdbcTemplate db, String url) throws Exception;
  }

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  // ------------------------------------------------------------------------------------------
  // Plumbing
  // ------------------------------------------------------------------------------------------

  private Connection connect() throws SQLException {
    Connection c = Objects.requireNonNull(jdbc.getDataSource()).getConnection();
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

  /** Runs the work, judges every deferred constraint, and always rolls back. */
  private void attempt(SqlWork work) throws SQLException {
    try (Connection c = connect()) {
      try {
        work.run(c);
        exec(c, "SET CONSTRAINTS ALL IMMEDIATE");
      } finally {
        c.rollback();
      }
    }
  }

  private void refuses(String constraint, SqlWork work) {
    refusesOneOf(List.of(constraint), work);
  }

  private void refusesOneOf(List<String> constraints, SqlWork work) {
    assertThatThrownBy(() -> attempt(work))
        .as(constraints.toString())
        .isInstanceOfSatisfying(
            PSQLException.class,
            e -> assertThat(e.getServerErrorMessage().getConstraint()).isIn(constraints));
  }

  private void accepts(SqlWork work) throws SQLException {
    attempt(work);
  }

  private static String packageCopy(String... changes) {
    Map<String, String> replaced = new java.util.HashMap<>();
    replaced.put("id", "gen_random_uuid()");
    replaced.put("version", "version + 1");
    for (int i = 0; i < changes.length; i += 2) {
      replaced.put(changes[i], changes[i + 1]);
    }
    List<String> select = new ArrayList<>();
    for (String column : PACKAGE_COLUMNS) {
      select.add(replaced.getOrDefault(column, column));
    }
    return "INSERT INTO payroll.payroll_package ("
        + String.join(", ", PACKAGE_COLUMNS)
        + ") SELECT "
        + String.join(", ", select)
        + " FROM payroll.payroll_package WHERE id = ?";
  }

  private static String slipCopy(String textExpression, String... changes) {
    Map<String, String> replaced = new java.util.HashMap<>();
    replaced.put("id", "gen_random_uuid()");
    replaced.put("snapshot_text", textExpression);
    for (int i = 0; i < changes.length; i += 2) {
      replaced.put(changes[i], changes[i + 1]);
    }
    List<String> select = new ArrayList<>();
    for (String column : SLIP_COLUMNS) {
      select.add(replaced.getOrDefault(column, column));
    }
    return "INSERT INTO payroll.payroll_payslip ("
        + String.join(", ", SLIP_COLUMNS)
        + ") SELECT "
        + String.join(", ", select)
        + " FROM payroll.payroll_payslip WHERE package_id = ? AND NOT excluded LIMIT 1";
  }

  private UUID approved() throws Exception {
    Chain chain = w.readyWithThree();
    return UUID.fromString(w.approve(chain).get("packageId").asText());
  }

  private String digestOf(UUID pkg) {
    return jdbc.queryForObject(
        "SELECT approval_digest FROM payroll.payroll_package WHERE id = ?", String.class, pkg);
  }

  private static String resource(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void script(JdbcTemplate db, String sql) {
    db.execute(
        (org.springframework.jdbc.core.ConnectionCallback<Void>)
            c -> {
              try (java.sql.Statement statement = c.createStatement()) {
                statement.setEscapeProcessing(false);
                statement.execute(sql);
              }
              return null;
            });
  }

  private void withDatabase(DbWork body) throws Exception {
    String database = "payroll94c_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url = connection.getJdbcUrl().replaceAll("/[^/?]+(\\?.*)?$", "/" + database + "$1");
      body.run(
          new JdbcTemplate(
              new DriverManagerDataSource(url, connection.getUsername(), connection.getPassword())),
          url);
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

  // ------------------------------------------------------------------------------------------
  // Objects
  // ------------------------------------------------------------------------------------------

  @Test
  void theMigrationCreatesTheTablesIndexesTriggersAndGeneratedColumns() {
    assertThat(
            jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '46'", Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'payroll'"
                    + " AND table_name LIKE 'payroll\\_%' ORDER BY table_name",
                String.class))
        .contains(
            "payroll_package", "payroll_package_source", "payroll_package_void", "payroll_payslip");
    assertThat(
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'payroll'"
                    + " AND (indexname LIKE 'payroll\\_package\\_%' OR indexname LIKE"
                    + " 'payroll\\_payslip\\_%')",
                String.class))
        .contains(
            "payroll_package_history",
            "payroll_package_one_approved",
            "payroll_package_one_approved_run",
            "payroll_package_one_approved_calculation",
            "payroll_package_pair",
            "payroll_package_source_lookup",
            "payroll_payslip_employee_page",
            "payroll_payslip_package_page");
    assertThat(
            jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'payroll_package_history'",
                String.class))
        .contains("period_start DESC", "version DESC");
    assertThat(
            jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'payroll_payslip_package_page'",
                String.class))
        .contains("COLLATE \"C\"");
    assertThat(
            jdbc.queryForList(
                "SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                    + " WHERE c.relnamespace = 'payroll'::regnamespace AND NOT t.tgisinternal"
                    + " AND t.tgenabled = 'O' AND (t.tgname LIKE 'payroll\\_package\\_%'"
                    + " OR t.tgname LIKE 'payroll\\_payslip\\_%'"
                    + " OR t.tgname LIKE 'payroll\\_%\\_package\\_lock')",
                String.class))
        .contains(
            "payroll_package_guard",
            "payroll_package_no_delete",
            "payroll_package_no_truncate",
            "payroll_package_exact",
            "payroll_package_void_guard",
            "payroll_package_void_exact",
            "payroll_package_source_guard",
            "payroll_package_source_exact",
            "payroll_payslip_guard",
            "payroll_payslip_immutable",
            "payroll_payslip_exact",
            "payroll_pay_run_package_lock",
            "payroll_statutory_calculation_package_lock");
    assertThat(
            jdbc.queryForList(
                "SELECT table_name || '.' || column_name FROM information_schema.columns"
                    + " WHERE table_schema = 'payroll' AND is_generated = 'ALWAYS'"
                    + " AND table_name IN ('payroll_package', 'payroll_payslip')"
                    + " ORDER BY 1",
                String.class))
        .containsExactly(
            "payroll_package.synthetic",
            "payroll_payslip.snapshot",
            "payroll_payslip.snapshot_digest");
    // The migration seeds nothing.
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0, 0, 0);
  }

  // ------------------------------------------------------------------------------------------
  // Digests
  // ------------------------------------------------------------------------------------------

  @Test
  void theSnapshotDigestIsTheSha256OfTheCanonicalTextAndTheApprovalDigestIsRecomputed()
      throws Exception {
    UUID pkg = approved();
    List<Map<String, Object>> slips =
        jdbc.queryForList(
            "SELECT snapshot_text, snapshot_digest, employee_number, employee_id::text AS employee"
                + " FROM payroll.payroll_payslip WHERE package_id = ?"
                + " ORDER BY employee_number COLLATE \"C\", employee_id",
            pkg);
    assertThat(slips).hasSize(3);
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    StringBuilder lines = new StringBuilder();
    for (Map<String, Object> slip : slips) {
      String text = (String) slip.get("snapshot_text");
      String expected = HexFormat.of().formatHex(sha.digest(text.getBytes(StandardCharsets.UTF_8)));
      assertThat(slip.get("snapshot_digest")).isEqualTo(expected);
      assertThat(text).startsWith("{").endsWith("}");
      lines
          .append(slip.get("employee"))
          .append('|')
          .append(slip.get("employee_number"))
          .append('|')
          .append(expected)
          .append('\n');
    }
    Map<String, Object> p =
        jdbc.queryForMap(
            "SELECT tenant_id::text AS tenant, legal_entity_id::text AS entity,"
                + " to_char(period_start, 'YYYY-MM') AS period, currency, run_id::text AS run,"
                + " run_version::text AS run_version, run_digest, calculation_id::text AS calc,"
                + " calculation_version::text AS calc_version, calculation_digest,"
                + " calculation_source_run_digest AS src, calculation_profile_digest AS prof,"
                + " calculation_rule_pack_digest AS rp, pack_id::text AS pack, rules_digest,"
                + " policy_id::text AS policy, policy_sequence::text AS policy_seq,"
                + " gross_total_minor::text AS gross, employee_deductions_total_minor::text AS ded,"
                + " employer_contributions_total_minor::text AS emp, net_total_minor::text AS net,"
                + " employer_cost_total_minor::text AS cost, payslip_count::text AS cnt,"
                + " approval_digest FROM payroll.payroll_package WHERE id = ?",
            pkg);
    String text =
        "payroll-package-approval:v1\n"
            + "tenant="
            + p.get("tenant")
            + "\n"
            + "entity="
            + p.get("entity")
            + "\n"
            + "period="
            + p.get("period")
            + "\n"
            + "currency="
            + p.get("currency")
            + "\n"
            + "run="
            + p.get("run")
            + "|"
            + p.get("run_version")
            + "|"
            + p.get("run_digest")
            + "\n"
            + "calculation="
            + p.get("calc")
            + "|"
            + p.get("calc_version")
            + "|"
            + p.get("calculation_digest")
            + "|"
            + p.get("src")
            + "|"
            + p.get("prof")
            + "|"
            + p.get("rp")
            + "\n"
            + "pack="
            + p.get("pack")
            + "|"
            + p.get("rules_digest")
            + "\n"
            + "policy="
            + p.get("policy")
            + "|"
            + p.get("policy_seq")
            + "\n"
            + "totals="
            + p.get("gross")
            + "|"
            + p.get("ded")
            + "|"
            + p.get("emp")
            + "|"
            + p.get("net")
            + "|"
            + p.get("cost")
            + "|"
            + p.get("cnt")
            + "\n"
            + lines;
    String recomputed = HexFormat.of().formatHex(sha.digest(text.getBytes(StandardCharsets.UTF_8)));
    assertThat(p.get("approval_digest")).isEqualTo(recomputed);
    assertThat(
            jdbc.queryForObject(
                "SELECT payroll.payroll_package_approval_digest(tenant_id, id)"
                    + " FROM payroll.payroll_package WHERE id = ?",
                String.class,
                pkg))
        .isEqualTo(recomputed);
    assertThat(jdbc.queryForObject("SELECT payroll.payroll_snapshot_digest('{}')", String.class))
        .isEqualTo(HexFormat.of().formatHex(sha.digest("{}".getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void theSyntheticFlagIsGeneratedFromThePackCode() throws Exception {
    UUID pkg = approved();
    assertThat(
            jdbc.queryForObject(
                "SELECT synthetic FROM payroll.payroll_package WHERE id = ?", Boolean.class, pkg))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT pack_code FROM payroll.payroll_package WHERE id = ?", String.class, pkg))
        .startsWith("SYNTHETIC-TEST");
    refusesOneOf(
        List.of("payroll_package_pack_state", "payroll_package_pack_code"),
        c -> exec(c, packageCopy("pack_code", "'SYNTHETIC-TEST-OTHER'"), pkg));
  }

  // ------------------------------------------------------------------------------------------
  // Package guard
  // ------------------------------------------------------------------------------------------

  @Test
  void aPackageIsCreatedOnlyAsTheNextVersionOfTheCurrentSourcesAndTheirExactTotals()
      throws Exception {
    UUID pkg = approved();
    // A second APPROVED package of the chain, run or calculation is refused by the unique indexes.
    refusesOneOf(
        List.of(
            "payroll_package_one_approved",
            "payroll_package_one_approved_run",
            "payroll_package_one_approved_calculation"),
        c -> exec(c, packageCopy(), pkg));
    refuses("payroll_package_version", c -> exec(c, packageCopy("version", "version + 2"), pkg));
    refuses("payroll_package_version", c -> exec(c, packageCopy("version", "version"), pkg));
    refuses(
        "payroll_package_version",
        c ->
            exec(
                c,
                packageCopy("status", "'VOIDED'", "voided_at", "approved_at + interval '1 hour'"),
                pkg));
    refuses(
        "payroll_package_run_state",
        c -> exec(c, packageCopy("run_digest", "repeat('0', 64)"), pkg));
    refuses("payroll_package_run_state", c -> exec(c, packageCopy("run_version", "2"), pkg));
    refuses(
        "payroll_package_calculation_state",
        c -> exec(c, packageCopy("calculation_digest", "repeat('0', 64)"), pkg));
    refuses(
        "payroll_package_calculation_state",
        c -> exec(c, packageCopy("calculation_rule_pack_digest", "repeat('0', 64)"), pkg));
    refuses(
        "payroll_package_pack_state",
        c -> exec(c, packageCopy("rules_digest", "repeat('0', 64)"), pkg));
    refuses("payroll_package_pack_state", c -> exec(c, packageCopy("pack_version", "2"), pkg));
    refuses(
        "payroll_package_totals_source",
        c ->
            exec(
                c,
                packageCopy(
                    "gross_total_minor", "gross_total_minor + 1",
                    "net_total_minor", "net_total_minor + 1",
                    "employer_cost_total_minor", "employer_cost_total_minor + 1"),
                pkg));
    refuses("payroll_package_totals_source", c -> exec(c, packageCopy("payslip_count", "2"), pkg));
    refuses(
        "payroll_package_approved_at",
        c -> exec(c, packageCopy("approved_at", "timestamptz '2000-01-01 00:00:00+00'"), pkg));
    refuses(
        "payroll_package_same_preparer",
        c -> exec(c, packageCopy("same_preparer", "NOT same_preparer"), pkg));
    // The remaining rules are table checks: the guard lets these rows through.
    refuses(
        "payroll_package_acknowledgement",
        c -> exec(c, packageCopy("reviewed_totals", "false"), pkg));
    refuses(
        "payroll_package_acknowledgement",
        c -> exec(c, packageCopy("understands_no_payment", "false"), pkg));
    refusesOneOf(
        List.of("payroll_package_currency", "payroll_package_run_state"),
        c -> exec(c, packageCopy("currency", "'USD'"), pkg));
    refusesOneOf(
        List.of("payroll_package_totals_source", "payroll_package_arithmetic"),
        c -> exec(c, packageCopy("net_total_minor", "net_total_minor + 1"), pkg));
  }

  @Test
  void aPackageIsNeverUpdatedDeletedOrTruncatedExceptForItsSingleVoid() throws Exception {
    UUID pkg = approved();
    for (String change :
        List.of(
            "gross_total_minor = gross_total_minor + 1",
            "approval_digest = repeat('0', 64)",
            "approved_by_subject = 'someone-else'",
            "approved_at = approved_at + interval '1 second'",
            "same_preparer = NOT same_preparer",
            "payslip_count = payslip_count + 1",
            "run_digest = repeat('0', 64)",
            "pack_code = 'SYNTHETIC-TEST-OTHER'",
            "version = version + 1",
            "reviewed_totals = reviewed_totals")) {
      refusesOneOf(
          List.of(
              "payroll_package_lifecycle_only",
              "payroll_package_void_evidence",
              "payroll_package_version"),
          c -> exec(c, "UPDATE payroll.payroll_package SET " + change + " WHERE id = ?", pkg));
    }
    refuses(
        "payroll_package_immutable",
        c -> exec(c, "DELETE FROM payroll.payroll_package WHERE id = ?", pkg));
    refusesOneOf(
        List.of("payroll_package_immutable", "payroll_payslip_immutable"),
        c ->
            exec(
                c,
                "TRUNCATE payroll.payroll_package, payroll.payroll_package_source,"
                    + " payroll.payroll_package_void, payroll.payroll_payslip"));
    assertThat(w.evidence().get(0)).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Void evidence and lifecycle
  // ------------------------------------------------------------------------------------------

  @Test
  void aVoidNeedsItsEvidenceFirstAndTheLegalSequenceIsAccepted() throws Exception {
    UUID pkg = approved();
    String digest = digestOf(pkg);
    accepts(
        c -> {
          exec(c, VOID_ROW, "ADMINISTRATIVE_ERROR", null, true, digest, pkg);
          exec(c, VOID_PACKAGE.formatted("1 hour"), pkg);
        });
    accepts(
        c -> {
          exec(
              c,
              VOID_ROW,
              "OTHER_CONTROLLED",
              "A controlled explanation of the void",
              true,
              digest,
              pkg);
          exec(c, VOID_PACKAGE.formatted("1 hour"), pkg);
        });
    // The status alone, without evidence, is refused.
    refuses("payroll_package_void_evidence", c -> exec(c, VOID_PACKAGE.formatted("1 hour"), pkg));
    // Evidence whose instant differs from the package's is refused.
    refuses(
        "payroll_package_void_evidence",
        c -> {
          exec(c, VOID_ROW, "ADMINISTRATIVE_ERROR", null, true, digest, pkg);
          exec(c, VOID_PACKAGE.formatted("2 hours"), pkg);
        });
    // Evidence alone, without the VOIDED status, is judged at commit.
    refuses(
        "payroll_package_void_evidence",
        c -> exec(c, VOID_ROW, "ADMINISTRATIVE_ERROR", null, true, digest, pkg));
    // Evidence with another approval digest is refused.
    refuses(
        "payroll_package_void_state",
        c -> exec(c, VOID_ROW, "ADMINISTRATIVE_ERROR", null, true, "0".repeat(64), pkg));
    // Nothing else may move together with the status.
    refusesOneOf(
        List.of("payroll_package_lifecycle_only", "payroll_package_arithmetic"),
        c -> {
          exec(c, VOID_ROW, "ADMINISTRATIVE_ERROR", null, true, digest, pkg);
          exec(
              c,
              "UPDATE payroll.payroll_package SET status = 'VOIDED',"
                  + " voided_at = approved_at + interval '1 hour',"
                  + " gross_total_minor = gross_total_minor + 1 WHERE id = ?",
              pkg);
        });
    // One evidence row per package.
    refuses(
        "payroll_package_void_package_unique",
        c -> {
          exec(c, VOID_ROW, "ADMINISTRATIVE_ERROR", null, true, digest, pkg);
          exec(c, VOID_ROW, "SOURCE_CORRECTION", null, true, digest, pkg);
        });
  }

  @Test
  void theReasonTheExplanationAndTheAcknowledgementAreCheckedByTheDatabase() throws Exception {
    UUID pkg = approved();
    String digest = digestOf(pkg);
    refuses(
        "payroll_package_void_reason",
        c -> exec(c, VOID_ROW, "UNKNOWN_REASON", null, true, digest, pkg));
    refuses(
        "payroll_package_void_acknowledgement",
        c -> exec(c, VOID_ROW, "ADMINISTRATIVE_ERROR", null, false, digest, pkg));
    for (Object[] row :
        new Object[][] {
          {"OTHER_CONTROLLED", null},
          {"OTHER_CONTROLLED", "too short"},
          {"OTHER_CONTROLLED", "x".repeat(501)},
          {"OTHER_CONTROLLED", " padded explanation "},
          {"OTHER_CONTROLLED", "control\ncharacter in it"},
          {"ADMINISTRATIVE_ERROR", "an explanation is only for the other reason"}
        }) {
      refuses(
          "payroll_package_void_explanation",
          c -> exec(c, VOID_ROW, row[0], row[1], true, digest, pkg));
    }
  }

  @Test
  void aVoidedPackageStaysVoidedAndItsEvidenceAndPayslipsAreImmutable() throws Exception {
    UUID pkg = approved();
    List<String> before =
        jdbc.queryForList(
            "SELECT snapshot_digest FROM payroll.payroll_payslip WHERE package_id = ?"
                + " ORDER BY id",
            String.class,
            pkg);
    w.voidPackage(pkg, "ADMINISTRATIVE_ERROR", null);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payroll.payroll_package WHERE id = ?", String.class, pkg))
        .isEqualTo("VOIDED");
    assertThat(
            jdbc.queryForList(
                "SELECT snapshot_digest FROM payroll.payroll_payslip WHERE package_id = ?"
                    + " ORDER BY id",
                String.class,
                pkg))
        .isEqualTo(before);
    refusesOneOf(
        List.of("payroll_package_lifecycle_only", "payroll_package_lifecycle"),
        c ->
            exec(
                c,
                "UPDATE payroll.payroll_package SET status = 'APPROVED', voided_at = NULL"
                    + " WHERE id = ?",
                pkg));
    refuses(
        "payroll_package_immutable",
        c ->
            exec(
                c,
                "UPDATE payroll.payroll_package_void SET reason_code = reason_code"
                    + " WHERE package_id = ?",
                pkg));
    refuses(
        "payroll_package_immutable",
        c -> exec(c, "DELETE FROM payroll.payroll_package_void WHERE package_id = ?", pkg));
    refuses(
        "payroll_package_immutable",
        c -> exec(c, "DELETE FROM payroll.payroll_package_source WHERE package_id = ?", pkg));
    // A voided package cannot be voided again: the evidence is unique and the package is no
    // longer APPROVED.
    String digest = digestOf(pkg);
    refusesOneOf(
        List.of("payroll_package_void_state", "payroll_package_void_package_unique"),
        c -> exec(c, VOID_ROW, "ADMINISTRATIVE_ERROR", null, true, digest, pkg));
    // The voided pair can never be approved again, whatever the application says.
    refuses(
        "payroll_package_source_voided",
        c -> exec(c, packageCopy("status", "'APPROVED'", "voided_at", "NULL"), pkg));
  }

  // ------------------------------------------------------------------------------------------
  // Source binding and payslips
  // ------------------------------------------------------------------------------------------

  @Test
  void theSourceBindingIsAppendOnlyAndNamesTheExactSourceVersionAndDigest() throws Exception {
    UUID pkg = approved();
    assertThat(
            jdbc.queryForList(
                "SELECT kind FROM payroll.payroll_package_source WHERE package_id = ?"
                    + " ORDER BY kind",
                String.class,
                pkg))
        .containsExactly("CALCULATION", "RUN");
    refuses("payroll_package_source_binding", c -> exec(c, SOURCE_COPY.formatted("RUN", 1), pkg));
    refuses(
        "payroll_package_source_binding",
        c -> exec(c, SOURCE_COPY.formatted("CALCULATION", 0), pkg));
    // The same binding again is the primary key.
    refuses("payroll_package_source_pkey", c -> exec(c, SOURCE_COPY.formatted("RUN", 0), pkg));
    refuses(
        "payroll_package_immutable",
        c ->
            exec(
                c,
                "UPDATE payroll.payroll_package_source SET source_version = source_version"
                    + " WHERE package_id = ?",
                pkg));
    refuses(
        "payroll_package_immutable",
        c -> exec(c, "DELETE FROM payroll.payroll_package_source WHERE package_id = ?", pkg));
  }

  @Test
  void aPayslipSnapshotMustEqualItsColumnsItsPackageAndItsCalculationLine() throws Exception {
    UUID pkg = approved();
    // Positive control: the unchanged copy passes the guard and meets the unique employee.
    refusesOneOf(
        List.of("payroll_payslip_employee_unique", "payroll_payslip_line_unique"),
        c -> exec(c, slipCopy("snapshot_text"), pkg));
    refuses(
        "payroll_payslip_snapshot_fields",
        c -> exec(c, slipCopy("(snapshot - 'status')::text"), pkg));
    refuses(
        "payroll_payslip_snapshot_fields",
        c -> exec(c, slipCopy("(snapshot || jsonb_build_object('extra', 'x'))::text"), pkg));
    for (String field : List.of("netMinor", "status", "period", "packageId", "employeeNumber")) {
      refuses(
          "payroll_payslip_snapshot_values",
          c ->
              exec(
                  c,
                  slipCopy("(snapshot || jsonb_build_object('" + field + "', 'tampered'))::text"),
                  pkg));
    }
    refuses(
        "payroll_payslip_snapshot_values",
        c -> exec(c, slipCopy("(snapshot || jsonb_build_object('schemaVersion', 2))::text"), pkg));
    refusesOneOf(
        List.of("payroll_payslip_snapshot_sums", "payroll_payslip_snapshot_components"),
        c ->
            exec(
                c,
                slipCopy("(snapshot || jsonb_build_object('components', '[]'::jsonb))::text"),
                pkg));
    refuses(
        "payroll_payslip_snapshot_sums",
        c ->
            exec(
                c,
                slipCopy("(snapshot || jsonb_build_object('employeeCnssMinor', '1'))::text"),
                pkg));
    refuses("payroll_payslip_snapshot_explanations", c -> exec(c, slipCopy(ZZ_CODES), pkg));
    refuses(
        "payroll_payslip_source_line",
        c ->
            exec(
                c,
                slipCopy(
                    "snapshot_text",
                    "gross_minor",
                    "gross_minor + 1",
                    "net_minor",
                    "net_minor + 1",
                    "employer_cost_minor",
                    "employer_cost_minor + 1"),
                pkg));
    refuses(
        "payroll_payslip_package_state",
        c ->
            exec(
                c,
                slipCopy("snapshot_text", "approved_at", "approved_at + interval '1 second'"),
                pkg));
    refuses(
        "payroll_payslip_snapshot_version",
        c -> exec(c, slipCopy("snapshot_text", "snapshot_version", "2"), pkg));
    refusesOneOf(
        List.of("payroll_payslip_source_line", "payroll_payslip_arithmetic"),
        c -> exec(c, slipCopy("snapshot_text", "net_minor", "net_minor + 1"), pkg));
  }

  @Test
  void aPayslipIsNeverUpdatedOrDeleted() throws Exception {
    UUID pkg = approved();
    refuses(
        "payroll_payslip_immutable",
        c ->
            exec(
                c,
                "UPDATE payroll.payroll_payslip SET employee_number = employee_number"
                    + " WHERE package_id = ?",
                pkg));
    refuses(
        "payroll_payslip_immutable",
        c -> exec(c, "DELETE FROM payroll.payroll_payslip WHERE package_id = ?", pkg));
    refuses("payroll_payslip_immutable", c -> exec(c, "TRUNCATE payroll.payroll_payslip"));
    assertThat(w.evidence().get(3)).isEqualTo(3);
  }

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV45ExactlyAndTheMigrationCanBeAppliedAgain() throws Exception {
    withDatabase(
        (db, url) -> {
          migrate(url, "45");
          String v45 = db.queryForObject(signature(), String.class);
          migrate(url, "46");
          String v46 = db.queryForObject(signature(), String.class);
          assertThat(v46).isNotEqualTo(v45).contains("payroll_payslip");
          int recorded =
              Objects.requireNonNull(
                  db.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
          script(db, resource("db/rollback/V46__rollback.sql"));
          assertThat(db.queryForObject(signature(), String.class)).isEqualTo(v45);
          // The rollback never edits the Flyway history.
          assertThat(db.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
              .isEqualTo(recorded);
          script(db, resource("db/migration/V46__payroll_approval_payslips.sql"));
          assertThat(db.queryForObject(signature(), String.class)).isEqualTo(v46);
        });
  }

  @Test
  void theRollbackRefusesWhileAnyPackageSourceVoidOrPayslipRowExists() throws Exception {
    withDatabase(
        (db, url) -> {
          migrate(url, "46");
          String v46 = db.queryForObject(signature(), String.class);
          UUID tenant = UUID.randomUUID();
          UUID entity = UUID.randomUUID();
          UUID pkg = UUID.randomUUID();
          String digest = "a".repeat(64);
          List<String> rows =
              List.of(
                  "INSERT INTO payroll.payroll_package (tenant_id, legal_entity_id, id,"
                      + " period_start, currency, version, status, run_id, run_version, run_digest,"
                      + " calculation_id, calculation_version, calculation_digest,"
                      + " calculation_source_run_digest, calculation_profile_digest,"
                      + " calculation_rule_pack_digest, pack_id, pack_code, pack_version,"
                      + " rules_digest, policy_id, policy_sequence, payslip_count,"
                      + " gross_total_minor, employee_deductions_total_minor,"
                      + " employer_contributions_total_minor, net_total_minor,"
                      + " employer_cost_total_minor, reviewed_totals, reviewed_exceptions,"
                      + " understands_no_payment, same_preparer, approval_digest, approved_at,"
                      + " approved_by_subject) VALUES ('"
                      + tenant
                      + "', '"
                      + entity
                      + "', '"
                      + pkg
                      + "', DATE '2026-09-01', 'CDF', 1, 'APPROVED', gen_random_uuid(), 1, '"
                      + digest
                      + "', gen_random_uuid(), 1, '"
                      + digest
                      + "', '"
                      + digest
                      + "', '"
                      + digest
                      + "', '"
                      + digest
                      + "', gen_random_uuid(), 'SYNTHETIC-TEST-X', 1, '"
                      + digest
                      + "', gen_random_uuid(), 1, 1, 100, 10, 5, 90, 105, true, true, true,"
                      + " false, '"
                      + digest
                      + "', now(), 'subject')",
                  "INSERT INTO payroll.payroll_package_source (tenant_id, package_id, kind,"
                      + " source_id, source_version, source_digest) VALUES ('"
                      + tenant
                      + "', '"
                      + pkg
                      + "', 'RUN', gen_random_uuid(), 1, '"
                      + digest
                      + "')",
                  "INSERT INTO payroll.payroll_package_void (tenant_id, legal_entity_id,"
                      + " package_id, id, reason_code, explanation, no_payment_acknowledged,"
                      + " approval_digest, voided_at, voided_by_subject) VALUES ('"
                      + tenant
                      + "', '"
                      + entity
                      + "', '"
                      + pkg
                      + "', gen_random_uuid(), 'ADMINISTRATIVE_ERROR', NULL, true, '"
                      + digest
                      + "', now(), 'subject')",
                  "INSERT INTO payroll.payroll_payslip (tenant_id, legal_entity_id, package_id, id,"
                      + " period_start, approved_at, employee_id, employee_number, calculation_id,"
                      + " calculation_line_id, excluded, gross_minor, employee_deductions_minor,"
                      + " employer_contributions_minor, net_minor, employer_cost_minor,"
                      + " snapshot_version, snapshot_text) VALUES ('"
                      + tenant
                      + "', '"
                      + entity
                      + "', '"
                      + pkg
                      + "', gen_random_uuid(), DATE '2026-09-01', now(), gen_random_uuid(),"
                      + " 'E-1', gen_random_uuid(), gen_random_uuid(), false, 100, 10, 5, 90,"
                      + " 105, 1, '{}')");
          List<String> tables =
              List.of(
                  "payroll_package",
                  "payroll_package_source",
                  "payroll_package_void",
                  "payroll_payslip");
          for (int i = 0; i < rows.size(); i++) {
            String insert = rows.get(i);
            String table = tables.get(i);
            // Row-level triggers and foreign keys are off for the fixture only (replica role).
            db.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Void>)
                    c -> {
                      c.setAutoCommit(false);
                      try (java.sql.Statement s = c.createStatement()) {
                        s.execute("SET LOCAL session_replication_role = replica");
                        s.execute(insert);
                      }
                      c.commit();
                      return null;
                    });
            assertThat(db.queryForObject("SELECT count(*) FROM payroll." + table, Integer.class))
                .as(table)
                .isEqualTo(1);
            assertThatThrownBy(() -> script(db, resource("db/rollback/V46__rollback.sql")))
                .as(table)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("V46 rollback refused");
            db.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Void>)
                    c -> {
                      c.setAutoCommit(false);
                      try (java.sql.Statement s = c.createStatement()) {
                        s.execute("SET LOCAL session_replication_role = replica");
                        s.execute("DELETE FROM payroll." + table);
                      }
                      c.commit();
                      return null;
                    });
            assertThat(db.queryForObject(signature(), String.class)).as(table).isEqualTo(v46);
          }
          // With nothing left the rollback succeeds.
          script(db, resource("db/rollback/V46__rollback.sql"));
          assertThat(db.queryForObject(signature(), String.class)).isNotEqualTo(v46);
        });
  }
}
