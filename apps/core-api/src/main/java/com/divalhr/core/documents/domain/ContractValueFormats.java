package com.divalhr.core.documents.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Server-owned value tables of renderer v1 (MVP-030): contract-type labels and long dates in French
 * and English. Month names and patterns are fixed here, never taken from the JDK's locale data, so
 * snapshots and their digests do not change when the JDK is upgraded (A30-4).
 */
public final class ContractValueFormats {

  /** Renderer version (A30-4: pinned in the database). */
  public static final int RENDERER_VERSION = 1;

  private static final List<String> FR_MONTHS =
      List.of(
          "janvier",
          "février",
          "mars",
          "avril",
          "mai",
          "juin",
          "juillet",
          "août",
          "septembre",
          "octobre",
          "novembre",
          "décembre");

  private static final List<String> EN_MONTHS =
      List.of(
          "January",
          "February",
          "March",
          "April",
          "May",
          "June",
          "July",
          "August",
          "September",
          "October",
          "November",
          "December");

  private static final Map<String, Map<String, String>> TYPES =
      Map.of(
          "fr",
          Map.of(
              "PERMANENT", "Durée indéterminée",
              "FIXED_TERM", "Durée déterminée",
              "APPRENTICESHIP", "Apprentissage",
              "INTERNSHIP", "Stage",
              "DAILY", "Travail journalier"),
          "en",
          Map.of(
              "PERMANENT", "Permanent",
              "FIXED_TERM", "Fixed term",
              "APPRENTICESHIP", "Apprenticeship",
              "INTERNSHIP", "Internship",
              "DAILY", "Daily work"));

  private ContractValueFormats() {}

  /**
   * A long date: « 1er mars 2026 », « 15 août 2026 », "March 1, 2026".
   *
   * @param locale {@code fr} or {@code en}
   * @param date the date
   * @return the text
   */
  public static String date(String locale, LocalDate date) {
    int day = date.getDayOfMonth();
    int month = date.getMonthValue() - 1;
    if ("fr".equals(locale)) {
      return (day == 1 ? "1er" : Integer.toString(day))
          + " "
          + FR_MONTHS.get(month)
          + " "
          + date.getYear();
    }
    if ("en".equals(locale)) {
      return EN_MONTHS.get(month) + " " + day + ", " + date.getYear();
    }
    throw new IllegalArgumentException("unsupported locale");
  }

  /**
   * The contract-type label.
   *
   * @param locale {@code fr} or {@code en}
   * @param type closed type code
   * @return the label
   */
  public static String type(String locale, String type) {
    Map<String, String> labels = TYPES.get(locale);
    if (labels == null || !labels.containsKey(type)) {
      throw new IllegalArgumentException("unsupported locale or type");
    }
    return labels.get(type);
  }
}
