package com.divalhr.core.integrations.domain;

/** Closed set of delivery failure classes (D110-10). Never carries an address or a message. */
public enum FailureClass {
  /** Name resolved only to, or also to, a forbidden address. */
  DNS_BLOCKED,
  /** Name did not resolve. */
  DNS_FAILED,
  /** TCP connect exceeded its deadline. */
  CONNECT_TIMEOUT,
  /** The whole request exceeded its deadline. */
  REQUEST_TIMEOUT,
  /** TLS handshake or certificate or hostname verification failed. */
  TLS_FAILED,
  /** Connection refused, reset or malformed answer. */
  CONNECTION_FAILED,
  /** A response arrived and its status was not 2xx. */
  HTTP_RESPONSE,
  /** Failure inside DivalHR. */
  INTERNAL_FAILURE
}
