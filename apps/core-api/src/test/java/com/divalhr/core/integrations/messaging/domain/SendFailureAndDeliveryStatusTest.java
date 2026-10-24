package com.divalhr.core.integrations.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** MVP-111 (D111-9, D111-10): the closed failure classes and the monotonic delivery states. */
class SendFailureAndDeliveryStatusTest {

  @Test
  void onlyTransportThrottleAndServerFailuresAreRetried() {
    Set<SendFailure> retryable =
        EnumSet.of(
            SendFailure.DNS_FAILED,
            SendFailure.CONNECT_TIMEOUT,
            SendFailure.REQUEST_TIMEOUT,
            SendFailure.TLS_FAILED,
            SendFailure.CONNECTION_FAILED,
            SendFailure.RATE_LIMITED,
            SendFailure.PROVIDER_FAILED);
    for (SendFailure failure : SendFailure.values()) {
      assertThat(failure.retryable()).as(failure.name()).isEqualTo(retryable.contains(failure));
    }
    assertThat(SendFailure.CREDENTIAL_INVALID.retryable()).isFalse();
    assertThat(SendFailure.TEMPLATE_INVALID.retryable()).isFalse();
    assertThat(SendFailure.RECIPIENT_REJECTED.retryable()).isFalse();
    assertThat(SendFailure.HTTP_RESPONSE.retryable()).isFalse();
  }

  @Test
  void onlyAnAnswerlessAttemptLeavesAcceptanceUnknown() {
    Set<SendFailure> unknown =
        EnumSet.of(
            SendFailure.REQUEST_TIMEOUT,
            SendFailure.CONNECTION_FAILED,
            SendFailure.PROVIDER_FAILED);
    for (SendFailure failure : SendFailure.values()) {
      assertThat(failure.acceptanceUnknown())
          .as(failure.name())
          .isEqualTo(unknown.contains(failure));
    }
  }

  @Test
  void theFailureClassesMatchTheDatabaseVocabulary() throws Exception {
    String sql =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/main/resources/db/migration/V40__whatsapp_messaging_connector.sql"));
    for (SendFailure failure : SendFailure.values()) {
      assertThat(sql).as(failure.name()).contains("'" + failure.name() + "'");
    }
    for (DeliveryStatus status : DeliveryStatus.values()) {
      assertThat(sql).as(status.name()).contains("'" + status.name() + "'");
    }
  }

  @Test
  void finalAndOpenStatesAreDisjointAndAcceptedIsNotFinal() {
    for (DeliveryStatus status : DeliveryStatus.values()) {
      assertThat(status.isFinal() && status.isOpen()).as(status.name()).isFalse();
    }
    assertThat(DeliveryStatus.PENDING.isOpen()).isTrue();
    assertThat(DeliveryStatus.RETRYING.isOpen()).isTrue();
    assertThat(DeliveryStatus.ACCEPTED.isFinal()).isFalse();
    assertThat(DeliveryStatus.DELIVERED.isFinal()).isFalse();
    for (DeliveryStatus done :
        new DeliveryStatus[] {
          DeliveryStatus.READ,
          DeliveryStatus.FAILED,
          DeliveryStatus.CANCELLED,
          DeliveryStatus.STATUS_UNKNOWN
        }) {
      assertThat(done.isFinal()).as(done.name()).isTrue();
    }
  }

  @Test
  void providerRankIsForwardOnly() {
    assertThat(DeliveryStatus.ACCEPTED.providerRank())
        .isLessThan(DeliveryStatus.DELIVERED.providerRank());
    assertThat(DeliveryStatus.DELIVERED.providerRank())
        .isLessThan(DeliveryStatus.READ.providerRank());
    assertThat(DeliveryStatus.PENDING.providerRank()).isZero();
    assertThat(DeliveryStatus.FAILED.providerRank()).isZero();
  }
}
