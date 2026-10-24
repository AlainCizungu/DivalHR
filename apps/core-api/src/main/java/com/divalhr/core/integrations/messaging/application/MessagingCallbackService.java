package com.divalhr.core.integrations.messaging.application;

import com.divalhr.core.integrations.messaging.domain.MetaSignature;
import com.divalhr.core.integrations.messaging.domain.SendFailure;
import com.divalhr.core.integrations.messaging.internal.GraphApiProvider;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository.CallbackEffect;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The provider callback boundary (D111-10): the only unauthenticated door of the WhatsApp
 * connector, and it authenticates the provider rather than a user. The signature over the raw bytes
 * is verified in constant time before anything is parsed; the body is bounded; only allow-listed
 * fields are read (a provider message id, the phone-number id, a delivery status, a time and a
 * numeric error code); a message is matched by provider message id plus the stored phone-number
 * binding of its connector; unknown, duplicate and out-of-order statuses are acknowledged without
 * effect; inbound customer messages are ignored without being read or stored. Nothing of the
 * request is logged beyond counts.
 */
@Service
public class MessagingCallbackService {

  /** How a callback was answered. */
  public enum Outcome {
    /** Verified and processed (also for unknown, duplicate and ignored content). */
    ACCEPTED,
    /** The signature is missing or wrong. */
    FORBIDDEN,
    /** The capability is not configured. */
    UNAVAILABLE,
    /** The provider-wide ceiling was reached. */
    RATE_LIMITED
  }

