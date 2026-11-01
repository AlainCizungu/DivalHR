package com.divalhr.core.payroll.domain;

import java.util.List;
import java.util.Optional;

/**
 * The controlled bank and mobile-money providers an administrator can record (MVP-095A, D95A-2). A
 * code and a display name only: no logo, no partner, no integration and no claim that DivalHR can
 * reach the provider. The generic OTHER_* codes serve any country.
 */
public final class PaymentProviders {

  /** Wildcard country of the generic providers. */
  public static final String ANY_COUNTRY = "*";

  /**
   * A controlled provider.
   *
   * @param code stable code
   * @param name display name (language-neutral)
   * @param kind {@code BANK_ACCOUNT} or {@code MOBILE_MONEY}
   * @param country ISO 3166-1 alpha-2, or {@link #ANY_COUNTRY}
   */
  public record Provider(String code, String name, PaymentMethod kind, String country) {}

  private static final List<Provider> ALL =
      List.of(
          new Provider("RAWBANK", "Rawbank", PaymentMethod.BANK_ACCOUNT, "CD"),
          new Provider("EQUITY_BCDC", "Equity BCDC", PaymentMethod.BANK_ACCOUNT, "CD"),
          new Provider("TMB", "Trust Merchant Bank", PaymentMethod.BANK_ACCOUNT, "CD"),
          new Provider("ECOBANK_CD", "Ecobank RDC", PaymentMethod.BANK_ACCOUNT, "CD"),
          new Provider("STANDARD_BANK_CD", "Standard Bank RDC", PaymentMethod.BANK_ACCOUNT, "CD"),
          new Provider("ACCESS_BANK_CD", "Access Bank RDC", PaymentMethod.BANK_ACCOUNT, "CD"),
          new Provider("OTHER_BANK", "Other bank", PaymentMethod.BANK_ACCOUNT, ANY_COUNTRY),
          new Provider("MPESA", "M-Pesa", PaymentMethod.MOBILE_MONEY, "CD"),
          new Provider("AIRTEL_MONEY", "Airtel Money", PaymentMethod.MOBILE_MONEY, "CD"),
          new Provider("ORANGE_MONEY", "Orange Money", PaymentMethod.MOBILE_MONEY, "CD"),
          new Provider("AFRIMONEY", "Afrimoney", PaymentMethod.MOBILE_MONEY, "CD"),
          new Provider(
              "OTHER_MOBILE_MONEY", "Other mobile money", PaymentMethod.MOBILE_MONEY, ANY_COUNTRY));

  private PaymentProviders() {}

  /**
   * @return every controlled provider
   */
  public static List<Provider> all() {
    return ALL;
  }

  /**
   * Finds the provider for a method and country.
   *
   * @param code provider code
   * @param kind the method
   * @param country ISO country code
   * @return the provider, or empty when the code is unknown, of the other kind or of another country
   */
  public static Optional<Provider> find(String code, PaymentMethod kind, String country) {
    for (Provider provider : ALL) {
      if (provider.code().equals(code)
          && provider.kind() == kind
          && (ANY_COUNTRY.equals(provider.country()) || provider.country().equals(country))) {
        return Optional.of(provider);
      }
    }
    return Optional.empty();
  }
}
