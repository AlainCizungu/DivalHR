package com.divalhr.core.people.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * R20-1: the character classes of V13's {@code people.person_name_valid} accept exactly the
 * characters {@link RowValidator#NAME} accepts, for every code point the running JVM's Unicode data
 * assigns; ASCII is compared too. Code points the JVM still treats as unassigned may be accepted by
 * the database only (the application rejects them, as they are not letters).
 */
class EmployeeNameGrammarDriftTest {

  /** One inclusive code-point range. */
  private record Range(int from, int to) {}

  private static final Pattern LITERAL = Pattern.compile("'((?:[^']|'')*)'");

  private static String migration() throws IOException {
    return new ClassPathResource("db/migration/V13__employee_import.sql")
        .getContentAsString(StandardCharsets.UTF_8);
  }

  /** The concatenated regular expression of people.person_name_valid, as PostgreSQL sees it. */
  private static String functionRegex() throws IOException {
    String sql = migration();
    int start = sql.indexOf("AND name ~ ('^' ||");
    int end = sql.indexOf("|| '*$')", start);
    assertThat(start).isPositive();
    assertThat(end).isGreaterThan(start);
    StringBuilder regex = new StringBuilder();
    for (String line : sql.substring(start + "AND name ~ (".length(), end).split("\n")) {
      String code = line.strip();
      if (code.startsWith("--")) {
        continue;
      }
      Matcher literal = LITERAL.matcher(code);
      while (literal.find()) {
        regex.append(literal.group(1).replace("''", "'"));
      }
    }
    return regex.toString();
  }

  /** Splits "^[first][rest]" into the two bracket bodies. */
  private static List<String> classes(String regex) {
    assertThat(regex).startsWith("^[").endsWith("]");
    int split = regex.indexOf("][");
    assertThat(split).isPositive();
    return List.of(regex.substring(2, split), regex.substring(split + 2, regex.length() - 1));
  }

  private static int[] next(String body, int index) {
    char c = body.charAt(index);
    if (c != '\\') {
      int cp = body.codePointAt(index);
      return new int[] {cp, index + Character.charCount(cp)};
    }
    char kind = body.charAt(index + 1);
    if (kind == 'u') {
      return new int[] {Integer.parseInt(body.substring(index + 2, index + 6), 16), index + 6};
    }
    if (kind == 'U') {
      return new int[] {Integer.parseInt(body.substring(index + 2, index + 10), 16), index + 10};
    }
    return new int[] {kind, index + 2};
  }

  private static List<Range> ranges(String body) {
    List<Range> ranges = new ArrayList<>();
    int index = 0;
    while (index < body.length()) {
      int[] from = next(body, index);
      index = from[1];
      if (index < body.length() - 1 && body.charAt(index) == '-') {
        int[] to = next(body, index + 1);
        ranges.add(new Range(from[0], to[0]));
        index = to[1];
      } else {
        ranges.add(new Range(from[0], from[0]));
      }
    }
    return ranges;
  }

  private static boolean contains(List<Range> ranges, int codePoint) {
    for (Range range : ranges) {
      if (codePoint >= range.from() && codePoint <= range.to()) {
        return true;
      }
    }
    return false;
  }

  @Test
  void theDatabaseNameClassesMatchTheApplicationGrammarForEveryAssignedCodePoint()
      throws IOException {
    List<String> bodies = classes(functionRegex());
    List<Range> first = ranges(bodies.get(0));
    List<Range> rest = ranges(bodies.get(1));
    List<String> differences = new ArrayList<>();
    for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
      if (Character.getType(codePoint) == Character.UNASSIGNED
          || Character.getType(codePoint) == Character.SURROGATE) {
        continue;
      }
      String text = new String(Character.toChars(codePoint));
      boolean appFirst = RowValidator.NAME.matcher(text).matches();
      boolean appRest = RowValidator.NAME.matcher("A" + text).matches();
      if (appFirst != contains(first, codePoint)) {
        differences.add("first U+" + Integer.toHexString(codePoint));
      }
      if (appRest != contains(rest, codePoint)) {
        differences.add("rest U+" + Integer.toHexString(codePoint));
      }
    }
    assertThat(differences).isEmpty();
  }

  @Test
  void asciiIsExactlyLettersFirstThenLettersSpaceApostrophePeriodHyphen() throws IOException {
    List<String> bodies = classes(functionRegex());
    List<Range> first = ranges(bodies.get(0));
    List<Range> rest = ranges(bodies.get(1));
    for (int codePoint = 0; codePoint < 0x80; codePoint++) {
      boolean letter = Character.isLetter(codePoint);
      assertThat(contains(first, codePoint)).as("first %s", codePoint).isEqualTo(letter);
      assertThat(contains(rest, codePoint))
          .as("rest %s", codePoint)
          .isEqualTo(letter || " '.-".indexOf(codePoint) >= 0);
    }
  }
}
