package com.divalhr.core.platform.security;

import java.io.Serial;
import org.springframework.security.access.AccessDeniedException;

/**
 * A privileged operation was called with a verified token that does not prove multifactor
 * authentication. Answered as {@code 403 MFA_REQUIRED} with an RFC 9470 step-up challenge.
 */
public class MfaRequiredException extends AccessDeniedException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public MfaRequiredException() {
    super("multifactor authentication required");
  }
}
