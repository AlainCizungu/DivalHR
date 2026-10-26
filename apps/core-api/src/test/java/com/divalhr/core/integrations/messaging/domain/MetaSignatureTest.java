package com.divalhr.core.integrations.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** MVP-111 (D111-10): the X-Hub-Signature-256 over the exact raw bytes. */
class MetaSignatureTest {

  private static final String SECRET = "test-only-meta-app-secret-0123456789";
  private static final byte[] BODY =
      "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);

  private static String independentHmac(String secret, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return java.util.HexFormat.of().formatHex(mac.doFinal(body));
  }

  @Test
  void matchesAnIndependentlyComputedHmacWithTheSha256Prefix() throws Exception {
    assertThat(MetaSignature.sign(SECRET, BODY))
        .isEqualTo("sha256=" + independentHmac(SECRET, BODY));
  }

  @Test
  void verifiesItsOwnSignatureAndAnUppercaseHexForm() throws Exception {
    String header = MetaSignature.sign(SECRET, BODY);
    assertThat(MetaSignature.verify(SECRET, BODY, header)).isTrue();
    assertThat(
            MetaSignature.verify(
                SECRET, BODY, "sha256=" + independentHmac(SECRET, BODY).toUpperCase()))
        .isTrue();
  }

  @Test
  void anyChangeToTheBodyTheSecretOrTheHeaderFails() throws Exception {
    String header = MetaSignature.sign(SECRET, BODY);
    byte[] other = "{\"object\":\"whatsapp_business_account\" }".getBytes(StandardCharsets.UTF_8);
    assertThat(MetaSignature.verify(SECRET, other, header)).isFalse();
    assertThat(MetaSignature.verify(SECRET + "x", BODY, header)).isFalse();
    String flipped = header.substring(0, header.length() - 1) + (header.endsWith("0") ? "1" : "0");
    assertThat(MetaSignature.verify(SECRET, BODY, flipped)).isFalse();
  }

  @Test
  void malformedMissingOrOtherSchemeHeadersFailClosed() {
    String good = MetaSignature.sign(SECRET, BODY);
    String hex = good.substring("sha256=".length());
    for (String bad :
        new String[] {
          null,
          "",
          "sha256=",
          "sha256=abc",
          "sha1=" + hex.substring(0, 40),
          hex,
          "SHA256=" + hex,
          "sha256=" + hex + "00",
          "sha256=" + hex.substring(0, 63) + "g",
          good + " ",
          " " + good
        }) {
      assertThat(MetaSignature.verify(SECRET, BODY, bad)).as(String.valueOf(bad)).isFalse();
    }
  }

  @Test
  void aBlankOrMissingSecretVerifiesNothing() {
    String header = MetaSignature.sign(SECRET, BODY);
    assertThat(MetaSignature.verify(null, BODY, header)).isFalse();
    assertThat(MetaSignature.verify("", BODY, header)).isFalse();
    assertThat(MetaSignature.verify("   ", BODY, header)).isFalse();
  }

  @Test
  void theComparisonIsConstantTimeByConstruction() throws Exception {
    // The implementation compares decoded digests with MessageDigest.isEqual: a header that
    // differs only in its first byte and one that differs only in its last byte both fail the same
    // way, and neither throws.
    String header = MetaSignature.sign(SECRET, BODY);
    String first = (header.charAt(7) == 'a' ? "sha256=b" : "sha256=a") + header.substring(8);
    String last = header.substring(0, header.length() - 1) + (header.endsWith("a") ? "b" : "a");
    assertThat(MetaSignature.verify(SECRET, BODY, first)).isFalse();
    assertThat(MetaSignature.verify(SECRET, BODY, last)).isFalse();
    String source =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/main/java/com/divalhr/core/integrations/messaging/domain/MetaSignature.java"));
    assertThat(source).contains("MessageDigest.isEqual").doesNotContain(".equals(provided");
  }
}
