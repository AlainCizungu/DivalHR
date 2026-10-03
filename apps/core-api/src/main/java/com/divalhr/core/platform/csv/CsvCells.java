package com.divalhr.core.platform.csv;

import java.util.List;

/**
 * The output rule for every CSV DivalHR produces (MVP-020, decision E10): a cell that a spreadsheet
 * could execute as a formula ({@code =}, {@code +}, {@code -}, {@code @}, tab or carriage return
 * first) is prefixed with an apostrophe, and every cell is quoted with doubled inner quotes. Input
 * is defended separately by field allow-lists that never accept these prefixes.
 */
public final class CsvCells {

  private CsvCells() {}

  /**
   * Neutralizes and quotes one cell.
   *
   * @param value cell value ({@code null} is an empty cell)
   * @return the quoted cell
   */
  public static String cell(String value) {
    String text = value == null ? "" : value;
    if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
      text = "'" + text;
    }
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  /**
   * One CSV line of neutralized, quoted cells, ending with CRLF.
   *
   * @param values cell values
   * @param delimiter delimiter
   * @return the line
   */
  public static String line(List<String> values, char delimiter) {
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        line.append(delimiter);
      }
      line.append(cell(values.get(i)));
    }
    return line.append("\r\n").toString();
  }
}
