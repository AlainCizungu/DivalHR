package com.divalhr.core.platform.ratelimit;

import com.divalhr.core.platform.error.ErrorCode;
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
 * In-process fixed-window limiter per verified subject (MVP-012B, R2 and B5). Buckets are keyed by
 * an HMAC pseudonym of the subject under a random key that changes with every window, so no raw
 * subject is kept in memory maps, logs or metrics. When the tracking table is full, new subjects
 * are refused (fail closed) until the window ends.
 */
@Component
public class SubjectRateLimiter {

  private static final String ALGORITHM = "HmacSHA256";
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final ReviewRateLimitProperties properties;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Object lock = new Object();
  private final Map<String, Integer> counts = new HashMap<>();
  private long windowIndex = Long.MIN_VALUE;
  private SecretKeySpec windowKey;

  /**
   * Creates the limiter.
   *
   * @param properties limits
   */
  @Autowired
  public SubjectRateLimiter(ReviewRateLimitProperties properties) {
    this(properties, Clock.systemUTC());
  }

  /**
   * Creates the limiter with a clock (tests).
   *
   * @param properties limits
   * @param clock clock
   */
  SubjectRateLimiter(ReviewRateLimitProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Counts one request of the subject in the bucket and rejects it over the limit.
   *
   * @param bucket stable bucket name
   * @param subject verified token subject (never stored)
   * @throws SubjectRateLimitedException {@link ErrorCode#RATE_LIMITED} with the seconds left in the
   *     window, marked when it is the subject's first refusal in that window
   */
  public void acquire(String bucket, String subject) {
    Instant now = Instant.now(clock);
    long index = Math.floorDiv(now.toEpochMilli(), WINDOW.toMillis());
    synchronized (lock) {
      if (index != windowIndex) {
        windowIndex = index;
        windowKey = newKey();
        counts.clear();
      }
      String key = bucket + ":" + pseudonym(subject);
      if (!counts.containsKey(key) && counts.size() >= properties.maxTrackedSubjects()) {
        throw limited(index, now, false);
      }
      int mine = counts.merge(key, 1, Integer::sum);
      if (mine > properties.requestsPerMinute()) {
        throw limited(index, now, mine == properties.requestsPerMinute() + 1);
      }
    }
  }

  private static SubjectRateLimitedException limited(long index, Instant now, boolean first) {
    long windowEnd = (index + 1) * WINDOW.toMillis();
    long seconds = (windowEnd - now.toEpochMilli() + 999) / 1000;
    return new SubjectRateLimitedException(seconds, first);
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
      return HexFormat.of()
          .formatHex(mac.doFinal(String.valueOf(subject).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
