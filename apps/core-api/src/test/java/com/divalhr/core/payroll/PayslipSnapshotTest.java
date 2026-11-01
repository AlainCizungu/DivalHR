package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.PayslipSnapshot;
import com.divalhr.core.payroll.domain.PayslipSnapshot.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** MVP-094C: the canonical payslip snapshot text, its digest and its arithmetic, exactly. */
class PayslipSnapshotTest {

  private static final UUID PACKAGE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ENTITY = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID EMPLOYEE = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final Instant APPROVED = Instant.parse("2026-04-01T10:11:12.123456Z");

  // Computed by an independent script (json.dumps with compact separators over the ADR key order)
  // and cross-checked against the production writer.
  private static final String TEXT_162000 =
      "{\"schemaVersion\":1,\"packageId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaa"
          + "aaaaaaaa\",\"legalEntityId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbb"
          + "bbb\",\"period\":\"2026-03\",\"currency\":\"CDF\",\"employeeId\":\"ccccc"
          + "ccc-cccc-4ccc-8ccc-cccccccccccc\",\"employeeNumber\":\"E-0042\",\""
          + "status\":\"APPROVED\",\"approvedAt\":\"2026-04-01T10:11:12.123456Z"
          + "\",\"rulePack\":{\"code\":\"SYNTHETIC-TEST-DRC\",\"version\":1},\"excl"
          + "uded\":false,\"contractualGrossMinor\":\"162000\",\"employeeCnssMi"
          + "nor\":\"8100\",\"irppWithholdingMinor\":\"4860\",\"employeeDeduction"
          + "sMinor\":\"12960\",\"netMinor\":\"149040\",\"employerContributionsMi"
          + "nor\":\"21060\",\"explanationCodes\":[\"BASE_GROSS\",\"BASE_GROSS_LE"
          + "SS_EMPLOYEE_SOCIAL\",\"PROGRESSIVE_APPLIED\",\"RATE_APPLIED\"],\"c"
          + "omponents\":[{\"seq\":1,\"id\":\"CNSS_EMPLOYEE_PENSION\",\"party\":\"E"
          + "MPLOYEE\",\"category\":\"SOCIAL_CONTRIBUTION\",\"baseMinor\":\"16200"
          + "0\",\"amountMinor\":\"8100\",\"explanationCodes\":[\"RATE_APPLIED\",\""
          + "BASE_GROSS\"]},{\"seq\":2,\"id\":\"CNSS_EMPLOYER_PENSION\",\"party\":"
          + "\"EMPLOYER\",\"category\":\"SOCIAL_CONTRIBUTION\",\"baseMinor\":\"162"
          + "000\",\"amountMinor\":\"8100\",\"explanationCodes\":[\"RATE_APPLIED\""
          + ",\"BASE_GROSS\"]},{\"seq\":3,\"id\":\"CNSS_EMPLOYER_OCCUPATIONAL_RI"
          + "SK\",\"party\":\"EMPLOYER\",\"category\":\"SOCIAL_CONTRIBUTION\",\"bas"
          + "eMinor\":\"162000\",\"amountMinor\":\"2430\",\"explanationCodes\":[\"R"
          + "ATE_APPLIED\",\"BASE_GROSS\"]},{\"seq\":4,\"id\":\"CNSS_EMPLOYER_FAM"
          + "ILY_BENEFITS\",\"party\":\"EMPLOYER\",\"category\":\"SOCIAL_CONTRIBU"
          + "TION\",\"baseMinor\":\"162000\",\"amountMinor\":\"10530\",\"explanatio"
          + "nCodes\":[\"RATE_APPLIED\",\"BASE_GROSS\"]},{\"seq\":5,\"id\":\"IRPP_S"
          + "ALARY_WITHHOLDING\",\"party\":\"EMPLOYEE\",\"category\":\"INCOME_TAX"
          + "\",\"baseMinor\":\"153900\",\"amountMinor\":\"4860\",\"explanationCode"
          + "s\":[\"PROGRESSIVE_APPLIED\",\"BASE_GROSS_LESS_EMPLOYEE_SOCIAL\"]"
          + "}]}";

