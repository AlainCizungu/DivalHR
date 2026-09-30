package com.divalhr.core.identity.domain;

/** The identity provider's "choose your password" email after acceptance (durable, retried). */
public enum CredentialSetupState {
  /** Not accepted yet. */
  NOT_APPLICABLE,
  /** Requested but not yet confirmed; retried by the reconciler. */
  PENDING,
  /** The identity provider accepted the request. */
  SENT,
  /** Retries exhausted; an operator must reset the credential in the identity provider. */
  FAILED
}
