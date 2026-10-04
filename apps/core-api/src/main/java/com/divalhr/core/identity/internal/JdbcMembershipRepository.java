package com.divalhr.core.identity.internal;

import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.platform.tenancy.CrossTenantAccess;
import com.divalhr.core.platform.tenancy.MembershipAuthority.ActiveMembership;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that touches {@code identity.tenant_membership}. The stored address is confidential
 * (MVP-012A): it is written once, from the source invitation, and never logged.
 */
@Repository
public class JdbcMembershipRepository {

  /**
   * Whether a separation revoked the membership's DivalHR access (MVP-022, D22-7): a revocation
   * that is not cancelled and whose instant has passed on the database clock. It holds from that
   * instant, whatever the background worker or the identity provider has done since. Requires
   * {@code identity.tenant_membership} unaliased in the query.
   */
  static final String REVOKED =
      "EXISTS (SELECT 1 FROM identity.access_revocation access_revocation"
          + " WHERE access_revocation.membership_id = identity.tenant_membership.id"
          + " AND access_revocation.state <> 'CANCELLED'"
          + " AND access_revocation.effective_at <= statement_timestamp())";

  /**
   * The single definition of an active membership (MVP-012A, M1 and A5). Every query that decides
   * or reports tenant access includes it: the membership gate, {@code GET /session}, the MVP-014
   * bootstrap rule and the access review, so they can never disagree. MVP-022 narrows it, in this
   * one place, to memberships whose access no separation revoked.
   */
  static final String ACTIVE = "NOT " + REVOKED;

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
            "SELECT 1 FROM identity.tenant_membership"
                + " WHERE tenant_id = :tenant AND role = 'tenant-admin'"
                + " AND source_invitation_id IS DISTINCT FROM CAST(:excluded AS uuid)"
                + " AND "
                + ACTIVE
                + " LIMIT 1")
        .param("tenant", tenant.value())
        .param("excluded", excludingSourceInvitation)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /**
   * The subject's active membership (the subject is unique across tenants), for the membership gate
   * and the session. Reads committed state; nothing is cached.
   *
   * @param subject verified token subject
   * @return the membership, or empty
   */
  @CrossTenantAccess(
      "Authorization lookup before any tenant is trusted: the subject is unique across tenants and"
          + " the caller compares the returned tenant with the token's verified tenant")
  public Optional<ActiveMembership> findActiveBySubject(String subject) {
    return jdbc.sql(
            "SELECT tenant_id, role FROM identity.tenant_membership WHERE subject = :subject AND "
                + ACTIVE)
        .param("subject", subject)
        .query(
            (row, number) ->
                new ActiveMembership(
                    new TenantId(row.getObject("tenant_id", UUID.class)), row.getString("role")))
        .optional();
  }

  /**
   * Inserts the membership created by an accepted invitation.
   *
   * @param tenant the invitation's tenant
   * @param id membership id
   * @param subject identity-provider subject
   * @param role the invitation's role
   * @param email the invitation's validated address (stored, confidential)
   * @param emailLookup the lookup of that same address
   * @param sourceInvitationId the invitation
   * @param createdAt acceptance time
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(
      TenantId tenant,
      UUID id,
      String subject,
      TenantRole role,
      EmailAddress email,
      byte[] emailLookup,
      UUID sourceInvitationId,
      Instant createdAt) {
    jdbc.sql(
            """
            INSERT INTO identity.tenant_membership
              (id, tenant_id, subject, role, email, email_lookup, source_invitation_id,
               created_at)
            VALUES (:id, :tenant, :subject, :role, :email, :lookup, :source, :createdAt)
            """)
        .param("id", id)
        .param("tenant", tenant.value())
        .param("subject", subject)
        .param("role", role.wireName())
        .param("email", email.value())
        .param("lookup", emailLookup)
        .param("source", sourceInvitationId)
        .param("createdAt", Timestamp.from(createdAt))
        .update();
  }

  /**
   * DEVELOPMENT ONLY: inserts a seed membership without a source invitation and without an address
   * (V10 rejects unanchored addresses). Idempotent.
   *
   * @param tenant seed tenant
   * @param id fixed membership id
   * @param subject fixed realm user id
   * @param role tenant role
   * @param emailLookup lookup of the published seed address
   * @param createdAt seed time
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertDevelopmentSeed(
      TenantId tenant,
      UUID id,
      String subject,
      TenantRole role,
      byte[] emailLookup,
      Instant createdAt) {
    jdbc.sql(
            """
            INSERT INTO identity.tenant_membership
              (id, tenant_id, subject, role, email_lookup, created_at)
            VALUES (:id, :tenant, :subject, :role, :lookup, :createdAt)
            ON CONFLICT DO NOTHING
            """)
        .param("id", id)
        .param("tenant", tenant.value())
        .param("subject", subject)
        .param("role", role.wireName())
        .param("lookup", emailLookup)
        .param("createdAt", Timestamp.from(createdAt))
        .update();
  }
}
