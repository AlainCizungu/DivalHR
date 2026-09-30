package com.divalhr.core.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Architecture amendment A4: forwarding headers are trusted only from configured proxies. */
class ClientAddressResolverTest {

  private static ClientAddressResolver resolver(List<String> trusted) {
    return new ClientAddressResolver(
        new RateLimitProperties(10, 100, Duration.ofMinutes(1), 100, trusted));
  }

  private static MockHttpServletRequest request(String peer, String forwarded) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(peer);
    if (forwarded != null) {
      request.addHeader("X-Forwarded-For", forwarded);
    }
    return request;
  }

  @Test
  void untrustedForwardingHeadersAreIgnored() {
    ClientAddressResolver none = resolver(List.of());
    assertThat(none.resolve(request("203.0.113.7", "198.51.100.9"))).isEqualTo("203.0.113.7");
    ClientAddressResolver some = resolver(List.of("10.0.0.2"));
    // The peer is not a trusted proxy: a spoofed header cannot choose the key.
    assertThat(some.resolve(request("203.0.113.7", "198.51.100.9, 10.0.0.2")))
        .isEqualTo("203.0.113.7");
  }

  @Test
  void trustedProxiesYieldTheNearestUntrustedHop() {
    ClientAddressResolver trusted = resolver(List.of("10.0.0.2", "10.0.0.3"));
    assertThat(trusted.resolve(request("10.0.0.2", "198.51.100.9"))).isEqualTo("198.51.100.9");
    // A client-supplied prefix is skipped: the right-most untrusted hop wins.
    assertThat(trusted.resolve(request("10.0.0.2", "1.2.3.4, 198.51.100.9, 10.0.0.3")))
        .isEqualTo("198.51.100.9");
    assertThat(trusted.resolve(request("10.0.0.2", null))).isEqualTo("10.0.0.2");
    assertThat(trusted.resolve(request("10.0.0.2", " , 198.51.100.9"))).isEqualTo("198.51.100.9");
    assertThat(trusted.resolve(request("10.0.0.2", "x".repeat(80)))).isEqualTo("10.0.0.2");
  }
}
