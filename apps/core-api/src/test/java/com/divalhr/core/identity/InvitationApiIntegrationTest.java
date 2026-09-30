package com.divalhr.core.identity;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.bearer;
import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Invitations.address;
import static com.divalhr.core.support.Invitations.anonymous;
import static com.divalhr.core.support.Invitations.body;
import static com.divalhr.core.support.Invitations.create;
import static com.divalhr.core.support.Invitations.invite;
import static com.divalhr.core.support.Invitations.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.identity.application.InvitationMailer.InvitationMessage;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.support.FakeIdentityDirectory;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.RecordingInvitationMailer;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MVP-010 administrative invitation endpoints through HTTP, security, idempotency, audit, outbox
 * and PostgreSQL, with in-memory identity-provider and mail fakes.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class InvitationApiIntegrationTest {

  private static final String PRIVATE_NO_STORE = "private, no-store";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;
  @Autowired private RecordingInvitationMailer mailer;
  @Autowired private FakeIdentityDirectory directory;

  private UUID tenant;

  @BeforeEach
  void setUp() throws Exception {
    mailer.reset();
    directory.reset();
    tenant = Hierarchy.newTenant(mvc);
  }

  @Test
  void tenantAdminInvitesWithAReceiptWithoutAddressAndOneAuditEventAndEmail() throws Exception {
    String email = address("ana");
    String key = Organizations.newKey();
    MvcResult result =
        mvc.perform(create(admin(tenant), key, body(email, "employee", "fr")))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", PRIVATE_NO_STORE))
            .andExpect(jsonPath("$.role").value("employee"))
            .andExpect(jsonPath("$.locale").value("fr"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.deliveryState").value("QUEUED"))
            .andExpect(jsonPath("$.email").doesNotExist())
            .andReturn();
    String text = result.getResponse().getContentAsString();
    assertThat(text).doesNotContain(email).doesNotContain("token");
    UUID id = UUID.fromString(json(text).get("id").asText());

    Map<String, Object> row =
        jdbc.queryForMap(
            """
            SELECT tenant_id, email, octet_length(email_lookup) AS lookup_bytes,
                   octet_length(token_sha256) AS token_bytes, role, locale, state, issue_count,
                   delivery_state, expires_at - token_issued_at AS ttl
            FROM identity.invitation WHERE id = ?
            """,
            id);
    assertThat(row)
        .containsEntry("tenant_id", tenant)
        .containsEntry("email", email)
        .containsEntry("lookup_bytes", 32)
        .containsEntry("token_bytes", 32)
        .containsEntry("role", "employee")
        .containsEntry("state", "PENDING")
        .containsEntry("issue_count", 1)
        .containsEntry("delivery_state", "SENT");
    assertThat(row.get("ttl").toString()).contains("7 days");

    List<InvitationMessage> sent = mailer.sent();
    assertThat(sent).hasSize(1);
    assertThat(sent.get(0).to().value()).isEqualTo(email);
    assertThat(sent.get(0).link())
        .matches("^http://localhost:5173/invitation#token=[A-Za-z0-9_-]{43}$");

    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT count(*) AS n, min(action) AS action, min(metadata::text) AS metadata,"
                + " min(tenant_id::text) AS tenant FROM platform.audit_event WHERE resource_id = ?",
            id);
    assertThat(audit)
        .containsEntry("n", 1L)
        .containsEntry("action", "invitation.create")
        .containsEntry("tenant", tenant.toString());
    assertThat(audit.get("metadata").toString())
        .contains("\"role\": \"employee\"")
        .doesNotContain(email)
        .doesNotContain("token");
    Map<String, Object> event =
        jdbc.queryForMap(
            "SELECT count(*) AS n, min(event_type) AS type, min(envelope::text) AS envelope"
                + " FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
            id.toString());
    assertThat(event)
        .containsEntry("n", 1L)
        .containsEntry("type", "identity.invitation-created.v1");
    assertThat(event.get("envelope").toString())
        .doesNotContain(email)
        .doesNotContain(sent.get(0).link().substring(sent.get(0).link().indexOf('#') + 7));
    String stored =
        jdbc.queryForObject(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation = 'invitation.create' AND idempotency_key = ?",
            String.class,
            key);
    assertThat(stored).doesNotContain(email).contains(id.toString());
  }

  @Test
  void replayIsExactEvenAfterTheInvitationChangedAndSendsNothing() throws Exception {
    String email = address("replay");
    String key = Organizations.newKey();
    String first =
        mvc.perform(create(admin(tenant), key, body(email, "tenant-admin", "en")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(json(first).get("id").asText());
    mvc.perform(
            post("/api/v1/invitations/" + id + "/revoke").header("Authorization", admin(tenant)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVOKED"));

    String replayed =
        mvc.perform(create(admin(tenant), key, body(email, "tenant-admin", "en")))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json(replayed)).isEqualTo(json(first));
    assertThat(json(replayed).get("status").asText()).isEqualTo("PENDING");
    assertThat(mailer.sent()).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.invitation WHERE tenant_id = ?",
                Integer.class,
                tenant))
        .isEqualTo(1);

    // Same key, different payload.
    mvc.perform(create(admin(tenant), key, body(email, "employee", "en")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"platform-admin", "PLATFORM-ADMIN", "admin", "manager", "Employee"})
  void onlyTenantRolesCanBeAssignedAndPlatformAdminIsJustAnUnknownValue(String role)
      throws Exception {
    String email = address("role");
    String text =
        mvc.perform(create(admin(tenant), Organizations.newKey(), body(email, role, "fr")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.params.fields[0].field").value("role"))
            .andExpect(jsonPath("$.params.fields[0].constraint").value("FORMAT"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(text).doesNotContain(email).doesNotContain(role.toLowerCase(Locale.ROOT) + "\"");
    assertThat(mailer.sent()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.idempotency_record WHERE operation ="
                    + " 'invitation.create' AND principal = ?",
                Integer.class,
                "sub-admin-" + tenant))
        .isZero();
  }

  @Test
  void validationUsesStableCodesAndNeverEchoesTheAddress() throws Exception {
    String bad = "Not An Address";
    String text =
        mvc.perform(
                create(
                    admin(tenant),
                    Organizations.newKey(),
                    "{\"email\": \""
                        + bad
                        + "\", \"role\": \"employee\", \"locale\": \"de\","
                        + " \"tenantId\": \""
                        + TestTokens.TENANT_B
                        + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode fields = json(text).get("params").get("fields");
    List<String> pairs = new ArrayList<>();
    fields.forEach(f -> pairs.add(f.get("field").asText() + ":" + f.get("constraint").asText()));
    assertThat(pairs)
        .containsExactlyInAnyOrder("body:UNKNOWN_PROPERTY", "email:FORMAT", "locale:FORMAT");
    assertThat(text).doesNotContain(bad).doesNotContain(TestTokens.TENANT_B.toString());

    mvc.perform(create(admin(tenant), null, body(address("nokey"), "employee", "fr")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("Idempotency-Key"));
    mvc.perform(create(admin(tenant), Organizations.newKey(), "{\"role\": \"employee\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("email"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("REQUIRED"));
  }

  @Test
  void addressesAreNormalizedAndOneOpenInvitationPerAddressPerTenant() throws Exception {
    String email = address("dup");
    invite(mvc, tenant, email, "employee");
    String shouted = "  " + email.toUpperCase(Locale.ROOT) + " ";
    String text =
        mvc.perform(
                create(admin(tenant), Organizations.newKey(), body(shouted, "tenant-admin", "fr")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_PENDING"))
            .andExpect(jsonPath("$.params.field").value("email"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(text).doesNotContainIgnoringCase(email);
  }

  @Test
  void aMemberOfTheTenantCannotBeInvitedAgainButAnotherTenantSeesNothing() throws Exception {
    String email = address("member");
    invite(mvc, tenant, email, "employee");
    String token = mailer.lastTokenFor(email).orElseThrow();
    mvc.perform(anonymous("accept", token)).andExpect(status().isOk());

    mvc.perform(create(admin(tenant), Organizations.newKey(), body(email, "employee", "fr")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVITATION_RECIPIENT_ALREADY_MEMBER"));

    // Tenant B: the same address (a member elsewhere, and known to the identity provider) gives
    // exactly the same 201 shape as any unknown address.
    UUID other = Hierarchy.newTenant(mvc);
    JsonNode known = invite(mvc, other, email, "employee");
    JsonNode unknown = invite(mvc, other, address("fresh"), "employee");
    List<String> knownFields = new ArrayList<>();
    known.fieldNames().forEachRemaining(knownFields::add);
    List<String> unknownFields = new ArrayList<>();
    unknown.fieldNames().forEachRemaining(unknownFields::add);
    assertThat(knownFields).isEqualTo(unknownFields);
    assertThat(known.get("status").asText()).isEqualTo(unknown.get("status").asText());
  }

  @Test
  void listIsNewestFirstPaginatedFilteredAndBoundToTenantAndStatus() throws Exception {
    List<String> emails = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      String email = address("list" + i);
      emails.add(email);
      invite(mvc, tenant, email, i == 0 ? "tenant-admin" : "employee");
    }
    JsonNode first =
        json(
            mvc.perform(list(admin(tenant), "limit=2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", PRIVATE_NO_STORE))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(first.get("data")).hasSize(2);
    assertThat(first.get("data").get(0).get("email").asText()).isEqualTo(emails.get(2));
    assertThat(first.get("data").get(0).get("resendsRemaining").asInt()).isEqualTo(3);
    assertThat(first.get("data").get(0).has("acceptedAt")).isTrue();
    assertThat(first.get("data").get(0).toString())
        .doesNotContain("token")
        .doesNotContain("lookup")
        .doesNotContain("sub-admin");
    String cursor = first.get("nextCursor").asText();
    JsonNode second =
        json(
            mvc.perform(list(admin(tenant), "limit=2&cursor=" + cursor))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(second.get("data")).hasSize(1);
    assertThat(second.get("data").get(0).get("email").asText()).isEqualTo(emails.get(0));
    assertThat(second.has("nextCursor")).isFalse();

    // Cursor bound to the status filter and to the tenant.
    mvc.perform(list(admin(tenant), "status=PENDING&cursor=" + cursor))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
    UUID other = Hierarchy.newTenant(mvc);
    mvc.perform(list(admin(other), "cursor=" + cursor))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
    // Tenant B sees none of tenant A's invitations.
    assertThat(
            json(mvc.perform(list(admin(other), "")).andReturn().getResponse().getContentAsString())
                .get("data"))
        .isEmpty();

    mvc.perform(list(admin(tenant), "status=invalid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("status"));
  }

  @Test
  void statusFilterUsesThePublicStatusIncludingDerivedExpiry() throws Exception {
    UUID pending =
        UUID.fromString(invite(mvc, tenant, address("p"), "employee").get("id").asText());
    UUID revoked =
        UUID.fromString(invite(mvc, tenant, address("r"), "employee").get("id").asText());
    UUID lapsed = UUID.fromString(invite(mvc, tenant, address("l"), "employee").get("id").asText());
    mvc.perform(
            post("/api/v1/invitations/" + revoked + "/revoke")
                .header("Authorization", admin(tenant)))
        .andExpect(status().isOk());
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = now() - interval '9 days',"
            + " expires_at = now() - interval '1 day' WHERE id = ?",
        lapsed);
    assertThat(ids("status=PENDING")).containsExactly(pending.toString());
    assertThat(ids("status=REVOKED")).containsExactly(revoked.toString());
    assertThat(ids("status=EXPIRED")).containsExactly(lapsed.toString());
    assertThat(ids("status=ACCEPTED")).isEmpty();
  }

  @Test
  void revokeIsIdempotentByStateAndInvalidatesTheLinkImmediately() throws Exception {
    String email = address("rev");
    UUID id = UUID.fromString(invite(mvc, tenant, email, "employee").get("id").asText());
    String token = mailer.lastTokenFor(email).orElseThrow();
    mvc.perform(anonymous("inspect", token)).andExpect(status().isOk());

    mvc.perform(
            post("/api/v1/invitations/" + id + "/revoke").header("Authorization", admin(tenant)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", PRIVATE_NO_STORE))
        .andExpect(jsonPath("$.status").value("REVOKED"))
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.revokedAt").isNotEmpty());
    mvc.perform(anonymous("inspect", token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    mvc.perform(
            post("/api/v1/invitations/" + id + "/revoke").header("Authorization", admin(tenant)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVOKED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.audit_event WHERE resource_id = ?"
                    + " AND action = 'invitation.revoke'",
                Integer.class,
                id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT token_sha256 IS NULL FROM identity.invitation WHERE id = ?",
                Boolean.class,
                id))
        .isTrue();
  }

  @Test
  void revokeAndResendOfAnotherTenantAreIndistinguishableFromMissing() throws Exception {
    UUID foreign =
        UUID.fromString(
            invite(mvc, Hierarchy.newTenant(mvc), address("f"), "employee").get("id").asText());
    for (String action : List.of("revoke", "resend")) {
      String missingBody = notFound(UUID.randomUUID(), action);
      String foreignBody = notFound(foreign, action);
      assertThat(withoutCorrelation(foreignBody)).isEqualTo(withoutCorrelation(missingBody));
    }
    mvc.perform(
            post("/api/v1/invitations/not-a-uuid/revoke").header("Authorization", admin(tenant)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("invitationId"));
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM identity.invitation WHERE id = ?", String.class, foreign))
        .isEqualTo("PENDING");
  }

  @Test
  void resendIssuesANewLinkInvalidatesTheOldOneAndIsLimited() throws Exception {
    String email = address("resend");
    UUID id = UUID.fromString(invite(mvc, tenant, email, "employee").get("id").asText());
    String firstToken = mailer.lastTokenFor(email).orElseThrow();

    // Too soon after issuing.
    mvc.perform(resend(id, Organizations.newKey()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("INVITATION_RESEND_LIMITED"))
        .andExpect(header().exists("Retry-After"));

    for (int reissue = 1; reissue <= 3; reissue++) {
      backdateIssue(id);
      String key = Organizations.newKey();
      String receipt =
          mvc.perform(resend(id, key))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.deliveryState").value("QUEUED"))
              .andExpect(jsonPath("$.email").doesNotExist())
              .andReturn()
              .getResponse()
              .getContentAsString();
      // Exact replay sends nothing new.
      int sentBefore = mailer.sent().size();
      mvc.perform(resend(id, key))
          .andExpect(status().isOk())
          .andExpect(header().string("Idempotent-Replayed", "true"))
          .andExpect(
              result ->
                  assertThat(json(result.getResponse().getContentAsString()))
                      .isEqualTo(json(receipt)));
      assertThat(mailer.sent()).hasSize(sentBefore);
    }
    String newest = mailer.lastTokenFor(email).orElseThrow();
    assertThat(newest).isNotEqualTo(firstToken);
    mvc.perform(anonymous("inspect", firstToken)).andExpect(status().isNotFound());
    mvc.perform(anonymous("inspect", newest)).andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT issue_count FROM identity.invitation WHERE id = ?", Integer.class, id))
        .isEqualTo(4);

    backdateIssue(id);
    mvc.perform(resend(id, Organizations.newKey()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("INVITATION_RESEND_LIMITED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.audit_event WHERE resource_id = ?"
                    + " AND action = 'invitation.resend'",
                Integer.class,
                id))
        .isEqualTo(3);
  }

  @Test
  void acceptedInvitationsCanNeitherBeRevokedNorResent() throws Exception {
    String email = address("done");
    UUID id = UUID.fromString(invite(mvc, tenant, email, "employee").get("id").asText());
    mvc.perform(anonymous("accept", mailer.lastTokenFor(email).orElseThrow()))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/invitations/" + id + "/revoke").header("Authorization", admin(tenant)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVITATION_NOT_PENDING"));
    mvc.perform(resend(id, Organizations.newKey()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVITATION_NOT_PENDING"));
  }

  @Test
  void hourlyQuotaIsEnforcedPerTenantWithRetryAfter() throws Exception {
    for (int i = 0; i < 50; i++) {
      invite(mvc, tenant, address("q" + i), "employee");
    }
    mvc.perform(
            create(admin(tenant), Organizations.newKey(), body(address("q50"), "employee", "fr")))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("INVITATION_RATE_LIMITED"))
        .andExpect(jsonPath("$.params").isEmpty())
        .andExpect(header().exists("Retry-After"));
    // Another tenant is unaffected.
    invite(mvc, Hierarchy.newTenant(mvc), address("q-other"), "employee");
  }

  @Test
  void onlyTenantAdministratorsWithSubjectAndTenantMayManageInvitations() throws Exception {
    for (String role : List.of("employee", "platform-admin")) {
      String caller = bearer(tenant, "sub-" + role, role);
      mvc.perform(list(caller, ""))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      mvc.perform(create(caller, Organizations.newKey(), body(address("x"), "employee", "fr")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      mvc.perform(
              post("/api/v1/invitations/" + UUID.randomUUID() + "/revoke")
                  .header("Authorization", caller))
          .andExpect(status().isForbidden());
      mvc.perform(
              post("/api/v1/invitations/" + UUID.randomUUID() + "/resend")
                  .header("Authorization", caller)
                  .header("Idempotency-Key", Organizations.newKey()))
          .andExpect(status().isForbidden());
    }
    String noTenant =
        "Bearer "
            + TestTokens.token()
                .tenant(null)
                .subject("sub-nt")
                .roles(List.of("tenant-admin"))
                .build();
    mvc.perform(list(noTenant, ""))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));
    String noSubject =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject(null)
                .roles(List.of("tenant-admin"))
                .build();
    mvc.perform(list(noSubject, "")).andExpect(status().isForbidden());
    mvc.perform(create(null, Organizations.newKey(), body(address("anon"), "employee", "fr")))
        .andExpect(status().isUnauthorized());
    assertThat(mailer.sent()).isEmpty();
  }

  @Test
  void logsMetricsAndErrorsCarryNoAddressTokenOrKey(CapturedOutput output) throws Exception {
    String email = address("private");
    String key = Organizations.newKey();
    mvc.perform(create(admin(tenant), key, body(email, "employee", "fr")))
        .andExpect(status().isCreated());
    String token = mailer.lastTokenFor(email).orElseThrow();
    mvc.perform(anonymous("inspect", token)).andExpect(status().isOk());
    mvc.perform(create(admin(tenant), Organizations.newKey(), body(email, "employee", "fr")))
        .andExpect(status().isConflict());
    assertThat(output.getAll())
        .contains("invitation_created")
        .contains("invitation_delivery_sent")
        .doesNotContain(email)
        .doesNotContain(token)
        .doesNotContain(key);
    for (Meter meter : meters.getMeters()) {
      assertThat(meter.getId().getTags())
          .allSatisfy(
              tag ->
                  assertThat(tag.getValue())
                      .doesNotContain(email)
                      .doesNotContain(tenant.toString())
                      .doesNotContain(token));
    }
    assertThat(
            meters
                .find(OperationMetrics.METRIC)
                .tags("operation", "invitation.create", "outcome", "duplicate_conflict")
                .counter())
        .isNotNull();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder resend(
      UUID id, String key) {
    return post("/api/v1/invitations/" + id + "/resend")
        .header("Authorization", admin(tenant))
        .header("Idempotency-Key", key);
  }

  private void backdateIssue(UUID id) {
    jdbc.update(
        "UPDATE identity.invitation SET token_issued_at = token_issued_at - interval '10 minutes'"
            + " WHERE id = ? AND state = 'PENDING'",
        id);
  }

  private List<String> ids(String query) throws Exception {
    List<String> ids = new ArrayList<>();
    json(mvc.perform(list(admin(tenant), query)).andReturn().getResponse().getContentAsString())
        .get("data")
        .forEach(row -> ids.add(row.get("id").asText()));
    return ids;
  }

  private String notFound(UUID id, String action) throws Exception {
    var request =
        post("/api/v1/invitations/" + id + "/" + action).header("Authorization", admin(tenant));
    if (action.equals("resend")) {
      request = request.header("Idempotency-Key", Organizations.newKey());
    }
    return mvc.perform(request)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"))
        .andExpect(jsonPath("$.params").isEmpty())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static JsonNode withoutCorrelation(String body) throws Exception {
    var node = (com.fasterxml.jackson.databind.node.ObjectNode) json(body);
    node.remove("correlationId");
    return node;
  }
}
