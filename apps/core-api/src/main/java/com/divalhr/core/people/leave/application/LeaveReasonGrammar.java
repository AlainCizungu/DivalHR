package com.divalhr.core.people.leave.application;

import java.text.Normalizer;

/**
 * The decision-reason grammar, version 1 (MVP-041B, D41B-2, R90-2): one exact definition that the
 * application and PostgreSQL ({@code people.leave_reason_valid}, V20) enforce identically. It is an
 * explicit, fixed list of code points, so it does not drift with the Unicode version of the JVM or
 * of the database. A stored reason is valid when:
 *
 * <ol>
 *   <li>it is NFC-normalized;
 *   <li>its first and last code points are not in the trim set {@link #TRIM} (U+0009–U+000D,
 *       U+0020, U+0085, U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000);
 *   <li>it has 2 to 500 code points (a supplementary character counts once);
 *   <li>it contains no code point of the forbidden set {@link #FORBIDDEN}: C0 and C1 controls
 *       (U+0000–U+001F, U+007F–U+009F), the format characters of Unicode 15.0 (general category Cf:
 *       soft hyphen, zero-width, joiners, bidirectional controls, tags…), U+2028 and U+2029,
 *       private-use code points, noncharacters and surrogates.
 * </ol>
 *
 * <p>Unassigned code points are not refused: whether a code point is assigned depends on the
 * Unicode version, which would make the two layers disagree. Policy names keep their own, stricter
 * grammar ({@code LeavePolicyCommand}).
 */
public final class LeaveReasonGrammar {

  /** Fewest code points of a reason. */
  public static final int MIN = 2;

  /** Most code points of a reason. */
  public static final int MAX = 500;

  /** Inclusive code point ranges trimmed from both ends and refused there in a stored reason. */
  static final int[][] TRIM = {
    {0x0009, 0x000D},
    {0x0020, 0x0020},
    {0x0085, 0x0085},
    {0x00A0, 0x00A0},
    {0x1680, 0x1680},
    {0x2000, 0x200A},
    {0x2028, 0x2029},
    {0x202F, 0x202F},
    {0x205F, 0x205F},
    {0x3000, 0x3000},
  };

  /** Inclusive code point ranges a reason never contains (the V20 SQL lists the same ranges). */
  static final int[][] FORBIDDEN = {
    // C0 and C1 controls.
    {0x0000, 0x001F},
    {0x007F, 0x009F},
    // Format characters (Cf) of Unicode 15.0.
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
    // Line and paragraph separators.
    {0x2028, 0x2029},
    // Surrogates (only reachable from Java text).
    {0xD800, 0xDFFF},
    // Private use.
    {0xE000, 0xF8FF},
    {0xF0000, 0xFFFFD},
    {0x100000, 0x10FFFD},
    // Noncharacters.
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

  private LeaveReasonGrammar() {}

  private static boolean in(int[][] ranges, int codePoint) {
    for (int[] range : ranges) {
      if (codePoint >= range[0] && codePoint <= range[1]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a code point is in the trim set.
   *
   * @param codePoint code point
   * @return whether it is trimmed
   */
  static boolean trimmed(int codePoint) {
    return in(TRIM, codePoint);
  }

  /**
   * Whether a code point is forbidden anywhere in a reason.
   *
   * @param codePoint code point
   * @return whether it is forbidden
   */
  static boolean forbidden(int codePoint) {
    return in(FORBIDDEN, codePoint);
  }

  /**
   * The stored form of submitted text: NFC-normalized, then the trim set removed from both ends.
   * Unpaired surrogates are kept, so {@link #isValid} refuses them.
   *
   * @param text submitted text
   * @return the stored form (possibly empty)
   */
  public static String normalize(String text) {
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
    int start = 0;
    int end = normalized.length();
    while (start < end && trimmed(normalized.codePointAt(start))) {
      start += Character.charCount(normalized.codePointAt(start));
    }
    while (end > start && trimmed(normalized.codePointBefore(end))) {
      end -= Character.charCount(normalized.codePointBefore(end));
    }
    return normalized.substring(start, end);
  }

  /**
   * Whether no code point of the text is forbidden (unpaired surrogates included).
   *
   * @param text text
   * @return whether every code point is allowed
   */
  static boolean allowedCodePoints(String text) {
    return text.codePoints().noneMatch(LeaveReasonGrammar::forbidden);
  }

  /**
   * The grammar on a stored reason: exactly what {@code people.leave_reason_valid} decides.
   *
   * @param reason stored reason
   * @return whether it is valid
   */
  public static boolean isValid(String reason) {
    if (reason == null || !Normalizer.isNormalized(reason, Normalizer.Form.NFC)) {
      return false;
    }
    int length = reason.codePointCount(0, reason.length());
    return length >= MIN
        && length <= MAX
        && !trimmed(reason.codePointAt(0))
        && !trimmed(reason.codePointBefore(reason.length()))
        && allowedCodePoints(reason);
  }
}
