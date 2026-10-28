package com.divalhr.core.operations.application;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Verifies a receipt from its bytes (MVP-081, D81-5): the verified media type comes from the
 * leading bytes and the structure, never from the declared type or the file name, and the declared
 * type must agree. PDF, JPEG and PNG only; at most 2 MiB; an empty, malformed, truncated or
 * trailing-data (polyglot) file, an executable, an archive, SVG or HTML, and a PDF with active
 * content are refused with the one code {@code EXPENSE_RECEIPT_INVALID}. The digest is computed
 * here, never accepted from a client. Nothing of the bytes or the file name appears in a message.
 */
public final class ReceiptVerifier {

  /** Largest receipt in bytes (2 MiB). */
  public static final int MAX_BYTES = 2 * 1024 * 1024;

  /** A verified receipt. */
  public record Verified(
      String mediaType, byte[] bytes, byte[] sha256, String sha256Hex, String fileName) {

    @Override
    public String toString() {
      return "Verified[" + mediaType + ", " + bytes.length + "]";
    }
  }

  private static final Set<String> ACCEPTED = Set.of("application/pdf", "image/jpeg", "image/png");

  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

  /** Active or embedded PDF content that a receipt never needs. */
  private static final String[] PDF_ACTIVE = {
    "/JavaScript", "/JS", "/Launch", "/EmbeddedFile", "/RichMedia", "/OpenAction", "/AA",
    "/XFA", "/SubmitForm", "/ImportData", "/GoToR", "/GoToE", "/URI", "/Movie", "/Sound",
    "/FileAttachment"
  };

  private ReceiptVerifier() {}

  /**
   * The declared media type: only the three accepted ones, parameters ignored.
   *
   * @param contentType the declared {@code Content-Type}
   * @return the lower-case media type
   */
  public static String declared(String contentType) {
    String type =
        contentType == null
            ? ""
            : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (!ACCEPTED.contains(type)) {
      throw new ApiException(ErrorCode.EXPENSE_RECEIPT_MEDIA_UNSUPPORTED, Map.of());
    }
    return type;
  }

  /**
   * Verifies the bytes against the declared type.
   *
   * @param declaredType accepted declared media type
   * @param bytes the whole body (at most {@link #MAX_BYTES})
   * @param declaredName the display name sent by the client, or {@code null}
   * @return the verified receipt
   */
  public static Verified verify(String declaredType, byte[] bytes, String declaredName) {
    if (bytes == null || bytes.length == 0) {
      throw invalid();
    }
    if (bytes.length > MAX_BYTES) {
      throw new ApiException(ErrorCode.EXPENSE_RECEIPT_TOO_LARGE, Map.of());
    }
    String sniffed = sniff(bytes);
    if (sniffed == null || !sniffed.equals(declaredType)) {
      throw invalid();
    }
    boolean wellFormed =
        switch (sniffed) {
          case "application/pdf" -> pdf(bytes);
          case "image/jpeg" -> jpeg(bytes);
          default -> png(bytes);
        };
    if (!wellFormed) {
      throw invalid();
    }
    byte[] digest = sha256(bytes);
    return new Verified(
        sniffed, bytes, digest, HexFormat.of().formatHex(digest), displayName(declaredName, sniffed));
  }