  private static final Logger LOG = LoggerFactory.getLogger(MessagingCallbackService.class);
  private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9._~-]{1,256}$");
  private static final Pattern PROVIDER_ID = Pattern.compile("^[A-Za-z0-9=_.:+/-]{8,256}$");
  private static final Pattern DIGITS = Pattern.compile("^[0-9]{5,20}$");
  private static final int MAX_ENTRIES = 20;
  private static final int MAX_STATUSES = 200;
  private static final Duration MAX_FUTURE = Duration.ofMinutes(5);
  private static final Duration MAX_AGE = Duration.ofDays(30);

  private final JdbcMessagingRepository repository;
  private final MessagingProperties properties;
  private final JsonMapper json;
  private final Clock clock;
  private final PlatformTransactionManager transactions;
  private final Object windowLock = new Object();
  private long windowStart;
  private long windowCount;

  /**
   * Creates the service.
   *
   * @param repository storage
   * @param properties settings
   * @param json JSON mapper
   * @param clock the platform clock
   * @param transactions transaction manager
   */
  public MessagingCallbackService(
      JdbcMessagingRepository repository,
      MessagingProperties properties,
      JsonMapper json,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.repository = repository;
    this.properties = properties;
    this.json = json;
    this.clock = clock;
    this.transactions = transactions;
  }

  /**
   * Answers Meta's subscription verification handshake.
   *
   * @param mode {@code hub.mode}
   * @param token {@code hub.verify_token}
   * @param challenge {@code hub.challenge}
   * @return the challenge to echo, or empty to refuse
   */
  public Optional<String> verify(String mode, String token, String challenge) {
    if (!properties.callbackConfigured()
        || !"subscribe".equals(mode)
        || token == null
        || challenge == null
        || !CHALLENGE.matcher(challenge).matches()) {
      return Optional.empty();
    }
    boolean equal =
        MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8),
            properties.verifyToken().getBytes(StandardCharsets.UTF_8));
    return equal ? Optional.of(challenge) : Optional.empty();
  }

  /**
   * Handles one signed delivery-status callback.
   *
   * @param raw the exact request bytes
   * @param signature the {@code X-Hub-Signature-256} header, or null
   * @return the outcome
   */
  public Outcome receive(byte[] raw, String signature) {
    if (!properties.callbackConfigured()) {
      return Outcome.UNAVAILABLE;
    }
    if (!MetaSignature.verify(properties.appSecret(), raw, signature)) {
      return Outcome.FORBIDDEN;
    }
    if (!withinCeiling()) {
      return Outcome.RATE_LIMITED;
    }
    int applied = 0;
    int ignored = 0;
    try {
      List<Status> statuses = parse(raw);
      Instant now = Instant.now(clock);
      int[] counts =
          inTransaction(
              () -> {
                int advanced = 0;
                int other = 0;
                for (Status status : statuses) {
                  CallbackEffect effect =
                      repository.applyCallback(
                          status.providerMessageId(),
                          status.phoneNumberId(),
                          status.status(),
                          status.occurredAt(now),
                          status.error(),
                          now);
                  if (effect == CallbackEffect.ADVANCED || effect == CallbackEffect.RECORDED) {
                    advanced++;
                  } else {
                    other++;
                  }
                }
                return new int[] {advanced, other};
              });
      if (counts != null) {
        applied = counts[0];
        ignored = counts[1];
      }
    } catch (RuntimeException failure) {
      // A verified but unusable body is acknowledged so the provider does not retry it forever.
      LOG.atWarn()
          .addKeyValue("operation", "messaging.callback")
          .addKeyValue("outcome", "unprocessable")
          .addKeyValue("error", failure.getClass().getSimpleName())
          .log("messaging_callback_unprocessable");
      return Outcome.ACCEPTED;
    }
    LOG.atInfo()
        .addKeyValue("operation", "messaging.callback")
        .addKeyValue("outcome", "accepted")
        .addKeyValue("applied", applied)
        .addKeyValue("ignored", ignored)
        .log("messaging_callback");
    return Outcome.ACCEPTED;
  }

  /** One allow-listed status. */
  private record Status(
      String providerMessageId,
      String phoneNumberId,
      String status,
      Instant provided,
      SendFailure error) {
    Instant occurredAt(Instant now) {
      if (provided == null
          || provided.isAfter(now.plus(MAX_FUTURE))
          || provided.isBefore(now.minus(MAX_AGE))) {
        return now;
      }
      return provided;
    }
  }

  private List<Status> parse(byte[] raw) {
    Object root = json.readValue(raw, Object.class);
    List<Status> found = new java.util.ArrayList<>();
    if (!(root instanceof Map<?, ?> top) || !(top.get("entry") instanceof List<?> entries)) {
      return found;
    }
    int entryCount = 0;
    for (Object entry : entries) {
      if (++entryCount > MAX_ENTRIES) {
        break;
      }
      if (!(entry instanceof Map<?, ?> e) || !(e.get("changes") instanceof List<?> changes)) {
        continue;
      }
      for (Object change : changes) {
        if (!(change instanceof Map<?, ?> c) || !(c.get("value") instanceof Map<?, ?> value)) {
          continue;
        }
        String phone = phoneNumberId(value);
        if (phone == null || !(value.get("statuses") instanceof List<?> statuses)) {
          continue; // Inbound messages and everything else: ignored, never read.
        }
        for (Object item : statuses) {
          if (found.size() >= MAX_STATUSES) {
            return found;
          }
          status(item, phone).ifPresent(found::add);
        }
      }
    }
    return found;
  }

  private static String phoneNumberId(Map<?, ?> value) {
    if (value.get("metadata") instanceof Map<?, ?> metadata
        && metadata.get("phone_number_id") instanceof String id
        && DIGITS.matcher(id).matches()) {
      return id;
    }
    return null;
  }

  private static Optional<Status> status(Object item, String phone) {
    if (!(item instanceof Map<?, ?> s)
        || !(s.get("id") instanceof String id)
        || !PROVIDER_ID.matcher(id).matches()
        || !(s.get("status") instanceof String raw)) {
      return Optional.empty();
    }
    String status = raw.toUpperCase(Locale.ROOT);
    if (!List.of("SENT", "DELIVERED", "READ", "FAILED").contains(status)) {
      return Optional.empty();
    }
    Instant provided = null;
    if (s.get("timestamp") instanceof String text && text.matches("^[0-9]{1,12}$")) {
      provided = Instant.ofEpochSecond(Long.parseLong(text));
    } else if (s.get("timestamp") instanceof Number number) {
      provided = Instant.ofEpochSecond(number.longValue());
    }
    SendFailure error = null;
    if ("FAILED".equals(status)) {
      Integer code = null;
      if (s.get("errors") instanceof List<?> errors
          && !errors.isEmpty()
          && errors.get(0) instanceof Map<?, ?> first
          && first.get("code") instanceof Number number) {
        code = number.intValue();
      }
      error = GraphApiProvider.classify(200, code);
      // The callback evidence stores a closed subset; any other class is a generic provider
      // failure.
      if (error != SendFailure.RECIPIENT_REJECTED
          && error != SendFailure.TEMPLATE_INVALID
          && error != SendFailure.CREDENTIAL_INVALID) {
        error = SendFailure.PROVIDER_FAILED;
      }
    }
    return Optional.of(new Status(id, phone, status, provided, error));
  }

  private boolean withinCeiling() {
    long minute = Instant.now(clock).getEpochSecond() / 60;
    synchronized (windowLock) {
      if (windowStart != minute) {
        windowStart = minute;
        windowCount = 0;
      }
      windowCount++;
      return windowCount <= properties.callbackRequestsPerMinute();
    }
  }

  private <T> T inTransaction(java.util.function.Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setTimeout(30);
    return template.execute(status -> work.get());
  }
}
