package com.divalhr.core.integrations.messaging.domain;

import java.util.Arrays;
import java.util.Optional;

/** How the recipient's opt-in was obtained (D111-5); the administrator attests, DivalHR records. */
public enum ConsentSource {
  /** A signed or written statement. */
  WRITTEN,
  /** A web form submission. */
  WEB_FORM,
  /** A verbal agreement recorded outside DivalHR. */
  VERBAL_RECORDED_OUTSIDE_DIVALHR,
  /** Another documented source. */
  OTHER_DOCUMENTED;

  /**
   * Parses a code exactly.
   *
   * @param value candidate
   * @return the source, or empty
   */
  public static Optional<ConsentSource> of(Object value) {
    return Arrays.stream(values()).filter(s -> s.name().equals(value)).findFirst();
  }
}
