package com.divalhr.core.operations;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;

/**
 * Synthetic receipt files for tests (no personal data). The JPEG and PDF are genuinely valid files
 * produced or verified by standard tools (R170-1); the PNG is a minimal hand-built file whose
 * chunks and checksums are validated by the verifier.
 */
final class Receipts {

  private Receipts() {}

  /** A well-formed 1 x 1 PNG; {@code salt} makes the bytes (and the digest) differ. */
  static byte[] png(int salt) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    chunk(out, "IHDR", new byte[] {0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0});
    Deflater deflater = new Deflater();
    deflater.setInput(new byte[] {0, (byte) salt});
    deflater.finish();
    byte[] buffer = new byte[64];
    int length = deflater.deflate(buffer);
    byte[] data = java.util.Arrays.copyOf(buffer, length);
    chunk(out, "IDAT", data);
    chunk(out, "IEND", new byte[0]);
    return out.toByteArray();
  }

  /**
   * A genuinely decodable JPEG written by the JDK's standard encoder (8 x 8, a colour taken from
   * {@code salt}, so the bytes and the digest differ); the tests decode it again with the standard
   * reader.
   */
  static byte[] jpeg(int salt) {
    try {
      BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
      int rgb = (salt * 40503) & 0xFFFFFF;
      for (int y = 0; y < 8; y++) {
        for (int x = 0; x < 8; x++) {
          image.setRGB(x, y, (x + y) % 2 == 0 ? rgb : 0xFFFFFF - rgb);
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!ImageIO.write(image, "jpeg", out)) {
        throw new IllegalStateException("no JPEG writer");
      }
      return out.toByteArray();
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }

  /**
   * A genuinely valid one-page PDF: the committed {@code receipts/receipt.pdf} (written by
   * reportlab and checked with qpdf and poppler's pdfinfo) with its five-character total replaced
   * by the salt, so the length, the cross-reference offsets and the digest-distinctness all hold.
   */
  static byte[] pdf(int salt) {
    byte[] file = resource("receipt.pdf");
    byte[] total = "12.50".getBytes(StandardCharsets.ISO_8859_1);
    byte[] replacement = String.format("%05d", salt % 100000).getBytes(StandardCharsets.ISO_8859_1);
    int at = indexOf(file, total);
    if (at < 0) {
      throw new IllegalStateException("fixture changed");
    }
    byte[] copy = file.clone();
    System.arraycopy(replacement, 0, copy, at, replacement.length);
    return copy;
  }

  /** A committed fixture under {@code receipts/} (see its README note in the test class). */
  static byte[] resource(String name) {
    try (InputStream in = Receipts.class.getResourceAsStream("/receipts/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing fixture " + name);
      }
      return in.readAllBytes();
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }

  /**
   * The old synthetic "JPEG": every marker is in place and the scan is a single byte, so only a
   * superficial check accepts it. It must now be refused.
   */
  static byte[] jpegMarkersOnly(int salt) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8});
    out.writeBytes(
        new byte[] {
          (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0
        });
    out.writeBytes(
        new byte[] {
          (byte) 0xFF, (byte) 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00
        });
    out.writeBytes(
        new byte[] {(byte) 0xFF, (byte) 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00});
    out.write(salt & 0x7F);
    out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9});
    return out.toByteArray();
  }

  /**
   * The old synthetic "PDF": a header, an object, a trailer and an end marker, but no page tree and
   * no cross-reference table. It must now be refused.
   */
  static byte[] pdfStringsOnly(int salt) {
    String text =
        "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Note "
            + salt
            + " >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n";
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  /** Index of the first occurrence of {@code part} in {@code whole}, or -1. */
  static int indexOf(byte[] whole, byte[] part) {
    outer:
    for (int i = 0; i + part.length <= whole.length; i++) {
      for (int j = 0; j < part.length; j++) {
        if (whole[i + j] != part[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
    byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
    out.writeBytes(int32(data.length));
    out.writeBytes(name);
    out.writeBytes(data);
    CRC32 crc = new CRC32();
    crc.update(name);
    crc.update(data);
    out.writeBytes(int32((int) crc.getValue()));
  }

  private static byte[] int32(int value) {
    return new byte[] {
      (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
    };
  }

  /** The bytes followed by trailing data (a polyglot or an appended payload). */
  static byte[] withTrailer(byte[] file, String trailer) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(file);
    out.writeBytes(trailer.getBytes(StandardCharsets.ISO_8859_1));
    return out.toByteArray();
  }
}
