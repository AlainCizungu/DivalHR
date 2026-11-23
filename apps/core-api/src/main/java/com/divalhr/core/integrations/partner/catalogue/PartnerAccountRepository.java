package com.divalhr.core.integrations.partner.catalogue;

import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.Bilingual;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.Content;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CurrencyLimit;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Storage of partner accounts and the product catalogue (MVP-120E, ADR 0044). Every statement is
 * parameterized. Database triggers enforce the transition matrices, immutability and scope; the
 * services check first so a violation is a precise error rather than a constraint name.
 */
@Repository
public class PartnerAccountRepository {

  /** A partner row. */
  public record PartnerRow(
      UUID id,
      String reference,
      UUID applicationId,
      String applicationReference,
      String status,
      int version,
      String primaryCategory,
      String legalName,
      String tradingName,
      String countryOfRegistration,
      String website,
      List<String> languages,
      Instant createdAt,
      Instant updatedAt) {}

  /** An approved application. */
  public record ApplicationRow(UUID id, String reference, String status) {}

  /** A history event of a partner. */
  public record EventRow(
      int sequence,
      String eventType,
      String fromStatus,
      String toStatus,
      String reasonCode,
      boolean noteRecorded,
      Integer itemCount,
      Instant occurredAt) {}

  /** A product identity. */
  public record ProductRow(
      UUID id, UUID partnerId, String productCode, String publicReference, String productType) {}

  /** A product version. */
  public record VersionRow(
      UUID id,
      UUID productId,
      int version,
      String status,
      int rowVersion,
      Content content,
      Instant createdAt,
      Instant updatedAt) {}

  /** A product history event. */
  public record ProductEventRow(
      int sequence,
      int version,
      String eventType,
      String fromStatus,
      String toStatus,
      String reasonCode,
      boolean noteRecorded,
      String actor,
      Instant occurredAt) {}

  /** A row of the ready-applications list. */
  public record ReadyRow(
      UUID id,
      String reference,
      String legalName,
      String tradingName,
      String country,
      String category,
      Instant approvedAt,
      String partnerReference) {}

  /** A product summary row. */
  public record SummaryRow(
      ProductRow product,
      int latestVersion,
      String latestStatus,
      Integer liveVersion,
      String liveStatus,
      Bilingual name) {}

  /** A catalogue row. */
  public record CatalogueRow(
      UUID versionId,
      String publicReference,
      String partnerName,
      String productType,
      Content content) {}

  private static final String PARTNER_COLUMNS =
      "p.id, p.reference, p.application_id, a.reference AS app_reference, p.status, p.row_version, "
          + "p.primary_category, p.legal_name, p.trading_name, p.country_of_registration, p.website, "
          + "p.supported_languages, p.created_at, p.updated_at";
  private static final String PARTNER_FROM =
      " FROM integrations.partner p JOIN integrations.partner_application a ON a.id = p.application_id ";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public PartnerAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------ partner

