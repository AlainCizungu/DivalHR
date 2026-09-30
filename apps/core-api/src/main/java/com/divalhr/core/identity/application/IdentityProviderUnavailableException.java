package com.divalhr.core.identity.application;

import java.io.Serial;

/** The identity provider could not be reached or failed; the operation may be retried. */
public class IdentityProviderUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception with a safe reason code (never a provider response body).
   *
   * @param reason safe, low-cardinality reason, e.g. {@code timeout}
   */
  public IdentityProviderUnavailableException(String reason) {
    super(reason);
  }
}
