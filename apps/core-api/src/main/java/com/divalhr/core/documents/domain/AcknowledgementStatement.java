package com.divalhr.core.documents.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The server-owned acknowledgement statements (MVP-030, D16, A30-3). Version 1 is approved
 * provisionally: the organization's counsel must review it before production use. It says only that
 * the employee received and reviewed the contract as displayed, and that the confirmation is not an
 * electronic signature. Changing one character requires version 2 (golden tests pin each text and
 * digest).
 *
 * @param code statement code
 * @param version statement version
 * @param locale language
 * @param text exact NFC text, one line
 */
public record AcknowledgementStatement(String code, int version, String locale, String text) {

  /** The only statement code. */
  public static final String CODE = "RECEIVED_AND_REVIEWED";

  /** The current statement version. */
  public static final int VERSION = 1;

  private static final Map<String, String> V1 =
      Map.of(
          "fr",
          "Je confirme avoir reçu ce contrat et en avoir pris connaissance tel qu’il est affiché."
              + " Cette confirmation n’est pas une signature électronique.",
          "en",
          "I confirm that I have received and reviewed this contract as displayed. This"
              + " confirmation is not an electronic signature.");

  /**
   * The statement's domain-separated digest.
   *
   * @return hex digest
   */
  public String sha256() {
    return ContractDigests.statement(code, version, locale, text);
  }

  /**
   * The current statement in a language.
   *
   * @param code code
   * @param version version
   * @param locale language
   * @return the statement, or empty when no such statement exists
   */
  public static Optional<AcknowledgementStatement> find(String code, int version, String locale) {
    if (!CODE.equals(code) || version != VERSION || !V1.containsKey(locale)) {
      return Optional.empty();
    }
    return Optional.of(new AcknowledgementStatement(CODE, VERSION, locale, V1.get(locale)));
  }

  /**
   * The current statement in French then English.
   *
   * @return both statements
   */
  public static List<AcknowledgementStatement> current() {
    return List.of(
        find(CODE, VERSION, "fr").orElseThrow(), find(CODE, VERSION, "en").orElseThrow());
  }
}
