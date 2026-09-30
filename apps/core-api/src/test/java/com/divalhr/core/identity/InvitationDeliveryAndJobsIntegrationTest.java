package com.divalhr.core.identity;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Invitations.address;
import static com.divalhr.core.support.Invitations.anonymous;
import static com.divalhr.core.support.Invitations.invite;
import static com.divalhr.core.support.Invitations.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.identity.api.CreateInvitationRequest;
import com.divalhr.core.identity.application.CreateInvitationService;
import com.divalhr.core.identity.application.InvitationJobs;
import com.divalhr.core.identity.domain.DeliveryState;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.FakeIdentityDirectory;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.RecordingInvitationMailer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Architecture amendment A3 (the gap between the committed invitation and its email) and the
 * background jobs: expiry, stale deliveries and retention.
 */
@IntegrationTest
class InvitationDeliveryAndJobsIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;
  @Autowired private RecordingInvitationMailer mailer;
  @Autowired private FakeIdentityDirectory directory;
  @Autowired private CreateInvitationService creates;
  @Autowired private InvitationJobs jobs;
  @Autowired private JdbcInvitationRepository invitations;

  private UUID tenant;

  @BeforeEach
  void setUp() throws Exception {
    mailer.reset();
    directory.reset();
    tenant = Hierarchy.newTenant(mvc);
  }

  @Test
  void aCrashBeforeSendingLeavesQueuedWhichBecomesFailedAndIsNeverRetried() throws Exception {
    String email = address("crash-before");
    mailer.mode(RecordingInvitationMailer.Mode.CRASH_BEFORE_SEND);
    assertThatThrownBy(() -> createDirectly(email))
        .isInstanceOf(RecordingInvitationMailer.SimulatedCrash.class);
    UUID id = idOf(email);
    assertThat(delivery(id)).isEqualTo("QUEUED");
    assertThat(mailer.sent()).isEmpty();

    // Not stale yet.
    assertThat(jobs.failStaleDeliveries()).isZero();
    makeDeliveryStale(id);
    mailer.mode(RecordingInvitationMailer.Mode.SENT);
    assertThat(jobs.failStaleDeliveries()).isEqualTo(1);
    assertThat(delivery(id)).isEqualTo("FAILED");
    assertThat(mailer.sent()).as("never retried: the token no longer exists").isEmpty();
    assertThat(listed(id, "deliveryState")).isEqualTo("FAILED");

    // The administrator reissues: a new link is sent and delivery becomes SENT.
    resend(id);
    assertThat(delivery(id)).isEqualTo("SENT");
    assertThat(mailer.sent()).hasSize(1);
    mvc.perform(anonymous("inspect", mailer.lastTokenFor(email).orElseThrow()))
        .andExpect(status().isOk());
  }

  @Test
  void aCrashAfterSmtpSuccessLeavesQueuedAndOnlyTheNewestLinkIsAuthoritative() throws Exception {
    String email = address("crash-after");
    mailer.mode(RecordingInvitationMailer.Mode.CRASH_AFTER_SEND);
    assertThatThrownBy(() -> createDirectly(email))
        .isInstanceOf(RecordingInvitationMailer.SimulatedCrash.class);
    UUID id = idOf(email);
    String delivered = mailer.lastTokenFor(email).orElseThrow();
    assertThat(delivery(id)).isEqualTo("QUEUED");
    makeDeliveryStale(id);
    assertThat(jobs.failStaleDeliveries()).isEqualTo(1);
    assertThat(delivery(id)).isEqualTo("FAILED");
    // Accepted limitation: the message may have arrived, so its link still works until reissued.
    mvc.perform(anonymous("inspect", delivered)).andExpect(status().isOk());

    mailer.mode(RecordingInvitationMailer.Mode.SENT);
    resend(id);
    String newest = mailer.lastTokenFor(email).orElseThrow();
    assertThat(newest).isNotEqualTo(delivered);
    mvc.perform(anonymous("inspect", delivered)).andExpect(status().isNotFound());
    mvc.perform(anonymous("inspect", newest)).andExpect(status().isOk());
  }

  @Test
  void anSmtpTimeoutOrRefusalIsRecordedAsFailed() throws Exception {
    mailer.mode(RecordingInvitationMailer.Mode.FAILED);
    String email = address("timeout");
    mvc.perform(
            com.divalhr.core.support.Invitations.create(
                admin(tenant),
                Organizations.newKey(),
                com.divalhr.core.support.Invitations.body(email, "employee", "en")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.deliveryState").value("QUEUED"));
    UUID id = idOf(email);
    assertThat(delivery(id)).isEqualTo("FAILED");
    assertThat(listed(id, "deliveryState")).isEqualTo("FAILED");
  }

  @Test
  void aLateResultForAnOlderLinkNeverOverwritesTheNewestIssuance() throws Exception {
    String email = address("race");
    mailer.mode(RecordingInvitationMailer.Mode.CRASH_BEFORE_SEND);
    assertThatThrownBy(() -> createDirectly(email))
        .isInstanceOf(RecordingInvitationMailer.SimulatedCrash.class);
    UUID id = idOf(email);
    mailer.mode(RecordingInvitationMailer.Mode.SENT);
    resend(id);
    assertThat(delivery(id)).isEqualTo("SENT");
    assertThat(issue(id)).isEqualTo(2);

    // The first attempt's result arrives late.
    assertThat(
            invitations.recordDelivery(
                new TenantId(tenant), id, 1, DeliveryState.FAILED, Instant.now()))
        .isFalse();
    assertThat(delivery(id)).isEqualTo("SENT");

    // A late result for the current issuance after the stale job gave up is ignored too.
    mailer.mode(RecordingInvitationMailer.Mode.CRASH_BEFORE_SEND);
    // The simulated crash strikes after the reissue committed: the request fails, issuance 3
    // stays QUEUED with no delivery result.
    resend(id, status().isInternalServerError());
    assertThat(issue(id)).isEqualTo(3);
    assertThat(delivery(id)).isEqualTo("QUEUED");
    makeDeliveryStale(id);
    jobs.failStaleDeliveries();
    assertThat(
            invitations.recordDelivery(
                new TenantId(tenant), id, 3, DeliveryState.SENT, Instant.now()))
        .isFalse();
    assertThat(delivery(id)).isEqualTo("FAILED");
  }

  @Test
  void theExpiryJobMaterializesExpiryWithAuditAndEvent() throws Exception {
    String email = address("expire");
    UUID id = UUID.fromString(invite(mvc, tenant, email, "employee").get("id").asText());
    String token = mailer.lastTokenFor(email).orElseThrow();
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = now() - interval '9 days',"
            + " expires_at = now() - interval '1 second' WHERE id = ?",
        id);
    // Derived immediately, before the job runs.
    assertThat(listed(id, "status")).isEqualTo("EXPIRED");
    mvc.perform(anonymous("accept", token)).andExpect(status().isNotFound());

    assertThat(jobs.expireDue()).isGreaterThanOrEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                "SELECT state, token_sha256 IS NULL AS erased FROM identity.invitation WHERE id ="
                    + " ?",
                id))
        .containsEntry("state", "EXPIRED")
        .containsEntry("erased", true);
    assertThat(
            jdbc.queryForObject(
                "SELECT actor_subject FROM platform.audit_event WHERE resource_id = ?"
                    + " AND action = 'invitation.expire'",
                String.class,
                id))
        .isEqualTo("system:invitation-expiry");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'subject' = ?"
                    + " AND event_type = 'identity.invitation-expired.v1'",
                Integer.class,
                id.toString()))
        .isEqualTo(1);
    // A second run does nothing more; the address can be invited again.
    jobs.expireDue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.audit_event WHERE resource_id = ?"
                    + " AND action = 'invitation.expire'",
                Integer.class,
                id))
        .isEqualTo(1);
    invite(mvc, tenant, email, "employee");
  }

  @Test
  void creatingAgainForAnAddressWhoseInvitationLapsedExpiresTheOldOneInline() throws Exception {
    String email = address("lapsed");
    UUID old = UUID.fromString(invite(mvc, tenant, email, "employee").get("id").asText());
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = now() - interval '9 days',"
            + " expires_at = now() - interval '1 second' WHERE id = ?",
        old);
    invite(mvc, tenant, email, "tenant-admin");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM identity.invitation WHERE id = ?", String.class, old))
        .isEqualTo("EXPIRED");
  }

  @Test
  void retentionDeletesOldTerminalInvitationsAndKeepsMembershipsWithoutAddresses()
      throws Exception {
    String email = address("retained");
    UUID accepted = UUID.fromString(invite(mvc, tenant, email, "employee").get("id").asText());
    mvc.perform(anonymous("accept", mailer.lastTokenFor(email).orElseThrow()))
        .andExpect(status().isOk());
    UUID revoked =
        UUID.fromString(invite(mvc, tenant, address("gone"), "employee").get("id").asText());
    mvc.perform(
            post("/api/v1/invitations/" + revoked + "/revoke")
                .header("Authorization", admin(tenant)))
        .andExpect(status().isOk());
    UUID recent =
        UUID.fromString(invite(mvc, tenant, address("recent"), "employee").get("id").asText());
    mvc.perform(
            post("/api/v1/invitations/" + recent + "/revoke")
                .header("Authorization", admin(tenant)))
        .andExpect(status().isOk());
    // Terminal rows are immutable; age them with triggers bypassed (test setup only).
    transactions.executeWithoutResult(
        status -> {
          jdbc.execute("SET LOCAL session_replication_role = replica");
          jdbc.update(
              "UPDATE identity.invitation SET terminal_at = now() - interval '91 days'"
                  + " WHERE id IN (?, ?)",
              accepted,
              revoked);
        });
    assertThat(jobs.deleteRetained()).isGreaterThanOrEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.invitation WHERE id IN (?, ?)",
                Integer.class,
                accepted,
                revoked))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.invitation WHERE id = ?", Integer.class, recent))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.tenant_membership WHERE tenant_id = ?"
                    + " AND source_invitation_id IS NULL",
                Integer.class,
                tenant))
        .isEqualTo(1);
    // Audit history remains, without the address.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.audit_event WHERE resource_id = ?"
                    + " AND metadata::text NOT LIKE ?",
                Integer.class,
                accepted,
                "%" + email + "%"))
        .isGreaterThanOrEqualTo(2);
  }

  private void createDirectly(String email) {
    creates.create(
        new TenantId(tenant),
        "sub-admin-" + tenant,
        Organizations.newKey(),
        CreateInvitationRequest.of(email, "employee", "fr"),
        "corr-delivery-test");
  }

  private void resend(UUID id) throws Exception {
    resend(id, status().isOk());
  }

  private void resend(UUID id, ResultMatcher expected) throws Exception {
    backdateIssue(id);
    mvc.perform(
            post("/api/v1/invitations/" + id + "/resend")
                .header("Authorization", admin(tenant))
                .header("Idempotency-Key", Organizations.newKey()))
        .andExpect(expected);
  }

  private UUID idOf(String email) {
    return jdbc.queryForObject(
        "SELECT id FROM identity.invitation WHERE tenant_id = ? AND email = ?",
        UUID.class,
        tenant,
        email);
  }

  private String delivery(UUID id) {
    return jdbc.queryForObject(
        "SELECT delivery_state FROM identity.invitation WHERE id = ?", String.class, id);
  }

  private int issue(UUID id) {
    return jdbc.queryForObject(
        "SELECT issue_count FROM identity.invitation WHERE id = ?", Integer.class, id);
  }

  private void makeDeliveryStale(UUID id) {
    jdbc.update(
        "UPDATE identity.invitation SET delivery_updated_at = now() - interval '11 minutes'"
            + " WHERE id = ?",
        id);
  }

  private void backdateIssue(UUID id) {
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = token_issued_at - interval '10 minutes'"
            + " WHERE id = ?",
        id);
  }

  private String listed(UUID id, String field) throws Exception {
    for (var row :
        json(mvc.perform(list(admin(tenant), "")).andReturn().getResponse().getContentAsString())
            .get("data")) {
      if (row.get("id").asText().equals(id.toString())) {
        return row.get(field).asText();
      }
    }
    throw new AssertionError("invitation not listed");
  }
}
