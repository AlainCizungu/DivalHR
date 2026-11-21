package com.divalhr.core.integrations.partner;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL of partner applications (MVP-120D). Every statement is parameterized; nothing here logs
 * or formats a value into SQL text.
 */
@Repository
public class PartnerApplicationRepository {

  /** A stored application as read. */
  public record Row(
      UUID id,
      String reference,
      String status,
      int version,
      String primaryCategory,
      String legalName,
      String tradingName,
      String countryOfRegistration,
      List<String> countriesOfOperation,
      String website,
      String contactRole,
      List<String> languages,
      String apiAvailability,
      String regulatorName,
      String serviceSummary,
      byte[] confidential,
      String organizationFingerprint,
      String submissionLocale,
      Instant submittedAt,
      Instant updatedAt) {}

  /** A stored checklist row. */
  public record CheckRow(
      String code, String result, String actor, byte[] note, int version, Instant updatedAt) {}

  /** A stored history row. */
  public record EventRow(
      int sequence,
      String eventType,
      String fromStatus,
      String toStatus,
      String checkCode,
      String checkResult,
      String reasonCode,
      String actorType,
      String actorSubject,
      Instant occurredAt) {}

  /** A stored submission key. */
  public record SubmissionRow(String requestFingerprint, UUID applicationId) {}

  /** Consent as stored. */
  public record ConsentRow(
      String privacyNoticeVersion, String consentVersion, String locale, Instant acceptedAt) {}

  /** Queue filters; every value is already validated. */
  public record Filter(
      String status,
      String category,
      String capability,
      String country,
      Instant submittedFrom,
      Instant submittedBefore) {}

  private static final String COLUMNS =
      """
      a.id, a.reference, a.status, a.row_version, a.primary_category, a.legal_name, a.trading_name,
      a.country_of_registration, array_to_string(a.countries_of_operation, ',') AS countries,
      a.website, a.contact_role, array_to_string(a.supported_languages, ',') AS languages,
      a.api_availability, a.regulator_name, a.service_summary, a.confidential_ciphertext,
      a.organization_fingerprint, a.submission_locale, a.submitted_at, a.updated_at
      """;

  private static final String FILTER =
      """
      (CAST(:status AS text) IS NULL OR a.status = :status)
      AND (CAST(:category AS text) IS NULL OR a.primary_category = :category
           OR EXISTS (SELECT 1 FROM integrations.partner_application_category c
                       WHERE c.application_id = a.id AND c.category = :category))
      AND (CAST(:capability AS text) IS NULL
           OR EXISTS (SELECT 1 FROM integrations.partner_application_capability k
                       WHERE k.application_id = a.id AND k.capability = :capability))
      AND (CAST(:country AS text) IS NULL OR a.country_of_registration = :country
           OR :country = ANY (a.countries_of_operation))
      AND (CAST(:from AS timestamptz) IS NULL OR a.submitted_at >= CAST(:from AS timestamptz))
      AND (CAST(:before AS timestamptz) IS NULL OR a.submitted_at < CAST(:before AS timestamptz))
      """;

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public PartnerApplicationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ---------------------------------------------------------------- submission

  /**
   * Finds a stored submission key.
   *
   * @param keyDigest keyed digest of the idempotency key
   * @return the stored fingerprint and application
   */
  public Optional<SubmissionRow> findSubmission(String keyDigest) {
    return jdbc.sql(
            """
            SELECT request_fingerprint, application_id
              FROM integrations.partner_application_submission WHERE key_digest = :key
            """)
        .param("key", keyDigest)
        .query(
            (rs, n) ->
                new SubmissionRow(
                    rs.getString("request_fingerprint"),
                    rs.getObject("application_id", UUID.class)))
        .optional();
  }

