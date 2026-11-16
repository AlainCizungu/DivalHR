package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository;
import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * MVP-097A, R200-1 (Issue #200, ADR 0040 section 7): V51 applies to a database that already holds
 * VERIFIED and RETIRED statutory packs created before governance existed. Each one is quarantined
 * as LEGACY without changing a single column of the pack, nothing is invented (no evidence, no
 * digest, no history), the DRAFT pack stays DRAFT, and a LEGACY version can never be moved, edited,
 * validated, selected as applicable or created again. Directly against PostgreSQL in a database of
 * its own. Synthetic data only: the packs below prove the mechanism and carry no statutory meaning.
 */
@IntegrationTest
class StatutoryPackLegacyMigrationIntegrationTest {

  private static final String PACKS =
      """
      SELECT coalesce(string_agg(id || '|' || code || '|' || version || '|' || status || '|'
                 || rules_digest || '|' || coalesce(reviewer_name, '') || '|'
                 || coalesce(reviewed_on::text, '') || '|' || attested || '|'
                 || coalesce(verified_at::text, '') || '|' || coalesce(retired_at::text, ''),
                 ';' ORDER BY code, version), '')
      FROM payroll.statutory_rule_pack
      """;

  private static final String RULES =
      """
      {"schemaVersion": 1, "components": [{"id": "SYNTHETIC_COMPONENT", "party": "EMPLOYEE",
        "category": "SOCIAL_CONTRIBUTION", "order": 1, "requires": "CNSS_SUBJECT", "base": "GROSS",
        "sourceKey": "CNSS_RATES", "method": {"type": "RATE", "rateBp": 1}, "rounding": "HALF_UP"}]}
      """;

  private static final String SIGNATURE =
      """
      SELECT md5(
          coalesce((SELECT string_agg(c.relname || ':' || c.relkind::text, ',' ORDER BY c.relname)
                    FROM pg_class c WHERE c.relnamespace = 'payroll'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(c.relname || '.' || a.attname || ':'
                                  || format_type(a.atttypid, a.atttypmod) || ':' || a.attnotnull::text,
                                  ',' ORDER BY c.relname, a.attname)
                              FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                              WHERE c.relnamespace = 'payroll'::regnamespace AND a.attnum > 0
                                AND NOT a.attisdropped AND c.relkind = 'r'), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ',' ORDER BY conrelid::regclass::text, conname)
                              FROM pg_constraint WHERE connamespace = 'payroll'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ',' ORDER BY proname)
                              FROM pg_proc WHERE pronamespace = 'payroll'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(tgrelid::regclass::text || '.' || tgname, ',' ORDER BY tgrelid::regclass::text, tgname)
                              FROM pg_trigger WHERE NOT tgisinternal
                              AND tgrelid::regclass::text LIKE 'payroll.%'), ''))
      """;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcConnectionDetails connection;

  private record Db(String url, JdbcTemplate jdbc) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "payroll97l_" + UUID.randomUUID().toString().replace("-", "");
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

  private static int count(Db db, String sql) {
    return java.util.Objects.requireNonNull(db.jdbc().queryForObject(sql, Integer.class));
  }

  /**
   * A pack created the way V45 allowed: DRAFT with its source, then VERIFIED, then maybe RETIRED.
   */
  private static UUID legacyPack(Db db, String code, boolean retired) {
    UUID id = UUID.randomUUID();
    db.jdbc()
        .update(
            "INSERT INTO payroll.statutory_rule_pack (id, code, version, country, currency, status,"
                + " effective_from, effective_to, method_version, rules, created_at)"
                + " VALUES (?, ?, 1, 'CD', 'CDF', 'DRAFT', DATE '2020-01-01', NULL, '1',"
                + " ?::jsonb, now())",
            id,
            code,
            RULES);
    db.jdbc()
        .update(
            "INSERT INTO payroll.statutory_rule_pack_source (pack_id, seq, source_key, title, url,"
                + " sha256, retrieved_on) VALUES (?, 1, 'CNSS_RATES', 'Synthetic test source',"
                + " 'https://cnss.cd/?page_id=1388', repeat('a', 64), DATE '2026-10-29')",
            id);
    db.jdbc()
        .update(
            "UPDATE payroll.statutory_rule_pack SET status = 'VERIFIED', reviewer_name = 'Synthetic"
                + " reviewer', reviewer_organization = 'Synthetic organization', reviewed_on = DATE"
                + " '2026-10-30', attested = true, verified_at = now() WHERE id = ?",
            id);
    if (retired) {
      db.jdbc()
          .update(
              "UPDATE payroll.statutory_rule_pack SET status = 'RETIRED', retired_at = now()"
                  + " WHERE id = ?",
              id);
    }
    return id;
  }

  @Test
  void v51QuarantinesExistingNonDraftPacksAsLegacyAndChangesNothingElse() {
    withDatabase(
        db -> {
          migrate(db.url(), "50");
          UUID verified = legacyPack(db, "SYNTHETIC-LEGACY-VERIFIED", false);
          UUID retired = legacyPack(db, "SYNTHETIC-LEGACY-RETIRED", true);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM payroll.statutory_rule_pack WHERE status <>"
                          + " 'DRAFT'"))
              .isEqualTo(2);
          String before = db.jdbc().queryForObject(PACKS, String.class);

          // The migration that failed on hr-dev now applies.
          migrate(db.url(), "51");

          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM flyway_schema_history WHERE version = '51' AND success"))
              .isEqualTo(1);
          // Not one column of any pack changed: status, evidence, digest, interval.
          assertThat(db.jdbc().queryForObject(PACKS, String.class)).isEqualTo(before);
          assertThat(count(db, "SELECT count(*) FROM payroll.statutory_pack_governance"))
              .isEqualTo(count(db, "SELECT count(*) FROM payroll.statutory_rule_pack"));
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT state FROM payroll.statutory_pack_governance WHERE pack_id = ?",
                          String.class,
                          verified))
              .isEqualTo("LEGACY");
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT state FROM payroll.statutory_pack_governance WHERE pack_id = ?",
                          String.class,
                          retired))
              .isEqualTo("LEGACY");
          // The seeded DRAFT pack stays DRAFT, with its legacy status DRAFT.
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT g.state || '/' || p.status FROM payroll.statutory_rule_pack p"
                              + " JOIN payroll.statutory_pack_governance g ON g.pack_id = p.id"
                              + " WHERE p.code = 'CD-SALARY-PREVIEW'",
                          String.class))
              .isEqualTo("DRAFT/DRAFT");
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM payroll.statutory_pack_governance WHERE state NOT IN"
                          + " ('DRAFT', 'LEGACY')"))
              .isZero();
          // Nothing is invented: no evidence, digest, activation period, review, case or history.
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM payroll.statutory_pack_governance WHERE state ="
                          + " 'LEGACY' AND (row_version <> 1 OR pack_sha256 IS NOT NULL"
                          + " OR fixture_sha256 IS NOT NULL OR source_authority IS NOT NULL"
                          + " OR source_reference IS NOT NULL OR source_bundle_sha256 IS NOT NULL"
                          + " OR active_from IS NOT NULL OR activated_at IS NOT NULL"
                          + " OR submitted_by IS NOT NULL)"))
              .isZero();
          assertThat(count(db, "SELECT count(*) FROM payroll.statutory_reference_case")).isZero();
          assertThat(count(db, "SELECT count(*) FROM payroll.statutory_pack_validation_run"))
              .isZero();
          assertThat(count(db, "SELECT count(*) FROM payroll.statutory_pack_review")).isZero();
          assertThat(count(db, "SELECT count(*) FROM payroll.statutory_pack_transition")).isZero();
        });
  }

  @Test
  void aLegacyVersionIsFrozenAndNeverApplicable() {
    withDatabase(
        db -> {
          migrate(db.url(), "50");
          UUID verified = legacyPack(db, "SYNTHETIC-LEGACY-VERIFIED", false);
          migrate(db.url(), "51");
          String before = db.jdbc().queryForObject(PACKS, String.class);

          // No governance change of any kind: validation, evidence, retirement, reactivation.
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "UPDATE payroll.statutory_pack_governance SET state = 'RETIRED',"
                                  + " retired_at = now(), retired_by = 'synthetic', retire_reason"
                                  + " = 'ERROR', row_version = row_version + 1, transition_actor ="
                                  + " 'synthetic', transition_at = now() WHERE pack_id = ?",
                              verified))
              .hasMessageContaining("quarantined");
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "UPDATE payroll.statutory_pack_governance SET source_authority ="
                                  + " 'Synthetic test authority', row_version = row_version + 1,"
                                  + " transition_actor = 'synthetic', transition_at = now() WHERE"
                                  + " pack_id = ?",
                              verified))
              .hasMessageContaining("quarantined");
          // The legacy status cannot move outside the governed lifecycle either.
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "UPDATE payroll.statutory_rule_pack SET status = 'RETIRED',"
                                  + " retired_at = now() WHERE id = ?",
                              verified))
              .hasMessageContaining("governed statutory pack lifecycle");
          // No case, run or review can be recorded against it.
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "INSERT INTO payroll.statutory_reference_case (pack_id, seq,"
                                  + " case_key, inputs, expected) VALUES (?, 1, 'SYNTHETIC-CASE',"
                                  + " '{}'::jsonb, '{}'::jsonb)",
                              verified))
              .hasMessageContaining("DRAFT");
          // LEGACY is never assigned to a new row.
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "INSERT INTO payroll.statutory_pack_governance (pack_id, state,"
                                  + " semantic_version) VALUES (?, 'LEGACY', '9.0.0')",
                              UUID.randomUUID()))
              .hasMessageContaining("never assigned");
          assertThat(db.jdbc().queryForObject(PACKS, String.class)).isEqualTo(before);
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT state || '/' || row_version FROM"
                              + " payroll.statutory_pack_governance WHERE pack_id = ?",
                          String.class,
                          verified))
              .isEqualTo("LEGACY/1");

          // Never the applicable ACTIVE version: no employer activation can rest on it.
          DataSource source = java.util.Objects.requireNonNull(db.jdbc().getDataSource());
          JdbcStatutoryRulePackRepository packs =
              new JdbcStatutoryRulePackRepository(JdbcClient.create(source));
          assertThat(packs.applicableActivePackId("CD", LocalDate.of(2026, 6, 1))).isEmpty();
          assertThat(packs.applicableActivePackId("CD", LocalDate.of(2090, 6, 1))).isEmpty();
        });
  }

  @Test
  void theRollbackStillRestoresV50ExactlyWhileOnlyLegacyQuarantineRowsExist() {
    withDatabase(
        db -> {
          migrate(db.url(), "50");
          legacyPack(db, "SYNTHETIC-LEGACY-VERIFIED", false);
          legacyPack(db, "SYNTHETIC-LEGACY-RETIRED", true);
          String v50 = db.jdbc().queryForObject(SIGNATURE, String.class);
          String packs = db.jdbc().queryForObject(PACKS, String.class);
          migrate(db.url(), "51");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isNotEqualTo(v50);
          script(db, rollbackScript());
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v50);
          assertThat(db.jdbc().queryForObject(PACKS, String.class)).isEqualTo(packs);
        });
  }
}
