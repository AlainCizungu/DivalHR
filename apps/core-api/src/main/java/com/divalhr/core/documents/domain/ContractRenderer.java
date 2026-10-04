package com.divalhr.core.documents.domain;

import com.divalhr.core.documents.domain.TemplateGrammar.Parsed;
import com.divalhr.core.documents.domain.TemplateGrammar.Segment;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renderer v1 (MVP-030): pure and deterministic. The template is parsed into blocks <em>before</em>
 * values are substituted, so a value can never create a heading, list, link or markup; values are
 * inserted as literal text (NFC, any line break or other whitespace run as one space). A
 * placeholder without a value blocks rendering (D9).
 */
public final class ContractRenderer {

  private ContractRenderer() {}

  /**
   * The outcome of a rendering.
   *
   * @param snapshot the snapshot, or {@code null} when values are missing
   * @param missing placeholders without a value
   */
  public record Rendering(RenderedSnapshot snapshot, Set<ContractPlaceholder> missing) {

    /** Copies the missing set. */
    public Rendering {
      missing =
          missing.isEmpty()
              ? Set.of()
              : java.util.Collections.unmodifiableSet(EnumSet.copyOf(missing));
    }
  }

  /**
   * Renders a valid parsed template.
   *
   * @param locale the version's language
   * @param template parsed template (must be valid)
   * @param values server values per placeholder; absent or {@code null} means missing
   * @return the snapshot, or the missing placeholders
   */
  public static Rendering render(
      String locale, Parsed template, Map<ContractPlaceholder, String> values) {
    if (!template.valid()) {
      throw new IllegalArgumentException("only a valid template is rendered");
    }
    EnumSet<ContractPlaceholder> missing = EnumSet.noneOf(ContractPlaceholder.class);
    for (ContractPlaceholder placeholder : template.placeholders()) {
      String value = values.get(placeholder);
      if (value == null || value.isBlank()) {
        missing.add(placeholder);
      }
    }
    if (!missing.isEmpty()) {
      return new Rendering(null, missing);
    }
    List<RenderedSnapshot.Block> blocks = new ArrayList<>();
    for (TemplateGrammar.Block block : template.blocks()) {
      StringBuilder text = new StringBuilder();
      for (Segment segment : block.segments()) {
        text.append(
            segment.placeholder() == null
                ? segment.text()
                : literal(values.get(segment.placeholder())));
      }
      blocks.add(new RenderedSnapshot.Block(block.type(), text.toString()));
    }
    return new Rendering(new RenderedSnapshot(locale, template.title(), blocks), Set.of());
  }

  /** A value as literal text: NFC, whitespace runs (including line breaks) as one space. */
  static String literal(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
  }
}
