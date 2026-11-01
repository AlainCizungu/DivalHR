package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.PayrollMoney;
import com.divalhr.core.payroll.domain.PayrollVoidReason;
import com.divalhr.core.payroll.domain.PayslipLocale;
import com.divalhr.core.payroll.domain.PayslipSnapshot;
import com.divalhr.core.payroll.domain.PayslipText;
import com.divalhr.core.payroll.domain.PayslipText.Label;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;
import org.springframework.stereotype.Component;

/**
 * Renders one payslip as a deterministic, tagged, single-page A4 PDF from its immutable snapshot
 * (MVP-094C, ADR 0035 section 12, R182-1). The same snapshot, status and locale always give the
 * same bytes: the document is written by Apache PDFBox with no compression, a fixed trailer
 * identifier (the first 16 bytes of the snapshot digest twice), both dates set to the approval
 * instant and no XMP packet. Text is set in the repository-owned DejaVu Sans (regular and bold),
 * embedded as a subset from the application resources and verified against pinned SHA-256 digests,
 * so any employee text the font covers is printed as typed. A character the font does not cover, or
 * a control character, is never replaced: the render fails closed with {@link
 * PayslipRenderException} and nothing is stored. The embedded font and character-map streams are
 * written without a filter, so the bytes do not depend on the zlib build of the runtime. The Info
 * dictionary holds only a generic localized title and the constant creator and producer {@code
 * DivalHR}: no name, number, period, subject, token, e-mail, path, host or filename. The structure
 * tree is a minimal tagged outline ({@code Document}, {@code H1}, {@code H2}, {@code P}, {@code
 * Table}, {@code TR}, {@code TH}, {@code TD}); full PDF/UA conformance is not claimed. The amounts
 * are the canonical whole-franc integers in both locales; only the wording changes.
 *
 * <p>Restricted financial and HR data: nothing is logged here and no exception carries content.
 */
@Component
public class PayslipPdfRenderer {

  /** The largest document, in bytes. */
  public static final int MAX_BYTES = 256 * 1024;

  /** The longest time a render may take. */
  public static final Duration TIMEOUT = Duration.ofSeconds(5);

  /** The classpath location and pinned SHA-256 of the embedded regular face. */
  static final String REGULAR_RESOURCE = "/fonts/DejaVuSans.ttf";

  static final String REGULAR_SHA256 =
      "ae7b7855e115a5966d8b1b3f80f254ccc117ec86f9965e202ee2940453837280";

  /** The classpath location and pinned SHA-256 of the embedded bold face. */
  static final String BOLD_RESOURCE = "/fonts/DejaVuSans-Bold.ttf";

  static final String BOLD_SHA256 =
      "5c1247acef7f2b8522a31742c76d6adcb5569bacc0be7ceaa4dc39dd252ce895";

  private static final double PAGE_WIDTH = 595;
  private static final double PAGE_HEIGHT = 842;
  private static final double LEFT = 50;
  private static final double RIGHT = 545;
  private static final double BOTTOM = 48;
  private static final byte[] NO_BYTES = new byte[0];

  private static volatile byte[] regularBytes = NO_BYTES;
  private static volatile byte[] boldBytes = NO_BYTES;

  /**
   * Everything a render needs.
   *
   * @param snapshot the parsed canonical snapshot
   * @param snapshotDigest the SHA-256 of the canonical text
   * @param locale the wording locale
   * @param voided whether the package is VOIDED
   * @param voidReason the closed void reason of a voided package, or {@code null}
   * @param synthetic whether the rule pack is a SYNTHETIC-TEST pack
   */
  public record Request(
      PayslipSnapshot snapshot,
      String snapshotDigest,
      PayslipLocale locale,
      boolean voided,
      PayrollVoidReason voidReason,
      boolean synthetic) {

    @Override
    public String toString() {
      return "PayslipRenderRequest[" + locale + "]";
    }
  }

