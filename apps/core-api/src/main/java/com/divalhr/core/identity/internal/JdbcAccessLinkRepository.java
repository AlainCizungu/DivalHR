package com.divalhr.core.identity.internal;

import com.divalhr.core.platform.tenancy.CrossTenantAccess;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that touches {@code identity.employee_access_link} and {@code
 * identity.access_revocation} (MVP-022). Every tenant operation takes the verified {@link
 * TenantId}; the revocation worker's claims are explicitly cross-tenant. Links are only inserted or
 * unlinked once; revocations only move through the states the V15 guard allows. Subjects, addresses
 * and roles read here are never logged.
 */
@Repository
public class JdbcAccessLinkRepository {

  private static final String LINK_COLUMNS =
      "l.id, l.employee_id, l.membership_id, l.linked_at, l.version, m.role, m.subject, EXISTS"
          + " (SELECT 1 FROM identity.access_revocation r WHERE r.tenant_id = m.tenant_id AND"
          + " r.membership_id = m.id AND r.state <> 'CANCELLED') AS revoked, EXISTS (SELECT 1 FROM"
          + " identity.access_revocation r WHERE r.tenant_id = m.tenant_id AND r.membership_id ="
          + " m.id AND r.state <> 'CANCELLED' AND r.effective_at <= statement_timestamp()) AS"
          + " revocation_effective";

  private static final String REVOCATION_COLUMNS =
      "id, tenant_id, membership_id, membership_role, link_id, employee_id, separation_id,"
          + " effective_at, state, attempts, version,"
          + " (effective_at <= statement_timestamp()) AS due,"
          + " (state = 'IDP_PENDING' AND next_attempt_at <= statement_timestamp()"
          + " AND (lease_until IS NULL OR lease_until < statement_timestamp())) AS claimable";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcAccessLinkRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * An employee's link with its membership's role and subject (Confidential; never logged).
   *
   * @param id link
   * @param employeeId employee
   * @param membershipId membership
   * @param linkedAt when it was linked
   * @param version link version
   * @param role membership role (wire name)
   * @param subject membership subject
   * @param revoked whether a revocation that is not cancelled exists for the membership
   * @param revocationEffective whether such a revocation has taken effect
   */
  public record LinkRow(
      UUID id,
      UUID employeeId,
      UUID membershipId,
      Instant linkedAt,
      long version,
      String role,
      String subject,
      boolean revoked,
      boolean revocationEffective) {

    @Override
    public String toString() {
      return "LinkRow[redacted]";
    }
  }

  /**
   * A membership found by its address lookup.
   *
   * @param id membership
   * @param role role (wire name)
   * @param linked whether it has an active link
   * @param revoked whether a revocation that is not cancelled exists for it
   */
  public record MembershipRow(UUID id, String role, boolean linked, boolean revoked) {}

  /**
   * A revocation row.
   *
   * @param id revocation
   * @param tenant tenant
   * @param membershipId membership
   * @param membershipRole membership role recorded with it
   * @param linkId link
   * @param employeeId employee
   * @param separationId separation
   * @param effectiveAt when DivalHR access ends
   * @param state state
   * @param attempts identity-provider attempts in the current budget
   * @param version version
   * @param due whether {@code effectiveAt} has passed (database clock)
   * @param claimable whether a worker may lease it now (database clock)
   */
  public record RevocationRow(
      UUID id,
      TenantId tenant,
      UUID membershipId,
      String membershipRole,
      UUID linkId,
      UUID employeeId,
      UUID separationId,
      Instant effectiveAt,
      String state,
      int attempts,
      long version,
      boolean due,
      boolean claimable) {

    @Override
    public String toString() {
      return "RevocationRow[redacted]";
    }
  }

  // -------------------------------------------------------------------------------------------
  // Links
  // -------------------------------------------------------------------------------------------

