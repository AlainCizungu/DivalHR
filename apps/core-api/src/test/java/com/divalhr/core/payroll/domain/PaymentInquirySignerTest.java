package com.divalhr.core.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-095C (ADR 0038 section 4): inquiry signatures are domain separated from payment requests. */
class PaymentInquirySignerTest {

  private static final UUID REQUEST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID INQUIRY = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String KEY = "test-only-payment-submission-signing-key-001";

  private final PaymentSubmissionSigner signer = new PaymentSubmissionSigner(KEY);
  private final byte[] body = PaymentInquiryBody.canonical(REQUEST, INQUIRY);

  @Test
  void anInquirySignatureRoundTripsAndIsBoundToEveryInput() {
    String signature = signer.signInquiry(REQUEST, INQUIRY, body);
    assertThat(signature).matches("v1=[0-9a-f]{64}");
    assertThat(signer.signInquiry(REQUEST, INQUIRY, body)).isEqualTo(signature);
    assertThat(signer.verifyInquiry(REQUEST, INQUIRY, body, signature)).isTrue();
    // A tampered body fails.
    byte[] changed = body.clone();
    changed[changed.length - 2] ^= 1;
    assertThat(signer.verifyInquiry(REQUEST, INQUIRY, changed, signature)).isFalse();
    // A different inquiry ID or request ID fails.
    assertThat(signer.verifyInquiry(REQUEST, UUID.randomUUID(), body, signature)).isFalse();
    assertThat(signer.verifyInquiry(UUID.randomUUID(), INQUIRY, body, signature)).isFalse();
    // Swapping the two identifiers fails.
    assertThat(signer.verifyInquiry(INQUIRY, REQUEST, body, signature)).isFalse();
    // Missing, malformed or another key's signature fails.
    assertThat(signer.verifyInquiry(REQUEST, INQUIRY, body, null)).isFalse();
    assertThat(signer.verifyInquiry(REQUEST, INQUIRY, body, "v1=")).isFalse();
    assertThat(signer.verifyInquiry(REQUEST, INQUIRY, body, signature.toUpperCase())).isFalse();
    assertThat(
            new PaymentSubmissionSigner(KEY + "x").verifyInquiry(REQUEST, INQUIRY, body, signature))
        .isFalse();
  }

  @Test
  void aPaymentRequestSignatureIsNotAnInquirySignatureAndTheReverse() {
    String payment = signer.sign(REQUEST, body);
    String inquiry = signer.signInquiry(REQUEST, INQUIRY, body);
    assertThat(payment).isNotEqualTo(inquiry);
    assertThat(signer.verifyInquiry(REQUEST, INQUIRY, body, payment)).isFalse();
    assertThat(signer.verify(REQUEST, body, inquiry)).isFalse();
    // Each still verifies under its own rule.
    assertThat(signer.verify(REQUEST, body, payment)).isTrue();
    assertThat(signer.verifyInquiry(REQUEST, INQUIRY, body, inquiry)).isTrue();
  }

  @Test
  void anAnswerSignatureOverAnswerBytesVerifiesOnlyForThoseBytes() {
    byte[] answer = "{\"status\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8);
    String signature = signer.signInquiry(REQUEST, INQUIRY, answer);
    assertThat(signer.verifyInquiry(REQUEST, INQUIRY, answer, signature)).isTrue();
    assertThat(signer.verifyInquiry(REQUEST, INQUIRY, body, signature)).isFalse();
  }
}
