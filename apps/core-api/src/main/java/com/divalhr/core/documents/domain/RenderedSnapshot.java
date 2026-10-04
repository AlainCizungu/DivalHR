package com.divalhr.core.documents.domain;

import com.divalhr.core.documents.domain.TemplateGrammar.BlockType;
import java.util.List;

/**
 * The exact rendered content of a contract (Restricted HR).
 *
 * @param locale {@code fr} or {@code en}
 * @param title title
 * @param blocks blocks of plain text
 */
public record RenderedSnapshot(String locale, String title, List<Block> blocks) {

  /** Copies the blocks. */
  public RenderedSnapshot {
    blocks = List.copyOf(blocks);
  }

  /**
   * A rendered block.
   *
   * @param type kind
   * @param text plain text
   */
  public record Block(BlockType type, String text) {}
}
