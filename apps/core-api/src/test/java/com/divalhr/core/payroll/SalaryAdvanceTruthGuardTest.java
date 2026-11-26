package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * MVP-120A (D120A-13): the salary-advance contract and copy never claim lending, guarantee or
 * funding, and the commercial page keeps salary advances partner-dependent.
 */
class SalaryAdvanceTruthGuardTest {

  private static final Path SPEC = Path.of("../../docs/API-SPEC.yaml");
  private static final Path EN = Path.of("../../packages/localization/locales/en/common.json");
  private static final Path FR = Path.of("../../packages/localization/locales/fr/common.json");

  private static final Path TRUTH =
      Path.of("../../apps/web/src/features/commercial/launch-truth.json");
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Claims the commercial page must never make about salary advances, English. */
  private static final Pattern BANNED_EN =
      Pattern.compile(
          "available now|\\blive\\b|(?<!never )guaranteed approval|approval (is|will be) guaranteed"
              + "|DivalHR (lends|will lend|funds|approves)",
          Pattern.CASE_INSENSITIVE);

  /** The same claims in French. */
  private static final Pattern BANNED_FR =
      Pattern.compile(
          "disponible (maintenant|dès maintenant)|\\ben direct\\b|approbation garantie"
              + "|DivalHR (prête|prêtera|finance|approuve)",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static final Pattern BANNED =
      Pattern.compile(
          "approved\\s+loan|guarantee|funded|interest\\s+rate|(?<!\\p{L})APR(?!\\p{L})"
              + "|credit\\s+score|prêt\\s+approuvé|garanti|financé|taux\\s+d.intérêt|TAEG"
              + "|score\\s+de\\s+crédit",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static String read(Path path) throws IOException {
    assumeTrue(Files.exists(path), "repository document not available: " + path);
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  /** The text of the salary-advance schemas, paths and tags in the specification. */
  private static String specSalaryAdvanceText() throws IOException {
    String spec = read(SPEC);
    StringBuilder out = new StringBuilder();
    Matcher block =
        Pattern.compile(
                "(?m)^    (SalaryAdvance\\w*|Me?SalaryAdvance\\w*):\\n(?:(?:      .*)?\\n)*")
            .matcher(spec);
    while (block.find()) {
      out.append(block.group());
    }
    Matcher path =
        Pattern.compile("(?m)^  (/api/v1/[^\\n]*salary-advance[^\\n]*):\\n(?:(?:    .*)?\\n)*")
            .matcher(spec);
    while (path.find()) {
      out.append(path.group());
    }
    return out.toString();
  }

  @Test
  void theContractCarriesNoLendingGuaranteeOrFundingWording() throws IOException {
    String text = specSalaryAdvanceText();
    assertThat(text).as("salary-advance contract text found").contains("SalaryAdvance");
    Matcher hit = BANNED.matcher(text);
    boolean found = hit.find();
    assertThat(found).as(found ? "banned wording: " + hit.group() : "clean").isFalse();
  }

  @Test
  void theLocalesCarryNoBannedWordingInSalaryAdvanceCopy() throws IOException {
    for (Path file : new Path[] {EN, FR}) {
      String text = read(file);
      for (String line : text.split("\n")) {
        if (line.contains("SALARY_ADVANCE_") || line.toLowerCase().contains("salaryadvance")) {
          assertThat(BANNED.matcher(line).find()).as(file + ": " + line).isFalse();
        }
      }
    }
  }

  /**
   * LAUNCH-004 (Issue #228, R229-1): the commercial page replaced the landing's "Coming next" list.
   * Its canonical sources, the launch-truth file and the {@code commercial} locale namespace, keep
   * salary advances partner-dependent: DivalHR is not the lender, real availability needs approved
   * partners, and nothing says available now, live, guaranteed approval or lending by DivalHR.
   */
  @Test
  void theCommercialPageKeepsSalaryAdvancesPartnerDependent() throws IOException {
    JsonNode advance = null;
    for (JsonNode capability : JSON.readTree(read(TRUTH)).path("capabilities")) {
      if ("salary-advances".equals(capability.path("id").asText())) {
        advance = capability;
      }
    }
    assertThat(advance).as("the salary-advances capability in the launch-truth file").isNotNull();
    assertThat(advance.path("status").asText()).isEqualTo("PARTNER_REQUIRED");
    for (String language : new String[] {"en", "fr"}) {
      JsonNode wellness =
          JSON.readTree(read(language.equals("en") ? EN : FR)).path("commercial").path("wellness");
      assertThat(wellness.isObject()).as(language + " commercial.wellness").isTrue();
      StringBuilder claims = new StringBuilder(advance.path(language).asText());
      collect(wellness, claims);
      Matcher hit = (language.equals("en") ? BANNED_EN : BANNED_FR).matcher(claims);
      boolean found = hit.find();
      assertThat(found)
          .as(language + (found ? ": banned claim " + hit.group() : ": clean"))
          .isFalse();
    }
    JsonNode statements =
        JSON.readTree(read(EN)).path("commercial").path("wellness").path("statements");
    assertThat(statements.path("notLender").asText()).isEqualTo("DivalHR is not the lender.");
    assertThat(statements.path("noGuarantee").asText()).isEqualTo("Approval is never guaranteed.");
    assertThat(statements.path("availability").asText())
        .isEqualTo("Real availability depends on approved partners and country rules.");
  }

  private static void collect(JsonNode node, StringBuilder out) {
    if (node.isTextual()) {
      out.append(' ').append(node.asText());
    }
    node.forEach(child -> collect(child, out));
  }
}
