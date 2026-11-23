package com.divalhr.core.identity.internal;

import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the verified address of a membership (MVP-120F). Tenant-bound; the value is never logged.
 */
@Repository
public class JdbcMembershipContactRepository {

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcMembershipContactRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The membership's address.
   *
   * @param tenant verified tenant
   * @param membershipId membership
   * @return the address, or empty
   */
  public Optional<String> email(TenantId tenant, UUID membershipId) {
    return jdbc.sql(
            "SELECT email FROM identity.tenant_membership WHERE tenant_id = :tenant AND id = :id"
                + " AND email IS NOT NULL")
        .param("tenant", tenant.value())
        .param("id", membershipId)
        .query(String.class)
        .optional();
  }
}
