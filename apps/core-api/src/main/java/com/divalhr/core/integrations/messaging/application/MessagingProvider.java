package com.divalhr.core.integrations.messaging.application;

import com.divalhr.core.integrations.messaging.domain.MessagingLanguage;
import com.divalhr.core.integrations.messaging.domain.PhoneNumber;
import com.divalhr.core.integrations.messaging.domain.SendFailure;
import java.time.Duration;

/**
 * The WhatsApp Business Cloud API as the application sees it (D111-1, D111-7, D111-8). The only
 * implementation talks to the operator-configured Graph origin; no method follows a redirect,
 * inherits a proxy, stores a response body or header, or throws for a network problem.
 */
public interface MessagingProvider {

  /** The answer to a credential check. */
  enum CredentialResult {
    /** The token works for the phone-number id. */
    VALID,
    /** The provider refused the token or the identifiers. */
    REJECTED,
    /** The provider could not be reached or answered unexpectedly. */
    UNAVAILABLE
  }

  /** The answer to a template check. */
  enum TemplateResult {
    /** Exists, approved, expected language and exact supported shape. */
    OK,
    /** The provider has no such template. */
    MISSING,
    /** The template exists but is not approved. */
    NOT_APPROVED,
    /** The template exists but not in the expected language. */
    WRONG_LANGUAGE,
    /** The template's components are not the supported shape. */
    WRONG_SHAPE,
    /** The provider refused the credential. */
    CREDENTIAL_REJECTED,
    /** The provider could not be reached or answered unexpectedly. */
    UNAVAILABLE
  }

  /**
   * A template check's outcome.
   *
   * @param result the verdict
   * @param bodyParameters 0 or 1 body text parameter when the verdict is {@link TemplateResult#OK}
   */
  record TemplateCheck(TemplateResult result, int bodyParameters) {}

  /**
   * One send request. The token appears only here and is never printed.
   *
   * @param token the plaintext system-user token
   * @param phoneNumberId the sending phone-number id
   * @param to the recipient number
   * @param templateName the Meta template name
   * @param language the template language
   * @param bodyLabel the single generic body label, or null when the template has no body variable
   * @param buttonSuffix the URL-button suffix appended to the template's fixed base
   */
  record SendRequest(
      String token,
      String phoneNumberId,
      PhoneNumber to,
      String templateName,
      MessagingLanguage language,
      String bodyLabel,
      String buttonSuffix) {
    @Override
    public String toString() {
      return "SendRequest[...]";
    }
  }

  /**
   * One send outcome.
   *
   * @param providerMessageId the provider's message id when it accepted the message, else null
   * @param failure the closed class, null on acceptance
   * @param httpStatus the exact status when a response arrived
   * @param retryAfter a bounded provider hint, or null
   * @param latencyMillis wall time
   */
  record SendOutcome(
      String providerMessageId,
      SendFailure failure,
      Integer httpStatus,
      Duration retryAfter,
      long latencyMillis) {
    /**
     * @return whether the provider accepted the message
     */
    public boolean accepted() {
      return failure == null && providerMessageId != null;
    }
  }

  /**
   * Checks the token against the phone-number id.
   *
   * @param token plaintext token
   * @param phoneNumberId sending phone-number id
   * @return the verdict
   */
  CredentialResult checkCredential(String token, String phoneNumberId);

  /**
   * Checks one template.
   *
   * @param token plaintext token
   * @param wabaId the WhatsApp Business Account id
   * @param templateName the template name
   * @param language the expected language
   * @param expectedButtonUrl the URL the template's single URL button must declare
   * @return the verdict
   */
  TemplateCheck checkTemplate(
      String token,
      String wabaId,
      String templateName,
      MessagingLanguage language,
      String expectedButtonUrl);

  /**
   * Sends one template message.
   *
   * @param request the request
   * @return the outcome
   */
  SendOutcome send(SendRequest request);
}
