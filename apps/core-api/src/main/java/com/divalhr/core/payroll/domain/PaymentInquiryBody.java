package com.divalhr.core.payroll.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The canonical body of a provider status inquiry (MVP-095C, ADR 0038 section 4): UTF-8 JSON, keys
 * sorted, no whitespace, fixed schema. It carries the two identifiers and nothing else: no
 * employee, destination, amount, currency, line, batch or original payment payload.
 */
public final class PaymentInquiryBody {

  private PaymentInquiryBody() {}

  /**
   * The canonical body.
   *
   * @param requestId the stable payment request ID
   * @param inquiryId the stable inquiry ID
   * @return the bytes
   */
  public static byte[] canonical(UUID requestId, UUID inquiryId) {
    return ("{\"inquiryId\":\""
            + inquiryId
            + "\",\"requestId\":\""
            + requestId
            + "\",\"schemaVersion\":1}")
        .getBytes(StandardCharsets.UTF_8);
  }
}
