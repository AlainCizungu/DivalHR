package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffSigner;

/**
 * The delivery and callback signers, or nothing when a key is not configured (MVP-120B). Without
 * the delivery key a handoff cannot be authorized (stable 503, nothing stored); without the
 * callback key every callback is refused with the generic invalid-callback answer.
 */
public final class SalaryAdvancePartnerSecrets {

  private final SalaryAdvanceHandoffSigner delivery;
  private final SalaryAdvanceHandoffSigner callback;
  private final String keyVersion;

  SalaryAdvancePartnerSecrets(SalaryAdvancePartnerProperties properties) {
    this.delivery =
        properties.deliveryKeyConfigured()
            ? new SalaryAdvanceHandoffSigner(properties.deliverySigningKey())
            : null;
    this.callback =
        properties.callbackKeyConfigured()
            ? new SalaryAdvanceHandoffSigner(properties.callbackSigningKey())
            : null;
    this.keyVersion = properties.keyVersion();
  }

  /**
   * Wraps signers (tests).
   *
   * @param delivery the delivery signer, or {@code null}
   * @param callback the callback signer, or {@code null}
   * @param keyVersion the key version
   */
  public SalaryAdvancePartnerSecrets(
      SalaryAdvanceHandoffSigner delivery, SalaryAdvanceHandoffSigner callback, String keyVersion) {
    this.delivery = delivery;
    this.callback = callback;
    this.keyVersion = keyVersion;
  }

  /**
   * @return {@code true} when deliveries can be signed
   */
  public boolean deliveryAvailable() {
    return delivery != null;
  }

  /**
   * @return {@code true} when callbacks can be verified
   */
  public boolean callbackAvailable() {
    return callback != null;
  }

  /**
   * @return the delivery signer
   * @throws IllegalStateException when the key is not configured
   */
  public SalaryAdvanceHandoffSigner delivery() {
    if (delivery == null) {
      throw new IllegalStateException("the partner delivery signing key is not configured");
    }
    return delivery;
  }

  /**
   * @return the callback signer
   * @throws IllegalStateException when the key is not configured
   */
  public SalaryAdvanceHandoffSigner callback() {
    if (callback == null) {
      throw new IllegalStateException("the partner callback signing key is not configured");
    }
    return callback;
  }

  /**
   * @return the version of the delivery key recorded with every handoff
   */
  public String keyVersion() {
    return keyVersion;
  }
}
