package com.divalhr.core.documents.internal;

import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract templates and their versions (MVP-030), always bound to the verified tenant. Titles and
 * bodies are Confidential organization text: never logged. The V16 guards make approved text
 * immutable whatever the caller does.
 */
@Repository
public class JdbcContractTemplateRepository {

  private static final String TEMPLATE_COLUMNS =
      "id, code, name, contract_type, created_at, version";

  private static final String VERSION_COLUMNS =
      "id, template_id, locale, version_number, state, title, body, placeholders, body_sha256,"
          + " created_at, updated_at, approved_at, retired_at, version";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcContractTemplateRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A template identity.
   *
   * @param id template
   * @param code tenant-unique code
   * @param name administrative label
   * @param contractType closed type code
   * @param createdAt creation time
   * @param version row version
   */
  public record TemplateRow(
      UUID id, String code, String name, String contractType, Instant createdAt, long version) {}

  /**
   * A template version.
   *
   * @param id version
   * @param templateId template
   * @param locale language
   * @param number version number per language
   * @param state DRAFT, APPROVED or RETIRED
   * @param title title
   * @param body grammar v1 body
   * @param placeholders distinct placeholder keys
   * @param bodySha256 text digest
   * @param createdAt creation
   * @param updatedAt last draft edit
   * @param approvedAt approval, or {@code null}
   * @param retiredAt retirement, or {@code null}
   * @param version row version
   */
  public record VersionRow(
      UUID id,
      UUID templateId,
      String locale,
      int number,
      String state,
      String title,
      String body,
      List<String> placeholders,
      String bodySha256,
      Instant createdAt,
      Instant updatedAt,
      Instant approvedAt,
      Instant retiredAt,
      long version) {

    /** Copies the placeholders. */
    public VersionRow {
      placeholders = List.copyOf(placeholders);
    }

    @Override
    public String toString() {
      return "VersionRow[" + id + "]";
    }
  }

  /**
   * Inserts a template.
   *
   * @param tenant verified tenant
   * @param row template
   * @param by verified subject
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertTemplate(TenantId tenant, TemplateRow row, String by) {
    jdbc.sql(
            "INSERT INTO documents.contract_template (id, tenant_id, code, name, contract_type,"
                + " created_at, created_by) VALUES (:id, :tenant, :code, :name, :type, :at, :by)")
        .param("id", row.id())
        .param("tenant", tenant.value())
        .param("code", row.code())
        .param("name", row.name())
        .param("type", row.contractType())
        .param("at", Timestamp.from(row.createdAt()))
        .param("by", by)
        .update();
  }

  /**
   * One template of the tenant.
   *
   * @param tenant verified tenant
   * @param id template
   * @return the template, if in the tenant
   */
  public Optional<TemplateRow> template(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + TEMPLATE_COLUMNS
                + " FROM documents.contract_template WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcContractTemplateRepository::template)
        .optional();
  }

  /**
   * A page of templates by code.
   *
   * @param tenant verified tenant
   * @param afterCode keyset code, or {@code null}
   * @param limit rows to fetch
   * @return templates
   */
  public List<TemplateRow> templatePage(TenantId tenant, String afterCode, int limit) {
    return jdbc.sql(
            "SELECT "
                + TEMPLATE_COLUMNS
                + " FROM documents.contract_template WHERE tenant_id = :tenant"
                + " AND (CAST(:after AS text) IS NULL OR code > CAST(:after AS text))"
                + " ORDER BY code LIMIT :limit")
        .param("tenant", tenant.value())
        .param("after", afterCode)
        .param("limit", limit)
        .query(JdbcContractTemplateRepository::template)
        .list();
  }

  /**
   * Versions of templates of the tenant, newest first per language.
   *
   * @param tenant verified tenant
   * @param templateIds templates
   * @return versions
   */
  public List<VersionRow> versions(TenantId tenant, Collection<UUID> templateIds) {
    if (templateIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM documents.contract_template_version WHERE tenant_id = :tenant"
                + " AND template_id = ANY(:ids) ORDER BY template_id, locale, version_number DESC")
        .param("tenant", tenant.value())
        .param("ids", templateIds.toArray(UUID[]::new))
        .query(JdbcContractTemplateRepository::version)
        .list();
  }

