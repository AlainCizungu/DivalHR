package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PaymentMethod;
import com.divalhr.core.payroll.domain.PaymentSubmissionProfile;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The operator-controlled provider profiles (MVP-095B, D95B-2): never tenant data. This story ships
 * exactly one, the synthetic test provider, and only when it is explicitly enabled.
 */
public final class PaymentSubmissionProfiles {

  private final List<PaymentSubmissionProfile> active;

  PaymentSubmissionProfiles(PaymentSubmissionProperties properties) {
    PaymentSubmissionProperties.Synthetic synthetic = properties.synthetic();
    this.active =
        synthetic.active()
            ? List.of(
                new PaymentSubmissionProfile(
                    PaymentSubmissionProfile.SYNTHETIC_TEST,
                    "SYNTHETIC-TEST",
                    synthetic.host(),
                    synthetic.port(),
                    null,
                    Set.of("CDF", "USD"),
                    Set.of(PaymentMethod.BANK_ACCOUNT, PaymentMethod.MOBILE_MONEY),
                    true,
                    1))
            : List.of();
  }

  /**
   * Wraps explicit profiles (tests).
   *
   * @param profiles the active profiles
   */
  public PaymentSubmissionProfiles(List<PaymentSubmissionProfile> profiles) {
    this.active = List.copyOf(profiles);
  }

  /**
   * The profile serving a legal entity and currency.
   *
   * @param entity the legal entity
   * @param currency the currency
   * @return the first matching active profile, or empty
   */
  public Optional<PaymentSubmissionProfile> forBatch(UUID entity, String currency) {
    return active.stream()
        .filter(profile -> profile.serves(entity) && profile.currencies().contains(currency))
        .findFirst();
  }

  /**
   * Looks a profile up by its exact code.
   *
   * @param code the provider code
   * @return the active profile, or empty
   */
  public Optional<PaymentSubmissionProfile> byCode(String code) {
    return active.stream().filter(profile -> profile.code().equals(code)).findFirst();
  }
}
