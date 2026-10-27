package com.divalhr.core.operations.application;

import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The restricted plain text of a task (MVP-080, D80-2): title, instructions, completion note and
 * cancellation explanation. One line of NFC text, trimmed, counted in code points, with no control,
 * format, line-separator, private-use or noncharacter code point (the explicit fixed list of {@link
 * #FORBIDDEN}, so it does not drift with the JVM's Unicode version), no markup ({@code <}, {@code
 * >}, {@code \}), no URL, web address or URI scheme and no encoded content. The detection also runs
 * on the NFKC-folded, lower-cased copy, so fullwidth and compatibility forms do not slip through.
 *
 * <p>The database function {@code operations.task_text_valid} (V41) is a strict subset of what this
 * class refuses: a value the database would reject is always rejected here first, so a constraint
 * violation of this grammar is never reachable from the API. A user-entered value is never echoed
 * in an error, a log line or a metric.
 */
public final class TaskText {

  /** Inclusive code point ranges trimmed from both ends. */
  private static final int[][] TRIM = {
    {0x0009, 0x000D}, {0x0020, 0x0020}, {0x0085, 0x0085}, {0x00A0, 0x00A0}, {0x1680, 0x1680},
    {0x2000, 0x200A}, {0x2028, 0x2029}, {0x202F, 0x202F}, {0x205F, 0x205F}, {0x3000, 0x3000},
  };

  /** Inclusive code point ranges a task text never contains. */
  private static final int[][] FORBIDDEN = {
    {0x0000, 0x001F},
    {0x007F, 0x009F},
    {0x00AD, 0x00AD},
    {0x0600, 0x0605},
    {0x061C, 0x061C},
    {0x06DD, 0x06DD},
    {0x070F, 0x070F},
    {0x0890, 0x0891},
    {0x08E2, 0x08E2},
    {0x180E, 0x180E},
    {0x200B, 0x200F},
    {0x202A, 0x202E},
    {0x2060, 0x2064},
    {0x2066, 0x206F},
    {0xFEFF, 0xFEFF},
    {0xFFF9, 0xFFFB},
    {0x110BD, 0x110BD},
    {0x110CD, 0x110CD},
    {0x13430, 0x1343F},
    {0x1BCA0, 0x1BCA3},
    {0x1D173, 0x1D17A},
    {0xE0001, 0xE0001},
    {0xE0020, 0xE007F},
    {0x2028, 0x2029},
    {0xD800, 0xDFFF},
    {0xE000, 0xF8FF},
    {0xF0000, 0xFFFFD},
    {0x100000, 0x10FFFD},
    {0xFDD0, 0xFDEF},
    {0xFFFE, 0xFFFF},
    {0x1FFFE, 0x1FFFF},
    {0x2FFFE, 0x2FFFF},
    {0x3FFFE, 0x3FFFF},
    {0x4FFFE, 0x4FFFF},
    {0x5FFFE, 0x5FFFF},
    {0x6FFFE, 0x6FFFF},
    {0x7FFFE, 0x7FFFF},
    {0x8FFFE, 0x8FFFF},
    {0x9FFFE, 0x9FFFF},
    {0xAFFFE, 0xAFFFF},
    {0xBFFFE, 0xBFFFF},
    {0xCFFFE, 0xCFFFF},
    {0xDFFFE, 0xDFFFF},
    {0xEFFFE, 0xEFFFF},
    {0xFFFFE, 0xFFFFF},
    {0x10FFFE, 0x10FFFF},
  };

  /** What the database refuses, applied to the value as written (a subset of the rest). */
  private static final Pattern DATABASE_MIRROR =
      Pattern.compile(
          "https?:|www\\.|://|%[0-9a-f]{2}|&#|&[a-z][a-z0-9]{1,31};", Pattern.CASE_INSENSITIVE);

  private static final String WORD_START = "(?<![\\p{L}\\p{N}+.\\-])";

  private static final List<String> STRONG_SCHEMES =
      List.of("https", "http", "javascript", "vbscript", "mailto", "ftp");

  private static final Pattern STRONG = spaced(STRONG_SCHEMES);

  private static final Pattern WORD =
      Pattern.compile(WORD_START + "(?:data|file|tel|blob|wss|ws):(?=\\S)");

  private static final Pattern GENERIC_SCHEME =
      Pattern.compile(WORD_START + "\\p{L}[\\p{L}\\p{N}+.\\-]*:/");

  private static final Pattern PROTOCOL_RELATIVE =
      Pattern.compile("(?<![:\\p{L}\\p{N}/])//[\\p{L}\\p{N}]");

  private static final String TLD =
      "(?:com|org|net|edu|gov|int|info|biz|io|app|dev|co|cd|cg|fr|be|ch|ca|us|uk|eu|africa"
          + "|rw|bi|ug|ke|tz|za|ma|sn|ci|cm|ga|ao)";

