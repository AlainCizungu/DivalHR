package com.divalhr.core.people.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The accent- and case-insensitive name key used by employee search (MVP-021, H16). It is derived
 * from the names, so it is Confidential like them. The same normalization fills the key for every
 * write (import, backfill V14_1, future name changes) and splits search queries, so both sides
 * always agree; golden vectors pin it ({@code search-key-golden.tsv}).
 *
 * <p>Normalization: NFD, combining marks removed, apostrophes ({@code '}, {@code ’}) and periods
 * removed, hyphens and whitespace turned into single spaces, lower case ({@link Locale#ROOT}). The
 * key is the given names then the family name, with one space before and after every word, so a
 * word-prefix match is {@code key LIKE '% ' || word || '%'}.
 */
public final class EmployeeSearchKey {

  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern REMOVED = Pattern.compile("['’.]");
  private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-]+");

  private EmployeeSearchKey() {}

  /**
   * The stored key of an employee.
   *
   * @param givenNames given names
   * @param familyName family name
   * @return the key, with a space before and after every word
   */
  public static String of(String givenNames, String familyName) {
    List<String> words = new ArrayList<>(words(givenNames));
    words.addAll(words(familyName));
    return " " + String.join(" ", words) + " ";
  }

  /**
   * Normalized words of a text (names or a search query).
   *
   * @param text text
   * @return its words, possibly empty
   */
  public static List<String> words(String text) {
    String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
    String plain = REMOVED.matcher(MARKS.matcher(decomposed).replaceAll("")).replaceAll("");
    String spaced = SEPARATORS.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    return spaced.isEmpty() ? List.of() : List.of(spaced.split(" "));
  }
}
