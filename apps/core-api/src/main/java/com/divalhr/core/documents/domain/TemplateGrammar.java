package com.divalhr.core.documents.domain;

import com.divalhr.core.documents.domain.TemplateProblem.Reason;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grammar v1 of contract templates (MVP-030): line-based, plain text, never markup.
 *
 * <ul>
 *   <li>{@code # text}: heading level 1; {@code ## text}: heading level 2;
 *   <li>{@code - text}: list item (consecutive items form one list);
 *   <li>a blank line ends a paragraph or list; other consecutive lines join into one paragraph with
 *       one space;
 *   <li>{@code {{name}}}: an allow-listed placeholder, inline only (never in the title).
 * </ul>
 *
 * <p>Text is NFC with LF line breaks. Control characters (other than LF), format characters
 * (bidirectional overrides, zero-width), private-use, unassigned, surrogate and line or paragraph
 * separator characters are refused, as are the {@link ForbiddenConstructs} (A30-2). Limits: title
 * 1–160 code points, body 1–40,000 code points, at most 200 placeholder occurrences. Problems carry
 * a closed reason and a line number, never the text; at most {@value #MAX_PROBLEMS} are returned.
 */
public final class TemplateGrammar {

  /** Grammar version (A30-4: pinned in the database). */
  public static final int VERSION = 1;

  /** Longest title, in code points. */
  public static final int MAX_TITLE = 160;

  /** Longest body, in code points. */
  public static final int MAX_BODY = 40_000;

  /** Most placeholder occurrences. */
  public static final int MAX_PLACEHOLDERS = 200;

  /** Most problems reported. */
  public static final int MAX_PROBLEMS = 50;

  private static final Pattern TOKEN = Pattern.compile("\\{\\{([^{}]*)}}");

  private TemplateGrammar() {}

  /** Kinds of block. */
  public enum BlockType {
    /** Heading level 1. */
    H1("h1"),
    /** Heading level 2. */
    H2("h2"),
    /** Paragraph. */
    P("p"),
    /** List item. */
    LI("li");

    private final String code;

    BlockType(String code) {
      this.code = code;
    }

    /**
     * The canonical and API code.
     *
     * @return {@code h1}, {@code h2}, {@code p} or {@code li}
     */
    public String code() {
      return code;
    }
  }

  /**
   * Inline content: literal text or a placeholder.
   *
   * @param text literal text, or {@code null}
   * @param placeholder placeholder, or {@code null}
   */
  public record Segment(String text, ContractPlaceholder placeholder) {}

  /**
   * One block of a parsed template.
   *
   * @param type kind
   * @param segments inline content
   */
  public record Block(BlockType type, List<Segment> segments) {

    /** Copies the segments. */
    public Block {
      segments = List.copyOf(segments);
    }
  }

  /**
   * A parsed template.
   *
   * @param title normalized title
   * @param blocks blocks, in order
   * @param placeholders distinct placeholders used
   * @param problems problems found (empty when valid)
   */
  public record Parsed(
      String title,
      List<Block> blocks,
      Set<ContractPlaceholder> placeholders,
      List<TemplateProblem> problems) {

    /** Copies the collections. */
    public Parsed {
      blocks = List.copyOf(blocks);
      placeholders = Set.copyOf(placeholders);
      problems = List.copyOf(problems);
    }

    /**
     * Whether the template has no problem.
     *
     * @return true when valid
     */
    public boolean valid() {
      return problems.isEmpty();
    }
  }

  /**
   * NFC with LF line breaks (CRLF and lone CR become LF), as stored and digested.
   *
   * @param text raw text
   * @return normalized text
   */
  public static String normalize(String text) {
    return Normalizer.normalize(text, Normalizer.Form.NFC)
        .replace("\r\n", "\n")
        .replace('\r', '\n');
  }

  /**
   * Parses and validates a title and body (both normalized first).
   *
   * @param rawTitle title
   * @param rawBody body
   * @return the parsed template with its problems
   */
  public static Parsed parse(String rawTitle, String rawBody) {
    List<TemplateProblem> problems = new ArrayList<>();
    String title = normalize(rawTitle == null ? "" : rawTitle);
    String body = normalize(rawBody == null ? "" : rawBody);
    EnumSet<ContractPlaceholder> used = EnumSet.noneOf(ContractPlaceholder.class);

    // Title (line 0): plain text only.
    if (title.isBlank()) {
      add(problems, Reason.EMPTY, 0);
    } else if (title.codePointCount(0, title.length()) > MAX_TITLE || title.indexOf('\n') >= 0) {
      add(problems, title.indexOf('\n') >= 0 ? Reason.CONTROL_CHARACTER : Reason.TOO_LONG, 0);
    } else {
      characters(title, 0, problems);
      ForbiddenConstructs.find(title).ifPresent(reason -> add(problems, reason, 0));
      if (title.contains("{{") || title.contains("}}")) {
        add(problems, Reason.BRACES, 0);
      }
    }

    if (body.isBlank()) {
      add(problems, Reason.EMPTY, 1);
      return new Parsed(title.strip(), List.of(), used, problems);
    }
    if (body.codePointCount(0, body.length()) > MAX_BODY) {
      add(problems, Reason.TOO_LONG, 1);
      return new Parsed(title.strip(), List.of(), used, problems);
    }

    List<BlockType> types = new ArrayList<>();
    List<List<Segment>> contents = new ArrayList<>();
    List<Segment> paragraph = null;
    int occurrences = 0;
    String[] lines = body.split("\n", -1);
    for (int index = 0; index < lines.length; index++) {
      int number = index + 1;
      String line = lines[index];
      characters(line, number, problems);
      ForbiddenConstructs.find(line).ifPresent(reason -> add(problems, reason, number));
      String trimmed = line.strip();
      if (trimmed.isEmpty()) {
        paragraph = null;
        continue;
      }
      BlockType type;
      String content;
      if (trimmed.startsWith("## ") || trimmed.equals("##")) {
        type = BlockType.H2;
        content = trimmed.substring(2).strip();
      } else if (trimmed.startsWith("# ") || trimmed.equals("#")) {
        type = BlockType.H1;
        content = trimmed.substring(1).strip();
      } else if (trimmed.startsWith("- ") || trimmed.equals("-")) {
        type = BlockType.LI;
        content = trimmed.substring(1).strip();
      } else {
        type = BlockType.P;
        content = trimmed;
      }
      if (content.isEmpty()) {
        add(problems, type == BlockType.LI ? Reason.EMPTY : Reason.HEADING_EMPTY, number);
        paragraph = null;
        continue;
      }
      List<Segment> segments = new ArrayList<>();
      occurrences += inline(content, number, segments, used, problems);
      if (type == BlockType.P && paragraph != null) {
        paragraph.add(new Segment(" ", null));
        paragraph.addAll(segments);
        continue;
      }
      List<Segment> target = new ArrayList<>(segments);
      types.add(type);
      contents.add(target);
      paragraph = type == BlockType.P ? target : null;
    }
    if (occurrences > MAX_PLACEHOLDERS) {
      add(problems, Reason.TOO_MANY_PLACEHOLDERS, 1);
    }
    List<Block> frozen = new ArrayList<>(types.size());
    for (int i = 0; i < types.size(); i++) {
      frozen.add(new Block(types.get(i), merge(contents.get(i))));
    }
    return new Parsed(title.strip(), frozen, used, problems);
  }

  /** Splits a line's content into literal text and placeholders; returns the occurrence count. */
  private static int inline(
      String content,
      int line,
      List<Segment> segments,
      Set<ContractPlaceholder> used,
      List<TemplateProblem> problems) {
    int count = 0;
    Matcher token = TOKEN.matcher(content);
    int at = 0;
    while (token.find()) {
      String before = content.substring(at, token.start());
      if (before.contains("{{") || before.contains("}}")) {
        add(problems, Reason.BRACES, line);
      }
      if (!before.isEmpty()) {
        segments.add(new Segment(before, null));
      }
      String name = token.group(1);
      if (!name.matches("[A-Za-z]+(\\.[A-Za-z]+)+")) {
        add(problems, Reason.MALFORMED_PLACEHOLDER, line);
      } else {
        Optional<ContractPlaceholder> placeholder = ContractPlaceholder.byKey(name);
        if (placeholder.isEmpty()) {
          add(problems, Reason.UNKNOWN_PLACEHOLDER, line);
        } else {
          used.add(placeholder.get());
          segments.add(new Segment(null, placeholder.get()));
        }
      }
      count++;
      at = token.end();
    }
    String rest = content.substring(at);
    if (rest.contains("{{") || rest.contains("}}")) {
      add(problems, Reason.BRACES, line);
    }
    if (!rest.isEmpty()) {
      segments.add(new Segment(rest, null));
    }
    return count;
  }

  /** Joins adjacent literal segments. */
  private static List<Segment> merge(List<Segment> segments) {
    List<Segment> merged = new ArrayList<>();
    StringBuilder text = null;
    for (Segment segment : segments) {
      if (segment.placeholder() == null) {
        if (text == null) {
          text = new StringBuilder();
        }
        text.append(segment.text());
      } else {
        if (text != null) {
          merged.add(new Segment(text.toString(), null));
          text = null;
        }
        merged.add(segment);
      }
    }
    if (text != null) {
      merged.add(new Segment(text.toString(), null));
    }
    return merged;
  }

  /** Character classes: control, format and other unsafe code points are refused. */
  private static void characters(String line, int number, List<TemplateProblem> problems) {
    line.codePoints()
        .mapToObj(TemplateGrammar::characterProblem)
        .flatMap(Optional::stream)
        .findFirst()
        .ifPresent(reason -> add(problems, reason, number));
  }

  private static Optional<Reason> characterProblem(int codePoint) {
    int type = Character.getType(codePoint);
    if (type == Character.CONTROL) {
      return Optional.of(Reason.CONTROL_CHARACTER);
    }
    if (type == Character.FORMAT
        || type == Character.PRIVATE_USE
        || type == Character.UNASSIGNED
        || type == Character.SURROGATE
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR) {
      return Optional.of(Reason.UNSAFE_CHARACTER);
    }
    return Optional.empty();
  }

  private static void add(List<TemplateProblem> problems, Reason reason, int line) {
    if (problems.size() < MAX_PROBLEMS) {
      TemplateProblem problem = new TemplateProblem(reason, line);
      if (!problems.contains(problem)) {
        problems.add(problem);
      }
    }
  }
}
