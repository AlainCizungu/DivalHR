package com.divalhr.core.tenant.internal;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.domain.EffectivePeriod;
import com.divalhr.core.tenant.domain.LegalEntity;
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
 * Legal-entity persistence. Every method takes the verified {@link TenantId} and every statement
 * filters on it; a row of another tenant is never read, locked or listed.
 */
@Repository
public class JdbcLegalEntityRepository {

  /** Unique index enforcing case-insensitive codes per tenant (V3). */
  static final String CODE_CONSTRAINT = "legal_entity_code_ci_unique";

  private static final String COLUMNS =
      "id, tenant_id, code, name, country_code, effective_from, effective_to, created_at,"
          + " created_by";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcLegalEntityRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a legal entity.
   *
   * @param tenant verified tenant (must own the entity)
   * @param entity entity
   * @throws ApiException {@code DUPLICATE_LEGAL_ENTITY_CODE} for this tenant's code index only
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(TenantId tenant, LegalEntity entity) {
    if (!tenant.equals(entity.tenantId())) {
      throw new IllegalArgumentException("entity tenant differs from the verified tenant");
    }
    try {
      jdbc.sql(
              """
              INSERT INTO tenant.legal_entity
                (id, tenant_id, code, name, country_code, effective_from, effective_to,
                 created_at, created_by)
              VALUES (:id, :tenant, :code, :name, :country, :from, :to, :createdAt, :createdBy)
              """)
          .param("id", entity.id())
          .param("tenant", tenant.value())
          .param("code", entity.code())
          .param("name", entity.name())
          .param("country", entity.countryCode())
          .param("from", Date.valueOf(entity.period().from()))
          .param("to", entity.period().to() == null ? null : Date.valueOf(entity.period().to()))
          .param("createdAt", Timestamp.from(entity.createdAt()))
          .param("createdBy", entity.createdBy())
          .update();
    } catch (DuplicateKeyException duplicate) {
      if (UniqueViolations.constraint(duplicate).filter(CODE_CONSTRAINT::equals).isPresent()) {
        throw new ApiException(ErrorCode.DUPLICATE_LEGAL_ENTITY_CODE, Map.of("field", "code"));
      }
      throw duplicate;
    }
  }

  /**
   * Finds a legal entity of the tenant and locks it {@code FOR SHARE} for the rest of the
   * transaction (site containment check). Missing and foreign rows are indistinguishable.
   *
   * @param tenant verified tenant
   * @param id legal entity id
   * @return the entity if it exists in this tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<LegalEntity> findForShare(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM tenant.legal_entity WHERE tenant_id = :tenant AND id = :id FOR SHARE")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcLegalEntityRepository::map)
        .optional();
  }

  /**
   * Whether a legal entity exists in the tenant.
   *
   * @param tenant verified tenant
   * @param id legal entity id
   * @return true only for this tenant's entity
   */
  public boolean exists(TenantId tenant, UUID id) {
    return jdbc.sql("SELECT 1 FROM tenant.legal_entity WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /**
   * Returns one page in {@code (code, id)} byte order after a keyset position.
   *
   * @param tenant verified tenant
   * @param after continue after this row, or {@code null} for the first page
   * @param rows number of rows to fetch
   * @return rows
   */
  public List<LegalEntity> page(TenantId tenant, KeysetPosition after, int rows) {
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM tenant.legal_entity WHERE tenant_id = :tenant"
            + (after == null
                ? ""
                : " AND (code COLLATE \"C\", id) > (CAST(:code AS text) COLLATE \"C\", :id)")
            + " ORDER BY code COLLATE \"C\", id LIMIT :rows";
    JdbcClient.StatementSpec statement =
        jdbc.sql(sql).param("tenant", tenant.value()).param("rows", rows);
    if (after != null) {
      statement = statement.param("code", after.code()).param("id", after.id());
    }
    return statement.query(JdbcLegalEntityRepository::map).list();
  }

  private static LegalEntity map(ResultSet rs, int row) throws SQLException {
    Date to = rs.getDate("effective_to");
    return new LegalEntity(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("country_code"),
        new EffectivePeriod(
            rs.getDate("effective_from").toLocalDate(), to == null ? null : to.toLocalDate()),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("created_by"));
  }
}
