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

  /** The vector printed in docs/WEBHOOKS.md: the documentation must stay verifiable. */
  @Test
  void matchesTheDocumentedReceiverVector() {
    byte[] secret =
        java.util.Base64.getUrlDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
    byte[] body =
        ("{\"schemaVersion\":1,\"eventId\":\"6f1c1f0e-8a8b-4b43-9a3e-0f6f7b0c2a07\","
                + "\"eventType\":\"webhook.test.v1\",\"occurredAt\":\"2026-10-20T09:30:00Z\","
                + "\"tenantId\":\"00000000-0000-4000-8000-00000000000a\",\"resource\":{\"kind\":"
                + "\"webhook-test\",\"id\":\"7a2d3c4e-1b5f-4c6d-8e7f-9a0b1c2d3e07\",\"status\":"
                + "\"TEST\",\"version\":1},\"links\":[]}")
            .getBytes(StandardCharsets.UTF_8);
    assertThat(WebhookSigner.sign(secret, 1792746000L, body))
        .isEqualTo("v1=99ec1c16b5c79c2cba0e53ae1ed1eab7820e68109c642793a5888b03fdf6f98a");
  }
}