  /**
   * Takes the per-tenant access-link transaction lock (step 3 of the ADR 0008 lock order). Every
   * link, unlink, separation and separation cancellation takes it before reading a link.
   *
   * @param tenant verified tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockTenant(TenantId tenant) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('identity.access-link:'"
                + " || CAST(:tenant AS uuid), 0))")
        .param("tenant", tenant.value())
        .query()
        .singleRow();
  }

  /**
   * The employee's active link, without locking.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the link, if any
   */
  public Optional<LinkRow> activeLink(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT "
                + LINK_COLUMNS
                + " FROM identity.employee_access_link l JOIN identity.tenant_membership m"
                + " ON m.tenant_id = l.tenant_id AND m.id = l.membership_id"
                + " WHERE l.tenant_id = :tenant AND l.employee_id = :employee"
                + " AND l.unlinked_at IS NULL")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query(JdbcAccessLinkRepository::link)
        .optional();
  }

  /**
   * Locks the employee's active link and shares its membership (step 4; the caller holds the
   * tenant's access-link lock).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the locked link, if any
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<LinkRow> lockActiveLink(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT "
                + LINK_COLUMNS
                + " FROM identity.employee_access_link l JOIN identity.tenant_membership m"
                + " ON m.tenant_id = l.tenant_id AND m.id = l.membership_id"
                + " WHERE l.tenant_id = :tenant AND l.employee_id = :employee"
                + " AND l.unlinked_at IS NULL FOR UPDATE OF l FOR SHARE OF m")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query(JdbcAccessLinkRepository::link)
        .optional();
  }

  /**
   * The membership of the tenant holding an address lookup, active or revoked.
   *
   * @param tenant verified tenant
   * @param emailLookup keyed lookup of the normalized address
   * @return the membership, if any
   */
  public Optional<MembershipRow> membershipByLookup(TenantId tenant, byte[] emailLookup) {
    return jdbc.sql(
            "SELECT m.id, m.role, EXISTS (SELECT 1 FROM identity.employee_access_link l WHERE"
                + " l.tenant_id = m.tenant_id AND l.membership_id = m.id AND l.unlinked_at IS NULL)"
                + " AS linked, EXISTS (SELECT 1 FROM identity.access_revocation r WHERE r.tenant_id"
                + " = m.tenant_id AND r.membership_id = m.id AND r.state <> 'CANCELLED') AS revoked"
                + " FROM identity.tenant_membership m WHERE m.tenant_id = :tenant AND"
                + " m.email_lookup = :lookup")
        .param("tenant", tenant.value())
        .param("lookup", emailLookup)
        .query(
            (rs, row) ->
                new MembershipRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("role"),
                    rs.getBoolean("linked"),
                    rs.getBoolean("revoked")))
        .optional();
  }

  /**
   * The tenant's membership by ID, shared-locked (link creation; the caller holds the tenant's
   * access-link lock).
   *
   * @param tenant verified tenant
   * @param membershipId membership
   * @return the membership, if in the tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<MembershipRow> lockMembership(TenantId tenant, UUID membershipId) {
    return jdbc.sql(
            "SELECT m.id, m.role, EXISTS (SELECT 1 FROM identity.employee_access_link l WHERE"
                + " l.tenant_id = m.tenant_id AND l.membership_id = m.id AND l.unlinked_at IS NULL)"
                + " AS linked, EXISTS (SELECT 1 FROM identity.access_revocation r WHERE r.tenant_id"
                + " = m.tenant_id AND r.membership_id = m.id AND r.state <> 'CANCELLED') AS revoked"
                + " FROM identity.tenant_membership m WHERE m.tenant_id = :tenant AND m.id = :id"
                + " FOR SHARE OF m")
        .param("tenant", tenant.value())
        .param("id", membershipId)
        .query(
            (rs, row) ->
                new MembershipRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("role"),
                    rs.getBoolean("linked"),
                    rs.getBoolean("revoked")))
        .optional();
  }

  /**
   * Inserts an active link.
   *
   * @param tenant verified tenant
   * @param id link
   * @param employeeId employee
   * @param membershipId membership
   * @param at link time
   * @param by verified subject
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertLink(
      TenantId tenant, UUID id, UUID employeeId, UUID membershipId, Instant at, String by) {
    jdbc.sql(
            "INSERT INTO identity.employee_access_link (id, tenant_id, employee_id, membership_id,"
                + " linked_at, linked_by) VALUES (:id, :tenant, :employee, :membership, :at, :by)")
        .param("id", id)
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("membership", membershipId)
        .param("at", Timestamp.from(at))
        .param("by", by)
        .update();
  }

  /**
   * Unlinks an active link at its expected version.
   *
   * @param tenant verified tenant
   * @param id link
   * @param employeeId employee owning it
   * @param expectedVersion expected version
   * @param at unlink time
   * @param by verified subject
   * @return rows updated (0 when missing, already unlinked or at another version)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int unlink(
      TenantId tenant, UUID id, UUID employeeId, long expectedVersion, Instant at, String by) {
    return jdbc.sql(
            "UPDATE identity.employee_access_link SET unlinked_at = :at, unlinked_by = :by,"
                + " version = version + 1 WHERE tenant_id = :tenant AND id = :id"
                + " AND employee_id = :employee AND unlinked_at IS NULL AND version = :version")
        .param("at", Timestamp.from(at))
        .param("by", by)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("employee", employeeId)
        .param("version", expectedVersion)
        .update();
  }

  private static LinkRow link(ResultSet rs, int row) throws SQLException {
    return new LinkRow(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getObject("membership_id", UUID.class),
        rs.getTimestamp("linked_at").toInstant(),
        rs.getLong("version"),
        rs.getString("role"),
        rs.getString("subject"),
        rs.getBoolean("revoked"),
        rs.getBoolean("revocation_effective"));
  }

  // -------------------------------------------------------------------------------------------
  // Revocations: the separation coordinator
  // -------------------------------------------------------------------------------------------

  /**
   * Inserts a revocation: SCHEDULED when its instant is still ahead (database clock), else
   * IDP_PENDING at once.
   *
   * @param tenant verified tenant
   * @param id revocation
   * @param membershipId membership
   * @param linkId link
   * @param employeeId employee
   * @param separationId separation
   * @param effectiveAt when DivalHR access ends
   * @return whether it was recorded as already effective
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean insertRevocation(
      TenantId tenant,
      UUID id,
      UUID membershipId,
      UUID linkId,
      UUID employeeId,
      UUID separationId,
      Instant effectiveAt) {
    return Boolean.TRUE.equals(
        jdbc.sql(
                "INSERT INTO identity.access_revocation (id, tenant_id, membership_id,"
                    + " membership_role, link_id, employee_id, separation_id, effective_at, state,"
                    + " next_attempt_at, effective_marked_at, requested_at)"
                    + " SELECT :id, :tenant, :membership, 'employee', :link, :employee,"
                    + " :separation, :at, CASE WHEN due THEN 'IDP_PENDING' ELSE 'SCHEDULED' END,"
                    + " CASE WHEN due THEN statement_timestamp() END,"
                    + " CASE WHEN due THEN statement_timestamp() END, statement_timestamp()"
                    + " FROM (SELECT CAST(:at AS timestamptz) <= statement_timestamp() AS due) t"
                    + " RETURNING state = 'IDP_PENDING'")
            .param("id", id)
            .param("tenant", tenant.value())
            .param("membership", membershipId)
            .param("link", linkId)
            .param("employee", employeeId)
            .param("separation", separationId)
            .param("at", Timestamp.from(effectiveAt))
            .query(Boolean.class)
            .single());
  }

  /**
   * The revocations of separations.
   *
   * @param tenant verified tenant
   * @param separationIds separations
   * @return their revocations (newest first per separation)
   */
  public List<RevocationRow> revocationsOf(TenantId tenant, Collection<UUID> separationIds) {
    if (separationIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT "
                + REVOCATION_COLUMNS
                + " FROM identity.access_revocation WHERE tenant_id = :tenant"
                + " AND separation_id = ANY(:ids) ORDER BY requested_at DESC")
        .param("tenant", tenant.value())
        .param("ids", separationIds.toArray(UUID[]::new))
        .query(JdbcAccessLinkRepository::revocation)
        .list();
  }

  /**
   * Locks the separation's open revocation without waiting: a row a worker holds raises lock-not-
   * available (SQLSTATE 55P03), which the coordinator turns into a retryable 503.
   *
   * @param tenant verified tenant
   * @param separationId separation
   * @return the locked revocation, if any
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RevocationRow> lockOpenRevocation(TenantId tenant, UUID separationId) {
    return jdbc.sql(
            "SELECT "
                + REVOCATION_COLUMNS
                + " FROM identity.access_revocation WHERE tenant_id = :tenant"
                + " AND separation_id = :separation AND state <> 'CANCELLED' FOR UPDATE NOWAIT")
        .param("tenant", tenant.value())
        .param("separation", separationId)
        .query(JdbcAccessLinkRepository::revocation)
        .optional();
  }

  /**
   * Cancels a scheduled revocation that has not taken effect (database clock).
   *
   * @param tenant verified tenant
   * @param id revocation
   * @param version expected version
   * @param by verified subject
   * @return rows updated
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int cancel(TenantId tenant, UUID id, long version, String by) {
    return jdbc.sql(
            "UPDATE identity.access_revocation SET state = 'CANCELLED',"
                + " cancelled_at = statement_timestamp(), cancelled_by = :by,"
                + " version = version + 1 WHERE tenant_id = :tenant AND id = :id"
                + " AND version = :version AND state = 'SCHEDULED'"
                + " AND effective_at > statement_timestamp()")
        .param("by", by)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("version", version)
        .update();
  }

  /**
   * Re-queues a revocation in manual intervention with a fresh attempt budget.
   *
   * @param tenant verified tenant
   * @param id revocation
   * @param version expected version
   * @return rows updated
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int requeue(TenantId tenant, UUID id, long version) {
    return jdbc.sql(
            "UPDATE identity.access_revocation SET state = 'IDP_PENDING', attempts = 0,"
                + " next_attempt_at = statement_timestamp(), outcome_code = NULL,"
                + " version = version + 1 WHERE tenant_id = :tenant AND id = :id"
                + " AND version = :version AND state = 'MANUAL_INTERVENTION'")
        .param("tenant", tenant.value())
        .param("id", id)
        .param("version", version)
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Revocations: the background worker (cross-tenant, A22-5)
  // -------------------------------------------------------------------------------------------

  /**
   * Moves due scheduled revocations to IDP_PENDING (DivalHR access was already denied by the gate
   * from their instant on); several instances never claim the same row.
   *
   * @param limit batch size
   * @return the activated revocations
   */
  @CrossTenantAccess("background activation of due revocations across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<RevocationRow> activateDue(int limit) {
    return jdbc.sql(
            "UPDATE identity.access_revocation SET state = 'IDP_PENDING',"
                + " effective_marked_at = statement_timestamp(),"
                + " next_attempt_at = statement_timestamp(), version = version + 1"
                + " WHERE id IN (SELECT id FROM identity.access_revocation"
                + " WHERE state = 'SCHEDULED' AND effective_at <= statement_timestamp()"
                + " ORDER BY effective_at LIMIT :limit FOR UPDATE SKIP LOCKED)"
                + " RETURNING "
                + REVOCATION_COLUMNS)
        .param("limit", limit)
        .query(JdbcAccessLinkRepository::revocation)
        .list();
  }

  /**
   * IDs of revocations a worker may lease now (unlocked read; each is re-checked under its lock).
   *
   * @param limit batch size
   * @return candidate IDs, oldest due first
   */
  @CrossTenantAccess("background identity-provider revocations across tenants")
  public List<UUID> claimableIds(int limit) {
    return jdbc.sql(
            "SELECT id FROM identity.access_revocation WHERE state = 'IDP_PENDING'"
                + " AND next_attempt_at <= statement_timestamp()"
                + " AND (lease_until IS NULL OR lease_until < statement_timestamp())"
                + " ORDER BY next_attempt_at LIMIT :limit")
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  /**
   * Locks one revocation for the worker, skipping a row another worker or a coordinator holds.
   *
   * @param id revocation
   * @return the locked row, if not held elsewhere
   */
  @CrossTenantAccess("background identity-provider revocations across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RevocationRow> lockForWorker(UUID id) {
    return jdbc.sql(
            "SELECT "
                + REVOCATION_COLUMNS
                + " FROM identity.access_revocation WHERE id = :id FOR UPDATE SKIP LOCKED")
        .param("id", id)
        .query(JdbcAccessLinkRepository::revocation)
        .optional();
  }

  /**
   * What the worker revalidates before calling the identity provider (A22-5): the revocation's link
   * and membership as they are now, share-locked without waiting.
   *
   * @param linkTenant the link's tenant, or null when the link is gone
   * @param linkEmployee the link's employee
   * @param linkMembership the link's membership
   * @param linkActive whether the link is still active
   * @param membershipTenant the membership's tenant, or null when the membership is gone
   * @param membershipRole the membership's role
   * @param subject the membership's subject now
   */
  public record Binding(
      UUID linkTenant,
      UUID linkEmployee,
      UUID linkMembership,
      boolean linkActive,
      UUID membershipTenant,
      String membershipRole,
      String subject) {

    @Override
    public String toString() {
      return "Binding[redacted]";
    }
  }

  /**
   * Reads the revocation's link and membership under share locks that never wait (a conflict raises
   * SQLSTATE 55P03 and the worker retries later).
   *
   * @param revocation the locked revocation
   * @return the current binding
   */
  @CrossTenantAccess("background identity-provider revocations across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public Binding binding(RevocationRow revocation) {
    Optional<Object[]> link =
        jdbc.sql(
                "SELECT tenant_id, employee_id, membership_id, unlinked_at IS NULL AS active"
                    + " FROM identity.employee_access_link WHERE id = :id FOR SHARE NOWAIT")
            .param("id", revocation.linkId())
            .query(
                (rs, row) ->
                    new Object[] {
                      rs.getObject("tenant_id", UUID.class),
                      rs.getObject("employee_id", UUID.class),
                      rs.getObject("membership_id", UUID.class),
                      rs.getBoolean("active")
                    })
            .optional();
    Optional<Object[]> membership =
        jdbc.sql(
                "SELECT tenant_id, role, subject FROM identity.tenant_membership"
                    + " WHERE id = :id FOR SHARE NOWAIT")
            .param("id", revocation.membershipId())
            .query(
                (rs, row) ->
                    new Object[] {
                      rs.getObject("tenant_id", UUID.class),
                      rs.getString("role"),
                      rs.getString("subject")
                    })
            .optional();
    return new Binding(
        link.map(l -> (UUID) l[0]).orElse(null),
        link.map(l -> (UUID) l[1]).orElse(null),
        link.map(l -> (UUID) l[2]).orElse(null),
        link.map(l -> (Boolean) l[3]).orElse(false),
        membership.map(m -> (UUID) m[0]).orElse(null),
        membership.map(m -> (String) m[1]).orElse(null),
        membership.map(m -> (String) m[2]).orElse(null));
  }

  /**
   * Leases a locked revocation for one identity-provider attempt.
   *
   * @param id revocation
   * @param version its version under the lock
   * @param owner worker lease owner
   * @param leaseUntil lease expiry
   * @return rows updated
   */
  @CrossTenantAccess("background identity-provider revocations across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public int lease(UUID id, long version, UUID owner, Instant leaseUntil) {
    return jdbc.sql(
            "UPDATE identity.access_revocation SET lease_owner = :owner, lease_until = :until,"
                + " attempts = attempts + 1, version = version + 1"
                + " WHERE id = :id AND version = :version AND state = 'IDP_PENDING'")
        .param("owner", owner)
        .param("until", Timestamp.from(leaseUntil))
        .param("id", id)
        .param("version", version)
        .update();
  }

  /**
   * Records the identity provider's outcome for the worker holding the lease at that version.
   *
   * @param id revocation
   * @param version version the lease produced
   * @param owner lease owner
   * @param state new state ({@code COMPLETED}, {@code IDP_PENDING} or {@code MANUAL_INTERVENTION})
   * @param outcome closed outcome code
   * @param nextAttemptAt next attempt (IDP_PENDING only)
   * @return rows updated (0 when the lease was lost)
   */
  @CrossTenantAccess("background identity-provider revocations across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public int finish(
      UUID id, long version, UUID owner, String state, String outcome, Instant nextAttemptAt) {
    return jdbc.sql(
            "UPDATE identity.access_revocation SET state = :state, outcome_code = :outcome,"
                + " next_attempt_at = :next, lease_owner = NULL, lease_until = NULL,"
                + " idp_completed_at = CASE WHEN :state = 'COMPLETED'"
                + " THEN statement_timestamp() END, version = version + 1"
                + " WHERE id = :id AND version = :version AND lease_owner = :owner"
                + " AND state = 'IDP_PENDING'")
        .param("state", state)
        .param("outcome", outcome)
        .param("next", nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt))
        .param("id", id)
        .param("version", version)
        .param("owner", owner)
        .update();
  }

  /**
   * Refuses a revocation whose binding no longer holds, without any identity-provider call.
   *
   * @param id revocation
   * @param version its version under the lock
   * @param outcome closed refusal code
   * @return rows updated
   */
  @CrossTenantAccess("background identity-provider revocations across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public int refuse(UUID id, long version, String outcome) {
    return jdbc.sql(
            "UPDATE identity.access_revocation SET state = 'MANUAL_INTERVENTION',"
                + " outcome_code = :outcome, next_attempt_at = NULL, lease_owner = NULL,"
                + " lease_until = NULL, version = version + 1"
                + " WHERE id = :id AND version = :version AND state = 'IDP_PENDING'")
        .param("outcome", outcome)
        .param("id", id)
        .param("version", version)
        .update();
  }

  /**
   * Open revocations per state, for the operational gauges (counts only).
   *
   * @return counts of IDP_PENDING, MANUAL_INTERVENTION and overdue IDP_PENDING (older than the
   *     given age) rows
   * @param overdueAfter age after which a pending row counts as overdue
   */
  @CrossTenantAccess("operational gauges: counts only, no row data leaves the method")
  public long[] openCounts(java.time.Duration overdueAfter) {
    return jdbc.sql(
            "SELECT count(*) FILTER (WHERE state = 'IDP_PENDING') AS pending,"
                + " count(*) FILTER (WHERE state = 'MANUAL_INTERVENTION') AS manual,"
                + " count(*) FILTER (WHERE state = 'IDP_PENDING' AND effective_marked_at"
                + " < statement_timestamp() - CAST(:age AS interval)) AS overdue"
                + " FROM identity.access_revocation"
                + " WHERE state IN ('IDP_PENDING', 'MANUAL_INTERVENTION')")
        .param("age", overdueAfter.toSeconds() + " seconds")
        .query(
            (rs, row) ->
                new long[] {rs.getLong("pending"), rs.getLong("manual"), rs.getLong("overdue")})
        .single();
  }

  private static RevocationRow revocation(ResultSet rs, int row) throws SQLException {
    return new RevocationRow(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("membership_id", UUID.class),
        rs.getString("membership_role"),
        rs.getObject("link_id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getObject("separation_id", UUID.class),
        rs.getTimestamp("effective_at").toInstant(),
        rs.getString("state"),
        rs.getInt("attempts"),
        rs.getLong("version"),
        rs.getBoolean("due"),
        rs.getBoolean("claimable"));
  }
}
