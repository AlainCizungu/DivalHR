package com.divalhr.core.integrations.application;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Name resolution seam so tests control DNS answers (D110-8, D110-15). */
@FunctionalInterface
public interface DnsResolver {

  /** Maximum number of answers considered. */
  int MAX_ANSWERS = 16;

  /**
   * Resolves all A and AAAA records.
   *
   * @param host ASCII host
   * @return the answers
   * @throws UnknownHostException when the name does not resolve
   */
  InetAddress[] resolve(String host) throws UnknownHostException;

  /**
   * @return the system resolver
   */
  static DnsResolver system() {
    return InetAddress::getAllByName;
  }
}
