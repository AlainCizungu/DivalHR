package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.Checklist;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.Rule;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Policy versions, rules, checklists and history (LAUNCH-001). Policies are platform-owned: they
 * belong to no tenant. Every statement is parameterized. The database enforces the lifecycle, the
 * immutability of approved versions and the one-active-version-per-country rule; this store only
 * reads and writes.
 */
@Repository
public class PrivacyPolicyStore {

  /** A policy header. */
  public record PolicyRow(
      UUID id,
      String reference,
      String countryCode,
      String state,
      int version,
      String wordingVersion,
      String sourceReference,
      LocalDate reviewDate,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      Instant createdAt,
      Instant updatedAt) {}

  /** A history event. */
  public record EventRow(int sequence, String fromState, String toState, Instant occurredAt) {}

  /** The keyset position of a list. */
  public record Position(Instant createdAt, UUID id) {}

  private static final String SELECT =
      "SELECT id, reference, country_code, state, row_version, wording_version, source_reference,"
          + " review_date, effective_from, effective_to, created_at, updated_at"
          + " FROM integrations.privacy_policy";

  private final JdbcClient jdbc;

  /**
   * Creates the store.
   *
   * @param jdbc JDBC client
   */
  public PrivacyPolicyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static PolicyRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new PolicyRow(
        rs.getObject("id", UUID.class),
        rs.getString("reference"),
        rs.getString("country_code").trim(),
        rs.getString("state"),
        rs.getInt("row_version"),
        rs.getString("wording_version"),
        rs.getString("source_reference"),
        rs.getObject("review_date", LocalDate.class),
        rs.getObject("effective_from", LocalDate.class),
        rs.getObject("effective_to", LocalDate.class),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  /** One policy by reference. */
  public Optional<PolicyRow> byReference(String reference) {
    return jdbc.sql(SELECT + " WHERE reference = :reference")
        .param("reference", reference)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /** One policy by reference, locked for update. */
  public Optional<PolicyRow> lockByReference(String reference) {
    return jdbc.sql(SELECT + " WHERE reference = :reference FOR UPDATE")
        .param("reference", reference)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /** One policy by id (cursor resolution). */
  public Optional<PolicyRow> byId(UUID id) {
    return jdbc.sql(SELECT + " WHERE id = :id")
        .param("id", id)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /** A page, newest first. */
  public List<PolicyRow> page(Position after, int limit) {
    return jdbc.sql(
            SELECT
                + " WHERE (CAST(:at AS timestamptz) IS NULL OR (created_at, id) <"
                + " (CAST(:at AS timestamptz), CAST(:id AS uuid)))"
                + " ORDER BY created_at DESC, id DESC LIMIT :limit")
        .param("at", after == null ? null : Timestamp.from(after.createdAt()))
        .param("id", after == null ? null : after.id())
        .param("limit", limit)
        .query((rs, n) -> row(rs))
        .list();
  }

  /** Inserts a DRAFT header at version 1. */
  public void insert(
      UUID id,
      String reference,
      String country,
      String wording,
      String source,
      LocalDate reviewDate,
      LocalDate from,
      LocalDate to,
      String createdBy,
      Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_policy (id, reference, country_code, state,"
                + " row_version, wording_version, source_reference, review_date, effective_from,"
                + " effective_to, created_by, created_at, updated_at) VALUES (:id, :reference,"
                + " :country, 'DRAFT', 1, :wording, :source, :review, :from, :to, :by, :now, :now)")
        .param("id", id)
        .param("reference", reference)
        .param("country", country)
        .param("wording", wording)
        .param("source", source)
        .param("review", reviewDate)
        .param("from", from)
        .param("to", to)
        .param("by", createdBy)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Inserts one rule. */
  public void insertRule(UUID policy, Rule rule) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_policy_rule (policy_id, data_class, purpose, action,"
                + " trigger_event, duration_days) VALUES (:policy, :class, :purpose, :action,"
                + " :trigger, :days)")
        .param("policy", policy)
        .param("class", rule.dataClass())
        .param("purpose", rule.purpose())
        .param("action", rule.action())
        .param("trigger", rule.trigger())
        .param("days", rule.durationDays())
        .update();
  }

  /** The four rules, in class order. */
  public List<Rule> rules(UUID policy) {
    return jdbc.sql(
            "SELECT data_class, purpose, action, trigger_event, duration_days"
                + " FROM integrations.privacy_policy_rule WHERE policy_id = :policy"
                + " ORDER BY data_class")
        .param("policy", policy)
        .query(
            (rs, n) ->
                new Rule(
                    rs.getString("data_class"),
                    rs.getString("purpose"),
                    rs.getString("action"),
                    rs.getString("trigger_event"),
                    (Integer) rs.getObject("duration_days")))
        .list();
  }

  /** Inserts the empty checklist of a new DRAFT. */
  public void insertChecklist(UUID policy, Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_policy_checklist VALUES (:policy, false, false,"
                + " false, false, false, false, false, false, NULL, :now)")
        .param("policy", policy)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** The checklist. */
  public Checklist checklist(UUID policy) {
    return jdbc.sql(
            "SELECT jurisdiction_identified, source_reference_recorded, counsel_review_recorded,"
                + " data_classes_covered, lifecycle_trigger_defined, durations_or_hold_set,"
                + " employee_wording_version_recorded, rollback_review_complete, review_reference"
                + " FROM integrations.privacy_policy_checklist WHERE policy_id = :policy")
        .param("policy", policy)
        .query(
            (rs, n) ->
                new Checklist(
                    rs.getBoolean(1),
                    rs.getBoolean(2),
                    rs.getBoolean(3),
                    rs.getBoolean(4),
                    rs.getBoolean(5),
                    rs.getBoolean(6),
                    rs.getBoolean(7),
                    rs.getBoolean(8),
                    rs.getString(9)))
        .single();
  }

  /** Replaces the checklist of a DRAFT. */
  public void updateChecklist(UUID policy, Checklist c, Instant now) {
    jdbc.sql(
            "UPDATE integrations.privacy_policy_checklist SET jurisdiction_identified = :a,"
                + " source_reference_recorded = :b, counsel_review_recorded = :c,"
                + " data_classes_covered = :d, lifecycle_trigger_defined = :e,"
                + " durations_or_hold_set = :f, employee_wording_version_recorded = :g,"
                + " rollback_review_complete = :h, review_reference = :ref, updated_at = :now"
                + " WHERE policy_id = :policy")
        .param("a", c.jurisdictionIdentified())
        .param("b", c.sourceReferenceRecorded())
        .param("c", c.counselReviewRecorded())
        .param("d", c.dataClassesCovered())
        .param("e", c.lifecycleTriggerDefined())
        .param("f", c.durationsOrHoldSet())
        .param("g", c.employeeWordingVersionRecorded())
        .param("h", c.rollbackReviewComplete())
        .param("ref", c.reviewReference())
        .param("now", Timestamp.from(now))
        .param("policy", policy)
        .update();
  }

  /** Moves the version one step (state may stay the same while DRAFT). */
  public void move(UUID policy, String state, int version, Instant now) {
    jdbc.sql(
            "UPDATE integrations.privacy_policy SET state = :state, row_version = :version,"
                + " updated_at = :now WHERE id = :policy")
        .param("state", state)
        .param("version", version)
        .param("now", Timestamp.from(now))
        .param("policy", policy)
        .update();
  }

  /** Appends a history event. */
  public void insertEvent(UUID policy, int sequence, String from, String to, Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_policy_event VALUES (:policy, :sequence, :from, :to,"
                + " :now)")
        .param("policy", policy)
        .param("sequence", sequence)
        .param("from", from)
        .param("to", to)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** The history, oldest first. */
  public List<EventRow> events(UUID policy) {
    return jdbc.sql(
            "SELECT sequence, from_state, to_state, occurred_at"
                + " FROM integrations.privacy_policy_event WHERE policy_id = :policy"
                + " ORDER BY sequence")
        .param("policy", policy)
        .query(
            (rs, n) ->
                new EventRow(
                    rs.getInt("sequence"),
                    rs.getString("from_state"),
                    rs.getString("to_state"),
                    rs.getTimestamp("occurred_at").toInstant()))
        .list();
  }

  /** Whether another ACTIVE version of the country overlaps the period. */
  public boolean overlapsActive(UUID except, String country, LocalDate from, LocalDate to) {
    Boolean overlaps =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM integrations.privacy_policy o WHERE o.state ="
                    + " 'ACTIVE' AND o.country_code = :country AND o.id <> :except AND"
                    + " daterange(o.effective_from, coalesce(o.effective_to, 'infinity'::date),"
                    + " '[]') && daterange(:from, coalesce(CAST(:to AS date),"
                    + " 'infinity'::date), '[]'))")
            .param("country", country)
            .param("except", except)
            .param("from", from)
            .param("to", to)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(overlaps);
  }
}
