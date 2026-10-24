package com.divalhr.core.integrations.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MVP-111 (D111-7): the only link a notification carries is the operator origin plus a fixed path.
 */
class DeepLinkTest {

  @Test
  void normalizesAnHttpsOriginAndStripsTrailingSlashes() {
    assertThat(DeepLink.normalizeBase("https://hr-dev.divalhr.com/", false))
        .contains("https://hr-dev.divalhr.com");
    assertThat(DeepLink.normalizeBase("  https://app.example.com:8443//  ", false))
        .contains("https://app.example.com:8443");
  }

  @Test
  void refusesAnythingThatIsNotABareOrigin() {
    for (String bad :
        List.of(
            "",
            "   ",
            "http://app.example.com",
            "https://app.example.com/path",
            "https://app.example.com?x=1",
            "https://app.example.com#f",
            "https://user:pw@app.example.com",
            "https://APP.example.com",
            "https://app example.com",
            "ftp://app.example.com",
            "app.example.com",
            "javascript:alert(1)",
            "http://localhost:3000")) {
      assertThat(DeepLink.normalizeBase(bad, false)).as(bad).isEmpty();
    }
    assertThat(DeepLink.normalizeBase(null, false)).isEmpty();
    assertThat(DeepLink.normalizeBase("https://" + "a".repeat(260) + ".com", false)).isEmpty();
  }

  @Test
  void plainHttpIsAcceptedOnlyForLocalhostWhenRelaxed() {
    assertThat(DeepLink.normalizeBase("http://localhost:3000", true))
        .contains("http://localhost:3000");
    assertThat(DeepLink.normalizeBase("http://localhost", true)).contains("http://localhost");
    assertThat(DeepLink.normalizeBase("http://evil.example.com", true)).isEmpty();
    assertThat(DeepLink.normalizeBase("http://localhost.evil.example.com", true)).isEmpty();
    assertThat(DeepLink.normalizeBase("http://localhost:3000", false)).isEmpty();
  }

  @Test
  void everyTypeHasOneAllowListedRelativePath() {
    String base = "https://hr-dev.divalhr.com";
    for (NotificationType type : NotificationType.values()) {
      assertThat(type.path()).startsWith("/admin/").doesNotContain("?", "#", "..", "//");
      assertThat(DeepLink.absolute(base, type)).isEqualTo(base + type.path());
      assertThat(DeepLink.buttonSuffix(type))
          .isEqualTo(type.path().substring(1))
          .doesNotStartWith("/");
    }
    assertThat(DeepLink.absolute(base, NotificationType.LEAVE_DECISION))
        .isEqualTo("https://hr-dev.divalhr.com/admin/leave-approvals");
  }

  @Test
  void theTemplateButtonMustDeclareTheBaseWithOneVariable() {
    assertThat(DeepLink.expectedButtonUrl("https://hr-dev.divalhr.com"))
        .isEqualTo("https://hr-dev.divalhr.com/{{1}}");
  }
}
