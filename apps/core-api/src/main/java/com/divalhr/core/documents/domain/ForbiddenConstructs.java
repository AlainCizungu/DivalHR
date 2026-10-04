package com.divalhr.core.documents.domain;

import com.divalhr.core.documents.domain.TemplateProblem.Reason;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Grammar v1's forbidden constructs (MVP-030, A30-2): links, web addresses, URI schemes, encoded
 * content, Markdown link syntax and HTML markup are refused at draft validation, whatever their
 * case or obfuscation. Each line is checked on a <em>detection copy</em>: NFKC (fullwidth and other
 * compatibility forms folded), lower case, and zero-width characters and soft hyphens removed.
 * Schemes spelled with spaces between letters ({@code h t t p s :}) are matched too.
 *
 * <p>Ordinary legal punctuation stays allowed: « Article 2 : Durée », {@code 14:30}, {@code
 * 2026/27}, {@code et/ou}, comparisons with {@code <} and {@code >} followed by a space, {@code
 * Dupont & Fils}, « 10 % », {@code §}, {@code °}, {@code €}, parentheses and brackets. A future
 * need for web addresses is a new grammar version, never a silent change here.
 */
public final class ForbiddenConstructs {

  private ForbiddenConstructs() {}

  /** Not a letter, digit or scheme character: the start of a word. */
  private static final String WORD_START = "(?<![\\p{L}\\p{N}+.\\-])";

  /** Schemes refused whatever follows the colon. */
  private static final List<String> STRONG_SCHEMES =
      List.of("https", "http", "javascript", "vbscript", "mailto", "ftp");

  /**
   * Schemes that are also ordinary words (« data : … », "Personal data: the …"): refused only when
   * the colon is directly followed by a non-space character, as in {@code data:text/html}.
   */
  private static final List<String> WORD_SCHEMES =
      List.of("data", "file", "tel", "blob", "wss", "ws");

  private static final Pattern STRONG = scheme(STRONG_SCHEMES, true, "");
  private static final Pattern WORD = scheme(WORD_SCHEMES, false, "(?=\\S)");

  /** Any scheme-like token directly followed by a slash ({@code c:/}, {@code x-y:/}). */
  private static final Pattern GENERIC_SCHEME =
      Pattern.compile(WORD_START + "\\p{L}[\\p{L}\\p{N}+.\\-]*:/");

  private static final Pattern PROTOCOL_RELATIVE =
      Pattern.compile("(?<![:\\p{L}\\p{N}/])//[\\p{L}\\p{N}]|\\\\\\\\[\\p{L}\\p{N}]");

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
      Pattern.compile(
          "%[0-9a-f]{2}|&#x?[0-9a-f]+;|&[a-z][a-z0-9]{1,31};|\\\\[ux][0-9a-f{]|\\\\0[0-7]");

  private static final Pattern MARKDOWN = Pattern.compile("\\]\\(|^\\s*\\[[^\\]]+]\\s*:|!\\[");

  private static final Pattern HTML =
      Pattern.compile(
          "<[\\p{L}/!?]"
              + "|(?<![\\p{L}\\p{N}])on[a-z]{2,}\\s*="
              + "|(?<![\\p{L}\\p{N}])(?:style|src|href|srcset|formaction|xlink:href)\\s*=");

  /**
   * The first forbidden construct of a line, in the contract's reason order.
   *
   * @param line one NFC line, without its line break
   * @return the reason, or empty when the line is allowed
   */
  public static Optional<Reason> find(String line) {
    String probe = detectionCopy(line);
    if (STRONG.matcher(probe).find()
        || WORD.matcher(probe).find()
        || GENERIC_SCHEME.matcher(probe).find()) {
      return Optional.of(Reason.URI_SCHEME);
    }
    if (PROTOCOL_RELATIVE.matcher(probe).find()) {
      return Optional.of(Reason.PROTOCOL_RELATIVE);
    }
    if (WEB_ADDRESS.matcher(probe).find()) {
      return Optional.of(Reason.WEB_ADDRESS);
    }
    if (ENCODED.matcher(probe).find()) {
      return Optional.of(Reason.ENCODED_CONTENT);
    }
    if (MARKDOWN.matcher(probe).find()) {
      return Optional.of(Reason.MARKDOWN_LINK);
    }
    if (HTML.matcher(probe).find()) {
      return Optional.of(Reason.HTML_MARKUP);
    }
    return Optional.empty();
  }

  /**
   * The detection copy of a line: NFKC, lower case (root locale), without zero-width characters,
   * word joiners, byte-order marks or soft hyphens.
   *
   * @param line original line
   * @return the copy patterns are matched against
   */
  static String detectionCopy(String line) {
    String folded = Normalizer.normalize(line, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    return folded.replaceAll("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\u00AD]", "");
  }

  private static Pattern scheme(List<String> names, boolean spaced, String after) {
    StringBuilder alternatives = new StringBuilder();
    for (String name : names) {
      if (!alternatives.isEmpty()) {
        alternatives.append('|');
      }
      if (spaced) {
        // h t t p s :  — letters separated by whitespace.
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
          if (i > 0) {
            letters.append("\\s*");
          }
          letters.append(name.charAt(i));
        }
        alternatives.append(letters);
      } else {
        alternatives.append(name);
      }
    }
    return Pattern.compile(
        WORD_START + "(?:" + alternatives + ")" + (spaced ? "\\s*" : "") + ":" + after);
  }
}
