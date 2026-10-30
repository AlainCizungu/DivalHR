package com.divalhr.core.payroll.domain;

import com.divalhr.core.payroll.domain.StatutoryRulesException.Reason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict, bounded JSON reader for rule packs (MVP-094B). It accepts the JSON grammar with integer
 * numbers only (at most 18 digits, no fraction, no exponent) and refuses duplicate object fields,
 * control characters in strings, trailing content, more than 8 levels of nesting and documents
 * longer than 65,536 characters. Objects become {@code Map<String, Object>} in document order,
 * arrays {@code List<Object>}, strings {@code String}, integers {@code Long}, booleans {@code
 * Boolean} and null {@code null}. Errors are {@link StatutoryRulesException}s that never echo the
 * input.
 */
final class StatutoryJson {

  static final int MAX_LENGTH = 65_536;
  static final int MAX_DEPTH = 8;
  private static final int MAX_DIGITS = 18;

  private final String text;
  private int pos;

  private StatutoryJson(String text) {
    this.text = text;
  }

  /**
   * Parses a document.
   *
   * @param text the JSON text
   * @return the value tree
   * @throws StatutoryRulesException when the text is not acceptable
   */
  static Object parse(String text) {
    if (text == null) {
      throw new StatutoryRulesException(Reason.MALFORMED_JSON);
    }
    if (text.length() > MAX_LENGTH) {
      throw new StatutoryRulesException(Reason.TOO_LARGE);
    }
    StatutoryJson reader = new StatutoryJson(text);
    Object value = reader.value(0);
    reader.skipWhitespace();
    if (reader.pos != text.length()) {
      throw malformed();
    }
    return value;
  }

  private static StatutoryRulesException malformed() {
    return new StatutoryRulesException(Reason.MALFORMED_JSON);
  }

  private void skipWhitespace() {
    while (pos < text.length()) {
      char c = text.charAt(pos);
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
        return;
      }
      pos++;
    }
  }

  private char peek() {
    if (pos >= text.length()) {
      throw malformed();
    }
    return text.charAt(pos);
  }

  private void expect(char expected) {
    if (peek() != expected) {
      throw malformed();
    }
    pos++;
  }

  private Object value(int depth) {
    skipWhitespace();
    char c = peek();
    if (c == '{') {
      return object(depth + 1);
    }
    if (c == '[') {
      return array(depth + 1);
    }
    if (c == '"') {
      return string();
    }
    if (c == 't') {
      literal("true");
      return Boolean.TRUE;
    }
    if (c == 'f') {
      literal("false");
      return Boolean.FALSE;
    }
    if (c == 'n') {
      literal("null");
      return null;
    }
    if (c == '-' || (c >= '0' && c <= '9')) {
      return number();
    }
    throw malformed();
  }

  private void literal(String word) {
    if (!text.startsWith(word, pos)) {
      throw malformed();
    }
    pos += word.length();
  }

  private Map<String, Object> object(int depth) {
    if (depth > MAX_DEPTH) {
      throw new StatutoryRulesException(Reason.TOO_DEEP);
    }
    expect('{');
    Map<String, Object> members = new LinkedHashMap<>();
    skipWhitespace();
    if (peek() == '}') {
      pos++;
      return members;
    }
    while (true) {
      skipWhitespace();
      String key = string();
      skipWhitespace();
      expect(':');
      Object member = value(depth);
      if (members.containsKey(key)) {
        throw new StatutoryRulesException(Reason.DUPLICATE_FIELD);
      }
      members.put(key, member);
      skipWhitespace();
      char c = peek();
      pos++;
      if (c == '}') {
        return members;
      }
      if (c != ',') {
        throw malformed();
      }
    }
  }

  private List<Object> array(int depth) {
    if (depth > MAX_DEPTH) {
      throw new StatutoryRulesException(Reason.TOO_DEEP);
    }
    expect('[');
    List<Object> elements = new ArrayList<>();
    skipWhitespace();
    if (peek() == ']') {
      pos++;
      return elements;
    }
    while (true) {
      elements.add(value(depth));
      skipWhitespace();
      char c = peek();
      pos++;
      if (c == ']') {
        return elements;
      }
      if (c != ',') {
        throw malformed();
      }
    }
  }

  private String string() {
    expect('"');
    StringBuilder out = new StringBuilder();
    while (true) {
      char c = peek();
      pos++;
      if (c == '"') {
        return out.toString();
      }
      if (c < 0x20) {
        throw malformed();
      }
      if (c != '\\') {
        out.append(c);
        continue;
      }
      char escape = peek();
      pos++;
      switch (escape) {
        case '"' -> out.append('"');
        case '\\' -> out.append('\\');
        case '/' -> out.append('/');
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'u' -> out.append(unicode());
        default -> throw malformed();
      }
    }
  }

  private char unicode() {
    if (pos + 4 > text.length()) {
      throw malformed();
    }
    int code = 0;
    for (int i = 0; i < 4; i++) {
      int digit = Character.digit(text.charAt(pos + i), 16);
      if (digit < 0) {
        throw malformed();
      }
      code = code * 16 + digit;
    }
    pos += 4;
    return (char) code;
  }

  private Long number() {
    int start = pos;
    if (peek() == '-') {
      pos++;
    }
    int digitsStart = pos;
    while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
      pos++;
    }
    int digits = pos - digitsStart;
    if (digits == 0) {
      throw malformed();
    }
    if (digits > 1 && text.charAt(digitsStart) == '0') {
      throw malformed();
    }
    if (pos < text.length()) {
      char next = text.charAt(pos);
      if (next == '.' || next == 'e' || next == 'E') {
        throw new StatutoryRulesException(Reason.INVALID_NUMBER);
      }
    }
    if (digits > MAX_DIGITS) {
      throw new StatutoryRulesException(Reason.INVALID_NUMBER);
    }
    return Long.valueOf(text.substring(start, pos));
  }
}
