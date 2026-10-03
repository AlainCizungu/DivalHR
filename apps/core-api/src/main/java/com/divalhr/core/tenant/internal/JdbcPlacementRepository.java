package com.divalhr.core.tenant.internal;

import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.PlacementUnit;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Batched, read-only resolution of organizational units by code (MVP-020, E13). Every method takes
 * the verified {@link TenantId} and filters on it; codes are matched through the case-insensitive
 * unique indexes. Names are never read.
 */
@Repository
public class JdbcPlacementRepository {

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcPlacementRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Legal entities by code.
   *
   * @param tenant verified tenant
   * @param codes upper-case codes
   * @return units by code
   */
  public Map<String, PlacementUnit> legalEntities(TenantId tenant, Set<String> codes) {
    return query(
        tenant,
        codes,
        "SELECT id, upper(code) AS code, effective_from, effective_to, NULL::uuid AS le,"
            + " NULL::uuid AS site, NULL::uuid AS dept, NULL::uuid AS cc"
            + " FROM tenant.legal_entity WHERE tenant_id = :tenant AND upper(code) = ANY(:codes)");
  }

  /**
   * Sites by code, with their legal entity.
   *
   * @param tenant verified tenant
   * @param codes upper-case codes
   * @return units by code
   */
  public Map<String, PlacementUnit> sites(TenantId tenant, Set<String> codes) {
    return query(
        tenant,
        codes,
        "SELECT id, upper(code) AS code, effective_from, effective_to, legal_entity_id AS le,"
            + " NULL::uuid AS site, NULL::uuid AS dept, NULL::uuid AS cc"
            + " FROM tenant.site WHERE tenant_id = :tenant AND upper(code) = ANY(:codes)");
  }

  /**
   * Departments by code, with their site.
   *
   * @param tenant verified tenant
   * @param codes upper-case codes
   * @return units by code
   */
  public Map<String, PlacementUnit> departments(TenantId tenant, Set<String> codes) {
    return query(
        tenant,
        codes,
        "SELECT id, upper(code) AS code, effective_from, effective_to, NULL::uuid AS le,"
            + " site_id AS site, NULL::uuid AS dept, NULL::uuid AS cc"
            + " FROM tenant.department WHERE tenant_id = :tenant AND upper(code) = ANY(:codes)");
  }

  /**
   * Cost centers by code, with their site.
   *
   * @param tenant verified tenant
   * @param codes upper-case codes
   * @return units by code
   */
  public Map<String, PlacementUnit> costCenters(TenantId tenant, Set<String> codes) {
    return query(
        tenant,
        codes,
        "SELECT id, upper(code) AS code, effective_from, effective_to, NULL::uuid AS le,"
            + " site_id AS site, NULL::uuid AS dept, NULL::uuid AS cc"
            + " FROM tenant.cost_center WHERE tenant_id = :tenant AND upper(code) = ANY(:codes)");
  }

  /**
   * Teams by code, with their site and parent.
   *
   * @param tenant verified tenant
   * @param codes upper-case codes
   * @return units by code
   */
  public Map<String, PlacementUnit> teams(TenantId tenant, Set<String> codes) {
    return query(
        tenant,
        codes,
        "SELECT id, upper(code) AS code, effective_from, effective_to, NULL::uuid AS le,"
            + " site_id AS site, department_id AS dept, cost_center_id AS cc"
            + " FROM tenant.team WHERE tenant_id = :tenant AND upper(code) = ANY(:codes)");
  }

  private Map<String, PlacementUnit> query(TenantId tenant, Set<String> codes, String sql) {
    Map<String, PlacementUnit> units = new HashMap<>();
    if (codes.isEmpty()) {
      return units;
    }
    jdbc.sql(sql)
        .param("tenant", tenant.value())
        .param("codes", codes.toArray(String[]::new))
        .query((ResultSet rs, int row) -> units.put(rs.getString("code"), unit(rs)))
        .list();
    return units;
  }

  private static PlacementUnit unit(ResultSet rs) throws SQLException {
    LocalDate to = rs.getObject("effective_to", LocalDate.class);
    return new PlacementUnit(
        rs.getObject("id", UUID.class),
        rs.getObject("effective_from", LocalDate.class),
        to,
        rs.getObject("le", UUID.class),
        rs.getObject("site", UUID.class),
        rs.getObject("dept", UUID.class),
        rs.getObject("cc", UUID.class));
  }
}
