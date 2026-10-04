package com.divalhr.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.IdentityDirectory.CompensationOutcome;
import com.divalhr.core.identity.application.IdentityDirectory.CredentialSetupOutcome;
import com.divalhr.core.identity.application.IdentityDirectory.IdentityConflict;
import com.divalhr.core.identity.application.IdentityDirectory.Provisioned;
import com.divalhr.core.identity.application.IdentityDirectory.ProvisioningRequest;
import com.divalhr.core.identity.application.IdentityDirectory.ProvisioningResult;
import com.divalhr.core.identity.application.IdentityDirectory.RevocationOutcome;
import com.divalhr.core.identity.application.InvitationMailer.InvitationMessage;
import com.divalhr.core.identity.domain.DeliveryState;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.InvitationLocale;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.identity.internal.keycloak.KeycloakIdentityDirectory;
import com.divalhr.core.identity.internal.keycloak.KeycloakProperties;
import com.divalhr.core.identity.internal.mail.InvitationTemplates;
import com.divalhr.core.identity.internal.mail.MailProperties;
import com.divalhr.core.identity.internal.mail.SmtpInvitationMailer;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVP-010 provisioning through the Core API's real adapter and the {@code divalhr-provisioning}
 * extension (Issue #31), against a real Keycloak 26.7 importing the committed development realm and
 * a Mailpit capture: each tenant role with its tenant and nothing else, idempotency, conflicts,
 * compensation, the setup email, and the SMTP invitation mailer.
 */
class KeycloakProvisioningContainerTest {

  static final TenantId TENANT =
      new TenantId(UUID.fromString("00000000-0000-4000-8000-00000000000a"));

  private static KeycloakTestStack stack;
  private static KeycloakCalls calls;
  private static KeycloakIdentityDirectory directory;

  @BeforeAll
  static void start() throws Exception {
    stack = KeycloakTestStack.start();
    calls = new KeycloakCalls(stack);
    directory =
        new KeycloakIdentityDirectory(
            new KeycloakProperties(
                stack.baseUrl(),
                KeycloakTestStack.REALM,
                null,
                KeycloakTestStack.PROVISIONER_SECRET,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20)),
            "development",
            JsonMapper.builder().build());
  }

  @AfterAll
  static void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  private static ProvisioningRequest request(UUID invitation, String email, TenantRole role) {
    return new ProvisioningRequest(
        invitation, TENANT, EmailAddress.parse(email).orElseThrow(), role, InvitationLocale.EN);
  }

  private static String unique(String prefix) {
    return (prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
        .toLowerCase(Locale.ROOT);
  }

  private static List<String> texts(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asString()));
    return values;
  }

  @Test
  void provisionsEachTenantRoleWithTheTenantAndNothingElse() throws Exception {
    for (TenantRole role : TenantRole.values()) {
      UUID invitation = UUID.randomUUID();
      String email = unique(role.wireName());
      ProvisioningResult result = directory.provision(request(invitation, email, role));
      assertThat(result).isInstanceOf(Provisioned.class);
      String subject = ((Provisioned) result).subject();

      JsonNode token =
          calls.admin(
              "GET",
              "/clients/"
                  + calls.clientUuid("divalhr-web")
                  + "/evaluate-scopes/generate-example-access-token?scope=openid&userId="
                  + subject,
              null);
      assertThat(token.path("tenant_id").asString()).isEqualTo(TENANT.toString());
      // The role, plus (MVP-011) the internal MFA marker that tenant-admin implies; nothing else.
      assertThat(texts(token.path("realm_access").path("roles")))
          .containsExactlyInAnyOrderElementsOf(
              role.requiresMfa()
                  ? List.of(role.wireName(), "divalhr-privileged-mfa")
                  : List.of(role.wireName()));
      assertThat(token.has("email")).isFalse();
      assertThat(token.has("preferred_username")).isFalse();

      JsonNode user = calls.admin("GET", "/users/" + subject, null);
      assertThat(user.path("username").asString()).isEqualTo(email);
      assertThat(user.path("enabled").asBoolean()).isTrue();
      // Verified only because the invitee presented the single-use token sent to this address.
      assertThat(user.path("emailVerified").asBoolean()).isTrue();
      assertThat(texts(user.path("requiredActions")))
          .containsExactlyInAnyOrderElementsOf(
              role.requiresMfa()
                  ? List.of("UPDATE_PASSWORD", "CONFIGURE_TOTP")
                  : List.of("UPDATE_PASSWORD"));
      List<String> attributes = new ArrayList<>();
      user.path("attributes").propertyNames().forEach(attributes::add);
      assertThat(attributes)
          .containsExactlyInAnyOrder("tenant_id", "divalhr_invitation_id", "locale");
      assertThat(texts(user.path("attributes").path("divalhr_invitation_id")))
          .containsExactly(invitation.toString());
      assertThat(calls.admin("GET", "/users/" + subject + "/credentials", null).size()).isZero();
      List<String> groups = new ArrayList<>();
      calls
          .admin("GET", "/users/" + subject + "/groups", null)
          .forEach(group -> groups.add(group.path("name").asString()));
      assertThat(groups)
          .containsExactly(
              role.requiresMfa() ? "divalhr-role-tenant-admin" : "divalhr-role-employee");
      // Idempotent: the same invitation finds the same identity.
      assertThat(directory.provision(request(invitation, email, role)))
          .isEqualTo(new Provisioned(subject));
    }
  }

  @Test
  void anAddressThatAlreadyHasAnIdentityIsAConflictWithoutDetail() throws Exception {
    String email = unique("taken");
    directory.provision(request(UUID.randomUUID(), email, TenantRole.EMPLOYEE));
    assertThat(directory.provision(request(UUID.randomUUID(), email, TenantRole.TENANT_ADMIN)))
        .isInstanceOf(IdentityConflict.class);
    // A seed administrator's address too: the extension never touches an existing identity.
    assertThat(
            directory.provision(
                request(UUID.randomUUID(), "dev-admin-a@example.com", TenantRole.EMPLOYEE)))
        .isInstanceOf(IdentityConflict.class);
  }

  @Test
  void compensationRemovesOnlyThePristineIdentityOfItsInvitation() throws Exception {
    UUID mine = UUID.randomUUID();
    String subject =
        ((Provisioned) directory.provision(request(mine, unique("comp"), TenantRole.EMPLOYEE)))
            .subject();
    assertThat(directory.compensate(UUID.randomUUID()))
        .isEqualTo(CompensationOutcome.DELETED_OR_ABSENT);
    assertThat(calls.adminAs(calls.adminToken(), "GET", "/users/" + subject, null).status())
        .isEqualTo(200);
    assertThat(directory.compensate(mine)).isEqualTo(CompensationOutcome.DELETED_OR_ABSENT);
    assertThat(calls.adminAs(calls.adminToken(), "GET", "/users/" + subject, null).status())
        .isEqualTo(404);
    assertThat(directory.compensate(mine)).isEqualTo(CompensationOutcome.DELETED_OR_ABSENT);

    // Once a credential exists the identity is no longer pristine: refused, left in place.
    UUID started = UUID.randomUUID();
    String other =
        ((Provisioned) directory.provision(request(started, unique("comp2"), TenantRole.EMPLOYEE)))
            .subject();
    calls.admin(
        "PUT",
        "/users/" + other + "/reset-password",
        "{\"type\":\"password\",\"value\":\"test-only-Password-2026!\",\"temporary\":false}");
    assertThat(directory.compensate(started)).isEqualTo(CompensationOutcome.REFUSED);
    assertThat(calls.adminAs(calls.adminToken(), "GET", "/users/" + other, null).status())
        .isEqualTo(200);
  }

  /**
   * MVP-022 (A22-5): the access-revocation call disables an employee identity the extension created
   * for the tenant, ends its sessions (refresh fails) and blocks the next sign-in; it refuses,
   * unchanged, every other identity, and reports an unknown subject as absent.
   */
  @Test
  void accessRevocationDisablesOnlyAnEmployeeIdentityOfTheTenant() throws Exception {
    String password = "test-only-Password-2026!";
    String email = unique("revoke");
    String subject =
        ((Provisioned) directory.provision(request(UUID.randomUUID(), email, TenantRole.EMPLOYEE)))
            .subject();
    calls.admin(
        "PUT",
        "/users/" + subject + "/reset-password",
        "{\"type\":\"password\",\"value\":\"" + password + "\",\"temporary\":false}");
    calls.admin("PUT", "/users/" + subject, "{\"requiredActions\":[]}");
    ScriptedBrowser browser = new ScriptedBrowser(stack.baseUrl(), KeycloakTestStack.REALM);
    ScriptedBrowser.Outcome signedIn =
        browser.drive(browser.authorize(Map.of()), email, password, null, secret -> {});
    assertThat(signedIn.reachedApplication()).isTrue();
    String refreshToken =
        KeycloakCalls.JSON
            .readTree(browser.exchange(signedIn.last()))
            .path("refresh_token")
            .asString();
    assertThat(calls.admin("GET", "/users/" + subject + "/sessions", null).size()).isOne();

    // Another tenant's path, a tenant administrator and a seed identity are refused, unchanged.
    TenantId other = new TenantId(UUID.fromString("00000000-0000-4000-8000-00000000000b"));
    assertThat(directory.revokeAccess(other, subject, UUID.randomUUID()))
        .isEqualTo(RevocationOutcome.REFUSED);
    String admin =
        ((Provisioned)
                directory.provision(
                    request(UUID.randomUUID(), unique("revoke-admin"), TenantRole.TENANT_ADMIN)))
            .subject();
    assertThat(directory.revokeAccess(TENANT, admin, UUID.randomUUID()))
        .isEqualTo(RevocationOutcome.REFUSED);
    assertThat(calls.admin("GET", "/users/" + admin, null).path("enabled").asBoolean()).isTrue();
    String seed =
        calls
            .admin("GET", "/users?username=dev-employee-a&exact=true", null)
            .get(0)
            .path("id")
            .asString();
    assertThat(directory.revokeAccess(TENANT, seed, UUID.randomUUID()))
        .isEqualTo(RevocationOutcome.REFUSED);
    assertThat(calls.admin("GET", "/users/" + seed, null).path("enabled").asBoolean()).isTrue();
    assertThat(calls.admin("GET", "/users/" + subject, null).path("enabled").asBoolean()).isTrue();

    // The employee identity: disabled, no session, refresh and sign-in refused; idempotent.
    UUID revocation = UUID.randomUUID();
    assertThat(directory.revokeAccess(TENANT, subject, revocation))
        .isEqualTo(RevocationOutcome.REVOKED);
    assertThat(directory.revokeAccess(TENANT, subject, revocation))
        .isEqualTo(RevocationOutcome.REVOKED);
    JsonNode user = calls.admin("GET", "/users/" + subject, null);
    assertThat(user.path("enabled").asBoolean()).isFalse();
    assertThat(calls.admin("GET", "/users/" + subject + "/sessions", null).size()).isZero();
    assertThat(KeycloakCalls.JSON.readTree(browser.refresh(refreshToken)).path("error").asString())
        .isEqualTo("invalid_grant");
    ScriptedBrowser again = new ScriptedBrowser(stack.baseUrl(), KeycloakTestStack.REALM);
    assertThat(
            again
                .drive(again.authorize(Map.of()), email, password, null, secret -> {})
                .reachedApplication())
        .isFalse();
    assertThat(directory.revokeAccess(TENANT, UUID.randomUUID().toString(), UUID.randomUUID()))
        .isEqualTo(RevocationOutcome.ABSENT);
    // The extension's evidence carries the revocation ID, never the address.
    String events = calls.admin("GET", "/admin-events?resourceTypes=USER&max=500", null).toString();
    assertThat(events).contains(revocation.toString()).doesNotContain(email);
  }

  @Test
  void theSetupEmailIsSentInTheInviteesLanguageAndInvalidStatesAreReported() throws Exception {
    String email = unique("password");
    UUID invitation = UUID.randomUUID();
    String subject =
        ((Provisioned) directory.provision(request(invitation, email, TenantRole.EMPLOYEE)))
            .subject();
    assertThat(directory.requestCredentialSetup(invitation))
        .isEqualTo(CredentialSetupOutcome.EMAIL_SENT);
    assertThat(calls.awaitMessage(email, 1).path("Subject").asString())
        .isEqualTo("Update Your Account");
    // The required action removed without its credential: invalid, nothing sent.
    calls.admin("PUT", "/users/" + subject, "{\"requiredActions\":[]}");
    assertThat(directory.requestCredentialSetup(invitation))
        .isEqualTo(CredentialSetupOutcome.STATE_INVALID);
    assertThat(calls.messagesTo(email)).isEqualTo(1);
    // The password set: the employee's proven terminal state.
    calls.admin(
        "PUT",
        "/users/" + subject + "/reset-password",
        "{\"type\":\"password\",\"value\":\"test-only-Password-2026!\",\"temporary\":false}");
    assertThat(directory.requestCredentialSetup(invitation))
        .isEqualTo(CredentialSetupOutcome.COMPLETED);
    assertThat(calls.messagesTo(email)).isEqualTo(1);
  }

  @Test
  void theSmtpMailerDeliversTheVersionedInvitationTemplate() throws Exception {
    SmtpInvitationMailer mailer =
        new SmtpInvitationMailer(
            new MailProperties(
                stack.mailpitSmtpHost(),
                stack.mailpitSmtpPort(),
                null,
                null,
                "no-reply@divalhr.test",
                "DivalHR (test)",
                false,
                false,
                Duration.ofSeconds(10)),
            "test",
            new InvitationTemplates());
    String email = unique("smtp");
    DeliveryState state =
        mailer.send(
            new InvitationMessage(
                EmailAddress.parse(email).orElseThrow(),
                InvitationLocale.FR,
                "Société Minière de Kinshasa",
                "Africa/Kinshasa",
                TenantRole.EMPLOYEE,
                Instant.now().plus(Duration.ofDays(7)),
                "http://localhost:5173/invitation#token=" + "B".repeat(43)));
    assertThat(state).isEqualTo(DeliveryState.SENT);
    JsonNode full = calls.awaitMessage(email, 1);
    assertThat(full.path("Subject").asString())
        .isEqualTo("Invitation à rejoindre Société Minière de Kinshasa sur DivalHR");
    assertThat(full.path("Text").asString())
        .contains("Employé")
        .contains("#token=" + "B".repeat(43));
    assertThat(full.path("HTML").asString()).contains("lang=\"fr\"").doesNotContain(email);

    SmtpInvitationMailer unreachable =
        new SmtpInvitationMailer(
            new MailProperties(
                "127.0.0.1",
                1,
                null,
                null,
                "no-reply@divalhr.test",
                null,
                false,
                false,
                Duration.ofSeconds(2)),
            "test",
            new InvitationTemplates());
    assertThat(
            unreachable.send(
                new InvitationMessage(
                    EmailAddress.parse(email).orElseThrow(),
                    InvitationLocale.EN,
                    "Org",
                    "UTC",
                    TenantRole.EMPLOYEE,
                    Instant.now(),
                    "http://localhost/invitation#token=x")))
        .isEqualTo(DeliveryState.FAILED);
  }
}
