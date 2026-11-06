package com.divalhr.core.payroll.domain;

import java.util.regex.Pattern;

/**
 * The generated file names of an accounting journal export (MVP-096, ADR 0039 section 9):
 * {@code accounting-journal-<ENTITYCODE>-<YYYY-MM>-<CUR>-v<version>.csv} and {@code
 * .manifest.json}. Built only from the stored legal entity code, the period, the currency and the
 * version, never from user text; a part that does not fit the stored formats is refused.
 */
public final class AccountingFileNames {

  private static final Pattern ENTITY = Pattern.compile("^[A-Z0-9_-]{2,20}$");
  private static final Pattern PERIOD = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");

  private AccountingFileNames() {}

  /**
   * The CSV file name.
   *
   * @param entityCode stored legal entity code
   * @param period {@code YYYY-MM}
   * @param currency currency code
   * @param version journal version
   * @return the file name
   */
  public static String csv(String entityCode, String period, String currency, int version) {
    return stem(entityCode, period, currency, version) + ".csv";
  }

  /**
   * The manifest file name.
   *
   * @param entityCode stored legal entity code
   * @param period {@code YYYY-MM}
   * @param currency currency code
   * @param version journal version
   * @return the file name
   */
  public static String manifest(String entityCode, String period, String currency, int version) {
    return stem(entityCode, period, currency, version) + ".manifest.json";
  }

  private static String stem(String entityCode, String period, String currency, int version) {
    if (!ENTITY.matcher(entityCode).matches()
        || !PERIOD.matcher(period).matches()
        || AccountingCurrency.parse(currency).isEmpty()
        || version < 1) {
      throw new IllegalArgumentException("accounting file name part is not valid");
    }
    return "accounting-journal-" + entityCode + "-" + period + "-" + currency + "-v" + version;
  }
}
