package com.divalhr.core.identity.internal;

import com.divalhr.core.identity.domain.DeliveryState;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.domain.InvitationLocale;
import com.divalhr.core.identity.domain.InvitationState;
import com.divalhr.core.identity.domain.InvitationStatus;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.platform.tenancy.CrossTenantAccess;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that touches {@code identity.invitation}. Tenant-scoped operations take the
 * verified {@link TenantId}; the few that cannot (token lookups for the anonymous flow and job
 * claims) are marked {@link CrossTenantAccess} and never return data to a caller of another tenant.
 * The token hash and address lookup are written but never read back into the domain.
 */
@Repository
public class JdbcInvitationRepository {

  private static final String COLUMNS =
      """
      id, tenant_id, email, role, locale, state, token_issued_at, expires_at, issue_count,
      delivery_state, accepted_at, revoked_at, created_at
      """;

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcInvitationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Serializes invitation creation within one tenant for the rest of the transaction, so quotas and
   * duplicate checks are exact under concurrency.
   *
   * @param tenant verified tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockTenant(TenantId tenant) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", "identity.invitation:" + tenant)
        .query((rs, row) -> 1)
        .single();
  }

  /**
   * Invitations the tenant created since a time.
   *
   * @param tenant verified tenant
   * @param since window start
   * @return count
   */
  public int countCreatedSince(TenantId tenant, Instant since) {
    return jdbc.sql(
            "SELECT count(*) FROM identity.invitation WHERE tenant_id = :tenant AND created_at >="
                + " :since")
        .param("tenant", tenant.value())
        .param("since", Timestamp.from(since))
        .query(Integer.class)
        .single();
  }

  /**
   * The oldest creation time inside a window (for {@code Retry-After}).
   *
   * @param tenant verified tenant
   * @param since window start
   * @return oldest creation time, if any
   */
  public Optional<Instant> oldestCreatedSince(TenantId tenant, Instant since) {
    return jdbc.sql(
            """
            SELECT min(created_at) FROM identity.invitation
            WHERE tenant_id = :tenant AND created_at >= :since
            """)
        .param("tenant", tenant.value())
        .param("since", Timestamp.from(since))
        .query((rs, row) -> instant(rs, 1))
        .optional();
  }

  /**
   * Open (pending or accepting, not past expiry) invitations of the tenant.
   *
   * @param tenant verified tenant
   * @param now current time
   * @return count
   */
  public int countOpen(TenantId tenant, Instant now) {
    return jdbc.sql(
            """
            SELECT count(*) FROM identity.invitation
            WHERE tenant_id = :tenant
              AND (state = 'ACCEPTING' OR (state = 'PENDING' AND expires_at > :now))
            """)
        .param("tenant", tenant.value())
        .param("now", Timestamp.from(now))
        .query(Integer.class)
        .single();
  }

  /**
   * The open invitation for an address in the tenant, locked, if any (including one past expiry
   * that the expiry job has not materialized yet).
   *
   * @param tenant verified tenant
   * @param emailLookup address lookup
   * @return the invitation
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Invitation> findOpenForUpdate(TenantId tenant, byte[] emailLookup) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                 FROM identity.invitation
                WHERE tenant_id = :tenant AND email_lookup = :lookup
                  AND state IN ('PENDING', 'ACCEPTING')
                FOR UPDATE
                """)
        .param("tenant", tenant.value())
        .param("lookup", emailLookup)
        .query(JdbcInvitationRepository::map)
        .optional();
  }

  /**
   * Inserts a new pending invitation with its first, queued issuance.
   *
   * @param tenant verified tenant (must equal the invitation's)
   * @param invitation the invitation
   * @param emailLookup address lookup
   * @param tokenSha256 SHA-256 of the token
   * @param createdBy verified subject
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(
      TenantId tenant,
      Invitation invitation,
      byte[] emailLookup,
      byte[] tokenSha256,
      String createdBy) {
    if (!tenant.equals(invitation.tenant())) {
      throw new IllegalArgumentException("tenant mismatch");
    }
    jdbc.sql(
            """
            INSERT INTO identity.invitation
              (id, tenant_id, email, email_lookup, role, locale, state, token_sha256,
               token_issued_at, expires_at, issue_count, delivery_state, delivery_updated_at,
               created_at, created_by)
            VALUES (:id, :tenant, :email, :lookup, :role, :locale, 'PENDING', :token,
                    :issuedAt, :expiresAt, 1, 'QUEUED', :createdAt, :createdAt, :createdBy)
            """)
        .param("id", invitation.id())
        .param("tenant", tenant.value())
        .param("email", invitation.email().value())
        .param("lookup", emailLookup)
        .param("role", invitation.role().wireName())
        .param("locale", invitation.locale().tag())
        .param("token", tokenSha256)
        .param("issuedAt", Timestamp.from(invitation.tokenIssuedAt()))
        .param("expiresAt", Timestamp.from(invitation.expiresAt()))
        .param("createdAt", Timestamp.from(invitation.createdAt()))
        .param("createdBy", createdBy)
        .update();
  }

  /**
   * Finds and locks an invitation of the tenant.
   *
   * @param tenant verified tenant
   * @param id invitation
   * @return the invitation (missing and foreign are both empty)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Invitation> findForUpdate(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM identity.invitation WHERE tenant_id = :tenant AND id = :id FOR UPDATE")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcInvitationRepository::map)
        .optional();
  }

  /**
   * Reissues a pending invitation: new token, new expiry, next issuance, queued delivery.
   *
   * @param tenant verified tenant
   * @param id invitation
   * @param currentIssue the issuance being replaced
   * @param tokenSha256 SHA-256 of the new token
   * @param issuedAt issue time
   * @param expiresAt new expiry
   * @return true when the row was pending with that issuance
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean reissue(
      TenantId tenant,
      UUID id,
      int currentIssue,
      byte[] tokenSha256,
      Instant issuedAt,
      Instant expiresAt) {
    return jdbc.sql(
                """
                UPDATE identity.invitation
                SET token_sha256 = :token, token_issued_at = :issuedAt, expires_at = :expiresAt,
                    issue_count = issue_count + 1, delivery_state = 'QUEUED',
                    delivery_updated_at = :issuedAt, version = version + 1
                WHERE tenant_id = :tenant AND id = :id AND state = 'PENDING'
                  AND issue_count = :issue
                """)
            .param("token", tokenSha256)
            .param("issuedAt", Timestamp.from(issuedAt))
            .param("expiresAt", Timestamp.from(expiresAt))
            .param("tenant", tenant.value())
            .param("id", id)
            .param("issue", currentIssue)
            .update()
        == 1;
  }

  /**
   * Revokes a pending invitation and erases its token.
   *
   * @param tenant verified tenant
   * @param id invitation
   * @param actor verified subject
   * @param now revocation time
   * @return true when the row was pending
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean revoke(TenantId tenant, UUID id, String actor, Instant now) {
    return jdbc.sql(
                """
                UPDATE identity.invitation
                SET state = 'REVOKED', token_sha256 = NULL, revoked_at = :now, revoked_by = :actor,
                    terminal_at = :now, version = version + 1
                WHERE tenant_id = :tenant AND id = :id AND state = 'PENDING'
                """)
            .param("now", Timestamp.from(now))
            .param("actor", actor)
            .param("tenant", tenant.value())
            .param("id", id)
            .update()
        == 1;
  }

  /**
   * Records the delivery result of one issuance. Conditional on the issuance and on the delivery
   * still being queued, so a late result for an older link never overwrites a newer one and a
   * result never overwrites the stale-delivery job's FAILED (amendment A3).
   *
   * @param tenant verified tenant
   * @param id invitation
   * @param issue the issuance that was sent
   * @param result SENT or FAILED
   * @param now time of the result
   * @return true when recorded
   */
  public boolean recordDelivery(
      TenantId tenant, UUID id, int issue, DeliveryState result, Instant now) {
    return jdbc.sql(
                """
                UPDATE identity.invitation
                SET delivery_state = :result, delivery_updated_at = :now, version = version + 1
                WHERE tenant_id = :tenant AND id = :id AND issue_count = :issue
                  AND delivery_state = 'QUEUED'
                """)
            .param("result", result.name())
            .param("now", Timestamp.from(now))
            .param("tenant", tenant.value())
            .param("id", id)
            .param("issue", issue)
            .update()
        == 1;
  }

  /**
   * One page of the tenant's invitations, newest first.
   *
   * @param tenant verified tenant
   * @param status optional public-status filter
   * @param afterCreatedAt keyset position (creation time), or {@code null} for the first page
   * @param afterId keyset position (id), or {@code null}
   * @param limit rows to fetch
   * @param now current time (for the derived EXPIRED status)
   * @return rows
   */
  public List<Invitation> page(
      TenantId tenant,
      InvitationStatus status,
      Instant afterCreatedAt,
      UUID afterId,
      int limit,
      Instant now) {
    String filter =
        status == null
            ? ""
            : switch (status) {
              case PENDING ->
                  " AND (state = 'ACCEPTING' OR (state = 'PENDING' AND expires_at > :now))";
              case EXPIRED ->
                  " AND (state = 'EXPIRED' OR (state = 'PENDING' AND expires_at <= :now))";
              case ACCEPTED -> " AND state = 'ACCEPTED'";
              case REVOKED -> " AND state = 'REVOKED'";
            };
    String keyset = afterId == null ? "" : " AND (created_at, id) < (:afterCreatedAt, :afterId)";
    JdbcClient.StatementSpec spec =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM identity.invitation WHERE tenant_id = :tenant"
                    + filter
                    + keyset
                    + " ORDER BY created_at DESC, id DESC LIMIT :limit")
            .param("tenant", tenant.value())
            .param("limit", limit);
    if (status == InvitationStatus.PENDING || status == InvitationStatus.EXPIRED) {
      spec = spec.param("now", Timestamp.from(now));
    }
    if (afterId != null) {
      spec = spec.param("afterCreatedAt", Timestamp.from(afterCreatedAt)).param("afterId", afterId);
    }
    return spec.query(JdbcInvitationRepository::map).list();
  }

  // ------------------------------------------------------------------------------------------
  // Anonymous flow and jobs (no verified tenant)
  // ------------------------------------------------------------------------------------------

  /**
   * The invitation a token belongs to, without locking.
   *
   * @param tokenSha256 SHA-256 of a well-formed token
   * @return the invitation, if the hash is known
   */
  @CrossTenantAccess("anonymous inspection by a 256-bit single-use token")
  public Optional<Invitation> findByTokenHash(byte[] tokenSha256) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM identity.invitation WHERE token_sha256 = :token")
        .param("token", tokenSha256)
        .query(JdbcInvitationRepository::map)
        .optional();
  }

  /**
   * The invitation a token belongs to, locked, with its acceptance lease.
   *
   * @param tokenSha256 SHA-256 of a well-formed token
   * @return the invitation and lease, if the hash is known
   */
  @CrossTenantAccess("anonymous acceptance by a 256-bit single-use token")
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AcceptanceRow> findByTokenHashForUpdate(byte[] tokenSha256) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                , acceptance_lease_owner, acceptance_lease_until
                FROM identity.invitation WHERE token_sha256 = :token FOR UPDATE
                """)
        .param("token", tokenSha256)
        .query(JdbcInvitationRepository::mapAcceptance)
        .optional();
  }

  /**
   * Starts or takes over an acceptance lease.
   *
   * @param id invitation
   * @param owner new lease owner
   * @param until lease end
   * @return true when the invitation was pending or accepting
   */
  @CrossTenantAccess("acceptance of an invitation already identified by its token or a job claim")
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean startAcceptance(UUID id, UUID owner, Instant until) {
    return jdbc.sql(
                """
                UPDATE identity.invitation
                SET state = 'ACCEPTING', acceptance_lease_owner = :owner,
                    acceptance_lease_until = :until, version = version + 1
                WHERE id = :id AND state IN ('PENDING', 'ACCEPTING')
                """)
            .param("owner", owner)
            .param("until", Timestamp.from(until))
            .param("id", id)
            .update()
        == 1;
  }

  /**
   * Returns an accepting invitation to pending, only for the current lease owner.
   *
   * @param id invitation
   * @param owner lease owner
   * @return true when released
   */
  @CrossTenantAccess("acceptance of an invitation already identified by its token or a job claim")
  public boolean releaseAcceptance(UUID id, UUID owner) {
    return jdbc.sql(
                """
                UPDATE identity.invitation
                SET state = 'PENDING', acceptance_lease_owner = NULL, acceptance_lease_until = NULL,
                    version = version + 1
                WHERE id = :id AND state = 'ACCEPTING' AND acceptance_lease_owner = :owner
                """)
            .param("id", id)
            .param("owner", owner)
            .update()
        == 1;
  }

  /**
   * Locks an accepting invitation for completion, only for the current lease owner (guardrail 5: a
   * worker whose lease was taken over can never complete).
   *
   * @param id invitation
   * @param owner lease owner
   * @return the invitation and lease, if still owned
   */
  @CrossTenantAccess("acceptance of an invitation already identified by its token or a job claim")
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AcceptanceRow> lockOwnedAcceptance(UUID id, UUID owner) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                , acceptance_lease_owner, acceptance_lease_until
                FROM identity.invitation
                WHERE id = :id AND state = 'ACCEPTING' AND acceptance_lease_owner = :owner
                FOR UPDATE
                """)
        .param("id", id)
        .param("owner", owner)
        .query(JdbcInvitationRepository::mapAcceptance)
        .optional();
  }

  /**
   * Completes an owned acceptance: consumes the token, links the membership and schedules the
   * credential setup.
   *
   * @param id invitation
   * @param owner lease owner
   * @param membershipId the new membership
   * @param now acceptance time
   * @return true when completed
   */
  @CrossTenantAccess("acceptance of an invitation already identified by its token or a job claim")
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean completeAcceptance(UUID id, UUID owner, UUID membershipId, Instant now) {
    return jdbc.sql(
                """
                UPDATE identity.invitation
                SET state = 'ACCEPTED', token_sha256 = NULL, acceptance_lease_owner = NULL,
                    acceptance_lease_until = NULL, accepted_at = :now, membership_id = :membership,
                    terminal_at = :now, credential_setup_state = 'PENDING',
                    credential_setup_next_at = :now, version = version + 1
                WHERE id = :id AND state = 'ACCEPTING' AND acceptance_lease_owner = :owner
                """)
            .param("now", Timestamp.from(now))
            .param("membership", membershipId)
            .param("id", id)
            .param("owner", owner)
            .update()
        == 1;
  }

  /**
   * Claims due pending invitations for expiry.
   *
   * @param now current time
   * @param limit batch size
   * @return locked rows (the caller's transaction holds the locks)
   */
  @CrossTenantAccess("background expiry across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<Invitation> claimDueExpirations(Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                 FROM identity.invitation
                WHERE state = 'PENDING' AND expires_at <= :now
                ORDER BY expires_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(JdbcInvitationRepository::map)
        .list();
  }

  /**
   * Expires a pending (or an abandoned accepting) invitation and erases its token.
   *
   * @param id invitation
   * @param now expiry time
   * @return true when expired
   */
  @CrossTenantAccess("background expiry across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean markExpired(UUID id, Instant now) {
    return jdbc.sql(
                """
                UPDATE identity.invitation
                SET state = 'EXPIRED', token_sha256 = NULL, acceptance_lease_owner = NULL,
                    acceptance_lease_until = NULL, expired_at = :now, terminal_at = :now,
                    version = version + 1
                WHERE id = :id AND state IN ('PENDING', 'ACCEPTING') AND expires_at <= :now
                """)
            .param("now", Timestamp.from(now))
            .param("id", id)
            .update()
        == 1;
  }

  /**
   * Takes over acceptances whose lease expired (their worker crashed or stalled).
   *
   * @param now current time
   * @param owner new lease owner
   * @param until new lease end
   * @param limit batch size
   * @return taken-over invitation ids
   */
  @CrossTenantAccess("background acceptance recovery across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<UUID> takeOverStaleAcceptances(Instant now, UUID owner, Instant until, int limit) {
    return jdbc.sql(
            """
            UPDATE identity.invitation
            SET acceptance_lease_owner = :owner, acceptance_lease_until = :until,
                version = version + 1
            WHERE id IN (
                SELECT id FROM identity.invitation
                WHERE state = 'ACCEPTING' AND acceptance_lease_until < :now
                ORDER BY acceptance_lease_until
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            RETURNING id
            """)
        .param("owner", owner)
        .param("until", Timestamp.from(until))
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  /**
   * Marks deliveries still queued after the stale interval as FAILED (amendment A3). Their token no
   * longer exists anywhere, so they are never retried; the administrator can resend.
   *
   * @param queuedBefore queued before this time
   * @param now current time
   * @return rows changed
   */
  @CrossTenantAccess("background delivery staleness across tenants")
  public int failStaleDeliveries(Instant queuedBefore, Instant now) {
    return jdbc.sql(
            """
            UPDATE identity.invitation
            SET delivery_state = 'FAILED', delivery_updated_at = :now, version = version + 1
            WHERE delivery_state = 'QUEUED' AND delivery_updated_at < :before
            """)
        .param("now", Timestamp.from(now))
        .param("before", Timestamp.from(queuedBefore))
        .update();
  }

  /**
   * Deletes terminal invitations past retention (the email address leaves the database with them).
   *
   * @param terminalBefore ended before this time
   * @param limit batch size
   * @return rows deleted
   */
  @CrossTenantAccess("background retention across tenants")
  public int deleteRetained(Instant terminalBefore, int limit) {
    return jdbc.sql(
            """
            DELETE FROM identity.invitation
            WHERE id IN (
                SELECT id FROM identity.invitation
                WHERE terminal_at IS NOT NULL AND terminal_at < :before
                ORDER BY terminal_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """)
        .param("before", Timestamp.from(terminalBefore))
        .param("limit", limit)
        .update();
  }

  /**
   * Claims accepted invitations whose credential setup is due.
   *
   * @param now current time
   * @param limit batch size
   * @return due setups with the member subject (locked by the caller's transaction)
   */
  @CrossTenantAccess("background credential-setup retries across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<CredentialSetupRow> claimDueCredentialSetups(Instant now, int limit) {
    return jdbc.sql(
            """
            SELECT i.id, i.tenant_id, m.subject, i.credential_setup_attempts
            FROM identity.invitation i
            JOIN identity.tenant_membership m
              ON m.tenant_id = i.tenant_id AND m.id = i.membership_id
            WHERE i.state = 'ACCEPTED' AND i.credential_setup_state = 'PENDING'
              AND i.credential_setup_next_at <= :now
            ORDER BY i.credential_setup_next_at
            LIMIT :limit
            FOR UPDATE OF i SKIP LOCKED
            """)
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(
            (rs, row) ->
                new CredentialSetupRow(
                    rs.getObject("id", UUID.class),
                    new TenantId(rs.getObject("tenant_id", UUID.class)),
                    rs.getString("subject"),
                    rs.getInt("credential_setup_attempts")))
        .list();
  }

  /**
   * Records one credential-setup attempt.
   *
   * @param id invitation
   * @param state SENT, PENDING (retry at {@code nextAt}) or FAILED
   * @param attempts attempts so far
   * @param nextAt next retry (only for PENDING)
   * @return true when recorded
   */
  @CrossTenantAccess("credential setup of an accepted invitation identified by acceptance or a job")
  public boolean recordCredentialSetup(UUID id, String state, int attempts, Instant nextAt) {
    return jdbc.sql(
                """
                UPDATE identity.invitation
                SET credential_setup_state = :state, credential_setup_attempts = :attempts,
                    credential_setup_next_at = :nextAt, version = version + 1
                WHERE id = :id AND state = 'ACCEPTED' AND credential_setup_state = 'PENDING'
                """)
            .param("state", state)
            .param("attempts", attempts)
            .param("nextAt", nextAt == null ? null : Timestamp.from(nextAt))
            .param("id", id)
            .update()
        == 1;
  }

  /**
   * An invitation with its acceptance lease (internal to the acceptance flow).
   *
   * @param invitation the invitation
   * @param leaseOwner current lease owner, if accepting
   * @param leaseUntil current lease end, if accepting
   */
  public record AcceptanceRow(Invitation invitation, UUID leaseOwner, Instant leaseUntil) {}

  /**
   * A due credential setup.
   *
   * @param invitationId invitation
   * @param tenant tenant
   * @param subject member subject
   * @param attempts attempts so far
   */
  public record CredentialSetupRow(
      UUID invitationId, TenantId tenant, String subject, int attempts) {}

  private static Invitation map(ResultSet rs, int row) throws SQLException {
    return new Invitation(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        new EmailAddress(rs.getString("email")),
        TenantRole.fromWire(rs.getString("role")).orElseThrow(),
        InvitationLocale.fromTag(rs.getString("locale")).orElseThrow(),
        InvitationState.valueOf(rs.getString("state")),
        instant(rs, "token_issued_at"),
        instant(rs, "expires_at"),
        rs.getInt("issue_count"),
        DeliveryState.valueOf(rs.getString("delivery_state")),
        instant(rs, "accepted_at"),
        instant(rs, "revoked_at"),
        instant(rs, "created_at"));
  }

  private static AcceptanceRow mapAcceptance(ResultSet rs, int row) throws SQLException {
    return new AcceptanceRow(
        map(rs, row),
        rs.getObject("acceptance_lease_owner", UUID.class),
        instant(rs, "acceptance_lease_until"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
