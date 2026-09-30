package com.divalhr.core.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.internal.keycloak.KeycloakProperties;
import com.divalhr.core.identity.internal.mail.MailProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Secrets and transports fail closed at start-up; messages never contain the secret. */
class InvitationConfigurationTest {

  private static InvitationProperties properties(String key, String web, Duration ttl) {
    return new InvitationProperties(
        ttl, null, null, null, null, null, null, null, null, null, web, key);
  }

  @Test
  void theEmailLookupKeyIsRequiredLongAndNeverADevelopmentValueElsewhere() {
    String dev = "dev-only-email-lookup-key-not-a-secret-000001";
    assertThat(EmailLookup.validate("development", dev)).hasSize(dev.length());
    assertThatThrownBy(() -> EmailLookup.validate("production", dev)).hasMessageNotContaining(dev);
    assertThatThrownBy(() -> EmailLookup.validate("test", "short")).hasMessageContaining("32");
    assertThatThrownBy(() -> EmailLookup.validate("test", " ")).hasMessageContaining("required");

    EmailLookup lookup =
        new EmailLookup(
            properties(
                "test-only-email-lookup-key-00000000000000001", "https://app.example.com", null),
            "test");
    EmailAddress address = EmailAddress.parse("ana@example.cd").orElseThrow();
    assertThat(lookup.of(address)).hasSize(32).isEqualTo(lookup.of(address));
    EmailLookup other =
        new EmailLookup(
            properties(
                "test-only-email-lookup-key-00000000000000002", "https://app.example.com", null),
            "test");
    assertThat(other.of(address)).isNotEqualTo(lookup.of(address));
  }

  @Test
  void invitationBoundsAreEnforced() {
    assertThat(properties("k", "https://app.example.com", null).ttl())
        .isEqualTo(Duration.ofDays(7));
    assertThatThrownBy(() -> properties("k", "https://app.example.com", Duration.ofDays(15)))
        .hasMessageContaining("ttl");
    assertThatThrownBy(() -> properties("k", "https://app.example.com/", null))
        .hasMessageContaining("web-base-url");
    assertThatThrownBy(() -> properties("k", "javascript:alert(1)", null))
        .hasMessageContaining("web-base-url");
    assertThat(properties("super-secret-value", "https://app.example.com", null).toString())
        .doesNotContain("super-secret-value");
  }

  @Test
  void theProvisionerSecretAndAdminTransportFailClosed() {
    KeycloakProperties dev =
        new KeycloakProperties(
            "http://keycloak:8080",
            "divalhr-dev",
            null,
            "dev-only-provisioner-secret-2026",
            null,
            "http://localhost:5173/auth/callback",
            null,
            null,
            null);
    dev.validate("development");
    assertThatThrownBy(() -> dev.validate("staging"))
        .hasMessageNotContaining("dev-only-provisioner-secret-2026");
    KeycloakProperties plain =
        new KeycloakProperties(
            "http://id.example.com",
            "divalhr",
            null,
            "a-real-secret-value-123",
            null,
            "https://app.example.com/auth/callback",
            null,
            null,
            null);
    assertThatThrownBy(() -> plain.validate("production")).hasMessageContaining("https");
    assertThat(plain.toString()).doesNotContain("a-real-secret-value-123");
    new KeycloakProperties(
            "https://id.example.com",
            "divalhr",
            null,
            "a-real-secret-value-123",
            null,
            "https://app.example.com/auth/callback",
            null,
            null,
            null)
        .validate("production");
  }

  @Test
  void productionMailRequiresTls() {
    MailProperties plain =
        new MailProperties(
            "smtp.example.com",
            25,
            null,
            "pw-123",
            "no-reply@example.com",
            null,
            false,
            false,
            null);
    plain.validate("development");
    assertThatThrownBy(() -> plain.validate("production")).hasMessageContaining("TLS");
    assertThat(plain.toString()).doesNotContain("pw-123");
    new MailProperties(
            "smtp.example.com", 587, "u", "p", "no-reply@example.com", null, true, false, null)
        .validate("production");
    assertThatThrownBy(
            () ->
                new MailProperties(
                        null, null, null, null, "no-reply@example.com", null, null, null, null)
                    .validate("development"))
        .hasMessageContaining("host");
  }
}
