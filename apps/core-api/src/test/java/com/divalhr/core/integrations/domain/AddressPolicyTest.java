package com.divalhr.core.integrations.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressPolicyTest {

  private static InetAddress ip(String literal) throws Exception {
    return InetAddress.getByName(literal);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0.0.0.0",
        "0.1.2.3",
        "10.0.0.1",
        "10.255.255.255",
        "100.64.0.1",
        "100.127.255.255",
        "127.0.0.1",
        "127.255.255.254",
        "169.254.169.254",
        "169.254.0.1",
        "172.16.0.1",
        "172.31.255.255",
        "192.0.0.1",
        "192.0.0.192",
        "192.0.2.10",
        "192.88.99.1",
        "192.168.1.1",
        "198.18.0.1",
        "198.19.255.255",
        "198.51.100.7",
        "203.0.113.9",
        "224.0.0.1",
        "239.255.255.255",
        "240.0.0.1",
        "255.255.255.255",
        "100.100.100.200",
        "::",
        "::1",
        "::ffff:127.0.0.1",
        "::ffff:10.0.0.1",
        "::ffff:169.254.169.254",
        "64:ff9b::808:808",
        "64:ff9b:1::1",
        "100::1",
        "2001::1",
        "2001:db8::1",
        "2002:7f00:1::1",
        "fc00::1",
        "fd00:ec2::254",
        "fe80::1",
        "fec0::1",
        "ff02::1",
        "3fff::1",
        "4000::1"
      })
  void refusesNonPublicAddresses(String literal) throws Exception {
    assertThat(AddressPolicy.isPublic(ip(literal))).as(literal).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "8.8.8.8",
        "1.1.1.1",
        "100.63.255.255",
        "100.128.0.1",
        "172.15.255.255",
        "172.32.0.1",
        "169.253.255.255",
        "198.17.255.255",
        "198.20.0.1",
        "223.255.255.255",
        "93.184.216.34",
        "2606:4700:4700::1111",
        "2a00:1450:4009:81f::200e",
        "::ffff:8.8.8.8"
      })
  void allowsGlobalUnicast(String literal) throws Exception {
    assertThat(AddressPolicy.isPublic(ip(literal))).as(literal).isTrue();
  }

  @Test
  void nullIsRefused() {
    assertThat(AddressPolicy.isPublic(null)).isFalse();
  }
}
