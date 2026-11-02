package com.divalhr.core.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-095B (ADR 0037) pure rules: canonical payload, signature, retry schedule, state machine and
 * outcome classes. Synthetic values only.
 */
class PaymentSubmissionDomainTest {

  private static final UUID REQUEST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String DIGEST = "a".repeat(64);
  private static final String KEY = "test-only-payment-submission-signing-key-001";

  private static PaymentSubmissionPayload.Line bank(long amount, String ref) {
    return new PaymentSubmissionPayload.Line(
        UUID.fromString(ref),
        amount,
        PaymentMethod.BANK_ACCOUNT,
        "RAWBANK",
        "CD",
        "SYNTHACCT0099887766",
        "KIN-01",
        null,
        "Sentinel Holder Zed");
  }

  private static PaymentSubmissionPayload.Line mobile(long amount, String ref) {
    return new PaymentSubmissionPayload.Line(
        UUID.fromString(ref),
        amount,
        PaymentMethod.MOBILE_MONEY,
        "MPESA",
        "CD",
        null,
        null,
        "+243990001122",
        "Sentinel Holder Zed");
  }

  private static final String L1 = "00000000-0000-4000-8000-000000000001";
  private static final String L2 = "00000000-0000-4000-8000-000000000002";

  private static byte[] payload() {
    return PaymentSubmissionPayload.canonical(
        REQUEST, DIGEST, "CDF", 300_050L, List.of(bank(200_000L, L1), mobile(100_050L, L2)));
  }

  @Test
  void theCanonicalPayloadIsDeterministicSortedAndCarriesExactIntegers() {
    String text = new String(payload(), StandardCharsets.UTF_8);
    assertThat(payload()).isEqualTo(payload());
    assertThat(text).startsWith("{\"batchDigest\":\"" + DIGEST + "\",\"currency\":\"CDF\"");
    assertThat(text)
        .contains("\"amountMinor\":200000")
        .contains("\"amountMinor\":100050")
        .contains("\"lineCount\":2")
        .contains("\"totalMinor\":300050")
        .contains("\"schemaVersion\":1")
        .doesNotContain(": ")
        .doesNotContain(", ")
        .doesNotContain("\n");
    assertThat(text.indexOf("\"account\"")).isLessThan(text.indexOf("\"amountMinor\""));
    assertThat(text.indexOf("\"requestId\"")).isLessThan(text.indexOf("\"schemaVersion\""));
  }

