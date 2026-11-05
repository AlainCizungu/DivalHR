package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PaymentInquiryOutcome;
import com.divalhr.core.payroll.domain.PaymentSubmissionOutcome;
import com.divalhr.core.payroll.domain.PaymentSubmissionProfile;
import java.util.UUID;

/**
 * The payroll-owned port to a controlled payment provider (MVP-095B, D95B-2). An adapter turns one
 * signed request into exactly one closed {@link Result}; it never throws a provider message, never
 * follows a redirect and never returns arbitrary provider text. It must keep the stable request ID
 * as the provider idempotency key.
 */
public interface PaymentProviderGateway {

  /**
   * The closed result of one attempt.
   *
   * @param outcome the allow-listed outcome
   * @param reference the bounded provider reference of an acceptance, otherwise {@code null}
   */
  record Result(PaymentSubmissionOutcome outcome, String reference) {}

  /**
   * Sends one attempt.
   *
   * @param profile the operator-controlled profile (exact host and port)
   * @param requestId the stable request ID (reused by every attempt)
   * @param keyVersion the signing key version
   * @param signature the {@code v1=<hex>} signature of the canonical bytes
   * @param body the canonical payload bytes
   * @return the closed result
   */
  Result send(
      PaymentSubmissionProfile profile,
      UUID requestId,
      int keyVersion,
      String signature,
      byte[] body);

  /**
   * The closed result of one status inquiry (MVP-095C).
   *
   * @param outcome the allow-listed outcome
   */
  record InquiryResult(PaymentInquiryOutcome outcome) {}

  /**
   * Asks the provider for the status of an existing payment request by its stable request ID. This
   * is a read-like operation: it carries no payment data and can never send or resend a payment
   * instruction. The same inquiry ID is reused by every attempt of one investigation.
   *
   * @param profile the operator-controlled profile (exact host and port)
   * @param requestId the stable payment request ID
   * @param inquiryId the stable inquiry ID
   * @param keyVersion the signing key version
   * @param signature the {@code v1=<hex>} inquiry signature of the canonical body
   * @param body the canonical inquiry bytes
   * @return the closed result
   */
  InquiryResult inquire(
      PaymentSubmissionProfile profile,
      UUID requestId,
      UUID inquiryId,
      int keyVersion,
      String signature,
      byte[] body);
}
