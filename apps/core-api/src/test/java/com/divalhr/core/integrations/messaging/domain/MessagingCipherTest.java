package com.divalhr.core.integrations.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-111 (D111-3): token and number sealing, binding, tamper detection and key separation. */
class MessagingCipherTest {

  private static final String KEY = "test-only-messaging-key-0123456789abcdef";
  private static final String TOKEN = "EAAGtest0123456789ABCDEFGHIJKLMNOP";
  private final UUID tenant = UUID.randomUUID();
  private final UUID connector = UUID.randomUUID();
  private final UUID recipient = UUID.randomUUID();
  private final PhoneNumber number = PhoneNumber.parse("+243812345678").orElseThrow();

  @Test
  void aTokenRoundTripsAndPlaintextIsNeverInTheBlob() {
    var cipher = new MessagingCipher(KEY);
    byte[] blob = cipher.sealCredential(TOKEN, tenant, connector, 1);
    assertThat(blob[0]).isEqualTo((byte) MessagingCipher.ALGORITHM_VERSION);
    assertThat(blob).hasSize(1 + 12 + TOKEN.length() + 16);
    assertThat(new String(blob, StandardCharsets.ISO_8859_1)).doesNotContain(TOKEN);
    assertThat(cipher.openCredential(blob, tenant, connector, 1)).isEqualTo(TOKEN);
  }

  @Test
  void aNumberRoundTripsAndPlaintextIsNeverInTheBlob() {
    var cipher = new MessagingCipher(KEY);
    byte[] blob = cipher.sealNumber(number, tenant, connector, recipient);
    assertThat(new String(blob, StandardCharsets.ISO_8859_1)).doesNotContain("243812345678");
    assertThat(cipher.openNumber(blob, tenant, connector, recipient)).isEqualTo(number);
    assertThat(blob.length).isBetween(40, 256);
  }

  @Test
  void aFreshNonceIsUsedEachTime() {
    var cipher = new MessagingCipher(KEY);
    assertThat(cipher.sealCredential(TOKEN, tenant, connector, 1))
        .isNotEqualTo(cipher.sealCredential(TOKEN, tenant, connector, 1));
  }

  @Test
  void everyBindingChangeAWrongKeyAndTamperingFail() {
    var cipher = new MessagingCipher(KEY);
    byte[] blob = cipher.sealCredential(TOKEN, tenant, connector, 1);
    assertThatThrownBy(
            () ->
                new MessagingCipher(KEY.replace("0123", "9999"))
                    .openCredential(blob, tenant, connector, 1))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.openCredential(blob, UUID.randomUUID(), connector, 1))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.openCredential(blob, tenant, UUID.randomUUID(), 1))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.openCredential(blob, tenant, connector, 2))
        .isInstanceOf(IllegalStateException.class);
    for (int i : new int[] {0, 5, blob.length - 1}) {
      byte[] tampered = Arrays.copyOf(blob, blob.length);
      tampered[i] ^= 1;
      assertThatThrownBy(() -> cipher.openCredential(tampered, tenant, connector, 1))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(() -> cipher.openCredential(new byte[3], tenant, connector, 1))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.openCredential(null, tenant, connector, 1))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aCredentialAndANumberCannotBeSwappedAndNumbersBindToTheirRecipient() {
    var cipher = new MessagingCipher(KEY);
    byte[] numberBlob = cipher.sealNumber(number, tenant, connector, recipient);
    assertThatThrownBy(() -> cipher.openNumber(numberBlob, tenant, connector, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.openNumber(numberBlob, UUID.randomUUID(), connector, recipient))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.openCredential(numberBlob, tenant, connector, 1))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rotationYieldsADifferentCiphertextForANewVersionAndTheOldOneStillOpensOnlyAsItself() {
    var cipher = new MessagingCipher(KEY);
    byte[] first = cipher.sealCredential(TOKEN, tenant, connector, 1);
    byte[] second = cipher.sealCredential(TOKEN + "x", tenant, connector, 2);
    assertThat(cipher.openCredential(first, tenant, connector, 1)).isEqualTo(TOKEN);
    assertThat(cipher.openCredential(second, tenant, connector, 2)).isEqualTo(TOKEN + "x");
    assertThatThrownBy(() -> cipher.openCredential(second, tenant, connector, 1))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theDigestIsKeyedStableAndScopedToTenantAndConnector() {
    var cipher = new MessagingCipher(KEY);
    byte[] digest = cipher.digest(number, tenant, connector);
    assertThat(digest).hasSize(32);
    assertThat(cipher.digest(number, tenant, connector)).isEqualTo(digest);
    assertThat(cipher.digest(number, UUID.randomUUID(), connector)).isNotEqualTo(digest);
    assertThat(cipher.digest(number, tenant, UUID.randomUUID())).isNotEqualTo(digest);
    assertThat(new MessagingCipher(KEY.replace("0123", "9999")).digest(number, tenant, connector))
        .isNotEqualTo(digest);
    assertThat(cipher.digest(PhoneNumber.parse("+243812345679").orElseThrow(), tenant, connector))
        .isNotEqualTo(digest);
  }

  @Test
  void theIdempotencyDigestIsKeyedSeparatedFromThePhoneDigestAndHidesTheValue() {
    var cipher = new MessagingCipher(KEY);
    String digest = cipher.idempotencyDigest(TOKEN);
    assertThat(digest).matches("^[0-9a-f]{64}$").doesNotContain(TOKEN);
    assertThat(cipher.idempotencyDigest(TOKEN)).isEqualTo(digest);
    assertThat(cipher.idempotencyDigest(TOKEN + "x")).isNotEqualTo(digest);
    assertThat(new MessagingCipher(KEY.replace("0123", "9999")).idempotencyDigest(TOKEN))
        .isNotEqualTo(digest);
  }

  @Test
  void errorsNeverContainTheKeyTheTokenOrTheNumber() {
    assertThatThrownBy(() -> new MessagingCipher("short"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("short");
    var cipher = new MessagingCipher(KEY);
    byte[] blob = cipher.sealCredential(TOKEN, tenant, connector, 1);
    assertThatThrownBy(() -> cipher.openCredential(blob, tenant, connector, 9))
        .hasMessageNotContaining(KEY)
        .hasMessageNotContaining(TOKEN);
  }
}
