package com.divalhr.core.payroll.domain;

import java.util.Set;
import java.util.UUID;

/**
 * An operator-controlled provider profile (MVP-095B, D95B-2): an exact provider code bound to one
 * HTTPS host and port, the legal entities and currencies it serves and the destination methods it
 * supports. Tenants never supply any of it. The only profile this story ships is the synthetic
 * {@code SYNTHETIC-TEST} receiver.
 *
 * @param code exact provider code, {@code ^[A-Z][A-Z0-9-]{2,39}$}
 * @param label display label (language-neutral, never a claim of partnership)
 * @param host exact HTTPS host name
 * @param port HTTPS port
 * @param legalEntityId the only legal entity served, or {@code null} for every entity
 * @param currencies ISO currencies served
 * @param methods supported destination methods (never MANUAL)
 * @param synthetic whether this is the clearly labelled synthetic test provider
 * @param keyVersion version of the signing key used with this profile
 */
public record PaymentSubmissionProfile(
    String code,
    String label,
    String host,
    int port,
    UUID legalEntityId,
    Set<String> currencies,
    Set<PaymentMethod> methods,
    boolean synthetic,
    int keyVersion) {

  /** The code of the synthetic test provider. */
  public static final String SYNTHETIC_TEST = "SYNTHETIC-TEST";

  /** Validates the profile. */
  public PaymentSubmissionProfile {
    if (code == null || !code.matches("^[A-Z][A-Z0-9-]{2,39}$")) {
      throw new IllegalArgumentException("invalid provider profile code");
    }
    if (host == null || host.isBlank() || port < 1 || port > 65535) {
      throw new IllegalArgumentException("invalid provider profile endpoint");
    }
    currencies = Set.copyOf(currencies);
    methods = Set.copyOf(methods);
    if (methods.contains(PaymentMethod.MANUAL)) {
      throw new IllegalArgumentException("MANUAL is never submitted to a provider");
    }
    if (keyVersion < 1 || keyVersion > 1000) {
      throw new IllegalArgumentException("invalid signing key version");
    }
  }

  /**
   * Whether the profile serves a legal entity.
   *
   * @param entity the legal entity
   * @return {@code true} for the bound entity or when the profile serves every entity
   */
  public boolean serves(UUID entity) {
    return legalEntityId == null || legalEntityId.equals(entity);
  }
}
