package com.divalhr.core.identity;

import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Invitations.address;
import static com.divalhr.core.support.Invitations.anonymous;
import static com.divalhr.core.support.Invitations.invite;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.support.Bootstraps;
import com.divalhr.core.support.FakeIdentityDirectory;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Invitations;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.RecordingInvitationMailer;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MVP-014: a platform administrator invites an organization's first tenant administrator. Covers
 * authorization order, the bootstrap rule, target selection, idempotency, the per-actor limit,
 * status, revoke and resend, tenant-side visibility, events, audit and privacy.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class TenantAdminBootstrapApiIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RecordingInvitationMailer mailer;
  @Autowired private FakeIdentityDirectory directory;

  private UUID organization;
  private String subject;
  private String platform;

  @BeforeEach
  void setUp() throws Exception {
    mailer.reset();
    directory.reset();
    organization = Hierarchy.newTenant(mvc);
    subject = Bootstraps.platformSubject();
    platform = Bootstraps.platform(subject);
  }

  private JsonNode bootstrap(String email) throws Exception {
    return json(
        mvc.perform(
                Bootstraps.create(
                    platform, organization, Organizations.newKey(), Bootstraps.body(email, "fr")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private void unavailable(String email) throws Exception {
    mvc.perform(
            Bootstraps.create(
                platform, organization, Organizations.newKey(), Bootstraps.body(email, "en")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE"))
        .andExpect(jsonPath("$.params").isEmpty());
  }

  /** A tenant administrator created through the supported tenant path (accepted invitation). */
  private void acceptedTenantAdministrator() throws Exception {
    String email = address("admin");
    invite(mvc, organization, email, "tenant-admin");
    mvc.perform(anonymous("accept", mailer.lastTokenFor(email).orElseThrow()))
        .andExpect(status().isOk());
  }

  // --- authorization (A4) -------------------------------------------------------------------

  @Test
  void onlyAPlatformAdministratorWithMfaMayCallAndMfaIsCheckedBeforeTheBody() throws Exception {
    String body = Bootstraps.body(address("first"), "fr");
    for (String bearer :
        List.of(
            Hierarchy.admin(organization),
            Hierarchy.bearer(organization, "sub-employee", "employee"))) {
      mvc.perform(Bootstraps.create(bearer, organization, Organizations.newKey(), body))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      mvc.perform(Bootstraps.status(bearer, organization))
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .subject(subject)
                .roles(List.of("platform-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    // An invalid body, a malformed organization and a missing key still give MFA_REQUIRED.
    mvc.perform(Bootstraps.create(passwordOnly, "not-an-id", null, "{\"role\":\"x\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MFA_REQUIRED"))
        .andExpect(header().exists("WWW-Authenticate"));
    for (var request :
        List.of(
            Bootstraps.status(passwordOnly, organization),
            Bootstraps.revoke(passwordOnly, organization),
            Bootstraps.resend(passwordOnly, organization, Organizations.newKey()))) {
      mvc.perform(request)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
    }
    mvc.perform(post(Bootstraps.path(organization)).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
    assertThat(mailer.sent()).isEmpty();
  }

  // --- creation and the bootstrap rule ------------------------------------------------------

  @Test
  void createsATenantAdminInvitationForThePathOrganizationOnly() throws Exception {
    String email = address("first");
    MvcResult result =
        mvc.perform(
                Bootstraps.create(
                    platform, organization, Organizations.newKey(), Bootstraps.body(email, "fr")))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(jsonPath("$.role").value("tenant-admin"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.deliveryState").value("QUEUED"))
            .andExpect(jsonPath("$.email").doesNotExist())
            .andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain(email);
    UUID id = UUID.fromString(json(result.getResponse().getContentAsString()).get("id").asText());
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT tenant_id, role, origin, created_by, locale FROM identity.invitation WHERE id ="
                + " ?",
            id);
    // The platform token's own tenant claim (tenant A) is irrelevant.
    assertThat(row)
        .containsEntry("tenant_id", organization)
        .containsEntry("role", "tenant-admin")
        .containsEntry("origin", "PLATFORM_BOOTSTRAP")
        .containsEntry("created_by", subject)
        .containsEntry("locale", "fr");
    assertThat(mailer.sent()).hasSize(1);
    assertThat(mailer.sent().get(0).to().value()).isEqualTo(email);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.invitation WHERE created_by = ? AND tenant_id <> ?",
                Integer.class,
                subject,
                organization))
        .isZero();
  }

  @Test
  void bootstrapIsBlockedByAnAdministratorOrAnyOpenAdministratorInvitation() throws Exception {
    // An open bootstrap blocks a second one.
    bootstrap(address("first"));
    unavailable(address("second"));
    mvc.perform(Bootstraps.revoke(platform, organization)).andExpect(status().isNoContent());
    // An open tenant-created administrator invitation blocks it too (any origin).
    invite(mvc, organization, address("tenant-admin-invitee"), "tenant-admin");
    unavailable(address("third"));
    // Employees never block.
    UUID other = Hierarchy.newTenant(mvc);
    organization = other;
    invite(mvc, other, address("employee"), "employee");
    bootstrap(address("fourth"));
  }

  @Test
  void aTenantAdministratorMembershipBlocksBootstrapAndStatusSaysSo() throws Exception {
    acceptedTenantAdministrator();
    unavailable(address("late"));
    mvc.perform(Bootstraps.status(platform, organization))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.invitation").isEmpty());
  }

  @Test
  void anExpiredBootstrapIsExpiredInlineAndNoLongerBlocks() throws Exception {
    UUID id = UUID.fromString(bootstrap(address("first")).get("id").asText());
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = now() - interval '9 days',"
            + " expires_at = now() - interval '1 second' WHERE id = ?",
        id);
    bootstrap(address("second"));
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM identity.invitation WHERE id = ?", String.class, id))
        .isEqualTo("EXPIRED");
  }

  @Test
  void unknownMalformedAndOtherOrganizationsAreIndistinguishable(CapturedOutput output)
      throws Exception {
    String unknown = UUID.randomUUID().toString();
    JsonNode first = notFound(Bootstraps.status(platform, unknown));
    assertThat(notFound(Bootstraps.status(platform, "not-a-uuid"))).isEqualTo(first);
    assertThat(
            notFound(
                Bootstraps.create(
                    platform,
                    unknown,
                    Organizations.newKey(),
                    Bootstraps.body(address("x"), "fr"))))
        .isEqualTo(first);
    assertThat(notFound(Bootstraps.revoke(platform, unknown))).isEqualTo(first);
    assertThat(notFound(Bootstraps.resend(platform, "zz", Organizations.newKey())))
        .isEqualTo(first);
    // Invalid or foreign organization IDs never reach logs.
    assertThat(output.getAll()).doesNotContain(unknown).doesNotContain("not-a-uuid");
  }

  private JsonNode notFound(org.springframework.test.web.servlet.RequestBuilder request)
      throws Exception {
    String text =
        mvc.perform(request)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ObjectNode node = (ObjectNode) json(text);
    node.remove("correlationId");
    node.remove("instance");
    return node;
  }

  @Test
  void theBodyIsStrictAndTheRoleIsFixed() throws Exception {
    for (String body :
        List.of(
            "{\"email\":\"a@example.test\",\"locale\":\"fr\",\"role\":\"employee\"}",
            "{\"email\":\"a@example.test\",\"locale\":\"fr\",\"tenantId\":\""
                + organization
                + "\"}",
            "{\"email\":\"not an address\",\"locale\":\"fr\"}",
            "{\"email\":\"a@example.test\",\"locale\":\"de\"}")) {
      String text =
          mvc.perform(Bootstraps.create(platform, organization, Organizations.newKey(), body))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(text).doesNotContain("a@example.test").doesNotContain("not an address");
    }
    assertThat(mailer.sent()).isEmpty();
  }

  // --- idempotency (A3) ---------------------------------------------------------------------

  @Test
  void aLostResponseRetryReplaysExactlyAndAKeyCannotMoveToAnotherOrganization() throws Exception {
    String key = Organizations.newKey();
    String body = Bootstraps.body(address("first"), "en");
    String first =
        mvc.perform(Bootstraps.create(platform, organization, key, body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // The client never saw the response and retries: the exact receipt, no second email.
    mvc.perform(Bootstraps.create(platform, organization, key, body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(
            result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(first));
    assertThat(mailer.sent()).hasSize(1);
    UUID other = Hierarchy.newTenant(mvc);
    mvc.perform(Bootstraps.create(platform, other, key, body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    mvc.perform(Bootstraps.create(platform, organization, key, Bootstraps.body(address("x"), "en")))
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.invitation WHERE tenant_id IN (?, ?)",
                Integer.class,
                organization,
                other))
        .isEqualTo(1);
    String stored =
        jdbc.queryForObject(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation = 'tenant-admin-bootstrap.create' AND idempotency_key = ?",
            String.class,
            key);
    assertThat(stored).doesNotContain("@example.test");
  }

  // --- per-actor limit (A8) -----------------------------------------------------------------

  @Test
  void onePlatformAdministratorCanBootstrapOnlyAFewOrganizationsPerHour() throws Exception {
    for (int i = 0; i < 5; i++) {
      organization = Hierarchy.newTenant(mvc);
      bootstrap(address("first"));
    }
    UUID sixth = Hierarchy.newTenant(mvc);
    String text =
        mvc.perform(
                Bootstraps.create(
                    platform, sixth, Organizations.newKey(), Bootstraps.body(address("x"), "fr")))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
            .andExpect(header().exists("Retry-After"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(text).doesNotContain(sixth.toString()).doesNotContain(subject);
    // Another platform administrator is not affected.
    organization = sixth;
    platform = Bootstraps.platform(Bootstraps.platformSubject());
    bootstrap(address("first"));
  }

  // --- status, revoke, resend (A5) ----------------------------------------------------------

  @Test
  void statusShowsOnlyTheOpenBootstrapReceipt() throws Exception {
    mvc.perform(Bootstraps.status(platform, organization))
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.invitation").isEmpty());
    String email = address("first");
    String id = bootstrap(email).get("id").asText();
    String text =
        mvc.perform(Bootstraps.status(platform, organization))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.invitation.id").value(id))
            .andExpect(jsonPath("$.invitation.status").value("PENDING"))
            .andExpect(jsonPath("$.invitation.deliveryState").value("SENT"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(text).doesNotContain(email).doesNotContain(subject);
    // A tenant-created administrator invitation is never shown to the platform.
    mvc.perform(Bootstraps.revoke(platform, organization)).andExpect(status().isNoContent());
    invite(mvc, organization, address("tenant"), "tenant-admin");
    mvc.perform(Bootstraps.status(platform, organization))
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.invitation").isEmpty());
  }

  @Test
  void revokeIsIdempotentAndTheLinkStopsWorking() throws Exception {
    String email = address("first");
    String id = bootstrap(email).get("id").asText();
    String token = mailer.lastTokenFor(email).orElseThrow();
    mvc.perform(Bootstraps.revoke(platform, organization))
        .andExpect(status().isNoContent())
        .andExpect(header().string("Cache-Control", "private, no-store"));
    mvc.perform(Bootstraps.revoke(platform, organization)).andExpect(status().isNoContent());
    mvc.perform(anonymous("accept", token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    assertThat(
            jdbc.queryForList(
                "SELECT action FROM platform.audit_event WHERE resource_id = ?::uuid ORDER BY"
                    + " occurred_at",
                String.class,
                id))
        .containsExactly("invitation.bootstrap-create", "invitation.bootstrap-revoke");
  }

  @Test
  void resendReissuesTheBootstrapUnderTheMvp010Limits() throws Exception {
    mvc.perform(Bootstraps.resend(platform, organization, Organizations.newKey()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
    String email = address("first");
    String id = bootstrap(email).get("id").asText();
    String oldToken = mailer.lastTokenFor(email).orElseThrow();
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = token_issued_at - interval '10 minutes'"
            + " WHERE id = ?::uuid",
        id);
    String key = Organizations.newKey();
    mvc.perform(Bootstraps.resend(platform, organization, key))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(jsonPath("$.id").value(id));
    mvc.perform(Bootstraps.resend(platform, organization, key))
        .andExpect(header().string("Idempotent-Replayed", "true"));
    assertThat(mailer.sent()).hasSize(2);
    assertThat(mailer.lastTokenFor(email).orElseThrow()).isNotEqualTo(oldToken);
    // At least 5 minutes apart.
    mvc.perform(Bootstraps.resend(platform, organization, Organizations.newKey()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("INVITATION_RESEND_LIMITED"));
    // The address never changes.
    assertThat(
            jdbc.queryForObject(
                "SELECT email FROM identity.invitation WHERE id = ?::uuid", String.class, id))
        .isEqualTo(email);
  }

  @Test
  void resendFailsOnceAnotherAdministratorExistsButRevokeStillCleansUp() throws Exception {
    String id = bootstrap(address("first")).get("id").asText();
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = token_issued_at - interval '10 minutes'"
            + " WHERE id = ?::uuid",
        id);
    // A tenant administrator that exists only in the identity provider (pre-membership-gate) adds
    // a membership through the tenant path while the bootstrap is still open.
    String email = address("tenant-admin");
    invite(mvc, organization, email, "tenant-admin");
    mvc.perform(anonymous("accept", mailer.lastTokenFor(email).orElseThrow()))
        .andExpect(status().isOk());
    mvc.perform(Bootstraps.status(platform, organization))
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.invitation.id").value(id));
    mvc.perform(Bootstraps.resend(platform, organization, Organizations.newKey()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE"));
    mvc.perform(Bootstraps.revoke(platform, organization)).andExpect(status().isNoContent());
    mvc.perform(Bootstraps.status(platform, organization))
        .andExpect(jsonPath("$.invitation").isEmpty());
  }

  // --- the tenant side ----------------------------------------------------------------------

  @Test
  void tenantAdministratorsSeeTheOriginAndMayRevokeButNeverResendABootstrap() throws Exception {
    String id = bootstrap(address("first")).get("id").asText();
    mvc.perform(Invitations.list(Hierarchy.admin(organization), ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(id))
        .andExpect(jsonPath("$.data[0].origin").value("PLATFORM_BOOTSTRAP"));
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = token_issued_at - interval '10 minutes'"
            + " WHERE id = ?::uuid",
        id);
    mvc.perform(
            post("/api/v1/invitations/" + id + "/resend")
                .header("Authorization", Hierarchy.admin(organization))
                .header("Idempotency-Key", Organizations.newKey()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE"));
    mvc.perform(
            post("/api/v1/invitations/" + id + "/revoke")
                .header("Authorization", Hierarchy.admin(organization)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVOKED"))
        .andExpect(jsonPath("$.origin").value("PLATFORM_BOOTSTRAP"));
  }

  // --- acceptance, events and privacy -------------------------------------------------------

  @Test
  void theFirstAdministratorAcceptsThroughTheUnchangedPipeline(CapturedOutput output)
      throws Exception {
    String email = address("first");
    String key = Organizations.newKey();
    String id =
        json(mvc.perform(
                    Bootstraps.create(platform, organization, key, Bootstraps.body(email, "fr")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .get("id")
            .asText();
    mvc.perform(anonymous("accept", mailer.lastTokenFor(email).orElseThrow()))
        .andExpect(status().isOk());
    assertThat(directory.identities().get(email).role()).isEqualTo("tenant-admin");
    assertThat(directory.identities().get(email).tenant()).isEqualTo(organization.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM identity.tenant_membership WHERE source_invitation_id ="
                    + " ?::uuid",
                String.class,
                id))
        .isEqualTo("tenant-admin");
    mvc.perform(Bootstraps.status(platform, organization))
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.invitation").isEmpty());
    List<String> envelopes =
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope->>'subject' = ?",
            String.class,
            id);
    assertThat(envelopes).hasSize(2);
    for (String envelope : envelopes) {
      assertThat(json(envelope).get("data").get("origin").asText()).isEqualTo("PLATFORM_BOOTSTRAP");
      assertThat(envelope).doesNotContain(email).doesNotContain(subject);
    }
    String audit =
        jdbc.queryForObject(
            "SELECT string_agg(metadata::text || actor_subject, ',') FROM platform.audit_event"
                + " WHERE resource_id = ?::uuid",
            String.class,
            id);
    assertThat(audit).doesNotContain(email).contains("PLATFORM_BOOTSTRAP");
    assertThat(output.getAll())
        .contains("tenant_admin_bootstrap_created")
        .doesNotContain(email)
        .doesNotContain(key)
        .doesNotContain(subject)
        .doesNotContain(organization.toString());
  }
}
