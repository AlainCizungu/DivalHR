package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PaymentReconciliationFixtures.exec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-096 (Issue #194, ADR 0039 section 7) V50 directly against PostgreSQL: whatever the
 * application checks, the database refuses an update, a delete or a truncate of a mapping, a
 * journal or a line; a journal without all its lines, with an unbalanced or a differently totalled
 * set of lines, with a line outside its mapping or beyond its count, with totals that differ from
 * the frozen package, a second journal of a package and a journal of a voided package; and a
 * mapping that is out of chain, overlapping, out of time order, over the version limit or with an
 * invalid code (a leading hyphen included). Each refusal is proved by the constraint it names. The
 * API answers for an invalid code and an overlapping date are checked as well. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.accounting-preview=600",
      "divalhr.request-limits.subject.accounting-journal-write=600",
      "divalhr.request-limits.subject.accounting-mapping-write=600",
      "divalhr.request-limits.subject.accounting-read=600"
    })
class AccountingConstraintIntegrationTest {

  private static final Instant BASE = Instant.parse("2031-01-01T00:00:00Z");
  private static final String[] CODES = {"6100", "4310", "6450", "4320", "4210"};
  private static final String[] DIRECTIONS = {"DEBIT", "CREDIT", "DEBIT", "CREDIT", "CREDIT"};

  private static final String INSERT_MAPPING =
      "INSERT INTO payroll.accounting_mapping (tenant_id, legal_entity_id, id, currency, version,"
          + " effective_from, salary_expense, employee_deduction_liability,"
          + " employer_contribution_expense, employer_contribution_liability,"
          + " net_salary_payable, mapping_digest, created_at, created_by_subject) VALUES (?, ?, ?,"
          + " ?, ?, CAST(? AS date), ?, ?, ?, ?, ?, ?, ?, 'sub-test')";

  /** Writes a journal header exactly from the frozen package, shifted by the given amounts. */
  private static final String INSERT_JOURNAL =
      "INSERT INTO payroll.accounting_journal (tenant_id, legal_entity_id, id, package_id,"
          + " currency, version, period_start, legal_entity_code, package_version,"
          + " package_approval_digest, calculation_id, calculation_version, calculation_digest,"
          + " mapping_id, mapping_version, mapping_digest, payslip_count, source_entry_count,"
          + " line_count, gross_total_minor, employee_deductions_total_minor,"
          + " employer_contributions_total_minor, net_total_minor, total_debits_minor,"
          + " total_credits_minor, csv_sha256, csv_bytes, journal_digest, schema_version,"
          + " generated_at, generated_by_subject) SELECT p.tenant_id, p.legal_entity_id,"
          + " CAST(? AS uuid), p.id, p.currency, CAST(? AS integer), p.period_start, e.code,"
          + " p.version, p.approval_digest, p.calculation_id, p.calculation_version,"
          + " p.calculation_digest, m.id, m.version, m.mapping_digest, p.payslip_count, 10,"
          + " CAST(? AS integer), p.gross_total_minor + CAST(? AS bigint),"
          + " p.employee_deductions_total_minor, p.employer_contributions_total_minor,"
          + " p.net_total_minor + CAST(? AS bigint),"
          + " p.gross_total_minor + p.employer_contributions_total_minor + CAST(? AS bigint),"
          + " p.employee_deductions_total_minor + p.employer_contributions_total_minor"
          + " + p.net_total_minor + CAST(? AS bigint),"
          + " repeat('d', 64), 100, repeat('e', 64), 1, now(), 'sub-test'"
          + " FROM payroll.payroll_package p JOIN tenant.legal_entity e"
          + " ON e.tenant_id = p.tenant_id AND e.id = p.legal_entity_id"
          + " JOIN payroll.accounting_mapping m ON m.tenant_id = p.tenant_id"
          + " AND m.legal_entity_id = p.legal_entity_id AND m.currency = p.currency"
          + " WHERE p.tenant_id = ? AND p.id = ?";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private JdbcConnectionDetails connection;

