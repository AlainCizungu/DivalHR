package com.divalhr.core.documents.domain;

/**
 * The canonical JSON text of a snapshot (MVP-030, A30-4): keys in sorted order, no insignificant
 * whitespace, UTF-8, strings escaped minimally ({@code "}, {@code \}, and control characters as
 * {@code \}{@code u00XX}). The same text is stored, cast to {@code jsonb} by the database (which
 * checks they are equal), and digested.
 *
 * <pre>{"blocks":[{"t":"h1","x":"…"},…],"grammar":1,"locale":"fr","renderer":1,"title":"…"}</pre>
 */
public final class CanonicalSnapshot {

  private CanonicalSnapshot() {}

  /**
   * The canonical text.
   *
   * @param snapshot rendered snapshot
   * @return canonical JSON
   */
  public static String of(RenderedSnapshot snapshot) {
    StringBuilder json = new StringBuilder(256);
    json.append("{\"blocks\":[");
    boolean first = true;
    for (RenderedSnapshot.Block block : snapshot.blocks()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append("{\"t\":");
      string(json, block.type().code());
      json.append(",\"x\":");
      string(json, block.text());
      json.append('}');
    }
    json.append("],\"grammar\":").append(TemplateGrammar.VERSION).append(",\"locale\":");
    string(json, snapshot.locale());
    json.append(",\"renderer\":")
        .append(ContractValueFormats.RENDERER_VERSION)
        .append(",\"title\":");
    string(json, snapshot.title());
    return json.append('}').toString();
  }

  private static void string(StringBuilder json, String value) {
    json.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        json.append('\\').append(c);
      } else if (c < 0x20) {
        json.append(String.format("\\u%04x", (int) c));
      } else {
        json.append(c);
      }
    }
    json.append('"');
  }
}
