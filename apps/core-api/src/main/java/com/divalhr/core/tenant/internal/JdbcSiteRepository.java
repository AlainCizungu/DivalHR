package com.divalhr.core.tenant.internal;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.domain.EffectivePeriod;
import com.divalhr.core.tenant.domain.Site;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Site persistence. Every method takes the verified {@link TenantId} and every statement filters on
 * it.
 */
@Repository
public class JdbcSiteRepository {

  /** Unique index enforcing case-insensitive codes per tenant (V3). */
  static final String CODE_CONSTRAINT = "site_code_ci_unique";

  private static final String COLUMNS =
      "id, tenant_id, legal_entity_id, code, name, timezone, effective_from, effective_to,"
          + " created_at, created_by";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcSiteRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a site. The composite foreign key and containment trigger back up the service checks.
   *
   * @param tenant verified tenant (must own the site)
   * @param site site
   * @throws ApiException {@code DUPLICATE_SITE_CODE} for this tenant's code index only
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(TenantId tenant, Site site) {
    if (!tenant.equals(site.tenantId())) {
      throw new IllegalArgumentException("site tenant differs from the verified tenant");
    }
    try {
      jdbc.sql(
              """
              INSERT INTO tenant.site
                (id, tenant_id, legal_entity_id, code, name, timezone, effective_from,
                 effective_to, created_at, created_by)
              VALUES (:id, :tenant, :parent, :code, :name, :timezone, :from, :to, :createdAt,
                      :createdBy)
              """)
          .param("id", site.id())
          .param("tenant", tenant.value())
          .param("parent", site.legalEntityId())
          .param("code", site.code())
          .param("name", site.name())
          .param("timezone", site.timezone())
          .param("from", Date.valueOf(site.period().from()))
          .param("to", site.period().to() == null ? null : Date.valueOf(site.period().to()))
          .param("createdAt", Timestamp.from(site.createdAt()))
          .param("createdBy", site.createdBy())
          .update();
    } catch (DuplicateKeyException duplicate) {
      if (UniqueViolations.constraint(duplicate).filter(CODE_CONSTRAINT::equals).isPresent()) {
        throw new ApiException(ErrorCode.DUPLICATE_SITE_CODE, Map.of("field", "code"));
      }
      throw duplicate;
    }
  }

  /**
   * Returns one page of a legal entity's sites in {@code (code, id)} byte order.
   *
   * @param tenant verified tenant
   * @param legalEntityId parent (already verified to belong to the tenant)
   * @param after continue after this row, or {@code null} for the first page
   * @param rows number of rows to fetch
   * @return rows
   */
  public List<Site> page(TenantId tenant, UUID legalEntityId, KeysetPosition after, int rows) {
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM tenant.site WHERE tenant_id = :tenant AND legal_entity_id = :parent"
            + (after == null
                ? ""
                : " AND (code COLLATE \"C\", id) > (CAST(:code AS text) COLLATE \"C\", :id)")
            + " ORDER BY code COLLATE \"C\", id LIMIT :rows";
    JdbcClient.StatementSpec statement =
        jdbc.sql(sql)
            .param("tenant", tenant.value())
            .param("parent", legalEntityId)
            .param("rows", rows);
    if (after != null) {
      statement = statement.param("code", after.code()).param("id", after.id());
    }
    return statement.query(JdbcSiteRepository::map).list();
  }

  private static Site map(ResultSet rs, int row) throws SQLException {
    Date to = rs.getDate("effective_to");
    return new Site(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("timezone"),
        new EffectivePeriod(
            rs.getDate("effective_from").toLocalDate(), to == null ? null : to.toLocalDate()),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("created_by"));
  }
}
