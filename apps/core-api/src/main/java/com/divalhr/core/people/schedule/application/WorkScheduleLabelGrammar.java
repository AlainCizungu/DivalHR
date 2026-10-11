package com.divalhr.core.people.schedule.application;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Closed plain-text grammar version 1 of a slot label (the V25 SQL function {@code
 * people.work_schedule_label_valid} is the same rule): NFC, 2 to 60 characters, Latin letters
 * (ASCII, Latin-1 and Latin Extended-A/B) with combining accents, digits, single spaces and {@code
 * . , ' ’ - ( ) / & # °}; starting and ending with a letter, digit or closing parenthesis (a final
 * period is allowed); no dot directly before a letter. Markup, control characters, links and e-mail
 * addresses cannot be written ({@code < > : @} are outside the grammar).
 *
 * <p>Normalization, before the check: NFC, surrounding white space removed and runs of spaces
 * collapsed to one.
 */
public final class WorkScheduleLabelGrammar {

  /** Fewest characters of a label. */
  public static final int MIN = 2;

  /** Most characters of a label. */
  public static final int MAX = 60;

  private static final String LETTER = "A-Za-z0-9\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u024F";
  private static final String MARK = "\\u0300-\\u036F";
  private static final Pattern SHAPE =
      Pattern.compile(
          "^["
              + LETTER
              + "](["
              + LETTER
              + MARK
              + " .,'\\u2019()/&#\\u00B0-]*["
              + LETTER
              + MARK
              + ").])?$");
  private static final Pattern DOT_LETTER =
      Pattern.compile("\\.[A-Za-z\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u024F]");
  private static final Pattern SPACES = Pattern.compile(" {2,}");

  private WorkScheduleLabelGrammar() {}

  /**
   * Normalizes a label and checks it.
   *
   * @param raw submitted value
   * @return the normalized label, or {@code null} when it is not a valid label
   */
  public static String normalize(Object raw) {
    if (!(raw instanceof String text)) {
      return null;
    }
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFC).strip();
    normalized = SPACES.matcher(normalized).replaceAll(" ");
    return valid(normalized) ? normalized : null;
  }

  /**
   * Whether a stored label follows the grammar.
   *
   * @param label label
   * @return true when valid
   */
  public static boolean valid(String label) {
    if (label == null || !Normalizer.isNormalized(label, Normalizer.Form.NFC)) {
      return false;
    }
    int length = label.codePointCount(0, label.length());
    return length >= MIN
        && length <= MAX
        && SHAPE.matcher(label).matches()
        && !label.contains("  ")
        && !DOT_LETTER.matcher(label).find();
  }
}
