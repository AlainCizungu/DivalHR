package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-097A, R202-1 (Issue #200, ADR 0040 sections 6 and 7a) through the public API against real
 * PostgreSQL: a pack in the governance state LEGACY (quarantined, pre-governance) never powers a
 * new statutory calculation, even when an older employer activation points to it and even when
 * another governed ACTIVE pack applies to the same country and period (nothing is substituted); the
 * applicability read never reports it; and a calculation frozen before the quarantine stays
 * readable and unchanged. The LEGACY state is reached the way the V51 backfill leaves it: the
 * governance row is reduced to the evidence-free quarantine shape with triggers bypassed (test
 * setup only; the migration itself is proved in {@code StatutoryPackLegacyMigrationIntegrationTest}
 * and {@code StatutoryLegacyActivationMigrationIntegrationTest}). Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.statutory-read=600",
      "divalhr.request-limits.subject.statutory-pack-read=600",
      "divalhr.request-limits.subject.statutory-calculation-preview=600",
      "divalhr.request-limits.subject.statutory-calculation-create=600",
      "divalhr.request-limits.subject.statutory-profile-preview=600",
      "divalhr.request-limits.subject.statutory-profile-write=600",
      "divalhr.request-limits.subject.statutory-activation-write=600",
      "divalhr.request-limits.subject.payroll-run-preview=600",
      "divalhr.request-limits.subject.payroll-run-create=600",
      "divalhr.request-limits.subject.payroll-policy-preview=600",
      "divalhr.request-limits.subject.payroll-policy-write=600",
      "divalhr.request-limits.subject.payroll-run-read=600",
      "divalhr.request-limits.subject.payroll-policy-read=600"
    })
class StatutoryLegacyPackResolutionIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private PlatformTransactionManager transactions;

  private StatutoryWorld w;

  @BeforeEach
  void world() throws Exception {
    w = new StatutoryWorld(mvc, jdbc, lookups, clock);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  /**
   * The state V51 leaves a pre-governance pack in: LEGACY, no evidence, digest, period or history.
   */
  private void quarantine(UUID pack) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL session_replication_role = replica");
              for (String table :
                  List.of(
                      "statutory_pack_transition",
                      "statutory_pack_review",
                      "statutory_pack_validation_run",
                      "statutory_reference_case")) {
                jdbc.update("DELETE FROM payroll." + table + " WHERE pack_id = ?", pack);
              }
              jdbc.update(
                  "UPDATE payroll.statutory_pack_governance SET state = 'LEGACY', row_version = 1,"
                      + " pack_sha256 = NULL, fixture_sha256 = NULL, validated_at = NULL,"
                      + " submitted_by = NULL, submitted_at = NULL, scheduled_at = NULL,"
                      + " activated_at = NULL, activated_by = NULL, active_from = NULL,"
                      + " active_to = NULL, retired_at = NULL, retired_by = NULL,"
                      + " retire_reason = NULL, source_authority = NULL, source_title = NULL,"
                      + " source_published_on = NULL, source_reference = NULL,"
                      + " source_bundle_sha256 = NULL, evidence_schema_version = NULL,"
                      + " supersedes_pack_id = NULL, transition_actor = NULL, transition_at = NULL"
                      + " WHERE pack_id = ?",
                  pack);
            });
  }

  /** Leaves the shared test database as other tests expect it: the pack is RETIRED again. */
  private void release(UUID pack) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL session_replication_role = replica");
              jdbc.update(
                  "UPDATE payroll.statutory_pack_governance SET state = 'RETIRED', row_version = 2,"
                      + " retired_at = now(), retired_by = 'synthetic-reviewer',"
                      + " retire_reason = 'SUPERSEDED', transition_actor = 'synthetic-reviewer',"
                      + " transition_at = now() WHERE pack_id = ?",
                  pack);
              jdbc.update(
                  "UPDATE payroll.statutory_rule_pack SET status = 'RETIRED', retired_at = now()"
                      + " WHERE id = ?",
                  pack);
            });
  }

  private String applicabilityPath() {
    return "/api/v1/legal-entities/"
        + w.entity
        + "/statutory-pack-applicability?payrollPeriod="
        + w.month;
  }

  private int rows(String table, UUID pack) {
    return w.c.count("SELECT count(*) FROM payroll." + table + " WHERE pack_id = ?", pack);
  }

  private void assertRefused(String run) throws Exception {
    JsonNode preview = w.preview(run);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(preview.get("pack").isNull()).isTrue();
    assertThat(preview.get("totals").isNull()).isTrue();
    w.c.problem(
        w.createRequest(run, Organizations.newKey(), StatutoryWorld.calculationCommitting(preview)),
        409,
        "STATUTORY_PACK_NO_ACTIVE_VERSION");
  }

  @Test
  void aLegacyPackNeverPowersANewCalculationAndHistoryStaysReadable() throws Exception {
    UUID legacy = StatutoryWorld.verifiedPack(jdbc);
    w.activate(legacy, w.month);
    UUID employee = w.paidInCdf("Mukendi", StatutoryWorld.GROSS);
    w.standard(employee);
    String run = w.cdfRun();
    String calculation = w.create(run).get("calculationId").asText();
    String path = w.calculationsPath(run) + "/" + calculation;
    JsonNode history = w.c.expect(PayrollRunWorld.get(w.admin, path), 200);
    int calculations = w.evidence().get(2);
    int activations = w.evidence().get(0);
    UUID other = null;
    try {
      quarantine(legacy);

      // The pack is quarantined: LEGACY, unchanged legacy status, nothing fabricated.
      assertThat(
              jdbc.queryForObject(
                  "SELECT g.state || '/' || p.status || '/' || g.row_version FROM"
                      + " payroll.statutory_pack_governance g JOIN payroll.statutory_rule_pack p"
                      + " ON p.id = g.pack_id WHERE g.pack_id = ?",
                  String.class,
                  legacy))
          .isEqualTo("LEGACY/VERIFIED/1");
      for (String table :
          List.of(
              "statutory_pack_transition",
              "statutory_pack_review",
              "statutory_pack_validation_run",
              "statutory_reference_case")) {
        assertThat(rows(table, legacy)).as(table).isZero();
      }

      // Applicability never reports LEGACY as applicable.
      JsonNode applicability = w.c.expect(PayrollRunWorld.get(w.admin, applicabilityPath()), 200);
      assertThat(applicability.path("applicable").asBoolean()).isFalse();

      // The calculation frozen before the quarantine is readable and unchanged.
      JsonNode again = w.c.expect(PayrollRunWorld.get(w.admin, path), 200);
      assertThat(again.get("calculation")).isEqualTo(history.get("calculation"));
      assertThat(again.get("lines")).isEqualTo(history.get("lines"));
      assertThat(again.get("sources")).isEqualTo(history.get("sources"));

      // The older activation still points to the LEGACY pack, which powers nothing new.
      assertRefused(run);

      // Another governed ACTIVE pack for the same country and period is never substituted.
      other = StatutoryWorld.verifiedPack(jdbc);
      assertThat(
              w.c
                  .expect(PayrollRunWorld.get(w.admin, applicabilityPath()), 200)
                  .path("applicable")
                  .asBoolean())
          .isTrue();
      assertRefused(run);

      // Nothing was written: no calculation, no activation, the history is as it was.
      assertThat(w.evidence().get(2)).isEqualTo(calculations);
      assertThat(w.evidence().get(0)).isEqualTo(activations);
      assertThat(w.c.expect(PayrollRunWorld.get(w.admin, path), 200).get("lines"))
          .isEqualTo(history.get("lines"));
    } finally {
      if (other != null) {
        GovernedPackFixtures.retire(jdbc, other);
      }
      release(legacy);
    }
  }
}
