package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.PayrollMoney;
import com.divalhr.core.payroll.domain.PayrollVoidReason;
import com.divalhr.core.payroll.domain.PayslipLocale;
import com.divalhr.core.payroll.domain.PayslipSnapshot;
import com.divalhr.core.payroll.domain.PayslipText;
import com.divalhr.core.payroll.domain.PayslipText.Label;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Renders one payslip as a deterministic, tagged, single-page A4 PDF from its immutable snapshot
 * (MVP-094C, ADR 0035 section 12). The same snapshot, status and locale always give the same bytes:
 * the writer is repository-owned (a classic PDF 1.7 file with no compression, no embedded font and
 * no remote asset), the trailer identifier is the first 16 bytes of the snapshot digest twice, and
 * both dates are the approval instant. Text is Standard-14 Helvetica in WinAnsi; a character
 * outside WinAnsi prints as {@code ?} in the PDF only. The Info dictionary holds only a generic
 * localized title and the constant creator and producer {@code DivalHR}: no name, number, period,
 * subject, token, e-mail, path, host or filename. The structure tree is a minimal tagged outline
 * ({@code Document}, {@code H1}, {@code H2}, {@code P}, {@code Table}, {@code TR}, {@code TH},
 * {@code TD}); full PDF/UA conformance is not claimed. The amounts are the canonical whole-franc
 * integers in both locales; only the wording changes.
 *
 * <p>Restricted financial and HR data: nothing is logged here and no exception carries content.
 */
@Component
public class PayslipPdfRenderer {

  /** The largest document, in bytes. */
  public static final int MAX_BYTES = 256 * 1024;

