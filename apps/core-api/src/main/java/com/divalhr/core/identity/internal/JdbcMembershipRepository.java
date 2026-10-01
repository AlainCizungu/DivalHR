package com.divalhr.core.identity.internal;

import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The only code that touches {@code identity.tenant_membership}. Stores no email address. */
@Repository
public class JdbcMembershipRepository {

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcMembershipRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Whether the address already belongs to a member of the tenant. Only the caller's own tenant is
   * ever consulted, so the answer reveals nothing about other tenants.
   *
   * @param tenant verified tenant
   * @param emailLookup address lookup
   * @return true when a member of the tenant has the address
   */
  public boolean memberExists(TenantId tenant, byte[] emailLookup) {
    return jdbc.sql(
            """
            SELECT 1 FROM identity.tenant_membership
            WHERE tenant_id = :tenant AND email_lookup = :lookup
            """)
        .param("tenant", tenant.value())
        .param("lookup", emailLookup)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /**
   * Whether the tenant has a tenant-admin membership other than one created by the given invitation
   * (MVP-014 bootstrap rule; the caller holds the organization lock).
   *
   * @param tenant target tenant
   * @param excludingSourceInvitation an invitation whose own membership does not count, or null
   * @return true when another tenant administrator exists
   */
  public boolean tenantAdminExists(TenantId tenant, UUID excludingSourceInvitation) {
    return jdbc.sql(
            """
            SELECT 1 FROM identity.tenant_membership
            WHERE tenant_id = :tenant AND role = 'tenant-admin'
              AND source_invitation_id IS DISTINCT FROM CAST(:excluded AS uuid)
            LIMIT 1
            """)
        .param("tenant", tenant.value())
        .param("excluded", excludingSourceInvitation)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /**
   * Inserts the membership created by an accepted invitation.
   *
   * @param tenant the invitation's tenant
   * @param id membership id
   * @param subject identity-provider subject
   * @param role the invitation's role
   * @param emailLookup the invitation's address lookup
   * @param sourceInvitationId the invitation
   * @param createdAt acceptance time
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(
      TenantId tenant,
      UUID id,
      String subject,
      TenantRole role,
      byte[] emailLookup,
      UUID sourceInvitationId,
      Instant createdAt) {
    jdbc.sql(
            """
            INSERT INTO identity.tenant_membership
              (id, tenant_id, subject, role, email_lookup, source_invitation_id, created_at)
            VALUES (:id, :tenant, :subject, :role, :lookup, :source, :createdAt)
            """)
        .param("id", id)
        .param("tenant", tenant.value())
        .param("subject", subject)
        .param("role", role.wireName())
        .param("lookup", emailLookup)
        .param("source", sourceInvitationId)
        .param("createdAt", Timestamp.from(createdAt))
        .update();
  }
}
