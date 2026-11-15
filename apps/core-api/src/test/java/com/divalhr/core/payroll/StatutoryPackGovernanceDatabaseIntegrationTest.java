package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MVP-097A (Issue #200, ADR 0040): the invariants of the governed statutory pack lifecycle, proved
 * against real PostgreSQL and independent of the application: the state machine, the append-only
 * evidence, the one SCHEDULED or ACTIVE version per country and period, the independent reviewer,
 * the immutability of a RETIRED version, and the legacy status that moves only with the governance
 * row. Every refusal is proved by the constraint it names. Synthetic data only: the packs are
 * {@code SYNTHETIC-TEST-*} with intervals far in the future; the real {@code CD-SALARY-PREVIEW}
 * stays DRAFT.
 */
@IntegrationTest
class StatutoryPackGovernanceDatabaseIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  private static String constraintOf(Throwable error) {
    Throwable cause = error;
    while (cause != null && !(cause instanceof PSQLException)) {
      cause = cause.getCause();
    }
    assertThat(cause).as("a PostgreSQL error").isNotNull();
    var server = ((PSQLException) cause).getServerErrorMessage();
    return server == null ? "" : String.valueOf(server.getConstraint());
  }

  private void refused(String constraint, Runnable statement) {
    assertThatThrownBy(statement::run)
        .satisfies(error -> assertThat(constraintOf(error)).isEqualTo(constraint));
  }

  private UUID draft(String from, String to) {
    return StatutoryWorld.draftPack(jdbc, StatutoryFixtures.SEED_JSON, from, to);
  }

  private String state(UUID pack) {
    return jdbc.queryForObject(
        "SELECT state FROM payroll.statutory_pack_governance WHERE pack_id = ?",
        String.class,
        pack);
  }

  private String legacy(UUID pack) {
    return jdbc.queryForObject(
        "SELECT status FROM payroll.statutory_rule_pack WHERE id = ?", String.class, pack);
  }

  private int version(UUID pack) {
    return java.util.Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT row_version FROM payroll.statutory_pack_governance WHERE pack_id = ?",
            Integer.class,
            pack));
  }

  private void move(UUID pack, String set) {
    jdbc.update(
        "UPDATE payroll.statutory_pack_governance SET "
            + set
            + ", row_version = row_version + 1, transition_actor = 'synthetic-actor',"
            + " transition_at = now() WHERE pack_id = ?",
        pack);
  }

  @Test
  void everyNewPackVersionIsBornDraftWithGovernanceAndTheSeededRealPackStaysDraft() {
    UUID pack = draft("2085-01-01", "2085-12-31");
    assertThat(state(pack)).isEqualTo("DRAFT");
    assertThat(version(pack)).isEqualTo(1);
    assertThat(legacy(pack)).isEqualTo("DRAFT");
    List<String> real =
        jdbc.queryForList(
            "SELECT g.state || '|' || p.status || '|' || coalesce(g.source_authority, '-')"
                + " FROM payroll.statutory_pack_governance g"
                + " JOIN payroll.statutory_rule_pack p ON p.id = g.pack_id"
                + " WHERE p.code = 'CD-SALARY-PREVIEW'",
            String.class);
    assertThat(real).containsExactly("DRAFT|DRAFT|-");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.statutory_reference_case c"
                    + " JOIN payroll.statutory_rule_pack p ON p.id = c.pack_id"
                    + " WHERE p.code = 'CD-SALARY-PREVIEW'",
                Integer.class))
        .isZero();
  }

  @Test
  void theWholeLifecycleRecordsItsHistoryAndTheLegacyStatusFollowsTheGovernedState() {
    UUID pack = draft("2085-01-01", "2085-12-31");
    GovernedPackFixtures.toReviewed(jdbc, pack);
    assertThat(state(pack)).isEqualTo("REVIEWED");
    assertThat(legacy(pack)).isEqualTo("DRAFT");
    GovernedPackFixtures.retireOverlapping(jdbc, pack);
    GovernedPackFixtures.activateReviewed(jdbc, pack);
    assertThat(state(pack)).isEqualTo("ACTIVE");
    assertThat(legacy(pack)).isEqualTo("VERIFIED");
    GovernedPackFixtures.retire(jdbc, pack);
    assertThat(state(pack)).isEqualTo("RETIRED");
    assertThat(legacy(pack)).isEqualTo("RETIRED");
    assertThat(
            jdbc.queryForList(
                "SELECT kind FROM payroll.statutory_pack_transition WHERE pack_id = ? ORDER BY seq",
                String.class,
                pack))
        .containsExactly("VALIDATE", "SUBMIT", "APPROVE", "ACTIVATE", "RETIRE");
  }

  @Test
  void aTransitionThatSkipsAStateIsRefused() {
    UUID pack = draft("2085-01-01", "2085-12-31");
    refused(
        "payroll_statutory_pack_governance_transition",
        () ->
            move(
                pack,
                "state = 'ACTIVE', pack_sha256 = repeat('a', 64), fixture_sha256 = repeat('b', 64),"
                    + " validated_at = now(), submitted_by = 'x', submitted_at = now(),"
                    + " activated_at = now(), activated_by = 'x', active_from = DATE '2085-01-01'"));
    assertThat(state(pack)).isEqualTo("DRAFT");
  }

  @Test
  void aPackIsValidatedOnlyByAPassingRunBoundToItsDigests() {
    UUID pack = draft("2085-01-01", "2085-12-31");
    refused(
        "payroll_statutory_pack_governance_validation",
        () ->
            move(
                pack,
                "state = 'VALIDATED', pack_sha256 = (SELECT rules_digest FROM payroll.statutory_rule_pack"
                    + " WHERE id = pack_id), fixture_sha256 = repeat('c', 64), validated_at = now()"));
    assertThat(state(pack)).isEqualTo("DRAFT");
  }

  @Test
  void aSubmissionNeedsCompleteSourceEvidence() {
    UUID pack = draft("2085-01-01", "2085-12-31");
    jdbc.update(
        "INSERT INTO payroll.statutory_reference_case (pack_id, seq, case_key, inputs, expected)"
            + " VALUES (?, 1, 'SYNTHETIC-CASE', '{}'::jsonb, '{}'::jsonb)",
        pack);
    jdbc.update(
        "INSERT INTO payroll.statutory_pack_validation_run (id, pack_id, run_by, run_at, outcome,"
            + " check_count, failed_count, summary, pack_sha256, fixture_sha256, engine_version)"
            + " SELECT ?, p.id, 'synthetic', now(), 'PASSED', 1, 0,"
            + " '[{\"code\":\"PACK_PARSES\",\"passed\":true}]'::jsonb, p.rules_digest, repeat('c', 64),"
            + " 'pack-validator-1' FROM payroll.statutory_rule_pack p WHERE p.id = ?",
        UUID.randomUUID(),
        pack);
    move(
        pack,
        "state = 'VALIDATED', pack_sha256 = (SELECT rules_digest FROM payroll.statutory_rule_pack"
            + " WHERE id = pack_id), fixture_sha256 = repeat('c', 64), validated_at = now()");
    refused(
        "payroll_statutory_pack_governance_incomplete",
        () -> move(pack, "submitted_by = 'synthetic-submitter', submitted_at = now()"));
    assertThat(state(pack)).isEqualTo("VALIDATED");
  }

  @Test
  void theReviewerIsNeverTheSubmitterAndAReviewNeedsASubmittedVersion() {
    UUID pack = draft("2085-01-01", "2085-12-31");
    refused(
        "payroll_statutory_pack_review_state",
        () ->
            jdbc.update(
                "INSERT INTO payroll.statutory_pack_review (pack_id, decision, reviewer_subject,"
                    + " decided_at, confirmed, mfa_verified) VALUES (?, 'APPROVED', 'someone',"
                    + " now(), true, true)",
                pack));
    UUID third = draft("2087-01-01", "2087-12-31");
    // Up to the submission, then a review by the submitter itself.
    walkToSubmitted(third);
    refused(
        "payroll_statutory_pack_review_independent",
        () ->
            jdbc.update(
                "INSERT INTO payroll.statutory_pack_review (pack_id, decision, reviewer_subject,"
                    + " decided_at, confirmed, mfa_verified) VALUES (?, 'APPROVED', ?, now(),"
                    + " true, true)",
                third,
                GovernedPackFixtures.SUBMITTER));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.statutory_pack_review WHERE pack_id = ?",
                Integer.class,
                third))
        .isZero();
  }

  private void walkToSubmitted(UUID pack) {
    jdbc.update(
        "UPDATE payroll.statutory_pack_governance SET source_authority = 'Synthetic test authority',"
            + " source_title = 'Synthetic test source bundle', source_published_on = DATE '2026-10-01',"
            + " source_reference = 'https://example.test/synthetic-source',"
            + " source_bundle_sha256 = repeat('d', 64), evidence_schema_version = 1,"
            + " row_version = row_version + 1, transition_actor = 'synthetic-submitter',"
            + " transition_at = now() WHERE pack_id = ?",
        pack);
    jdbc.update(
        "INSERT INTO payroll.statutory_reference_case (pack_id, seq, case_key, inputs, expected)"
            + " VALUES (?, 1, 'SYNTHETIC-CASE', '{}'::jsonb, '{}'::jsonb)",
        pack);
    jdbc.update(
        "INSERT INTO payroll.statutory_pack_validation_run (id, pack_id, run_by, run_at, outcome,"
            + " check_count, failed_count, summary, pack_sha256, fixture_sha256, engine_version)"
            + " SELECT ?, p.id, 'synthetic', now(), 'PASSED', 1, 0,"
            + " '[{\"code\":\"PACK_PARSES\",\"passed\":true}]'::jsonb, p.rules_digest, repeat('c', 64),"
            + " 'pack-validator-1' FROM payroll.statutory_rule_pack p WHERE p.id = ?",
        UUID.randomUUID(),
        pack);
    move(
        pack,
        "state = 'VALIDATED', pack_sha256 = (SELECT rules_digest FROM payroll.statutory_rule_pack"
            + " WHERE id = pack_id), fixture_sha256 = repeat('c', 64), validated_at = now()");
    jdbc.update(
        "UPDATE payroll.statutory_pack_governance SET submitted_by = ?, submitted_at = now(),"
            + " row_version = row_version + 1, transition_actor = ?, transition_at = now()"
            + " WHERE pack_id = ?",
        GovernedPackFixtures.SUBMITTER,
        GovernedPackFixtures.SUBMITTER,
        pack);
  }

  @Test
  void evidenceIsAppendOnlyAndImmutableOnceValidated() {
    UUID pack = draft("2085-01-01", "2085-12-31");
    GovernedPackFixtures.toReviewed(jdbc, pack);
    for (String table :
        List.of(
            "statutory_reference_case",
            "statutory_pack_validation_run",
            "statutory_pack_review",
            "statutory_pack_transition")) {
      refused(
          "payroll_statutory_pack_evidence_immutable",
          () -> jdbc.update("DELETE FROM payroll." + table + " WHERE pack_id = ?", pack));
    }
    refused(
        "payroll_statutory_pack_evidence_immutable",
        () ->
            jdbc.update(
                "UPDATE payroll.statutory_pack_validation_run SET outcome = 'FAILED' WHERE pack_id = ?",
                pack));
    refused(
        "payroll_statutory_pack_governance_evidence",
        () -> move(pack, "source_title = 'A changed title'"));
    refused(
        "payroll_statutory_pack_governance_digest",
        () -> move(pack, "pack_sha256 = repeat('f', 64)"));
    assertThat(state(pack)).isEqualTo("REVIEWED");
  }

  @Test
  void referenceCasesAreAddedOnlyWhileDraft() {
    UUID pack = draft("2085-01-01", "2085-12-31");
    GovernedPackFixtures.toReviewed(jdbc, pack);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO payroll.statutory_reference_case (pack_id, seq, case_key, inputs,"
                        + " expected) VALUES (?, 2, 'LATE', '{}'::jsonb, '{}'::jsonb)",
                    pack))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void oneScheduledOrActiveVersionPerCountryAndPeriod() {
    GovernedPackFixtures.retireAll(jdbc);
    UUID first = draft("2088-01-01", "2088-12-31");
    UUID second = draft("2088-06-01", "2089-06-30");
    UUID disjoint = draft("2089-07-01", "2089-12-31");
    GovernedPackFixtures.toReviewed(jdbc, first);
    GovernedPackFixtures.activateReviewed(jdbc, first);
    GovernedPackFixtures.toReviewed(jdbc, second);
    refused(
        "payroll_statutory_pack_period_overlap",
        () -> GovernedPackFixtures.activateReviewed(jdbc, second));
    assertThat(state(second)).isEqualTo("REVIEWED");
    GovernedPackFixtures.toReviewed(jdbc, disjoint);
    GovernedPackFixtures.activateReviewed(jdbc, disjoint);
    assertThat(state(disjoint)).isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.statutory_pack_governance g"
                    + " JOIN payroll.statutory_rule_pack p ON p.id = g.pack_id"
                    + " WHERE g.state = 'ACTIVE' AND p.country = 'CD'"
                    + " AND daterange(g.active_from, g.active_to, '[]') @> DATE '2088-07-01'",
                Integer.class))
        .isEqualTo(1);
    GovernedPackFixtures.retire(jdbc, first);
    GovernedPackFixtures.retire(jdbc, disjoint);
  }

  @Test
  void aRetiredVersionIsReadableForeverAndNeverChangedOrReactivated() {
    UUID pack = draft("2085-01-01", "2085-12-31");
    GovernedPackFixtures.activate(jdbc, pack);
    GovernedPackFixtures.retire(jdbc, pack);
    refused(
        "payroll_statutory_pack_governance_retired",
        () ->
            move(
                pack,
                "state = 'ACTIVE', activated_at = now(), activated_by = 'x', retired_at = NULL,"
                    + " retired_by = NULL, retire_reason = NULL"));
    refused(
        "payroll_statutory_pack_governance_retired", () -> move(pack, "retire_reason = 'ERROR'"));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM payroll.statutory_pack_governance WHERE pack_id = ?", pack))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(state(pack)).isEqualTo("RETIRED");
  }

  @Test
  void theLegacyStatusCannotBeWrittenDirectly() {
    UUID pack = draft("2085-01-01", "2085-12-31");
    refused(
        "payroll_statutory_rule_pack_governed",
        () ->
            jdbc.update(
                "UPDATE payroll.statutory_rule_pack SET status = 'VERIFIED',"
                    + " reviewer_name = 'Synthetic Test Reviewer', reviewer_organization = 'Test Only',"
                    + " reviewed_on = DATE '2026-10-01', attested = true, verified_at = now()"
                    + " WHERE id = ?",
                pack));
    assertThat(legacy(pack)).isEqualTo("DRAFT");
    assertThat(state(pack)).isEqualTo("DRAFT");
  }

  @Test
  void aCodeHasAtMostTwentyFiveVersions() {
    String code =
        "SYNTHETIC-TEST-LIMIT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    for (int version = 1; version <= 25; version++) {
      jdbc.update(
          "INSERT INTO payroll.statutory_rule_pack (id, code, version, country, currency, status,"
              + " effective_from, effective_to, method_version, rules, created_at) VALUES (?, ?, ?,"
              + " 'CD', 'CDF', 'DRAFT', DATE '2084-01-01', NULL, '1', CAST(? AS jsonb), now())",
          UUID.randomUUID(),
          code,
          version,
          StatutoryFixtures.SEED_JSON);
    }
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO payroll.statutory_rule_pack (id, code, version, country, currency,"
                        + " status, effective_from, effective_to, method_version, rules, created_at)"
                        + " VALUES (?, ?, 26, 'CD', 'CDF', 'DRAFT', DATE '2084-01-01', NULL, '1',"
                        + " CAST(? AS jsonb), now())",
                    UUID.randomUUID(),
                    code,
                    StatutoryFixtures.SEED_JSON))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
