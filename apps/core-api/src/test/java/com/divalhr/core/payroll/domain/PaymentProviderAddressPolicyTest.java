package com.divalhr.core.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * R188-1A: the synthetic-receiver exception is narrow. Only an address inside an explicitly
 * configured private network qualifies; loopback, unspecified, link-local, multicast, metadata and
 * other special-purpose ranges never do, public addresses never do, and the configured networks
 * themselves must be private space. The real-provider (public) policy is unchanged.
 */
class PaymentProviderAddressPolicyTest {

  private static final List<String> NETWORKS = List.of("172.20.0.0/16", "fd12:3456::/32");

  private static InetAddress ip(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException impossible) {
      throw new AssertionError(impossible);
    }
  }

  @Test
  void anAddressInsideAConfiguredPrivateNetworkIsAcceptable() {
    assertThat(PaymentProviderAddressPolicy.isInternalProvider(ip("172.20.0.5"), NETWORKS))
        .isTrue();
    assertThat(PaymentProviderAddressPolicy.isInternalProvider(ip("172.20.255.254"), NETWORKS))
        .isTrue();
    assertThat(PaymentProviderAddressPolicy.isInternalProvider(ip("fd12:3456::7"), NETWORKS))
        .isTrue();
    assertThat(PaymentProviderAddressPolicy.isInternalProvider(ip("::ffff:172.20.0.5"), NETWORKS))
        .isTrue();
  }

  @Test
  void privateAddressesOutsideTheConfiguredNetworksAreRefused() {
    for (String outside :
        List.of("172.21.0.5", "172.19.255.255", "10.0.0.5", "192.168.1.1", "fd12:3457::1")) {
      assertThat(PaymentProviderAddressPolicy.isInternalProvider(ip(outside), NETWORKS))
          .as(outside)
          .isFalse();
    }
  }

  @Test
  void loopbackUnspecifiedLinkLocalMulticastMetadataAndSpecialRangesNeverQualify() {
    List<String> everything = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7");
    for (String bad :
        List.of(
            "127.0.0.1",
            "127.255.255.254",
            "0.0.0.0",
            "169.254.169.254",
            "169.254.0.1",
            "224.0.0.1",
            "239.255.255.250",
            "255.255.255.255",
            "100.64.0.1",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
            "198.18.0.1",
            "240.0.0.1",
            "::1",
            "::",
            "fe80::1",
            "ff02::1",
            "fd00:ec2::254",
            "::ffff:127.0.0.1",
            "::ffff:169.254.169.254",
            "::ffff:0.0.0.0")) {
      assertThat(PaymentProviderAddressPolicy.isInternalProvider(ip(bad), everything))
          .as(bad)
          .isFalse();
    }
  }

  @Test
  void aPublicAddressIsNeverAcceptableForTheSyntheticProfile() {
    List<String> everything = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7");
    for (String pub : List.of("93.184.216.34", "8.8.8.8", "2606:4700:4700::1111")) {
      assertThat(PaymentProviderAddressPolicy.isInternalProvider(ip(pub), everything))
          .as(pub)
          .isFalse();
    }
  }

  @Test
  void aNullAddressOrNetworkListIsRefused() {
    assertThat(PaymentProviderAddressPolicy.isInternalProvider(null, NETWORKS)).isFalse();
    assertThat(PaymentProviderAddressPolicy.isInternalProvider(ip("172.20.0.5"), null)).isFalse();
    assertThat(PaymentProviderAddressPolicy.isInternalProvider(ip("172.20.0.5"), List.of()))
        .isFalse();
  }

  @Test
  void theConfiguredNetworksMustBeNonEmptyAndWhollyPrivate() {
    PaymentProviderAddressPolicy.validateInternalNetworks(NETWORKS);
    PaymentProviderAddressPolicy.validateInternalNetworks(
        List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"));
    assertThatThrownBy(() -> PaymentProviderAddressPolicy.validateInternalNetworks(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PaymentProviderAddressPolicy.validateInternalNetworks(null))
        .isInstanceOf(IllegalArgumentException.class);
    for (String bad :
        List.of(
            "0.0.0.0/0",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "8.8.8.0/24",
            "172.0.0.0/8",
            "10.0.0.0/7",
            "::/0",
            "fe80::/10",
            "not-a-network",
            "10.0.0.0",
            "10.0.0.0/33")) {
      assertThatThrownBy(() -> PaymentProviderAddressPolicy.validateInternalNetworks(List.of(bad)))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void theRealProviderPolicyStillAcceptsOnlyPublicAddresses() {
    assertThat(PaymentProviderAddressPolicy.isPublic(ip("93.184.216.34"))).isTrue();
    assertThat(PaymentProviderAddressPolicy.isPublic(ip("2606:4700:4700::1111"))).isTrue();
    for (String bad :
        List.of(
            "172.20.0.5",
            "10.0.0.5",
            "192.168.1.1",
            "127.0.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "::1",
            "fe80::1",
            "fd12:3456::7",
            "::ffff:10.0.0.5")) {
      assertThat(PaymentProviderAddressPolicy.isPublic(ip(bad))).as(bad).isFalse();
    }
  }
}
