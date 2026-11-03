package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PaymentSubmissionSigner;

/**
 * The submission signer, or nothing when the signing key is not configured (MVP-095B). Without the
 * key every operation that needs it fails closed with a stable 503 and nothing is stored.
 */
public final class PaymentSubmissionSecrets {

  private final PaymentSubmissionSigner signer;

  PaymentSubmissionSecrets(PaymentSubmissionProperties properties) {
    this.signer =
        properties.keyConfigured() ? new PaymentSubmissionSigner(properties.signingKey()) : null;
  }

  /**
   * Wraps a signer (tests).
   *
   * @param signer the signer, or {@code null}
   */
  public PaymentSubmissionSecrets(PaymentSubmissionSigner signer) {
    this.signer = signer;
  }

  /**
   * @return {@code true} when requests can be signed
   */
  public boolean available() {
    return signer != null;
  }

  /**
   * @return the signer
   * @throws IllegalStateException when the key is not configured
   */
  public PaymentSubmissionSigner signer() {
    if (signer == null) {
      throw new IllegalStateException("the submission signing key is not configured");
    }
    return signer;
  }
}
