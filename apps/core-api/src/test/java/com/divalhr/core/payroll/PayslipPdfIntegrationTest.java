package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.payroll.PayrollPackageWorld.Linked;
import com.divalhr.core.payroll.application.PayslipPdfRenderer;
import com.divalhr.core.payroll.application.PayslipRenderException;
import com.divalhr.core.payroll.domain.PayslipLocale;
import com.divalhr.core.payroll.domain.PayslipText;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-094C (Issue #181, ADR 0035) payslip PDFs through the public API: the download headers, the
 * generated filename, deterministic bytes, identical amounts and digest in English and French, a
 * document that PDFBox parses, a bounded size, metadata free of every identifier, the safe 500 of a
 * render failure with nothing recorded, the visible VOIDED status and the download audit of the
 * administrator and of the employee. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
class PayslipPdfIntegrationTest {

  private static final String EXPLANATION = "The statutory profile was entered for the wrong month";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @MockitoSpyBean private PayslipPdfRenderer renderer;

  private PayrollPackageWorld w;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String text(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return new PDFTextStripper().getText(document).replaceAll("\\s+", " ").strip();
    }
  }

  private static String raw(byte[] pdf) {
    return new String(pdf, StandardCharsets.ISO_8859_1);
  }

  /** Approves the chain; returns the package and the payslip of its employee. */
  private String[] approved(Chain chain) throws Exception {
    String pkg = w.approve(chain).get("packageId").asText();
    return new String[] {pkg, w.slipOf(pkg, chain.employee())};
  }

  @Test
  void theDownloadIsAnAttachmentWithTheSafeHeadersAndAGeneratedFilename() throws Exception {
    Chain chain = w.ready();
    String[] ids = approved(chain);

    MockHttpServletResponse response = w.pdf(w.admin, w.slipPdfPath(ids[0], ids[1]));

    assertThat(response.getContentType()).isEqualTo("application/pdf");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    String disposition = response.getHeader("Content-Disposition");
    assertThat(disposition).startsWith("attachment");
    assertThat(disposition).containsPattern("filename=\"payslip-\\d{4}-\\d{2}-[0-9a-f]{8}\\.pdf\"");
    assertThat(disposition)
        .contains(ids[1].replace("-", "").substring(0, 8))
        .doesNotContain("Mukendi")
        .doesNotContain(ids[0])
        .doesNotContain(ids[1]);
    byte[] body = response.getContentAsByteArray();
    assertThat(raw(body)).startsWith("%PDF-");
    assertThat(body.length).isPositive().isLessThanOrEqualTo(256 * 1024);
  }

  @Test
  void twoDownloadsOfTheSameLanguageAreByteIdenticalAndTheLanguagesDiffer() throws Exception {
    String[] ids = approved(w.ready());
    String path = w.slipPdfPath(ids[0], ids[1]);

    byte[] first = w.pdf(w.admin, path).getContentAsByteArray();
    w.advance(60);
    byte[] second = w.pdf(w.admin, path).getContentAsByteArray();
    byte[] french = w.pdf(w.admin, path + "?locale=fr").getContentAsByteArray();
    byte[] english = w.pdf(w.admin, path + "?locale=en").getContentAsByteArray();

    assertThat(second).isEqualTo(first);
    assertThat(english).isEqualTo(first);
    assertThat(french).isNotEqualTo(first);
    assertThat(w.pdf(w.admin, path + "?locale=fr").getContentAsByteArray()).isEqualTo(french);
  }

  @Test
  void englishAndFrenchShowTheSameAmountsAndTheSameCanonicalDigest() throws Exception {
    String[] ids = approved(w.ready());
    String path = w.slipPdfPath(ids[0], ids[1]);
    String english = text(w.pdf(w.admin, path + "?locale=en").getContentAsByteArray());
    String french = text(w.pdf(w.admin, path + "?locale=fr").getContentAsByteArray());
    JsonNode view = w.slipView(ids[0], ids[1], "en");
    String digest = view.get("snapshotDigest").asText();
    assertThat(w.slipView(ids[0], ids[1], "fr").get("snapshotDigest").asText()).isEqualTo(digest);

    for (String amount :
        List.of("162000 CDF", "8100 CDF", "4860 CDF", "12960 CDF", "149040 CDF", "21060 CDF")) {
      assertThat(english).as("EN " + amount).contains(amount);
      assertThat(french).as("FR " + amount).contains(amount);
    }
    assertThat(english).contains(PayslipText.title(PayslipLocale.EN));
    assertThat(french).contains(PayslipText.title(PayslipLocale.FR));
    assertThat(english).contains(PayslipText.notProofOfPayment(PayslipLocale.EN));
    assertThat(french).contains(PayslipText.notProofOfPayment(PayslipLocale.FR));
    assertThat(english).doesNotContain(PayslipText.notProofOfPayment(PayslipLocale.FR));
    // The digest is printed in two halves in both languages (32 hexadecimal characters each).
    for (String document : List.of(english, french)) {
      assertThat(document).contains(digest.substring(0, 32)).contains(digest.substring(32));
    }
    // Accept-Language selects the language when no locale is requested.
    byte[] negotiated =
        w.c
            .call(
                PayrollPackageWorld.get(w.admin, path)
                    .header("Accept-Language", "fr-CA,fr;q=0.9,en;q=0.5"))
            .getContentAsByteArray();
    assertThat(text(negotiated)).isEqualTo(french);
    w.c.problem(PayrollPackageWorld.get(w.admin, path + "?locale=de"), 400, "LOCALE_NOT_SUPPORTED");
  }

  @Test
  void theDocumentParsesAsOneBoundedPageWithOnlyTheGenericMetadata() throws Exception {
    String[] ids = approved(w.ready());
    String path = w.slipPdfPath(ids[0], ids[1]);
    for (String locale : List.of("en", "fr")) {
      byte[] pdf = w.pdf(w.admin, path + "?locale=" + locale).getContentAsByteArray();
      assertThat(pdf.length).as(locale).isLessThanOrEqualTo(PayslipPdfRenderer.MAX_BYTES);
      try (PDDocument document = Loader.loadPDF(pdf)) {
        assertThat(document.getNumberOfPages()).as(locale).isEqualTo(1);
        PDDocumentInformation info = document.getDocumentInformation();
        assertThat(info.getMetadataKeys())
            .containsExactlyInAnyOrder("Title", "Creator", "Producer", "CreationDate", "ModDate");
        assertThat(info.getTitle())
            .isEqualTo(PayslipText.documentTitle(PayslipLocale.parse(locale).orElseThrow(), false));
        assertThat(info.getAuthor()).isNull();
        assertThat(info.getSubject()).isNull();
        assertThat(info.getKeywords()).isNull();
      }
    }
  }

  @Test
  void noSubjectTokenEmailPathOrIdentifierIsEmbeddedInTheDocument() throws Exception {
    Chain chain = w.ready();
    String[] ids = approved(chain);
    String subject = PayrollPackageWorld.subjectOf(w.admin);
    String token = w.admin.substring("Bearer ".length());
    String body = raw(w.pdf(w.admin, w.slipPdfPath(ids[0], ids[1])).getContentAsByteArray());
    String french =
        raw(w.pdf(w.admin, w.slipPdfPath(ids[0], ids[1]) + "?locale=fr").getContentAsByteArray());

    for (String document : List.of(body, french)) {
      assertThat(document)
          .doesNotContain(subject)
          .doesNotContain(token)
          .doesNotContain(w.approver(ids[0]))
          .doesNotContain(ids[0])
          .doesNotContain(ids[1])
          .doesNotContain(w.entity.toString())
          .doesNotContain(w.tenant.toString())
          .doesNotContain(chain.employee().toString())
          .doesNotContain(chain.run())
          .doesNotContain(chain.calculation())
          .doesNotContain(chain.pack().toString())
          .doesNotContain("@")
          .doesNotContain("/api/")
          .doesNotContain("/home/")
          .doesNotContain("Mukendi")
          .doesNotContain("/Author")
          .doesNotContain("/Subject")
          .doesNotContain("/Keywords");
    }
  }

  @Test
  void aRenderFailureIsASafeInternalErrorAndNothingIsRecorded() throws Exception {
    String[] ids = approved(w.ready());
    String path = w.slipPdfPath(ids[0], ids[1]);
    int downloads = w.audits("payslip.download");
    doThrow(new PayslipRenderException()).when(renderer).render(any());

    MockHttpServletResponse response = w.c.call(PayrollPackageWorld.get(w.admin, path));

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentType()).doesNotContain("pdf");
    JsonNode problem = CompensationWorld.JSON.readTree(response.getContentAsString());
    assertThat(problem.get("code").asText()).isEqualTo("PAYSLIP_RENDER_FAILED");
    assertThat(response.getContentAsString())
        .doesNotContain("PayslipRenderException")
        .doesNotContain("Exception")
        .doesNotContain("at com.divalhr")
        .doesNotContain(ids[1])
        .doesNotContain("149040");
    assertThat(raw(response.getContentAsByteArray())).doesNotContain("%PDF-");
    assertThat(w.audits("payslip.download")).isEqualTo(downloads);
  }

  @Test
  void aVoidedPayslipIsVisiblyVoidedInBothLanguagesAndNeverEchoesTheExplanation() throws Exception {
    String[] ids = approved(w.ready());
    String path = w.slipPdfPath(ids[0], ids[1]);
    String approvedEnglish = text(w.pdf(w.admin, path).getContentAsByteArray());
    assertThat(approvedEnglish).doesNotContain("VOIDED");
    assertThat(approvedEnglish).contains(PayslipText.statusLabel(PayslipLocale.EN, false));

    w.voidPackage(ids[0], "OTHER_CONTROLLED", EXPLANATION);

    String english = text(w.pdf(w.admin, path + "?locale=en").getContentAsByteArray());
    String french = text(w.pdf(w.admin, path + "?locale=fr").getContentAsByteArray());
    assertThat(english).contains("VOIDED").contains("149040 CDF");
    assertThat(french).contains("ANNULÉ").contains("149040 CDF");
    assertThat(english).doesNotContain("wrong month").doesNotContain(EXPLANATION);
    assertThat(french).doesNotContain("wrong month").doesNotContain(EXPLANATION);
    try (PDDocument document =
        Loader.loadPDF(w.pdf(w.admin, path + "?locale=fr").getContentAsByteArray())) {
      assertThat(document.getDocumentInformation().getTitle())
          .isEqualTo(PayslipText.documentTitle(PayslipLocale.FR, true))
          .contains("ANNULÉ");
    }
    JsonNode view = w.slipView(ids[0], ids[1], "en");
    assertThat(view.get("status").asText()).isEqualTo("VOIDED");
    assertThat(view.get("snapshotDigest").asText()).matches("[0-9a-f]{64}");
  }

  @Test
  void aSyntheticPackIsMarkedAndAReviewedPackIsNot() throws Exception {
    String[] synthetic = approved(w.ready());
    String notice = PayslipText.syntheticNotice(PayslipLocale.EN);
    assertThat(
            text(w.pdf(w.admin, w.slipPdfPath(synthetic[0], synthetic[1])).getContentAsByteArray()))
        .contains(notice);

    PayrollPackageWorld other = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    UUID reviewed = PayrollPackageWorld.verifiedNonSyntheticPack(jdbc);
    Chain realChain = other.ready(reviewed);
    String pkg = other.approve(realChain).get("packageId").asText();
    JsonNode detail = other.packageDetail(pkg);
    assertThat(detail.get("package").get("synthetic").asBoolean()).isFalse();
    String slip = other.slipOf(pkg, realChain.employee());
    byte[] bytes = other.pdf(other.admin, other.slipPdfPath(pkg, slip)).getContentAsByteArray();
    assertThat(text(bytes)).doesNotContain(notice);
  }

  @Test
  void theAdministratorAndTheEmployeeDownloadsAreAuditedWithoutAmountsOrBytes() throws Exception {
    Linked linked = w.readyLinked();
    String pkg = w.approve(linked.chain()).get("packageId").asText();
    String slip = w.slipOf(pkg, linked.chain().employee());
    int before = w.audits("payslip.download");

    byte[] admin = w.pdf(w.admin, w.slipPdfPath(pkg, slip)).getContentAsByteArray();
    assertThat(w.audits("payslip.download")).isEqualTo(before + 1);
    byte[] mine =
        w.pdf(linked.person().bearer(), PayrollPackageWorld.minePdfPath(slip))
            .getContentAsByteArray();
    assertThat(w.audits("payslip.download")).isEqualTo(before + 2);
    MockHttpServletResponse response =
        w.c.call(
            PayrollPackageWorld.get(
                linked.person().bearer(), PayrollPackageWorld.minePdfPath(slip)));
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("Content-Disposition")).startsWith("attachment");

    assertThat(mine).isEqualTo(admin);
    List<String> actors =
        jdbc.queryForList(
            "SELECT actor_subject FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action = 'payslip.download' ORDER BY occurred_at, id",
            String.class,
            w.tenant);
    assertThat(actors).contains(PayrollPackageWorld.subjectOf(w.admin), linked.person().subject());
    List<String> metadata =
        jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action = 'payslip.download'",
            String.class,
            w.tenant);
    assertThat(metadata).isNotEmpty();
    for (String row : metadata) {
      assertThat(row)
          .doesNotContain("149040")
          .doesNotContain("162000")
          .doesNotContain("%PDF")
          .doesNotContain("Mukendi")
          .doesNotContain(PayrollPackageWorld.subjectOf(w.admin));
    }
  }

  @Test
  void unknownAndMalformedPayslipsAreOneSafeNotFoundAndWriteNoAudit() throws Exception {
    String[] ids = approved(w.ready());
    int downloads = w.audits("payslip.download");
    for (String slip : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
      w.c.problem(
          PayrollPackageWorld.get(w.admin, w.slipPdfPath(ids[0], slip)), 404, "PAYSLIP_NOT_FOUND");
    }
    w.c.problem(
        PayrollPackageWorld.get(w.admin, w.slipPdfPath(UUID.randomUUID(), ids[1])),
        404,
        "PAYSLIP_NOT_FOUND");
    assertThat(w.audits("payslip.download")).isEqualTo(downloads);
  }
}
