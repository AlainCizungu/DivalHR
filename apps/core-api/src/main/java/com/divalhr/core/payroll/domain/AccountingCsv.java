package com.divalhr.core.payroll.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * The deterministic CSV of an accounting journal (MVP-096, ADR 0039 section 9): UTF-8 with a byte
 * order mark, comma separated, RFC 4180 escaping (a cell is quoted only when it contains a comma, a
 * quote, a carriage return or a line feed), CRLF after every row including the last, fixed columns,
 * lines in journal order, lower-case UUIDs and plain integers. It never contains an employee name,
 * number, e-mail, bank account, phone, national identifier, payslip detail or free text, and never
 * the status of the source package, so the file is byte-identical before and after a void.
 */
public final class AccountingCsv {

  /** The CSV header, in the fixed column order. */
  public static final String HEADER =
      "journal_id,package_id,legal_entity_id,currency,line_number,account_code,direction,"
          + "amount_minor,cost_center_code,site_code";

  private static final String BOM = "﻿";
  private static final String CRLF = "\r\n";

  private AccountingCsv() {}

  /**
   * Builds the CSV.
   *
   * @param journalId journal
   * @param packageId package
   * @param legalEntityId legal entity
   * @param currency currency code
   * @param lines the lines in journal order
   * @return the bytes
   */
  public static byte[] csv(
      UUID journalId,
      UUID packageId,
      UUID legalEntityId,
      String currency,
      List<AccountingLine> lines) {
    StringBuilder out = new StringBuilder(BOM).append(HEADER).append(CRLF);
    for (AccountingLine line : lines) {
      out.append(cell(journalId.toString()))
          .append(',')
          .append(cell(packageId.toString()))
          .append(',')
          .append(cell(legalEntityId.toString()))
          .append(',')
          .append(cell(currency))
          .append(',')
          .append(line.lineNumber())
          .append(',')
          .append(cell(line.accountCode()))
          .append(',')
          .append(line.direction().name())
          .append(',')
          .append(line.amountMinor())
          .append(',')
          .append(cell(line.costCenterCode() == null ? "" : line.costCenterCode()))
          .append(',')
          .append(cell(line.siteCode() == null ? "" : line.siteCode()))
          .append(CRLF);
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Encodes one cell: quoted only when it contains a comma, a quote, a carriage return or a line
   * feed, with quotes doubled.
   *
   * @param value the cell text
   * @return the CSV field
   */
  static String cell(String value) {
    boolean quote =
        value.indexOf(',') >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;
    if (!quote) {
      return value;
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}
