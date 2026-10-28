package com.divalhr.core.operations;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Synthetic receipt files for tests: tiny, well-formed PDF, JPEG and PNG, and corruptions. */
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

  /** A well-formed 1 x 1 JPEG. */
  static byte[] jpeg(int salt) {
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

  /** A well-formed minimal PDF. */
  static byte[] pdf(int salt) {
    String text =
        "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Note "
            + salt
            + " >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n";
    return text.getBytes(StandardCharsets.ISO_8859_1);
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
