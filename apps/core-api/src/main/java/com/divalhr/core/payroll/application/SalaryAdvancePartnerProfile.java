package com.divalhr.core.payroll.application;

/**
 * The operator-controlled partner profile (MVP-120B, ADR 0042 §5): one exact code bound to one
 * HTTPS host and port. Tenants and employees never supply any of it. The only profile this story
 * ships is the synthetic {@code SYNTHETIC-TEST} receiver, and only when it is explicitly enabled.
 *
 * @param code the exact partner code
 * @param host exact HTTPS host name
 * @param port exact HTTPS port
 */
public record SalaryAdvancePartnerProfile(String code, String host, int port) {

  /** The code of the synthetic test partner. */
  public static final String SYNTHETIC_TEST = "SYNTHETIC-TEST";

  /** Validates the profile. */
  public SalaryAdvancePartnerProfile {
    if (!SYNTHETIC_TEST.equals(code)) {
      throw new IllegalArgumentException("invalid partner profile code");
    }
    if (host == null || host.isBlank() || port < 1 || port > 65535) {
      throw new IllegalArgumentException("invalid partner profile endpoint");
    }
  }
}
