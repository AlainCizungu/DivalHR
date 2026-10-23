package com.divalhr.core.integrations.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

  private static final byte[] SECRET =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OLD =
      "another-secret-another-secret-123".getBytes(StandardCharsets.UTF_8);
  private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

  @Test
  void matchesAnIndependentlyComputedHmacOverTimestampNewlineBody() {
    assertThat(WebhookSigner.sign(SECRET, 1700000000L, BODY))
        .isEqualTo("v1=f824efffbec511ae8031371967ae73d2320428c743d851a31e27f2aab9602ea9");
  }

  @Test
  void sendsBothSignaturesDuringOverlapCurrentFirst() {
    assertThat(WebhookSigner.header(List.of(SECRET, OLD), 1700000000L, BODY))
        .isEqualTo(
            "v1=f824efffbec511ae8031371967ae73d2320428c743d851a31e27f2aab9602ea9,"
                + "v1=7cb4cb9b83287167b5c2c9149fb1637eaedc983b972086f9d62a55c2875d747b");
  }

  @Test
  void aChangedBodyTimestampOrSecretChangesTheSignature() {
    String base = WebhookSigner.sign(SECRET, 1700000000L, BODY);
    assertThat(WebhookSigner.sign(SECRET, 1700000001L, BODY)).isNotEqualTo(base);
    assertThat(
            WebhookSigner.sign(SECRET, 1700000000L, "{\"a\":2}".getBytes(StandardCharsets.UTF_8)))
        .isNotEqualTo(base);
    assertThat(WebhookSigner.sign(OLD, 1700000000L, BODY)).isNotEqualTo(base);
  }
}
