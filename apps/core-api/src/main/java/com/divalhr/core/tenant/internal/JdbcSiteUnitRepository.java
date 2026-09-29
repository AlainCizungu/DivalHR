package com.divalhr.core.tenant.internal;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.domain.EffectivePeriod;
import com.divalhr.core.tenant.domain.SiteUnit;
import com.divalhr.core.tenant.domain.SiteUnitKind;
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
 * Department and cost-center persistence. Every method takes the verified {@link TenantId} and
 * every statement filters on it. Table and constraint names come only from {@link SiteUnitTable}.
 */
@Repository
public class JdbcSiteUnitRepository {

  private static final String COLUMNS =
      "id, tenant_id, site_id, code, name, effective_from, effective_to, created_at, created_by";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcSiteUnitRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a department or cost center. The composite foreign key and containment trigger back up
   * the service checks.
   *
   * @param tenant verified tenant (must own the unit)
   * @param unit department or cost center
   * @throws ApiException the kind's duplicate-code error for its own code index only
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(TenantId tenant, SiteUnit unit) {
    if (!tenant.equals(unit.tenantId())) {
      throw new IllegalArgumentException("unit tenant differs from the verified tenant");
    }
    SiteUnitTable table = SiteUnitTable.of(unit.kind());
    try {
      jdbc.sql(
              "INSERT INTO "
                  + table.table()
                  + " (id, tenant_id, site_id, code, name, effective_from, effective_to,"
                  + " created_at, created_by)"
                  + " VALUES (:id, :tenant, :site, :code, :name, :from, :to, :createdAt,"
                  + " :createdBy)")
          .param("id", unit.id())
          .param("tenant", tenant.value())
          .param("site", unit.siteId())
          .param("code", unit.code())
          .param("name", unit.name())
          .param("from", Date.valueOf(unit.period().from()))
          .param("to", unit.period().to() == null ? null : Date.valueOf(unit.period().to()))
          .param("createdAt", Timestamp.from(unit.createdAt()))
          .param("createdBy", unit.createdBy())
          .update();
    } catch (DuplicateKeyException duplicate) {
      if (UniqueViolations.constraint(duplicate)
          .filter(table.codeConstraint()::equals)
          .isPresent()) {
        throw new ApiException(duplicateCode(unit.kind()), Map.of("field", "code"));
      }
      throw duplicate;
    }
  }

  /**
   * Returns one page of a site's departments or cost centers in {@code (code, id)} byte order.
   *
   * @param tenant verified tenant
   * @param kind which table
   * @param siteId parent (already verified to belong to the tenant)
   * @param after continue after this row, or {@code null} for the first page
   * @param rows number of rows to fetch
   * @return rows
   */
  public List<SiteUnit> page(
      TenantId tenant, SiteUnitKind kind, UUID siteId, KeysetPosition after, int rows) {
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM "
            + SiteUnitTable.of(kind).table()
            + " WHERE tenant_id = :tenant AND site_id = :site"
            + (after == null
                ? ""
                : " AND (code COLLATE \"C\", id) > (CAST(:code AS text) COLLATE \"C\", :id)")
            + " ORDER BY code COLLATE \"C\", id LIMIT :rows";
    JdbcClient.StatementSpec statement =
        jdbc.sql(sql).param("tenant", tenant.value()).param("site", siteId).param("rows", rows);
    if (after != null) {
      statement = statement.param("code", after.code()).param("id", after.id());
    }
    return statement.query((rs, row) -> map(kind, rs)).list();
  }

  private static ErrorCode duplicateCode(SiteUnitKind kind) {
    return switch (kind) {
      case DEPARTMENT -> ErrorCode.DUPLICATE_DEPARTMENT_CODE;
      case COST_CENTER -> ErrorCode.DUPLICATE_COST_CENTER_CODE;
    };
  }

  private static SiteUnit map(SiteUnitKind kind, ResultSet rs) throws SQLException {
    Date to = rs.getDate("effective_to");
    return SiteUnit.of(
        kind,
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("site_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        new EffectivePeriod(
            rs.getDate("effective_from").toLocalDate(), to == null ? null : to.toLocalDate()),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("created_by"));
  }
}
