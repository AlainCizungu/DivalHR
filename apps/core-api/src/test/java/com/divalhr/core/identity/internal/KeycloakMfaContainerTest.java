package com.divalhr.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.application.IdentityDirectory.Provisioned;
import com.divalhr.core.identity.application.IdentityDirectory.ProvisioningRequest;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.InvitationLocale;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.identity.internal.ScriptedBrowser.Outcome;
import com.divalhr.core.identity.internal.keycloak.KeycloakIdentityDirectory;
import com.divalhr.core.identity.internal.keycloak.KeycloakProperties;
import com.divalhr.core.platform.security.AssuranceEvidence;
import com.divalhr.core.platform.security.DivalJwtValidators;
import com.divalhr.core.platform.security.KeycloakRealmRoleConverter;
import com.divalhr.core.platform.security.SecurityProperties;
import com.divalhr.core.platform.tenancy.TenantId;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVP-011 against a real Keycloak 26.7 importing the committed development realm: which sign-ins
 * produce {@code acr=urn:divalhr:loa:mfa}, and that the Core's own token validation and authority
 * mapping accept exactly those. Pages are driven by {@link ScriptedBrowser}; TOTP secrets and codes
 * stay in memory and are never printed.
 */
class KeycloakMfaContainerTest {

  private static final String REALM = KeycloakTestStack.REALM;
  private static final String MFA = "urn:divalhr:loa:mfa";
  private static final String PWD = "urn:divalhr:loa:pwd";
  private static final String CORE_AUDIENCE = "divalhr-core-api";
  private static final String PASSWORD = "test-only-Password-2026!";
  private static final String EN_DENIAL =
      "Your role requires an authenticator app, and none is set up for your account yet.";
  private static final String FR_DENIAL =
      "Votre rôle exige une application d’authentification, et aucune n’est encore configurée";
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("00000000-0000-4000-8000-00000000000a"));
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern ACTION_LINK =
      Pattern.compile("(https?://\\S+/login-actions/action-token\\?\\S+)");

  private static KeycloakTestStack stack;
  private static KeycloakIdentityDirectory directory;
  private static NimbusJwtDecoder coreDecoder;
  private static final KeycloakRealmRoleConverter CONVERTER = new KeycloakRealmRoleConverter();

  @BeforeAll
  static void start() throws Exception {
    stack = KeycloakTestStack.start();
    directory =
        new KeycloakIdentityDirectory(
            new KeycloakProperties(
                stack.baseUrl(),
                REALM,
                null,
                KeycloakTestStack.PROVISIONER_SECRET,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20)),
            "development",
            JSON);
    String issuer =
        get(stack.baseUrl() + "/realms/" + REALM + "/.well-known/openid-configuration", null)
            .path("issuer")
            .asString();
    String jwks = stack.baseUrl() + "/realms/" + REALM + "/protocol/openid-connect/certs";
    coreDecoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    coreDecoder.setJwtValidator(
        DivalJwtValidators.create(new SecurityProperties(issuer, CORE_AUDIENCE, jwks)));
  }

  @AfterAll
  static void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  /** Failed attempts in one test must not lock accounts used by another. */
  @AfterEach
  void clearLockouts() throws Exception {
    admin("DELETE", "/attack-detection/brute-force/users", null);
  }

  // --- sign-in -----------------------------------------------------------------------------------

  @Test
  void anEmployeeSignsInWithAPasswordOnlyAndGetsPasswordAssurance() throws Exception {
    ScriptedBrowser browser = browser();
    Outcome outcome =
        browser.drive(
            browser.authorize(Map.of()),
            "dev-employee-a",
            "dev-only-Employee-A-2026",
            null,
            s -> {});
    assertThat(outcome.forms()).containsExactly("kc-form-login");
    Jwt token = coreToken(browser, outcome);
    assertThat(token.getClaimAsString("acr")).isEqualTo(PWD);
    assertThat(authorities(token)).containsExactly("ROLE_employee");

    // Issue #77: the app lost its in-memory tokens (a reload) and signs in again. The SSO session
    // is reused through the employee-access fallback: no credential prompt, still password level.
    Outcome reentry =
        browser.drive(
            browser.authorize(Map.of()),
            "dev-employee-a",
            "dev-only-Employee-A-2026",
            null,
            s -> {});
    assertThat(reentry.forms()).as("no credential prompt").isEmpty();
    Jwt again = coreToken(browser, reentry);
    assertThat(again.getClaimAsString("acr")).isEqualTo(PWD);
    assertThat(authorities(again)).containsExactly("ROLE_employee");

    // Asking for MFA does not raise an employee's level: the fallback never records a level.
    Outcome asked =
        browser.drive(
            browser.authorize(Map.of("acr_values", MFA)),
            "dev-employee-a",
            "dev-only-Employee-A-2026",
            null,
            s -> {});
    assertThat(asked.forms()).isEmpty();
    assertThat(coreToken(browser, asked).getClaimAsString("acr")).isEqualTo(PWD);
  }

  @Test
  void bothPrivilegedRolesNeedTheirTotpAndThenCarryTheMfaLevel() throws Exception {
    for (String[] seed :
        List.of(
            new String[] {"dev-admin-a", "dev-only-Admin-A-2026", "dev-only-totp-admin-a-2026"},
            new String[] {
              "dev-platform-admin", "dev-only-Platform-2026", "dev-only-totp-platform-2026"
            })) {
      ScriptedBrowser browser = browser();
      Outcome outcome =
          browser.drive(
              browser.authorize(Map.of()), seed[0], seed[1], () -> currentCode(seed[2]), s -> {});
      assertThat(outcome.forms()).containsExactly("kc-form-login", "kc-otp-login-form");
      Jwt token = coreToken(browser, outcome);
      assertThat(token.getClaimAsString("acr")).isEqualTo(MFA);
      assertThat(authorities(token)).contains(AssuranceEvidence.MFA_AUTHORITY);
      // The marker role never reaches the Core's authorities.
      assertThat(token.getClaimAsMap("realm_access").toString()).contains("divalhr-privileged-mfa");
      assertThat(authorities(token)).noneMatch(a -> a.contains("privileged"));

      // Refresh keeps the level for the bounded SSO session.
      JsonNode refreshed = JSON.readTree(browser.refresh(lastRefreshToken));
      Jwt again = coreDecoder.decode(refreshed.path("access_token").asString());
      assertThat(again.getClaimAsString("acr")).isEqualTo(MFA);
    }
  }

  @Test
  void aPasswordLevelRequestCannotSkipTheTotpOfAPrivilegedUser() throws Exception {
    ScriptedBrowser browser = browser();
    Outcome outcome =
        browser.drive(
            browser.authorize(Map.of("acr_values", PWD)),
            "dev-admin-b",
            "dev-only-Admin-B-2026",
            null,
            s -> {});
    // Without a code the flow stops on the OTP form: no authorization code is issued.
    assertThat(outcome.forms()).containsExactly("kc-form-login", "kc-otp-login-form");
    assertThat(outcome.last().isCallback()).isFalse();

    // The client minimum also holds when the requested level is below it, for enrolled users.
    ScriptedBrowser second = browser();
    Outcome completed =
        second.drive(
            second.authorize(Map.of("acr_values", PWD)),
            "dev-admin-b",
            "dev-only-Admin-B-2026",
            () -> currentCode("dev-only-totp-admin-b-2026"),
            s -> {});
    assertThat(coreToken(second, completed).getClaimAsString("acr")).isEqualTo(MFA);
  }

  @Test
  void aPrivilegedUserWhoIsAlsoAnEmployeeStillNeedsTheirTotp() throws Exception {
    Enrolled admin = enrolledTenantAdmin();
    // Issue #77: the employee role must not open the password-level fallback to a privileged user.
    admin("PUT", "/users/" + admin.subject() + "/groups/" + groupId("divalhr-role-employee"), "");
    for (Map<String, String> request :
        List.of(Map.<String, String>of(), Map.of("acr_values", PWD))) {
      ScriptedBrowser withoutCode = browser();
      Outcome stopped =
          withoutCode.drive(withoutCode.authorize(request), admin.email(), PASSWORD, null, s -> {});
      assertThat(stopped.forms()).containsExactly("kc-form-login", "kc-otp-login-form");
      assertThat(stopped.last().isCallback()).isFalse();
    }
    ScriptedBrowser browser = browser();
    Outcome outcome =
        browser.drive(browser.authorize(Map.of()), admin.email(), PASSWORD, admin::code, s -> {});
    Jwt token = coreToken(browser, outcome);
    assertThat(token.getClaimAsString("acr")).isEqualTo(MFA);
    assertThat(authorities(token))
        .contains("ROLE_tenant-admin", "ROLE_employee", AssuranceEvidence.MFA_AUTHORITY);
    // Reuse of a session that achieved MFA keeps the MFA level.
    Outcome reuse =
        browser.drive(browser.authorize(Map.of()), admin.email(), PASSWORD, null, s -> {});
    assertThat(reuse.forms()).isEmpty();
    assertThat(coreToken(browser, reuse).getClaimAsString("acr")).isEqualTo(MFA);
  }

  @Test
  void wrongAndReplayedCodesAreRejected() throws Exception {
    Enrolled admin = enrolledTenantAdmin();
    ScriptedBrowser wrong = browser();
    String bad =
        String.format(Locale.ROOT, "%06d", (Integer.parseInt(admin.code()) + 1) % 1_000_000);
    Outcome rejected =
        wrong.drive(wrong.authorize(Map.of()), admin.email(), PASSWORD, () -> bad, s -> {});
    assertThat(rejected.forms()).containsExactly("kc-form-login", "kc-otp-login-form");
    assertThat(rejected.last().formId()).isEqualTo("kc-otp-login-form");
    assertThat(rejected.last().isCallback()).isFalse();

    // A code accepted once is not accepted again (otpPolicyCodeReusable=false).
    String code = admin.code();
    ScriptedBrowser first = browser();
    Outcome accepted =
        first.drive(first.authorize(Map.of()), admin.email(), PASSWORD, () -> code, s -> {});
    assertThat(accepted.reachedApplication()).isTrue();
    ScriptedBrowser replay = browser();
    Outcome replayed =
        replay.drive(replay.authorize(Map.of()), admin.email(), PASSWORD, () -> code, s -> {});
    assertThat(replayed.reachedApplication()).isFalse();
    assertThat(replayed.last().formId()).isEqualTo("kc-otp-login-form");
  }

  @Test
  void repeatedFailuresLockTheAccountTemporarilyWithoutPermanentLockout() throws Exception {
    Enrolled admin = enrolledTenantAdmin();
    for (int attempt = 0; attempt < 5; attempt++) {
      ScriptedBrowser browser = browser();
      browser.drive(browser.authorize(Map.of()), admin.email(), "wrong-" + attempt, null, s -> {});
    }
    JsonNode status = admin("GET", "/attack-detection/brute-force/users/" + admin.subject(), null);
    assertThat(status.path("disabled").asBoolean()).isTrue();
    assertThat(status.path("numFailures").asInt()).isPositive();
    // Temporary: the user itself stays enabled.
    assertThat(admin("GET", "/users/" + admin.subject(), null).path("enabled").asBoolean())
        .isTrue();
  }

  // --- enrollment --------------------------------------------------------------------------------

  @Test
  void anInvitedTenantAdministratorSetsAPasswordAndAnAuthenticatorFromTheLink() throws Exception {
    Enrolled admin = enrolledTenantAdmin();
    assertThat(admin.setupForms()).contains("kc-passwd-update-form", "kc-totp-settings-form");
    JsonNode credentials = admin("GET", "/users/" + admin.subject() + "/credentials", null);
    assertThat(types(credentials)).containsExactlyInAnyOrder("password", "otp");
    assertThat(admin("GET", "/users/" + admin.subject(), null).path("requiredActions").size())
        .isZero();

    ScriptedBrowser browser = browser();
    Outcome outcome =
        browser.drive(browser.authorize(Map.of()), admin.email(), PASSWORD, admin::code, s -> {});
    Jwt token = coreToken(browser, outcome);
    assertThat(token.getClaimAsString("acr")).isEqualTo(MFA);
    assertThat(authorities(token))
        .containsExactlyInAnyOrder("ROLE_tenant-admin", AssuranceEvidence.MFA_AUTHORITY);
    assertThat(token.getClaimAsString("tenant_id")).isEqualTo(TENANT.toString());
  }

  @Test
  void anInvitedEmployeeIsAskedOnlyForAPassword() throws Exception {
    String email = unique("employee");
    String subject = provision(email, TenantRole.EMPLOYEE);
    assertThat(texts(admin("GET", "/users/" + subject, null).path("requiredActions")))
        .containsExactly("UPDATE_PASSWORD");
    directory.requestCredentialSetup(INVITATIONS.get(subject));
    ScriptedBrowser browser = browser();
    AtomicReference<String> secret = new AtomicReference<>();
    Outcome setup =
        browser.drive(browser.open(actionLink(email)), email, PASSWORD, null, secret::set);
    assertThat(setup.forms())
        .contains("kc-passwd-update-form")
        .doesNotContain("kc-totp-settings-form");
    assertThat(secret.get()).isNull();
  }

  @Test
  void aPrivilegedUserWithoutAnAuthenticatorIsDeniedInTheirLanguage() throws Exception {
    // Keycloak answers in the user's own locale, set from the invitation language.
    for (Map.Entry<InvitationLocale, String> locale :
        Map.of(InvitationLocale.EN, EN_DENIAL, InvitationLocale.FR, FR_DENIAL).entrySet()) {
      String email = unique("unenrolled");
      String subject = provision(email, TenantRole.TENANT_ADMIN, locale.getKey());
      // As if the setup link had been ignored and a realm administrator had set a password.
      withPasswordOnly(subject);
      ScriptedBrowser browser = browser();
      Outcome outcome = browser.drive(browser.authorize(Map.of()), email, PASSWORD, null, s -> {});
      assertThat(outcome.forms()).containsExactly("kc-form-login");
      assertThat(outcome.last().isCallback()).isFalse();
      assertThat(outcome.last().formId())
          .as("no self-enrollment form is offered")
          .isNotEqualTo("kc-totp-settings-form");
      assertThat(outcome.last().text().contains(locale.getValue()))
          .as("denial message in %s", locale.getKey())
          .isTrue();
      assertThat(types(admin("GET", "/users/" + subject + "/credentials", null)))
          .containsExactly("password");
    }
  }

  // --- role change -------------------------------------------------------------------------------

  @Test
  void aPromotionInsideAPasswordSessionIsRefusedByTheCoreUntilStepUp() throws Exception {
    String email = unique("promoted");
    String subject = provision(email, TenantRole.EMPLOYEE, InvitationLocale.FR);
    withPasswordOnly(subject);
    ScriptedBrowser browser = browser();
    Outcome outcome = browser.drive(browser.authorize(Map.of()), email, PASSWORD, null, s -> {});
    Jwt employee = coreToken(browser, outcome);
    assertThat(employee.getClaimAsString("acr")).isEqualTo(PWD);

    // A realm administrator adds the tenant-admin role group; the user is still an employee.
    String employeeGroup = groupId("divalhr-role-employee");
    String adminGroup = groupId("divalhr-role-tenant-admin");
    admin("PUT", "/users/" + subject + "/groups/" + adminGroup, "");

    // The refreshed token gains the role but keeps the password level: no MFA authority, so the
    // Core answers every privileged operation with 403 MFA_REQUIRED.
    Jwt refreshed =
        coreDecoder.decode(
            JSON.readTree(browser.refresh(lastRefreshToken)).path("access_token").asString());
    assertThat(refreshed.getClaimAsString("acr")).isEqualTo(PWD);
    assertThat(authorities(refreshed))
        .contains("ROLE_tenant-admin", "ROLE_employee")
        .doesNotContain(AssuranceEvidence.MFA_AUTHORITY);
    assertThat(AssuranceEvidence.provesMfa(refreshed)).isFalse();

    // Issue #77: holding the employee role too does not open the password-level fallback.
    Outcome mixed = browser.drive(browser.authorize(Map.of()), email, PASSWORD, null, s -> {});
    assertThat(mixed.reachedApplication()).isFalse();
    assertThat(mixed.last().text().contains(FR_DENIAL)).as("denial in French").isTrue();

    // Moved out of the employee group: the same.
    admin("DELETE", "/users/" + subject + "/groups/" + employeeGroup, null);

    // A new authorization in the same browser session must step up; without an authenticator
    // the user is denied instead of being let through on the existing session.
    Outcome stepUp = browser.drive(browser.authorize(Map.of()), email, PASSWORD, null, s -> {});
    assertThat(stepUp.reachedApplication()).isFalse();
    assertThat(stepUp.last().text().contains(FR_DENIAL)).as("denial in French").isTrue();
  }

  // --- employee-access fallback (Issue #77)
  // ---------------------------------------------------------------------------------------

  @Test
  void anAccountWithoutTheEmployeeRoleCannotReenterThroughTheFallback() throws Exception {
    String email = unique("norole");
    admin(
        "POST",
        "/users",
        "{\"username\":\""
            + email
            + "\",\"email\":\""
            + email
            + "\",\"emailVerified\":true,\"enabled\":true,"
            + "\"firstName\":\"No\",\"lastName\":\"Role\"}");
    String subject =
        admin("GET", "/users?exact=true&username=" + enc(email), null).get(0).path("id").asString();
    withPasswordOnly(subject);
    ScriptedBrowser browser = browser();
    Outcome first = browser.drive(browser.authorize(Map.of()), email, PASSWORD, null, s -> {});
    Jwt token = coreToken(browser, first);
    assertThat(token.getClaimAsString("acr")).isEqualTo(PWD);
    assertThat(authorities(token)).isEmpty();
    // Level 1 is satisfied and neither the OTP branch nor the fallback applies: no code.
    Outcome reentry = browser.drive(browser.authorize(Map.of()), email, PASSWORD, null, s -> {});
    assertThat(reentry.forms()).isEmpty();
    assertThat(reentry.reachedApplication()).isFalse();
  }

  @Test
  void signingOutEndsTheSessionTheFallbackWouldReuse() throws Exception {
    ScriptedBrowser browser = browser();
    Outcome outcome =
        browser.drive(
            browser.authorize(Map.of()),
            "dev-employee-b",
            "dev-only-Employee-B-2026",
            null,
            s -> {});
    coreToken(browser, outcome);
    browser.open(
        stack.baseUrl()
            + "/realms/"
            + REALM
            + "/protocol/openid-connect/logout?client_id="
            + ScriptedBrowser.CLIENT_ID
            + "&id_token_hint="
            + enc(lastIdToken));
    assertThat(browser.authorize(Map.of()).formId()).isEqualTo("kc-form-login");
    ScriptedBrowser.Page silent = browser.authorize(Map.of("prompt", "none"));
    assertThat(silent.isCallback()).isTrue();
    assertThat(silent.callbackParameter("code")).isNull();
    assertThat(silent.callbackParameter("error")).isEqualTo("login_required");
  }

  /**
   * An essential {@code acr} claim (OIDC {@code claims} parameter) may make Keycloak refuse the
   * request or answer at password level (the open upstream issue for 26.0.0-26.7.4), but it never
   * yields the MFA level without a TOTP, and the Core never accepts such a token as MFA.
   */
  @Test
  void anEssentialMfaClaimNeverYieldsMfaWithoutATotp() throws Exception {
    Map<String, String> essential =
        Map.of(
            "claims", "{\"id_token\":{\"acr\":{\"essential\":true,\"values\":[\"" + MFA + "\"]}}}");
    ScriptedBrowser employee = browser();
    assertNeverMfa(
        employee,
        employee.drive(
            employee.authorize(essential),
            "dev-employee-a",
            "dev-only-Employee-A-2026",
            null,
            s -> {}));
    coreToken(
        employee,
        employee.drive(
            employee.authorize(Map.of()),
            "dev-employee-a",
            "dev-only-Employee-A-2026",
            null,
            s -> {}));
    assertNeverMfa(
        employee,
        employee.drive(
            employee.authorize(essential),
            "dev-employee-a",
            "dev-only-Employee-A-2026",
            null,
            s -> {}));

    // A user promoted inside a password-level session.
    String email = unique("essential");
    String subject = provision(email, TenantRole.EMPLOYEE);
    withPasswordOnly(subject);
    ScriptedBrowser promoted = browser();
    coreToken(
        promoted, promoted.drive(promoted.authorize(Map.of()), email, PASSWORD, null, s -> {}));
    admin("PUT", "/users/" + subject + "/groups/" + groupId("divalhr-role-tenant-admin"), "");
    assertNeverMfa(
        promoted, promoted.drive(promoted.authorize(essential), email, PASSWORD, null, s -> {}));
  }

  private static void assertNeverMfa(ScriptedBrowser browser, Outcome outcome) throws Exception {
    if (!outcome.reachedApplication()) {
      return; // refused safely
    }
    Jwt token = coreToken(browser, outcome);
    assertThat(token.getClaimAsString("acr")).isNotEqualTo(MFA);
    assertThat(AssuranceEvidence.provesMfa(token)).isFalse();
    assertThat(authorities(token)).doesNotContain(AssuranceEvidence.MFA_AUTHORITY);
  }

  // --- other grants and clients
  // --------------------------------------------------------------------

  @Test
  void noOtherGrantOrClientYieldsATokenTheCoreAccepts() throws Exception {
    // The browser client allows neither the password nor the device grant.
    assertThat(
            tokenEndpoint(
                    Map.of(
                        "grant_type", "password",
                        "client_id", ScriptedBrowser.CLIENT_ID,
                        "username", "dev-admin-a",
                        "password", "dev-only-Admin-A-2026"))
                .path("error")
                .asString())
        .isEqualTo("unauthorized_client");
    HttpResponse<String> device =
        post(
            stack.baseUrl() + "/realms/" + REALM + "/protocol/openid-connect/auth/device",
            form(Map.of("client_id", ScriptedBrowser.CLIENT_ID)),
            null);
    assertThat(device.statusCode()).isBetween(400, 401);

    // admin-cli keeps working for the development kcadm runbook (A1): the realm's own admin-cli
    // enforces the TOTP of privileged users on password grants, and none of its tokens carry the
    // Core audience, roles, tenant or assurance, so the Core rejects them.
    assertThat(
            tokenEndpoint(
                    Map.of(
                        "grant_type", "password",
                        "client_id", "admin-cli",
                        "username", "dev-admin-a",
                        "password", "dev-only-Admin-A-2026"))
                .path("error")
                .asString())
        .isEqualTo("invalid_grant");
    JsonNode employee =
        tokenEndpoint(
            Map.of(
                "grant_type", "password",
                "client_id", "admin-cli",
                "username", "dev-employee-a",
                "password", "dev-only-Employee-A-2026"));
    assertAdminCliTokenIsUseless(employee.path("access_token").asString());
    assertAdminCliTokenIsUseless(masterAdminToken());
  }

  private static void assertAdminCliTokenIsUseless(String token) {
    JsonNode claims = claims(token);
    assertThat(claims.path("azp").asString()).isEqualTo("admin-cli");
    assertThat(claims.has("realm_access")).isFalse();
    assertThat(claims.has("tenant_id")).isFalse();
    assertThat(claims.path("aud").toString()).doesNotContain(CORE_AUDIENCE);
    assertThat(claims.path("acr").asString("")).isNotEqualTo(MFA);
    assertThatThrownBy(() -> coreDecoder.decode(token)).isInstanceOf(JwtException.class);
  }

  // --- helpers
  // -------------------------------------------------------------------------------------

  /** The refresh token of the last code exchange, kept out of assertion messages. */
  private static String lastRefreshToken;

  /** The ID token of the last code exchange (logout hint only), kept out of assertion messages. */
  private static String lastIdToken;

  /** A tenant administrator who completed the setup link; the secret stays in memory. */
  private record Enrolled(String email, String subject, String secret, List<String> setupForms) {

    String code() {
      return ScriptedBrowser.totp(secret, System.currentTimeMillis() / 1000);
    }

    @Override
    public String toString() {
      return "Enrolled[subject=" + subject + "]";
    }
  }

  private static Enrolled enrolledTenantAdmin() throws Exception {
    String email = unique("admin");
    String subject = provision(email, TenantRole.TENANT_ADMIN);
    assertThat(texts(admin("GET", "/users/" + subject, null).path("requiredActions")))
        .containsExactlyInAnyOrder("UPDATE_PASSWORD", "CONFIGURE_TOTP");
    directory.requestCredentialSetup(INVITATIONS.get(subject));
    ScriptedBrowser browser = browser();
    AtomicReference<String> secret = new AtomicReference<>();
    Outcome setup =
        browser.drive(browser.open(actionLink(email)), email, PASSWORD, null, secret::set);
    assertThat(secret.get()).as("an authenticator was set up").isNotNull();
    // The setup code was used in this period; later sign-ins use the next one.
    waitForNextPeriod();
    return new Enrolled(email, subject, secret.get(), setup.forms());
  }

  private static String provision(String email, TenantRole role) {
    return provision(email, role, InvitationLocale.EN);
  }

  /** Invitation of each provisioned subject (setup is keyed by invitation, Issue #31). */
  private static final Map<String, UUID> INVITATIONS =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static String provision(String email, TenantRole role, InvitationLocale locale) {
    UUID invitation = UUID.randomUUID();
    String subject =
        ((Provisioned)
                directory.provision(
                    new ProvisioningRequest(
                        invitation, TENANT, EmailAddress.parse(email).orElseThrow(), role, locale)))
            .subject();
    INVITATIONS.put(subject, invitation);
    return subject;
  }

  /** Clears the setup actions and sets a password, as a realm administrator could. */
  private static void withPasswordOnly(String subject) throws Exception {
    admin("PUT", "/users/" + subject, "{\"requiredActions\":[]}");
    admin(
        "PUT",
        "/users/" + subject + "/reset-password",
        "{\"type\":\"password\",\"value\":\"" + PASSWORD + "\",\"temporary\":false}");
  }

  private static String actionLink(String email) throws Exception {
    for (int i = 0; i < 50; i++) {
      JsonNode list = get(stack.mailpitUrl() + "/api/v1/search?query=" + enc("to:" + email), null);
      if (list.path("messages").size() > 0) {
        JsonNode full =
            get(
                stack.mailpitUrl()
                    + "/api/v1/message/"
                    + list.path("messages").get(0).path("ID").asString(),
                null);
        Matcher link = ACTION_LINK.matcher(full.path("Text").asString());
        assertThat(link.find()).as("action link in the setup email").isTrue();
        return link.group(1);
      }
      Thread.sleep(200);
    }
    throw new AssertionError("no setup email captured");
  }

  private static ScriptedBrowser browser() {
    return new ScriptedBrowser(stack.baseUrl(), REALM);
  }

  private static String currentCode(String secret) {
    return ScriptedBrowser.totp(secret, System.currentTimeMillis() / 1000);
  }

  private static void waitForNextPeriod() {
    long millis = 30_000 - (System.currentTimeMillis() % 30_000) + 500;
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static Jwt coreToken(ScriptedBrowser browser, Outcome outcome) throws Exception {
    assertThat(outcome.reachedApplication()).as("reached the application with a code").isTrue();
    JsonNode tokens = JSON.readTree(browser.exchange(outcome.last()));
    lastRefreshToken = tokens.path("refresh_token").asString();
    lastIdToken = tokens.path("id_token").asString();
    return coreDecoder.decode(tokens.path("access_token").asString());
  }

  private static List<String> authorities(Jwt token) {
    return CONVERTER.convert(token).stream().map(GrantedAuthority::getAuthority).toList();
  }

  private static List<String> types(JsonNode credentials) {
    List<String> types = new java.util.ArrayList<>();
    credentials.forEach(c -> types.add(c.path("type").asString()));
    return types;
  }

  private static List<String> texts(JsonNode array) {
    List<String> values = new java.util.ArrayList<>();
    array.forEach(v -> values.add(v.asString()));
    return values;
  }

  private static JsonNode claims(String token) {
    return JSON.readTree(
        new String(
            java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),
            StandardCharsets.UTF_8));
  }

  private static String groupId(String name) throws Exception {
    return admin("GET", "/groups?search=" + name + "&exact=true", null)
        .get(0)
        .path("id")
        .asString();
  }

  private static String unique(String prefix) {
    return (prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
        .toLowerCase(Locale.ROOT);
  }

  private static String masterAdminToken() throws Exception {
    HttpResponse<String> response =
        post(
            stack.baseUrl() + "/realms/master/protocol/openid-connect/token",
            form(
                Map.of(
                    "grant_type",
                    "password",
                    "client_id",
                    "admin-cli",
                    "username",
                    stack.adminUser(),
                    "password",
                    stack.adminPassword())),
            null);
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body()).path("access_token").asString();
  }

  private static JsonNode admin(String method, String path, String body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(stack.baseUrl() + "/admin/realms/" + REALM + path))
            .header("Authorization", "Bearer " + masterAdminToken())
            .header("Content-Type", "application/json");
    builder =
        switch (method) {
          case "GET" -> builder.GET();
          case "DELETE" -> builder.DELETE();
          default ->
              builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        };
    HttpResponse<String> response =
        HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("%s %s", method, path.replaceAll("[0-9a-f-]{36}", "{id}"))
        .isLessThan(300);
    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
  }

  private static JsonNode tokenEndpoint(Map<String, String> params) throws Exception {
    return JSON.readTree(
        post(
                stack.baseUrl() + "/realms/" + REALM + "/protocol/openid-connect/token",
                form(params),
                null)
            .body());
  }

  private static HttpResponse<String> post(String url, String body, String bearer)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode get(String url, String bearer) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return JSON.readTree(HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body());
  }

  private static String form(Map<String, String> params) {
    StringBuilder out = new StringBuilder();
    params.forEach(
        (k, v) -> out.append(out.isEmpty() ? "" : "&").append(enc(k)).append('=').append(enc(v)));
    return out.toString();
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
