package com.divalhr.core.payroll.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage of the governed lifecycle of platform-owned statutory packs (MVP-097A; V51, ADR 0040).
 * Like the rule pack repository it serves platform data: the tables have no tenant column, no
 * operation takes a {@code TenantId} and no statement names a tenant table. The database guard
 * enforces the closed lifecycle, the append-only evidence and history, the independent review and
 * the single applicable version; this class never decides them, it only reports a refusal.
 *
 * <p>No statement logs, selects or returns a rate, a formula or a reference-case value except
 * {@link #rules} and {@link #cases}, which feed the offline validator and are never rendered.
 */
@Repository
public class JdbcStatutoryPackGovernanceRepository {

  private static final String VERSION_COLUMNS =
      "p.id, p.code, p.version, p.country, p.currency, p.effective_from, p.effective_to,"
          + " p.rules_digest, g.state, g.row_version, g.semantic_version, g.applicability,"
          + " g.source_authority, g.source_title, g.source_published_on, g.source_reference,"
          + " g.source_bundle_sha256, g.evidence_schema_version, g.pack_sha256, g.fixture_sha256,"
          + " g.validated_at, g.submitted_by, g.submitted_at, g.scheduled_at, g.activated_at,"
          + " g.active_from, g.active_to, g.retired_at, g.retire_reason, g.supersedes_pack_id,"
          + " (SELECT count(*) FROM payroll.statutory_rule_pack_source s WHERE s.pack_id = p.id)"
          + " AS source_count,"
          + " (SELECT count(*) FROM payroll.statutory_reference_case c WHERE c.pack_id = p.id)"
          + " AS case_count,"
          + " (SELECT r.outcome FROM payroll.statutory_pack_validation_run r WHERE r.pack_id = p.id"
          + " ORDER BY r.run_at DESC, r.id DESC LIMIT 1) AS last_validation";

  private static final String VERSION_FROM =
      " FROM payroll.statutory_rule_pack p"
          + " JOIN payroll.statutory_pack_governance g ON g.pack_id = p.id";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcStatutoryPackGovernanceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One governed pack version.
   *
   * @param id pack row
   * @param code pack code
   * @param version integer version
   * @param country country code
   * @param currency currency code
   * @param effectiveFrom first day of the pack interval
   * @param effectiveTo last day of the pack interval, or {@code null}
   * @param rulesDigest SHA-256 of the rules
   * @param state governed lifecycle state
   * @param rowVersion optimistic version of the governance row
   * @param semanticVersion semantic version text
   * @param applicability applicability key
   * @param sourceAuthority source authority name, or {@code null}
   * @param sourceTitle source document title, or {@code null}
   * @param sourcePublishedOn source publication date, or {@code null}
   * @param sourceReference source URL or reference id (informational), or {@code null}
   * @param sourceBundleSha256 digest of the archived source bundle, or {@code null}
   * @param evidenceSchemaVersion evidence schema version, or {@code null}
   * @param packSha256 pack digest recorded at validation, or {@code null}
   * @param fixtureSha256 fixture digest recorded at validation, or {@code null}
   * @param validatedAt validation time, or {@code null}
   * @param submittedBy submitter subject (restricted), or {@code null}
   * @param submittedAt submission time, or {@code null}
   * @param scheduledAt scheduling time, or {@code null}
   * @param activatedAt activation time, or {@code null}
   * @param activeFrom first day of applicability, or {@code null}
   * @param activeTo last day of applicability, or {@code null}
   * @param retiredAt retirement time, or {@code null}
   * @param retireReason retirement reason, or {@code null}
   * @param supersedesPackId rejected version this one corrects, or {@code null}
   * @param sourceCount number of declared sources
   * @param caseCount number of reference cases
   * @param lastValidation outcome of the latest validation run, or {@code null}
   */
  public record VersionRow(
      UUID id,
      String code,
      int version,
      String country,
      String currency,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String rulesDigest,
      String state,
      int rowVersion,
      String semanticVersion,
      String applicability,
      String sourceAuthority,
      String sourceTitle,
      LocalDate sourcePublishedOn,
      String sourceReference,
      String sourceBundleSha256,
      Integer evidenceSchemaVersion,
      String packSha256,
      String fixtureSha256,
      Instant validatedAt,
      String submittedBy,
      Instant submittedAt,
      Instant scheduledAt,
      Instant activatedAt,
      LocalDate activeFrom,
      LocalDate activeTo,
      Instant retiredAt,
      String retireReason,
      UUID supersedesPackId,
      int sourceCount,
      int caseCount,
      String lastValidation) {

    @Override
    public String toString() {
      return "StatutoryPackVersion[" + id + "]";
    }

    /**
     * Whether the source evidence is complete.
     *
     * @return true when every evidence member is present
     */
    public boolean evidenceComplete() {
      return sourceAuthority != null
          && sourceTitle != null
          && sourcePublishedOn != null
          && sourceReference != null
          && sourceBundleSha256 != null
          && evidenceSchemaVersion != null
          && evidenceSchemaVersion == 1;
    }
  }

  /**
   * A reference case as stored.
   *
   * @param key case key
   * @param inputs inputs document
   * @param expected expected results document
   */
  public record CaseRow(String key, String inputs, String expected) {

    @Override
    public String toString() {
      return "StatutoryReferenceCase[" + key + "]";
    }
  }

  /**
   * A validation run.
   *
   * @param id run
   * @param runBy subject (restricted)
   * @param runAt time
   * @param outcome PASSED or FAILED
   * @param checkCount checks
   * @param failedCount failed checks
   * @param summary bounded JSON array of check codes and outcomes
   * @param packSha256 pack digest
   * @param fixtureSha256 fixture digest
   * @param engineVersion validator version
   */
  public record RunRow(
      UUID id,
      String runBy,
      Instant runAt,
      String outcome,
      int checkCount,
      int failedCount,
      String summary,
      String packSha256,
      String fixtureSha256,
      String engineVersion) {}

  /**
   * The review decision.
   *
   * @param decision APPROVED or REJECTED
   * @param reviewerSubject subject (restricted)
   * @param decidedAt time
   * @param reasonCode bounded rejection reason, or {@code null}
   */
  public record ReviewRow(
      String decision, String reviewerSubject, Instant decidedAt, String reasonCode) {}

  /**
   * One lifecycle history entry.
   *
   * @param seq position
   * @param kind VALIDATE, SUBMIT, APPROVE, REJECT, SCHEDULE, ACTIVATE or RETIRE
   * @param fromState previous state
   * @param toState new state
   * @param actor subject (restricted)
   * @param occurredAt time
   */
  public record TransitionRow(
      int seq, String kind, String fromState, String toState, String actor, Instant occurredAt) {}

  /**
   * Where a list page continues after.
   *
   * @param code last code
   * @param version last version
   */
  public record Position(String code, int version) {}

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * Bounds one read or command transaction.
   *
   * @param timeout statement and lock timeout
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void limits(java.time.Duration timeout) {
    long millis = Math.max(1, timeout.toMillis());
    // Values are server-owned integers; SET does not accept bind parameters.
    jdbc.sql("SET LOCAL statement_timeout = " + millis).update();
    jdbc.sql("SET LOCAL lock_timeout = " + millis).update();
  }

  /**
   * How many versions a pack code has.
   *
   * @param code pack code
   * @return the count
   */
  public int versionCount(String code) {
    Integer count =
        jdbc.sql("SELECT count(*)::int FROM payroll.statutory_rule_pack WHERE code = :code")
            .param("code", code)
            .query(Integer.class)
            .single();
    return count;
  }

  /**
   * One version by its pack row.
   *
   * @param packId pack row
   * @return the version, if it exists
   */
  public Optional<VersionRow> findById(UUID packId) {
    return jdbc.sql("SELECT " + VERSION_COLUMNS + VERSION_FROM + " WHERE p.id = :pack")
        .param("pack", packId)
        .query(JdbcStatutoryPackGovernanceRepository::version)
        .optional();
  }

  /**
   * A page of versions ordered by code then version descending.
   *
   * @param after the last row of the previous page, or {@code null}
   * @param limit rows to read
   * @return the versions
   */
  public List<VersionRow> page(Position after, int limit) {
    if (after == null) {
      return jdbc.sql(
              "SELECT "
                  + VERSION_COLUMNS
                  + VERSION_FROM
                  + " ORDER BY p.code, p.version DESC LIMIT :limit")
          .param("limit", limit)
          .query(JdbcStatutoryPackGovernanceRepository::version)
          .list();
    }
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + VERSION_FROM
                + " WHERE (p.code > :code OR (p.code = :code AND p.version < :version))"
                + " ORDER BY p.code, p.version DESC LIMIT :limit")
        .param("code", after.code())
        .param("version", after.version())
        .param("limit", limit)
        .query(JdbcStatutoryPackGovernanceRepository::version)
        .list();
  }

  /**
   * One version by its pack code and integer version.
   *
   * @param code pack code
   * @param version integer version
   * @return the version, if it exists
   */
  public Optional<VersionRow> find(String code, int version) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + VERSION_FROM
                + " WHERE p.code = :code AND p.version = :version")
        .param("code", code)
        .param("version", version)
        .query(JdbcStatutoryPackGovernanceRepository::version)
        .optional();
  }

  /**
   * One version by its pack row, locked for the rest of the transaction.
   *
   * @param packId pack row
   * @return the version, if it exists
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<VersionRow> lock(UUID packId) {
    jdbc.sql("SELECT 1 FROM payroll.statutory_pack_governance WHERE pack_id = :pack FOR UPDATE")
        .param("pack", packId)
        .query(Integer.class)
        .optional();
    return jdbc.sql("SELECT " + VERSION_COLUMNS + VERSION_FROM + " WHERE p.id = :pack")
        .param("pack", packId)
        .query(JdbcStatutoryPackGovernanceRepository::version)
        .optional();
  }

  /**
   * The applicable ACTIVE version for a country on a date (public provenance for tenants).
   *
   * @param country country code
   * @param date payroll date
   * @return the version, if any
   */
  public Optional<VersionRow> applicable(String country, LocalDate date) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + VERSION_FROM
                + " WHERE p.country = :country AND g.state = 'ACTIVE'"
                + " AND g.active_from <= :date AND (g.active_to IS NULL OR g.active_to >= :date)")
        .param("country", country)
        .param("date", date)
        .query(JdbcStatutoryPackGovernanceRepository::version)
        .optional();
  }

  /**
   * The rules document of a version, for the offline validator only.
   *
   * @param packId pack row
   * @return the rules text
   */
  public Optional<String> rules(UUID packId) {
    return jdbc.sql("SELECT rules::text FROM payroll.statutory_rule_pack WHERE id = :pack")
        .param("pack", packId)
        .query(String.class)
        .optional();
  }

  /**
   * The declared source keys of a version.
   *
   * @param packId pack row
   * @return the keys
   */
  public List<String> sourceKeys(UUID packId) {
    return jdbc.sql(
            "SELECT source_key FROM payroll.statutory_rule_pack_source WHERE pack_id = :pack"
                + " ORDER BY seq")
        .param("pack", packId)
        .query(String.class)
        .list();
  }

  /**
   * The reference cases of a version, in order.
   *
   * @param packId pack row
   * @return the cases
   */
  public List<CaseRow> cases(UUID packId) {
    return jdbc.sql(
            "SELECT case_key, inputs::text AS inputs, expected::text AS expected"
                + " FROM payroll.statutory_reference_case WHERE pack_id = :pack ORDER BY seq")
        .param("pack", packId)
        .query(
            (rs, row) ->
                new CaseRow(
                    rs.getString("case_key"), rs.getString("inputs"), rs.getString("expected")))
        .list();
  }

  /**
   * The latest validation runs of a version, newest first.
   *
   * @param packId pack row
   * @param limit rows
   * @return the runs
   */
  public List<RunRow> runs(UUID packId, int limit) {
    return jdbc.sql(
            "SELECT id, run_by, run_at, outcome, check_count, failed_count, summary::text AS"
                + " summary, pack_sha256, fixture_sha256, engine_version"
                + " FROM payroll.statutory_pack_validation_run WHERE pack_id = :pack"
                + " ORDER BY run_at DESC, id DESC LIMIT :limit")
        .param("pack", packId)
        .param("limit", limit)
        .query(
            (rs, row) ->
                new RunRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("run_by"),
                    rs.getTimestamp("run_at").toInstant(),
                    rs.getString("outcome"),
                    rs.getInt("check_count"),
                    rs.getInt("failed_count"),
                    rs.getString("summary"),
                    rs.getString("pack_sha256").trim(),
                    rs.getString("fixture_sha256").trim(),
                    rs.getString("engine_version")))
        .list();
  }

  /**
   * The review decision of a version.
   *
   * @param packId pack row
   * @return the decision, if one was recorded
   */
  public Optional<ReviewRow> review(UUID packId) {
    return jdbc.sql(
            "SELECT decision, reviewer_subject, decided_at, reason_code"
                + " FROM payroll.statutory_pack_review WHERE pack_id = :pack")
        .param("pack", packId)
        .query(
            (rs, row) ->
                new ReviewRow(
                    rs.getString("decision"),
                    rs.getString("reviewer_subject"),
                    rs.getTimestamp("decided_at").toInstant(),
                    rs.getString("reason_code")))
        .optional();
  }

  /**
   * The lifecycle history of a version, oldest first.
   *
   * @param packId pack row
   * @return the entries
   */
  public List<TransitionRow> transitions(UUID packId) {
    return jdbc.sql(
            "SELECT seq, kind, from_state, to_state, actor, occurred_at"
                + " FROM payroll.statutory_pack_transition WHERE pack_id = :pack ORDER BY seq")
        .param("pack", packId)
        .query(
            (rs, row) ->
                new TransitionRow(
                    rs.getInt("seq"),
                    rs.getString("kind"),
                    rs.getString("from_state"),
                    rs.getString("to_state"),
                    rs.getString("actor"),
                    rs.getTimestamp("occurred_at").toInstant()))
        .list();
  }

  /**
   * Validation runs recorded since an instant, by one subject.
   *
   * @param subject platform administrator subject
   * @param since start of the window
   * @return the count
   */
  public int runsBySubjectSince(String subject, Instant since) {
    Integer count =
        jdbc.sql(
                "SELECT count(*)::int FROM payroll.statutory_pack_validation_run"
                    + " WHERE run_by = :subject AND run_at >= :since")
            .param("subject", subject)
            .param("since", Timestamp.from(since))
            .query(Integer.class)
            .single();
    return count;
  }

  /**
   * Validation runs recorded since an instant, on the whole platform.
   *
   * @param since start of the window
   * @return the count
   */
  public int runsSince(Instant since) {
    Integer count =
        jdbc.sql(
                "SELECT count(*)::int FROM payroll.statutory_pack_validation_run"
                    + " WHERE run_at >= :since")
            .param("since", Timestamp.from(since))
            .query(Integer.class)
            .single();
    return count;
  }

  /**
   * Review and activation commands recorded since an instant, by one subject.
   *
   * @param subject platform administrator subject
   * @param since start of the window
   * @return the count
   */
  public int commandsBySubjectSince(String subject, Instant since) {
    Integer count =
        jdbc.sql(
                "SELECT count(*)::int FROM payroll.statutory_pack_transition"
                    + " WHERE actor = :subject AND occurred_at >= :since AND kind <> 'VALIDATE'")
            .param("subject", subject)
            .param("since", Timestamp.from(since))
            .query(Integer.class)
            .single();
    return count;
  }

  // ------------------------------------------------------------------------------------------
  // Writes (inside the caller's transaction; the database guard decides legality)
  // ------------------------------------------------------------------------------------------

  /**
   * Appends a validation run.
   *
   * @param id run
   * @param packId pack row
   * @param runBy subject
   * @param runAt time
   * @param outcome PASSED or FAILED
   * @param checkCount checks
   * @param failedCount failed checks
   * @param summaryJson bounded JSON array
   * @param packSha256 pack digest
   * @param fixtureSha256 fixture digest
   * @param engineVersion validator version
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertRun(
      UUID id,
      UUID packId,
      String runBy,
      Instant runAt,
      String outcome,
      int checkCount,
      int failedCount,
      String summaryJson,
      String packSha256,
      String fixtureSha256,
      String engineVersion) {
    jdbc.sql(
            "INSERT INTO payroll.statutory_pack_validation_run (id, pack_id, run_by, run_at,"
                + " outcome, check_count, failed_count, summary, pack_sha256, fixture_sha256,"
                + " engine_version) VALUES (:id, :pack, :by, :at, :outcome, :checks, :failed,"
                + " CAST(:summary AS jsonb), :pack_sha, :fixture_sha, :engine)")
        .param("id", id)
        .param("pack", packId)
        .param("by", runBy)
        .param("at", Timestamp.from(runAt))
        .param("outcome", outcome)
        .param("checks", checkCount)
        .param("failed", failedCount)
        .param("summary", summaryJson)
        .param("pack_sha", packSha256)
        .param("fixture_sha", fixtureSha256)
        .param("engine", engineVersion)
        .update();
  }

  /**
   * DRAFT to VALIDATED with the digests.
   *
   * @param packId pack row
   * @param expectedVersion row version the caller read
   * @param packSha256 pack digest
   * @param fixtureSha256 fixture digest
   * @param actor subject
   * @param at time
   * @return whether the expected version matched and the row changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean markValidated(
      UUID packId,
      int expectedVersion,
      String packSha256,
      String fixtureSha256,
      String actor,
      Instant at) {
    return transition(
        packId,
        expectedVersion,
        "state = 'VALIDATED', pack_sha256 = :pack_sha, fixture_sha256 = :fixture_sha,"
            + " validated_at = :at",
        actor,
        at,
        new String[] {"pack_sha", "fixture_sha"},
        new Object[] {packSha256, fixtureSha256});
  }

  /**
   * Records the submission for independent review (the state stays VALIDATED).
   *
   * @param packId pack row
   * @param expectedVersion row version the caller read
   * @param actor submitter
   * @param at time
   * @return whether the row changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean markSubmitted(UUID packId, int expectedVersion, String actor, Instant at) {
    return transition(
        packId,
        expectedVersion,
        "submitted_by = :actor, submitted_at = :at",
        actor,
        at,
        new String[0],
        new Object[0]);
  }

  /**
   * Appends the review decision.
   *
   * @param packId pack row
   * @param decision APPROVED or REJECTED
   * @param reviewer reviewer subject
   * @param at time
   * @param reasonCode bounded reason (rejection only)
   * @param note bounded internal note, or {@code null}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertReview(
      UUID packId, String decision, String reviewer, Instant at, String reasonCode, String note) {
    jdbc.sql(
            "INSERT INTO payroll.statutory_pack_review (pack_id, decision, reviewer_subject,"
                + " decided_at, reason_code, internal_note, confirmed, mfa_verified)"
                + " VALUES (:pack, :decision, :reviewer, :at, :reason, :note, true, true)")
        .param("pack", packId)
        .param("decision", decision)
        .param("reviewer", reviewer)
        .param("at", Timestamp.from(at))
        .param("reason", reasonCode)
        .param("note", note)
        .update();
  }

  /**
   * VALIDATED to REVIEWED.
   *
   * @param packId pack row
   * @param expectedVersion row version the caller read
   * @param actor reviewer
   * @param at time
   * @return whether the row changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean markReviewed(UUID packId, int expectedVersion, String actor, Instant at) {
    return transition(
        packId, expectedVersion, "state = 'REVIEWED'", actor, at, new String[0], new Object[0]);
  }

  /**
   * VALIDATED to RETIRED(REJECTED).
   *
   * @param packId pack row
   * @param expectedVersion row version the caller read
   * @param actor reviewer
   * @param at time
   * @return whether the row changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean markRejected(UUID packId, int expectedVersion, String actor, Instant at) {
    return transition(
        packId,
        expectedVersion,
        "state = 'RETIRED', retired_at = :at, retired_by = :actor, retire_reason = 'REJECTED'",
        actor,
        at,
        new String[0],
        new Object[0]);
  }

  /**
   * REVIEWED to SCHEDULED.
   *
   * @param packId pack row
   * @param expectedVersion row version the caller read
   * @param activeFrom future effective date
   * @param activeTo end of the pack interval, or {@code null}
   * @param actor administrator
   * @param at time
   * @return whether the row changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean markScheduled(
      UUID packId,
      int expectedVersion,
      LocalDate activeFrom,
      LocalDate activeTo,
      String actor,
      Instant at) {
    return transition(
        packId,
        expectedVersion,
        "state = 'SCHEDULED', scheduled_at = :at, active_from = :from, active_to = :to",
        actor,
        at,
        new String[] {"from", "to"},
        new Object[] {activeFrom, activeTo});
  }

  /**
   * REVIEWED or SCHEDULED to ACTIVE.
   *
   * @param packId pack row
   * @param expectedVersion row version the caller read
   * @param activeFrom first day of applicability (kept when already scheduled)
   * @param activeTo end of the pack interval, or {@code null}
   * @param actor administrator
   * @param at time
   * @return whether the row changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean markActive(
      UUID packId,
      int expectedVersion,
      LocalDate activeFrom,
      LocalDate activeTo,
      String actor,
      Instant at) {
    return transition(
        packId,
        expectedVersion,
        "state = 'ACTIVE', activated_at = :at, activated_by = :actor, active_from = :from,"
            + " active_to = :to",
        actor,
        at,
        new String[] {"from", "to"},
        new Object[] {activeFrom, activeTo});
  }

  /**
   * REVIEWED, SCHEDULED or ACTIVE to RETIRED.
   *
   * @param packId pack row
   * @param expectedVersion row version the caller read
   * @param reason closed retirement reason
   * @param actor administrator
   * @param at time
   * @return whether the row changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean markRetired(
      UUID packId, int expectedVersion, String reason, String actor, Instant at) {
    return transition(
        packId,
        expectedVersion,
        "state = 'RETIRED', retired_at = :at, retired_by = :actor, retire_reason = :reason",
        actor,
        at,
        new String[] {"reason"},
        new Object[] {reason});
  }

  /**
   * Creates the next DRAFT version of a rejected one: the same rules, sources and reference cases
   * under the next integer version, recorded as superseding the rejected one.
   *
   * @param rejectedPackId the rejected pack row
   * @param newPackId the new pack row
   * @param at creation time
   * @return the new integer version
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int createRevision(UUID rejectedPackId, UUID newPackId, Instant at) {
    Integer next =
        jdbc.sql(
                "SELECT (max(v.version) + 1)::int FROM payroll.statutory_rule_pack v"
                    + " WHERE v.code = (SELECT code FROM payroll.statutory_rule_pack"
                    + " WHERE id = :old)")
            .param("old", rejectedPackId)
            .query(Integer.class)
            .single();
    jdbc.sql(
            "INSERT INTO payroll.statutory_rule_pack (id, code, version, country, currency,"
                + " status, effective_from, effective_to, method_version, rules, created_at)"
                + " SELECT :new, code, :version, country, currency, 'DRAFT', effective_from,"
                + " effective_to, method_version, rules, :at"
                + " FROM payroll.statutory_rule_pack WHERE id = :old")
        .param("new", newPackId)
        .param("version", next)
        .param("at", Timestamp.from(at))
        .param("old", rejectedPackId)
        .update();
    jdbc.sql(
            "INSERT INTO payroll.statutory_rule_pack_source (pack_id, seq, source_key, title, url,"
                + " sha256, retrieved_on) SELECT :new, seq, source_key, title, url, sha256,"
                + " retrieved_on FROM payroll.statutory_rule_pack_source WHERE pack_id = :old")
        .param("new", newPackId)
        .param("old", rejectedPackId)
        .update();
    jdbc.sql(
            "INSERT INTO payroll.statutory_reference_case (pack_id, seq, case_key, inputs,"
                + " expected) SELECT :new, seq, case_key, inputs, expected"
                + " FROM payroll.statutory_reference_case WHERE pack_id = :old")
        .param("new", newPackId)
        .param("old", rejectedPackId)
        .update();
    return next;
  }

  /**
   * Links a new DRAFT revision to the version it corrects and copies the source evidence.
   *
   * @param newPackId the new pack row
   * @param rejectedPackId the rejected pack row
   * @param actor subject
   * @param at time
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void linkRevision(UUID newPackId, UUID rejectedPackId, String actor, Instant at) {
    jdbc.sql(
            "UPDATE payroll.statutory_pack_governance n SET row_version = n.row_version + 1,"
                + " supersedes_pack_id = :old,"
                + " source_authority = o.source_authority, source_title = o.source_title,"
                + " source_published_on = o.source_published_on,"
                + " source_reference = o.source_reference,"
                + " source_bundle_sha256 = o.source_bundle_sha256,"
                + " evidence_schema_version = o.evidence_schema_version,"
                + " transition_actor = :actor, transition_at = :at"
                + " FROM payroll.statutory_pack_governance o"
                + " WHERE n.pack_id = :new AND o.pack_id = :old")
        .param("new", newPackId)
        .param("old", rejectedPackId)
        .param("actor", actor)
        .param("at", Timestamp.from(at))
        .update();
  }

  private boolean transition(
      UUID packId,
      int expectedVersion,
      String assignments,
      String actor,
      Instant at,
      String[] names,
      Object[] values) {
    JdbcClient.StatementSpec statement =
        jdbc.sql(
                "UPDATE payroll.statutory_pack_governance SET row_version = row_version + 1, "
                    + assignments
                    + ", transition_actor = :actor, transition_at = :at"
                    + " WHERE pack_id = :pack AND row_version = :expected")
            .param("pack", packId)
            .param("expected", expectedVersion)
            .param("actor", actor)
            .param("at", Timestamp.from(at));
    for (int i = 0; i < names.length; i++) {
      statement = statement.param(names[i], values[i]);
    }
    return statement.update() == 1;
  }

  private static VersionRow version(ResultSet rs, int row) throws SQLException {
    return new VersionRow(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getInt("version"),
        rs.getString("country"),
        rs.getString("currency"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getObject("effective_to", LocalDate.class),
        trim(rs.getString("rules_digest")),
        rs.getString("state"),
        rs.getInt("row_version"),
        rs.getString("semantic_version"),
        rs.getString("applicability"),
        rs.getString("source_authority"),
        rs.getString("source_title"),
        rs.getObject("source_published_on", LocalDate.class),
        rs.getString("source_reference"),
        trim(rs.getString("source_bundle_sha256")),
        (Integer) rs.getObject("evidence_schema_version"),
        trim(rs.getString("pack_sha256")),
        trim(rs.getString("fixture_sha256")),
        instant(rs.getTimestamp("validated_at")),
        rs.getString("submitted_by"),
        instant(rs.getTimestamp("submitted_at")),
        instant(rs.getTimestamp("scheduled_at")),
        instant(rs.getTimestamp("activated_at")),
        rs.getObject("active_from", LocalDate.class),
        rs.getObject("active_to", LocalDate.class),
        instant(rs.getTimestamp("retired_at")),
        rs.getString("retire_reason"),
        rs.getObject("supersedes_pack_id", UUID.class),
        rs.getInt("source_count"),
        rs.getInt("case_count"),
        rs.getString("last_validation"));
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
