package com.divalhr.core.identity;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Invitations.address;
import static com.divalhr.core.support.Invitations.anonymous;
import static com.divalhr.core.support.Invitations.invite;
import static com.divalhr.core.support.Invitations.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.identity.application.InvitationJobs;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.support.FakeIdentityDirectory;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.RecordingInvitationMailer;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-010 anonymous inspection and acceptance: enumeration resistance, provisioning through the
 * identity-provider port, the lease-owned completion, recovery, credential setup and concurrency.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PublicInvitationIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RecordingInvitationMailer mailer;
  @Autowired private FakeIdentityDirectory directory;
  @Autowired private InvitationJobs jobs;
  @Autowired private JdbcInvitationRepository invitations;
  @Autowired private TransactionTemplate transactions;

  private UUID tenant;

  @BeforeEach
  void setUp() throws Exception {
    mailer.reset();
    directory.reset();
    tenant = Hierarchy.newTenant(mvc);
  }

  private record Invited(UUID id, String email, String token) {}

  private Invited invited(String role) throws Exception {
    String email = address("inv");
    UUID id = UUID.fromString(invite(mvc, tenant, email, role).get("id").asText());
    return new Invited(id, email, mailer.lastTokenFor(email).orElseThrow());
  }

  @Test
  void inspectionRevealsOnlyRoleLocaleAndExpiry() throws Exception {
    Invited invited = invited("tenant-admin");
    String text =
        mvc.perform(anonymous("inspect", invited.token()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.role").value("tenant-admin"))
            .andExpect(jsonPath("$.locale").value("fr"))
            .andExpect(jsonPath("$.expiresAt").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> fields = new ArrayList<>();
    json(text).fieldNames().forEachRemaining(fields::add);
    assertThat(fields).containsExactlyInAnyOrder("role", "locale", "expiresAt");
    assertThat(text).doesNotContain(tenant.toString()).doesNotContain(invited.email());
  }

  @Test
  void everyUnusableTokenGivesTheSameAnswer() throws Exception {
    Invited revoked = invited("employee");
    mvc.perform(
            post("/api/v1/invitations/" + revoked.id() + "/revoke")
                .header("Authorization", admin(tenant)))
        .andExpect(status().isOk());
    Invited expired = invited("employee");
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = now() - interval '9 days',"
            + " expires_at = now() - interval '1 day' WHERE id = ?",
        expired.id());
    Invited accepted = invited("employee");
    mvc.perform(anonymous("accept", accepted.token())).andExpect(status().isOk());

    JsonNode reference = invalid("inspect", "A".repeat(43));
    for (String token :
        List.of(
            revoked.token(),
            expired.token(),
            accepted.token(),
            "short",
            "A".repeat(44),
            "A".repeat(42) + "=",
            "../../etc/passwd")) {
      assertThat(invalid("inspect", token)).isEqualTo(reference);
      assertThat(invalid("accept", token)).isEqualTo(reference);
    }
    assertThat(invalid("inspect", null)).isEqualTo(reference);
    mvc.perform(
            post("/api/v1/public/invitations/inspect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"x\", \"tenantId\": \"" + tenant + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].constraint").value("UNKNOWN_PROPERTY"));
  }

  @Test
  void employeeAcceptanceRequestsOnlyAPassword() throws Exception {
    Invited invited = invited("employee");
    mvc.perform(anonymous("accept", invited.token())).andExpect(status().isOk());
    assertThat(directory.credentialSetupRoles().values()).containsExactly(TenantRole.EMPLOYEE);
  }

  @Test
  void bearerTokensAreNeitherRequiredNorRead() throws Exception {
    Invited invited = invited("employee");
    // MVP-011: a password-level privileged token does not trigger MFA_REQUIRED here.
    String passwordLevelAdmin =
        "Bearer "
            + TestTokens.token()
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    for (String header :
        List.of(
            "Bearer not-a-jwt",
            admin(Hierarchy.newTenant(mvc)),
            passwordLevelAdmin,
            "Basic Zm9vOmJhcg==")) {
      mvc.perform(anonymous("inspect", invited.token()).header("Authorization", header))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.role").value("employee"));
    }
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/public/invitations/inspect"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void acceptanceProvisionsOneIdentityRecordsMembershipAndConsumesTheToken() throws Exception {
    Invited invited = invited("tenant-admin");
    mvc.perform(anonymous("accept", invited.token()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    FakeIdentityDirectory.Identity identity = directory.identities().get(invited.email());
    assertThat(identity.tenant()).isEqualTo(tenant.toString());
    assertThat(identity.role()).isEqualTo("tenant-admin");
    assertThat(identity.invitationId()).isEqualTo(invited.id());
    // Issue #31: the setup request is keyed by the invitation, never by the subject.
    assertThat(directory.credentialSetups()).containsExactly(invited.id());
    // MVP-011: an invited tenant administrator must also enroll an authenticator.
    assertThat(directory.credentialSetupRoles())
        .containsExactly(Map.entry(invited.id(), TenantRole.TENANT_ADMIN));

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT state, token_sha256 IS NULL AS consumed, credential_setup_state,"
                + " membership_id FROM identity.invitation WHERE id = ?",
            invited.id());
    assertThat(row)
        .containsEntry("state", "ACCEPTED")
        .containsEntry("consumed", true)
        .containsEntry("credential_setup_state", "SENT");
    Map<String, Object> membership =
        jdbc.queryForMap(
            "SELECT tenant_id, subject, role, source_invitation_id FROM identity.tenant_membership"
                + " WHERE id = ?",
            row.get("membership_id"));
    assertThat(membership)
        .containsEntry("tenant_id", tenant)
        .containsEntry("subject", identity.subject())
        .containsEntry("role", "tenant-admin")
        .containsEntry("source_invitation_id", invited.id());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'identity'"
                    + " AND table_name = 'tenant_membership' AND column_name = 'email'",
                Integer.class))
        .isZero();

    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT actor_subject, result, metadata::text AS metadata FROM platform.audit_event"
                + " WHERE resource_id = ? AND action = 'invitation.accept'",
            invited.id());
    assertThat(audit)
        .containsEntry("actor_subject", identity.subject())
        .containsEntry("result", "SUCCESS");
    String event =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?"
                + " AND event_type = 'identity.invitation-accepted.v1'",
            String.class,
            invited.id().toString());
    assertThat(event)
        .contains("membershipId")
        .doesNotContain(identity.subject())
        .doesNotContain(invited.email());

    // Single use.
    assertThat(invalid("accept", invited.token())).isNotNull();
    assertThat(directory.identities()).hasSize(1);
    // The administrator sees it accepted.
    JsonNode page =
        json(
            mvc.perform(list(admin(tenant), "status=ACCEPTED"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page.get("data").get(0).get("acceptedAt").asText()).isNotEmpty();
  }

  @Test
  void anAddressWithAnIdentityCannotAcceptAndNothingIsDisclosed() throws Exception {
    Invited invited = invited("employee");
    directory.existing(invited.email());
    String text =
        mvc.perform(anonymous("accept", invited.token()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVITATION_CANNOT_BE_ACCEPTED"))
            .andExpect(jsonPath("$.params").isEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(text).doesNotContain(invited.email()).doesNotContain(tenant.toString());
    assertThat(state(invited.id())).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT metadata ->> 'reason' FROM platform.audit_event WHERE resource_id = ?"
                    + " AND action = 'invitation.accept' AND result = 'DENIED'",
                String.class,
                invited.id()))
        .isEqualTo("not_acceptable");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.tenant_membership WHERE source_invitation_id = ?",
                Integer.class,
                invited.id()))
        .isZero();
    // The administrator only sees a pending invitation.
    JsonNode page =
        json(mvc.perform(list(admin(tenant), "")).andReturn().getResponse().getContentAsString());
    assertThat(page.get("data").get(0).get("status").asText()).isEqualTo("PENDING");
  }

  @Test
  void anUnreachableProviderLeavesTheInvitationPendingAndRetryable() throws Exception {
    Invited invited = invited("employee");
    directory.mode(FakeIdentityDirectory.Mode.DOWN);
    mvc.perform(anonymous("accept", invited.token()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("IDENTITY_PROVIDER_UNAVAILABLE"));
    assertThat(state(invited.id())).isEqualTo("PENDING");
    directory.mode(FakeIdentityDirectory.Mode.UP);
    mvc.perform(anonymous("accept", invited.token())).andExpect(status().isOk());
    assertThat(state(invited.id())).isEqualTo("ACCEPTED");
  }

  @Test
  void credentialSetupIsDurableAndRetriedUntilSentOrFailed() throws Exception {
    Invited invited = invited("tenant-admin");
    directory.mode(FakeIdentityDirectory.Mode.CREDENTIAL_SETUP_DOWN);
    mvc.perform(anonymous("accept", invited.token())).andExpect(status().isOk());
    Map<String, Object> row = credential(invited.id());
    assertThat(row).containsEntry("credential_setup_state", "PENDING").containsEntry("attempts", 1);
    assertThat(row.get("next_at")).isNotNull();

    // Not due yet: nothing happens.
    assertThat(jobs.retryCredentialSetups()).isZero();
    makeCredentialSetupDue(invited.id());
    directory.mode(FakeIdentityDirectory.Mode.UP);
    assertThat(jobs.retryCredentialSetups()).isEqualTo(1);
    assertThat(credential(invited.id()))
        .containsEntry("credential_setup_state", "SENT")
        .containsEntry("attempts", 2);
    assertThat(directory.credentialSetups()).hasSize(1);
    // The retry keeps the invited role, so the administrator still gets the TOTP action.
    assertThat(directory.credentialSetupRoles().values()).containsExactly(TenantRole.TENANT_ADMIN);

    // Exhausted retries end in FAILED, never in an endless loop.
    Invited other = invited("employee");
    directory.mode(FakeIdentityDirectory.Mode.CREDENTIAL_SETUP_DOWN);
    mvc.perform(anonymous("accept", other.token())).andExpect(status().isOk());
    for (int attempt = 2; attempt <= 5; attempt++) {
      makeCredentialSetupDue(other.id());
      jobs.retryCredentialSetups();
    }
    assertThat(credential(other.id()))
        .containsEntry("credential_setup_state", "FAILED")
        .containsEntry("attempts", 5);
    assertThat(credential(other.id()).get("next_at")).isNull();
  }

  @Test
  void aLiveLeaseMakesAConcurrentAcceptanceWaitAndAnExpiredOneIsTakenOver() throws Exception {
    Invited invited = invited("employee");
    jdbc.update(
        "UPDATE identity.invitation SET state = 'ACCEPTING', acceptance_lease_owner = ?,"
            + " acceptance_lease_until = now() + interval '1 minute' WHERE id = ?",
        UUID.randomUUID(),
        invited.id());
    mvc.perform(anonymous("accept", invited.token()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVITATION_ACCEPTANCE_IN_PROGRESS"));
    mvc.perform(anonymous("inspect", invited.token())).andExpect(status().isOk());

    jdbc.update(
        "UPDATE identity.invitation SET acceptance_lease_until = now() - interval '1 second'"
            + " WHERE id = ?",
        invited.id());
    mvc.perform(anonymous("accept", invited.token())).andExpect(status().isOk());
    assertThat(state(invited.id())).isEqualTo("ACCEPTED");
  }

  @Test
  void aWorkerWhoseLeaseWasTakenOverCanNeverComplete() throws Exception {
    Invited invited = invited("employee");
    UUID usurper = UUID.randomUUID();
    // While the first worker provisions, another worker takes the lease over.
    directory.beforeProvision(
        () ->
            jdbc.update(
                "UPDATE identity.invitation SET acceptance_lease_owner = ? WHERE id = ?"
                    + " AND state = 'ACCEPTING'",
                usurper,
                invited.id()));
    mvc.perform(anonymous("accept", invited.token()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVITATION_ACCEPTANCE_IN_PROGRESS"));
    assertThat(state(invited.id())).isEqualTo("ACCEPTING");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.tenant_membership WHERE source_invitation_id = ?",
                Integer.class,
                invited.id()))
        .isZero();
    // Directly: the old owner can neither lock nor complete.
    UUID stale = UUID.randomUUID();
    Boolean completed =
        transactions.execute(
            status ->
                invitations.lockOwnedAcceptance(invited.id(), stale).isPresent()
                    || invitations.completeAcceptance(
                        invited.id(), stale, UUID.randomUUID(), Instant.now()));
    assertThat(completed).isFalse();

    // The reconciler takes over once the lease expires and finishes with the same identity.
    directory.beforeProvision(() -> {});
    jdbc.update(
        "UPDATE identity.invitation SET acceptance_lease_until = now() - interval '1 second'"
            + " WHERE id = ?",
        invited.id());
    assertThat(jobs.recoverStaleAcceptances()).isEqualTo(1);
    assertThat(state(invited.id())).isEqualTo("ACCEPTED");
    assertThat(directory.identities()).hasSize(1);
  }

  @Test
  void aCrashAfterProvisioningIsCompletedByTheReconcilerWithoutASecondIdentity() throws Exception {
    Invited invited = invited("tenant-admin");
    // Simulate a worker that provisioned the identity and died before completing.
    directory.provision(
        new com.divalhr.core.identity.application.IdentityDirectory.ProvisioningRequest(
            invited.id(),
            new com.divalhr.core.platform.tenancy.TenantId(tenant),
            new com.divalhr.core.identity.domain.EmailAddress(invited.email()),
            com.divalhr.core.identity.domain.TenantRole.TENANT_ADMIN,
            com.divalhr.core.identity.domain.InvitationLocale.FR));
    String subject = directory.identities().get(invited.email()).subject();
    jdbc.update(
        "UPDATE identity.invitation SET state = 'ACCEPTING', acceptance_lease_owner = ?,"
            + " acceptance_lease_until = now() - interval '1 second' WHERE id = ?",
        UUID.randomUUID(),
        invited.id());
    assertThat(jobs.recoverStaleAcceptances()).isEqualTo(1);
    assertThat(state(invited.id())).isEqualTo("ACCEPTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT subject FROM identity.tenant_membership WHERE source_invitation_id = ?",
                String.class,
                invited.id()))
        .isEqualTo(subject);
    assertThat(directory.identities()).hasSize(1);
  }

  @Test
  void aStalledAcceptancePastExpiryIsCompensatedAndExpired() throws Exception {
    Invited invited = invited("employee");
    directory.provision(
        new com.divalhr.core.identity.application.IdentityDirectory.ProvisioningRequest(
            invited.id(),
            new com.divalhr.core.platform.tenancy.TenantId(tenant),
            new com.divalhr.core.identity.domain.EmailAddress(invited.email()),
            com.divalhr.core.identity.domain.TenantRole.EMPLOYEE,
            com.divalhr.core.identity.domain.InvitationLocale.FR));
    jdbc.update(
        "UPDATE identity.invitation SET state = 'ACCEPTING', acceptance_lease_owner = ?,"
            + " acceptance_lease_until = now() - interval '1 second',"
            + " token_issued_at = now() - interval '9 days', expires_at = now() - interval '1 hour'"
            + " WHERE id = ?",
        UUID.randomUUID(),
        invited.id());
    assertThat(jobs.recoverStaleAcceptances()).isEqualTo(1);
    assertThat(state(invited.id())).isEqualTo("EXPIRED");
    assertThat(directory.compensations()).containsExactly(invited.id());
    assertThat(directory.identities()).isEmpty();
  }

  @Test
  void anInvalidSetupStateIsFailedAtOnceAndAlertedNeverSent(CapturedOutput output)
      throws Exception {
    // Issue #31 A1: a partial or drifted identity is never recorded as SENT and never retried.
    Invited invited = invited("tenant-admin");
    directory.mode(FakeIdentityDirectory.Mode.CREDENTIAL_SETUP_INVALID);
    mvc.perform(anonymous("accept", invited.token())).andExpect(status().isOk());
    assertThat(credential(invited.id()))
        .containsEntry("credential_setup_state", "FAILED")
        .containsEntry("attempts", 1);
    assertThat(credential(invited.id()).get("next_at")).isNull();
    assertThat(jobs.retryCredentialSetups()).isZero();
    assertThat(output.getAll())
        .contains("invitation_credential_setup_invalid_state")
        .doesNotContain(invited.email());
  }

  @Test
  void aProvenCompletedSetupIsRecordedAsSent() throws Exception {
    Invited invited = invited("employee");
    directory.mode(FakeIdentityDirectory.Mode.CREDENTIAL_SETUP_COMPLETED);
    mvc.perform(anonymous("accept", invited.token())).andExpect(status().isOk());
    assertThat(credential(invited.id())).containsEntry("credential_setup_state", "SENT");
  }

  @Test
  void aRefusedCompensationIsAlertedAndNeverLoops(CapturedOutput output) throws Exception {
    // Issue #31: the provider refuses to delete an identity that is no longer pristine. The
    // stalled acceptance still expires, and the identity is left to a realm administrator.
    Invited invited = invited("employee");
    directory.provision(
        new com.divalhr.core.identity.application.IdentityDirectory.ProvisioningRequest(
            invited.id(),
            new com.divalhr.core.platform.tenancy.TenantId(tenant),
            new com.divalhr.core.identity.domain.EmailAddress(invited.email()),
            com.divalhr.core.identity.domain.TenantRole.EMPLOYEE,
            com.divalhr.core.identity.domain.InvitationLocale.FR));
    directory.mode(FakeIdentityDirectory.Mode.COMPENSATION_REFUSED);
    jdbc.update(
        "UPDATE identity.invitation SET state = 'ACCEPTING', acceptance_lease_owner = ?,"
            + " acceptance_lease_until = now() - interval '1 second',"
            + " token_issued_at = now() - interval '9 days', expires_at = now() - interval '1 hour'"
            + " WHERE id = ?",
        UUID.randomUUID(),
        invited.id());
    assertThat(jobs.recoverStaleAcceptances()).isEqualTo(1);
    assertThat(state(invited.id())).isEqualTo("EXPIRED");
    assertThat(jobs.recoverStaleAcceptances()).isZero();
    assertThat(directory.compensations()).containsExactly(invited.id());
    assertThat(directory.identities()).hasSize(1);
    assertThat(output.getAll())
        .contains("invitation_accept_compensation_refused")
        .doesNotContain(invited.email());
  }

  @Test
  void parallelAcceptancesOfOneTokenYieldExactlyOneMembership() throws Exception {
    Invited invited = invited("employee");
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    directory.beforeProvision(
        () -> {
          inside.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      Callable<Integer> accept =
          () ->
              mvc.perform(anonymous("accept", invited.token()))
                  .andReturn()
                  .getResponse()
                  .getStatus();
      Future<Integer> first = pool.submit(accept);
      assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
      List<Future<Integer>> others = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        others.add(pool.submit(accept));
      }
      List<Integer> otherStatuses = new ArrayList<>();
      for (Future<Integer> other : others) {
        otherStatuses.add(other.get(20, TimeUnit.SECONDS));
      }
      // Revoking while the acceptance is in progress is refused.
      mvc.perform(
              post("/api/v1/invitations/" + invited.id() + "/revoke")
                  .header("Authorization", admin(tenant)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("INVITATION_NOT_PENDING"));
      release.countDown();
      assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(200);
      assertThat(otherStatuses).containsOnly(409);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.tenant_membership WHERE source_invitation_id = ?",
                Integer.class,
                invited.id()))
        .isEqualTo(1);
    assertThat(directory.identities()).hasSize(1);
  }

  @Test
  void anonymousFlowLogsNeitherTokensNorAddresses(CapturedOutput output) throws Exception {
    Invited invited = invited("employee");
    mvc.perform(anonymous("inspect", invited.token())).andExpect(status().isOk());
    mvc.perform(anonymous("accept", invited.token())).andExpect(status().isOk());
    mvc.perform(anonymous("accept", invited.token())).andExpect(status().isNotFound());
    assertThat(output.getAll())
        .contains("invitation_accepted")
        .doesNotContain(invited.token())
        .doesNotContain(invited.email())
        .doesNotContain(directory.identities().get(invited.email()).subject());
  }

  private JsonNode invalid(String action, String token) throws Exception {
    String text =
        mvc.perform(anonymous(action, token))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("INVITATION_INVALID"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ObjectNode node = (ObjectNode) json(text);
    node.remove("correlationId");
    node.remove("instance");
    return node;
  }

  private String state(UUID id) {
    return jdbc.queryForObject(
        "SELECT state FROM identity.invitation WHERE id = ?", String.class, id);
  }

  private Map<String, Object> credential(UUID id) {
    return jdbc.queryForMap(
        "SELECT credential_setup_state, credential_setup_attempts AS attempts,"
            + " credential_setup_next_at AS next_at FROM identity.invitation WHERE id = ?",
        id);
  }

  private void makeCredentialSetupDue(UUID id) {
    jdbc.update(
        "UPDATE identity.invitation SET credential_setup_next_at = now() - interval '1 second'"
            + " WHERE id = ? AND credential_setup_state = 'PENDING'",
        id);
  }
}
