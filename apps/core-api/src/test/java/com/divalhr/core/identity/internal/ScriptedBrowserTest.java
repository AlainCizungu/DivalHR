package com.divalhr.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The test-side TOTP generator matches RFC 6238 (appendix B, SHA-1, last six digits). */
class ScriptedBrowserTest {

  private static final String RFC_SECRET = "12345678901234567890";

  @Test
  void totpMatchesTheRfc6238Vectors() {
    assertThat(ScriptedBrowser.totp(RFC_SECRET, 59L)).isEqualTo("287082");
    assertThat(ScriptedBrowser.totp(RFC_SECRET, 1111111109L)).isEqualTo("081804");
    assertThat(ScriptedBrowser.totp(RFC_SECRET, 1234567890L)).isEqualTo("005924");
    assertThat(ScriptedBrowser.totp(RFC_SECRET, 20000000000L)).isEqualTo("353130");
  }

  @Test
  void entitiesAreDecoded() {
    assertThat(ScriptedBrowser.unescape("a&amp;b &#8217; &#xe9;")).isEqualTo("a&b ’ é");
  }
}
