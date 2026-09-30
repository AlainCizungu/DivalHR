package com.divalhr.core.platform.ratelimit;

import com.divalhr.core.platform.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fixed-window, in-process limiter for anonymous endpoints (defense in depth; see {@link
 * RateLimitProperties}). Client addresses are never kept: each is reduced to an HMAC under an
 * ephemeral random key that is replaced at every window boundary, so remembered keys cannot be
 * linked across windows or reversed, and nothing outlives one window. Rejections are counted per
 * bucket only.
 */
@Component
public class PublicRateLimiter {

  /** Metric counting rejected requests, tagged by bucket only. */
  public static final String METRIC = "divalhr.ratelimit.rejections";

  private static final String ALGORITHM = "HmacSHA256";

  private final RateLimitProperties properties;
  private final MeterRegistry registry;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Object lock = new Object();

  private long windowIndex = Long.MIN_VALUE;
  private SecretKeySpec windowKey;
  private final Map<String, Integer> perClient = new ConcurrentHashMap<>();
  private final Map<String, Integer> global = new ConcurrentHashMap<>();

  /**
   * Creates the limiter.
   *
   * @param properties limits
   * @param registry metrics
   */
  @Autowired
  public PublicRateLimiter(RateLimitProperties properties, MeterRegistry registry) {
    this(properties, registry, Clock.systemUTC());
  }

  /**
   * Creates the limiter with a clock (tests).
   *
   * @param properties limits
   * @param registry metrics
   * @param clock clock
   */
  PublicRateLimiter(RateLimitProperties properties, MeterRegistry registry, Clock clock) {
    this.properties = properties;
    this.registry = registry;
    this.clock = clock;
  }

  /**
   * Counts one request and rejects it when a limit is exceeded.
   *
   * @param bucket stable bucket name, e.g. {@code invitation.public}
   * @param clientAddress client address from {@link ClientAddressResolver} (never stored)
   * @throws RateLimitedException {@link ErrorCode#RATE_LIMITED} with the seconds left in the window
   */
  public void acquire(String bucket, String clientAddress) {
    Duration window = properties.window();
    Instant now = Instant.now(clock);
    long index = Math.floorDiv(now.toEpochMilli(), window.toMillis());
    String clientKey;
    synchronized (lock) {
      if (index != windowIndex) {
        windowIndex = index;
        windowKey = newKey();
        perClient.clear();
        global.clear();
      }
      clientKey = bucket + ":" + pseudonym(clientAddress);
      if (!perClient.containsKey(clientKey) && perClient.size() >= properties.maxTrackedClients()) {
        reject(bucket, index, window, now);
      }
      int total = global.merge(bucket, 1, Integer::sum);
      int mine = perClient.merge(clientKey, 1, Integer::sum);
      if (total > properties.globalRequests() || mine > properties.perClientRequests()) {
        reject(bucket, index, window, now);
      }
    }
  }

  private void reject(String bucket, long index, Duration window, Instant now) {
    Counter.builder(METRIC)
        .description("Anonymous requests rejected by the in-process rate limiter")
        .tag("bucket", bucket)
        .register(registry)
        .increment();
    long windowEnd = (index + 1) * window.toMillis();
    long seconds = (windowEnd - now.toEpochMilli() + 999) / 1000;
    throw new RateLimitedException(ErrorCode.RATE_LIMITED, seconds);
  }

  private SecretKeySpec newKey() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return new SecretKeySpec(bytes, ALGORITHM);
  }

  private String pseudonym(String address) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(windowKey);
      return HexFormat.of()
          .formatHex(mac.doFinal(String.valueOf(address).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
