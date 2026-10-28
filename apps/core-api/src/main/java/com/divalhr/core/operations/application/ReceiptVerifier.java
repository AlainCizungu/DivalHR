package com.divalhr.core.operations.application;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Verifies a receipt from its bytes (MVP-081, D81-5): the verified media type comes from the
 * leading bytes and the structure, never from the declared type or the file name, and the declared
 * type must agree. PDF, JPEG and PNG only; at most 2 MiB; an empty, malformed, truncated or
 * trailing-data (polyglot) file, an executable, an archive, SVG or HTML, and a PDF with active
 * content are refused with the one code {@code EXPENSE_RECEIPT_INVALID}. The digest is computed
 * here, never accepted from a client. Nothing of the bytes or the file name appears in a message.
 *
 * <p>R170-1: a JPEG must decode completely with the JDK's maintained image reader (every scan line,
 * no decoder warning, so truncated or corrupt entropy data is refused) and a PDF must load with
 * Apache PDFBox's strict (non-repairing) parser as a document with at least one page. Dimensions
 * come from the decoder, never from the client, and are bounded before any pixel is decoded; the
 * decoded raster is subsampled to about a megapixel so memory stays small. No stream or page
 * content is ever rendered, and nothing is written to disk.
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

  /**
   * Most pixels a JPEG may declare (checked on the decoder's own header reading, before decoding).
   */
  static final long MAX_JPEG_PIXELS = 16_000_000L;

  /** Pixels kept in memory while decoding (the decoder still reads every scan line). */
  private static final double DECODED_PIXELS = 1_000_000d;

  /** Most pages and most indirect objects a PDF receipt may hold. */
  static final int MAX_PDF_PAGES = 50;

  static final int MAX_PDF_OBJECTS = 20_000;

  private static final Set<String> ACCEPTED = Set.of("application/pdf", "image/jpeg", "image/png");

  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

  /** Active or embedded PDF content that a receipt never needs. */
  private static final String[] PDF_ACTIVE = {
    "/JavaScript",
    "/JS",
    "/Launch",
    "/EmbeddedFile",
    "/RichMedia",
    "/OpenAction",
    "/AA",
    "/XFA",
    "/SubmitForm",
    "/ImportData",
    "/GoToR",
    "/GoToE",
    "/URI",
    "/Movie",
    "/Sound",
    "/FileAttachment"
  };

  /** The same active names, without the leading slash, for the parsed object graph. */
  private static final Set<String> PDF_ACTIVE_NAMES =
      Set.of(
          "JavaScript",
          "JS",
          "Launch",
          "EmbeddedFile",
          "EmbeddedFiles",
          "RichMedia",
          "OpenAction",
          "AA",
          "XFA",
          "SubmitForm",
          "ImportData",
          "GoToR",
          "GoToE",
          "URI",
          "Movie",
          "Sound",
          "FileAttachment");

  private ReceiptVerifier() {}

  /**
   * The declared media type: only the three accepted ones, parameters ignored.
   *
   * @param contentType the declared {@code Content-Type}
   * @return the lower-case media type
   */
  public static String declared(String contentType) {
    String type =
        contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
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
        sniffed,
        bytes,
        digest,
        HexFormat.of().formatHex(digest),
        displayName(declaredName, sniffed));
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

  /** A complete, decodable JPEG: the segment structure ends exactly at EOI, then a full decode. */
  private static boolean jpeg(byte[] b) {
    int[] size = jpegStructure(b);
    return size != null && jpegDecodes(b, size[0], size[1]);
  }

  /**
   * Walks the marker segments (frame, scans, entropy-coded data) to the one end-of-image marker,
   * which must be the last two bytes: nothing may follow the legal terminator.
   *
   * @return width and height from the frame header, or {@code null} when the structure is not a
   *     whole baseline, extended-sequential or progressive 8-bit JPEG with one or three components
   */
  private static int[] jpegStructure(byte[] b) {
    if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
      return null;
    }
    int pos = 2;
    int[] size = null;
    int scans = 0;
    while (pos + 2 <= b.length) {
      if ((b[pos] & 0xFF) != 0xFF) {
        return null;
      }
      int marker = b[pos + 1] & 0xFF;
      if (marker == 0xFF) {
        pos++;
        continue;
      }
      if (marker == 0xD9) {
        return size != null && scans > 0 && pos + 2 == b.length ? size : null;
      }
      if (marker == 0x00
          || marker == 0x01
          || marker == 0xD8
          || (marker >= 0xD0 && marker <= 0xD7)) {
        return null;
      }
      if (pos + 4 > b.length) {
        return null;
      }
      int length = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
      if (length < 2 || pos + 2 + length > b.length) {
        return null;
      }
      if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
        boolean decodable = marker == 0xC0 || marker == 0xC1 || marker == 0xC2;
        int components = length >= 8 ? b[pos + 9] & 0xFF : 0;
        if (size != null
            || !decodable
            || length < 8 + 3 * components
            || (b[pos + 4] & 0xFF) != 8
            || (components != 1 && components != 3)) {
          return null;
        }
        int height = ((b[pos + 5] & 0xFF) << 8) | (b[pos + 6] & 0xFF);
        int width = ((b[pos + 7] & 0xFF) << 8) | (b[pos + 8] & 0xFF);
        if (width == 0 || height == 0) {
          return null;
        }
        size = new int[] {width, height};
      }
      pos += 2 + length;
      if (marker == 0xDA) {
        if (size == null) {
          return null;
        }
        scans++;
        // Entropy-coded data: runs until a marker that is not a stuffed zero, restart or fill.
        while (pos + 1 < b.length) {
          if ((b[pos] & 0xFF) == 0xFF) {
            int next = b[pos + 1] & 0xFF;
            if (next == 0x00 || (next >= 0xD0 && next <= 0xD7)) {
              pos += 2;
              continue;
            }
            if (next == 0xFF) {
              pos++;
              continue;
            }
            break;
          }
          pos++;
        }
      }
    }
    return null;
  }

  /**
   * Decodes every scan line with the JDK's JPEG reader. A decoder warning (premature end of file,
   * corrupt Huffman data, extraneous bytes) means the file is not whole, so it is refused. The
   * dimensions the decoder reports must equal the frame header and stay within {@link
   * #MAX_JPEG_PIXELS}.
   */
  private static boolean jpegDecodes(byte[] b, int width, int height) {
    if ((long) width * height > MAX_JPEG_PIXELS) {
      return false;
    }
    Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
    if (!readers.hasNext()) {
      return false;
    }
    ImageReader reader = readers.next();
    AtomicBoolean warned = new AtomicBoolean();
    reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
    try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(b))) {
      reader.setInput(in, true, true);
      if (reader.getWidth(0) != width || reader.getHeight(0) != height) {
        return false;
      }
      int step =
          (int) Math.max(1, Math.ceil(Math.sqrt(((double) width * height) / DECODED_PIXELS)));
      ImageReadParam param = reader.getDefaultReadParam();
      param.setSourceSubsampling(step, step, 0, 0);
      BufferedImage image = reader.read(0, param);
      return !warned.get()
          && image != null
          && image.getWidth() == (width + step - 1) / step
          && image.getHeight() == (height + step - 1) / step;
    } catch (IOException | RuntimeException refused) {
      return false;
    } finally {
      reader.dispose();
    }
  }

  /**
   * A structurally loadable PDF: the header, a last {@code %%EOF} with nothing after it, a strict
   * (non-repairing) parse of the cross-reference data and trailer by Apache PDFBox, no encryption,
   * a catalog with between one and {@link #MAX_PDF_PAGES} pages, a bounded object count, and no
   * active or embedded content anywhere in the object graph or in the raw bytes.
   */
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
    for (String token : PDF_ACTIVE) {
      if (all.contains(token)) {
        return false;
      }
    }
    String lower = all.toLowerCase(Locale.ROOT);
    if (lower.contains("<script") || lower.contains("<html") || lower.contains("<svg")) {
      return false;
    }
    try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(b);
        PDDocument document = new PDFParser(source).parse(false)) {
      COSDocument cos = document.getDocument();
      if (document.isEncrypted()
          || cos.getTrailer() == null
          || cos.getXrefTable().size() > MAX_PDF_OBJECTS
          || document.getNumberOfPages() < 1
          || document.getNumberOfPages() > MAX_PDF_PAGES
          || document.getPage(0) == null) {
        return false;
      }
      return !hasActiveContent(cos);
    } catch (IOException | RuntimeException refused) {
      return false;
    }
  }

  /**
   * Whether any object, including those packed in object streams, carries an active key or name.
   */
  private static boolean hasActiveContent(COSDocument cos) throws IOException {
    Set<String> active = PDF_ACTIVE_NAMES;
    Map<COSBase, Boolean> seen = new IdentityHashMap<>();
    Deque<COSBase> work = new ArrayDeque<>();
    for (COSObjectKey key : cos.getXrefTable().keySet()) {
      COSBase resolved = cos.getObjectFromPool(key).getObject();
      if (resolved != null) {
        work.add(resolved);
      }
    }
    work.add(cos.getTrailer());
    int visited = 0;
    while (!work.isEmpty()) {
      COSBase node = work.poll();
      if (node == null || seen.put(node, Boolean.TRUE) != null) {
        continue;
      }
      if (++visited > 20 * MAX_PDF_OBJECTS) {
        return true;
      }
      if (node instanceof COSObject indirect) {
        work.add(indirect.getObject());
      } else if (node instanceof COSName name) {
        if (active.contains(name.getName())) {
          return true;
        }
      } else if (node instanceof COSDictionary dictionary) {
        for (var entry : dictionary.entrySet()) {
          if (active.contains(entry.getKey().getName())) {
            return true;
          }
          work.add(entry.getValue());
        }
      } else if (node instanceof COSArray array) {
        for (COSBase item : array) {
          work.add(item);
        }
      }
    }
    return false;
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
   * markup character, at most 120 code points; never used as a storage path. A missing or empty
   * name becomes {@code receipt} plus the extension of the verified type.
   *
   * @param declared client name or {@code null}
   * @param mediaType verified type
   * @return a name the database accepts
   */
  static String displayName(String declared, String mediaType) {
    String fallback =
        "receipt"
            + switch (mediaType) {
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
    cleaned =
        cleaned
            .replaceAll("^[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+", "")
            .replaceAll("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+$", "");
    return cleaned.isEmpty() ? fallback : cleaned;
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.EXPENSE_RECEIPT_INVALID, Map.of());
  }
}
