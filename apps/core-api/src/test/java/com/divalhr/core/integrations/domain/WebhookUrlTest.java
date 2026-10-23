package com.divalhr.core.integrations.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookUrlTest {

  @Test
  void normalizesHostCasePortAndPath() {
    var url = WebhookUrl.parse("https://Hooks.Example.COM:443", List.of()).orElseThrow();
    assertThat(url.normalized()).isEqualTo("https://hooks.example.com/");
    assertThat(url.host()).isEqualTo("hooks.example.com");
    assertThat(url.port()).isEqualTo(443);
    assertThat(url.requestTarget()).isEqualTo("/");
  }

  @Test
  void keepsPathAndQueryAndAllowListedPort() {
    var url =
        WebhookUrl.parse("https://hooks.example.com:8443/in/x?a=1", List.of(8443)).orElseThrow();
    assertThat(url.normalized()).isEqualTo("https://hooks.example.com:8443/in/x?a=1");
    assertThat(url.requestTarget()).isEqualTo("/in/x?a=1");
  }

  @Test
  void acceptsPunycodeButRefusesUnicodeHosts() {
    var url = WebhookUrl.parse("https://xn--bcher-kva.example/hook", List.of()).orElseThrow();
    assertThat(url.host()).isEqualTo("xn--bcher-kva.example");
    assertThat(WebhookUrl.parse("https://bücher.example/hook", List.of())).isEmpty();
  }

  @Test
  void toStringNeverShowsTheEndpoint() {
    var url = WebhookUrl.parse("https://hooks.example.com/secret-path", List.of()).orElseThrow();
    assertThat(url.toString()).doesNotContain("hooks").doesNotContain("secret-path");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://hooks.example.com/",
        "ftp://hooks.example.com/",
        "//hooks.example.com/",
        "hooks.example.com",
        "https://user@hooks.example.com/",
        "https://user:pw@hooks.example.com/",
        "https://hooks.example.com/#frag",
        "https://hooks.example.com:8443/",
        "https://hooks.example.com:80/",
        "https://127.0.0.1/",
        "https://[::1]/",
        "https://[2001:db8::1]:443/",
        "https://169.254.169.254/latest",
        "https://2130706433/",
        "https://0x7f000001/",
        "https://localhost/",
        "https://intranet/",
        "https://hooks.example.com./",
        "https://-bad.example.com/",
        "https://exa mple.com/",
        "https://hooks.example.com/a b",
        "https://hooks.example.com\\@evil.example/",
        " https://hooks.example.com/",
        "https://hooks.example.com/\n",
        "https://1.2.3.4.5/",
        "https://example.123/"
      })
  void refusesUnacceptableEndpoints(String raw) {
    assertThat(WebhookUrl.parse(raw, List.of())).as(raw).isEmpty();
  }

  @Test
  void refusesNullBlankAndOversized() {
    assertThat(WebhookUrl.parse(null, List.of())).isEmpty();
    assertThat(WebhookUrl.parse("  ", List.of())).isEmpty();
    assertThat(WebhookUrl.parse("https://a.example/" + "x".repeat(2100), List.of())).isEmpty();
  }
}
