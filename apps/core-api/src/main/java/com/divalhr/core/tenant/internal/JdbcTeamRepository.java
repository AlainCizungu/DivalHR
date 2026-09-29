package com.divalhr.core.tenant.internal;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.domain.EffectivePeriod;
import com.divalhr.core.tenant.domain.Team;
import com.divalhr.core.tenant.domain.TeamParent;
import com.divalhr.core.tenant.domain.TeamParentKind;
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
 * Team persistence (MVP-002 Increment 3B). Every method takes the verified {@link TenantId} and
 * every statement filters on it. The parent table and column are chosen by an exhaustive switch
 * over the closed {@link TeamParentKind}; request text never reaches SQL.
 */
@Repository
public class JdbcTeamRepository {

  /** Unique index enforcing case-insensitive codes per tenant (V7). */
  static final String CODE_CONSTRAINT = "team_code_ci_unique";

  private static final String COLUMNS =
      "id, tenant_id, site_id, department_id, cost_center_id, code, name, effective_from,"
          + " effective_to, created_at, created_by";

  private static final String DEPARTMENT_FOR_SHARE =
      "SELECT id, site_id, effective_from, effective_to FROM tenant.department"
          + " WHERE tenant_id = :tenant AND id = :id FOR SHARE";
  private static final String COST_CENTER_FOR_SHARE =
      "SELECT id, site_id, effective_from, effective_to FROM tenant.cost_center"
          + " WHERE tenant_id = :tenant AND id = :id FOR SHARE";
  private static final String DEPARTMENT_EXISTS =
      "SELECT 1 FROM tenant.department WHERE tenant_id = :tenant AND id = :id";
  private static final String COST_CENTER_EXISTS =
      "SELECT 1 FROM tenant.cost_center WHERE tenant_id = :tenant AND id = :id";
  private static final String PAGE_BY_DEPARTMENT =
      "SELECT "
          + COLUMNS
          + " FROM tenant.team WHERE tenant_id = :tenant AND department_id = :parent";
  private static final String PAGE_BY_COST_CENTER =
      "SELECT "
          + COLUMNS
          + " FROM tenant.team WHERE tenant_id = :tenant AND cost_center_id = :parent";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcTeamRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Reads a department or cost center of the verified tenant and locks it {@code FOR SHARE} until
   * the transaction ends (parent before child). A parent of another tenant is not found.
   *
   * @param tenant verified tenant
   * @param kind parent type
   * @param id parent id
   * @return the parent with its site and period, if it exists in this tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<TeamParent> findParentForShare(TenantId tenant, TeamParentKind kind, UUID id) {
    String sql =
        switch (kind) {
          case DEPARTMENT -> DEPARTMENT_FOR_SHARE;
          case COST_CENTER -> COST_CENTER_FOR_SHARE;
        };
    return jdbc.sql(sql)
        .param("tenant", tenant.value())
        .param("id", id)
        .query(
            (rs, row) -> {
              Date to = rs.getDate("effective_to");
              return new TeamParent(
                  kind,
                  rs.getObject("id", UUID.class),
                  rs.getObject("site_id", UUID.class),
                  new EffectivePeriod(
                      rs.getDate("effective_from").toLocalDate(),
                      to == null ? null : to.toLocalDate()));
            })
        .optional();
  }

  /**
   * Whether a department or cost center exists in the tenant.
   *
   * @param tenant verified tenant
   * @param kind parent type
   * @param id parent id
   * @return true only for this tenant's parent
   */
  public boolean parentExists(TenantId tenant, TeamParentKind kind, UUID id) {
    String sql =
        switch (kind) {
          case DEPARTMENT -> DEPARTMENT_EXISTS;
          case COST_CENTER -> COST_CENTER_EXISTS;
        };
    return jdbc.sql(sql)
        .param("tenant", tenant.value())
        .param("id", id)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /**
   * Inserts a team, mapping a code collision to {@code DUPLICATE_TEAM_CODE}. The composite foreign
   * keys, the exactly-one-parent check and the containment trigger back up the service checks.
   *
   * @param tenant verified tenant (must equal the team's tenant)
   * @param team team
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(TenantId tenant, Team team) {
    if (!tenant.equals(team.tenantId())) {
      throw new IllegalArgumentException("team tenant differs from the verified tenant");
    }
    try {
      jdbc.sql(
              """
              INSERT INTO tenant.team
                (id, tenant_id, site_id, department_id, cost_center_id, code, name,
                 effective_from, effective_to, created_at, created_by)
              VALUES (:id, :tenant, :site, :department, :costCenter, :code, :name, :from, :to,
                      :createdAt, :createdBy)
              """)
          .param("id", team.id())
          .param("tenant", tenant.value())
          .param("site", team.siteId())
          .param("department", team.departmentId())
          .param("costCenter", team.costCenterId())
          .param("code", team.code())
          .param("name", team.name())
          .param("from", Date.valueOf(team.period().from()))
          .param("to", team.period().to() == null ? null : Date.valueOf(team.period().to()))
          .param("createdAt", Timestamp.from(team.createdAt()))
          .param("createdBy", team.createdBy())
          .update();
    } catch (DuplicateKeyException duplicate) {
      if (UniqueViolations.constraint(duplicate).filter(CODE_CONSTRAINT::equals).isPresent()) {
        throw new ApiException(ErrorCode.DUPLICATE_TEAM_CODE, Map.of("field", "code"));
      }
      throw duplicate;
    }
  }

  /**
   * Lists one page of a parent's teams in (code, id) byte order, using the partial index {@code
   * team_tenant_department_code_id} or {@code team_tenant_cost_center_code_id}.
   *
   * @param tenant verified tenant
   * @param kind parent type
   * @param parentId parent id (already verified to belong to the tenant)
   * @param after keyset position to continue after, or {@code null} for the first page
   * @param rows maximum rows
   * @return rows
   */
  public List<Team> page(
      TenantId tenant, TeamParentKind kind, UUID parentId, KeysetPosition after, int rows) {
    String base =
        switch (kind) {
          case DEPARTMENT -> PAGE_BY_DEPARTMENT;
          case COST_CENTER -> PAGE_BY_COST_CENTER;
        };
    String sql =
        base
            + (after == null
                ? ""
                : " AND (code COLLATE \"C\", id) > (CAST(:code AS text) COLLATE \"C\", :id)")
            + " ORDER BY code COLLATE \"C\", id LIMIT :rows";
    JdbcClient.StatementSpec statement =
        jdbc.sql(sql).param("tenant", tenant.value()).param("parent", parentId).param("rows", rows);
    if (after != null) {
      statement = statement.param("code", after.code()).param("id", after.id());
    }
    return statement.query(JdbcTeamRepository::map).list();
  }

  private static Team map(ResultSet rs, int row) throws SQLException {
    Date to = rs.getDate("effective_to");
    UUID department = rs.getObject("department_id", UUID.class);
    UUID costCenter = rs.getObject("cost_center_id", UUID.class);
    TeamParentKind kind =
        department != null ? TeamParentKind.DEPARTMENT : TeamParentKind.COST_CENTER;
    return new Team(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("site_id", UUID.class),
        kind,
        department != null ? department : costCenter,
        rs.getString("code"),
        rs.getString("name"),
        new EffectivePeriod(
            rs.getDate("effective_from").toLocalDate(), to == null ? null : to.toLocalDate()),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("created_by"));
  }
}
