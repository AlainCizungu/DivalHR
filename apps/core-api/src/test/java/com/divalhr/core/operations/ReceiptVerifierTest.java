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

  private static void assertInvalid(String declared, byte[] bytes, String why) {
    assertThat(refused(declared, bytes)).as(why).isEqualTo(ErrorCode.EXPENSE_RECEIPT_INVALID);
  }

  /** The offset of the first byte after the SOS header of the first scan. */
  private static int scanStart(byte[] jpeg) {
    for (int i = 2; i + 4 < jpeg.length; i++) {
      if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xDA) {
        return i + 2 + (((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF));
      }
    }
    throw new IllegalStateException("no scan");
  }

  // ------------------------------------------------------------------------- R170-1: JPEG

  @Test
  void genuineJpegsFromStandardWritersAreAcceptedAndReallyDecode() throws Exception {
    // The fixtures were written by Pillow and checked with ImageMagick; the salted ones come from
    // the JDK encoder. Each is also decoded here by the standard reader, independently of the
    // verifier, so "accepted" is never merely "accepted by itself".
    for (byte[] file :
        new byte[][] {
          Receipts.resource("receipt-baseline.jpg"),
          Receipts.resource("receipt-progressive.jpg"),
          Receipts.jpeg(1),
          Receipts.jpeg(2)
        }) {
      assertThat(javax.imageio.ImageIO.read(new ByteArrayInputStream(file))).isNotNull();
      var verified = ReceiptVerifier.verify("image/jpeg", file, "scan.jpg");
      assertThat(verified.mediaType()).isEqualTo("image/jpeg");
    }
    assertThat(Receipts.jpeg(1)).isNotEqualTo(Receipts.jpeg(2));
  }

  @Test
  void aJpegWithValidMarkersButNoRealScanDataIsRefused() {
    // Every marker is present and well ordered, including SOI, JFIF, SOF, SOS and EOI.
    assertInvalid("image/jpeg", Receipts.jpegMarkersOnly(1), "the old synthetic fixture");
  }

  @Test
  void aJpegWithCorruptScanDataIsRefused() {
    for (String name : new String[] {"receipt-baseline.jpg", "receipt-progressive.jpg"}) {
      byte[] jpeg = Receipts.resource(name);
      int start = scanStart(jpeg);
      byte[] zeros = jpeg.clone();
      java.util.Arrays.fill(zeros, start, jpeg.length - 2, (byte) 0);
      assertInvalid("image/jpeg", zeros, name + ": scan replaced by zero bytes");
      byte[] noise = jpeg.clone();
      for (int i = start; i < jpeg.length - 2; i++) {
        noise[i] = (byte) (i * 31 + 7);
        if ((noise[i] & 0xFF) == 0xFF) {
          noise[i] = 0x55;
        }
      }
      assertInvalid("image/jpeg", noise, name + ": scan replaced by noise");
      byte[] empty = new byte[start + 2];
      System.arraycopy(jpeg, 0, empty, 0, start);
      empty[start] = (byte) 0xFF;
      empty[start + 1] = (byte) 0xD9;
      assertInvalid("image/jpeg", empty, name + ": no entropy data at all");
    }
  }

  @Test
  void aTruncatedOrCorruptJpegIsRefused() {
    byte[] jpeg = Receipts.resource("receipt-baseline.jpg");
    int start = scanStart(jpeg);
    int middle = start + (jpeg.length - start) / 2;
    byte[] cut = java.util.Arrays.copyOf(jpeg, middle);
    assertInvalid("image/jpeg", cut, "cut inside the scan, no end marker");
    byte[] cutWithEnd = java.util.Arrays.copyOf(jpeg, middle + 2);
    cutWithEnd[middle] = (byte) 0xFF;
    cutWithEnd[middle + 1] = (byte) 0xD9;
    assertInvalid("image/jpeg", cutWithEnd, "cut inside the scan, then a forged end marker");
    assertInvalid("image/jpeg", java.util.Arrays.copyOf(jpeg, start - 3), "cut inside a header");
    assertInvalid("image/jpeg", java.util.Arrays.copyOf(jpeg, 3), "only the first marker");
    byte[] header = jpeg.clone();
    header[4] ^= 0x7F;
    assertInvalid("image/jpeg", header, "a corrupt segment length");
  }

  @Test
  void theJpegDimensionsAreTheDecodersAndAreBounded() {
    byte[] jpeg = Receipts.resource("receipt-baseline.jpg").clone();
    int sof = -1;
    for (int i = 2; i + 9 < jpeg.length; i++) {
      if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xC0) {
        sof = i;
        break;
      }
    }
    assertThat(sof).isPositive();
    // Zero dimensions are refused.
    byte[] zero = jpeg.clone();
    zero[sof + 7] = 0;
    zero[sof + 8] = 0;
    assertInvalid("image/jpeg", zero, "zero width");
    // A header that claims a huge image over a tiny scan is not believed: the decode fails (the
    // data is far too short) or the pixel bound refuses it before any decoding.
    byte[] huge = jpeg.clone();
    huge[sof + 5] = (byte) 0xFF;
    huge[sof + 6] = (byte) 0xFF;
    huge[sof + 7] = (byte) 0xFF;
    huge[sof + 8] = (byte) 0xFF;
    assertInvalid("image/jpeg", huge, "65535 x 65535 declared");
    assertThat(ReceiptVerifier.MAX_BYTES).isEqualTo(2 * 1024 * 1024);
  }

  // -------------------------------------------------------------------------- R170-1: PDF

  @Test
  void genuinePdfsFromStandardWritersAreAccepted() {
    // receipt.pdf: reportlab, checked with qpdf and pdfinfo. receipt-objstm.pdf: the same document
    // rewritten by qpdf with a compressed object stream and a cross-reference stream (PDF 1.5).
    for (byte[] file :
        new byte[][] {
          Receipts.resource("receipt.pdf"),
          Receipts.resource("receipt-objstm.pdf"),
          Receipts.pdf(1),
          Receipts.pdf(2)
        }) {
      assertThat(ReceiptVerifier.verify("application/pdf", file, "ticket.pdf").mediaType())
          .isEqualTo("application/pdf");
    }
    assertThat(Receipts.pdf(1)).isNotEqualTo(Receipts.pdf(2)).hasSize(Receipts.pdf(2).length);
  }

  @Test
  void aPdfWithHeaderObjectAndEofStringsButNoDocumentStructureIsRefused() {
    assertInvalid("application/pdf", Receipts.pdfStringsOnly(1), "the old synthetic fixture");
    String lookalike =
        "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n2 0 obj\n<< /Type /Pages >>\nendobj\n"
            + "xref\n0 1\n0000000000 65535 f \ntrailer\n<< /Size 3 /Root 1 0 R >>\n"
            + "startxref\n0\n%%EOF\n";
    assertInvalid(
        "application/pdf",
        lookalike.getBytes(StandardCharsets.ISO_8859_1),
        "a catalog and a pages object that no page tree connects");
  }

  @Test
  void aTruncatedOrCorruptPdfIsRefused() {
    for (String name : new String[] {"receipt.pdf", "receipt-objstm.pdf"}) {
      byte[] pdf = Receipts.resource(name);
      assertInvalid(
          "application/pdf", java.util.Arrays.copyOf(pdf, pdf.length / 2), name + ": cut in half");
      assertInvalid(
          "application/pdf",
          withEof(java.util.Arrays.copyOf(pdf, pdf.length / 2)),
          name + ": cut in half, then a forged end marker");
      assertInvalid(
          "application/pdf",
          java.util.Arrays.copyOf(pdf, pdf.length - 20),
          name + ": the end of the file removed");
      String text = new String(pdf, StandardCharsets.ISO_8859_1);
      int startxref = text.lastIndexOf("startxref");
      byte[] wrongOffset =
          text.substring(0, startxref)
              .concat("startxref\n7\n%%EOF\n")
              .getBytes(StandardCharsets.ISO_8859_1);
      assertInvalid("application/pdf", wrongOffset, name + ": startxref points at nothing");
      byte[] noise = pdf.clone();
      for (int i = noise.length / 3; i < noise.length / 3 + 64; i++) {
        noise[i] = (byte) (i * 17 + 3);
      }
      assertInvalid("application/pdf", noise, name + ": bytes overwritten in the middle");
    }
  }

  private static byte[] withEof(byte[] bytes) {
    return Receipts.withTrailer(bytes, "\nstartxref\n0\n%%EOF\n");
  }

  @Test
  void aPdfWithActiveContentIsRefusedWhereverItIsHidden() {
    // Real, loadable PDFs (written with pikepdf, checked with qpdf and pdfinfo) that carry an
    // OpenAction with JavaScript, a URI link, and JavaScript hidden inside a compressed object
    // stream that no byte search can see.
    for (String name :
        new String[] {"active-javascript.pdf", "active-uri.pdf", "active-in-objstm.pdf"}) {
      assertInvalid("application/pdf", Receipts.resource(name), name);
    }
    for (String active :
        new String[] {"/JavaScript", "/JS", "/Launch", "/EmbeddedFile", "/OpenAction"}) {
      String text = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog " + active + " (x) >>\nendobj\n%%EOF\n";
      assertInvalid("application/pdf", text.getBytes(StandardCharsets.ISO_8859_1), active);
    }
  }

  // ------------------------------------------------------- terminal data and declared type

  @Test
  void payloadAfterTheLegalTerminatorIsRefused() {
    assertInvalid("image/png", Receipts.withTrailer(Receipts.png(1), "<script>"), "PNG");
    for (String name : new String[] {"receipt-baseline.jpg", "receipt-progressive.jpg"}) {
      byte[] jpeg = Receipts.resource(name);
      assertInvalid("image/jpeg", Receipts.withTrailer(jpeg, "PK\u0003\u0004"), name + ": archive");
      assertInvalid("image/jpeg", Receipts.withTrailer(jpeg, "\u0000"), name + ": one NUL byte");
      assertInvalid(
          "image/jpeg",
          Receipts.withTrailer(jpeg, "MZ-payload\u00FF\u00D9"),
          name + ": payload that itself ends with a forged end marker");
    }
    for (String name : new String[] {"receipt.pdf", "receipt-objstm.pdf"}) {
      byte[] pdf = Receipts.resource(name);
      assertInvalid("application/pdf", Receipts.withTrailer(pdf, "MZ-payload"), name + ": payload");
      assertInvalid("application/pdf", Receipts.withTrailer(pdf, "\u0000"), name + ": NUL byte");
    }
  }

  @Test
  void aDeclaredTypeThatDisagreesWithTheRealFileIsRefused() {
    byte[] jpeg = Receipts.jpeg(3);
    byte[] pdf = Receipts.pdf(3);
    byte[] png = Receipts.png(3);
    assertInvalid("application/pdf", jpeg, "JPEG declared as PDF");
    assertInvalid("image/png", jpeg, "JPEG declared as PNG");
    assertInvalid("image/jpeg", pdf, "PDF declared as JPEG");
    assertInvalid("image/png", pdf, "PDF declared as PNG");
    assertInvalid("image/jpeg", png, "PNG declared as JPEG");
    assertInvalid("application/pdf", png, "PNG declared as PDF");
  }

  @Test
  void aPngIsStillValidatedAsBefore() {
    byte[] png = Receipts.png(1);
    assertThat(ReceiptVerifier.verify("image/png", png, "a.png").mediaType())
        .isEqualTo("image/png");
    assertInvalid("image/png", java.util.Arrays.copyOf(png, png.length - 5), "truncated PNG");
    byte[] flipped = png.clone();
    flipped[20] ^= 0x55;
    assertInvalid("image/png", flipped, "corrupt PNG checksum");
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
