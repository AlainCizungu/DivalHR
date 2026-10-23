package com.divalhr.core.integrations.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.integrations.application.IntegrationProperties.TestReceiver;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Customer URLs: the receiver exception opens one exact host and port, nothing else. */
class IntegrationPropertiesTest {

  private static IntegrationProperties with(List<Integer> extra, TestReceiver receiver) {
    return new IntegrationProperties(
        null, extra, false, Duration.ofSeconds(3), Duration.ofSeconds(10), receiver);
  }

  @Test
  void aNonStandardPortIsRejectedWithoutConfiguration() {
    var properties = with(List.of(), null);
    assertThat(properties.parseUrl("https://hooks.example.com/in")).isPresent();
    assertThat(properties.parseUrl("https://hooks.example.com:8443/in")).isEmpty();
  }

  @Test
  void theReceiverPortIsAcceptedOnlyForTheReceiverHost() {
    var properties = with(List.of(), new TestReceiver("receiver.hooks.test", 8443, null));
    assertThat(properties.parseUrl("https://receiver.hooks.test:8443/hook")).isPresent();
    assertThat(properties.parseUrl("https://other.hooks.test:8443/hook")).isEmpty();
    assertThat(properties.parseUrl("https://hooks.example.com:8443/hook")).isEmpty();
    assertThat(properties.parseUrl("https://receiver.hooks.test:9443/hook")).isEmpty();
    assertThat(properties.parseUrl("https://a.receiver.hooks.test:8443/hook")).isEmpty();
  }

  @Test
  void configuredExtraPortsStillApplyToEveryHost() {
    var properties = with(List.of(8443), null);
    assertThat(properties.parseUrl("https://hooks.example.com:8443/in")).isPresent();
  }
}