  private static final String DIGEST_162000 =
      "1355528a189f143cb394e412274e3c394fd7ddc062f7ebfd798cce8ca9ccd713";

  private static final String TEXT_EXCLUDED =
      "{\"schemaVersion\":1,\"packageId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\","
          + "\"legalEntityId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\",\"period\":\"2026-03\","
          + "\"currency\":\"CDF\",\"employeeId\":\"cccccccc-cccc-4ccc-8ccc-cccccccccccc\","
          + "\"employeeNumber\":\"E-0099\",\"status\":\"APPROVED\","
          + "\"approvedAt\":\"2026-04-01T10:11:12.000000Z\","
          + "\"rulePack\":{\"code\":\"SYNTHETIC-TEST-DRC\",\"version\":1},\"excluded\":true,"
          + "\"contractualGrossMinor\":\"50000\",\"employeeCnssMinor\":\"0\","
          + "\"irppWithholdingMinor\":\"0\",\"employeeDeductionsMinor\":\"0\","
          + "\"netMinor\":\"50000\",\"employerContributionsMinor\":\"0\","
          + "\"explanationCodes\":[],\"components\":[]}";

  private static final String DIGEST_EXCLUDED =
      "341c26b666259cb2dc0373483c8d04aa5dc8ad619878e8a2d8f6f95c68979525";

  private static Component component(
      int seq, String id, String party, String category, long base, long amount, String... codes) {
    return new Component(seq, id, party, category, base, amount, List.of(codes));
  }

