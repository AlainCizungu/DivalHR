package com.divalhr.core.payroll.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * The deterministic payment register of a prepared batch (MVP-095A, D95A-7): a UTF-8 CSV and a
 * canonical JSON manifest. Fixed column order, CRLF, no byte order mark, formula-safe cells, lines
 * by employee ID, masked destinations only, no destination detail and no employee name. The
 * register is an instruction-preparation artifact; it is not proof of transmission or payment.
 */
public final class PaymentRegister {

  /** The CSV header, in the fixed column order. */
  public static final String HEADER =
      "packageId,packageVersion,batchId,batchVersion,batchStatus,legalEntityId,period,currency,"
          + "employeeNumber,paymentMethod,providerCode,maskedDestination,netAmountMinor";

  private PaymentRegister() {}

  /**
   * The facts of the batch a register states.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param packageVersion package version
   * @param packageApprovalDigest package approval digest
   * @param batchId batch
   * @param batchVersion batch version
   * @param status PREPARED or CANCELLED
   * @param period YYYY-MM
   * @param currency currency
   * @param lineCount lines
   * @param zeroNetExcluded zero-net payslips left out
   * @param bank BANK_ACCOUNT lines
   * @param mobile MOBILE_MONEY lines
   * @param manual MANUAL lines
   * @param totalNet total in minor units
   */
  public record Header(
      UUID legalEntityId,
      UUID packageId,
      int packageVersion,
      String packageApprovalDigest,
      UUID batchId,
      int batchVersion,
      String status,
      String period,
      String currency,
      int lineCount,
      int zeroNetExcluded,
      int bank,
      int mobile,
      int manual,
      long totalNet) {}

  /**
   * One stored line.
   *
   * @param employeeNumber employee number
   * @param method payment method
   * @param providerCode provider code, or null for MANUAL
   * @param masked masked destination, or null for MANUAL
   * @param net approved net in minor units
   */
  public record Row(
      String employeeNumber, PaymentMethod method, String providerCode, String masked, long net) {

    @Override
    public String toString() {
      return "PaymentRegisterRow[redacted]";
    }
  }

  /**
   * Builds the CSV.
   *
   * @param header the batch facts
   * @param rows the lines, already ordered by employee ID
   * @return the bytes
   */
  public static byte[] csv(Header header, List<Row> rows) {
    StringBuilder out = new StringBuilder(HEADER).append("\r\n");
    for (Row row : rows) {
      out.append(cell(header.packageId().toString()))
          .append(',')
          .append(header.packageVersion())
          .append(',')
          .append(cell(header.batchId().toString()))
          .append(',')
          .append(header.batchVersion())
          .append(',')
          .append(cell(header.status()))
          .append(',')
          .append(cell(header.legalEntityId().toString()))
          .append(',')
          .append(cell(header.period()))
          .append(',')
          .append(cell(header.currency()))
          .append(',')
          .append(cell(row.employeeNumber()))
          .append(',')
          .append(cell(row.method().name()))
          .append(',')
          .append(cell(row.providerCode() == null ? "" : row.providerCode()))
          .append(',')
          .append(cell(row.masked() == null ? "" : row.masked()))
          .append(',')
          .append(row.net())
          .append("\r\n");
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Makes one text cell safe: a cell a spreadsheet would read as a formula is prefixed with an
   * apostrophe, and a cell with a comma, a quote, a line break or an edge space is quoted.
   *
   * @param value the cell text
   * @return the CSV field
   */
  static String cell(String value) {
    String safe = value;
    if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
      safe = "'" + safe;
    }
    boolean quote =
        safe.indexOf(',') >= 0
            || safe.indexOf('"') >= 0
            || safe.indexOf('\n') >= 0
            || safe.indexOf('\r') >= 0
            || safe.startsWith(" ")
            || safe.endsWith(" ");
    if (!quote) {
      return safe;
    }
    return '"' + safe.replace("\"", "\"\"") + '"';
  }

  /**
   * Builds the canonical manifest (keys in alphabetical order, no whitespace).
   *
   * @param header the batch facts
   * @param csvSha256 the SHA-256 of the CSV bytes
   * @return the bytes
   */
  public static byte[] manifest(Header header, String csvSha256) {
    String json =
        "{\"batchId\":\""
            + header.batchId()
            + "\",\"batchStatus\":\""
            + header.status()
            + "\",\"batchVersion\":"
            + header.batchVersion()
            + ",\"csvSha256\":\""
            + csvSha256
            + "\",\"currency\":\""
            + header.currency()
            + "\",\"kind\":\"PAYMENT_REGISTER\",\"legalEntityId\":\""
            + header.legalEntityId()
            + "\",\"lineCount\":"
            + header.lineCount()
            + ",\"methodCounts\":{\"bankAccount\":"
            + header.bank()
            + ",\"manual\":"
            + header.manual()
            + ",\"mobileMoney\":"
            + header.mobile()
            + "},\"notice\":\"PAYMENT_PREPARATION_ONLY\",\"packageApprovalDigest\":\""
            + header.packageApprovalDigest()
            + "\",\"packageId\":\""
            + header.packageId()
            + "\",\"packageVersion\":"
            + header.packageVersion()
            + ",\"period\":\""
            + header.period()
            + "\",\"schemaVersion\":1,\"totalNetMinor\":"
            + header.totalNet()
            + ",\"zeroNetExcludedCount\":"
            + header.zeroNetExcluded()
            + "}";
    return json.getBytes(StandardCharsets.UTF_8);
  }
}