  /** The single page under construction. */
  private static final class Sheet {
    private final PDPage page;
    private final PDPageContentStream content;
    private final PDStructureTreeRoot root;
    private final PDType0Font regular;
    private final PDType0Font bold;
    private final CmapLookup regularMap;
    private final CmapLookup boldMap;
    private final List<PDStructureElement> nodes = new ArrayList<>();
    private final List<PDStructureElement> owners = new ArrayList<>();
    private double y = PAGE_HEIGHT - 56;

    Sheet(
        PDPage page,
        PDPageContentStream content,
        PDStructureTreeRoot root,
        PDType0Font regular,
        PDType0Font bold,
        CmapLookup regularMap,
        CmapLookup boldMap) {
      this.page = page;
      this.content = content;
      this.root = root;
      this.regular = regular;
      this.bold = bold;
      this.regularMap = regularMap;
      this.boldMap = boldMap;
    }

    PDFont face(boolean heavy) {
      return heavy ? bold : regular;
    }

    void strokeRectangle(double lineWidth, double x, double bottom, double width, double height) {
      try {
        content.beginMarkedContent(COSName.ARTIFACT);
        content.setLineWidth((float) lineWidth);
        content.addRect((float) x, (float) bottom, (float) width, (float) height);
        content.stroke();
        content.endMarkedContent();
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }

    void strokeLine(double lineWidth, double x1, double y1, double x2, double y2) {
      try {
        content.beginMarkedContent(COSName.ARTIFACT);
        content.setLineWidth((float) lineWidth);
        content.moveTo((float) x1, (float) y1);
        content.lineTo((float) x2, (float) y2);
        content.stroke();
        content.endMarkedContent();
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }

    int node(String type, int parent) {
      PDStructureNode owner = parent < 0 ? root : nodes.get(parent);
      PDStructureElement element = new PDStructureElement(type, owner);
      element.setPage(page);
      owner.appendKid(element);
      nodes.add(element);
      return nodes.size() - 1;
    }
  }

  /**
   * Renders a payslip.
   *
   * @param request the snapshot, status and locale
   * @return the document
   * @throws PayslipRenderException when the content does not fit one page, exceeds 256 KiB or takes
   *     longer than 5 seconds
   */
  public PayslipPdf render(Request request) {
    long started = System.nanoTime();
    try {
      byte[] bytes = document(request);
      if (bytes.length > MAX_BYTES || System.nanoTime() - started > TIMEOUT.toNanos()) {
        throw new PayslipRenderException();
      }
      return new PayslipPdf(bytes);
    } catch (PayslipRenderException failed) {
      throw failed;
    } catch (IOException | RuntimeException failure) {
      throw new PayslipRenderException();
    }
  }

  // ------------------------------------------------------------------------------------------
  // Layout
  // ------------------------------------------------------------------------------------------

  private static void layout(Sheet sheet, Request request) {
    PayslipSnapshot snapshot = request.snapshot();
    PayslipLocale locale = request.locale();
    int doc = sheet.node("Document", -1);

    int h1 = sheet.node("H1", doc);
    text(sheet, h1, true, 18, LEFT, sheet.y, PayslipText.title(locale));
    sheet.y -= 26;

    if (request.voided()) {
      banner(sheet, doc, PayslipText.voidedBanner(locale));
    }
    int status = sheet.node("P", doc);
    text(
        sheet,
        status,
        true,
        11,
        LEFT,
        sheet.y,
        Label.STATUS.in(locale) + ": " + PayslipText.statusLabel(locale, request.voided()));
    sheet.y -= 16;
    int period = sheet.node("P", doc);
    text(
        sheet,
        period,
        false,
        10,
        LEFT,
        sheet.y,
        Label.PERIOD.in(locale) + ": " + snapshot.period());
    sheet.y -= 14;
    int number = sheet.node("P", doc);
    text(
        sheet,
        number,
        false,
        10,
        LEFT,
        sheet.y,
        Label.EMPLOYEE_NUMBER.in(locale) + ": " + snapshot.employeeNumber());
    sheet.y -= 18;

    paragraph(
        sheet,
        doc,
        PayslipText.rulePackStatement(locale, snapshot.rulePackCode(), snapshot.rulePackVersion()),
        false,
        9);
    if (request.synthetic()) {
      paragraph(sheet, doc, PayslipText.syntheticNotice(locale), true, 9);
    }
    sheet.y -= 6;

    heading(sheet, doc, Label.EMPLOYEE_AMOUNTS.in(locale));
    int amounts = sheet.node("Table", doc);
    amountRow(sheet, amounts, false, Label.GROSS.in(locale), snapshot.grossMinor());
    if (snapshot.excluded()) {
      paragraph(sheet, doc, Label.EXCLUDED.in(locale), false, 9);
    }
    amountRow(sheet, amounts, false, Label.EMPLOYEE_CNSS.in(locale), snapshot.employeeCnssMinor());
    amountRow(sheet, amounts, false, Label.IRPP.in(locale), snapshot.irppWithholdingMinor());
    amountRow(
        sheet, amounts, false, Label.DEDUCTIONS.in(locale), snapshot.employeeDeductionsMinor());
    amountRow(sheet, amounts, true, Label.NET.in(locale), snapshot.netMinor());
    sheet.y -= 8;

    heading(sheet, doc, Label.EMPLOYER_SECTION.in(locale));
    int employer = sheet.node("Table", doc);
    amountRow(
        sheet,
        employer,
        false,
        Label.EMPLOYER_TOTAL.in(locale),
        snapshot.employerContributionsMinor());
    paragraph(sheet, doc, PayslipText.employerContributionsNote(locale), false, 9);
    sheet.y -= 4;

    if (!snapshot.components().isEmpty()) {
      components(sheet, doc, snapshot, locale);
    }
    if (!snapshot.explanationCodes().isEmpty()) {
      List<String> labels = new ArrayList<>();
      for (String code : snapshot.explanationCodes()) {
        labels.add(PayslipText.explanationLabel(locale, code));
      }
      paragraph(
          sheet, doc, Label.EXPLANATIONS.in(locale) + ": " + String.join("; ", labels), false, 8);
    }
    sheet.y -= 4;

    if (request.voided()) {
      paragraph(sheet, doc, PayslipText.voidedNotice(locale, request.voidReason()), true, 9);
    }
    paragraph(sheet, doc, PayslipText.notProofOfPayment(locale), true, 10);
    String digest = request.snapshotDigest();
    paragraph(sheet, doc, Label.DIGEST.in(locale) + ":", false, 8);
    paragraph(sheet, doc, digest.substring(0, 32), false, 8);
    paragraph(sheet, doc, digest.substring(32), false, 8);
  }

  private static void banner(Sheet sheet, int doc, String word) {
    double top = sheet.y + 4;
    double height = 26;
    sheet.strokeRectangle(1.5, LEFT, top - height, RIGHT - LEFT, height);
    int node = sheet.node("P", doc);
    text(sheet, node, true, 16, LEFT + 8, top - 19, word);
    sheet.y -= 34;
  }

  private static void heading(Sheet sheet, int doc, String words) {
    guard(sheet, 40);
    int node = sheet.node("H2", doc);
    text(sheet, node, true, 11, LEFT, sheet.y, words);
    rule(sheet, sheet.y - 4);
    sheet.y -= 18;
  }

  private static void rule(Sheet sheet, double y) {
    sheet.strokeLine(0.5, LEFT, y, RIGHT, y);
  }

  private static void amountRow(Sheet sheet, int table, boolean bold, String label, long minor) {
    guard(sheet, 16);
    int row = sheet.node("TR", table);
    int head = sheet.node("TH", row);
    int cell = sheet.node("TD", row);
    text(sheet, head, bold, 10, LEFT, sheet.y, label);
    right(sheet, cell, bold, 10, RIGHT, sheet.y, money(minor) + " CDF");
    sheet.y -= 15;
  }

  private static void components(
      Sheet sheet, int doc, PayslipSnapshot snapshot, PayslipLocale locale) {
    heading(sheet, doc, Label.COMPONENT.in(locale));
    int table = sheet.node("Table", doc);
    double party = 290;
    double baseRight = 450;
    int header = sheet.node("TR", table);
    text(sheet, sheet.node("TH", header), true, 8, LEFT, sheet.y, Label.COMPONENT.in(locale));
    text(sheet, sheet.node("TH", header), true, 8, party, sheet.y, Label.PARTY.in(locale));
    right(sheet, sheet.node("TH", header), true, 8, baseRight, sheet.y, Label.BASE.in(locale));
    right(sheet, sheet.node("TH", header), true, 8, RIGHT, sheet.y, Label.AMOUNT.in(locale));
    sheet.y -= 13;
    for (PayslipSnapshot.Component component : snapshot.components()) {
      guard(sheet, 14);
      int row = sheet.node("TR", table);
      text(
          sheet,
          sheet.node("TD", row),
          false,
          8,
          LEFT,
          sheet.y,
          fit(sheet, PayslipText.componentLabel(locale, component.id()), 8, party - LEFT - 6));
      text(
          sheet,
          sheet.node("TD", row),
          false,
          8,
          party,
          sheet.y,
          PayslipText.partyLabel(locale, component.party()));
      right(
          sheet, sheet.node("TD", row), false, 8, baseRight, sheet.y, money(component.baseMinor()));
      right(sheet, sheet.node("TD", row), false, 8, RIGHT, sheet.y, money(component.amountMinor()));
      sheet.y -= 12;
    }
    sheet.y -= 4;
  }

  private static String money(long minor) {
    return PayrollMoney.text(CompensationCurrency.CDF, minor);
  }

  private static void paragraph(Sheet sheet, int parent, String words, boolean bold, double size) {
    int node = sheet.node("P", parent);
    double leading = size + 3;
    for (String line : wrap(sheet, words, bold, size, RIGHT - LEFT)) {
      guard(sheet, leading);
      text(sheet, node, bold, size, LEFT, sheet.y, line);
      sheet.y -= leading;
    }
    sheet.y -= 2;
  }

  private static void guard(Sheet sheet, double needed) {
    if (sheet.y - needed < BOTTOM) {
      throw new PayslipRenderException();
    }
  }

  // ------------------------------------------------------------------------------------------
  // Text
  // ------------------------------------------------------------------------------------------

  private static void text(
      Sheet sheet, int node, boolean bold, double size, double x, double y, String words) {
    PDStructureElement element = sheet.nodes.get(node);
    int mcid = sheet.owners.size();
    sheet.owners.add(element);
    element.appendKid(mcid);
    String type = element.getStructureType();
    COSName tag = "TH".equals(type) || "TD".equals(type) ? COSName.P : COSName.getPDFName(type);
    COSDictionary properties = new COSDictionary();
    properties.setInt(COSName.MCID, mcid);
    try {
      checked(sheet, words);
      PDPageContentStream content = sheet.content;
      content.beginMarkedContent(tag, PDPropertyList.create(properties));
      content.beginText();
      content.setFont(sheet.face(bold), (float) size);
      content.newLineAtOffset((float) x, (float) y);
      content.showText(words);
      content.endText();
      content.endMarkedContent();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static void right(
      Sheet sheet, int node, boolean bold, double size, double edge, double y, String words) {
    text(sheet, node, bold, size, edge - width(sheet, words, bold, size), y, words);
  }

  /**
   * The text itself when the embedded face covers every character; otherwise a failure. A control
   * character, an invisible format character (such as a bidirectional override) or a character with
   * no glyph is never replaced or dropped, because an immutable payslip must not print something
   * the employee record does not say.
   */
  private static String checked(Sheet sheet, String words) {
    if (words == null) {
      throw new PayslipRenderException();
    }
    for (int i = 0; i < words.length(); ) {
      int point = words.codePointAt(i);
      if (Character.isISOControl(point)
          || !Character.isDefined(point)
          || Character.getType(point) == Character.FORMAT
          || Character.isSurrogate(words.charAt(i)) && Character.charCount(point) == 1
          || !covered(sheet, point)) {
        throw new PayslipRenderException();
      }
      i += Character.charCount(point);
    }
    return words;
  }

  /**
   * Whether both embedded faces have a real glyph (not the missing-glyph box) for the character.
   */
  private static boolean covered(Sheet sheet, int codePoint) {
    return sheet.regularMap.getGlyphId(codePoint) != 0 && sheet.boldMap.getGlyphId(codePoint) != 0;
  }

  private static double width(Sheet sheet, String words, boolean bold, double size) {
    try {
      return sheet.face(bold).getStringWidth(checked(sheet, words)) / 1000.0 * size;
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /** The text cut at the last whole character that fits the width. */
  private static String fit(Sheet sheet, String words, double size, double max) {
    String shown = checked(sheet, words);
    while (shown.codePointCount(0, shown.length()) > 1 && width(sheet, shown, false, size) > max) {
      shown = shown.substring(0, shown.offsetByCodePoints(shown.length(), -1));
    }
    return shown;
  }

  private static List<String> wrap(
      Sheet sheet, String words, boolean bold, double size, double max) {
    List<String> lines = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    for (String word : checked(sheet, words).split(" ")) {
      if (word.isEmpty()) {
        continue;
      }
      String candidate = line.length() == 0 ? word : line + " " + word;
      if (line.length() > 0 && width(sheet, candidate, bold, size) > max) {
        lines.add(line.toString());
        line = new StringBuilder(word);
      } else {
        line = new StringBuilder(candidate);
      }
    }
    if (line.length() > 0 || lines.isEmpty()) {
      lines.add(line.toString());
    }
    return lines;
  }

  // ------------------------------------------------------------------------------------------
  // File
  // ------------------------------------------------------------------------------------------

  private static byte[] document(Request request) throws IOException {
    byte[] regularFace = face(REGULAR_RESOURCE, REGULAR_SHA256, true);
    byte[] boldFace = face(BOLD_RESOURCE, BOLD_SHA256, false);
    try (PDDocument pdf = new PDDocument();
        TrueTypeFont regularFont = new TTFParser().parse(new RandomAccessReadBuffer(regularFace));
        TrueTypeFont boldFont = new TTFParser().parse(new RandomAccessReadBuffer(boldFace))) {
      CmapLookup regularMap = regularFont.getUnicodeCmapLookup();
      CmapLookup boldMap = boldFont.getUnicodeCmapLookup();
      pdf.getDocument().setVersion(1.7f);
      PDType0Font regular = PDType0Font.load(pdf, new ByteArrayInputStream(regularFace), true);
      PDType0Font bold = PDType0Font.load(pdf, new ByteArrayInputStream(boldFace), true);
      PDPage page = new PDPage(new PDRectangle((float) PAGE_WIDTH, (float) PAGE_HEIGHT));
      pdf.addPage(page);
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      Sheet sheet;
      try (PDPageContentStream content =
          new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.OVERWRITE, false)) {
        sheet = new Sheet(page, content, root, regular, bold, regularMap, boldMap);
        layout(sheet, request);
      }
      describe(pdf, page, root, sheet, request);
      // First pass: PDFBox subsets the fonts while saving. The subset streams are then rewritten
      // without a filter so that no byte depends on the zlib build, and the file is saved again.
      pdf.save(OutputStream.nullOutputStream(), CompressParameters.NO_COMPRESSION);
      unfilter(pdf.getDocument().getTrailer(), Collections.newSetFromMap(new IdentityHashMap<>()));
      pdf.getDocumentCatalog().getCOSObject().removeItem(COSName.VERSION);
      ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
      pdf.save(out, CompressParameters.NO_COMPRESSION);
      return out.toByteArray();
    }
  }

  private static void describe(
      PDDocument pdf, PDPage page, PDStructureTreeRoot root, Sheet sheet, Request request) {
    PDDocumentCatalog catalog = pdf.getDocumentCatalog();
    catalog.setLanguage(request.locale().code());
    PDMarkInfo mark = new PDMarkInfo();
    mark.setMarked(true);
    catalog.setMarkInfo(mark);
    PDViewerPreferences preferences = new PDViewerPreferences(new COSDictionary());
    preferences.setDisplayDocTitle(true);
    catalog.setViewerPreferences(preferences);
    catalog.setStructureTreeRoot(root);

    COSArray owners = new COSArray();
    for (PDStructureElement owner : sheet.owners) {
      owners.add(owner);
    }
    COSArray numbers = new COSArray();
    numbers.add(COSInteger.get(0));
    numbers.add(owners);
    COSDictionary parentTree = new COSDictionary();
    parentTree.setItem(COSName.NUMS, numbers);
    root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
    root.getCOSObject().setInt(COSName.PARENT_TREE_NEXT_KEY, 1);
    page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
    page.getCOSObject().setItem(COSName.getPDFName("Tabs"), COSName.S);

    Calendar approved = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
    approved.setTimeInMillis(request.snapshot().approvedAt().toEpochMilli());
    PDDocumentInformation info = new PDDocumentInformation();
    info.setTitle(PayslipText.documentTitle(request.locale(), request.voided()));
    info.setCreator("DivalHR");
    info.setProducer("DivalHR");
    info.setCreationDate(approved);
    info.setModificationDate(approved);
    pdf.setDocumentInformation(info);

    byte[] identifier = HexFormat.of().parseHex(request.snapshotDigest().substring(0, 32));
    COSString first = new COSString(identifier);
    COSString second = new COSString(identifier);
    COSArray ids = new COSArray();
    ids.add(first);
    ids.add(second);
    pdf.getDocument().getTrailer().setItem(COSName.ID, ids);
  }

  /**
   * Rewrites every filtered stream (the subset font programs and their character maps) as an
   * unfiltered copy of the same decoded bytes.
   */
  private static void unfilter(COSBase start, Set<COSBase> seen) throws IOException {
    COSBase node = start instanceof COSObject indirect ? indirect.getObject() : start;
    if (node == null || !seen.add(node)) {
      return;
    }
    if (node instanceof COSStream stream && stream.containsKey(COSName.FILTER)) {
      byte[] decoded;
      try (InputStream in = stream.createInputStream()) {
        decoded = in.readAllBytes();
      }
      stream.removeItem(COSName.FILTER);
      stream.removeItem(COSName.DECODE_PARMS);
      try (OutputStream sink = stream.createRawOutputStream()) {
        sink.write(decoded);
      }
    }
    if (node instanceof COSDictionary dictionary) {
      for (COSBase value : new ArrayList<>(dictionary.getValues())) {
        unfilter(value, seen);
      }
    } else if (node instanceof COSArray array) {
      for (COSBase value : new ArrayList<>(array.toList())) {
        unfilter(value, seen);
      }
    }
  }

  /** The embedded face, checked against its pinned digest. */
  private static byte[] face(String resource, String expected, boolean regular) throws IOException {
    byte[] cached = regular ? regularBytes : boldBytes;
    if (cached.length > 0) {
      return cached;
    }
    byte[] bytes;
    try (InputStream in = PayslipPdfRenderer.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new PayslipRenderException();
      }
      bytes = in.readAllBytes();
    }
    if (!expected.equals(sha256(bytes))) {
      throw new PayslipRenderException();
    }
    if (regular) {
      regularBytes = bytes;
    } else {
      boldBytes = bytes;
    }
    return bytes;
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
