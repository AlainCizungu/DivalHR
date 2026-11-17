package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffOutcome;
import java.util.UUID;

/**
 * The payroll-owned port to the controlled partner receiver (MVP-120B, ADR 0042 §5). An adapter
 * turns one signed delivery into exactly one closed {@link Result}; it never throws a partner
 * message, never follows a redirect, never returns arbitrary partner text and keeps the stable
 * application id as the partner idempotency key. Only the closed outcomes {@code
 * RECEIVED_ACKNOWLEDGED}, {@code REJECTED_BY_PARTNER}, {@code TRANSIENT_NOT_SENT}, {@code
 * UNKNOWN_TIMEOUT} and {@code UNKNOWN_BAD_RESPONSE} are ever returned from a delivery.
 */
public interface SalaryAdvancePartnerGateway {

  /**
   * The closed result of one delivery attempt.
   *
   * @param outcome the allow-listed outcome
   * @param reference the bounded partner reference of an acknowledgement, otherwise {@code null}
   */
  record Result(SalaryAdvanceHandoffOutcome outcome, String reference) {}

  /**
   * Sends one attempt.
   *
   * @param profile the operator-controlled profile (exact host and port)
   * @param applicationId the stable opaque application id (reused by every attempt)
   * @param keyVersion the delivery key version
   * @param timestamp the epoch-second timestamp covered by the signature
   * @param signature the {@code v1=<hex>} signature
   * @param body the canonical payload bytes
   * @return the closed result
   */
  Result send(
      SalaryAdvancePartnerProfile profile,
      UUID applicationId,
      String keyVersion,
      long timestamp,
      String signature,
      byte[] body);

  /** What a signed status inquiry proved. */
  enum InquiryOutcome {
    /** The receiver proves it holds the application. */
    RECEIVED,
    /**
     * The receiver proves, signed, that it never accepted the application: the only case a resend
     * may follow.
     */
    NOT_RECEIVED,
    /** No trustworthy answer: the handoff stays {@code DELIVERY_UNKNOWN}. */
    INCONCLUSIVE
  }

  /**
   * The closed result of a status inquiry.
   *
   * @param outcome what was proven
   * @param reference the bounded partner reference when {@code RECEIVED}, otherwise {@code null}
   */
  record InquiryResult(InquiryOutcome outcome, String reference) {}

  /**
   * Asks the receiver for the status of an application by its stable id. A read-like operation: it
   * carries no payload and can never deliver or redeliver anything.
   *
   * @param profile the operator-controlled profile
   * @param applicationId the stable application id
   * @param keyVersion the delivery key version
   * @param timestamp the epoch-second timestamp covered by the signature
   * @param signature the {@code v1=<hex>} inquiry signature
   * @param body the canonical inquiry bytes
   * @return the closed result
   */
  InquiryResult inquire(
      SalaryAdvancePartnerProfile profile,
      UUID applicationId,
      String keyVersion,
      long timestamp,
      String signature,
      byte[] body);
}