  /**
   * Locks an application by reference.
   *
   * @param reference application reference
   * @return the application
   */
  public Optional<ApplicationRow> lockApplication(String reference) {
    return jdbc.sql(
            "SELECT id, reference, status FROM integrations.partner_application WHERE reference = :r FOR UPDATE")
        .param("r", reference)
        .query(
            (rs, n) ->
                new ApplicationRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("reference"),
                    rs.getString("status")))
        .optional();
  }

  /**
   * Finds the partner created from an application.
   *
   * @param applicationId application id
   * @return the partner
   */
  public Optional<PartnerRow> findByApplication(UUID applicationId) {
    return jdbc.sql("SELECT " + PARTNER_COLUMNS + PARTNER_FROM + "WHERE p.application_id = :a")
        .param("a", applicationId)
        .query(PartnerAccountRepository::partner)
        .optional();
  }

  /**
   * Finds a partner by reference.
   *
   * @param reference partner reference
   * @return the partner
   */
  public Optional<PartnerRow> findByReference(String reference) {
    return jdbc.sql("SELECT " + PARTNER_COLUMNS + PARTNER_FROM + "WHERE p.reference = :r")
        .param("r", reference)
        .query(PartnerAccountRepository::partner)
        .optional();
  }

  /**
   * Locks a partner by reference.
   *
   * @param reference partner reference
   * @return the partner
   */
  public Optional<PartnerRow> lockByReference(String reference) {
    return jdbc.sql(
            "SELECT " + PARTNER_COLUMNS + PARTNER_FROM + "WHERE p.reference = :r FOR UPDATE OF p")
        .param("r", reference)
        .query(PartnerAccountRepository::partner)
        .optional();
  }

  /**
   * Finds a partner by id.
   *
   * @param id partner id
   * @return the partner
   */
  public Optional<PartnerRow> findById(UUID id) {
    return jdbc.sql("SELECT " + PARTNER_COLUMNS + PARTNER_FROM + "WHERE p.id = :i")
        .param("i", id)
        .query(PartnerAccountRepository::partner)
        .optional();
  }

  /**
   * Copies the approved identity, categories, capabilities and operating countries of an approved
   * application into a new draft partner. Nothing else is copied.
   *
   * @param id new partner id
   * @param reference new partner reference
   * @param applicationId source application
   * @param at creation time
   */
  public void insertFromApplication(UUID id, String reference, UUID applicationId, Instant at) {
    jdbc.sql(
            """
            INSERT INTO integrations.partner (id, reference, application_id, status, row_version, primary_category,
                legal_name, trading_name, country_of_registration, website, supported_languages, created_at, updated_at)
            SELECT :id, :ref, a.id, 'DRAFT', 0, a.primary_category, a.legal_name, a.trading_name,
                   a.country_of_registration, a.website, a.supported_languages, :at, :at
              FROM integrations.partner_application a WHERE a.id = :app
            """)
        .param("id", id)
        .param("ref", reference)
        .param("app", applicationId)
        .param("at", java.sql.Timestamp.from(at))
        .update();
    jdbc.sql(
            """
            INSERT INTO integrations.partner_category (partner_id, position, category)
            SELECT :id, c.position, c.category FROM integrations.partner_application_category c
             WHERE c.application_id = :app
            """)
        .param("id", id)
        .param("app", applicationId)
        .update();
    jdbc.sql(
            """
            INSERT INTO integrations.partner_capability (partner_id, capability)
            SELECT :id, c.capability FROM integrations.partner_application_capability c
             WHERE c.application_id = :app
            """)
        .param("id", id)
        .param("app", applicationId)
        .update();
    jdbc.sql(
            """
            INSERT INTO integrations.partner_country (partner_id, country_code, origin, added_at)
            SELECT :id, u.code, 'APPLICATION', :at
              FROM integrations.partner_application a, unnest(a.countries_of_operation) AS u(code)
             WHERE a.id = :app
            """)
        .param("id", id)
        .param("app", applicationId)
        .param("at", java.sql.Timestamp.from(at))
        .update();
  }

  /**
   * Changes the status of a partner, advancing its version by one.
   *
   * @param id partner id
   * @param expectedVersion version read under lock
   * @param status new status
   * @param at change time
   * @return rows changed
   */
  public int updateStatus(UUID id, int expectedVersion, String status, Instant at) {
    return jdbc.sql(
            """
            UPDATE integrations.partner SET status = :s, row_version = row_version + 1, updated_at = :at
             WHERE id = :id AND row_version = :v
            """)
        .param("s", status)
        .param("at", java.sql.Timestamp.from(at))
        .param("id", id)
        .param("v", expectedVersion)
        .update();
  }

  /**
   * Advances a partner's version without changing its status (scope amendment).
   *
   * @param id partner id
   * @param expectedVersion version read under lock
   * @param at change time
   * @return rows changed
   */
  public int touch(UUID id, int expectedVersion, Instant at) {
    return updateStatus(id, expectedVersion, currentStatus(id), at);
  }

  private String currentStatus(UUID id) {
    return jdbc.sql("SELECT status FROM integrations.partner WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .single();
  }

  /**
   * Adds approved operating countries (never removes).
   *
   * @param id partner id
   * @param countries countries to add
   * @param at time
   * @return number actually added
   */
  public int addCountries(UUID id, List<String> countries, Instant at) {
    int added = 0;
    for (String code : countries) {
      added +=
          jdbc.sql(
                  """
                  INSERT INTO integrations.partner_country (partner_id, country_code, origin, added_at)
                  VALUES (:id, :c, 'AMENDMENT', :at) ON CONFLICT DO NOTHING
                  """)
              .param("id", id)
              .param("c", code)
              .param("at", java.sql.Timestamp.from(at))
              .update();
    }
    return added;
  }

  /**
   * Appends a partner event with the next sequence.
   *
   * @param id partner id
   * @param type event type
   * @param from previous status or null
   * @param to resulting status
   * @param reason reason code or null
   * @param note encrypted note or null
   * @param items country count or null
   * @param actor actor subject
   * @param at time
   * @return the sequence
   */
  public int appendEvent(
      UUID id,
      String type,
      String from,
      String to,
      String reason,
      byte[] note,
      Integer items,
      String actor,
      Instant at) {
    int sequence =
        jdbc.sql(
                "SELECT coalesce(max(sequence), 0) + 1 FROM integrations.partner_event WHERE partner_id = :id")
            .param("id", id)
            .query(Integer.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO integrations.partner_event (partner_id, sequence, event_type, from_status, to_status,
                reason_code, note_ciphertext, item_count, actor_subject, occurred_at)
            VALUES (:id, :seq, :type, :from, :to, :reason, :note, :items, :actor, :at)
            """)
        .param("id", id)
        .param("seq", sequence)
        .param("type", type)
        .param("from", from)
        .param("to", to)
        .param("reason", reason)
        .param("note", note)
        .param("items", items)
        .param("actor", actor)
        .param("at", java.sql.Timestamp.from(at))
        .update();
    return sequence;
  }

  /**
   * Partner events after a sequence.
   *
   * @param id partner id
   * @param after last sequence already read
   * @param limit rows
   * @return events oldest first
   */
  public List<EventRow> events(UUID id, int after, int limit) {
    return jdbc.sql(
            """
            SELECT sequence, event_type, from_status, to_status, reason_code, note_ciphertext IS NOT NULL AS noted,
                   item_count, occurred_at
              FROM integrations.partner_event WHERE partner_id = :id AND sequence > :after
             ORDER BY sequence LIMIT :limit
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
                    rs.getString("reason_code"),
                    rs.getBoolean("noted"),
                    (Integer) rs.getObject("item_count"),
                    rs.getTimestamp("occurred_at").toInstant()))
        .list();
  }

  /**
   * Additional categories of a partner.
   *
   * @param id partner id
   * @return categories by position
   */
  public List<String> additionalCategories(UUID id) {
    return jdbc.sql(
            "SELECT category FROM integrations.partner_category WHERE partner_id = :id ORDER BY position")
        .param("id", id)
        .query(String.class)
        .list();
  }

  /**
   * Capabilities of a partner.
   *
   * @param id partner id
   * @return capabilities sorted
   */
  public List<String> capabilities(UUID id) {
    return jdbc.sql(
            "SELECT capability FROM integrations.partner_capability WHERE partner_id = :id ORDER BY capability")
        .param("id", id)
        .query(String.class)
        .list();
  }

  /**
   * Approved operating countries of a partner.
   *
   * @param id partner id
   * @return countries sorted
   */
  public List<String> countries(UUID id) {
    return jdbc.sql(
            "SELECT country_code FROM integrations.partner_country WHERE partner_id = :id ORDER BY country_code")
        .param("id", id)
        .query(String.class)
        .list();
  }

  /**
   * Product counts of a partner.
   *
   * @param id partner id
   * @return {total, active}
   */
  public int[] productCounts(UUID id) {
    return jdbc.sql(
            """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE EXISTS (SELECT 1 FROM integrations.partner_product_version v
                                                   WHERE v.product_id = x.id AND v.status = 'ACTIVE')) AS active
              FROM integrations.partner_product x WHERE x.partner_id = :id
            """)
        .param("id", id)
        .query((rs, n) -> new int[] {rs.getInt("total"), rs.getInt("active")})
        .single();
  }

  /**
   * A page of ready (approved) applications, newest approval first.
   *
   * @param afterAt keyset time or null
   * @param afterId keyset id or null
   * @param limit rows
   * @return rows
   */
  public List<ReadyRow> readyApplications(Instant afterAt, UUID afterId, int limit) {
    return jdbc.sql(
            """
            SELECT a.id, a.reference, a.legal_name, a.trading_name, a.country_of_registration, a.primary_category,
                   ap.approved_at, p.reference AS partner_reference
              FROM integrations.partner_application a
              JOIN LATERAL (SELECT max(e.occurred_at) AS approved_at
                              FROM integrations.partner_application_event e
                             WHERE e.application_id = a.id AND e.to_status = 'APPROVED_FOR_ONBOARDING') ap ON true
              LEFT JOIN integrations.partner p ON p.application_id = a.id
             WHERE a.status = 'APPROVED_FOR_ONBOARDING'
               AND (CAST(:at AS timestamptz) IS NULL
                    OR ap.approved_at < CAST(:at AS timestamptz)
                    OR (ap.approved_at = CAST(:at AS timestamptz) AND a.id < CAST(:id AS uuid)))
             ORDER BY ap.approved_at DESC, a.id DESC LIMIT :limit
            """)
        .param("at", afterAt == null ? null : java.sql.Timestamp.from(afterAt))
        .param("id", afterId)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new ReadyRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("reference"),
                    rs.getString("legal_name"),
                    rs.getString("trading_name"),
                    rs.getString("country_of_registration"),
                    rs.getString("primary_category"),
                    rs.getTimestamp("approved_at").toInstant(),
                    rs.getString("partner_reference")))
        .list();
  }

  /**
   * The approval time of an application approved for onboarding.
   *
   * @param applicationId application id
   * @return the time of its latest approval event
   */
  public Optional<Instant> approvedAt(UUID applicationId) {
    return jdbc.sql(
            "SELECT max(occurred_at) FROM integrations.partner_application_event"
                + " WHERE application_id = :id AND to_status = 'APPROVED_FOR_ONBOARDING'")
        .param("id", applicationId)
        .query((rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
        .optional();
  }

  /**
   * A page of partners, newest first.
   *
   * @param status status filter or null
   * @param category category filter or null
   * @param afterAt keyset time or null
   * @param afterId keyset id or null
   * @param limit rows
   * @return rows
   */
  public List<PartnerRow> page(
      String status, String category, Instant afterAt, UUID afterId, int limit) {
    return jdbc.sql(
            "SELECT "
                + PARTNER_COLUMNS
                + PARTNER_FROM
                + "WHERE "
                + PARTNER_FILTER
                + " AND (CAST(:at AS timestamptz) IS NULL OR p.created_at < CAST(:at AS timestamptz)"
                + " OR (p.created_at = CAST(:at AS timestamptz) AND p.id < CAST(:id AS uuid)))"
                + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit")
        .param("status", status)
        .param("category", category)
        .param("at", afterAt == null ? null : java.sql.Timestamp.from(afterAt))
        .param("id", afterId)
        .param("limit", limit)
        .query(PartnerAccountRepository::partner)
        .list();
  }

  private static final String PARTNER_FILTER =
      "(CAST(:status AS text) IS NULL OR p.status = :status)"
          + " AND (CAST(:category AS text) IS NULL OR p.primary_category = :category"
          + " OR EXISTS (SELECT 1 FROM integrations.partner_category c WHERE c.partner_id = p.id AND c.category = :category))";

  /**
   * Partners per status for a category filter.
   *
   * @param category category filter or null
   * @return counts by status
   */
  public Map<String, Integer> statusCounts(String category) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String s : List.of("DRAFT", "ACTIVE", "SUSPENDED", "CLOSED")) {
      counts.put(s, 0);
    }
    jdbc.sql(
            "SELECT p.status, count(*) AS n FROM integrations.partner p WHERE "
                + PARTNER_FILTER.replace(
                    "CAST(:status AS text) IS NULL OR p.status = :status", "true")
                + " GROUP BY p.status")
        .param("category", category)
        .query((rs, n) -> Map.entry(rs.getString("status"), rs.getInt("n")))
        .list()
        .forEach(e -> counts.put(e.getKey(), e.getValue()));
    return counts;
  }

  // ----------------------------------------------------------------- products

  /**
   * Finds a product of a partner by code.
   *
   * @param partnerId partner
   * @param code product code
   * @return the product
   */
  public Optional<ProductRow> product(UUID partnerId, String code) {
    return jdbc.sql(
            "SELECT id, partner_id, product_code, public_reference, product_type FROM integrations.partner_product"
                + " WHERE partner_id = :p AND product_code = :c")
        .param("p", partnerId)
        .param("c", code)
        .query(PartnerAccountRepository::product)
        .optional();
  }

  /**
   * Locks a product's partner-scoped identity row (serializes version creation per product).
   *
   * @param partnerId partner
   * @param code product code
   * @return the product
   */
  public Optional<ProductRow> lockProduct(UUID partnerId, String code) {
    return jdbc.sql(
            "SELECT id, partner_id, product_code, public_reference, product_type FROM integrations.partner_product"
                + " WHERE partner_id = :p AND product_code = :c FOR UPDATE")
        .param("p", partnerId)
        .param("c", code)
        .query(PartnerAccountRepository::product)
        .optional();
  }

  /**
   * Inserts a product identity.
   *
   * @param id product id
   * @param partnerId partner
   * @param code product code
   * @param publicReference public reference
   * @param type product type
   * @param at time
   */
  public void insertProduct(
      UUID id, UUID partnerId, String code, String publicReference, String type, Instant at) {
    jdbc.sql(
            """
            INSERT INTO integrations.partner_product (id, partner_id, product_code, public_reference, product_type, created_at)
            VALUES (:id, :p, :c, :ref, :t, :at)
            """)
        .param("id", id)
        .param("p", partnerId)
        .param("c", code)
        .param("ref", publicReference)
        .param("t", type)
        .param("at", java.sql.Timestamp.from(at))
        .update();
  }

  /**
   * Product summaries of a partner.
   *
   * @param partnerId partner
   * @return at most 200, by code
   */
  public List<SummaryRow> summaries(UUID partnerId) {
    return jdbc.sql(
            """
            SELECT x.id, x.partner_id, x.product_code, x.public_reference, x.product_type,
                   l.version AS latest_version, l.status AS latest_status, l.name_en, l.name_fr,
                   lv.version AS live_version, lv.status AS live_status
              FROM integrations.partner_product x
              JOIN LATERAL (SELECT v.version, v.status, v.name_en, v.name_fr
                              FROM integrations.partner_product_version v
                             WHERE v.product_id = x.id ORDER BY v.version DESC LIMIT 1) l ON true
              LEFT JOIN LATERAL (SELECT v.version, v.status FROM integrations.partner_product_version v
                                  WHERE v.product_id = x.id AND v.status IN ('ACTIVE', 'PAUSED') LIMIT 1) lv ON true
             WHERE x.partner_id = :p ORDER BY x.product_code LIMIT 200
            """)
        .param("p", partnerId)
        .query(
            (rs, n) ->
                new SummaryRow(
                    product(rs, n),
                    rs.getInt("latest_version"),
                    rs.getString("latest_status"),
                    (Integer) rs.getObject("live_version"),
                    rs.getString("live_status"),
                    new Bilingual(rs.getString("name_en"), rs.getString("name_fr"))))
        .list();
  }

  private static final String VERSION_COLUMNS =
      "v.id, v.product_id, v.version, v.status, v.row_version, v.name_en, v.name_fr, v.summary_en, v.summary_fr,"
          + " v.eligibility_en, v.eligibility_fr, v.disclosure_en, v.disclosure_fr, v.info_url, v.effective_date,"
          + " v.end_date, v.min_months, v.max_months, v.created_at, v.updated_at";

  /**
   * All versions of a product, newest first, with their countries and currencies.
   *
   * @param productId product
   * @return versions
   */
  public List<VersionRow> versions(UUID productId) {
    List<VersionRow> rows =
        jdbc.sql(
                "SELECT "
                    + VERSION_COLUMNS
                    + " FROM integrations.partner_product_version v WHERE v.product_id = :p ORDER BY v.version DESC")
            .param("p", productId)
            .query((rs, n) -> version(rs, List.of(), List.of()))
            .list();
    List<VersionRow> full = new ArrayList<>();
    for (VersionRow row : rows) {
      full.add(withChildren(row));
    }
    return full;
  }

  /**
   * Locks one version.
   *
   * @param productId product
   * @param version version number
   * @return the version
   */
  public Optional<VersionRow> lockVersion(UUID productId, int version) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM integrations.partner_product_version v WHERE v.product_id = :p AND v.version = :v FOR UPDATE")
        .param("p", productId)
        .param("v", version)
        .query((rs, n) -> version(rs, List.of(), List.of()))
        .optional()
        .map(this::withChildren);
  }

  /**
   * The latest version number of a product.
   *
   * @param productId product
   * @return the number, or 0
   */
  public int latestVersion(UUID productId) {
    return jdbc.sql(
            "SELECT coalesce(max(version), 0) FROM integrations.partner_product_version WHERE product_id = :p")
        .param("p", productId)
        .query(Integer.class)
        .single();
  }

  /**
   * Finds the live (ACTIVE or PAUSED) version of a product.
   *
   * @param productId product
   * @return the version number
   */
  public Optional<Integer> liveVersion(UUID productId) {
    return jdbc.sql(
            "SELECT version FROM integrations.partner_product_version WHERE product_id = :p AND status IN ('ACTIVE', 'PAUSED')")
        .param("p", productId)
        .query(Integer.class)
        .optional();
  }

  /**
   * Inserts a new draft version with its content.
   *
   * @param id version id
   * @param productId product
   * @param version number
   * @param content content
   * @param at time
   */
  public void insertDraft(UUID id, UUID productId, int version, Content content, Instant at) {
    jdbc.sql(
            """
            INSERT INTO integrations.partner_product_version (id, product_id, version, status, row_version,
                name_en, name_fr, summary_en, summary_fr, eligibility_en, eligibility_fr, disclosure_en, disclosure_fr,
                info_url, effective_date, end_date, min_months, max_months, created_at, updated_at)
            VALUES (:id, :p, :v, 'DRAFT', 0, :nen, :nfr, :sen, :sfr, :een, :efr, :den, :dfr, :url, :eff, :end, :min, :max, :at, :at)
            """)
        .param("id", id)
        .param("p", productId)
        .param("v", version)
        .param("nen", content.name().en())
        .param("nfr", content.name().fr())
        .param("sen", content.summary().en())
        .param("sfr", content.summary().fr())
        .param("een", content.eligibility().en())
        .param("efr", content.eligibility().fr())
        .param("den", content.disclosure().en())
        .param("dfr", content.disclosure().fr())
        .param("url", content.infoUrl())
        .param(
            "eff", content.effectiveDate() == null ? null : Date.valueOf(content.effectiveDate()))
        .param("end", content.endDate() == null ? null : Date.valueOf(content.endDate()))
        .param("min", content.minMonths())
        .param("max", content.maxMonths())
        .param("at", java.sql.Timestamp.from(at))
        .update();
    insertChildren(id, content);
  }

  /**
   * Replaces the content of a draft version.
   *
   * @param version the loaded version
   * @param content new content
   * @param at time
   * @return rows changed
   */
  public int replaceDraft(VersionRow version, Content content, Instant at) {
    jdbc.sql("DELETE FROM integrations.partner_product_country WHERE version_id = :id")
        .param("id", version.id())
        .update();
    jdbc.sql("DELETE FROM integrations.partner_product_currency WHERE version_id = :id")
        .param("id", version.id())
        .update();
    int changed =
        jdbc.sql(
                """
                UPDATE integrations.partner_product_version SET row_version = row_version + 1,
                    name_en = :nen, name_fr = :nfr, summary_en = :sen, summary_fr = :sfr, eligibility_en = :een,
                    eligibility_fr = :efr, disclosure_en = :den, disclosure_fr = :dfr, info_url = :url,
                    effective_date = :eff, end_date = :end, min_months = :min, max_months = :max, updated_at = :at
                 WHERE id = :id AND row_version = :rv AND status = 'DRAFT'
                """)
            .param("id", version.id())
            .param("rv", version.rowVersion())
            .param("nen", content.name().en())
            .param("nfr", content.name().fr())
            .param("sen", content.summary().en())
            .param("sfr", content.summary().fr())
            .param("een", content.eligibility().en())
            .param("efr", content.eligibility().fr())
            .param("den", content.disclosure().en())
            .param("dfr", content.disclosure().fr())
            .param("url", content.infoUrl())
            .param(
                "eff",
                content.effectiveDate() == null ? null : Date.valueOf(content.effectiveDate()))
            .param("end", content.endDate() == null ? null : Date.valueOf(content.endDate()))
            .param("min", content.minMonths())
            .param("max", content.maxMonths())
            .param("at", java.sql.Timestamp.from(at))
            .update();
    if (changed == 1) {
      insertChildren(version.id(), content);
    }
    return changed;
  }

  private void insertChildren(UUID versionId, Content content) {
    for (String country : content.countries()) {
      jdbc.sql(
              "INSERT INTO integrations.partner_product_country (version_id, country_code) VALUES (:v, :c)")
          .param("v", versionId)
          .param("c", country)
          .update();
    }
    for (CurrencyLimit limit : content.currencies()) {
      jdbc.sql(
              "INSERT INTO integrations.partner_product_currency (version_id, currency, min_amount, max_amount)"
                  + " VALUES (:v, :c, :min, :max)")
          .param("v", versionId)
          .param("c", limit.currency())
          .param("min", limit.minAmount() == null ? null : new BigDecimal(limit.minAmount()))
          .param("max", limit.maxAmount() == null ? null : new BigDecimal(limit.maxAmount()))
          .update();
    }
  }

  /**
   * Changes a version's status, advancing its row version.
   *
   * @param version the loaded version
   * @param status new status
   * @param at time
   * @return rows changed
   */
  public int updateVersionStatus(VersionRow version, String status, Instant at) {
    return jdbc.sql(
            """
            UPDATE integrations.partner_product_version SET status = :s, row_version = row_version + 1, updated_at = :at
             WHERE id = :id AND row_version = :rv
            """)
        .param("s", status)
        .param("at", java.sql.Timestamp.from(at))
        .param("id", version.id())
        .param("rv", version.rowVersion())
        .update();
  }

  /**
   * Appends a product event with the next sequence.
   *
   * @param productId product
   * @param version version number
   * @param type event type
   * @param from previous status or null
   * @param to resulting status
   * @param reason reason or null
   * @param note encrypted note or null
   * @param actor actor subject
   * @param at time
   * @return the sequence
   */
  public int appendProductEvent(
      UUID productId,
      int version,
      String type,
      String from,
      String to,
      String reason,
      byte[] note,
      String actor,
      Instant at) {
    int sequence =
        jdbc.sql(
                "SELECT coalesce(max(sequence), 0) + 1 FROM integrations.partner_product_event WHERE product_id = :p")
            .param("p", productId)
            .query(Integer.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO integrations.partner_product_event (product_id, sequence, version, event_type, from_status,
                to_status, reason_code, note_ciphertext, actor_subject, occurred_at)
            VALUES (:p, :seq, :v, :type, :from, :to, :reason, :note, :actor, :at)
            """)
        .param("p", productId)
        .param("seq", sequence)
        .param("v", version)
        .param("type", type)
        .param("from", from)
        .param("to", to)
        .param("reason", reason)
        .param("note", note)
        .param("actor", actor)
        .param("at", java.sql.Timestamp.from(at))
        .update();
    return sequence;
  }

  /**
   * Product events after a sequence.
   *
   * @param productId product
   * @param after last sequence read
   * @param limit rows
   * @return events oldest first
   */
  public List<ProductEventRow> productEvents(UUID productId, int after, int limit) {
    return jdbc.sql(
            """
            SELECT sequence, version, event_type, from_status, to_status, reason_code,
                   note_ciphertext IS NOT NULL AS noted, actor_subject, occurred_at
              FROM integrations.partner_product_event WHERE product_id = :p AND sequence > :after
             ORDER BY sequence LIMIT :limit
            """)
        .param("p", productId)
        .param("after", after)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new ProductEventRow(
                    rs.getInt("sequence"),
                    rs.getInt("version"),
                    rs.getString("event_type"),
                    rs.getString("from_status"),
                    rs.getString("to_status"),
                    rs.getString("reason_code"),
                    rs.getBoolean("noted"),
                    rs.getString("actor_subject"),
                    rs.getTimestamp("occurred_at").toInstant()))
        .list();
  }

  // ---------------------------------------------------------------- catalogue

  private static final String ELIGIBLE =
      """
      FROM integrations.partner_product_version v
      JOIN integrations.partner_product x ON x.id = v.product_id
      JOIN integrations.partner p ON p.id = x.partner_id
      WHERE v.status = 'ACTIVE' AND p.status = 'ACTIVE'
        AND v.effective_date <= :today AND (v.end_date IS NULL OR v.end_date >= :today)
        AND EXISTS (SELECT 1 FROM integrations.partner_product_country c
                     WHERE c.version_id = v.id AND c.country_code = :country)
      """;

  /**
   * Partners with eligible products for a country and date.
   *
   * @param country organization country
   * @param today UTC date
   * @return display name, categories, product count
   */
  public List<Object[]> eligiblePartners(String country, LocalDate today) {
    return jdbc.sql(
            "SELECT p.id, coalesce(p.trading_name, p.legal_name) AS display, p.primary_category, count(*) AS n "
                + ELIGIBLE
                + " GROUP BY p.id, p.trading_name, p.legal_name, p.primary_category"
                + " ORDER BY lower(coalesce(p.trading_name, p.legal_name)), p.id LIMIT 200")
        .param("country", country)
        .param("today", Date.valueOf(today))
        .query(
            (rs, n) ->
                new Object[] {
                  rs.getObject("id", UUID.class),
                  rs.getString("display"),
                  rs.getString("primary_category"),
                  rs.getInt("n")
                })
        .list();
  }

  /**
   * One page of eligible products ordered by public reference.
   *
   * @param country organization country
   * @param today UTC date
   * @param type product type filter or null
   * @param afterReference keyset or null
   * @param limit rows
   * @return rows
   */
  public List<CatalogueRow> eligibleProducts(
      String country, LocalDate today, String type, String afterReference, int limit) {
    return eligible(country, today, type, afterReference, null, limit);
  }

  private List<CatalogueRow> eligible(
      String country,
      LocalDate today,
      String type,
      String afterReference,
      String exactReference,
      int limit) {
    List<CatalogueRow> rows =
        jdbc.sql(
                "SELECT "
                    + VERSION_COLUMNS
                    + ", x.public_reference, x.product_type, coalesce(p.trading_name, p.legal_name) AS display "
                    + ELIGIBLE
                    + " AND (CAST(:type AS text) IS NULL OR x.product_type = :type)"
                    + " AND (CAST(:after AS text) IS NULL OR x.public_reference > :after)"
                    + " AND (CAST(:exact AS text) IS NULL OR x.public_reference = :exact)"
                    + " ORDER BY x.public_reference LIMIT :limit")
            .param("country", country)
            .param("today", Date.valueOf(today))
            .param("type", type)
            .param("after", afterReference)
            .param("exact", exactReference)
            .param("limit", limit)
            .query(
                (rs, n) ->
                    new CatalogueRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("public_reference"),
                        rs.getString("display"),
                        rs.getString("product_type"),
                        version(rs, List.of(), List.of()).content()))
            .list();
    return rows.stream().map(this::withChildren).toList();
  }

  /**
   * The public reference of a product version, used to resume a catalogue page.
   *
   * @param versionId version id
   * @return the public reference
   */
  public Optional<String> publicReferenceOf(UUID versionId) {
    return jdbc.sql(
            "SELECT x.public_reference FROM integrations.partner_product_version v"
                + " JOIN integrations.partner_product x ON x.id = v.product_id WHERE v.id = :v")
        .param("v", versionId)
        .query(String.class)
        .optional();
  }

  /**
   * One eligible product by public reference.
   *
   * @param country organization country
   * @param today UTC date
   * @param publicReference public reference
   * @return the row
   */
  public Optional<CatalogueRow> eligibleProduct(
      String country, LocalDate today, String publicReference) {
    return eligible(country, today, null, null, publicReference, 1).stream().findFirst();
  }

  // ------------------------------------------------------------------ mapping

  private CatalogueRow withChildren(CatalogueRow row) {
    Content content = children(row.versionId(), row.content());
    return new CatalogueRow(
        row.versionId(), row.publicReference(), row.partnerName(), row.productType(), content);
  }

  private VersionRow withChildren(VersionRow row) {
    return new VersionRow(
        row.id(),
        row.productId(),
        row.version(),
        row.status(),
        row.rowVersion(),
        children(row.id(), row.content()),
        row.createdAt(),
        row.updatedAt());
  }

  private Content children(UUID versionId, Content base) {
    List<String> countries =
        jdbc.sql(
                "SELECT country_code FROM integrations.partner_product_country WHERE version_id = :v ORDER BY country_code")
            .param("v", versionId)
            .query(String.class)
            .list();
    List<CurrencyLimit> currencies =
        jdbc.sql(
                "SELECT currency, min_amount, max_amount FROM integrations.partner_product_currency"
                    + " WHERE version_id = :v ORDER BY currency")
            .param("v", versionId)
            .query(
                (rs, n) ->
                    new CurrencyLimit(
                        rs.getString("currency").strip(),
                        amount(rs.getBigDecimal("min_amount")),
                        amount(rs.getBigDecimal("max_amount"))))
            .list();
    return new Content(
        base.name(),
        base.summary(),
        base.eligibility(),
        base.disclosure(),
        base.infoUrl(),
        base.effectiveDate(),
        base.endDate(),
        base.minMonths(),
        base.maxMonths(),
        countries,
        currencies);
  }

  private static String amount(BigDecimal value) {
    return value == null
        ? null
        : value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
  }

  private static PartnerRow partner(ResultSet rs, int n) throws SQLException {
    java.sql.Array languages = rs.getArray("supported_languages");
    List<String> codes = new ArrayList<>(List.of((String[]) languages.getArray()));
    return new PartnerRow(
        rs.getObject("id", UUID.class),
        rs.getString("reference"),
        rs.getObject("application_id", UUID.class),
        rs.getString("app_reference"),
        rs.getString("status"),
        rs.getInt("row_version"),
        rs.getString("primary_category"),
        rs.getString("legal_name"),
        rs.getString("trading_name"),
        rs.getString("country_of_registration").strip(),
        rs.getString("website"),
        codes,
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static ProductRow product(ResultSet rs, int n) throws SQLException {
    return new ProductRow(
        rs.getObject("id", UUID.class),
        rs.getObject("partner_id", UUID.class),
        rs.getString("product_code"),
        rs.getString("public_reference"),
        rs.getString("product_type"));
  }

  private static VersionRow version(
      ResultSet rs, List<String> countries, List<CurrencyLimit> currencies) throws SQLException {
    Date effective = rs.getDate("effective_date");
    Date end = rs.getDate("end_date");
    Content content =
        new Content(
            new Bilingual(rs.getString("name_en"), rs.getString("name_fr")),
            new Bilingual(rs.getString("summary_en"), rs.getString("summary_fr")),
            new Bilingual(rs.getString("eligibility_en"), rs.getString("eligibility_fr")),
            new Bilingual(rs.getString("disclosure_en"), rs.getString("disclosure_fr")),
            rs.getString("info_url"),
            effective == null ? null : effective.toLocalDate(),
            end == null ? null : end.toLocalDate(),
            (Integer) rs.getObject("min_months"),
            (Integer) rs.getObject("max_months"),
            countries,
            currencies);
    return new VersionRow(
        rs.getObject("id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getInt("version"),
        rs.getString("status"),
        rs.getInt("row_version"),
        content,
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
