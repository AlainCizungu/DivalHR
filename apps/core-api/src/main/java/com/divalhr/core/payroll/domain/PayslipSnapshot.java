package com.divalhr.core.payroll.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The canonical payslip snapshot, schema version 1 (MVP-094C, ADR 0035 section 4.1): one per
 * statutory calculation line, immutable once stored. The canonical text is the authority; the
 * digest is the SHA-256 of its UTF-8 bytes ({@link PackageDigests#snapshotDigest}).
 *
 * <p>Amounts are whole CDF francs. Employer contributions are shown separately and never reduce
 * the net: {@code net = gross - employee deductions} and the employee deductions are the employee
 * CNSS contribution plus the IRPP withholding. The snapshot holds no name, national ID, tax
 * number, CNSS number, bank detail, address, health or family data, approver or note. Restricted
 * financial and HR data: nothing here is logged and {@code toString} prints no value.
 *
 * @param packageId the payroll package
 * @param legalEntityId the legal entity
 * @param period {@code YYYY-MM}
 * @param employeeId the employee
 * @param employeeNumber the employee number
 * @param approvedAt the approval instant (microseconds)
 * @param rulePackCode the rule pack code
 * @param rulePackVersion the rule pack version
 * @param excluded whether the employee is subject to neither CNSS nor IRPP
 * @param grossMinor contractual gross
 * @param employeeCnssMinor employee CNSS contribution
 * @param irppWithholdingMinor IRPP withholding
 * @param employeeDeductionsMinor total employee deductions
 * @param netMinor approved net
 * @param employerContributionsMinor employer contributions (separate)
 * @param explanationCodes sorted distinct explanation codes of the components
 * @param components the components in rule order
 */
public record PayslipSnapshot(
    UUID packageId,
    UUID legalEntityId,
    String period,
    UUID employeeId,
    String employeeNumber,
    Instant approvedAt,
    String rulePackCode,
    int rulePackVersion,
    boolean excluded,
    long grossMinor,
    long employeeCnssMinor,
    long irppWithholdingMinor,
    long employeeDeductionsMinor,
    long netMinor,
    long employerContributionsMinor,
    List<String> explanationCodes,
    List<Component> components) {

  /** The only schema version. */
  public static final int SCHEMA_VERSION = 1;

  /** The only currency. */
  public static final String CURRENCY = "CDF";

  /** The status at publication; the current status lives on the package. */
  public static final String STATUS = "APPROVED";

  private static final DateTimeFormatter APPROVED_AT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  /**
   * One statutory component of the frozen line.
   *
   * @param seq 1-based position of the rule in the pack
   * @param id rule id
   * @param party {@code EMPLOYEE} or {@code EMPLOYER}
   * @param category {@code SOCIAL_CONTRIBUTION} or {@code INCOME_TAX}
   * @param baseMinor the base
   * @param amountMinor the amount
   * @param explanationCodes the component's explanation codes in stored order
   */
  public record Component(
      int seq,
      String id,
      String party,
      String category,
      long baseMinor,
      long amountMinor,
      List<String> explanationCodes) {

    /** Requires every component and copies the list. */
    public Component {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(party, "party");
      Objects.requireNonNull(category, "category");
      explanationCodes = List.copyOf(explanationCodes);
      if (seq < 1 || baseMinor < 0 || amountMinor < 0) {
        throw new IllegalArgumentException("invalid payslip component");
      }
    }

    @Override
    public String toString() {
      return "PayslipComponent[" + seq + "]";
    }
  }

  /** Requires every field, copies the lists and proves the arithmetic. */
  public PayslipSnapshot {
    Objects.requireNonNull(packageId, "packageId");
    Objects.requireNonNull(legalEntityId, "legalEntityId");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(employeeId, "employeeId");
    Objects.requireNonNull(employeeNumber, "employeeNumber");
    Objects.requireNonNull(approvedAt, "approvedAt");
    Objects.requireNonNull(rulePackCode, "rulePackCode");
    explanationCodes = List.copyOf(explanationCodes);
    components = List.copyOf(components);
    if (grossMinor < 0
        || employeeCnssMinor < 0
        || irppWithholdingMinor < 0
        || employerContributionsMinor < 0
        || employeeDeductionsMinor != Math.addExact(employeeCnssMinor, irppWithholdingMinor)
        || netMinor != grossMinor - employeeDeductionsMinor
        || netMinor < 0) {
      throw new IllegalArgumentException("payslip arithmetic does not hold");
    }
  }

  /**
   * Builds the snapshot of a calculation line: sums the components by party and category and
   * derives the net and the explanation codes. Employer contributions never enter the net.
   *
   * @param packageId the package
   * @param legalEntityId the legal entity
   * @param period {@code YYYY-MM}
   * @param approvedAt the approval instant
   * @param rulePackCode the pack code
   * @param rulePackVersion the pack version
   * @param employeeId the employee
   * @param employeeNumber the employee number
   * @param excluded whether the line is excluded
   * @param grossMinor contractual gross
   * @param components the line's components in rule order (empty when excluded)
   * @return the snapshot
   * @throws IllegalArgumentException when the amounts do not add up
   */
  public static PayslipSnapshot of(
      UUID packageId,
      UUID legalEntityId,
      String period,
      Instant approvedAt,
      String rulePackCode,
      int rulePackVersion,
      UUID employeeId,
      String employeeNumber,
      boolean excluded,
      long grossMinor,
      List<Component> components) {
    long cnss = 0;
    long irpp = 0;
    long employer = 0;
    Set<String> codes = new TreeSet<>();
    for (Component component : components) {
      if ("EMPLOYER".equals(component.party())) {
        employer = Math.addExact(employer, component.amountMinor());
      } else if ("SOCIAL_CONTRIBUTION".equals(component.category())) {
        cnss = Math.addExact(cnss, component.amountMinor());
      } else {
        irpp = Math.addExact(irpp, component.amountMinor());
      }
      codes.addAll(component.explanationCodes());
    }
    long deductions = Math.addExact(cnss, irpp);
    return new PayslipSnapshot(
        packageId,
        legalEntityId,
        period,
        employeeId,
        employeeNumber,
        approvedAt,
        rulePackCode,
        rulePackVersion,
        excluded,
        grossMinor,
        cnss,
        irpp,
        deductions,
        grossMinor - deductions,
        employer,
        List.copyOf(codes),
        components);
  }

  // ------------------------------------------------------------------------------------------
  // Canonical text
  // ------------------------------------------------------------------------------------------

  /**
   * The canonical UTF-8 text of the snapshot: no whitespace outside strings, keys in the order of
   * ADR 0035 section 4.1, amounts as decimal strings of whole francs, the approval instant in UTC
   * with six fractional digits, strings escaped minimally.
   *
   * @return the canonical text
   */
  public String canonicalText() {
    StringBuilder text = new StringBuilder(768);
    text.append("{\"schemaVersion\":").append(SCHEMA_VERSION);
    stringField(text, "packageId", packageId.toString());
    stringField(text, "legalEntityId", legalEntityId.toString());
    stringField(text, "period", period);
    stringField(text, "currency", CURRENCY);
    stringField(text, "employeeId", employeeId.toString());
    stringField(text, "employeeNumber", employeeNumber);
    stringField(text, "status", STATUS);
    stringField(text, "approvedAt", APPROVED_AT.format(approvedAt));
    text.append(",\"rulePack\":{\"code\":");
    string(text, rulePackCode);
    text.append(",\"version\":").append(rulePackVersion).append('}');
    text.append(",\"excluded\":").append(excluded);
    stringField(text, "contractualGrossMinor", Long.toString(grossMinor));
    stringField(text, "employeeCnssMinor", Long.toString(employeeCnssMinor));
    stringField(text, "irppWithholdingMinor", Long.toString(irppWithholdingMinor));
    stringField(text, "employeeDeductionsMinor", Long.toString(employeeDeductionsMinor));
    stringField(text, "netMinor", Long.toString(netMinor));
    stringField(text, "employerContributionsMinor", Long.toString(employerContributionsMinor));
    text.append(",\"explanationCodes\":");
    stringArray(text, explanationCodes);
    text.append(",\"components\":[");
    boolean first = true;
    for (Component component : components) {
      if (!first) {
        text.append(',');
      }
      first = false;
      text.append("{\"seq\":").append(component.seq());
      stringField(text, "id", component.id());
      stringField(text, "party", component.party());
      stringField(text, "category", component.category());
      stringField(text, "baseMinor", Long.toString(component.baseMinor()));
      stringField(text, "amountMinor", Long.toString(component.amountMinor()));
      text.append(",\"explanationCodes\":");
      stringArray(text, component.explanationCodes());
      text.append('}');
    }
    text.append("]}");
    return text.toString();
  }

  /**
   * The digest of the canonical text.
   *
   * @return lower-case hex SHA-256
   */
  public String digest() {
    return PackageDigests.snapshotDigest(canonicalText());
  }

  private static void stringField(StringBuilder text, String name, String value) {
    text.append(",\"").append(name).append("\":");
    string(text, value);
  }

  private static void stringArray(StringBuilder text, List<String> values) {
    text.append('[');
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        text.append(',');
      }
      string(text, values.get(i));
    }
    text.append(']');
  }

  /** RFC 8259 minimal escaping: the quote, the backslash and controls as lower-case \\u00xx. */
  private static void string(StringBuilder text, String value) {
    text.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"') {
        text.append("\\\"");
      } else if (c == '\\') {
        text.append("\\\\");
      } else if (c < 0x20) {
        text.append("\\u00");
        text.append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xf, 16));
      } else {
        text.append(c);
      }
    }
    text.append('"');
  }

  // ------------------------------------------------------------------------------------------
  // Parsing
  // ------------------------------------------------------------------------------------------

  /**
   * Reads a stored canonical text back. The text must be exactly canonical: it is rebuilt and
   * compared byte for byte, so a stored text that does not round trip is refused.
   *
   * @param text a canonical snapshot text
   * @return the snapshot
   * @throws IllegalArgumentException when the text is not a canonical schema 1 snapshot
   */
  public static PayslipSnapshot parse(String text) {
    try {
      Map<String, Object> root = object(StatutoryJson.parse(text));
      if (!KEYS.equals(root.keySet())) {
        throw new IllegalArgumentException("snapshot fields");
      }
      Map<String, Object> pack = object(root.get("rulePack"));
      List<Component> components = new ArrayList<>();
      for (Object item : list(root.get("components"))) {
        Map<String, Object> c = object(item);
        components.add(
            new Component(
                integer(c.get("seq")),
                string(c.get("id")),
                string(c.get("party")),
                string(c.get("category")),
                amount(c.get("baseMinor")),
                amount(c.get("amountMinor")),
                strings(c.get("explanationCodes"))));
      }
      PayslipSnapshot snapshot =
          new PayslipSnapshot(
              UUID.fromString(string(root.get("packageId"))),
              UUID.fromString(string(root.get("legalEntityId"))),
              string(root.get("period")),
              UUID.fromString(string(root.get("employeeId"))),
              string(root.get("employeeNumber")),
              Instant.parse(string(root.get("approvedAt"))),
              string(pack.get("code")),
              integer(pack.get("version")),
              Boolean.TRUE.equals(root.get("excluded")),
              amount(root.get("contractualGrossMinor")),
              amount(root.get("employeeCnssMinor")),
              amount(root.get("irppWithholdingMinor")),
              amount(root.get("employeeDeductionsMinor")),
              amount(root.get("netMinor")),
              amount(root.get("employerContributionsMinor")),
              strings(root.get("explanationCodes")),
              components);
      if (!snapshot.canonicalText().equals(text)) {
        throw new IllegalArgumentException("snapshot is not canonical");
      }
      return snapshot;
    } catch (StatutoryRulesException
        | ClassCastException
        | NullPointerException
        | ArithmeticException
        | java.time.DateTimeException invalid) {
      throw new IllegalArgumentException("snapshot does not parse", invalid);
    }
  }

  private static final Set<String> KEYS =
      Set.of(
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
          "components");

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) Objects.requireNonNull(value);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) Objects.requireNonNull(value);
  }

  private static String string(Object value) {
    return (String) Objects.requireNonNull(value);
  }

  private static int integer(Object value) {
    return Math.toIntExact((Long) Objects.requireNonNull(value));
  }

  private static long amount(Object value) {
    return Long.parseLong(string(value));
  }

  private static List<String> strings(Object value) {
    List<String> result = new ArrayList<>();
    for (Object item : list(value)) {
      result.add(string(item));
    }
    return result;
  }

  @Override
  public String toString() {
    return "PayslipSnapshot[" + packageId + "]";
  }
}
