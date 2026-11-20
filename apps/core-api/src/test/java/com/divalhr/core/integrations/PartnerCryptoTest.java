package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.integrations.application.IntegrationProperties;
import com.divalhr.core.integrations.partner.PartnerCrypto;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-120D: confidential partner data is bound to its application and digests are keyed. */
class PartnerCryptoTest {

  private static PartnerCrypto crypto(String key) {
    return new PartnerCrypto(new IntegrationProperties(key, null, null, null, null, null));
  }

  private static final String KEY = "test-only-partner-crypto-key-0000000001";

  @Test
  void sealedDataOpensOnlyWithItsOwnContext() {
    PartnerCrypto crypto = crypto(KEY);
    byte[] sealed = crypto.seal("synthetic contact".getBytes(StandardCharsets.UTF_8), "ctx-a");
    assertThat(new String(crypto.open(sealed, "ctx-a"), StandardCharsets.UTF_8))
        .isEqualTo("synthetic contact");
    assertThatThrownBy(() -> crypto.open(sealed, "ctx-b")).isInstanceOf(IllegalStateException.class);
    byte[] tampered = sealed.clone();
    tampered[tampered.length - 1] ^= 1;
    assertThatThrownBy(() -> crypto.open(tampered, "ctx-a")).isInstanceOf(IllegalStateException.class);
    assertThat(sealed).isNotEqualTo(crypto.seal("synthetic contact".getBytes(StandardCharsets.UTF_8), "ctx-a"));
    assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("synthetic contact");
  }

  @Test
  void digestsAreDeterministicKeyedAndDomainSeparated() {
    PartnerCrypto crypto = crypto(KEY);
    String value = "synthetic@example.test";
    assertThat(crypto.digest("email", value)).matches("^[0-9a-f]{64}$").isEqualTo(crypto.digest("email", value));
    assertThat(crypto.digest("email", value)).isNotEqualTo(crypto.digest("organization", value));
    assertThat(crypto.digest("email", value)).isNotEqualTo(crypto("another-test-only-key-00000000000000002").digest("email", value));
    assertThat(crypto.digest("email", value)).doesNotContain(value);
  }

  @Test
  void withoutAKeyItIsUnavailableAndFailsClosed() {
    PartnerCrypto crypto = crypto(null);
    assertThat(crypto.available()).isFalse();
    assertThatThrownBy(() -> crypto.seal(new byte[] {1}, "ctx")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> crypto.digest("email", UUID.randomUUID().toString()))
        .isInstanceOf(IllegalStateException.class);
  }
}