  /** The longest time a render may take. */
  public static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static final Charset WIN_ANSI = Charset.forName("windows-1252");
  private static final double PAGE_WIDTH = 595;
  private static final double PAGE_HEIGHT = 842;
  private static final double LEFT = 50;
  private static final double RIGHT = 545;
  private static final double BOTTOM = 48;
  private static final DateTimeFormatter PDF_DATE =
      DateTimeFormatter.ofPattern("'D:'uuuuMMddHHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

  /** Helvetica advance widths of the ASCII characters 32 to 126, per 1000 em. */
  private static final int[] WIDTHS = {
    278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556,
    556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667,
    611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667,
    667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500,
    222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584
  };

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

  /** One element of the structure tree. */
  private static final class Node {
    private final String type;
    private final int parent;
    private final List<Integer> mcids = new ArrayList<>();

    Node(String type, int parent) {
      this.type = type;
      this.parent = parent;
    }
  }

  /** The single page under construction. */
  private static final class Sheet {
    private final StringBuilder content = new StringBuilder(8192);
    private final List<Node> nodes = new ArrayList<>();
    private final List<Node> owners = new ArrayList<>();
    private double y = PAGE_HEIGHT - 56;

    int node(String type, int parent) {
      nodes.add(new Node(type, parent));
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
    } catch (RuntimeException failure) {
      throw new PayslipRenderException();
    }
  }

  // ------------------------------------------------------------------------------------------
  // Layout
  // ------------------------------------------------------------------------------------------

  private static byte[] document(Request request) {
    PayslipSnapshot snapshot = request.snapshot();
    PayslipLocale locale = request.locale();
    Sheet sheet = new Sheet();
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

    return write(sheet, request);
  }

  private static void banner(Sheet sheet, int doc, String word) {
    double top = sheet.y + 4;
    double height = 26;
    sheet
        .content
        .append("/Artifact BMC 1.5 w ")
        .append(num(LEFT))
        .append(' ')
        .append(num(top - height))
        .append(' ')
        .append(num(RIGHT - LEFT))
        .append(' ')
        .append(num(height))
        .append(" re S EMC\n");
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
    sheet
        .content
        .append("/Artifact BMC 0.5 w ")
        .append(num(LEFT))
        .append(' ')
        .append(num(y))
        .append(" m ")
        .append(num(RIGHT))
        .append(' ')
        .append(num(y))
        .append(" l S EMC\n");
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
          fit(PayslipText.componentLabel(locale, component.id()), 8, party - LEFT - 6));
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
    for (String line : wrap(words, bold, size, RIGHT - LEFT)) {
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
    Node element = sheet.nodes.get(node);
    int mcid = sheet.owners.size();
    sheet.owners.add(element);
    element.mcids.add(mcid);
    String tag = "TH".equals(element.type) || "TD".equals(element.type) ? "P" : element.type;
    sheet
        .content
        .append('/')
        .append(tag)
        .append(" <</MCID ")
        .append(mcid)
        .append(">> BDC BT /")
        .append(bold ? "F2" : "F1")
        .append(' ')
        .append(num(size))
        .append(" Tf ")
        .append(num(x))
        .append(' ')
        .append(num(y))
        .append(" Td <")
        .append(HexFormat.of().withUpperCase().formatHex(bytes(words)))
        .append("> Tj ET EMC\n");
  }

  private static void right(
      Sheet sheet, int node, boolean bold, double size, double edge, double y, String words) {
    text(sheet, node, bold, size, edge - width(words, bold, size), y, words);
  }

  /** The text as WinAnsi bytes; control characters and anything unmappable become {@code ?}. */
  private static byte[] bytes(String words) {
    StringBuilder clean = new StringBuilder(words.length());
    for (int i = 0; i < words.length(); i++) {
      char c = words.charAt(i);
      clean.append(c < 0x20 || c == 0x7F ? '?' : c);
    }
    return clean.toString().getBytes(WIN_ANSI);
  }

  /** The text as it will print: what the WinAnsi bytes decode to. */
  private static String printable(String words) {
    return new String(bytes(words), WIN_ANSI);
  }

  private static double width(String words, boolean bold, double size) {
    String shown = printable(words);
    double units = 0;
    for (int i = 0; i < shown.length(); i++) {
      char c = shown.charAt(i);
      if (c >= 32 && c <= 126) {
        units += WIDTHS[c - 32];
      } else if (c == '’') {
        units += 222;
      } else {
        units += 556;
      }
    }
    return units / 1000.0 * size * (bold ? 1.08 : 1.0);
  }

  /** The text cut at the last character that fits the width. */
  private static String fit(String words, double size, double max) {
    String shown = printable(words);
    while (shown.length() > 1 && width(shown, false, size) > max) {
      shown = shown.substring(0, shown.length() - 1);
    }
    return shown;
  }

  private static List<String> wrap(String words, boolean bold, double size, double max) {
    List<String> lines = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    for (String word : printable(words).split(" ")) {
      if (word.isEmpty()) {
        continue;
      }
      String candidate = line.length() == 0 ? word : line + " " + word;
      if (line.length() > 0 && width(candidate, bold, size) > max) {
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

  private static String num(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  // ------------------------------------------------------------------------------------------
  // File
  // ------------------------------------------------------------------------------------------

  private static final int CATALOG = 1;
  private static final int PAGES = 2;
  private static final int PAGE = 3;
  private static final int FONT_REGULAR = 4;
  private static final int FONT_BOLD = 5;
  private static final int CONTENTS = 6;
  private static final int INFO = 7;
  private static final int STRUCT_ROOT = 8;
  private static final int FIRST_ELEMENT = 9;

  private static byte[] write(Sheet sheet, Request request) {
    byte[] content = sheet.content.toString().getBytes(StandardCharsets.US_ASCII);
    int objects = FIRST_ELEMENT + sheet.nodes.size() - 1;
    List<byte[]> bodies = new ArrayList<>();
    String lang = request.locale().code();
    String date = PDF_DATE.format(request.snapshot().approvedAt());

    bodies.add(
        ascii(
            "<< /Type /Catalog /Pages 2 0 R /Lang ("
                + lang
                + ") /MarkInfo << /Marked true >> /StructTreeRoot 8 0 R"
                + " /ViewerPreferences << /DisplayDocTitle true >> >>"));
    bodies.add(ascii("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"));
    bodies.add(
        ascii(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R"
                + " /F2 5 0 R >> >> /Contents 6 0 R /StructParents 0 /Tabs /S >>"));
    bodies.add(
        ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
    bodies.add(
        ascii(
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold"
                + " /Encoding /WinAnsiEncoding >>"));
    ByteArrayOutputStream stream = new ByteArrayOutputStream(content.length + 64);
    stream.writeBytes(ascii("<< /Length " + content.length + " >>\nstream\n"));
    stream.writeBytes(content);
    stream.writeBytes(ascii("\nendstream"));
    bodies.add(stream.toByteArray());
    bodies.add(
        ascii(
            "<< /Title "
                + textString(PayslipText.documentTitle(request.locale(), request.voided()))
                + " /Creator (DivalHR) /Producer (DivalHR) /CreationDate ("
                + date
                + ") /ModDate ("
                + date
                + ") >>"));

    StringBuilder tree =
        new StringBuilder("<< /Type /StructTreeRoot /K 9 0 R /ParentTree << /Nums [0 [");
    for (Node owner : sheet.owners) {
      tree.append(' ').append(FIRST_ELEMENT + sheet.nodes.indexOf(owner)).append(" 0 R");
    }
    tree.append(" ]] >> /ParentTreeNextKey 1 >>");
    bodies.add(ascii(tree.toString()));

    for (int i = 0; i < sheet.nodes.size(); i++) {
      Node node = sheet.nodes.get(i);
      int parent = node.parent < 0 ? STRUCT_ROOT : FIRST_ELEMENT + node.parent;
      StringBuilder element =
          new StringBuilder("<< /Type /StructElem /S /")
              .append(node.type)
              .append(" /P ")
              .append(parent)
              .append(" 0 R /Pg ")
              .append(PAGE)
              .append(" 0 R /K ");
      if (!node.mcids.isEmpty()) {
        if (node.mcids.size() == 1) {
          element.append(node.mcids.get(0));
        } else {
          element.append('[');
          for (int mcid : node.mcids) {
            element.append(' ').append(mcid);
          }
          element.append(" ]");
        }
      } else {
        element.append('[');
        for (int child = 0; child < sheet.nodes.size(); child++) {
          if (sheet.nodes.get(child).parent == i) {
            element.append(' ').append(FIRST_ELEMENT + child).append(" 0 R");
          }
        }
        element.append(" ]");
      }
      element.append(" >>");
      bodies.add(ascii(element.toString()));
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 4096);
    out.writeBytes(ascii("%PDF-1.7\n%"));
    out.write(0xE2);
    out.write(0xE3);
    out.write(0xCF);
    out.write(0xD3);
    out.write('\n');
    int[] offsets = new int[objects + 1];
    for (int i = 0; i < bodies.size(); i++) {
      offsets[i + 1] = out.size();
      out.writeBytes(ascii((i + 1) + " 0 obj\n"));
      out.writeBytes(bodies.get(i));
      out.writeBytes(ascii("\nendobj\n"));
    }
    int xref = out.size();
    StringBuilder table = new StringBuilder("xref\n0 ").append(objects + 1).append('\n');
    table.append("0000000000 65535 f \n");
    for (int i = 1; i <= objects; i++) {
      table.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]));
    }
    String id = request.snapshotDigest().substring(0, 32);
    table
        .append("trailer\n<< /Size ")
        .append(objects + 1)
        .append(" /Root ")
        .append(CATALOG)
        .append(" 0 R /Info ")
        .append(INFO)
        .append(" 0 R /ID [<")
        .append(id)
        .append("> <")
        .append(id)
        .append(">] >>\nstartxref\n")
        .append(xref)
        .append("\n%%EOF\n");
    out.writeBytes(ascii(table.toString()));
    return out.toByteArray();
  }

  /** A PDF text string: plain when printable ASCII, otherwise UTF-16BE with a byte order mark. */
  private static String textString(String words) {
    boolean plain = true;
    for (int i = 0; i < words.length(); i++) {
      char c = words.charAt(i);
      if (c < 32 || c > 126 || c == '(' || c == ')' || c == '\\') {
        plain = false;
      }
    }
    if (plain) {
      return "(" + words + ")";
    }
    byte[] utf16 = words.getBytes(StandardCharsets.UTF_16BE);
    return "<FEFF" + HexFormat.of().withUpperCase().formatHex(utf16) + ">";
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }
}
