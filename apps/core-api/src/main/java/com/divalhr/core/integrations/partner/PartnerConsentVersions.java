package com.divalhr.core.integrations.partner;

/**
 * The only privacy-notice and consent identifiers a partner application may record (R216-3). They
 * are stable semantic identifiers for the exact bilingual notice the web form displays, not dates:
 * changing the displayed wording requires a new identifier here, in the contract, in the web
 * constants and in ADR 0043. Any other client-supplied value is refused, never recorded.
 */
public final class PartnerConsentVersions {

  /** The privacy notice shown beside the application form. */
  public static final String PRIVACY_NOTICE = "PARTNER-PRIVACY-1";

  /** The acknowledgment the applicant ticks before submitting. */
  public static final String CONSENT = "PARTNER-APPLICATION-CONSENT-1";

  private PartnerConsentVersions() {}
}
