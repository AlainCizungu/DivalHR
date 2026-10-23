package com.divalhr.core.integrations.domain;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

/**
 * Decides whether a resolved address may be contacted by webhook delivery (D110-8). Only global
 * unicast addresses pass: loopback, private, link-local, multicast, carrier-grade NAT,
 * documentation, benchmark, reserved, cloud-metadata and transition (NAT64, 6to4, Teredo) ranges
 * are refused for IPv4 and IPv6. An IPv4-mapped IPv6 address is judged as the IPv4 address.
 */
public final class AddressPolicy {

  private record Net(byte[] prefix, int bits) {
    boolean contains(byte[] address) {
      if (address.length != prefix.length) {
        return false;
      }
      int full = bits / 8;
      for (int i = 0; i < full; i++) {
        if (address[i] != prefix[i]) {
          return false;
        }
      }
      int rest = bits % 8;
      if (rest == 0) {
        return true;
      }
      int mask = 0xFF << (8 - rest) & 0xFF;
      return (address[full] & mask) == (prefix[full] & mask);
    }
  }

  private static final List<Net> BLOCKED_V4 =
      List.of(
          v4("0.0.0.0", 8),
          v4("10.0.0.0", 8),
          v4("100.64.0.0", 10),
          v4("127.0.0.0", 8),
          v4("169.254.0.0", 16),
          v4("172.16.0.0", 12),
          v4("192.0.0.0", 24),
          v4("192.0.2.0", 24),
          v4("192.88.99.0", 24),
          v4("192.168.0.0", 16),
          v4("198.18.0.0", 15),
          v4("198.51.100.0", 24),
          v4("203.0.113.0", 24),
          v4("224.0.0.0", 4),
          v4("240.0.0.0", 4));

  private static final Net GLOBAL_V6 = v6("2000::", 3);

  private static final List<Net> BLOCKED_V6 =
      List.of(
          v6("64:ff9b::", 96),
          v6("64:ff9b:1::", 48),
          v6("2001::", 23),
          v6("2001:db8::", 32),
          v6("2002::", 16),
          v6("3fff::", 20),
          v6("fc00::", 7),
          v6("fe80::", 10),
          v6("fec0::", 10),
          v6("ff00::", 8));

  private AddressPolicy() {}

  private static Net v4(String literal, int bits) {
    return new Net(raw(literal), bits);
  }

  private static Net v6(String literal, int bits) {
    return new Net(raw(literal), bits);
  }

  private static byte[] raw(String literal) {
    try {
      return InetAddress.getByName(literal).getAddress();
    } catch (java.net.UnknownHostException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /**
   * Whether the address may be contacted.
   *
   * @param address a resolved address
   * @return true only for a public, global unicast address
   */
  public static boolean isPublic(InetAddress address) {
    if (address == null) {
      return false;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      return BLOCKED_V4.stream().noneMatch(n -> n.contains(bytes));
    }
    if (address instanceof Inet6Address) {
      if (isMappedV4(bytes)) {
        byte[] v4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        return BLOCKED_V4.stream().noneMatch(n -> n.contains(v4));
      }
      return GLOBAL_V6.contains(bytes) && BLOCKED_V6.stream().noneMatch(n -> n.contains(bytes));
    }
    return false;
  }

  private static boolean isMappedV4(byte[] bytes) {
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
  }
}
