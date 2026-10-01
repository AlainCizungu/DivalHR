package com.divalhr.core.identity.internal;

import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only access-review queries on {@code identity.tenant_membership} (MVP-012B). Every query is
 * bound to the verified tenant and uses the single active-membership predicate shared with the
 * membership gate ({@link JdbcMembershipRepository#ACTIVE}), so the review, its summary and the
 * gate can never disagree. Results contain confidential addresses and are never logged.
 */
@Repository
public class JdbcAccessReviewRepository {

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcAccessReviewRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One review row.
   *
   * @param id membership id
   * @param email confidential address, or null when not recorded
   * @param role tenant role
   * @param grantedAt membership creation
   */
  public record Row(UUID id, String email, TenantRole role, Instant grantedAt) {}

  /**
   * Members newest grant first, strictly after a keyset position.
   *
   * @param tenant verified tenant
   * @param role role filter or null
   * @param afterGrantedAt keyset time or null for the first page
   * @param afterId keyset id or null for the first page
   * @param rows maximum rows
   * @return rows in {@code created_at DESC, id DESC} order
   */
  public List<Row> page(
      TenantId tenant, TenantRole role, Instant afterGrantedAt, UUID afterId, int rows) {
    String roleFilter = role == null ? "" : " AND role = :role";
    String keyset = afterId == null ? "" : " AND (created_at, id) < (:afterGrantedAt, :afterId)";
    JdbcClient.StatementSpec spec =
        jdbc.sql(
                "SELECT id, email, role, created_at FROM identity.tenant_membership"
                    + " WHERE tenant_id = :tenant AND "
                    + JdbcMembershipRepository.ACTIVE
                    + roleFilter
                    + keyset
                    + " ORDER BY created_at DESC, id DESC LIMIT :rows")
            .param("tenant", tenant.value())
            .param("rows", rows);
    if (role != null) {
      spec = spec.param("role", role.wireName());
    }
    if (afterId != null) {
      spec = spec.param("afterGrantedAt", Timestamp.from(afterGrantedAt)).param("afterId", afterId);
    }
    return spec.query(JdbcAccessReviewRepository::map).list();
  }

  /**
   * The member with exactly this address lookup in the tenant.
   *
   * @param tenant verified tenant
   * @param emailLookup keyed lookup of the normalized address
   * @return the member, or empty
   */
  public Optional<Row> findByLookup(TenantId tenant, byte[] emailLookup) {
    return jdbc.sql(
            "SELECT id, email, role, created_at FROM identity.tenant_membership"
                + " WHERE tenant_id = :tenant AND email_lookup = :lookup AND "
                + JdbcMembershipRepository.ACTIVE)
        .param("tenant", tenant.value())
        .param("lookup", emailLookup)
        .query(JdbcAccessReviewRepository::map)
        .optional();
  }

  /**
   * Active members per role.
   *
   * @param tenant verified tenant
   * @param role role
   * @return count
   */
  public long count(TenantId tenant, TenantRole role) {
    Long count =
        jdbc.sql(
                "SELECT count(*) FROM identity.tenant_membership"
                    + " WHERE tenant_id = :tenant AND role = :role AND "
                    + JdbcMembershipRepository.ACTIVE)
            .param("tenant", tenant.value())
            .param("role", role.wireName())
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  private static Row map(ResultSet row, int number) throws SQLException {
    return new Row(
        row.getObject("id", UUID.class),
        row.getString("email"),
        TenantRole.fromWire(row.getString("role")).orElseThrow(),
        row.getTimestamp("created_at").toInstant());
  }
}
