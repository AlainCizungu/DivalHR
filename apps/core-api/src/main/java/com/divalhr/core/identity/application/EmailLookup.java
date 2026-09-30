package com.divalhr.core.identity.application;

import com.divalhr.core.identity.domain.EmailAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keyed lookup value for invitee addresses: {@code HMAC-SHA256(key, normalized address)}. Used for
 * case-insensitive uniqueness in the invitation and membership tables without storing the address
 * in the membership table, and without a plain hash that a dictionary could reverse. The key is
 * required (at least 32 bytes) and outside development a published {@code dev-only-} value is
 * refused at start-up. Rotating it requires recomputing stored lookups.
 */
@Component
public final class EmailLookup {

  /** Minimum key length in bytes. */
  public static final int MIN_BYTES = 32;

  private static final String ALGORITHM = "HmacSHA256";
  private static final Set<String> PLACEHOLDER_ALLOWED = Set.of("development");

  private final SecretKeySpec key;

  /**
   * Creates the lookup.
   *
   * @param properties invitation settings (the key)
   * @param environment deployment environment
   */
  public EmailLookup(
      InvitationProperties properties, @Value("${divalhr.environment}") String environment) {
    this.key = new SecretKeySpec(validate(environment, properties.emailLookupKey()), ALGORITHM);
  }

  /**
   * Validates the key and returns its bytes; messages never contain the key.
   *
   * @param environment deployment environment
   * @param value configured key
   * @return key bytes
   */
  static byte[] validate(String environment, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("divalhr.invitations.email-lookup-key is required");
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_BYTES) {
      throw new IllegalStateException(
          "divalhr.invitations.email-lookup-key must be at least " + MIN_BYTES + " bytes");
    }
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    if (!PLACEHOLDER_ALLOWED.contains(env)
        && value.toLowerCase(Locale.ROOT).startsWith("dev-only-")) {
      throw new IllegalStateException(
          "The development email lookup key must not be used in environment " + env);
    }
    return bytes;
  }

  /**
   * Computes the lookup value.
   *
   * @param email normalized address
   * @return 32 bytes
   */
  public byte[] of(EmailAddress email) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(email.value().getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
