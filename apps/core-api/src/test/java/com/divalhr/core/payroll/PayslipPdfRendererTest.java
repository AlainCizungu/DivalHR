package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.divalhr.core.payroll.application.PayslipPdf;
import com.divalhr.core.payroll.application.PayslipPdfRenderer;
import com.divalhr.core.payroll.application.PayslipPdfRenderer.Request;
import com.divalhr.core.payroll.application.PayslipRenderException;
import com.divalhr.core.payroll.domain.PayrollVoidReason;
import com.divalhr.core.payroll.domain.PayslipLocale;
import com.divalhr.core.payroll.domain.PayslipSnapshot;
import com.divalhr.core.payroll.domain.PayslipSnapshot.Component;
import com.divalhr.core.payroll.domain.PayslipText;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

/** MVP-094C: the payslip PDF is deterministic, bilingual, bounded and free of identifiers. */
class PayslipPdfRendererTest {

  private static final UUID PACKAGE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ENTITY = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID EMPLOYEE = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final Instant APPROVED = Instant.parse("2026-04-01T10:11:12.123456Z");
  private static final PayslipPdfRenderer RENDERER = new PayslipPdfRenderer();

  private static Component component(
      int seq, String id, String party, String category, long base, long amount, String... codes) {
    return new Component(seq, id, party, category, base, amount, List.of(codes));
  }

  private static List<Component> components() {
    return List.of(
        component(
            1,
            "CNSS_EMPLOYEE_PENSION",
            "EMPLOYEE",
            "SOCIAL_CONTRIBUTION",
            162000,
            8100,
            "RATE_APPLIED",
            "BASE_GROSS"),
        component(
            2,
            "CNSS_EMPLOYER_PENSION",
            "EMPLOYER",
            "SOCIAL_CONTRIBUTION",
            162000,
            8100,
            "RATE_APPLIED",
            "BASE_GROSS"),
        component(
            3,
            "CNSS_EMPLOYER_OCCUPATIONAL_RISK",
            "EMPLOYER",
            "SOCIAL_CONTRIBUTION",
            162000,
            2430,
            "RATE_APPLIED",
            "BASE_GROSS"),
        component(
            4,
            "CNSS_EMPLOYER_FAMILY_BENEFITS",
            "EMPLOYER",
            "SOCIAL_CONTRIBUTION",
            162000,
            10530,
            "RATE_APPLIED",
            "BASE_GROSS"),
        component(
            5,
            "IRPP_SALARY_WITHHOLDING",
            "EMPLOYEE",
            "INCOME_TAX",
            153900,
            4860,
            "PROGRESSIVE_APPLIED",
            "BASE_GROSS_LESS_EMPLOYEE_SOCIAL"));
  }

  private static PayslipSnapshot snapshot(String number) {
    return PayslipSnapshot.of(
        PACKAGE,
        ENTITY,
        "2026-03",
        APPROVED,
        "SYNTHETIC-TEST-DRC",
        1,
        EMPLOYEE,
        number,
        false,
        162000,
        components());
  }

  private static Request request(PayslipSnapshot snapshot, PayslipLocale locale, boolean voided) {
    PayrollVoidReason reason = voided ? PayrollVoidReason.SOURCE_CORRECTION : null;
    return new Request(snapshot, snapshot.digest(), locale, voided, reason, false);
  }

  private static byte[] render(PayslipLocale locale, boolean voided, boolean synthetic) {
    PayslipSnapshot snapshot = snapshot("E-0042");
    PayrollVoidReason reason = voided ? PayrollVoidReason.SOURCE_CORRECTION : null;
    Request request = new Request(snapshot, snapshot.digest(), locale, voided, reason, synthetic);
    return RENDERER.render(request).bytes();
  }