  /**
   * Inserts the application row.
   *
   * @param id internal id
   * @param reference opaque reference
   * @param s validated submission
   * @param ciphertext sealed confidential block
   * @param emailDigest keyed email digest
   * @param organizationFingerprint keyed organization fingerprint
   * @param at server time
   */
  public void insertApplication(
      UUID id,
      String reference,
      PartnerSubmission s,
      byte[] ciphertext,
      String emailDigest,
      String organizationFingerprint,
      Instant at) {
    jdbc.sql(
            """
            INSERT INTO integrations.partner_application
              (id, reference, status, row_version, primary_category, legal_name, trading_name,
               country_of_registration, countries_of_operation, website, contact_role,
               supported_languages, api_availability, regulator_name, service_summary,
               confidential_ciphertext, email_digest, organization_fingerprint, submission_locale,
               submitted_at, updated_at)
            VALUES (:id, :reference, 'SUBMITTED', 0, :primary, :legalName, :tradingName, :country,
                    string_to_array(:operation, ','), :website, :role,
                    string_to_array(:languages, ','), :api, :regulator, :summary, :cipher,
                    :emailDigest, :orgFingerprint, :locale, :at, :at)
            """)
        .param("id", id)
        .param("reference", reference)
        .param("primary", s.primaryCategory().name())
        .param("legalName", s.legalName())
        .param("tradingName", s.tradingName())
        .param("country", s.countryOfRegistration())
        .param("operation", String.join(",", s.countriesOfOperation()))
        .param("website", s.website())
        .param("role", s.contactRole())
        .param("languages", String.join(",", s.languages()))
        .param("api", s.apiAvailability().name())
        .param("regulator", s.regulatorName())
        .param("summary", s.serviceSummary())
        .param("cipher", ciphertext)
        .param("emailDigest", emailDigest)
        .param("orgFingerprint", organizationFingerprint)
        .param("locale", s.locale())
        .param("at", Timestamp.from(at))
        .update();
  }

  /**
   * Inserts the categories, capabilities, consent and the seven pending checks.
   *
   * @param id application id
   * @param s validated submission
   * @param at server time
   */
  public void insertDetails(UUID id, PartnerSubmission s, Instant at) {
    int position = 1;
    for (PartnerDomain.Category category : s.additionalCategories()) {
      jdbc.sql(
              """
              INSERT INTO integrations.partner_application_category (application_id, position, category)
              VALUES (:id, :position, :category)
              """)
          .param("id", id)
          .param("position", position++)
          .param("category", category.name())
          .update();
    }
    for (PartnerDomain.Capability capability : s.capabilities()) {
      jdbc.sql(
              """
              INSERT INTO integrations.partner_application_capability (application_id, capability)
              VALUES (:id, :capability)
              """)
          .param("id", id)
          .param("capability", capability.name())
          .update();
    }
    jdbc.sql(
            """
            INSERT INTO integrations.partner_application_consent
              (application_id, privacy_notice_version, consent_version, acknowledged, locale, accepted_at)
            VALUES (:id, :notice, :consent, true, :locale, :at)
            """)
        .param("id", id)
        .param("notice", s.privacyNoticeVersion())
        .param("consent", s.consentVersion())
        .param("locale", s.locale())
        .param("at", Timestamp.from(at))
        .update();
    for (PartnerDomain.CheckCode code : PartnerDomain.CheckCode.values()) {
      jdbc.sql(
              """
              INSERT INTO integrations.partner_application_check
                (application_id, check_code, result, actor_subject, note_ciphertext, row_version, updated_at)
              VALUES (:id, :code, 'PENDING', NULL, NULL, 0, :at)
              """)
          .param("id", id)
          .param("code", code.name())
          .param("at", Timestamp.from(at))
          .update();
    }
  }

  /**
   * Records the idempotency key of a submission.
   *
   * @param keyDigest keyed key digest
   * @param requestFingerprint keyed request digest
   * @param id application id
   * @param at server time
   */
  public void insertSubmission(String keyDigest, String requestFingerprint, UUID id, Instant at) {
    jdbc.sql(
            """
            INSERT INTO integrations.partner_application_submission
              (key_digest, request_fingerprint, application_id, created_at)
            VALUES (:key, :fingerprint, :id, :at)
            """)
        .param("key", keyDigest)
        .param("fingerprint", requestFingerprint)
        .param("id", id)
        .param("at", Timestamp.from(at))
        .update();
  }

  // ---------------------------------------------------------------- reads

