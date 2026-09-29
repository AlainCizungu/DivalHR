package com.divalhr.core.tenant.internal;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.domain.EffectivePeriod;
import com.divalhr.core.tenant.domain.Region;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Region persistence (MVP-002 Increment 3A). Every method takes the verified {@link TenantId} and
 * every statement filters on it.
 */
@Repository
public class JdbcRegionRepository {

  /** Unique index enforcing case-insensitive codes per tenant (V6). */
  static final String CODE_CONSTRAINT = "region_code_ci_unique";

  private static final String COLUMNS =
      "id, tenant_id, legal_entity_id, code, name, effective_from, effective_to, created_at,"
          + " created_by";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcRegionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a region, mapping a code collision to {@code DUPLICATE_REGION_CODE}.
   *
   * @param tenant verified tenant (must equal the region's tenant)
   * @param region region
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(TenantId tenant, Region region) {
    if (!tenant.equals(region.tenantId())) {
      throw new IllegalArgumentException("region tenant differs from the verified tenant");
    }
    try {
      jdbc.sql(
              """
              INSERT INTO tenant.region
                (id, tenant_id, legal_entity_id, code, name, effective_from, effective_to,
                 created_at, created_by)
              VALUES (:id, :tenant, :parent, :code, :name, :from, :to, :createdAt, :createdBy)
              """)
          .param("id", region.id())
          .param("tenant", tenant.value())
          .param("parent", region.legalEntityId())
          .param("code", region.code())
          .param("name", region.name())
          .param("from", Date.valueOf(region.period().from()))
          .param("to", region.period().to() == null ? null : Date.valueOf(region.period().to()))
          .param("createdAt", Timestamp.from(region.createdAt()))
          .param("createdBy", region.createdBy())
          .update();
    } catch (DuplicateKeyException duplicate) {
      if (UniqueViolations.constraint(duplicate).filter(CODE_CONSTRAINT::equals).isPresent()) {
        throw new ApiException(ErrorCode.DUPLICATE_REGION_CODE, Map.of("field", "code"));
      }
      throw duplicate;
    }
  }

  /**
   * Reads a region of the verified tenant and locks it {@code FOR SHARE} until the transaction
   * ends, so its period cannot change while a site is created in it or assigned to it. A region of
   * another tenant is not found.
   *
   * @param tenant verified tenant
   * @param id region id
   * @return the region, if it exists in this tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Region> findForShare(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM tenant.region WHERE tenant_id = :tenant AND id = :id FOR SHARE")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcRegionRepository::map)
        .optional();
  }

  /**
   * Lists one page of a legal entity's regions in (code, id) byte order, using the index {@code
   * region_tenant_legal_entity_code_id}.
   *
   * @param tenant verified tenant
   * @param legalEntityId parent legal entity
   * @param after keyset position to continue after, or {@code null} for the first page
   * @param rows maximum rows
   * @return rows
   */
  public List<Region> page(TenantId tenant, UUID legalEntityId, KeysetPosition after, int rows) {
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM tenant.region WHERE tenant_id = :tenant AND legal_entity_id = :parent"
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
    return statement.query(JdbcRegionRepository::map).list();
  }

  private static Region map(ResultSet rs, int row) throws SQLException {
    Date to = rs.getDate("effective_to");
    return new Region(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        new EffectivePeriod(
            rs.getDate("effective_from").toLocalDate(), to == null ? null : to.toLocalDate()),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("created_by"));
  }
}
