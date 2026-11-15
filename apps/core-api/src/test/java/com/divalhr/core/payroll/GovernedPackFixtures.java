package com.divalhr.core.payroll;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MVP-097A test fixtures: walks a synthetic DRAFT pack through the governed lifecycle as the
 * database owner, the one way a pack becomes ACTIVE (the legacy VERIFIED status can no longer be
 * written directly). Synthetic evidence and reviewers only; it has no legal meaning and never
 * reaches a migration, a seed or a dev script.
 */
final class GovernedPackFixtures {

  static final String SUBMITTER = "synthetic-submitter";
  static final String REVIEWER = "synthetic-reviewer";

  private GovernedPackFixtures() {}

  /**
   * DRAFT to ACTIVE over the pack's own interval, retiring any overlapping ACTIVE version first.
   */
  static void activate(JdbcTemplate jdbc, UUID id) {
    retireOverlapping(jdbc, id);
    toReviewed(jdbc, id);
    activateReviewed(jdbc, id);
  }

  /**
   * DRAFT to REVIEWED: synthetic evidence, one case, a passing run, a submission and an independent
   * approval.
   */
  static void toReviewed(JdbcTemplate jdbc, UUID id) {
    step(
        jdbc,
        "UPDATE payroll.statutory_pack_governance SET source_authority = 'Synthetic test authority',"
            + " source_title = 'Synthetic test source bundle', source_published_on = DATE '2026-10-01',"
            + " source_reference = 'https://example.test/synthetic-source',"
            + " source_bundle_sha256 = repeat('d', 64), evidence_schema_version = 1,"
            + " row_version = row_version + 1, transition_actor = ?, transition_at = now()"
            + " WHERE pack_id = ?",
        SUBMITTER,
        id);
    jdbc.update(
        "INSERT INTO payroll.statutory_reference_case (pack_id, seq, case_key, inputs, expected)"
            + " VALUES (?, 1, 'SYNTHETIC-CASE', '{}'::jsonb, '{}'::jsonb)",
        id);
    jdbc.update(
        "INSERT INTO payroll.statutory_pack_validation_run (id, pack_id, run_by, run_at, outcome,"
            + " check_count, failed_count, summary, pack_sha256, fixture_sha256, engine_version)"
            + " SELECT ?, p.id, ?, now(), 'PASSED', 1, 0,"
            + " '[{\"code\":\"PACK_PARSES\",\"passed\":true}]'::jsonb, p.rules_digest, repeat('c', 64),"
            + " 'pack-validator-1' FROM payroll.statutory_rule_pack p WHERE p.id = ?",
        UUID.randomUUID(),
        SUBMITTER,
        id);
    step(
        jdbc,
        "UPDATE payroll.statutory_pack_governance g SET state = 'VALIDATED',"
            + " pack_sha256 = p.rules_digest, fixture_sha256 = repeat('c', 64), validated_at = now(),"
            + " row_version = g.row_version + 1, transition_actor = ?, transition_at = now()"
            + " FROM payroll.statutory_rule_pack p WHERE p.id = g.pack_id AND g.pack_id = ?",
        SUBMITTER,
        id);
    step(
        jdbc,
        "UPDATE payroll.statutory_pack_governance SET submitted_by = ?, submitted_at = now(),"
            + " row_version = row_version + 1, transition_actor = ?, transition_at = now()"
            + " WHERE pack_id = ?",
        SUBMITTER,
        SUBMITTER,
        id);
    jdbc.update(
        "INSERT INTO payroll.statutory_pack_review (pack_id, decision, reviewer_subject, decided_at,"
            + " confirmed, mfa_verified) VALUES (?, 'APPROVED', ?, now(), true, true)",
        id,
        REVIEWER);
    step(
        jdbc,
        "UPDATE payroll.statutory_pack_governance SET state = 'REVIEWED',"
            + " row_version = row_version + 1, transition_actor = ?, transition_at = now()"
            + " WHERE pack_id = ?",
        REVIEWER,
        id);
  }

  /** REVIEWED to ACTIVE over the pack's own interval (the database refuses an overlap). */
  static void activateReviewed(JdbcTemplate jdbc, UUID id) {
    step(
        jdbc,
        "UPDATE payroll.statutory_pack_governance g SET state = 'ACTIVE', active_from = p.effective_from,"
            + " active_to = p.effective_to, activated_at = now(), activated_by = ?,"
            + " row_version = g.row_version + 1, transition_actor = ?, transition_at = now()"
            + " FROM payroll.statutory_rule_pack p WHERE p.id = g.pack_id AND g.pack_id = ?",
        REVIEWER,
        REVIEWER,
        id);
  }

  /** ACTIVE to RETIRED, the one legal step; the legacy status follows. */
  static void retire(JdbcTemplate jdbc, UUID id) {
    step(
        jdbc,
        "UPDATE payroll.statutory_pack_governance SET state = 'RETIRED', retired_at = now(),"
            + " retired_by = ?, retire_reason = 'SUPERSEDED', row_version = row_version + 1,"
            + " transition_actor = ?, transition_at = now() WHERE pack_id = ?",
        REVIEWER,
        REVIEWER,
        id);
  }

  /**
   * Retires every ACTIVE or SCHEDULED version of every country: a clean applicability slate for a
   * test.
   */
  static void retireAll(JdbcTemplate jdbc) {
    jdbc.queryForList(
            "SELECT pack_id FROM payroll.statutory_pack_governance"
                + " WHERE state IN ('ACTIVE', 'SCHEDULED')",
            UUID.class)
        .forEach(other -> retire(jdbc, other));
  }

  /** Retires every ACTIVE or SCHEDULED version that overlaps the interval of the given pack. */
  static void retireOverlapping(JdbcTemplate jdbc, UUID id) {
    var clashing =
        jdbc.queryForList(
            "SELECT o.pack_id FROM payroll.statutory_pack_governance o"
                + " JOIN payroll.statutory_rule_pack op ON op.id = o.pack_id"
                + " JOIN payroll.statutory_rule_pack np ON np.id = ?"
                + " WHERE o.pack_id <> np.id AND op.country = np.country"
                + " AND o.state IN ('ACTIVE', 'SCHEDULED')"
                + " AND daterange(o.active_from, o.active_to, '[]')"
                + " && daterange(np.effective_from, np.effective_to, '[]')",
            UUID.class,
            id);
    clashing.forEach(other -> retire(jdbc, other));
  }

  private static void step(JdbcTemplate jdbc, String sql, Object... args) {
    if (jdbc.update(sql, args) != 1) {
      throw new IllegalStateException("governance fixture step touched no row");
    }
  }
}