  /**
   * An application by internal id.
   *
   * @param id internal id
   * @return the row
   */
  public Optional<Row> findById(UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM integrations.partner_application a WHERE a.id = :id")
        .param("id", id)
        .query(PartnerApplicationRepository::row)
        .optional();
  }

  /**
   * An application by reference.
   *
   * @param reference opaque reference
   * @return the row
   */
  public Optional<Row> findByReference(String reference) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM integrations.partner_application a WHERE a.reference = :r")
        .param("r", reference)
        .query(PartnerApplicationRepository::row)
        .optional();
  }

  /**
   * An application by reference, locked for update (serializes checklist and transitions).
   *
   * @param reference opaque reference
   * @return the locked row
   */
  public Optional<Row> lockByReference(String reference) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM integrations.partner_application a WHERE a.reference = :r FOR UPDATE")
        .param("r", reference)
        .query(PartnerApplicationRepository::row)
        .optional();
  }

  /**
   * One page of the queue, newest first.
   *
   * @param filter filters
   * @param afterSubmittedAt keyset time or {@code null}
   * @param afterId keyset id or {@code null}
   * @param limit rows to read
   * @return rows
   */
  public List<Row> page(Filter filter, Instant afterSubmittedAt, UUID afterId, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                   FROM integrations.partner_application a
                  WHERE
                  """
                + FILTER
                + """
                   AND (CAST(:afterAt AS timestamptz) IS NULL
                        OR (a.submitted_at, a.id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                  ORDER BY a.submitted_at DESC, a.id DESC
                  LIMIT :limit
                  """)
        .params(filterParams(filter))
        .param("afterAt", afterSubmittedAt == null ? null : Timestamp.from(afterSubmittedAt))
        .param("afterId", afterId)
        .param("limit", limit)
        .query(PartnerApplicationRepository::row)
        .list();
  }

  /**
   * Counts per status for the filters other than status.
   *
   * @param filter filters (its status is ignored)
   * @return count by status name
   */
  public Map<String, Integer> statusCounts(Filter filter) {
    Filter withoutStatus =
        new Filter(
            null,
            filter.category(),
            filter.capability(),
            filter.country(),
            filter.submittedFrom(),
            filter.submittedBefore());
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (PartnerDomain.Status status : PartnerDomain.Status.values()) {
      counts.put(status.name(), 0);
    }
    jdbc.sql(
            """
            SELECT a.status AS status, count(*) AS total
              FROM integrations.partner_application a
             WHERE
            """
                + FILTER
                + " GROUP BY a.status")
        .params(filterParams(withoutStatus))
        .query(
            (rs, n) -> {
              counts.put(rs.getString("status"), rs.getInt("total"));
              return null;
            })
        .list();
    return counts;
  }

  /**
   * The additional categories of applications.
   *
   * @param ids application ids
   * @return categories by application, in position order
   */
  public Map<UUID, List<String>> additionalCategories(Collection<UUID> ids) {
    return grouped(
        ids,
        """
        SELECT application_id, category AS value FROM integrations.partner_application_category
         WHERE application_id = ANY (CAST(:ids AS uuid[])) ORDER BY application_id, position
        """);
  }

  /**
   * The requested capabilities of applications.
   *
   * @param ids application ids
   * @return capabilities by application, sorted
   */
  public Map<UUID, List<String>> capabilities(Collection<UUID> ids) {
    return grouped(
        ids,
        """
        SELECT application_id, capability AS value FROM integrations.partner_application_capability
         WHERE application_id = ANY (CAST(:ids AS uuid[])) ORDER BY application_id, capability
        """);
  }

  private Map<UUID, List<String>> grouped(Collection<UUID> ids, String sql) {
    Map<UUID, List<String>> result = new LinkedHashMap<>();
    if (ids.isEmpty()) {
      return result;
    }
    String literal = "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    jdbc.sql(sql)
        .param("ids", literal)
        .query(
            (rs, n) -> {
              result
                  .computeIfAbsent(
                      rs.getObject("application_id", UUID.class), k -> new ArrayList<>())
                  .add(rs.getString("value"));
              return null;
            })
        .list();
    return result;
  }

  /**
   * The consent of an application.
   *
   * @param id application id
   * @return consent
   */
  public ConsentRow consent(UUID id) {
    return jdbc.sql(
            """
            SELECT privacy_notice_version, consent_version, locale, accepted_at
              FROM integrations.partner_application_consent WHERE application_id = :id
            """)
        .param("id", id)
        .query(
            (rs, n) ->
                new ConsentRow(
                    rs.getString("privacy_notice_version"),
                    rs.getString("consent_version"),
                    rs.getString("locale"),
                    rs.getTimestamp("accepted_at").toInstant()))
        .single();
  }

  /**
   * The checklist of an application.
   *
   * @param id application id
   * @return the seven rows
   */
  public List<CheckRow> checks(UUID id) {
    return jdbc.sql(
            """
            SELECT check_code, result, actor_subject, note_ciphertext, row_version, updated_at
              FROM integrations.partner_application_check WHERE application_id = :id
            """)
        .param("id", id)
        .query(PartnerApplicationRepository::check)
        .list();
  }

  /**
   * The checklist of an application, locked for update (the approval gate re-reads it this way).
   *
   * @param id application id
   * @return the seven rows
   */
  public List<CheckRow> lockChecks(UUID id) {
    return jdbc.sql(
            """
            SELECT check_code, result, actor_subject, note_ciphertext, row_version, updated_at
              FROM integrations.partner_application_check WHERE application_id = :id
             ORDER BY check_code FOR UPDATE
            """)
        .param("id", id)
        .query(PartnerApplicationRepository::check)
        .list();
  }

  /**
   * One checklist row.
   *
   * @param id application id
   * @param code check code
   * @return the row
   */
  public Optional<CheckRow> check(UUID id, String code) {
    return jdbc.sql(
            """
            SELECT check_code, result, actor_subject, note_ciphertext, row_version, updated_at
              FROM integrations.partner_application_check
             WHERE application_id = :id AND check_code = :code
            """)
        .param("id", id)
        .param("code", code)
        .query(PartnerApplicationRepository::check)
        .optional();
  }

  /**
   * Other applications with the same organization fingerprint.
   *
   * @param id this application
   * @param fingerprint its organization fingerprint
   * @return the count
   */
  public int possibleDuplicates(UUID id, String fingerprint) {
    return jdbc.sql(
            """
            SELECT count(*) FROM integrations.partner_application
             WHERE organization_fingerprint = :fingerprint AND id <> :id
            """)
        .param("fingerprint", fingerprint)
        .param("id", id)
        .query(Integer.class)
        .single();
  }

  /**
   * History entries after a sequence number.
   *
   * @param id application id
   * @param after last seen sequence (0 for the first page)
   * @param limit rows to read
   * @return entries in sequence order
   */
  public List<EventRow> events(UUID id, int after, int limit) {
    return jdbc.sql(
            """
            SELECT sequence, event_type, from_status, to_status, check_code, check_result, reason_code,
                   actor_type, actor_subject, occurred_at
              FROM integrations.partner_application_event
             WHERE application_id = :id AND sequence > :after ORDER BY sequence LIMIT :limit
            """)
        .param("id", id)
        .param("after", after)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new EventRow(
                    rs.getInt("sequence"),
                    rs.getString("event_type"),
                    rs.getString("from_status"),
                    rs.getString("to_status"),
                    rs.getString("check_code"),
                    rs.getString("check_result"),
                    rs.getString("reason_code"),
                    rs.getString("actor_type"),
                    rs.getString("actor_subject"),
                    rs.getTimestamp("occurred_at").toInstant()))
        .list();
  }

  // ---------------------------------------------------------------- writes

  /**
   * Appends a history event with the next contiguous sequence.
   *
   * @param id application id
   * @param type event type
   * @param from previous status or {@code null}
   * @param to current status
   * @param checkCode check code or {@code null}
   * @param checkResult check result or {@code null}
   * @param reason reason or {@code null}
   * @param actorType APPLICANT or PLATFORM_ADMIN
   * @param actorSubject admin subject or {@code null}
   * @param at server time
   * @return the new sequence
   */
  public int appendEvent(
      UUID id,
      PartnerDomain.EventType type,
      String from,
      String to,
      String checkCode,
      String checkResult,
      String reason,
      String actorType,
      String actorSubject,
      Instant at) {
    Integer sequence =
        jdbc.sql(
                """
                INSERT INTO integrations.partner_application_event
                  (application_id, sequence, event_type, from_status, to_status, check_code,
                   check_result, reason_code, actor_type, actor_subject, occurred_at)
                VALUES (:id,
                        (SELECT coalesce(max(e.sequence), 0) + 1
                           FROM integrations.partner_application_event e WHERE e.application_id = :id),
                        :type, :from, :to, :checkCode, :checkResult, :reason, :actorType, :actor, :at)
                RETURNING sequence
                """)
            .param("id", id)
            .param("type", type.name())
            .param("from", from)
            .param("to", to)
            .param("checkCode", checkCode)
            .param("checkResult", checkResult)
            .param("reason", reason)
            .param("actorType", actorType)
            .param("actor", actorSubject)
            .param("at", Timestamp.from(at))
            .query(Integer.class)
            .single();
    return sequence;
  }

  /**
   * Moves the application to a new status when its version still matches.
   *
   * @param id application id
   * @param expectedVersion version read
   * @param status new status
   * @param at server time
   * @return rows changed (0 when stale)
   */
  public int updateStatus(UUID id, int expectedVersion, String status, Instant at) {
    return jdbc.sql(
            """
            UPDATE integrations.partner_application
               SET status = :status, row_version = row_version + 1, updated_at = :at
             WHERE id = :id AND row_version = :version
            """)
        .param("status", status)
        .param("at", Timestamp.from(at))
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }

  /**
   * Records a check result when its version still matches.
   *
   * @param id application id
   * @param code check code
   * @param expectedVersion version read
   * @param result new result
   * @param actor admin subject, or {@code null} when resetting to PENDING
   * @param note sealed note or {@code null}
   * @param at server time
   * @return rows changed (0 when stale)
   */
  public int updateCheck(
      UUID id,
      String code,
      int expectedVersion,
      String result,
      String actor,
      byte[] note,
      Instant at) {
    return jdbc.sql(
            """
            UPDATE integrations.partner_application_check
               SET result = :result, actor_subject = :actor, note_ciphertext = :note,
                   row_version = row_version + 1, updated_at = :at
             WHERE application_id = :id AND check_code = :code AND row_version = :version
            """)
        .param("result", result)
        .param("actor", actor)
        .param("note", note)
        .param("at", Timestamp.from(at))
        .param("id", id)
        .param("code", code)
        .param("version", expectedVersion)
        .update();
  }

  // ---------------------------------------------------------------- mapping

  private static Map<String, Object> filterParams(Filter f) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("status", f.status());
    params.put("category", f.category());
    params.put("capability", f.capability());
    params.put("country", f.country());
    params.put("from", f.submittedFrom() == null ? null : Timestamp.from(f.submittedFrom()));
    params.put("before", f.submittedBefore() == null ? null : Timestamp.from(f.submittedBefore()));
    return params;
  }

  private static Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getString("reference"),
        rs.getString("status"),
        rs.getInt("row_version"),
        rs.getString("primary_category"),
        rs.getString("legal_name"),
        rs.getString("trading_name"),
        rs.getString("country_of_registration"),
        Arrays.asList(rs.getString("countries").split(",")),
        rs.getString("website"),
        rs.getString("contact_role"),
        Arrays.asList(rs.getString("languages").split(",")),
        rs.getString("api_availability"),
        rs.getString("regulator_name"),
        rs.getString("service_summary"),
        rs.getBytes("confidential_ciphertext"),
        rs.getString("organization_fingerprint"),
        rs.getString("submission_locale"),
        rs.getTimestamp("submitted_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static CheckRow check(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new CheckRow(
        rs.getString("check_code"),
        rs.getString("result"),
        rs.getString("actor_subject"),
        rs.getBytes("note_ciphertext"),
        rs.getInt("row_version"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
