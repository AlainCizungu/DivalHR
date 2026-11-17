package com.divalhr.core.payroll.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signing of the controlled partner handoff (ADR 0042 §5, §7). Delivery, status inquiry
 * and callback each use a distinct domain prefix, so a signature for one purpose is unusable for
 * another, and the delivery and callback keys are separate secrets held by separate instances. The
 * key is never stored, logged or returned.
 */
public final class SalaryAdvanceHandoffSigner {

  /** Minimum key length in bytes. */
  public static final int MIN_KEY_BYTES = 32;

  private final byte[] key;

  /**
   * Creates the signer.
   *
   * @param keyText the key text (at least 32 bytes in UTF-8)
   */
  public SalaryAdvanceHandoffSigner(String keyText) {
    byte[] bytes = keyText == null ? new byte[0] : keyText.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_KEY_BYTES) {
      throw new IllegalArgumentException("the partner signing key must be at least 32 bytes");
    }
    this.key = bytes;
  }

  /**
   * Signs a delivery: over {@code "v1\n" + timestamp + "\n" + applicationId + "\n" +
   * canonicalBytes}.
   *
   * @param timestamp epoch seconds as sent in the header
   * @param applicationId the opaque application id
   * @param body the canonical bytes
   * @return {@code v1=<64 hex characters>}
   */
  public String signDelivery(long timestamp, UUID applicationId, byte[] body) {
    return mac("v1", timestamp, applicationId, body);
  }

  /**
   * Signs a status inquiry: over {@code "v1-inquiry\n" + timestamp + "\n" + applicationId + "\n" +
   * bytes}.
   *
   * @param timestamp epoch seconds
   * @param applicationId the opaque application id
   * @param body the inquiry bytes, or the signed answer bytes
   * @return {@code v1=<64 hex characters>}
   */
  public String signInquiry(long timestamp, UUID applicationId, byte[] body) {
    return mac("v1-inquiry", timestamp, applicationId, body);
  }

  /**
   * Signs a callback: over {@code "v1\n" + timestamp + "\n" + callbackId + "\n" + canonicalBytes}.
   * Use an instance built with the callback key.
   *
   * @param timestamp epoch seconds
   * @param callbackId the replay id
   * @param body the canonical callback bytes
   * @return {@code v1=<64 hex characters>}
   */
  public String signCallback(long timestamp, UUID callbackId, byte[] body) {
    return mac("v1", timestamp, callbackId, body);
  }

  /**
   * Signs the receiver's answer to a delivery: over {@code "v1-ack\n" + applicationId + "\n" +
   * bytes}. Only an answer authenticated with the delivery key decides anything.
   *
   * @param applicationId the opaque application id
   * @param body the answer bytes
   * @return {@code v1=<64 hex characters>}
   */
  public String signAck(UUID applicationId, byte[] body) {
    return macPlain("v1-ack", applicationId, body);
  }

  /**
   * Signs the receiver's answer to a status inquiry: over {@code "v1-inquiry-answer\n" +
   * applicationId + "\n" + bytes}.
   *
   * @param applicationId the opaque application id
   * @param body the answer bytes
   * @return {@code v1=<64 hex characters>}
   */
  public String signInquiryAnswer(UUID applicationId, byte[] body) {
    return macPlain("v1-inquiry-answer", applicationId, body);
  }

  /**
   * Constant-time comparison of a presented signature against the expected one.
   *
   * @param expected the computed signature
   * @param presented the presented signature header value
   * @return {@code true} when equal
   */
  public static boolean matches(String expected, String presented) {
    if (presented == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        presented.getBytes(StandardCharsets.US_ASCII));
  }

  private String macPlain(String domain, UUID id, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      mac.update((domain + "\n" + id + "\n").getBytes(StandardCharsets.US_ASCII));
      mac.update(body);
      return "v1=" + HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("the partner signature could not be computed");
    }
  }

  private String mac(String domain, long timestamp, UUID id, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      mac.update(
          (domain + "\n" + timestamp + "\n" + id + "\n").getBytes(StandardCharsets.US_ASCII));
      mac.update(body);
      return "v1=" + HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("the partner signature could not be computed");
    }
  }

  @Override
  public String toString() {
    return "SalaryAdvanceHandoffSigner[redacted]";
  }
}
