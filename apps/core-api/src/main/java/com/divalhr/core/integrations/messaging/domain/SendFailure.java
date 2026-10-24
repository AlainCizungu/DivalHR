package com.divalhr.core.integrations.messaging.domain;

/**
 * The closed failure classes stored and exposed for a messaging attempt (D111-9). No provider error
 * text is ever kept: the provider's response is reduced to one of these.
 */
public enum SendFailure {
  /** The provider name did not resolve. Retryable. */
  DNS_FAILED(true),
  /** The connection could not be opened in time. Retryable. */
  CONNECT_TIMEOUT(true),
  /** No answer arrived in time; acceptance is unknown. Retryable. */
  REQUEST_TIMEOUT(true),
  /** The TLS handshake failed. Retryable. */
  TLS_FAILED(true),
  /** The connection broke; acceptance is unknown. Retryable. */
  CONNECTION_FAILED(true),
  /** The provider throttled the request (HTTP 429 or a documented throttle code). Retryable. */
  RATE_LIMITED(true),
  /** A provider 5xx answer or an answer without a message id. Retryable. */
  PROVIDER_FAILED(true),
  /** Another HTTP answer that is not a success and not retryable. */
  HTTP_RESPONSE(false),
  /** The provider refused the credential. Not retryable. */
  CREDENTIAL_INVALID(false),
  /** The provider refused the template. Not retryable. */
  TEMPLATE_INVALID(false),
  /** The provider refused the recipient. Not retryable. */
  RECIPIENT_REJECTED(false),
  /** An internal problem (for example the encryption key is unreadable). Not retried. */
  INTERNAL_FAILURE(false),
  /** The connector or recipient was no longer active. Not retried. */
  CONNECTOR_INACTIVE(false);

  private final boolean retryable;

  SendFailure(boolean retryable) {
    this.retryable = retryable;
  }

  /**
   * @return whether the attempt may be repeated while attempts remain (D111-9)
   */
  public boolean retryable() {
    return retryable;
  }

  /**
   * @return whether the failure leaves open that the provider already accepted the message
   */
  public boolean acceptanceUnknown() {
    return this == REQUEST_TIMEOUT || this == CONNECTION_FAILED || this == PROVIDER_FAILED;
  }
}
