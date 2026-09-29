package com.divalhr.core.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers for request fingerprints and audit integrity references. */
public final class Fingerprints {

  private Fingerprints() {}

  /**
   * Returns the lowercase hex SHA-256 of UTF-8 text.
   *
   * @param canonical canonical text (for example JSON with sorted keys)
   * @return 64-character hex digest
   */
  public static String sha256(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
