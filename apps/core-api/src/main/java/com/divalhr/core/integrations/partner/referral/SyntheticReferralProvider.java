package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.PartnerCrypto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The controlled SYNTHETIC-TEST provider (MVP-120F, D120F-7): an in-process receiver that verifies
 * the signature, keeps a bounded record of what it received, and answers according to a mode so
 * every delivery outcome can be exercised. It decides nothing about the employee, prices nothing
 * and moves no money. It has no endpoint and no credential.
 */
@Component
public class SyntheticReferralProvider implements ReferralGateway {

  /** How the synthetic provider answers. */
  public enum Mode {
    /** Accept every correctly signed referral. */
    ACCEPT,
    /** Refuse every referral. */
    REJECT,
    /** Behave as if nothing was sent (safe to retry). */
    NOT_SENT,
    /** Answer with an uncertain result (never resent). */
    UNCERTAIN,
    /** Fail with an exception after receiving (treated as uncertain). */
    FAIL
  }

  /** What the provider received. */
  public record Received(String reference, String payload, boolean signatureValid) {}

  private static final int KEEP = 100;

  private final PartnerCrypto crypto;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private volatile Mode mode;

  /**
   * Creates the provider.
   *
   * @param crypto the keyed digest used to verify signatures
   * @param initial the initial mode
   */
  public SyntheticReferralProvider(
      PartnerCrypto crypto,
      @Value("${divalhr.integrations.partner-referral.synthetic-mode:ACCEPT}") Mode initial) {
    this.crypto = crypto;
    this.mode = initial;
  }

  /**
   * The signature of a delivery.
   *
   * @param crypto keyed digest
   * @param timestamp delivery instant
   * @param payload the signed payload
   * @return 64 lowercase hex characters
   */
  static String sign(PartnerCrypto crypto, java.time.Instant timestamp, String payload) {
    return crypto.digest("partner-referral-delivery", timestamp + "." + payload);
  }

  /**
   * Sets the answer mode (controlled test state).
   *
   * @param next the mode
   */
  public void mode(Mode next) {
    this.mode = next;
  }

  /** Forgets what was received. */
  public void reset() {
    received.clear();
    mode = Mode.ACCEPT;
  }

  /**
   * What was received, oldest first.
   *
   * @return a copy
   */
  public List<Received> received() {
    return new ArrayList<>(received);
  }

  @Override
  public Result send(Delivery delivery) {
    Mode current = mode;
    if (current == Mode.NOT_SENT) {
      return new Result(Kind.NOT_SENT, null);
    }
    boolean valid =
        MessageDigest.isEqual(
            sign(crypto, delivery.timestamp(), delivery.payload())
                .getBytes(StandardCharsets.US_ASCII),
            delivery.signature().getBytes(StandardCharsets.US_ASCII));
    received.add(new Received(delivery.reference(), delivery.payload(), valid));
    while (received.size() > KEEP) {
      received.remove(0);
    }
    return switch (current) {
      case UNCERTAIN -> new Result(Kind.UNCERTAIN, null);
      case FAIL -> throw new IllegalStateException("synthetic provider failure");
      case REJECT -> new Result(Kind.REJECTED, null);
      default ->
          valid
              ? new Result(
                  Kind.ACCEPTED,
                  "SYN-"
                      + crypto
                          .digest("partner-referral-receipt", delivery.reference())
                          .substring(0, 16))
              : new Result(Kind.REJECTED, null);
    };
  }
}