  /**
   * The media type the leading bytes declare, or {@code null}.
   *
   * @param bytes content
   * @return media type or {@code null}
   */
  static String sniff(byte[] bytes) {
    if (startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
      return "application/pdf";
    }
    if (bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xFF
        && (bytes[1] & 0xFF) == 0xD8
        && (bytes[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (startsWith(bytes, PNG)) {
      return "image/png";
    }
    return null;
  }

  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (bytes[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean png(byte[] b) {
    int pos = PNG.length;
    boolean header = true;
    boolean data = false;
    while (pos + 12 <= b.length) {
      long length = u32(b, pos);
      if (length > b.length - pos - 12L) {
        return false;
      }
      int len = (int) length;
      String type = new String(b, pos + 4, 4, StandardCharsets.ISO_8859_1);
      CRC32 crc = new CRC32();
      crc.update(b, pos + 4, 4 + len);
      if (crc.getValue() != u32(b, pos + 8 + len)) {
        return false;
      }
      if (header) {
        if (!"IHDR".equals(type) || len != 13 || u32(b, pos + 8) == 0 || u32(b, pos + 12) == 0) {
          return false;
        }
        header = false;
      } else if ("IDAT".equals(type)) {
        data = true;
      }
      pos += 12 + len;
      if ("IEND".equals(type)) {
        return len == 0 && data && pos == b.length;
      }
    }
    return false;
  }

  private static boolean jpeg(byte[] b) {
    if (b.length < 4 || (b[b.length - 2] & 0xFF) != 0xFF || (b[b.length - 1] & 0xFF) != 0xD9) {
      return false;
    }
    int pos = 2;
    boolean frame = false;
    while (pos + 4 <= b.length) {
      if ((b[pos] & 0xFF) != 0xFF) {
        return false;
      }
      int marker = b[pos + 1] & 0xFF;
      if (marker == 0xFF) {
        pos++;
        continue;
      }
      int length = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
      if (length < 2 || pos + 2 + length > b.length) {
        return false;
      }
      boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8
          && marker != 0xCC;
      if (sof) {
        if (length < 8) {
          return false;
        }
        int height = ((b[pos + 5] & 0xFF) << 8) | (b[pos + 6] & 0xFF);
        int width = ((b[pos + 7] & 0xFF) << 8) | (b[pos + 8] & 0xFF);
        if (width == 0 || height == 0) {
          return false;
        }
        frame = true;
      }
      if (marker == 0xDA) {
        return frame;
      }
      pos += 2 + length;
    }
    return false;
  }

  private static boolean pdf(byte[] b) {
    String head = new String(b, 0, Math.min(b.length, 16), StandardCharsets.ISO_8859_1);
    if (!head.matches("(?s)%PDF-(1\\.[0-7]|2\\.0)[\\r\\n].*")) {
      return false;
    }
    int tailStart = Math.max(0, b.length - 1024);
    String tail = new String(b, tailStart, b.length - tailStart, StandardCharsets.ISO_8859_1);
    int eof = tail.lastIndexOf("%%EOF");
    if (eof < 0 || !tail.substring(eof + 5).isBlank()) {
      return false;
    }
    String all = new String(b, StandardCharsets.ISO_8859_1);
    if (!all.contains("obj") || !all.contains("endobj")) {
      return false;
    }
    for (String token : PDF_ACTIVE) {
      if (all.contains(token)) {
        return false;
      }
    }
    String lower = all.toLowerCase(Locale.ROOT);
    return !lower.contains("<script") && !lower.contains("<html") && !lower.contains("<svg");
  }

  private static long u32(byte[] b, int pos) {
    return ((long) (b[pos] & 0xFF) << 24)
        | ((long) (b[pos + 1] & 0xFF) << 16)
        | ((long) (b[pos + 2] & 0xFF) << 8)
        | (b[pos + 3] & 0xFF);
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is always available", impossible);
    }
  }

  /**
   * The normalized display name (D81-5): any path removed, NFC, no control, format, private-use or
   * markup character, at most 120 code points; never used as a storage path. A missing or empty name
   * becomes {@code receipt} plus the extension of the verified type.
   *
   * @param declared client name or {@code null}
   * @param mediaType verified type
   * @return a name the database accepts
   */
  static String displayName(String declared, String mediaType) {
    String fallback =
        "receipt" + switch (mediaType) {
          case "application/pdf" -> ".pdf";
          case "image/jpeg" -> ".jpg";
          default -> ".png";
        };
    if (declared == null) {
      return fallback;
    }
    String name = Normalizer.normalize(declared, Normalizer.Form.NFC);
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    name = name.substring(slash + 1);
    StringBuilder out = new StringBuilder();
    name.codePoints()
        .forEach(
            cp -> {
              boolean drop =
                  cp <= 0x1F
                      || (cp >= 0x7F && cp <= 0x9F)
                      || cp == 0xAD
                      || (cp >= 0x200B && cp <= 0x200F)
                      || (cp >= 0x2028 && cp <= 0x202E)
                      || (cp >= 0x2060 && cp <= 0x2064)
                      || (cp >= 0x2066 && cp <= 0x206F)
                      || cp == 0xFEFF
                      || (cp >= 0xE000 && cp <= 0xF8FF)
                      || (cp >= 0xFFF9 && cp <= 0xFFFB)
                      || (cp >= 0xD800 && cp <= 0xDFFF)
                      || cp >= 0xF0000
                      || Character.getType(cp) == Character.UNASSIGNED
                      || Character.getType(cp) == Character.FORMAT;
              if (drop) {
                return;
              }
              out.appendCodePoint("<>:\"|?*".indexOf(cp) >= 0 ? '_' : cp);
            });
    String cleaned = Normalizer.normalize(out.toString(), Normalizer.Form.NFC);
    int[] points = cleaned.codePoints().limit(120).toArray();
    cleaned = new String(points, 0, points.length).strip();
    cleaned = cleaned.replaceAll("^[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+", "")
        .replaceAll("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+$", "");
    return cleaned.isEmpty() ? fallback : cleaned;
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.EXPENSE_RECEIPT_INVALID, Map.of());
  }
}
