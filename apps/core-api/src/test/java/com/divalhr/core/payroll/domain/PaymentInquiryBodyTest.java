package com.divalhr.core.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-095C (ADR 0038 section 4): the canonical inquiry body carries two identifiers only. */
class PaymentInquiryBodyTest {

  private static final UUID REQUEST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID INQUIRY = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void theCanonicalBytesAreExactlyTheSortedCompactJson() {
    assertThat(new String(PaymentInquiryBody.canonical(REQUEST, INQUIRY), StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"inquiryId\":\"22222222-2222-4222-8222-222222222222\","
                + "\"requestId\":\"11111111-1111-4111-8111-111111111111\",\"schemaVersion\":1}");
  }

  @Test
  void theBytesAreDeterministicAndBindBothIdentifiers() {
    assertThat(PaymentInquiryBody.canonical(REQUEST, INQUIRY))
        .isEqualTo(PaymentInquiryBody.canonical(REQUEST, INQUIRY));
    assertThat(PaymentInquiryBody.canonical(UUID.randomUUID(), INQUIRY))
        .isNotEqualTo(PaymentInquiryBody.canonical(REQUEST, INQUIRY));
    assertThat(PaymentInquiryBody.canonical(REQUEST, UUID.randomUUID()))
        .isNotEqualTo(PaymentInquiryBody.canonical(REQUEST, INQUIRY));
  }

  @Test
  void theBodyCarriesNoPaymentDataKeys() {
    String text =
        new String(PaymentInquiryBody.canonical(REQUEST, INQUIRY), StandardCharsets.UTF_8);
    assertThat(text)
        .doesNotContain("amount")
        .doesNotContain("currency")
        .doesNotContain("account")
        .doesNotContain("batch")
        .doesNotContain("lines")
        .doesNotContain(" ")
        .doesNotContain("\n");
  }
}
