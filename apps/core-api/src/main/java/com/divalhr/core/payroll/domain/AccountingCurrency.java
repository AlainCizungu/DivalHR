package com.divalhr.core.payroll.domain;

import java.util.Optional;

/**
 * The currency of an accounting mapping and journal (MVP-096, ADR 0039 section 3). Mappings of
 * different currencies are independent; nothing here converts between them.
 */
public enum AccountingCurrency {
  /** Congolese franc. */
  CDF,
  /** United States dollar. */
  USD;

  /**
   * Parses a currency exactly.
   *
   * @param text raw value
   * @return the currency, or empty
   */
  public static Optional<AccountingCurrency> parse(String text) {
    for (AccountingCurrency currency : values()) {
      if (currency.name().equals(text)) {
        return Optional.of(currency);
      }
    }
    return Optional.empty();
  }
}
