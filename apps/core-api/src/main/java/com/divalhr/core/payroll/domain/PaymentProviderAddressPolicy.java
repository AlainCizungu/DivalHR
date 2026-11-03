package com.divalhr.core.payroll.domain;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

/**
 * Decides whether a resolved address may be contacted by provider submission (MVP-095B, ADR 0037).
 * Only global unicast addresses pass: loopback, private, link-local, multicast, carrier-grade NAT,
 * documentation, benchmark, reserved, cloud-metadata and transition (NAT64, 6to4, Teredo) ranges
 * are refused for IPv4 and IPv6. An IPv4-mapped IPv6 address is judged as the IPv4 address.
 *
 * <p>The synthetic test receiver (R188-1A) is judged by {@link #isInternalProvider}: it never
 * passes a loopback, unspecified, link-local, multicast, cloud-metadata or other special-purpose
 * range, and it must lie inside an explicitly configured private provider network (RFC 1918 or
 * unique-local space only, validated by {@link #validateInternalNetworks}). A public address is
 * never acceptable for the synthetic profile.
 */
public final class PaymentProviderAddressPolicy {

  private record Net(byte[] prefix, int bits) {
    boolean within(Net outer) {
      return prefix.length == outer.prefix.length && bits >= outer.bits && outer.contains(prefix);
    }

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

  /** The private ranges an operator may name as the synthetic provider's internal network. */
  private static final List<Net> PRIVATE_ALLOWED =
      List.of(v4("10.0.0.0", 8), v4("172.16.0.0", 12), v4("192.168.0.0", 16), v6("fc00::", 7));

  /** Special-purpose IPv4 ranges that no profile may reach: the blocked list minus RFC 1918. */
  private static final List<Net> SPECIAL_V4 =
      BLOCKED_V4.stream().filter(n -> !isPrivateAllowed(n)).toList();

  /**
   * Special-purpose IPv6 ranges: the blocked list minus unique-local, plus the host ranges and
   * metadata.
   */
  private static final List<Net> SPECIAL_V6 =
      java.util.stream.Stream.concat(
              BLOCKED_V6.stream().filter(n -> !isPrivateAllowed(n)),
              java.util.stream.Stream.of(
                  v6("::", 128), v6("::1", 128), v6("100::", 64), v6("fd00:ec2::", 32)))
          .toList();

  private PaymentProviderAddressPolicy() {}

  private static boolean isPrivateAllowed(Net net) {
    return PRIVATE_ALLOWED.stream()
        .anyMatch(p -> p.bits == net.bits && java.util.Arrays.equals(p.prefix, net.prefix));
  }

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

  /**
   * Whether the address is an acceptable synthetic provider address: inside one of the configured
   * private networks and in no special-purpose range (loopback, unspecified, link-local, multicast,
   * cloud metadata, carrier-grade NAT, documentation, reserved, transition ranges). Public
   * addresses are never acceptable here.
   *
   * @param address a resolved address
   * @param networks the configured CIDR networks (already validated)
   * @return true only for an address inside a configured internal network and outside every
   *     special-purpose range
   */
  public static boolean isInternalProvider(InetAddress address, List<String> networks) {
    if (address == null || networks == null) {
      return false;
    }
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] bytes = address.getAddress();
    boolean v6 = address instanceof Inet6Address;
    if (v6 && isMappedV4(bytes)) {
      bytes = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
      v6 = false;
    }
    final byte[] checked = bytes;
    List<Net> special = v6 ? SPECIAL_V6 : SPECIAL_V4;
    if (special.stream().anyMatch(n -> n.contains(checked))) {
      return false;
    }
    return networks.stream()
        .map(PaymentProviderAddressPolicy::parse)
        .anyMatch(n -> n.contains(checked));
  }

  /**
   * Validates the configured synthetic networks at startup: each must be a CIDR wholly inside RFC
   * 1918 or unique-local space, so a misconfiguration can never open a public, loopback, link-local
   * or metadata range.
   *
   * @param networks the configured CIDR networks
   * @throws IllegalArgumentException when empty, malformed or not wholly private
   */
  public static void validateInternalNetworks(List<String> networks) {
    if (networks == null || networks.isEmpty()) {
      throw new IllegalArgumentException(
          "the synthetic provider needs at least one internal network");
    }
    for (String cidr : networks) {
      Net net = parse(cidr);
      if (PRIVATE_ALLOWED.stream().noneMatch(outer -> net.within(outer))) {
        throw new IllegalArgumentException("a synthetic provider network must be private space");
      }
    }
  }

  private static Net parse(String cidr) {
    if (cidr == null) {
      throw new IllegalArgumentException("invalid network");
    }
    int slash = cidr.indexOf('/');
    if (slash < 1 || slash == cidr.length() - 1) {
      throw new IllegalArgumentException("invalid network");
    }
    try {
      byte[] prefix = InetAddress.getByName(cidr.substring(0, slash).trim()).getAddress();
      int bits = Integer.parseInt(cidr.substring(slash + 1).trim());
      if (bits < 0 || bits > prefix.length * 8) {
        throw new IllegalArgumentException("invalid network");
      }
      return new Net(prefix, bits);
    } catch (java.net.UnknownHostException | NumberFormatException malformed) {
      throw new IllegalArgumentException("invalid network");
    }
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
