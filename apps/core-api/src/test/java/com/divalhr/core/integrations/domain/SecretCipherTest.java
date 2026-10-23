package com.divalhr.core.integrations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

  private static final String KEY = "test-only-webhook-key-0123456789abcdef";
  private final UUID tenant = UUID.randomUUID();
  private final UUID webhook = UUID.randomUUID();

  @Test
  void roundTripsAndNeverStoresPlaintext() {
    var cipher = new SecretCipher(KEY);
    byte[] secret = SecretCipher.newSigningSecret();
    byte[] blob = cipher.encrypt(secret, tenant, webhook, 1);
    assertThat(blob).hasSize(1 + 12 + 32 + 16);
    assertThat(new String(blob, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(new String(secret, java.nio.charset.StandardCharsets.ISO_8859_1));
    assertThat(cipher.decrypt(blob, tenant, webhook, 1)).isEqualTo(secret);
  }

  @Test
  void usesAFreshNonceEachTime() {
    var cipher = new SecretCipher(KEY);
    byte[] secret = SecretCipher.newSigningSecret();
    assertThat(cipher.encrypt(secret, tenant, webhook, 1))
        .isNotEqualTo(cipher.encrypt(secret, tenant, webhook, 1));
  }

  @Test
  void aWrongKeyTamperingOrAnyBindingChangeFails() {
    var cipher = new SecretCipher(KEY);
    byte[] blob = cipher.encrypt(SecretCipher.newSigningSecret(), tenant, webhook, 1);
    assertThatThrownBy(
            () -> new SecretCipher(KEY.replace("0123", "9999")).decrypt(blob, tenant, webhook, 1))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.decrypt(blob, UUID.randomUUID(), webhook, 1))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.decrypt(blob, tenant, UUID.randomUUID(), 1))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.decrypt(blob, tenant, webhook, 2))
        .isInstanceOf(IllegalStateException.class);
    for (int i : new int[] {0, 5, blob.length - 1}) {
      byte[] tampered = Arrays.copyOf(blob, blob.length);
      tampered[i] ^= 1;
      assertThatThrownBy(() -> cipher.decrypt(tampered, tenant, webhook, 1))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(() -> cipher.decrypt(new byte[3], tenant, webhook, 1))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void errorsNeverContainKeyOrSecret() {
    assertThatThrownBy(() -> new SecretCipher("short"))
        .hasMessageNotContaining("short")
        .isInstanceOf(IllegalStateException.class);
    var cipher = new SecretCipher(KEY);
    assertThatThrownBy(() -> cipher.decrypt(new byte[40], tenant, webhook, 1))
        .hasMessageNotContaining(KEY);
  }
}
