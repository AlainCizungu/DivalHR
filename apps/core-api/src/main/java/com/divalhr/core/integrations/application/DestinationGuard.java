package com.divalhr.core.integrations.application;

import com.divalhr.core.integrations.domain.AddressPolicy;
import com.divalhr.core.integrations.domain.FailureClass;
import com.divalhr.core.integrations.domain.WebhookUrl;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Resolves a webhook host and refuses it unless every answer is a public address (D110-8). Used at
 * create and update, for test deliveries and for every real attempt. The only exception is the
 * configured synthetic receiver: one exact host and port, development and test only.
 */
@Component
public final class DestinationGuard {

  /** Refusal carrying only the closed failure class. */
  public static final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient FailureClass failure;

    Refused(FailureClass failure) {
      super(failure.name(), null, false, false);
      this.failure = failure;
    }

    /**
     * @return the closed class (DNS_BLOCKED or DNS_FAILED)
     */
    public FailureClass failure() {
      return failure;
    }
  }

  private final DnsResolver dns;
  private final IntegrationProperties.TestReceiver receiver;

  /**
   * Creates the guard.
   *
   * @param dns resolver
   * @param properties settings
   */
  public DestinationGuard(DnsResolver dns, IntegrationProperties properties) {
    this.dns = dns;
    this.receiver = properties.testReceiver();
  }

  /**
   * Resolves again and returns the validated addresses.
   *
   * @param url the endpoint
   * @return the addresses that may be contacted
   * @throws Refused when the name does not resolve or any answer is forbidden
   */
  public List<InetAddress> validated(WebhookUrl url) {
    InetAddress[] answers;
    try {
      answers = dns.resolve(url.host());
    } catch (UnknownHostException unknown) {
      throw new Refused(FailureClass.DNS_FAILED);
    }
    if (answers == null || answers.length == 0) {
      throw new Refused(FailureClass.DNS_FAILED);
    }
    if (answers.length > DnsResolver.MAX_ANSWERS) {
      throw new Refused(FailureClass.DNS_BLOCKED);
    }
    boolean exempt =
        receiver.configured()
            && receiver.host().equals(url.host())
            && receiver.port() == url.port();
    List<InetAddress> accepted = new ArrayList<>();
    for (InetAddress address : answers) {
      if (!exempt && !AddressPolicy.isPublic(address)) {
        throw new Refused(FailureClass.DNS_BLOCKED);
      }
      accepted.add(address);
    }
    return accepted;
  }
}
