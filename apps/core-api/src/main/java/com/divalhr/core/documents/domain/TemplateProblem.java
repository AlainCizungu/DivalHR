package com.divalhr.core.documents.domain;

/**
 * One problem of a template text (A30-2): a closed reason and a line number only, never the text.
 *
 * @param reason closed reason
 * @param line 1-based body line, or 0 for the title
 */
public record TemplateProblem(Reason reason, int line) {

  /** Closed reasons, in the contract's order. */
  public enum Reason {
    /** Nothing but whitespace. */
    EMPTY,
    /** Over the length limit. */
    TOO_LONG,
    /** A control character other than a line break. */
    CONTROL_CHARACTER,
    /** A format, private-use, unassigned or separator character (bidirectional overrides...). */
    UNSAFE_CHARACTER,
    /** A URI scheme. */
    URI_SCHEME,
    /** A protocol-relative or UNC path. */
    PROTOCOL_RELATIVE,
    /** A web address. */
    WEB_ADDRESS,
    /** Percent-encoding, character references or backslash escapes. */
    ENCODED_CONTENT,
    /** Markdown link, reference or image syntax, or an autolink. */
    MARKDOWN_LINK,
    /** HTML tags, comments, declarations or attributes. */
    HTML_MARKUP,
    /** A placeholder outside the allow-list. */
    UNKNOWN_PLACEHOLDER,
    /** A placeholder that is not exactly {@code {{name}}}. */
    MALFORMED_PLACEHOLDER,
    /** More than 200 placeholder occurrences. */
    TOO_MANY_PLACEHOLDERS,
    /** Double braces outside a placeholder (or in the title). */
    BRACES,
    /** A heading marker without text. */
    HEADING_EMPTY
  }
}
