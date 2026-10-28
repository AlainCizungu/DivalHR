package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.operations.application.ReceiptBody;
import com.divalhr.core.operations.application.ReceiptVerifier;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The receipt boundary (MVP-081, D81-5): the type comes from the bytes, never the declaration; the
 * structure must hold; the size is bounded; the digest is the server's.
 */
class ReceiptVerifierTest {

  private static ErrorCode refused(String declared, byte[] bytes) {
    try {
      ReceiptVerifier.verify(declared, bytes, "receipt");
      return null;
    } catch (ApiException invalid) {
      return invalid.code();
    }
  }

  @Test
  void aWellFormedFileOfEachTypeIsAccepted() throws Exception {
    var png = ReceiptVerifier.verify("image/png", Receipts.png(1), "ticket.png");
    var jpeg = ReceiptVerifier.verify("image/jpeg", Receipts.jpeg(1), "ticket.jpg");
    var pdf = ReceiptVerifier.verify("application/pdf", Receipts.pdf(1), "ticket.pdf");
    assertThat(png.mediaType()).isEqualTo("image/png");
    assertThat(jpeg.mediaType()).isEqualTo("image/jpeg");
    assertThat(pdf.mediaType()).isEqualTo("application/pdf");
    // The digest is computed from the bytes here.
    var sha = java.security.MessageDigest.getInstance("SHA-256").digest(Receipts.png(1));
    assertThat(png.sha256Hex()).isEqualTo(HexFormat.of().formatHex(sha));
  }

  @Test
  void theDeclaredTypeMustMatchTheMagicBytes() {
    // Mutation proof (D81-14): swapping the declared type must fail, not be believed.
    assertThat(refused("image/png", Receipts.jpeg(1))).isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
    assertThat(refused("application/pdf", Receipts.png(1)))
        .isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
    assertThat(refused("image/jpeg", Receipts.pdf(1))).isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
  }

  @Test
  void emptyUnknownExecutableArchiveSvgAndHtmlAreRefused() {
    assertThat(refused("image/png", new byte[0])).isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
    byte[][] others = {
      "MZ\u0090\u0000\u0003".getBytes(StandardCharsets.ISO_8859_1),
      new byte[] {0x7F, 'E', 'L', 'F', 2, 1, 1},
      new byte[] {'P', 'K', 3, 4, 20, 0},
      "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(StandardCharsets.UTF_8),
      "<!doctype html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8),
      "GIF89a".getBytes(StandardCharsets.ISO_8859_1),
    };
    for (byte[] other : others) {
      for (String declared : new String[] {"image/png", "image/jpeg", "application/pdf"}) {
        assertThat(refused(declared, other)).isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
      }
    }
  }

  @Test
  void trailingDataTruncationAndCorruptionAreRefused() {
    assertThat(refused("image/png", Receipts.withTrailer(Receipts.png(1), "<script>")))
        .isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
    assertThat(refused("image/jpeg", Receipts.withTrailer(Receipts.jpeg(1), "PK\u0003\u0004")))
        .isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
    assertThat(refused("application/pdf", Receipts.withTrailer(Receipts.pdf(1), "MZ-payload")))
        .isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
    byte[] png = Receipts.png(1);
    assertThat(refused("image/png", java.util.Arrays.copyOf(png, png.length - 5)))
        .isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
    byte[] flipped = png.clone();
    flipped[20] ^= 0x55;
    assertThat(refused("image/png", flipped)).isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
    byte[] jpeg = Receipts.jpeg(1);
    assertThat(refused("image/jpeg", java.util.Arrays.copyOf(jpeg, jpeg.length - 2)))
        .isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
    assertThat(
            refused(
                "application/pdf", "%PDF-1.4\nnothing else".getBytes(StandardCharsets.ISO_8859_1)))
        .isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
  }

  @Test
  void aPdfWithActiveContentIsRefused() {
    for (String active :
        new String[] {"/JavaScript", "/JS", "/Launch", "/EmbeddedFile", "/OpenAction"}) {
      String text = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog " + active + " (x) >>\nendobj\n%%EOF\n";
      assertThat(refused("application/pdf", text.getBytes(StandardCharsets.ISO_8859_1)))
          .as(active)
          .isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
    }
  }

  @Test
  void theSizeCapIsTwoMebibytes() {
    byte[] big = new byte[ReceiptVerifier.MAX_BYTES + 1];
    assertThat(refused("image/png", big)).isEqualTo(ErrorCode.EXPENSE_RECEIPT_TOO_LARGE);
    assertThat(ReceiptVerifier.MAX_BYTES).isEqualTo(2_097_152);
  }

  @Test
  void onlyThreeDeclaredTypesAreAccepted() {
    assertThat(ReceiptVerifier.declared("IMAGE/PNG; charset=binary")).isEqualTo("image/png");
    for (String declared :
        new String[] {
          null,
          "",
          "image/svg+xml",
          "text/html",
          "image/gif",
          "application/zip",
          "application/octet-stream",
          "multipart/form-data"
        }) {
      assertThatThrownBy(() -> ReceiptVerifier.declared(declared))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.code()).isEqualTo(ErrorCode.EXPENSE_RECEIPT_MEDIA_UNSUPPORTED));
    }
  }

  @Test
  void theFileNameIsDisplayOnlyPathStrippedAndBounded() {
    String name =
        ReceiptVerifier.verify("image/png", Receipts.png(1), "..\\..\\etc/pass‮wd<x>\u0000.png")
            .fileName();
    assertThat(name).doesNotContain("/", "\\", "<", ">", "‮", "\u0000").endsWith(".png");
    assertThat(ReceiptVerifier.verify("image/png", Receipts.png(1), null).fileName())
        .isEqualTo("receipt.png");
    assertThat(ReceiptVerifier.verify("image/png", Receipts.png(1), "  ").fileName())
        .isEqualTo("receipt.png");
    String longName = "é".repeat(300) + ".png";
    assertThat(
            ReceiptVerifier.verify("image/png", Receipts.png(1), longName)
                .fileName()
                .codePointCount(0, 120))
        .isEqualTo(120);
    assertThat(ReceiptVerifier.verify("image/png", Receipts.png(1), longName).fileName().length())
        .isLessThanOrEqualTo(120);
  }

  @Test
  void theBodyIsReadWithinTheCapAndTheDeadline() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC);
    byte[] ok = new byte[1000];
    try (InputStream in = new ByteArrayInputStream(ok)) {
      assertThat(ReceiptBody.read(in, clock)).hasSize(1000);
    }
    try (InputStream endless =
        new InputStream() {
          long served;

          @Override
          public int read() {
            served++;
            return 1;
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            served += length;
            assertThat(served).as("never read far beyond the cap").isLessThan(3L * 1024 * 1024);
            return length;
          }
        }) {
      assertThatThrownBy(() -> ReceiptBody.read(endless, clock))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.code()).isEqualTo(ErrorCode.EXPENSE_RECEIPT_TOO_LARGE));
    }
    try (InputStream broken =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("reset");
          }
        }) {
      assertThatThrownBy(() -> ReceiptBody.read(broken, clock))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.code()).isEqualTo(ErrorCode.EXPENSE_RECEIPT_UPLOAD_TIMEOUT));
    }
    assertThatThrownBy(() -> ReceiptBody.requireDeclaredLength(ReceiptVerifier.MAX_BYTES + 1L))
        .isInstanceOf(ApiException.class);
    ReceiptBody.requireDeclaredLength(-1);
    ReceiptBody.requireDeclaredLength(ReceiptVerifier.MAX_BYTES);
  }
}