  private static String text(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return new PDFTextStripper().getText(document).replaceAll("\\s+", " ").strip();
    }
  }

  private static String raw(byte[] pdf) {
    return new String(pdf, StandardCharsets.ISO_8859_1);
  }

  @Test
  void theDocumentIsAPdfWithOneTaggedPage() throws IOException {
    byte[] pdf = render(PayslipLocale.EN, false, false);
    assertThat(raw(pdf)).startsWith("%PDF-1.7");
    assertThat(raw(pdf).stripTrailing()).endsWith("%%EOF");
    try (PDDocument document = Loader.loadPDF(pdf)) {
      assertThat(document.getNumberOfPages()).isEqualTo(1);
      assertThat(document.isEncrypted()).isFalse();
      assertThat(document.getDocumentCatalog().getMetadata()).isNull();
      assertThat(document.getDocumentCatalog().getMarkInfo().isMarked()).isTrue();
      assertThat(document.getDocumentCatalog().getViewerPreferences().displayDocTitle()).isTrue();
      assertThat(document.getDocumentCatalog().getStructureTreeRoot()).isNotNull();
      assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(595f);
      assertThat(document.getPage(0).getMediaBox().getHeight()).isEqualTo(842f);
    }
  }

  @Test
  void theStructureTreeTagsTheHeadingsParagraphsAndTables() throws IOException {
    try (PDDocument document = Loader.loadPDF(render(PayslipLocale.EN, true, true))) {
      COSDictionary root = document.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
      Set<String> types = new TreeSet<>();
      collect(root.getDictionaryObject(COSName.K), types);
      assertThat(types).contains("Document", "H1", "H2", "P", "Table", "TR", "TH", "TD");
      assertThat(root.getDictionaryObject(COSName.getPDFName("ParentTree"))).isNotNull();
    }
  }

  private static void collect(COSBase node, Set<String> types) {
    if (node instanceof COSArray array) {
      for (int i = 0; i < array.size(); i++) {
        collect(array.getObject(i), types);
      }
    } else if (node instanceof COSDictionary dictionary) {
      String type = dictionary.getNameAsString(COSName.S);
      if (type != null) {
        types.add(type);
      }
      collect(dictionary.getDictionaryObject(COSName.K), types);
    }
  }

  @Test
  void theTextIsSetInTheTwoEmbeddedSubsetFacesAndNothingIsCompressed() throws IOException {
    for (PayslipLocale locale : PayslipLocale.values()) {
      byte[] pdf = render(locale, true, true);
      assertThat(raw(pdf)).doesNotContain("/Helvetica").doesNotContain("/Type1");
      assertThat(raw(pdf)).doesNotContain("/Filter").doesNotContain("/FlateDecode");
      try (PDDocument document = Loader.loadPDF(pdf)) {
        Set<String> names = new TreeSet<>();
        for (COSName key : document.getPage(0).getResources().getFontNames()) {
          PDFont font = document.getPage(0).getResources().getFont(key);
          assertThat(font.isEmbedded()).isTrue();
          assertThat(font.getName()).matches("[A-Z]{6}\\+DejaVuSans(-Bold)?");
          names.add(font.getName().substring(7));
        }
        assertThat(names).containsExactly("DejaVuSans", "DejaVuSans-Bold");
      }
    }
  }

  @Test
  void theEmbeddedFacesAreTheRepositoryOwnedFilesWithThePinnedDigests() throws Exception {
    assertThat(sha256("/fonts/DejaVuSans.ttf"))
        .isEqualTo("ae7b7855e115a5966d8b1b3f80f254ccc117ec86f9965e202ee2940453837280");
    assertThat(sha256("/fonts/DejaVuSans-Bold.ttf"))
        .isEqualTo("5c1247acef7f2b8522a31742c76d6adcb5569bacc0be7ceaa4dc39dd252ce895");
    try (InputStream licence =
        PayslipPdfRenderer.class.getResourceAsStream("/fonts/DejaVu-LICENSE.txt")) {
      assertThat(licence).isNotNull();
      assertThat(new String(licence.readAllBytes(), StandardCharsets.UTF_8))
          .contains("Bitstream Vera");
    }
  }

  private static String sha256(String resource) throws Exception {
    try (InputStream in = PayslipPdfRenderer.class.getResourceAsStream(resource)) {
      assertThat(in).as(resource).isNotNull();
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
    }
  }

  @Test
  void theSameInputGivesIdenticalBytesEvenFromAnotherRendererInstance() {
    for (PayslipLocale locale : PayslipLocale.values()) {
      for (boolean voided : new boolean[] {false, true}) {
        byte[] first = render(locale, voided, true);
        byte[] second = render(locale, voided, true);
        assertThat(second).as(locale + " " + voided).isEqualTo(first);
        PayslipSnapshot snapshot = snapshot("E-0042");
        byte[] other = new PayslipPdfRenderer().render(request(snapshot, locale, voided)).bytes();
        byte[] same = RENDERER.render(request(snapshot, locale, voided)).bytes();
        assertThat(other).isEqualTo(same);
      }
    }
  }

  @Test
  void anotherLocaleStatusOrSyntheticFlagChangesTheBytesButNeverTheAmounts() throws IOException {
    byte[] en = render(PayslipLocale.EN, false, false);
    byte[] fr = render(PayslipLocale.FR, false, false);
    assertThat(fr).isNotEqualTo(en);
    assertThat(render(PayslipLocale.EN, true, false)).isNotEqualTo(en);
    assertThat(render(PayslipLocale.EN, false, true)).isNotEqualTo(en);
    for (String amount :
        List.of(
            "162000 CDF",
            "8100 CDF",
            "4860 CDF",
            "12960 CDF",
            "149040 CDF",
            "21060 CDF",
            "162000",
            "10530",
            "2430",
            "153900",
            "2026-03",
            "E-0042")) {
      assertThat(text(en)).as("EN " + amount).contains(amount);
      assertThat(text(fr)).as("FR " + amount).contains(amount);
    }
  }

  @Test
  void theAmountsAndWordingOfTheEnglishDocumentAreExtractable() throws IOException {
    String text = text(render(PayslipLocale.EN, false, false));
    assertThat(text).contains("Payslip").contains("Status: Approved payroll");
    assertThat(text).contains("Contractual gross 162000 CDF");
    assertThat(text).contains("Employee CNSS contribution 8100 CDF");
    assertThat(text).contains("IRPP withholding 4860 CDF");
    assertThat(text).contains("Total employee deductions 12960 CDF");
    assertThat(text).contains("Approved net 149040 CDF");
    assertThat(text).contains("Total employer contributions 21060 CDF");
    assertThat(text).contains(PayslipText.employerContributionsNote(PayslipLocale.EN));
    assertThat(text).contains("Statutory calculation based on rule pack SYNTHETIC-TEST-DRC v1");
    assertThat(text).contains("Not proof of payment");
  }

  @Test
  void theFrenchDocumentUsesTheFrenchWordingWithTheSameAmounts() throws IOException {
    String text = text(render(PayslipLocale.FR, false, false));
    assertThat(text).contains("Bulletin de paie").contains("Statut: Paie approuvée");
    assertThat(text).contains("Salaire brut contractuel 162000 CDF");
    assertThat(text).contains("Net approuvé 149040 CDF");
    assertThat(text).contains("Total des cotisations patronales 21060 CDF");
    assertThat(text).contains("Calcul statutaire fondé sur le jeu de règles");
    assertThat(text).contains("Ne constitue pas une preuve de paiement");
    assertThat(text).doesNotContain("Payslip").doesNotContain("Approved");
  }

  @Test
  void theNotProofOfPaymentSentenceAndTheRulePackStatementAreOnEveryVariant() throws IOException {
    for (PayslipLocale locale : PayslipLocale.values()) {
      for (boolean voided : new boolean[] {false, true}) {
        for (boolean synthetic : new boolean[] {false, true}) {
          String text = text(render(locale, voided, synthetic));
          String where = locale + " voided=" + voided + " synthetic=" + synthetic;
          assertThat(text).as(where).contains(PayslipText.notProofOfPayment(locale));
          assertThat(text)
              .as(where)
              .contains(PayslipText.rulePackStatement(locale, "SYNTHETIC-TEST-DRC", 1));
          assertThat(text).as(where).contains(PayslipText.employerContributionsNote(locale));
        }
      }
    }
  }

  @Test
  void aVoidedDocumentIsPlainlyVoidedAndNeverShownAsApproved() throws IOException {
    String en = text(render(PayslipLocale.EN, true, false));
    assertThat(en).contains("VOIDED").contains("Status: VOIDED");
    assertThat(en).contains("was voided").contains("Reason: Correction of the payroll run.");
    assertThat(en).doesNotContain("Approved payroll");
    String fr = text(render(PayslipLocale.FR, true, false));
    assertThat(fr).contains("ANNULÉ").contains("Statut: ANNULÉ");
    assertThat(fr).contains("Motif : Correction du calcul de paie.");
    assertThat(fr).doesNotContain("Paie approuvée");
    assertThat(text(render(PayslipLocale.EN, false, false))).doesNotContain("VOIDED");
    assertThat(text(render(PayslipLocale.FR, false, false))).doesNotContain("ANNULÉ");
    // The same amounts remain on the voided copy: only the status changes.
    assertThat(en).contains("149040 CDF");
    assertThat(fr).contains("149040 CDF");
  }

  @Test
  void aVoidedDocumentWithoutAReasonStatesNoReason() throws IOException {
    PayslipSnapshot snapshot = snapshot("E-0042");
    Request request = new Request(snapshot, snapshot.digest(), PayslipLocale.EN, true, null, false);
    String text = text(RENDERER.render(request).bytes());
    assertThat(text).contains("was voided").doesNotContain("Reason:");
  }

  @Test
  void aSyntheticPackIsAnnouncedAndARealOneIsNot() throws IOException {
    String en = text(render(PayslipLocale.EN, false, true));
    assertThat(en).contains(PayslipText.syntheticNotice(PayslipLocale.EN));
    String fr = text(render(PayslipLocale.FR, false, true));
    assertThat(fr).contains(PayslipText.syntheticNotice(PayslipLocale.FR));
    assertThat(text(render(PayslipLocale.EN, false, false))).doesNotContain("Synthetic test data");
    assertThat(text(render(PayslipLocale.FR, false, false)))
        .doesNotContain("Données de test synthétiques");
  }

  @Test
  void theDigestOfTheSnapshotIsPrintedWhole() throws IOException {
    String digest = snapshot("E-0042").digest();
    for (PayslipLocale locale : PayslipLocale.values()) {
      String compact = text(render(locale, false, false)).replace(" ", "");
      assertThat(compact).contains(digest);
    }
  }

  @Test
  void anExcludedEmployeeIsSaidToBeSubjectToNeitherCnssNorIrpp() throws IOException {
    PayslipSnapshot excluded =
        PayslipSnapshot.of(
            PACKAGE,
            ENTITY,
            "2026-03",
            APPROVED,
            "SYNTHETIC-TEST-DRC",
            1,
            EMPLOYEE,
            "E-0099",
            true,
            50000,
            List.of());
    String en = text(RENDERER.render(request(excluded, PayslipLocale.EN, false)).bytes());
    assertThat(en).contains("This employee is subject to neither CNSS nor IRPP.");
    assertThat(en).contains("Approved net 50000 CDF").contains("Total employee deductions 0 CDF");
    assertThat(en).doesNotContain("Party");
    String fr = text(RENDERER.render(request(excluded, PayslipLocale.FR, false)).bytes());
    assertThat(fr).contains("Ce salarié n'est soumis ni à la CNSS ni à l'IRPP.");
  }

  @Test
  void theInfoDictionaryHoldsOnlyAGenericTitleAndTheConstantCreatorAndProducer()
      throws IOException {
    for (PayslipLocale locale : PayslipLocale.values()) {
      for (boolean voided : new boolean[] {false, true}) {
        try (PDDocument document = Loader.loadPDF(render(locale, voided, true))) {
          PDDocumentInformation info = document.getDocumentInformation();
          String where = locale + " voided=" + voided;
          assertThat(info.getMetadataKeys())
              .as(where)
              .containsExactlyInAnyOrder("Title", "Creator", "Producer", "CreationDate", "ModDate");
          String title = PayslipText.documentTitle(locale, voided);
          assertThat(info.getTitle()).as(where).isEqualTo(title);
          assertThat(info.getCreator()).isEqualTo("DivalHR");
          assertThat(info.getProducer()).isEqualTo("DivalHR");
          assertThat(info.getAuthor()).isNull();
          assertThat(info.getSubject()).isNull();
          assertThat(info.getKeywords()).isNull();
          for (String key : List.of("Title", "Creator", "Producer")) {
            String value = info.getCustomMetadataValue(key);
            assertThat(value).as(where + " " + key).matches("[\\p{L}0-9 :\\-]+");
          }
        }
      }
    }
  }

  @Test
  void theDocumentLanguageIsDeclaredAndTheDatesAreTheApprovalInstant() throws IOException {
    for (PayslipLocale locale : PayslipLocale.values()) {
      byte[] pdf = render(locale, false, false);
      try (PDDocument document = Loader.loadPDF(pdf)) {
        assertThat(document.getDocumentCatalog().getLanguage()).isEqualTo(locale.code());
      }
      String id = snapshot("E-0042").digest().substring(0, 32);
      try (PDDocument document = Loader.loadPDF(pdf)) {
        Instant approved = APPROVED.truncatedTo(ChronoUnit.SECONDS);
        assertThat(document.getDocumentInformation().getCreationDate().toInstant())
            .isEqualTo(approved);
        assertThat(document.getDocumentInformation().getModificationDate().toInstant())
            .isEqualTo(approved);
        COSArray ids = document.getDocument().getTrailer().getCOSArray(COSName.ID);
        assertThat(ids.size()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
          assertThat(((COSString) ids.getObject(i)).toHexString().toLowerCase(Locale.ROOT))
              .isEqualTo(id);
        }
      }
    }
  }

  @Test
  void noIdentifierPathEmailOrTokenIsInTheBytesOrTheMetadata() throws IOException {
    List<String> identifiers = new ArrayList<>();
    for (UUID id : List.of(EMPLOYEE, PACKAGE, ENTITY)) {
      identifiers.add(id.toString());
      identifiers.add(id.toString().toUpperCase(Locale.ROOT));
      identifiers.add(id.toString().replace("-", ""));
      byte[] ascii = id.toString().getBytes(StandardCharsets.US_ASCII);
      identifiers.add(HexFormat.of().withUpperCase().formatHex(ascii));
      byte[] utf16 = id.toString().getBytes(StandardCharsets.UTF_16BE);
      identifiers.add(HexFormat.of().withUpperCase().formatHex(utf16));
    }
    Pattern email = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    Pattern path =
        Pattern.compile("(?i)(/home/|/tmp/|/var/|/etc/|/users/|[a-z]:\\\\|file:|\\.\\./)");
    Pattern token =
        Pattern.compile(
            "(?i)(eyJ[A-Za-z0-9_-]{8,}|bearer |[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.)");
    for (PayslipLocale locale : PayslipLocale.values()) {
      for (boolean voided : new boolean[] {false, true}) {
        String raw = raw(render(locale, voided, true));
        String where = locale + " voided=" + voided;
        for (String identifier : identifiers) {
          assertThat(raw).as(where + " " + identifier).doesNotContain(identifier);
        }
        assertThat(email.matcher(raw).find()).as(where + " email").isFalse();
        assertThat(path.matcher(raw).find()).as(where + " path").isFalse();
        assertThat(token.matcher(raw).find()).as(where + " token").isFalse();
        assertThat(raw).doesNotContain("localhost").doesNotContain("http");
        assertThat(raw).doesNotContain("/Author").doesNotContain("/Subject");
        assertThat(raw).doesNotContain("/Keywords").doesNotContain("/Metadata");
        assertThat(raw).doesNotContain("/URI").doesNotContain("/JavaScript");
        assertThat(raw).doesNotContain("/Launch").doesNotContain("/EmbeddedFile");
        assertThat(raw).doesNotContain("/OpenAction").doesNotContain("/Names");
      }
    }
  }

  @Test
  void theIdentifiersOfTheSnapshotAreNeverInTheExtractedText() throws IOException {
    for (PayslipLocale locale : PayslipLocale.values()) {
      String text = text(render(locale, true, true));
      for (UUID id : List.of(EMPLOYEE, PACKAGE, ENTITY)) {
        assertThat(text).doesNotContain(id.toString());
      }
    }
  }

  @Test
  void noForbiddenPhraseIsInTheExtractedText() throws IOException {
    Pattern english =
        Pattern.compile(
            "(?<!\\p{L})paid(?!\\p{L})|payment\\s+completed|(?<!\\p{L})filed(?!\\p{L})"
                + "|tax[\\s-]+compliant|government[\\s-]+approved",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    Pattern french =
        Pattern.compile(
            "(?<!\\p{L})pay[ée]e?s?(?!\\p{L})|paiement\\s+effectu[ée]"
                + "|(?<!\\p{L})d[ée]clar[ée]e?s?(?!\\p{L})|conforme\\s+fiscalement"
                + "|approuv[ée]e?s?\\s+par\\s+(le\\s+gouvernement|l['’]État)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    for (boolean voided : new boolean[] {false, true}) {
      for (boolean synthetic : new boolean[] {false, true}) {
        assertThat(english.matcher(text(render(PayslipLocale.EN, voided, synthetic))).find())
            .isFalse();
        assertThat(french.matcher(text(render(PayslipLocale.FR, voided, synthetic))).find())
            .isFalse();
      }
    }
  }

  @Test
  void theSizeIsBoundedBy256Kib() {
    assertThat(PayslipPdfRenderer.MAX_BYTES).isEqualTo(262_144);
    assertThat(PayslipPdfRenderer.TIMEOUT).isEqualTo(Duration.ofSeconds(5));
    for (PayslipLocale locale : PayslipLocale.values()) {
      PayslipPdf pdf = RENDERER.render(request(snapshot("E-0042"), locale, true));
      assertThat(pdf.size()).isLessThanOrEqualTo(PayslipPdfRenderer.MAX_BYTES);
      assertThat(pdf.size()).isEqualTo(pdf.bytes().length);
    }
  }

  @Test
  void unicodeEmployeeTextIsPrintedAsTypedAndNeverAsAQuestionMark() throws IOException {
    String typed = "Ж-é-€-Ɛɔ-Ŋ-1";
    PayslipSnapshot unicode = snapshot(typed);
    for (PayslipLocale locale : PayslipLocale.values()) {
      String shown = text(RENDERER.render(request(unicode, locale, false)).bytes());
      assertThat(shown).contains(typed).doesNotContain("?");
      assertThat(shown).contains("149040 CDF");
    }
    PayslipSnapshot euro = snapshot("E-€1 (a) \\b");
    String shown = text(RENDERER.render(request(euro, PayslipLocale.FR, false)).bytes());
    assertThat(shown).contains("E-€1 (a) \\b");
    // The canonical snapshot is untouched by the rendering.
    assertThat(unicode.canonicalText()).contains(typed);
  }

  /** One printed line with its horizontal extent in points. */
  private record Line(String text, float left, float right) {}

  private static List<Line> lines(byte[] pdf) throws IOException {
    List<Line> out = new ArrayList<>();
    try (PDDocument document = Loader.loadPDF(pdf)) {
      new PDFTextStripper() {
        @Override
        protected void writeString(String text, List<TextPosition> positions) {
          TextPosition first = positions.get(0);
          TextPosition last = positions.get(positions.size() - 1);
          out.add(new Line(text, first.getX(), last.getX() + last.getWidth()));
        }
      }.getText(document);
    }
    return out;
  }

  /** The lines of the employee number block: from its label up to the rule pack statement. */
  private static List<Line> numberBlock(PayslipLocale locale, String number) throws IOException {
    PayslipSnapshot snapshot = snapshot(number);
    List<Line> all = lines(RENDERER.render(request(snapshot, locale, false)).bytes());
    String label = PayslipText.Label.EMPLOYEE_NUMBER.in(locale) + ": ";
    String stop = PayslipText.rulePackStatement(locale, "SYNTHETIC-TEST-DRC", 1).substring(0, 12);
    List<Line> block = new ArrayList<>();
    boolean inside = false;
    for (Line line : all) {
      if (line.text().startsWith(label)) {
        inside = true;
      } else if (line.text().startsWith(stop)) {
        inside = false;
      }
      if (inside) {
        block.add(line);
      }
    }
    return block;
  }

  private static List<String> texts(List<Line> block) {
    return block.stream().map(Line::text).toList();
  }

  @Test
  void theLongestEmployeeNumberIsWrappedExactlyInBothLanguages() throws IOException {
    String wide = "W".repeat(64);
    assertThat(texts(numberBlock(PayslipLocale.EN, wide)))
        .containsExactly("Employee number: " + "W".repeat(40), "W".repeat(24));
    assertThat(texts(numberBlock(PayslipLocale.FR, wide)))
        .containsExactly("Numéro d'employé: " + "W".repeat(39), "W".repeat(25));
    String capitals = "M".repeat(64);
    assertThat(texts(numberBlock(PayslipLocale.EN, capitals)))
        .containsExactly("Employee number: " + "M".repeat(45), "M".repeat(19));
    assertThat(texts(numberBlock(PayslipLocale.FR, capitals)))
        .containsExactly("Numéro d'employé: " + "M".repeat(45), "M".repeat(19));
  }

  @Test
  void everyLongEmployeeNumberIsPrintedWholeInsideTheMarginsAndNeverChanged() throws IOException {
    List<String> numbers =
        List.of(
            "W".repeat(64),
            "M".repeat(64),
            "Ж".repeat(64),
            "i".repeat(64),
            "A.B/C-".repeat(10) + "A.B/",
            "0123456789".repeat(6) + "0123",
            "E-0042",
            "E");
    for (PayslipLocale locale : PayslipLocale.values()) {
      String label = PayslipText.Label.EMPLOYEE_NUMBER.in(locale) + ": ";
      for (String number : numbers) {
        List<Line> block = numberBlock(locale, number);
        String where = locale + " " + number;
        assertThat(block).as(where).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(block.get(0).text()).as(where).startsWith(label);
        assertThat(String.join("", texts(block)).substring(label.length())).as(where).isEqualTo(number);
        for (Line line : block) {
          assertThat(line.left()).as(where).isEqualTo(50f, within(0.01f));
          assertThat(line.right()).as(where).isLessThanOrEqualTo(545.01f);
        }
        String all = text(RENDERER.render(request(snapshot(number), locale, false)).bytes());
        assertThat(all).as(where).contains("149040 CDF").contains("Total");
      }
    }
  }

  @Test
  void anOrdinaryEmployeeNumberStaysOnTheLabelLine() throws IOException {
    assertThat(texts(numberBlock(PayslipLocale.EN, "E-0042")))
        .containsExactly("Employee number: E-0042");
    assertThat(texts(numberBlock(PayslipLocale.FR, "E-0042")))
        .containsExactly("Numéro d'employé: E-0042");
  }

  @Test
  void aCharacterTheFontDoesNotCoverFailsTheRenderInsteadOfPrintingSomethingElse() {
    for (String number : List.of("E中-1", "E-あ", "E-한", "E-\u0001-1", "E-\u202e1")) {
      PayslipSnapshot odd = snapshot(number);
      for (PayslipLocale locale : PayslipLocale.values()) {
        assertThatThrownBy(() -> RENDERER.render(request(odd, locale, false)))
            .as(number)
            .isInstanceOf(PayslipRenderException.class)
            .hasMessage("payslip render failed");
      }
    }
  }

  @Test
  void aDocumentThatCannotFitOnePageIsAFailureNotATruncatedPayslip() {
    List<Component> many = new ArrayList<>();
    for (int seq = 1; seq <= 200; seq++) {
      many.add(component(seq, "RULE_" + seq, "EMPLOYER", "SOCIAL_CONTRIBUTION", 1, 1, "A"));
    }
    PayslipSnapshot snapshot =
        PayslipSnapshot.of(
            PACKAGE,
            ENTITY,
            "2026-03",
            APPROVED,
            "SYNTHETIC-TEST-DRC",
            1,
            EMPLOYEE,
            "E-1",
            false,
            1000,
            many);
    assertThatThrownBy(() -> RENDERER.render(request(snapshot, PayslipLocale.EN, false)))
        .isInstanceOf(PayslipRenderException.class);
  }

  @Test
  void aDocumentOverTheSizeLimitIsAFailure() {
    PayslipSnapshot huge = snapshot("A".repeat(150_000));
    assertThatThrownBy(() -> RENDERER.render(request(huge, PayslipLocale.EN, false)))
        .isInstanceOf(PayslipRenderException.class);
  }

  @Test
  void malformedInputIsARenderFailureWithNoDetail() {
    PayslipSnapshot snapshot = snapshot("E-0042");
    List<Request> malformed =
        List.of(
            new Request(snapshot, "short", PayslipLocale.EN, false, null, false),
            new Request(snapshot, "", PayslipLocale.EN, false, null, false),
            new Request(snapshot, null, PayslipLocale.EN, false, null, false),
            new Request(null, snapshot.digest(), PayslipLocale.EN, false, null, false),
            new Request(snapshot, snapshot.digest(), null, false, null, false));
    for (Request request : malformed) {
      assertThatThrownBy(() -> RENDERER.render(request))
          .isInstanceOf(PayslipRenderException.class)
          .hasMessage("payslip render failed");
    }
    PayslipRenderException failure = new PayslipRenderException();
    assertThat(failure.getCause()).isNull();
    assertThat(failure.getStackTrace()).isEmpty();
  }

  @Test
  void theReturnedBytesAreACopy() {
    PayslipPdf pdf = RENDERER.render(request(snapshot("E-0042"), PayslipLocale.EN, false));
    byte[] first = pdf.bytes();
    first[0] = 0;
    assertThat(pdf.bytes()[0]).isEqualTo((byte) '%');
    assertThat(pdf.toString()).doesNotContain("E-0042").doesNotContain("149040");
    Request request = request(snapshot("E-0042"), PayslipLocale.EN, false);
    assertThat(request.toString()).doesNotContain("E-0042").doesNotContain("149040");
  }
}