  private static List<Component> components162000() {
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

  private static PayslipSnapshot snapshot(
      Instant approvedAt, String number, boolean excluded, long gross, List<Component> parts) {
    return PayslipSnapshot.of(
        PACKAGE,
        ENTITY,
        "2026-03",
        approvedAt,
        "SYNTHETIC-TEST-DRC",
        1,
        EMPLOYEE,
        number,
        excluded,
        gross,
        parts);
  }

  private static PayslipSnapshot vector162000() {
    return snapshot(APPROVED, "E-0042", false, 162000, components162000());
  }

  private static String sha256(String text) throws Exception {
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(sha.digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void theFullVectorHasExactlyTheDocumentedCanonicalText() {
    assertThat(vector162000().canonicalText()).isEqualTo(TEXT_162000);
  }

  @Test
  void theDigestIsTheSha256OfTheUtf8BytesOfTheText() throws Exception {
    PayslipSnapshot snapshot = vector162000();
    assertThat(snapshot.digest()).isEqualTo(DIGEST_162000);
    assertThat(sha256(snapshot.canonicalText())).isEqualTo(DIGEST_162000);
    assertThat(sha256(TEXT_162000)).isEqualTo(DIGEST_162000);
    assertThat(snapshot.digest()).matches("[0-9a-f]{64}");
  }

  @Test
  void aSingleChangedCharacterChangesTheDigest() {
    PayslipSnapshot other = snapshot(APPROVED, "E-0043", false, 162000, components162000());
    assertThat(other.digest()).isNotEqualTo(DIGEST_162000);
    assertThat(other.canonicalText()).isNotEqualTo(TEXT_162000);
  }

  @Test
  void theKeysAreInTheDocumentedOrderWithNoWhitespaceOutsideStrings() {
    String text = vector162000().canonicalText();
    String[] order = {
      "schemaVersion",
      "packageId",
      "legalEntityId",
      "period",
      "currency",
      "employeeId",
      "employeeNumber",
      "status",
      "approvedAt",
      "rulePack",
      "excluded",
      "contractualGrossMinor",
      "employeeCnssMinor",
      "irppWithholdingMinor",
      "employeeDeductionsMinor",
      "netMinor",
      "employerContributionsMinor",
      "explanationCodes",
      "components"
    };
    int at = -1;
    for (String key : order) {
      int found = text.indexOf("\"" + key + "\":");
      assertThat(found).as(key).isGreaterThan(at);
      at = found;
    }
    assertThat(text).doesNotContainPattern("\\s");
    assertThat(text).startsWith("{\"schemaVersion\":1,\"packageId\"");
    assertThat(text).endsWith("}]}");
  }

  @Test
  void theSnapshotCarriesExactlyTheDocumentedKeysAndNothingElse() {
    Matcher keys = Pattern.compile("\"([A-Za-z]+)\":").matcher(vector162000().canonicalText());
    Set<String> found = new TreeSet<>();
    while (keys.find()) {
      found.add(keys.group(1));
    }
    Set<String> allowed =
        new TreeSet<>(
            List.of(
                "schemaVersion",
                "packageId",
                "legalEntityId",
                "period",
                "currency",
                "employeeId",
                "employeeNumber",
                "status",
                "approvedAt",
                "rulePack",
                "code",
                "version",
                "excluded",
                "contractualGrossMinor",
                "employeeCnssMinor",
                "irppWithholdingMinor",
                "employeeDeductionsMinor",
                "netMinor",
                "employerContributionsMinor",
                "explanationCodes",
                "components",
                "seq",
                "id",
                "party",
                "category",
                "baseMinor",
                "amountMinor"));
    assertThat(found).isEqualTo(allowed);
  }

  @Test
  void noApproverNameNationalIdNoteOrEmployerCostEntersTheSnapshot() {
    String text = vector162000().canonicalText().toLowerCase(java.util.Locale.ROOT);
    for (String forbidden :
        List.of(
            "approver",
            "subject",
            "nationalid",
            "national_id",
            "taxnumber",
            "cnssnumber",
            "bank",
            "mobile",
            "address",
            "note",
            "firstname",
            "lastname",
            "fullname",
            "email",
            "tenant",
            "employercost",
            "costminor",
            "reason",
            "explanation\":\"")) {
      assertThat(text).as(forbidden).doesNotContain(forbidden);
    }
    List<String> fields = new ArrayList<>();
    for (var field : PayslipSnapshot.class.getRecordComponents()) {
      fields.add(field.getName().toLowerCase(java.util.Locale.ROOT));
    }
    for (String field : fields) {
      assertThat(field).doesNotContain("approver").doesNotContain("subject");
      assertThat(field).doesNotContain("national").doesNotContain("note").doesNotContain("cost");
      assertThat(field).doesNotContain("tenant").doesNotContain("email");
    }
    assertThat(fields).doesNotContain("name").doesNotContain("employeename");
  }

  @Test
  void theApprovalInstantIsUtcWithExactlySixFractionalDigits() {
    PayslipSnapshot whole =
        snapshot(Instant.parse("2026-04-01T10:11:12Z"), "E-1", false, 1000, List.of());
    assertThat(whole.canonicalText()).contains("\"approvedAt\":\"2026-04-01T10:11:12.000000Z\"");
    PayslipSnapshot nanos =
        snapshot(Instant.parse("2026-04-01T10:11:12.123456789Z"), "E-1", false, 1000, List.of());
    assertThat(nanos.canonicalText()).contains("\"approvedAt\":\"2026-04-01T10:11:12.123456Z\"");
    PayslipSnapshot micro =
        snapshot(Instant.parse("2026-12-31T23:59:59.000001Z"), "E-1", false, 1000, List.of());
    assertThat(micro.canonicalText()).contains("\"approvedAt\":\"2026-12-31T23:59:59.000001Z\"");
  }

  @Test
  void amountsAreDecimalStringsOfWholeFrancs() {
    String text = vector162000().canonicalText();
    for (String amount :
        List.of(
            "\"contractualGrossMinor\":\"162000\"", "\"employeeCnssMinor\":\"8100\"",
            "\"irppWithholdingMinor\":\"4860\"", "\"employeeDeductionsMinor\":\"12960\"",
            "\"netMinor\":\"149040\"", "\"employerContributionsMinor\":\"21060\"")) {
      assertThat(text).contains(amount);
    }
    assertThat(text).doesNotContainPattern("Minor\":[0-9]");
    assertThat(text).doesNotContainPattern("Minor\":\"[0-9]*[.,]");
    PayslipSnapshot large = snapshot(APPROVED, "E-1", false, Long.MAX_VALUE, List.of());
    assertThat(large.canonicalText()).contains("\"contractualGrossMinor\":\"9223372036854775807\"");
  }

  @Test
  void the162000VectorSeparatesEmployeeDeductionsFromEmployerContributions() {
    PayslipSnapshot snapshot = vector162000();
    assertThat(snapshot.grossMinor()).isEqualTo(162000L);
    assertThat(snapshot.employeeCnssMinor()).isEqualTo(8100L);
    assertThat(snapshot.irppWithholdingMinor()).isEqualTo(4860L);
    assertThat(snapshot.employeeDeductionsMinor()).isEqualTo(12960L);
    assertThat(snapshot.employerContributionsMinor()).isEqualTo(21060L);
    assertThat(snapshot.netMinor()).isEqualTo(149040L);
    assertThat(snapshot.excluded()).isFalse();
  }

  @Test
  void the12345VectorRoundsTheStatutoryAmountsAsTheEngineDoes() {
    PayslipSnapshot cnss =
        snapshot(
            APPROVED,
            "E-1",
            false,
            12345,
            List.of(
                component(
                    1,
                    "CNSS_EMPLOYEE_PENSION",
                    "EMPLOYEE",
                    "SOCIAL_CONTRIBUTION",
                    12345,
                    617,
                    "RATE_APPLIED"),
                component(
                    2,
                    "CNSS_EMPLOYER_PENSION",
                    "EMPLOYER",
                    "SOCIAL_CONTRIBUTION",
                    12345,
                    617,
                    "RATE_APPLIED"),
                component(
                    3,
                    "CNSS_EMPLOYER_OCCUPATIONAL_RISK",
                    "EMPLOYER",
                    "SOCIAL_CONTRIBUTION",
                    12345,
                    185,
                    "RATE_APPLIED"),
                component(
                    4,
                    "CNSS_EMPLOYER_FAMILY_BENEFITS",
                    "EMPLOYER",
                    "SOCIAL_CONTRIBUTION",
                    12345,
                    802,
                    "RATE_APPLIED")));
    assertThat(cnss.employeeCnssMinor()).isEqualTo(617L);
    assertThat(cnss.employeeDeductionsMinor()).isEqualTo(617L);
    assertThat(cnss.employerContributionsMinor()).isEqualTo(1604L);
    assertThat(cnss.netMinor()).isEqualTo(11728L);

    PayslipSnapshot irpp =
        snapshot(
            APPROVED,
            "E-1",
            false,
            12345,
            List.of(
                component(
                    1,
                    "IRPP_SALARY_WITHHOLDING",
                    "EMPLOYEE",
                    "INCOME_TAX",
                    12345,
                    370,
                    "PROGRESSIVE_APPLIED")));
    assertThat(irpp.irppWithholdingMinor()).isEqualTo(370L);
    assertThat(irpp.employeeDeductionsMinor()).isEqualTo(370L);
    assertThat(irpp.employerContributionsMinor()).isEqualTo(0L);
    assertThat(irpp.netMinor()).isEqualTo(11975L);

    PayslipSnapshot both =
        snapshot(
            APPROVED,
            "E-1",
            false,
            12345,
            List.of(
                component(
                    1,
                    "CNSS_EMPLOYEE_PENSION",
                    "EMPLOYEE",
                    "SOCIAL_CONTRIBUTION",
                    12345,
                    617,
                    "RATE_APPLIED"),
                component(
                    2,
                    "IRPP_SALARY_WITHHOLDING",
                    "EMPLOYEE",
                    "INCOME_TAX",
                    12345,
                    370,
                    "PROGRESSIVE_APPLIED")));
    assertThat(both.employeeDeductionsMinor()).isEqualTo(987L);
    assertThat(both.netMinor()).isEqualTo(11358L);
  }

  @Test
  void employerContributionsNeverReduceTheNet() {
    long[] employer = {0L, 1L, 21060L, 4_000_000_000_000L, 4_000_000_000_000_000_000L};
    for (long amount : employer) {
      List<Component> parts =
          List.of(
              component(
                  1,
                  "CNSS_EMPLOYEE_PENSION",
                  "EMPLOYEE",
                  "SOCIAL_CONTRIBUTION",
                  1000,
                  50,
                  "RATE_APPLIED"),
              component(
                  2,
                  "CNSS_EMPLOYER_PENSION",
                  "EMPLOYER",
                  "SOCIAL_CONTRIBUTION",
                  1000,
                  amount,
                  "RATE_APPLIED"));
      PayslipSnapshot snapshot = snapshot(APPROVED, "E-1", false, 1000, parts);
      assertThat(snapshot.netMinor()).as("employer " + amount).isEqualTo(950L);
      assertThat(snapshot.employeeDeductionsMinor()).isEqualTo(50L);
      assertThat(snapshot.employerContributionsMinor()).isEqualTo(amount);
    }
  }

  @Test
  void anEmployerIncomeTaxComponentIsStillEmployerSideAndNeverDeducted() {
    List<Component> parts =
        List.of(component(1, "X_EMPLOYER_TAX", "EMPLOYER", "INCOME_TAX", 1000, 75, "RATE_APPLIED"));
    PayslipSnapshot snapshot = snapshot(APPROVED, "E-1", false, 1000, parts);
    assertThat(snapshot.irppWithholdingMinor()).isEqualTo(0L);
    assertThat(snapshot.employerContributionsMinor()).isEqualTo(75L);
    assertThat(snapshot.netMinor()).isEqualTo(1000L);
  }

  @Test
  void anExcludedEmployeeHasAllZeroDeductionsAndAnEmptyBreakdown() {
    PayslipSnapshot snapshot =
        snapshot(Instant.parse("2026-04-01T10:11:12Z"), "E-0099", true, 50000, List.of());
    assertThat(snapshot.canonicalText()).isEqualTo(TEXT_EXCLUDED);
    assertThat(snapshot.digest()).isEqualTo(DIGEST_EXCLUDED);
    assertThat(snapshot.excluded()).isTrue();
    assertThat(snapshot.netMinor()).isEqualTo(50000L);
    assertThat(snapshot.explanationCodes()).isEmpty();
    assertThat(snapshot.components()).isEmpty();
  }

  @Test
  void theExplanationCodesAreSortedDistinctAndTheComponentsKeepTheirOwnOrder() {
    List<Component> parts =
        List.of(
            component(
                1,
                "CNSS_EMPLOYEE_PENSION",
                "EMPLOYEE",
                "SOCIAL_CONTRIBUTION",
                1000,
                50,
                "Z_CODE",
                "A_CODE"),
            component(
                2,
                "CNSS_EMPLOYER_PENSION",
                "EMPLOYER",
                "SOCIAL_CONTRIBUTION",
                1000,
                50,
                "A_CODE",
                "M_CODE"));
    PayslipSnapshot snapshot = snapshot(APPROVED, "E-1", false, 1000, parts);
    assertThat(snapshot.explanationCodes()).containsExactly("A_CODE", "M_CODE", "Z_CODE");
    assertThat(snapshot.canonicalText())
        .contains("\"explanationCodes\":[\"A_CODE\",\"M_CODE\",\"Z_CODE\"],\"components\"")
        .contains("\"explanationCodes\":[\"Z_CODE\",\"A_CODE\"]}");
    assertThat(vector162000().explanationCodes())
        .containsExactly(
            "BASE_GROSS", "BASE_GROSS_LESS_EMPLOYEE_SOCIAL", "PROGRESSIVE_APPLIED", "RATE_APPLIED");
  }

  @Test
  void stringsAreEscapedMinimallyAndControlCharactersAsLowerCaseUnicodeEscapes() {
    PayslipSnapshot snapshot = snapshot(APPROVED, "E\"\\\u0001\u001f-1", false, 1000, List.of());
    assertThat(snapshot.canonicalText())
        .contains("\"employeeNumber\":\"E\\\"\\\\\\u0001\\u001f-1\"");
    PayslipSnapshot accents = snapshot(APPROVED, "É-é-1", false, 1000, List.of());
    assertThat(accents.canonicalText()).contains("\"employeeNumber\":\"É-é-1\"");
  }

  @Test
  void aStoredCanonicalTextParsesBackToTheSameSnapshot() {
    PayslipSnapshot original = vector162000();
    assertThat(PayslipSnapshot.parse(TEXT_162000)).isEqualTo(original);
    assertThat(PayslipSnapshot.parse(TEXT_EXCLUDED).excluded()).isTrue();
  }

  @Test
  void aTextThatIsNotExactlyCanonicalIsRefused() {
    String spaced = TEXT_162000.replace("{\"schemaVersion\":1,", "{ \"schemaVersion\":1,");
    String tampered = TEXT_162000.replace("\"netMinor\":\"149040\"", "\"netMinor\":\"149041\"");
    String head = "{\"schemaVersion\":1,";
    String extra = TEXT_162000.replace(head, "{\"extra\":1,\"schemaVersion\":1,");
    String missing = TEXT_162000.replace("\"currency\":\"CDF\",", "");
    for (String text : List.of(spaced, tampered, extra, missing, "not json", "{}", "")) {
      assertThatThrownBy(() -> PayslipSnapshot.parse(text))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void arithmeticThatDoesNotHoldIsRefused() {
    // Deductions above the gross would make the net negative.
    List<Component> tooMuch =
        List.of(
            component(1, "CNSS_EMPLOYEE_PENSION", "EMPLOYEE", "SOCIAL_CONTRIBUTION", 100, 60, "A"),
            component(2, "IRPP_SALARY_WITHHOLDING", "EMPLOYEE", "INCOME_TAX", 100, 50, "A"));
    assertThatThrownBy(() -> snapshot(APPROVED, "E-1", false, 100, tooMuch))
        .isInstanceOf(IllegalArgumentException.class);
    // The constructor proves net = gross - deductions and deductions = cnss + irpp.
    assertThatThrownBy(
            () ->
                new PayslipSnapshot(
                    PACKAGE, ENTITY, "2026-03", EMPLOYEE, "E-1", APPROVED, "P", 1, false, 1000L,
                    50L, 0L, 50L, 951L, 0L, List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PayslipSnapshot(
                    PACKAGE, ENTITY, "2026-03", EMPLOYEE, "E-1", APPROVED, "P", 1, false, 1000L,
                    50L, 0L, 49L, 951L, 0L, List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PayslipSnapshot(
                    PACKAGE, ENTITY, "2026-03", EMPLOYEE, "E-1", APPROVED, "P", 1, false, -1L, 0L,
                    0L, 0L, -1L, 0L, List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anOverflowingComponentSumIsRefusedNeverWrapped() {
    List<Component> huge =
        List.of(
            component(
                1,
                "CNSS_EMPLOYEE_PENSION",
                "EMPLOYEE",
                "SOCIAL_CONTRIBUTION",
                1,
                Long.MAX_VALUE,
                "A"),
            component(
                2, "IRPP_SALARY_WITHHOLDING", "EMPLOYEE", "INCOME_TAX", 1, Long.MAX_VALUE, "A"));
    assertThatThrownBy(() -> snapshot(APPROVED, "E-1", false, Long.MAX_VALUE, huge))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void aComponentIsValidatedAndCopiesItsCodes() {
    assertThatThrownBy(() -> component(0, "X", "EMPLOYEE", "INCOME_TAX", 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> component(1, "X", "EMPLOYEE", "INCOME_TAX", -1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> component(1, "X", "EMPLOYEE", "INCOME_TAX", 1, -1))
        .isInstanceOf(IllegalArgumentException.class);
    List<String> codes = new ArrayList<>(List.of("A"));
    Component copy = new Component(1, "X", "EMPLOYEE", "INCOME_TAX", 1, 1, codes);
    codes.add("B");
    assertThat(copy.explanationCodes()).containsExactly("A");
  }

  @Test
  void toStringPrintsNoEmployeeNumberAndNoAmount() {
    PayslipSnapshot snapshot = vector162000();
    assertThat(snapshot.toString()).doesNotContain("E-0042").doesNotContain("162000");
    assertThat(snapshot.toString()).doesNotContain("149040").doesNotContain(EMPLOYEE.toString());
    assertThat(snapshot.components().get(0).toString()).doesNotContain("8100");
  }

  @Test
  void theSchemaVersionCurrencyAndStatusAreConstant() {
    assertThat(PayslipSnapshot.SCHEMA_VERSION).isEqualTo(1);
    assertThat(PayslipSnapshot.CURRENCY).isEqualTo("CDF");
    assertThat(PayslipSnapshot.STATUS).isEqualTo("APPROVED");
  }
}
