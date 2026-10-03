package com.divalhr.core.platform.audit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Flood bound of the denial audit (MVP-013, D6 and A13-2): per fixed one-minute window, at most
 * {@code perActorPerMinute} rows per verified subject and {@code perInstancePerMinute} rows per
 * instance. It is checked before any denial-audit connection is requested, so a suppressed denial
 * never touches the database.
 *
 * <p>Subjects are tracked under an HMAC pseudonym with a random key that changes every window, so
 * no raw subject is held and nothing carries over between windows: suppressed attempts are
 * telemetry only and are never presented as durable counts. When the tracking table is full, new
 * subjects are suppressed (no write); access is never affected.
 */
@Component
public class DenialAuditBudget {

  private static final String ALGORITHM = "HmacSHA256";
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final DenialAuditProperties properties;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Object lock = new Object();
  private final Map<String, Integer> perActor = new HashMap<>();
  private long windowIndex = Long.MIN_VALUE;
  private int instanceCount;
  private SecretKeySpec windowKey;

  /**
   * Creates the budget.
   *
   * @param properties bounds
   */
  @Autowired
  public DenialAuditBudget(DenialAuditProperties properties) {
    this(properties, Clock.systemUTC());
  }

  /**
   * Creates the budget with a clock (tests).
   *
   * @param properties bounds
   * @param clock clock
   */
  DenialAuditBudget(DenialAuditProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Takes one write from the budget.
   *
   * @param subject verified subject (never stored)
   * @return true when a row may be written; false when this denial is suppressed
   */
  public boolean tryAcquire(String subject) {
    long index = Math.floorDiv(Instant.now(clock).toEpochMilli(), WINDOW.toMillis());
    synchronized (lock) {
      if (index != windowIndex) {
        windowIndex = index;
        windowKey = newKey();
        perActor.clear();
        instanceCount = 0;
      }
      if (instanceCount >= properties.perInstancePerMinute()) {
        return false;
      }
      String key = pseudonym(subject);
      Integer mine = perActor.get(key);
      if (mine == null && perActor.size() >= properties.maxTrackedActors()) {
        return false;
      }
      int next = mine == null ? 1 : mine + 1;
      if (next > properties.perActorPerMinute()) {
        return false;
      }
      perActor.put(key, next);
      instanceCount++;
      return true;
    }
  }

  private SecretKeySpec newKey() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return new SecretKeySpec(bytes, ALGORITHM);
  }

  private String pseudonym(String subject) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(windowKey);
      return HexFormat.of().formatHex(mac.doFinal(subject.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
