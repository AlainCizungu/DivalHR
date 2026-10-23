package com.divalhr.core.integrations.application;

import com.divalhr.core.integrations.domain.FailureClass;
import com.divalhr.core.integrations.domain.WebhookUrl;
import java.util.Map;

/** Sends one delivery attempt (D110-6, D110-8). Implementations never follow redirects. */
public interface DeliveryTransport {

  /**
   * The result of one attempt.
   *
   * @param httpStatus the exact HTTP status when a response arrived, else null
   * @param failure null on success (any 2xx), else the closed failure class
   * @param latencyMillis wall time of the attempt
   */
  record Outcome(Integer httpStatus, FailureClass failure, long latencyMillis) {
    /**
     * @return whether the attempt succeeded
     */
    public boolean succeeded() {
      return failure == null;
    }
  }

  /**
   * Performs one POST.
   *
   * @param url validated endpoint
   * @param headers request headers (the transport adds Host, Content-Length and Connection)
   * @param body exact body bytes
   * @return the outcome; never throws for network problems
   */
  Outcome post(WebhookUrl url, Map<String, String> headers, byte[] body);
}
