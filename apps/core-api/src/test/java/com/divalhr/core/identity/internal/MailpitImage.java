package com.divalhr.core.identity.internal;

/** The development mail capture image, pinned like infrastructure/docker/compose.yaml. */
final class MailpitImage {

  /** Pinned Mailpit image (loopback-bound in compose; never relays mail). */
  static final String PINNED =
      "axllent/mailpit:v1.29.6@sha256:0b5c5f7ffd3c93474baa7fd3869c1462e5a3d03256ed0933dfc0e7d81d794036";

  private MailpitImage() {}
}