  @Test
  void aTotalThatDoesNotEqualTheLineSumIsRefusedNotRecalculated() {
    assertThatThrownBy(
            () ->
                PaymentSubmissionPayload.canonical(
                    REQUEST,
                    DIGEST,
                    "CDF",
                    300_051L,
                    List.of(bank(200_000L, L1), mobile(100_050L, L2))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> PaymentSubmissionPayload.canonical(REQUEST, DIGEST, "CDF", 0L, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aManualLineIsNeverSubmittable() {
    PaymentSubmissionPayload.Line manual =
        new PaymentSubmissionPayload.Line(
            UUID.fromString(L1), 10L, PaymentMethod.MANUAL, "X", "CD", null, null, null, "H");
    assertThatThrownBy(
            () -> PaymentSubmissionPayload.canonical(REQUEST, DIGEST, "CDF", 10L, List.of(manual)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lineToStringNeverPrintsADestination() {
    assertThat(bank(1, L1).toString()).doesNotContain("SYNTHACCT").doesNotContain("Sentinel");
  }

  @Test
  void theSignatureIsBoundToTheRequestIdAndTheExactBytes() {
    PaymentSubmissionSigner signer = new PaymentSubmissionSigner(KEY);
    byte[] body = payload();
    String signature = signer.sign(REQUEST, body);
    assertThat(signature).matches("v1=[0-9a-f]{64}");
    assertThat(signer.verify(REQUEST, body, signature)).isTrue();
    assertThat(signer.verify(UUID.randomUUID(), body, signature)).isFalse();
    byte[] changed = body.clone();
    changed[changed.length - 2] ^= 1;
    assertThat(signer.verify(REQUEST, changed, signature)).isFalse();
    assertThat(signer.verify(REQUEST, body, null)).isFalse();
    assertThat(new PaymentSubmissionSigner(KEY + "x").verify(REQUEST, body, signature)).isFalse();
    assertThat(signer.toString()).doesNotContain(KEY);
  }

  @Test
  void aShortSigningKeyIsRefused() {
    assertThatThrownBy(() -> new PaymentSubmissionSigner("too-short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PaymentSubmissionSigner(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void retryDelaysFollowTheApprovedScheduleAndStopAfterSixAttempts() {
    assertThat(PaymentSubmissionRetry.MAX_ATTEMPTS).isEqualTo(6);
    assertThat(List.of(1, 2, 3, 4, 5).stream().map(PaymentSubmissionRetry::delayAfter).toList())
        .containsExactly(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(12));
    assertThatThrownBy(() -> PaymentSubmissionRetry.delayAfter(6))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PaymentSubmissionRetry.delayAfter(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyTheLegalTransitionsExistAndFinalStatesNeverMove() {
    PaymentSubmissionState q = PaymentSubmissionState.QUEUED;
    PaymentSubmissionState s = PaymentSubmissionState.SENDING;
    assertThat(q.mayMoveTo(PaymentSubmissionState.SENDING)).isTrue();
    assertThat(q.mayMoveTo(PaymentSubmissionState.ACCEPTED_FOR_PROCESSING)).isFalse();
    assertThat(q.mayMoveTo(PaymentSubmissionState.DELIVERY_UNKNOWN)).isFalse();
    assertThat(s.mayMoveTo(PaymentSubmissionState.QUEUED)).isTrue();
    for (PaymentSubmissionState done :
        List.of(
            PaymentSubmissionState.ACCEPTED_FOR_PROCESSING,
            PaymentSubmissionState.REJECTED,
            PaymentSubmissionState.DELIVERY_UNKNOWN,
            PaymentSubmissionState.MANUAL_INTERVENTION)) {
      assertThat(done.terminal()).isTrue();
      for (PaymentSubmissionState next : PaymentSubmissionState.values()) {
        assertThat(done.mayMoveTo(next)).as(done + " -> " + next).isFalse();
      }
    }
    assertThat(PaymentSubmissionState.parse("PAID")).isEmpty();
    assertThat(PaymentSubmissionState.parse("SETTLED")).isEmpty();
  }

  @Test
  void outcomesAreAClosedAllowListWithoutAnyPaidOrSettledCode() {
    for (PaymentSubmissionOutcome outcome : PaymentSubmissionOutcome.values()) {
      assertThat(outcome.name())
          .doesNotContainIgnoringCase("PAID")
          .doesNotContainIgnoringCase("SETTLE");
      assertThat(PaymentSubmissionOutcome.parse(outcome.name())).contains(outcome);
      String name = outcome.name();
      PaymentSubmissionOutcome.Kind expected =
          name.equals("ACCEPTED")
              ? PaymentSubmissionOutcome.Kind.ACCEPTED
              : name.startsWith("REJECTED_")
                  ? PaymentSubmissionOutcome.Kind.REJECTED
                  : name.startsWith("TRANSIENT_")
                      ? PaymentSubmissionOutcome.Kind.TRANSIENT
                      : name.startsWith("UNKNOWN_")
                          ? PaymentSubmissionOutcome.Kind.UNKNOWN
                          : PaymentSubmissionOutcome.Kind.EXHAUSTED;
      assertThat(outcome.kind()).as(name).isEqualTo(expected);
    }
    assertThat(PaymentSubmissionOutcome.parse("accepted")).isEmpty();
    assertThat(PaymentSubmissionOutcome.ACCEPTED.kind())
        .isEqualTo(PaymentSubmissionOutcome.Kind.ACCEPTED);
    assertThat(PaymentSubmissionOutcome.UNKNOWN_TIMEOUT.kind())
        .isEqualTo(PaymentSubmissionOutcome.Kind.UNKNOWN);
    assertThat(PaymentSubmissionOutcome.REJECTED_OTHER.kind())
        .isEqualTo(PaymentSubmissionOutcome.Kind.REJECTED);
  }

  @Test
  void aProviderProfileNeverSubmitsManualAndRejectsABadCode() {
    assertThatThrownBy(
            () ->
                new PaymentSubmissionProfile(
                    "SYNTHETIC-TEST",
                    "x",
                    "h.test",
                    8443,
                    null,
                    java.util.Set.of("CDF"),
                    java.util.Set.of(PaymentMethod.MANUAL),
                    true,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PaymentSubmissionProfile(
                    "bad code",
                    "x",
                    "h.test",
                    8443,
                    null,
                    java.util.Set.of("CDF"),
                    java.util.Set.of(PaymentMethod.BANK_ACCOUNT),
                    true,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void thePreviewDigestBindsEveryFieldOfTheReview() {
    UUID tenant = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    UUID batch = UUID.randomUUID();
    java.time.Instant expires = java.time.Instant.parse("2026-11-02T10:00:00Z");
    String base =
        PaymentSubmissionDigests.preview(
            tenant, entity, batch, 1, DIGEST, "SYNTHETIC-TEST", 1, 2, "CDF", 300_050L, expires);
    assertThat(base).matches("[0-9a-f]{64}");
    assertThat(
            PaymentSubmissionDigests.preview(
                tenant, entity, batch, 1, DIGEST, "SYNTHETIC-TEST", 1, 2, "CDF", 300_050L, expires))
        .isEqualTo(base);
    assertThat(
            List.of(
                PaymentSubmissionDigests.preview(
                    tenant,
                    entity,
                    batch,
                    2,
                    DIGEST,
                    "SYNTHETIC-TEST",
                    1,
                    2,
                    "CDF",
                    300_050L,
                    expires),
                PaymentSubmissionDigests.preview(
                    tenant,
                    entity,
                    batch,
                    1,
                    "b".repeat(64),
                    "SYNTHETIC-TEST",
                    1,
                    2,
                    "CDF",
                    300_050L,
                    expires),
                PaymentSubmissionDigests.preview(
                    tenant,
                    entity,
                    batch,
                    1,
                    DIGEST,
                    "OTHER-PROVIDER",
                    1,
                    2,
                    "CDF",
                    300_050L,
                    expires),
                PaymentSubmissionDigests.preview(
                    tenant,
                    entity,
                    batch,
                    1,
                    DIGEST,
                    "SYNTHETIC-TEST",
                    2,
                    2,
                    "CDF",
                    300_050L,
                    expires),
                PaymentSubmissionDigests.preview(
                    tenant,
                    entity,
                    batch,
                    1,
                    DIGEST,
                    "SYNTHETIC-TEST",
                    1,
                    3,
                    "CDF",
                    300_050L,
                    expires),
                PaymentSubmissionDigests.preview(
                    tenant,
                    entity,
                    batch,
                    1,
                    DIGEST,
                    "SYNTHETIC-TEST",
                    1,
                    2,
                    "USD",
                    300_050L,
                    expires),
                PaymentSubmissionDigests.preview(
                    tenant,
                    entity,
                    batch,
                    1,
                    DIGEST,
                    "SYNTHETIC-TEST",
                    1,
                    2,
                    "CDF",
                    300_051L,
                    expires),
                PaymentSubmissionDigests.preview(
                    tenant,
                    entity,
                    batch,
                    1,
                    DIGEST,
                    "SYNTHETIC-TEST",
                    1,
                    2,
                    "CDF",
                    300_050L,
                    expires.plusSeconds(1)),
                PaymentSubmissionDigests.preview(
                    UUID.randomUUID(),
                    entity,
                    batch,
                    1,
                    DIGEST,
                    "SYNTHETIC-TEST",
                    1,
                    2,
                    "CDF",
                    300_050L,
                    expires)))
        .doesNotContain(base)
        .doesNotHaveDuplicates();
  }
}
