package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * MVP-120A (D120A-13): the salary-advance contract and copy never claim lending, guarantee or
 * funding, and the landing keeps Loans and Salary Advances under Coming next.
 */
class SalaryAdvanceTruthGuardTest {

  private static final Path SPEC = Path.of("../../docs/API-SPEC.yaml");
  private static final Path EN = Path.of("../../packages/localization/locales/en/common.json");
  private static final Path FR = Path.of("../../packages/localization/locales/fr/common.json");

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

  @Test
  void theLandingKeepsLoansAndSalaryAdvancesComingNext() throws IOException {
    String en = read(EN);
    assertThat(en).contains("Loans & Salary Advances");
    Pattern live =
        Pattern.compile("(?i)(loans?\\s*&\\s*salary advances?)[^\\n]*(available now|live)");
    assertThat(live.matcher(en).find()).isFalse();
  }
}
