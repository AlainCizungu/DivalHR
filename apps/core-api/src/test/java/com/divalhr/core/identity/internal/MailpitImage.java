package com.divalhr.core.identity.internal;

/** The development mail capture image, pinned like infrastructure/docker/compose.yaml. */
final class MailpitImage {

  /** Pinned Mailpit image (loopback-bound in compose; never relays mail). */
  static final String PINNED = "axllent/mailpit:v1.29.6";

  private MailpitImage() {}
}