  /**
   * One version of one template of the tenant.
   *
   * @param tenant verified tenant
   * @param templateId template
   * @param versionId version
   * @param lock whether to lock it {@code FOR UPDATE}
   * @return the version, if it belongs to that template of the tenant
   */
  public Optional<VersionRow> version(
      TenantId tenant, UUID templateId, UUID versionId, boolean lock) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM documents.contract_template_version WHERE tenant_id = :tenant"
                + " AND template_id = :template AND id = :id"
                + (lock ? " FOR UPDATE" : ""))
        .param("tenant", tenant.value())
        .param("template", templateId)
        .param("id", versionId)
        .query(JdbcContractTemplateRepository::version)
        .optional();
  }

  /**
   * A version of the tenant by ID alone, with its template's type (issue and preview).
   *
   * @param tenant verified tenant
   * @param versionId version
   * @param lock whether to lock it {@code FOR SHARE} (a retirement waits for the issue)
   * @return the version and its template, if in the tenant
   */
  public Optional<IssuableVersion> issuable(TenantId tenant, UUID versionId, boolean lock) {
    return jdbc.sql(
            "SELECT v.id, v.template_id, v.locale, v.version_number, v.state, v.title, v.body,"
                + " v.placeholders, v.body_sha256, v.created_at, v.updated_at, v.approved_at,"
                + " v.retired_at, v.version, t.contract_type, t.code"
                + " FROM documents.contract_template_version v"
                + " JOIN documents.contract_template t ON t.tenant_id = v.tenant_id"
                + " AND t.id = v.template_id"
                + " WHERE v.tenant_id = :tenant AND v.id = :id"
                + (lock ? " FOR SHARE OF v" : ""))
        .param("tenant", tenant.value())
        .param("id", versionId)
        .query(
            (rs, n) ->
                new IssuableVersion(
                    version(rs, n), rs.getString("contract_type"), rs.getString("code")))
        .optional();
  }

  /**
   * A version with its template's contract type and code.
   *
   * @param version version
   * @param contractType template type
   * @param templateCode template code
   */
  public record IssuableVersion(VersionRow version, String contractType, String templateCode) {}

  /**
   * The next version number of a template's language.
   *
   * @param tenant verified tenant
   * @param templateId template
   * @param locale language
   * @return 1 + the highest number so far
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int nextNumber(TenantId tenant, UUID templateId, String locale) {
    Integer highest =
        jdbc.sql(
                "SELECT max(version_number) FROM documents.contract_template_version"
                    + " WHERE tenant_id = :tenant AND template_id = :template AND locale = :locale")
            .param("tenant", tenant.value())
            .param("template", templateId)
            .param("locale", locale)
            .query(Integer.class)
            .optional()
            .orElse(null);
    return highest == null ? 1 : highest + 1;
  }

  /**
   * Locks the template row {@code FOR UPDATE}: version creation of one template is serialized.
   *
   * @param tenant verified tenant
   * @param templateId template
   * @return whether the template exists in the tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean lockTemplate(TenantId tenant, UUID templateId) {
    return jdbc.sql(
            "SELECT id FROM documents.contract_template WHERE tenant_id = :tenant AND id = :id"
                + " FOR NO KEY UPDATE")
        .param("tenant", tenant.value())
        .param("id", templateId)
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  /**
   * Inserts a draft version.
   *
   * @param tenant verified tenant
   * @param row the draft
   * @param by verified subject
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertDraft(TenantId tenant, VersionRow row, String by) {
    jdbc.sql(
            "INSERT INTO documents.contract_template_version (id, tenant_id, template_id, locale,"
                + " version_number, state, title, body, placeholders, body_sha256,"
                + " grammar_version, digest_version, created_at, created_by, updated_at,"
                + " updated_by) VALUES (:id, :tenant, :template, :locale, :number, 'DRAFT',"
                + " :title, :body, :placeholders, :sha, 1, 1, :at, :by, :at, :by)")
        .param("id", row.id())
        .param("tenant", tenant.value())
        .param("template", row.templateId())
        .param("locale", row.locale())
        .param("number", row.number())
        .param("title", row.title())
        .param("body", row.body())
        .param("placeholders", row.placeholders().toArray(String[]::new))
        .param("sha", row.bodySha256())
        .param("at", Timestamp.from(row.createdAt()))
        .param("by", by)
        .update();
  }

  /**
   * Replaces a draft's text.
   *
   * @param tenant verified tenant
   * @param id version
   * @param version expected row version
   * @param title title
   * @param body body
   * @param placeholders placeholder keys
   * @param sha text digest
   * @param at edit time
   * @param by verified subject
   * @return rows updated
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int updateDraft(
      TenantId tenant,
      UUID id,
      long version,
      String title,
      String body,
      List<String> placeholders,
      String sha,
      Instant at,
      String by) {
    return jdbc.sql(
            "UPDATE documents.contract_template_version SET title = :title, body = :body,"
                + " placeholders = :placeholders, body_sha256 = :sha, updated_at = :at,"
                + " updated_by = :by, version = version + 1 WHERE tenant_id = :tenant AND id = :id"
                + " AND version = :version AND state = 'DRAFT'")
        .param("title", title)
        .param("body", body)
        .param("placeholders", placeholders.toArray(String[]::new))
        .param("sha", sha)
        .param("at", Timestamp.from(at))
        .param("by", by)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("version", version)
        .update();
  }

  /**
   * Deletes a never-approved draft.
   *
   * @param tenant verified tenant
   * @param id version
   * @param version expected row version
   * @return rows deleted
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int deleteDraft(TenantId tenant, UUID id, long version) {
    return jdbc.sql(
            "DELETE FROM documents.contract_template_version WHERE tenant_id = :tenant AND id = :id"
                + " AND version = :version AND state = 'DRAFT'")
        .param("tenant", tenant.value())
        .param("id", id)
        .param("version", version)
        .update();
  }

  /**
   * Moves a version forward: DRAFT to APPROVED, or APPROVED to RETIRED.
   *
   * @param tenant verified tenant
   * @param id version
   * @param version expected row version
   * @param to APPROVED or RETIRED
   * @param at transition time
   * @param by verified subject
   * @return rows updated
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int transition(TenantId tenant, UUID id, long version, String to, Instant at, String by) {
    String set =
        "APPROVED".equals(to)
            ? "state = 'APPROVED', approved_at = :at, approved_by = :by"
            : "state = 'RETIRED', retired_at = :at, retired_by = :by";
    String from = "APPROVED".equals(to) ? "DRAFT" : "APPROVED";
    return jdbc.sql(
            "UPDATE documents.contract_template_version SET "
                + set
                + ", version = version + 1 WHERE tenant_id = :tenant AND id = :id"
                + " AND version = :version AND state = '"
                + from
                + "'")
        .param("at", Timestamp.from(at))
        .param("by", by)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("version", version)
        .update();
  }

  /**
   * The approved version of a template's language, locked {@code FOR UPDATE}.
   *
   * @param tenant verified tenant
   * @param templateId template
   * @param locale language
   * @return the approved version, if any
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<VersionRow> lockApproved(TenantId tenant, UUID templateId, String locale) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM documents.contract_template_version WHERE tenant_id = :tenant"
                + " AND template_id = :template AND locale = :locale AND state = 'APPROVED'"
                + " FOR UPDATE")
        .param("tenant", tenant.value())
        .param("template", templateId)
        .param("locale", locale)
        .query(JdbcContractTemplateRepository::version)
        .optional();
  }

  private static TemplateRow template(ResultSet rs, int n) throws SQLException {
    return new TemplateRow(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("contract_type"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getLong("version"));
  }

  private static VersionRow version(ResultSet rs, int n) throws SQLException {
    Array array = rs.getArray("placeholders");
    String[] keys = array == null ? new String[0] : (String[]) array.getArray();
    return new VersionRow(
        rs.getObject("id", UUID.class),
        rs.getObject("template_id", UUID.class),
        rs.getString("locale"),
        rs.getInt("version_number"),
        rs.getString("state"),
        rs.getString("title"),
        rs.getString("body"),
        Arrays.asList(keys),
        rs.getString("body_sha256"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        instant(rs, "approved_at"),
        instant(rs, "retired_at"),
        rs.getLong("version"));
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
