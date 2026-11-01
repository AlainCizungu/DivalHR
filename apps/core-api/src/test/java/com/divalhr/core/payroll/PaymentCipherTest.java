package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.PaymentCipher;
import com.divalhr.core.payroll.domain.PaymentMasking;
import com.divalhr.core.payroll.domain.PaymentSealException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-095A (D95A-3): payment values are sealed with an authenticated, context-bound envelope and
 * fingerprinted with a separate keyed function; masking keeps only the last digits. Synthetic
 * values only.
 */
class PaymentCipherTest {

  private static final String KEY = "test-only-payment-encryption-key-000001";
  private static final String ACCOUNT = "SYNTHETIC-ACCOUNT-0012345678901";

  private final PaymentCipher cipher = new PaymentCipher(KEY);
  private final UUID tenant = UUID.randomUUID();
  private final UUID employee = UUID.randomUUID();
  private final UUID destination = UUID.randomUUID();

  @Test
  void sealedValuesOpenOnlyInTheirOwnContext() {
    byte[] sealed = cipher.seal(ACCOUNT, tenant, employee, destination, 1);
    assertThat(cipher.open(sealed, tenant, employee, destination, 1)).isEqualTo(ACCOUNT);
    assertThat(new String(sealed, StandardCharsets.ISO_8859_1))
        .doesNotContain("0012345678901")
        .doesNotContain("SYNTHETIC");
    assertThatThrownBy(() -> cipher.open(sealed, UUID.randomUUID(), employee, destination, 1))
        .isInstanceOf(PaymentSealException.class);
    assertThatThrownBy(() -> cipher.open(sealed, tenant, UUID.randomUUID(), destination, 1))
        .isInstanceOf(PaymentSealException.class);
    assertThatThrownBy(() -> cipher.open(sealed, tenant, employee, UUID.randomUUID(), 1))
        .isInstanceOf(PaymentSealException.class);
    assertThatThrownBy(() -> cipher.open(sealed, tenant, employee, destination, 2))
        .isInstanceOf(PaymentSealException.class);
  }

  @Test
  void aTamperedOrForeignEnvelopeFailsClosedWithoutDetail() {
    byte[] sealed = cipher.seal(ACCOUNT, tenant, employee, destination, 1);
    byte[] tampered = sealed.clone();
    tampered[tampered.length - 1] ^= 1;
    assertThatThrownBy(() -> cipher.open(tampered, tenant, employee, destination, 1))
        .isInstanceOf(PaymentSealException.class)
        .hasMessageNotContaining("0012345678901");
    assertThatThrownBy(
            () ->
                new PaymentCipher("a-different-payment-key-of-32-bytes-0001")
                    .open(sealed, tenant, employee, destination, 1))
        .isInstanceOf(PaymentSealException.class);
    assertThatThrownBy(() -> cipher.open(new byte[] {1, 2, 3}, tenant, employee, destination, 1))
        .isInstanceOf(PaymentSealException.class);
    assertThatThrownBy(() -> cipher.open(null, tenant, employee, destination, 1))
        .isInstanceOf(PaymentSealException.class);
  }

  @Test
  void everySealingUsesAFreshNonce() {
    byte[] first = cipher.seal(ACCOUNT, tenant, employee, destination, 1);
    byte[] second = cipher.seal(ACCOUNT, tenant, employee, destination, 1);
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void aKeyShorterThan32BytesIsRefused() {
    assertThatThrownBy(() -> new PaymentCipher("short"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least 32 bytes")
        .hasMessageNotContaining("short");
  }

  @Test
  void fingerprintsAreKeyedPerTenantAndPurposeAndNeverAPlainHash() throws Exception {
    String first = cipher.fingerprint(tenant, "identifier", ACCOUNT);
    assertThat(first)
        .matches("^[0-9a-f]{64}$")
        .isEqualTo(cipher.fingerprint(tenant, "identifier", ACCOUNT));
    assertThat(cipher.fingerprint(UUID.randomUUID(), "identifier", ACCOUNT)).isNotEqualTo(first);
    assertThat(cipher.fingerprint(tenant, "content", ACCOUNT)).isNotEqualTo(first);
    assertThat(cipher.fingerprint(tenant, "identifier", ACCOUNT + "1")).isNotEqualTo(first);
    byte[] plain =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(ACCOUNT.getBytes(StandardCharsets.UTF_8));
    assertThat(first).isNotEqualTo(java.util.HexFormat.of().formatHex(plain));
    assertThat(
            new PaymentCipher("a-different-payment-key-of-32-bytes-0001")
                .fingerprint(tenant, "identifier", ACCOUNT))
        .isNotEqualTo(first);
  }

  @Test
  void maskingKeepsOnlyTheLastDigits() {
    assertThat(PaymentMasking.account("0012345678901")).isEqualTo("****8901");
    assertThat(PaymentMasking.account("123456")).isEqualTo("****56");
    assertThat(PaymentMasking.phone("+243810000123")).isEqualTo("******123");
  }
}