  private static final Pattern WEB_ADDRESS =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])www\\.[\\p{L}\\p{N}]"
              + "|(?<![\\p{L}\\p{N}@.\\-])[\\p{L}\\p{N}\\-]+(?:\\.[\\p{L}\\p{N}\\-]+)*\\."
              + TLD
              + "/");

  private static final Pattern ENCODED =
      Pattern.compile("%[0-9a-f]{2}|&#x?[0-9a-f]+;|&[a-z][a-z0-9]{1,31};|\\\\[ux][0-9a-f{]");

  private static final Pattern MARKDOWN = Pattern.compile("\\]\\(|^\\s*\\[[^\\]]+]\\s*:|!\\[");

  private static final Pattern ATTRIBUTE =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])(?:on[a-z]{2,}|style|src|href|srcset|formaction|xlink:href)\\s*=");

  private TaskText() {}

  private static Pattern spaced(List<String> names) {
    StringBuilder alternatives = new StringBuilder();
    for (String name : names) {
      if (!alternatives.isEmpty()) {
        alternatives.append('|');
      }
      for (int i = 0; i < name.length(); i++) {
        if (i > 0) {
          alternatives.append("\\s*");
        }
        alternatives.append(name.charAt(i));
      }
    }
    return Pattern.compile(WORD_START + "(?:" + alternatives + ")\\s*:");
  }

  private static boolean in(int[][] ranges, int codePoint) {
    for (int[] range : ranges) {
      if (codePoint >= range[0] && codePoint <= range[1]) {
        return true;
      }
    }
    return false;
  }

  /**
   * The stored form of submitted text: NFC-normalized, then the trim set removed from both ends.
   *
   * @param text submitted text
   * @return the stored form (possibly empty)
   */
  public static String normalize(String text) {
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
    int start = 0;
    int end = normalized.length();
    while (start < end && in(TRIM, normalized.codePointAt(start))) {
      start += Character.charCount(normalized.codePointAt(start));
    }
    while (end > start && in(TRIM, normalized.codePointBefore(end))) {
      end -= Character.charCount(normalized.codePointBefore(end));
    }
    return normalized.substring(start, end);
  }

  /**
   * Whether the text has a forbidden code point (unpaired surrogates included).
   *
   * @param text text
   * @return true when one is present
   */
  static boolean hasForbiddenCodePoint(String text) {
    return text.codePoints().anyMatch(codePoint -> in(FORBIDDEN, codePoint));
  }

  /**
   * Whether the text holds markup, a link, a web address, a URI scheme or encoded content.
   *
   * @param text normalized text
   * @return true when a forbidden construct is present
   */
  static boolean hasForbiddenConstruct(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    return constructIn(text, lower) || constructIn(folded, folded);
  }

  private static boolean constructIn(String original, String probe) {
    if (original.indexOf('<') >= 0
        || original.indexOf('>') >= 0
        || original.indexOf('\\') >= 0
        || probe.indexOf('<') >= 0
        || probe.indexOf('>') >= 0
        || probe.indexOf('\\') >= 0) {
      return true;
    }
    return DATABASE_MIRROR.matcher(probe).find()
        || STRONG.matcher(probe).find()
        || WORD.matcher(probe).find()
        || GENERIC_SCHEME.matcher(probe).find()
        || PROTOCOL_RELATIVE.matcher(probe).find()
        || WEB_ADDRESS.matcher(probe).find()
        || ENCODED.matcher(probe).find()
        || MARKDOWN.matcher(probe).find()
        || ATTRIBUTE.matcher(probe).find();
  }

  /**
   * The grammar on a stored value, as the application decides it.
   *
   * @param stored stored form
   * @param min fewest code points
   * @param max most code points
   * @return whether the value is valid
   */
  public static boolean isValid(String stored, int min, int max) {
    if (stored == null || !Normalizer.isNormalized(stored, Normalizer.Form.NFC)) {
      return false;
    }
    int length = stored.codePointCount(0, stored.length());
    return length >= min
        && length <= max
        && !stored.isEmpty()
        && !in(TRIM, stored.codePointAt(0))
        && !in(TRIM, stored.codePointBefore(stored.length()))
        && !hasForbiddenCodePoint(stored)
        && !hasForbiddenConstruct(stored);
  }

  /**
   * Validates one submitted text field.
   *
   * @param errors collected problems (a value is never echoed)
   * @param field field name
   * @param raw the raw JSON value
   * @param min fewest code points
   * @param max most code points
   * @param required whether the field must have a value
   * @return the stored form, or {@code null} when absent or invalid
   */
  public static String parse(
      FieldErrors errors, String field, Object raw, int min, int max, boolean required) {
    if (raw == null) {
      if (required) {
        errors.add(field, Constraint.REQUIRED);
      }
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    String stored = normalize(text);
    if (stored.isEmpty()) {
      if (required) {
        errors.add(field, Constraint.REQUIRED);
      }
      return null;
    }
    if (hasForbiddenCodePoint(stored) || hasForbiddenConstruct(stored)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    int length = stored.codePointCount(0, stored.length());
    if (length < min || length > max) {
      errors.add(field, Constraint.LENGTH);
      return null;
    }
    return stored;
  }
}
