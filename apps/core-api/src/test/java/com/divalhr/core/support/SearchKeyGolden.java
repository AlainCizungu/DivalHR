package com.divalhr.core.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/** MVP-021 (H16): the shared search-key golden vectors ({@code people/search-key-golden.tsv}). */
public final class SearchKeyGolden {

  private SearchKeyGolden() {}

  /**
   * One vector.
   *
   * @param givenNames given names (NFC, valid for the import grammar)
   * @param familyName family name
   * @param key the stored key
   */
  public record Vector(String givenNames, String familyName, String key) {}

  /**
   * Every vector of the file.
   *
   * @return vectors in file order
   */
  public static List<Vector> vectors() {
    String text;
    try {
      text =
          new ClassPathResource("people/search-key-golden.tsv")
              .getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    List<Vector> vectors = new ArrayList<>();
    for (String line : text.split("\n")) {
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] cells = line.split("\t", -1);
      if (cells.length != 3) {
        throw new IllegalStateException("malformed golden vector");
      }
      vectors.add(new Vector(cells[0], cells[1], " " + cells[2] + " "));
    }
    return vectors;
  }
}
