package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PaymentProviderAddressPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controlled partner handoff settings ({@code divalhr.payroll.partner}, MVP-120B, ADR 0042 §5). The
 * delivery and callback signing keys are two separate secrets with no default, read from the
 * protected secret directory; without the delivery key a handoff cannot be authorized (503) and
 * without the callback key callbacks are refused. The synthetic receiver is the only profile; it
 * names one exact host and port and is refused outside development and test environments. {@code
 * toString} is redacted.
 *
 * @param deliverySigningKey the outbound delivery HMAC key text, or blank when not configured
 * @param callbackSigningKey the inbound callback HMAC key text, or blank when not configured
 * @param keyVersion the version recorded with every handoff
 * @param synthetic the synthetic test receiver
 * @param worker the delivery worker
 */
@ConfigurationProperties("divalhr.payroll.partner")
public record SalaryAdvancePartnerProperties(
    String deliverySigningKey,
    String callbackSigningKey,
    String keyVersion,
    Synthetic synthetic,
    Worker worker) {

  /**
   * The synthetic receiver.
   *
   * @param enabled whether the profile is active
   * @param host exact HTTPS host name
   * @param port exact HTTPS port
   * @param caFile PEM file with the receiver's throwaway CA, trusted in addition to the defaults
   * @param networks internal CIDR networks (RFC 1918 or unique-local only) the receiver address
   *     must lie in
   */
  public record Synthetic(
      Boolean enabled, String host, Integer port, String caFile, List<String> networks) {

    /** Applies defaults and validates the internal networks (private space only). */
    public Synthetic {
      enabled = enabled != null && enabled;
      host = host == null ? "" : host.trim();
      port = port == null ? Integer.valueOf(8444) : port;
      networks =
          networks == null || networks.isEmpty()
              ? List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
              : networks.stream().map(String::trim).filter(n -> !n.isEmpty()).toList();
      PaymentProviderAddressPolicy.validateInternalNetworks(networks);
    }

    /**
     * @return whether the profile is enabled and names a host
     */
    public boolean active() {
      return enabled && !host.isBlank();
    }
  }

  /**
   * The delivery worker.
   *
   * @param enabled whether the scheduler runs
   * @param interval pause between cycles
   * @param lease how long a claim lasts
   * @param claimSize most handoffs claimed per cycle
   * @param connectTimeout TCP connect deadline
   * @param requestTimeout whole-attempt deadline
   */
  public record Worker(
      Boolean enabled,
      Duration interval,
      Duration lease,
      Integer claimSize,
      Duration connectTimeout,
      Duration requestTimeout) {

    /** Applies defaults and bounds. */
    public Worker {
      enabled = enabled == null || enabled;
      interval = interval == null ? Duration.ofSeconds(5) : interval;
      lease = lease == null ? Duration.ofMinutes(2) : lease;
      claimSize = claimSize == null ? 8 : Math.max(1, Math.min(claimSize, 8));
      connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
      requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
    }
  }

  /** Applies defaults. */
  public SalaryAdvancePartnerProperties {
    keyVersion = keyVersion == null || keyVersion.isBlank() ? "1" : keyVersion.trim();
    synthetic = synthetic == null ? new Synthetic(false, null, null, null, null) : synthetic;
    worker = worker == null ? new Worker(null, null, null, null, null, null) : worker;
  }

  /**
   * @return whether a delivery signing key is configured
   */
  public boolean deliveryKeyConfigured() {
    return deliverySigningKey != null && !deliverySigningKey.isBlank();
  }

  /**
   * @return whether a callback signing key is configured
   */
  public boolean callbackKeyConfigured() {
    return callbackSigningKey != null && !callbackSigningKey.isBlank();
  }

  /**
   * Refuses an unsafe configuration at start-up.
   *
   * @param environment the deployment environment
   */
  public void validate(String environment) {
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    if (!keyVersion.matches("^[A-Za-z0-9._-]{1,32}$")) {
      throw new IllegalStateException("divalhr.payroll.partner.key-version is not valid");
    }
    requireStrong("delivery-signing-key", deliverySigningKey, env);
    requireStrong("callback-signing-key", callbackSigningKey, env);
    if (deliveryKeyConfigured()
        && callbackKeyConfigured()
        && deliverySigningKey.equals(callbackSigningKey)) {
      throw new IllegalStateException(
          "the partner delivery and callback signing keys must be two different secrets");
    }
    if (synthetic.active() && !"development".equals(env) && !"test".equals(env)) {
      throw new IllegalStateException("the synthetic partner is refused in environment " + env);
    }
  }

  private static void requireStrong(String name, String key, String env) {
    if (key == null || key.isBlank()) {
      return;
    }
    if (key.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(
          "divalhr.payroll.partner." + name + " must be at least 32 bytes");
    }
    if (!"development".equals(env) && key.toLowerCase(Locale.ROOT).startsWith("dev-only-")) {
      throw new IllegalStateException(
          "the development partner signing key must not be used in environment " + env);
    }
  }

  @Override
  public String toString() {
    return "SalaryAdvancePartnerProperties[redacted]";
  }
}
