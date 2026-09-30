package com.divalhr.core.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * A single-use invitation token: 32 random bytes (256 bits) from {@link SecureRandom}, encoded as
 * base64url without padding (exactly 43 characters). Only its SHA-256 is stored. At 256 bits a
 * keyed or slow hash adds nothing against a database-only compromise. The secret is never logged,
 * audited, returned by the API or written to the outbox; {@link #toString()} hides it.
 *
 * @param secret the encoded token
 */
public record InvitationToken(String secret) {

  /** Encoded length. */
  public static final int LENGTH = 43;

  private static final Pattern SHAPE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * Generates a new token.
   *
   * @return token
   */
  public static InvitationToken generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return new InvitationToken(ENCODER.encodeToString(bytes));
  }

  /**
   * Whether a submitted value has the token's exact shape (checked before any lookup).
   *
   * @param raw submitted value
   * @return true for 43 base64url characters
   */
  public static boolean isWellFormed(String raw) {
    return raw != null && raw.length() == LENGTH && SHAPE.matcher(raw).matches();
  }

  /**
   * SHA-256 of a submitted token.
   *
   * @param raw well-formed token
   * @return 32-byte digest
   */
  public static byte[] hashOf(String raw) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /**
   * SHA-256 of this token.
   *
   * @return 32-byte digest
   */
  public byte[] sha256() {
    return hashOf(secret);
  }

  @Override
  public String toString() {
    return "InvitationToken[<redacted>]";
  }
}
