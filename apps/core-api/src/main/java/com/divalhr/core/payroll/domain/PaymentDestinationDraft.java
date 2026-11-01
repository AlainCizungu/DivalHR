package com.divalhr.core.payroll.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A validated, normalized payment destination to record (MVP-095A, D95A-2). Restricted financial
 * and HR data: the account, phone, holder, branch and explanation exist only here, in the
 * encrypted envelope and in the request that carried them; {@code toString} is redacted.
 *
 * @param method the method
 * @param country ISO country, or {@code null} for MANUAL
 * @param provider the controlled provider, or {@code null} for MANUAL
 * @param account normalized account number, or {@code null}
 * @param holder holder name, or {@code null} for MANUAL
 * @param branch optional branch value, or {@code null}
 * @param phone E.164 number, or {@code null}
 * @param effectiveFrom the first day the destination applies
 * @param reason the closed reason
 * @param explanation the explanation of OTHER_CONTROLLED, or {@code null}
 */
public record PaymentDestinationDraft(
    PaymentMethod method,
    String country,
    PaymentProviders.Provider provider,
    String account,
    String holder,
    String branch,
    String phone,
    LocalDate effectiveFrom,
    PaymentDestinationReason reason,
    String explanation) {

  private static final String HEADER = "DIVALHR-PAYMENT-DESTINATION v1\n";

  /**
   * The masked value shown after entry.
   *
   * @return the masked account or phone, or {@code null} for MANUAL
   */
  public String maskedValue() {
    return switch (method) {
      case BANK_ACCOUNT -> PaymentMasking.account(account);
      case MOBILE_MONEY -> PaymentMasking.phone(phone);
      case MANUAL -> null;
    };
  }

  /**
   * The primary identifier whose equality means "the same account": account number or phone.
   *
   * @return the value, or {@code null} for MANUAL
   */
  public String identifier() {
    return switch (method) {
      case BANK_ACCOUNT -> account;
      case MOBILE_MONEY -> phone;
      case MANUAL -> null;
    };
  }

  /**
   * The text sealed in the envelope: every sensitive field, one {@code name=value} line each.
   *
   * @return the text; control characters cannot occur in any value
   */
  public String sealedText() {
    StringBuilder text = new StringBuilder(HEADER);
    append(text, "account", account);
    append(text, "holder", holder);
    append(text, "branch", branch);
    append(text, "phone", phone);
    append(text, "explanation", explanation);
    return text.toString();
  }

  /**
   * The content that decides "no change": the method, the provider, the country and the sealed
   * text, but neither the date nor the reason.
   *
   * @return a canonical text for keyed fingerprinting
   */
  public String contentText() {
    return method.name()
        + "\n"
        + (country == null ? "" : country)
        + "\n"
        + (provider == null ? "" : provider.code())
        + "\n"
        + sealedText();
  }

  private static void append(StringBuilder text, String name, String value) {
    if (value != null) {
      text.append(name).append('=').append(value).append('\n');
    }
  }

  /**
   * Reads back the fields of a sealed text.
   *
   * @param sealedText a text produced by {@link #sealedText()}
   * @return the fields, or empty when the text is not of this format
   */
  public static Optional<Map<String, String>> parseSealed(String sealedText) {
    if (!sealedText.startsWith(HEADER)) {
      return Optional.empty();
    }
    Map<String, String> fields = new LinkedHashMap<>();
    for (String line : sealedText.substring(HEADER.length()).split("\n")) {
      if (line.isEmpty()) {
        continue;
      }
      int at = line.indexOf('=');
      if (at <= 0) {
        return Optional.empty();
      }
      fields.put(line.substring(0, at), line.substring(at + 1));
    }
    return Optional.of(fields);
  }

  @Override
  public String toString() {
    return "PaymentDestinationDraft[" + method + "]";
  }
}
