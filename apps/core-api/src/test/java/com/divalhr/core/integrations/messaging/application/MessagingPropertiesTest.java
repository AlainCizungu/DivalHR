package com.divalhr.core.integrations.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.integrations.messaging.application.MessagingProperties.TestProvider;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** MVP-111 (D111-8): operator configuration fails closed and never echoes a secret. */
class MessagingPropertiesTest {

  private static final String KEY = "test-only-messaging-key-0123456789abcdef";
  private static final String APP_SECRET = "test-only-app-secret-0123456789";
  private static final String VERIFY = "test-only-verify-token-0123456789";

  private static MessagingProperties props(
      String key, String graph, String version, String publicBase, TestProvider provider) {
    return new MessagingProperties(
        key,
        APP_SECRET,
        VERIFY,
        graph,
        version,
        publicBase,
        null,
        null,
        null,
        provider,
        null,
        null);
  }

  private static TestProvider none() {
    return new TestProvider(null, null, null);
  }

  @Test
  void defaultsAreTheOfficialOriginPinnedVersionAndBoundedLimits() {
    var p =
        new MessagingProperties(
            null, null, null, null, null, null, null, null, null, null, null, null);
    assertThat(p.graphBaseUrl()).isEqualTo("https://graph.facebook.com");
    assertThat(p.graphVersion()).isEqualTo("v22.0");
    assertThat(p.jitter()).isTrue();
    assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(p.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(p.callbackMaxBytes()).isEqualTo(65_536);
    assertThat(p.callbackRequestsPerMinute()).isEqualTo(600);
    assertThat(p.keyConfigured()).isFalse();
    assertThat(p.callbackConfigured()).isFalse();
    assertThatCode(() -> p.validate("production")).doesNotThrowAnyException();
  }

  @Test
  void aProductionConfigurationNeedsNoTestOnlyAllowance() {
    var p = props(KEY, "https://graph.facebook.com", "v23.0", "https://hr.example.com", none());
    assertThat(p.keyConfigured()).isTrue();
    assertThat(p.callbackConfigured()).isTrue();
    assertThatCode(() -> p.validate("production")).doesNotThrowAnyException();
    assertThatCode(() -> p.validate("hr-dev")).doesNotThrowAnyException();
  }

  @Test
  void theKeyMustBeLongAndADevelopmentKeyIsRefusedOutsideDevelopment() {
    assertThatThrownBy(() -> props("too-short", null, null, null, none()).validate("production"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("too-short");
    String devKey = "dev-only-messaging-key-0123456789abcdef";
    assertThatThrownBy(() -> props(devKey, null, null, null, none()).validate("production"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(devKey);
    assertThatCode(() -> props(devKey, null, null, null, none()).validate("development"))
        .doesNotThrowAnyException();
  }

  @Test
  void shortCallbackSecretsAreRefused() {
    var shortSecret =
        new MessagingProperties(
            KEY, "abcdefgh", VERIFY, null, null, null, null, null, null, null, null, null);
    assertThatThrownBy(() -> shortSecret.validate("production"))
        .hasMessageNotContaining("abcdefgh");
    var shortToken =
        new MessagingProperties(
            KEY, APP_SECRET, "tiny", null, null, null, null, null, null, null, null, null);
    assertThatThrownBy(() -> shortToken.validate("production")).hasMessageNotContaining("tiny");
  }

  @Test
  void theGraphVersionMustBePinned() {
    for (String bad : new String[] {"latest", "22.0", "v22", "v22.0.1", "v2x.0", "../v22.0"}) {
      assertThatThrownBy(() -> props(KEY, null, bad, null, none()).validate("production"))
          .as(bad)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void onlyTheOfficialGraphOriginIsAcceptedInProduction() {
    for (String bad :
        new String[] {
          "https://graph.facebook.com.evil.example",
          "https://graph.facebook.com/",
          "http://graph.facebook.com",
          "https://evil.example.com",
          "https://graph.facebook.com:8443"
        }) {
      assertThatThrownBy(() -> props(KEY, bad, null, null, none()).validate("production"))
          .as(bad)
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> props(KEY, bad, null, null, none()).validate("hr-dev"))
          .as(bad + " hr-dev")
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void theSyntheticProviderIsAcceptedOnlyInDevelopmentAndTestAtOneExactHostAndPort() {
    var provider = new TestProvider("meta.test", 8443, null);
    var p = props(KEY, "https://meta.test:8443", null, null, provider);
    assertThatCode(() -> p.validate("test")).doesNotThrowAnyException();
    assertThatCode(() -> p.validate("development")).doesNotThrowAnyException();
    assertThatThrownBy(() -> p.validate("production")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> p.validate("hr-dev")).isInstanceOf(IllegalStateException.class);
    // The graph origin must be exactly the configured synthetic provider.
    assertThatThrownBy(
            () -> props(KEY, "https://meta.test:9999", null, null, provider).validate("test"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> props(KEY, "https://other.test:8443", null, null, provider).validate("test"))
        .isInstanceOf(IllegalStateException.class);
    // A provider with a wildcard, a path, a port in the host or a missing port is refused.
    for (TestProvider bad :
        new TestProvider[] {
          new TestProvider("*.test", 8443, null),
          new TestProvider("meta.test/x", 8443, null),
          new TestProvider("meta.test:8443", 8443, null),
          new TestProvider("Meta.Test", 8443, null),
          new TestProvider("meta.test", null, null),
          new TestProvider("meta.test", 0, null)
        }) {
      assertThatThrownBy(
              () -> props(KEY, "https://graph.facebook.com", null, null, bad).validate("test"))
          .as(String.valueOf(bad))
          .isInstanceOf(IllegalStateException.class);
    }
    // A configured provider with the official origin is still refused in production.
    assertThatThrownBy(
            () ->
                props(KEY, "https://graph.facebook.com", null, null, provider)
                    .validate("production"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theDeepLinkOriginIsAnHttpsOriginAndLocalHttpOnlyInDevelopmentOrTest() {
    assertThatCode(
            () -> props(KEY, null, null, "https://hr-dev.divalhr.com", none()).validate("hr-dev"))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> props(KEY, null, null, "http://hr.example.com", none()).validate("hr-dev"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> props(KEY, null, null, "https://hr.example.com/app", none()).validate("hr-dev"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> props(KEY, null, null, "http://localhost:3000", none()).validate("production"))
        .isInstanceOf(IllegalStateException.class);
    assertThatCode(() -> props(KEY, null, null, "http://localhost:3000", none()).validate("test"))
        .doesNotThrowAnyException();
    assertThat(props(KEY, null, null, "https://hr.example.com/", none()).deepLinkBase(false))
        .contains("https://hr.example.com");
    assertThat(props(KEY, null, null, "", none()).deepLinkBase(false)).isEmpty();
  }

  @Test
  void timeoutsAndCallbackLimitsAreBounded() {
    for (var bad :
        new MessagingProperties[] {
          new MessagingProperties(
              KEY, null, null, null, null, null, null, Duration.ZERO, null, null, null, null),
          new MessagingProperties(
              KEY,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              Duration.ofSeconds(31),
              null,
              null,
              null),
          new MessagingProperties(
              KEY, null, null, null, null, null, null, null, null, null, 10, null),
          new MessagingProperties(
              KEY, null, null, null, null, null, null, null, null, null, 2_000_000, null),
          new MessagingProperties(
              KEY, null, null, null, null, null, null, null, null, null, null, 0)
        }) {
      assertThatThrownBy(() -> bad.validate("production"))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void toStringNeverContainsASecret() {
    var p = props(KEY, null, null, null, none());
    assertThat(p.toString()).doesNotContain(KEY, APP_SECRET, VERIFY);
  }
}
