package com.divalhr.core.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * MVP-095A (Issue #183, ADR 0036, D95A-13, R184-2): the fixed windows of the payment buckets,
 * deterministically. The limiters read the system clock inside the application, so a window cannot
 * be moved from an integration test; here each limiter takes a stepped clock instead. The limits
 * are the defaults written in {@code application.yaml} (so a changed default fails here), each of
 * the nine subject buckets (one minute) and eight tenant buckets (one hour) answers exactly its limit
 * and then a refusal whose Retry-After reaches the end of the window, the next window starts a
 * fresh count, and another subject or tenant is not affected. No sleep, no retry.
 */
class PaymentRateLimitWindowTest {

  private static final List<String> SUBJECT_BUCKETS =
      List.of(
          "payment-read",
          "payment-destination-preview",
          "payment-destination-write",
          "payment-batch-preview",
          "payment-batch-write",
          "payment-register-download",
          // MVP-095B (ADR 0037)
          "payment-submission-preview",
          "payment-submission-write",
          "payment-submission-read");
  private static final List<String> TENANT_BUCKETS =
      List.of(
          "payment-destination-preview",
          "payment-destination-write",
          "payment-batch-preview",
          "payment-batch-write",
          "payment-register-download",
          "payment-submission-preview",
          "payment-submission-write",
          "payment-submission-read");

  /** A clock that the test moves. */
  private static final class Stepped extends Clock {
    private Instant now;

    Stepped(String instant) {
      this.now = Instant.parse(instant);
    }

    void to(String instant) {
      this.now = Instant.parse(instant);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static Properties yaml() {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource("application.yaml"));
    return Objects.requireNonNull(factory.getObject());
  }

  /** The default of a {@code ${ENV:default}} value. */
  private static String defaultOf(Properties properties, String key) {
    String value = properties.getProperty(key);
    assertThat(value).as(key).isNotNull();
    Matcher matcher = Pattern.compile(":([0-9a-z]+)}$").matcher(value);
    assertThat(matcher.find()).as(key + " = " + value).isTrue();
    return matcher.group(1);
  }

  @Test
  void everySubjectBucketAnswersItsConfiguredLimitThenRefusesUntilTheNextMinute() {
    Properties yaml = yaml();
    Map<String, Integer> limits = new LinkedHashMap<>();
    for (String bucket : SUBJECT_BUCKETS) {
      limits.put(
          bucket, Integer.parseInt(defaultOf(yaml, "divalhr.request-limits.subject." + bucket)));
    }
    assertThat(limits)
        .containsExactly(
            Map.entry("payment-read", 60),
            Map.entry("payment-destination-preview", 10),
            Map.entry("payment-destination-write", 5),
            Map.entry("payment-batch-preview", 5),
            Map.entry("payment-batch-write", 2),
            Map.entry("payment-register-download", 30),
            Map.entry("payment-submission-preview", 5),
            Map.entry("payment-submission-write", 2),
            Map.entry("payment-submission-read", 60));
    Stepped clock = new Stepped("2026-10-01T10:00:15Z");
    SubjectRateLimiter limiter =
        new SubjectRateLimiter(
            new ReviewRateLimitProperties(30, 100),
            new RequestLimitProperties(limits, null, null),
            clock);
    for (Map.Entry<String, Integer> bucket : limits.entrySet()) {
      for (int i = 0; i < bucket.getValue(); i++) {
        limiter.acquire(bucket.getKey(), "subject-a");
      }
      assertThatThrownBy(() -> limiter.acquire(bucket.getKey(), "subject-a"))
          .as(bucket.getKey())
          .isInstanceOfSatisfying(
              SubjectRateLimitedException.class,
              e -> assertThat(e.retryAfterSeconds()).isEqualTo(45));
      // Another subject has its own count.
      limiter.acquire(bucket.getKey(), "subject-b");
    }
    // The next minute starts every count again.
    clock.to("2026-10-01T10:01:00Z");
    for (Map.Entry<String, Integer> bucket : limits.entrySet()) {
      for (int i = 0; i < bucket.getValue(); i++) {
        limiter.acquire(bucket.getKey(), "subject-a");
      }
      assertThatThrownBy(() -> limiter.acquire(bucket.getKey(), "subject-a"))
          .as(bucket.getKey() + " again")
          .isInstanceOf(SubjectRateLimitedException.class);
    }
  }

  @Test
  void everyTenantBucketAnswersItsConfiguredLimitThenRefusesUntilTheNextHour() {
    Properties yaml = yaml();
    Map<String, RequestLimitProperties.TenantBucket> buckets = new LinkedHashMap<>();
    for (String bucket : TENANT_BUCKETS) {
      String base = "divalhr.request-limits.tenant." + bucket;
      assertThat(defaultOf(yaml, base + ".requests")).isNotEmpty();
      assertThat(yaml.getProperty(base + ".window")).as(base).isEqualTo("1h");
      buckets.put(
          bucket,
          new RequestLimitProperties.TenantBucket(
              Integer.parseInt(defaultOf(yaml, base + ".requests")), Duration.ofHours(1)));
    }
    assertThat(
            buckets.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().requests()))
                .toList())
        .containsExactly(
            Map.entry("payment-destination-preview", 120),
            Map.entry("payment-destination-write", 60),
            Map.entry("payment-batch-preview", 60),
            Map.entry("payment-batch-write", 20),
            Map.entry("payment-register-download", 300),
            Map.entry("payment-submission-preview", 60),
            Map.entry("payment-submission-write", 20),
            Map.entry("payment-submission-read", 300));
    Stepped clock = new Stepped("2026-10-01T10:07:30Z");
    TenantRateLimiter limiter =
        new TenantRateLimiter(new RequestLimitProperties(Map.of(), buckets, null), clock);
    TenantId tenant = new TenantId(UUID.randomUUID());
    TenantId other = new TenantId(UUID.randomUUID());
    for (Map.Entry<String, RequestLimitProperties.TenantBucket> bucket : buckets.entrySet()) {
      for (int i = 0; i < bucket.getValue().requests(); i++) {
        limiter.acquire(bucket.getKey(), tenant);
      }
      assertThatThrownBy(() -> limiter.acquire(bucket.getKey(), tenant))
          .as(bucket.getKey())
          .isInstanceOfSatisfying(
              TenantRateLimitedException.class,
              e -> {
                assertThat(e.firstInWindow()).isTrue();
                assertThat(e.retryAfterSeconds()).isEqualTo(3150);
              });
      // Another tenant has its own count.
      limiter.acquire(bucket.getKey(), other);
    }
    // The next hour starts every count again.
    clock.to("2026-10-01T11:00:00Z");
    for (Map.Entry<String, RequestLimitProperties.TenantBucket> bucket : buckets.entrySet()) {
      for (int i = 0; i < bucket.getValue().requests(); i++) {
        limiter.acquire(bucket.getKey(), tenant);
      }
      assertThatThrownBy(() -> limiter.acquire(bucket.getKey(), tenant))
          .as(bucket.getKey() + " again")
          .isInstanceOf(TenantRateLimitedException.class);
    }
  }
}
