package com.divalhr.core.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-095C (ADR 0038 section 8): the preview digest and the request fingerprint. */
class PaymentReconciliationDigestsTest {

  private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ENTITY = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID BATCH = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID SUBMISSION = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final Instant EXPIRES = Instant.parse("2026-11-02T10:00:00Z");

  private static String digest(
      UUID tenant,
      UUID entity,
      UUID batch,
      UUID submission,
      int version,
      String state,
      String provider,
      int keyVersion,
      Instant expires) {
    return PaymentReconciliationDigests.preview(
        tenant, entity, batch, submission, version, state, provider, keyVersion, expires);
  }

  @Test
  void thePreviewDigestIsStableAndBindsEveryInput() {
    String base =
        digest(
            TENANT, ENTITY, BATCH, SUBMISSION, 3, "DELIVERY_UNKNOWN", "SYNTHETIC-TEST", 1, EXPIRES);
    assertThat(base).matches("[0-9a-f]{64}");
    assertThat(
            digest(
                TENANT,
                ENTITY,
                BATCH,
                SUBMISSION,
                3,
                "DELIVERY_UNKNOWN",
                "SYNTHETIC-TEST",
                1,
                EXPIRES))
        .isEqualTo(base);
    assertThat(
            List.of(
                digest(
                    UUID.randomUUID(),
                    ENTITY,
                    BATCH,
                    SUBMISSION,
                    3,
                    "DELIVERY_UNKNOWN",
                    "SYNTHETIC-TEST",
                    1,
                    EXPIRES),
                digest(
                    TENANT,
                    UUID.randomUUID(),
                    BATCH,
                    SUBMISSION,
                    3,
                    "DELIVERY_UNKNOWN",
                    "SYNTHETIC-TEST",
                    1,
                    EXPIRES),
                digest(
                    TENANT,
                    ENTITY,
                    UUID.randomUUID(),
                    SUBMISSION,
                    3,
                    "DELIVERY_UNKNOWN",
                    "SYNTHETIC-TEST",
                    1,
                    EXPIRES),
                digest(
                    TENANT,
                    ENTITY,
                    BATCH,
                    UUID.randomUUID(),
                    3,
                    "DELIVERY_UNKNOWN",
                    "SYNTHETIC-TEST",
                    1,
                    EXPIRES),
                digest(
                    TENANT,
                    ENTITY,
                    BATCH,
                    SUBMISSION,
                    4,
                    "DELIVERY_UNKNOWN",
                    "SYNTHETIC-TEST",
                    1,
                    EXPIRES),
                digest(
                    TENANT,
                    ENTITY,
                    BATCH,
                    SUBMISSION,
                    3,
                    "MANUAL_INTERVENTION",
                    "SYNTHETIC-TEST",
                    1,
                    EXPIRES),
                digest(
                    TENANT,
                    ENTITY,
                    BATCH,
                    SUBMISSION,
                    3,
                    "DELIVERY_UNKNOWN",
                    "OTHER-PROVIDER",
                    1,
                    EXPIRES),
                digest(
                    TENANT,
                    ENTITY,
                    BATCH,
                    SUBMISSION,
                    3,
                    "DELIVERY_UNKNOWN",
                    "SYNTHETIC-TEST",
                    2,
                    EXPIRES),
                digest(
                    TENANT,
                    ENTITY,
                    BATCH,
                    SUBMISSION,
                    3,
                    "DELIVERY_UNKNOWN",
                    "SYNTHETIC-TEST",
                    1,
                    EXPIRES.plusSeconds(1))))
        .doesNotContain(base)
        .doesNotHaveDuplicates();
  }

  @Test
  void theFingerprintIsTwelveHexCharactersStableAndDomainSeparated() {
    UUID request = UUID.fromString("55555555-5555-4555-8555-555555555555");
    String fingerprint = PaymentReconciliationDigests.requestFingerprint(request);
    assertThat(fingerprint).matches("[0-9a-f]{12}");
    assertThat(PaymentReconciliationDigests.requestFingerprint(request)).isEqualTo(fingerprint);
    assertThat(PaymentReconciliationDigests.requestFingerprint(UUID.randomUUID()))
        .isNotEqualTo(fingerprint);
    // Domain separated: not a plain hash of the raw ID, and never the ID itself.
    assertThat(fingerprint)
        .isNotEqualTo(PackageDigests.sha256(request.toString()).substring(0, 12))
        .isNotEqualTo(PackageDigests.sha256(request + "\n").substring(0, 12));
    assertThat(request.toString()).doesNotContain(fingerprint);
  }
}
