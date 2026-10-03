package com.divalhr.core.people.application;

import com.divalhr.core.people.domain.ImportColumn;
import java.util.List;
import java.util.Map;

/**
 * A decoded, structurally valid import file: header mapping and raw data rows (MVP-020). Raw cell
 * values are personal data and never leave the application layer except as normalized, validated
 * values of valid rows.
 *
 * @param fileSha256 SHA-256 of the received bytes (idempotency and audit integrity)
 * @param delimiter {@code COMMA} or {@code SEMICOLON}
 * @param headerLanguage {@code fr}, {@code en} or {@code mixed}
 * @param columns the file's columns in header order
 * @param rows data rows in file order
 */
public record ImportFile(
    String fileSha256,
    String delimiter,
    String headerLanguage,
    List<ImportColumn> columns,
    List<RawRow> rows) {

  /** Copies the lists. */
  public ImportFile {
    columns = List.copyOf(columns);
    rows = List.copyOf(rows);
  }

  /**
   * One data row before validation.
   *
   * @param rowNumber 1-based data row number
   * @param cells raw cells by column, or empty when the row has the wrong number of cells
   * @param shapeError whether the row has another number of cells than the header
   */
  public record RawRow(int rowNumber, Map<ImportColumn, String> cells, boolean shapeError) {

    /** Copies the cells. */
    public RawRow {
      cells = Map.copyOf(cells);
    }

    @Override
    public String toString() {
      return "RawRow[" + rowNumber + "]";
    }
  }

  @Override
  public String toString() {
    return "ImportFile[" + delimiter + ", " + headerLanguage + ", rows=" + rows.size() + "]";
  }
}
