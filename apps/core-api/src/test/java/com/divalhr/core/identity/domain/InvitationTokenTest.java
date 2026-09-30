package com.divalhr.core.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InvitationTokenTest {

  @Test
  void tokensAre256BitBase64UrlAndUnique() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 2_000; i++) {
      InvitationToken token = InvitationToken.generate();
      assertThat(token.secret()).matches("^[A-Za-z0-9_-]{43}$");
      assertThat(InvitationToken.isWellFormed(token.secret())).isTrue();
      assertThat(token.sha256()).hasSize(32);
      assertThat(seen.add(token.secret())).isTrue();
    }
  }

  @Test
  void shapeCheckAndRedaction() {
    assertThat(InvitationToken.isWellFormed(null)).isFalse();
    assertThat(InvitationToken.isWellFormed("A".repeat(42))).isFalse();
    assertThat(InvitationToken.isWellFormed("A".repeat(44))).isFalse();
    assertThat(InvitationToken.isWellFormed("A".repeat(42) + "=")).isFalse();
    assertThat(InvitationToken.isWellFormed("A".repeat(42) + "+")).isFalse();
    InvitationToken token = InvitationToken.generate();
    assertThat(token.toString()).doesNotContain(token.secret()).contains("redacted");
    assertThat(InvitationToken.hashOf(token.secret())).isEqualTo(token.sha256());
  }
}
