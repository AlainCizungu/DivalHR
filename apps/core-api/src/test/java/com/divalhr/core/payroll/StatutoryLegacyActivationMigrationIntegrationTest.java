package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository;
import com.divalhr.core.support.IntegrationTest;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * MVP-097A, R202-1 (Issue #200, ADR 0040 section 7a): a database that holds a legacy VERIFIED pack
 * with an existing employer activation migrates to V51. The pack is quarantined as LEGACY without a
 * column changing, the activation row is untouched and still points to the pack, no evidence,
 * digest, period or history is fabricated, and the pack is neither the applicable ACTIVE version
 * nor a pack whose governance state is ACTIVE, so it can power no new calculation. Directly against
 * PostgreSQL in a database of its own. Synthetic data only.
 */
@IntegrationTest
class StatutoryLegacyActivationMigrationIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcConnectionDetails connection;

  private void migrate(String url, String target) {
    Flyway.configure()
        .dataSource(url, connection.getUsername(), connection.getPassword())
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private static int count(JdbcTemplate db, String sql) {
    return Objects.requireNonNull(db.queryForObject(sql, Integer.class));
  }

  @Test
  void aLegacyPackWithAnActivationMigratesAndPowersNothingNew() {
    String database = "payroll97a_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url = connection.getJdbcUrl().replaceAll("/[^/?]+(\\?.*)?$", "/" + database + "$1");
      DriverManagerDataSource source =
          new DriverManagerDataSource(url, connection.getUsername(), connection.getPassword());
      JdbcTemplate db = new JdbcTemplate(source);
      migrate(url, "50");

      // A legacy VERIFIED pack and an employer activation of it, as hr-dev holds them.
      UUID pack = StatutoryWorld.draftPack(db, StatutoryFixtures.SEED_JSON, "2020-01-01", null);
      db.update(
          "UPDATE payroll.statutory_rule_pack SET status = 'VERIFIED', reviewer_name = 'Legacy"
              + " reviewer', reviewer_organization = 'Legacy organization', reviewed_on = DATE"
              + " '2026-10-30', attested = true, verified_at = now() WHERE id = ?",
          pack);
      UUID tenant = UUID.randomUUID();
      UUID entity = UUID.randomUUID();
      db.update(
          "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone,"
              + " status, created_at, created_by) VALUES (?, 'Legacy Org', 'CD', 'fr',"
              + " 'Africa/Kinshasa', 'ACTIVE', now(), 'test')",
          tenant);
      db.update(
          "INSERT INTO tenant.legal_entity (id, tenant_id, code, name, country_code,"
              + " effective_from, created_at, created_by) VALUES (?, ?, 'LE-LEGACY', 'Legacy"
              + " Societe', 'CD', DATE '2020-01-01', now(), 'test')",
          entity,
          tenant);
      UUID activation = UUID.randomUUID();
      db.update(
          "INSERT INTO payroll.statutory_activation (tenant_id, legal_entity_id, id, sequence,"
              + " pack_id, from_month, acknowledged, activated_at, activated_by) VALUES (?, ?, ?,"
              + " 1, ?, DATE '2026-01-01', true, now(), 'legacy-subject')",
          tenant,
          entity,
          activation,
          pack);
      String packBefore =
          db.queryForObject(
              "SELECT id || '|' || status || '|' || rules_digest || '|' || reviewer_name || '|'"
                  + " || verified_at FROM payroll.statutory_rule_pack WHERE id = ?",
              String.class,
              pack);
      String activationBefore =
          db.queryForObject(
              "SELECT id || '|' || pack_id || '|' || from_month || '|' || activated_at || '|'"
                  + " || activated_by FROM payroll.statutory_activation WHERE id = ?",
              String.class,
              activation);

      // The migration that failed on hr-dev applies.
      migrate(url, "51");

      // Pack and activation are untouched; the pack is quarantined with nothing fabricated.
      assertThat(
              db.queryForObject(
                  "SELECT id || '|' || status || '|' || rules_digest || '|' || reviewer_name"
                      + " || '|' || verified_at FROM payroll.statutory_rule_pack WHERE id = ?",
                  String.class,
                  pack))
          .isEqualTo(packBefore);
      assertThat(
              db.queryForObject(
                  "SELECT id || '|' || pack_id || '|' || from_month || '|' || activated_at"
                      + " || '|' || activated_by FROM payroll.statutory_activation WHERE id = ?",
                  String.class,
                  activation))
          .isEqualTo(activationBefore);
      assertThat(
              db.queryForObject(
                  "SELECT state FROM payroll.statutory_pack_governance WHERE pack_id = ?",
                  String.class,
                  pack))
          .isEqualTo("LEGACY");
      assertThat(
              count(
                  db,
                  "SELECT count(*) FROM payroll.statutory_pack_governance WHERE state = 'LEGACY'"
                      + " AND (row_version <> 1 OR pack_sha256 IS NOT NULL OR active_from IS NOT"
                      + " NULL OR source_reference IS NOT NULL OR submitted_by IS NOT NULL)"))
          .isZero();
      assertThat(count(db, "SELECT count(*) FROM payroll.statutory_pack_transition")).isZero();
      assertThat(count(db, "SELECT count(*) FROM payroll.statutory_pack_review")).isZero();
      assertThat(count(db, "SELECT count(*) FROM payroll.statutory_pack_validation_run")).isZero();
      assertThat(count(db, "SELECT count(*) FROM payroll.statutory_reference_case")).isZero();

      // No governed ACTIVE version exists, so the activation resolves nothing for a new
      // calculation: the pack's governance state is LEGACY, never ACTIVE.
      JdbcStatutoryRulePackRepository packs =
          new JdbcStatutoryRulePackRepository(JdbcClient.create(source));
      assertThat(packs.governanceState(pack)).contains("LEGACY");
      assertThat(packs.applicableActivePackId("CD", LocalDate.of(2026, 6, 1))).isEmpty();
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }
}
