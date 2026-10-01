package com.divalhr.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V8: the database independently enforces tenant integrity (amendment A1), the lifecycle, token
 * presence per state, one open invitation per address per tenant, assignable roles only, one tenant
 * per subject and immutability. Every statement is direct SQL.
 */
@IntegrationTest
class InvitationConstraintsIntegrationTest {

  /** Identity subjects are unique across tenants, so each test uses its own. */
  private final String run = UUID.randomUUID().toString();

  private String sub(String name) {
    return "sub-" + name + "-" + run;
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;

  private UUID tenantA;
  private UUID tenantB;

  @BeforeEach
  void organizations() {
    tenantA = organization();
    tenantB = organization();
  }

  @Test
  void invitationsAndMembershipsBelongToAnExistingOrganization() {
    assertThat(
            constraintOf(
                () ->
                    invitation(UUID.randomUUID(), UUID.randomUUID(), "o@example.test", "employee")))
        .isEqualTo("invitation_tenant_fk");
    assertThat(
            constraintOf(
                () ->
                    membership(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        sub("x"),
                        "employee",
                        "o@example.test",
                        null)))
        .isEqualTo("tenant_membership_tenant_fk");
  }

  @Test
  void onlyTenantRolesCanEverBeStored() {
    assertThat(
            constraintOf(
                () -> invitation(UUID.randomUUID(), tenantA, "p@example.test", "platform-admin")))
        .isEqualTo("invitation_role_assignable");
    assertThat(
            constraintOf(
                () ->
                    membership(
                        UUID.randomUUID(),
                        tenantA,
                        sub("p"),
                        "platform-admin",
                        "p@example.test",
                        null)))
        .isEqualTo("tenant_membership_role_assignable");
  }

  @Test
  void oneOpenInvitationPerAddressPerTenantButOtherTenantsAreIndependent() {
    invitation(UUID.randomUUID(), tenantA, "same@example.test", "employee");
    assertThat(
            constraintOf(
                () -> invitation(UUID.randomUUID(), tenantA, "same@example.test", "tenant-admin")))
        .isEqualTo("invitation_one_open_per_email");
    invitation(UUID.randomUUID(), tenantB, "same@example.test", "employee");
    assertThat(
            constraintOf(
                () -> invitation(UUID.randomUUID(), tenantA, "Same@example.test", "employee")))
        .isEqualTo("invitation_email_format");
  }

  @Test
  void invitationsStartPendingAndFollowOnlyAllowedTransitions() {
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        """
                        INSERT INTO identity.invitation
                          (id, tenant_id, email, email_lookup, role, locale, state, token_sha256,
                           token_issued_at, expires_at, delivery_updated_at, created_at, created_by)
                        VALUES (?, ?, 'z@example.test', ?, 'employee', 'fr', 'ACCEPTED', NULL,
                                now(), now() + interval '1 day', now(), now(), 'sub')
                        """,
                        UUID.randomUUID(),
                        tenantA,
                        sha256("z@example.test"))))
        .isEqualTo("invitation_insert_initial");
    UUID id = UUID.randomUUID();
    invitation(id, tenantA, "t@example.test", "employee");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.invitation SET state = 'ACCEPTED' WHERE id = ?", id)))
        .isEqualTo("invitation_transition_allowed");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.invitation SET state = 'ACCEPTING' WHERE id = ?", id)))
        .isEqualTo("invitation_state_consistent");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.invitation SET state = 'REVOKED', revoked_at = now(),"
                            + " revoked_by = 's', terminal_at = now() WHERE id = ?",
                        id)))
        .as("revoking keeps no token")
        .isEqualTo("invitation_state_consistent");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.invitation SET role = 'tenant-admin' WHERE id = ?", id)))
        .isEqualTo("invitation_ownership_immutable");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.invitation SET tenant_id = ? WHERE id = ?", tenantB, id)))
        .isEqualTo("invitation_ownership_immutable");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.invitation SET token_sha256 = ? WHERE id = ?",
                        sha256("other"),
                        id)))
        .isEqualTo("invitation_transition_allowed");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.invitation SET issue_count = 2, delivery_state = 'QUEUED'"
                            + " WHERE id = ?",
                        id)))
        .as("a reissue needs a new token")
        .isEqualTo("invitation_transition_allowed");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.invitation SET issue_count = 3, token_sha256 = ?,"
                            + " token_issued_at = now() + interval '1 second' WHERE id = ?",
                        sha256("x3"),
                        id)))
        .isEqualTo("invitation_transition_allowed");

    jdbc.update(
        "UPDATE identity.invitation SET state = 'REVOKED', token_sha256 = NULL, revoked_at = now(),"
            + " revoked_by = 's', terminal_at = now() WHERE id = ?",
        id);
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.invitation SET expires_at = expires_at + interval '1 day'"
                            + " WHERE id = ?",
                        id)))
        .isEqualTo("invitation_transition_allowed");
    // A late delivery result is still accepted on a terminal row.
    assertThat(
            jdbc.update("UPDATE identity.invitation SET delivery_state = 'SENT' WHERE id = ?", id))
        .isEqualTo(1);
  }

  @Test
  void anAcceptedInvitationAndItsMembershipAgreeOnTenantAndSource() {
    UUID invitation = UUID.randomUUID();
    invitation(invitation, tenantA, "ana@example.test", "employee");
    UUID membership = UUID.randomUUID();
    accept(invitation, membership, tenantA, sub("ana"), "employee", "ana@example.test");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM identity.invitation WHERE id = ?", String.class, invitation))
        .isEqualTo("ACCEPTED");

    // A membership whose source invitation is in another tenant. Since V10 (MVP-012A) the insert
    // trigger anchoring the address to the source invitation rejects these before the composite
    // foreign key, which still holds behind it.
    UUID foreign = UUID.randomUUID();
    invitation(foreign, tenantB, "bob@example.test", "employee");
    assertThat(
            constraintOf(
                () ->
                    membership(
                        UUID.randomUUID(),
                        tenantA,
                        sub("bob"),
                        "employee",
                        "bob@example.test",
                        foreign)))
        .isEqualTo("tenant_membership_email_from_source");
    // A membership whose role (or address) differs from its source invitation.
    assertThat(
            constraintOf(
                () ->
                    membership(
                        UUID.randomUUID(),
                        tenantB,
                        sub("bob"),
                        "tenant-admin",
                        "bob@example.test",
                        foreign)))
        .isEqualTo("tenant_membership_email_from_source");
    assertThat(
            constraintOf(
                () ->
                    membership(
                        UUID.randomUUID(),
                        tenantB,
                        sub("bob"),
                        "employee",
                        "eve@example.test",
                        foreign)))
        .isEqualTo("tenant_membership_email_from_source");

    // An accepted invitation pointing at the membership of another invitation (checked at commit).
    UUID other = UUID.randomUUID();
    invitation(other, tenantA, "cat@example.test", "employee");
    assertThat(
            constraintOf(
                () ->
                    transactions.executeWithoutResult(
                        status -> {
                          startAccepting(other);
                          jdbc.update(ACCEPT, membership, other);
                        })))
        .isEqualTo("invitation_membership_same_tenant_and_source");
    // ... or at a membership of another tenant.
    UUID crossTenant = UUID.randomUUID();
    invitation(crossTenant, tenantB, "dan@example.test", "employee");
    UUID bMembership = UUID.randomUUID();
    assertThat(
            constraintOf(
                () ->
                    transactions.executeWithoutResult(
                        status -> {
                          startAccepting(crossTenant);
                          membership(
                              bMembership,
                              tenantB,
                              sub("dan"),
                              "employee",
                              "dan@example.test",
                              crossTenant);
                          jdbc.update(ACCEPT, membership, crossTenant);
                        })))
        .isEqualTo("invitation_membership_same_tenant_and_source");
  }

  @Test
  void oneTenantPerSubjectAndMembershipsAreImmutable() {
    UUID invitation = UUID.randomUUID();
    invitation(invitation, tenantA, "one@example.test", "employee");
    UUID membership = UUID.randomUUID();
    accept(invitation, membership, tenantA, sub("one"), "employee", "one@example.test");
    UUID second = UUID.randomUUID();
    invitation(second, tenantB, "one-b@example.test", "employee");
    assertThat(
            constraintOf(
                () ->
                    membership(
                        UUID.randomUUID(),
                        tenantB,
                        sub("one"),
                        "employee",
                        "one-b@example.test",
                        second)))
        .isEqualTo("tenant_membership_one_tenant_per_subject");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.tenant_membership SET role = 'tenant-admin' WHERE id = ?",
                        membership)))
        .isEqualTo("tenant_membership_immutable");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.tenant_membership SET source_invitation_id = ? WHERE id ="
                            + " ?",
                        second,
                        membership)))
        .isEqualTo("tenant_membership_immutable");

    // Retention deletes the invitation; only the membership's source is cleared.
    jdbc.update("DELETE FROM identity.invitation WHERE id = ?", invitation);
    assertThat(
            jdbc.queryForObject(
                "SELECT source_invitation_id IS NULL FROM identity.tenant_membership WHERE id = ?",
                Boolean.class,
                membership))
        .isTrue();
  }

  @Test
  void credentialSetupAndLeaseColumnsFollowTheState() {
    UUID invitation = UUID.randomUUID();
    invitation(invitation, tenantA, "cred@example.test", "employee");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.invitation SET state = 'ACCEPTING',"
                            + " acceptance_lease_until = now() WHERE id = ?",
                        invitation)))
        .as("a lease needs an owner")
        .isEqualTo("invitation_state_consistent");
    accept(invitation, UUID.randomUUID(), tenantA, sub("cred"), "employee", "cred@example.test");
    assertThat(
            constraintOf(
                () ->
                    jdbc.update(
                        "UPDATE identity.invitation SET credential_setup_state = 'PENDING',"
                            + " credential_setup_next_at = NULL WHERE id = ?",
                        invitation)))
        .isEqualTo("invitation_state_consistent");
    assertThat(
            jdbc.update(
                "UPDATE identity.invitation SET credential_setup_state = 'SENT',"
                    + " credential_setup_next_at = NULL, credential_setup_attempts = 2 WHERE id ="
                    + " ?",
                invitation))
        .isEqualTo(1);
  }

  private static final String ACCEPT =
      """
      UPDATE identity.invitation SET state = 'ACCEPTED', token_sha256 = NULL,
        acceptance_lease_owner = NULL, acceptance_lease_until = NULL, accepted_at = now(),
        membership_id = ?, terminal_at = now(), credential_setup_state = 'SENT'
      WHERE id = ?
      """;

  private void accept(
      UUID invitation, UUID membership, UUID tenant, String subject, String role, String email) {
    transactions.executeWithoutResult(
        status -> {
          startAccepting(invitation);
          membership(membership, tenant, subject, role, email, invitation);
          jdbc.update(ACCEPT, membership, invitation);
        });
  }

  private void startAccepting(UUID invitation) {
    jdbc.update(
        "UPDATE identity.invitation SET state = 'ACCEPTING', acceptance_lease_owner = ?,"
            + " acceptance_lease_until = now() + interval '2 minutes' WHERE id = ?",
        UUID.randomUUID(),
        invitation);
  }

  private void invitation(UUID id, UUID tenant, String email, String role) {
    jdbc.update(
        """
        INSERT INTO identity.invitation
          (id, tenant_id, email, email_lookup, role, locale, state, token_sha256, token_issued_at,
           expires_at, delivery_updated_at, created_at, created_by)
        VALUES (?, ?, ?, ?, ?, 'fr', 'PENDING', ?, now(), now() + interval '7 days', now(), now(),
                'sub-db')
        """,
        id,
        tenant,
        email,
        sha256(email),
        role,
        sha256(id.toString()));
  }

  private void membership(
      UUID id, UUID tenant, String subject, String role, String email, UUID source) {
    jdbc.update(
        """
        INSERT INTO identity.tenant_membership
          (id, tenant_id, subject, role, email_lookup, source_invitation_id, created_at)
        VALUES (?, ?, ?, ?, ?, ?, now())
        """,
        id,
        tenant,
        subject,
        role,
        sha256(email),
        source);
  }

  private UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.organization
          (id, name, country_code, default_locale, timezone, created_at, created_by)
        VALUES (?, 'Invitation Constraint Org', 'CD', 'fr', 'Africa/Kinshasa', now(), 'sub-db')
        """,
        id);
    return id;
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** The violated constraint's name, whether it came from a CHECK, a key or a trigger. */
  private static String constraintOf(Runnable statement) {
    try {
      statement.run();
    } catch (RuntimeException failure) {
      // Deferred keys fail at commit, which may surface as a transaction exception.
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof PSQLException psql) {
          ServerErrorMessage message = psql.getServerErrorMessage();
          if (message != null && message.getConstraint() != null) {
            return message.getConstraint();
          }
          break;
        }
      }
      throw new AssertionError("failed without a named constraint", failure);
    }
    throw new AssertionError("statement unexpectedly succeeded");
  }
}