  private PayrollPackageWorld w;
  private AccountingWorld a;

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  /** An approved package of the world and the reference mapping. */
  private void approvedWithMapping() throws Exception {
    a = new AccountingWorld(w, w.approve(w.ready()).get("packageId").asText());
    a.createMapping("2000-01-01");
  }

  private Connection connect() throws SQLException {
    Connection c =
        DriverManager.getConnection(
            connection.getJdbcUrl(), connection.getUsername(), connection.getPassword());
    c.setAutoCommit(false);
    return c;
  }

  /** Runs work in a transaction that is committed. */
  private void committing(SqlWork work) throws SQLException {
    try (Connection c = connect()) {
      work.run(c);
      exec(c, "SET CONSTRAINTS ALL IMMEDIATE");
      c.commit();
    }
  }

  /** Runs work in a transaction that is always rolled back and must be refused by a constraint. */
  private void refuses(String constraint, SqlWork work) {
    refusesAny(Set.of(constraint), work);
  }

  private void refusesAny(Set<String> constraints, SqlWork work) {
    assertThatThrownBy(
            () -> {
              try (Connection c = connect()) {
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
            e ->
                assertThat(constraints)
                    .contains(
                        Objects.requireNonNull(e.getServerErrorMessage()).getConstraint()));
  }

  /** Runs work in a transaction that is always rolled back and must fail for any reason. */
  private void failsAnyway(SqlWork work) {
    assertThatThrownBy(
            () -> {
              try (Connection c = connect()) {
                try {
                  work.run(c);
                  exec(c, "SET CONSTRAINTS ALL IMMEDIATE");
                } finally {
                  c.rollback();
                }
              }
            })
        .isInstanceOf(PSQLException.class);
  }

  // ---- mapping rows ----

  private void mapping(
      Connection c,
      String currency,
      int version,
      String from,
      Instant at,
      String[] codes,
      String digest)
      throws SQLException {
    exec(
        c,
        INSERT_MAPPING,
        w.tenant,
        w.entity,
        UUID.randomUUID(),
        currency,
        version,
        from,
        codes[0],
        codes[1],
        codes[2],
        codes[3],
        codes[4],
        digest,
        at);
  }

  private void mapping(Connection c, int version, String from) throws SQLException {
    mapping(c, "CDF", version, from, BASE.plusSeconds(60L * version), CODES, "a".repeat(64));
  }

  private void mappingWithCode(Connection c, int field, String code) throws SQLException {
    String[] codes = CODES.clone();
    codes[field] = code;
    mapping(c, "CDF", 1, "2031-01-01", BASE, codes, "a".repeat(64));
  }

  @Test
  void aValidChainWithBoundaryCodesAndIndependentCurrenciesCommits() throws Exception {
    String longest = "A" + "0".repeat(39);
    committing(
        c -> {
          mapping(c, 1, "2031-01-01");
          mapping(c, 2, "2031-02-01");
          mapping(
              c,
              "CDF",
              3,
              "2031-03-01",
              BASE.plusSeconds(600),
              new String[] {longest, "A.B_C-1", "0", "Z9", "4210"},
              "b".repeat(64));
          // USD has a chain of its own and nothing converts between the two.
          mapping(c, "USD", 1, "2031-01-01", BASE, CODES, "c".repeat(64));
        });
    assertThat(
            w.c.count(
                "SELECT count(*) FROM payroll.accounting_mapping WHERE tenant_id = ?", w.tenant))
        .isEqualTo(4);
  }

  @Test
  void theFirstVersionOfAChainIsVersionOneAndNoVersionIsSkipped() {
    refuses("accounting_mapping_chain", c -> mapping(c, 2, "2031-01-01"));
    refuses(
        "accounting_mapping_chain",
        c -> {
          mapping(c, 1, "2031-01-01");
          mapping(c, 3, "2031-02-01");
        });
    refuses(
        "accounting_mapping_chain",
        c -> {
          mapping(c, 1, "2031-01-01");
          mapping(c, 1, "2031-02-01");
        });
  }

  @Test
  void aVersionTakesEffectStrictlyAfterThePreviousOne() {
    refuses(
        "accounting_mapping_overlap",
        c -> {
          mapping(c, 1, "2031-02-01");
          mapping(c, 2, "2031-02-01");
        });
    refuses(
        "accounting_mapping_overlap",
        c -> {
          mapping(c, 1, "2031-02-01");
          mapping(c, 2, "2031-01-01");
        });
  }

  @Test
  void versionsAreAppendedForwardInTime() {
    refuses(
        "accounting_mapping_time",
        c -> {
          mapping(c, 1, "2031-01-01");
          mapping(c, "CDF", 2, "2031-02-01", BASE.minusSeconds(60), CODES, "a".repeat(64));
        });
  }

  @Test
  void aChainHoldsAtMostTenVersions() {
    refuses(
        "accounting_mapping_version_range",
        c -> {
          for (int version = 1; version <= 10; version++) {
            mapping(c, version, String.format("2031-%02d-01", version));
          }
          mapping(c, 11, "2031-11-01");
        });
  }

  @Test
  void aCodeThatDoesNotMatchTheFormatIsRefusedInEveryColumn() {
    // A leading hyphen (or equals sign) could be read as a formula by a spreadsheet.
    for (int field = 0; field < CODES.length; field++) {
      int column = field;
      refuses("accounting_mapping_codes", c -> mappingWithCode(c, column, "-6100"));
    }
    for (String invalid :
        List.of(
            "",
            " ",
            "=SUM(A1)",
            "+6100",
            "@6100",
            ".6100",
            "_6100",
            "abc",
            "6100 ",
            "61 00",
            "61,00",
            "61\n00",
            "A".repeat(41),
            "ÉCOLE")) {
      refuses("accounting_mapping_codes", c -> mappingWithCode(c, 0, invalid));
    }
  }

  @Test
  void aMappingNeedsAnExistingLegalEntityAKnownCurrencyAndADigest() {
    refuses(
        "accounting_mapping_legal_entity",
        c ->
            exec(
                c,
                INSERT_MAPPING,
                w.tenant,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CDF",
                1,
                "2031-01-01",
                CODES[0],
                CODES[1],
                CODES[2],
                CODES[3],
                CODES[4],
                "a".repeat(64),
                BASE));
    refuses(
        "accounting_mapping_currency",
        c -> mapping(c, "EUR", 1, "2031-01-01", BASE, CODES, "a".repeat(64)));
    refuses(
        "accounting_mapping_digest",
        c -> mapping(c, "CDF", 1, "2031-01-01", BASE, CODES, "not-a-digest"));
  }

  // ---- journal rows ----

  private long amount(String column) {
    Long value =
        jdbc.queryForObject(
            "SELECT " + column + " FROM payroll.payroll_package WHERE tenant_id = ? AND id = ?",
            Long.class,
            w.tenant,
            UUID.fromString(a.pkg));
    return value == null ? 0 : value;
  }

  /** The five reference line amounts: gross, deductions, employer, employer, net. */
  private long[] reference() {
    long[] amounts = {
      amount("gross_total_minor"),
      amount("employee_deductions_total_minor"),
      amount("employer_contributions_total_minor"),
      amount("employer_contributions_total_minor"),
      amount("net_total_minor")
    };
    assertThat(amounts).as("the reference package has all five entries").doesNotContain(0L);
    return amounts;
  }

  private void header(
      Connection c,
      UUID journal,
      int version,
      int lineCount,
      long grossShift,
      long netShift,
      long debitsShift,
      long creditsShift)
      throws SQLException {
    exec(
        c,
        INSERT_JOURNAL,
        journal,
        version,
        lineCount,
        grossShift,
        netShift,
        debitsShift,
        creditsShift,
        w.tenant,
        UUID.fromString(a.pkg));
  }

  private void header(Connection c, UUID journal) throws SQLException {
    header(c, journal, 1, 5, 0, 0, 0, 0);
  }

  private void lines(Connection c, UUID journal, String[] codes, long... amounts)
      throws SQLException {
    for (int i = 0; i < amounts.length; i++) {
      exec(
          c,
          "INSERT INTO payroll.accounting_journal_line (tenant_id, journal_id, line_number,"
              + " account_code, direction, amount_minor) VALUES (?, ?, ?, ?, ?, ?)",
          w.tenant,
          journal,
          i + 1,
          codes[i],
          DIRECTIONS[i],
          amounts[i]);
    }
  }

  private void lines(Connection c, UUID journal, long... amounts) throws SQLException {
    lines(c, journal, CODES, amounts);
  }

  @Test
  void aBalancedCompleteJournalCommitsAsAControl() throws Exception {
    approvedWithMapping();
    UUID journal = UUID.randomUUID();
    long[] amounts = reference();
    committing(
        c -> {
          header(c, journal);
          lines(c, journal, amounts);
        });
    assertThat(a.journalCount()).isEqualTo(1);
    assertThat(a.lineCount()).isEqualTo(5);
  }

  @Test
  void aJournalWithoutAllItsLinesIsRefusedAtCommit() throws Exception {
    approvedWithMapping();
    long[] amounts = reference();
    refuses("accounting_journal_lines_exact", c -> header(c, UUID.randomUUID()));
    UUID journal = UUID.randomUUID();
    refuses(
        "accounting_journal_lines_exact",
        c -> {
          header(c, journal);
          lines(c, journal, amounts[0], amounts[1], amounts[2], amounts[3]);
        });
    assertThat(a.journalCount()).isZero();
    assertThat(a.lineCount()).isZero();
  }

  @Test
  void aJournalWhoseLinesDoNotBalanceOrDoNotEqualItsTotalsIsRefusedAtCommit() throws Exception {
    approvedWithMapping();
    long[] amounts = reference();
    UUID journal = UUID.randomUUID();
    // One credit is a unit short: debits differ from credits.
    refuses(
        "accounting_journal_lines_balanced",
        c -> {
          header(c, journal);
          lines(c, journal, amounts[0], amounts[1], amounts[2], amounts[3], amounts[4] - 1);
        });
    // Balanced among themselves, but not what the header (and the package) say.
    refuses(
        "accounting_journal_lines_balanced",
        c -> {
          header(c, journal);
          lines(c, journal, amounts[0] + 1, amounts[1], amounts[2], amounts[3], amounts[4] + 1);
        });
    assertThat(a.journalCount()).isZero();
  }

  @Test
  void aLineMustUseAMappedCodeAndStayWithinTheDeclaredCount() throws Exception {
    approvedWithMapping();
    long[] amounts = reference();
    UUID journal = UUID.randomUUID();
    refuses(
        "accounting_journal_line_mapped",
        c -> {
          header(c, journal);
          lines(c, journal, new String[] {"9999", "4310", "6450", "4320", "4210"}, amounts);
        });
    refuses(
        "accounting_journal_line_count",
        c -> {
          header(c, journal);
          lines(c, journal, amounts);
          exec(
              c,
              "INSERT INTO payroll.accounting_journal_line (tenant_id, journal_id, line_number,"
                  + " account_code, direction, amount_minor) VALUES (?, ?, 6, '6100', 'DEBIT', 1)",
              w.tenant,
              journal);
        });
  }

  @Test
  void aHeaderMustBalanceHaveSaneCountsAndCarryExactlyThePackageTotals() throws Exception {
    approvedWithMapping();
    UUID journal = UUID.randomUUID();
    refuses("accounting_journal_balanced", c -> header(c, journal, 1, 5, 0, 0, 1, 0));
    refuses("accounting_journal_counts", c -> header(c, journal, 1, 0, 0, 0, 0, 0));
    refuses("accounting_journal_counts", c -> header(c, journal, 1, 11, 0, 0, 0, 0));
    // Balanced and consistent, but not the frozen package totals.
    refuses("accounting_journal_totals_exact", c -> header(c, journal, 1, 5, 1, 1, 1, 1));
    assertThat(a.journalCount()).isZero();
  }

  @Test
  void aPackageHasOneJournalOnly() throws Exception {
    approvedWithMapping();
    a.generated();
    long[] amounts = reference();
    UUID second = UUID.randomUUID();
    refuses(
        "accounting_journal_one_per_package",
        c -> {
          header(c, second, 2, 5, 0, 0, 0, 0);
          lines(c, second, amounts);
        });
    assertThat(a.journalCount()).isEqualTo(1);
  }

  @Test
  void aVoidedPackageCannotGetAJournalEvenByDirectSql() throws Exception {
    approvedWithMapping();
    w.advance(5);
    w.voidPackage(a.pkg, "ADMINISTRATIVE_ERROR", null);
    long[] amounts = reference();
    UUID journal = UUID.randomUUID();
    refuses(
        "accounting_journal_source_state",
        c -> {
          header(c, journal);
          lines(c, journal, amounts);
        });
    assertThat(a.journalCount()).isZero();
  }

  // ---- immutability ----

  @Test
  void mappingsJournalsAndLinesRefuseUpdateDeleteAndTruncate() throws Exception {
    approvedWithMapping();
    a.generated();
    int lines = a.lineCount();
    Map<String, String> guarded = new LinkedHashMap<>();
    guarded.put("accounting_mapping", "accounting_mapping_immutable");
    guarded.put("accounting_journal", "accounting_journal_immutable");
    guarded.put("accounting_journal_line", "accounting_journal_line_immutable");
    String[] touched = {
      "accounting_mapping:version",
      "accounting_journal:version",
      "accounting_journal_line:line_number"
    };
    for (String entry : touched) {
      String table = entry.substring(0, entry.indexOf(':'));
      String column = entry.substring(entry.indexOf(':') + 1);
      String constraint = guarded.get(table);
      refuses(
          constraint,
          c ->
              exec(
                  c,
                  "UPDATE payroll." + table + " SET " + column + " = " + column
                      + " WHERE tenant_id = ?",
                  w.tenant));
      refuses(
          constraint,
          c -> exec(c, "DELETE FROM payroll." + table + " WHERE tenant_id = ?", w.tenant));
    }
    // The line table is not referenced by any foreign key, so its truncate trigger answers.
    refuses(
        "accounting_journal_line_immutable",
        c -> exec(c, "TRUNCATE TABLE payroll.accounting_journal_line"));
    // The referenced tables are refused by a trigger (cascade) or by their foreign keys (plain).
    Set<String> any = Set.copyOf(guarded.values());
    refusesAny(any, c -> exec(c, "TRUNCATE TABLE payroll.accounting_journal CASCADE"));
    refusesAny(any, c -> exec(c, "TRUNCATE TABLE payroll.accounting_mapping CASCADE"));
    failsAnyway(c -> exec(c, "TRUNCATE TABLE payroll.accounting_journal"));
    failsAnyway(c -> exec(c, "TRUNCATE TABLE payroll.accounting_mapping"));

    assertThat(a.mappingCount()).isEqualTo(1);
    assertThat(a.journalCount()).isEqualTo(1);
    assertThat(a.lineCount()).isEqualTo(lines);
  }

  // ---- through the API ----

  @Test
  void anInvalidCodeIsAStableUnprocessableAnswerThatNeverEchoesTheCode() throws Exception {
    approvedWithMapping();
    for (String field : List.of("salaryExpense", "netSalaryPayable")) {
      Map<String, Object> accounts = new LinkedHashMap<>(AccountingWorld.ACCOUNTS);
      accounts.put(field, "-SENTINEL-Q7");
      JsonNode body =
          w.c.problem(
              a.mappingPreviewRequest("2000-02-01", accounts), 422, "ACCOUNTING_MAPPING_INVALID");
      assertThat(body.toString()).doesNotContain("SENTINEL-Q7");
    }
    assertThat(a.mappingCount()).isEqualTo(1);
  }

  @Test
  void aMappingOnOrBeforeTheLatestEffectiveDateIsBlockedAsAnOverlap() throws Exception {
    approvedWithMapping();
    a.createMapping("2000-02-01");
    for (String from : List.of("2000-02-01", "2000-01-15")) {
      JsonNode preview = w.c.expect(a.mappingPreviewRequest(from, AccountingWorld.ACCOUNTS), 200);
      assertThat(preview.get("status").asText()).as(from).isEqualTo("BLOCKED");
      assertThat(preview.get("blockers").get(0).asText()).isEqualTo("OVERLAP");
    }
    assertThat(a.mappingCount()).isEqualTo(2);
  }
}
