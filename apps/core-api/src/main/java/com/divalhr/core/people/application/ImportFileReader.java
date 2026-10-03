package com.divalhr.core.people.application;

import com.divalhr.core.people.application.CsvRecordSource.Malformation;
import com.divalhr.core.people.application.CsvRecordSource.MalformedCsvException;
import com.divalhr.core.people.domain.ImportColumn;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns the received bytes into an {@link ImportFile} (MVP-020, A20-3, A20-6), enforcing the file
 * level of the accepted subset around the CSV parser port: strict UTF-8 (a leading byte-order mark
 * is ignored), at most 4,096 characters per physical line, comma or semicolon chosen once from the
 * header, a header of known, unique columns including every required one, at most 20 cells per
 * record, empty and all-blank lines ignored, and the row cap. Any failure is {@code 400
 * IMPORT_FILE_INVALID} with a reason and, for header problems, a column position or key: never the
 * file's content.
 */
@Component
public class ImportFileReader {

  /** Characters allowed on one physical line. */
  public static final int MAX_LINE_LENGTH = 4_096;

  /** Cells allowed per record (nine known columns plus room to report unknown ones). */
  public static final int MAX_COLUMNS = 20;

  private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  private final CsvRecordSource csv;

  /**
   * Creates the reader.
   *
   * @param csv CSV parser port
   */
  public ImportFileReader(CsvRecordSource csv) {
    this.csv = csv;
  }

  /** Stops parsing when the row cap is exceeded. */
  private static final class TooManyRows extends RuntimeException {
    private static final long serialVersionUID = 1L;

    TooManyRows() {
      super(null, null, false, false);
    }
  }

  /**
   * Reads the file.
   *
   * @param bytes received body
   * @param maxRows data row cap
   * @return the file
   * @throws ApiException {@code IMPORT_FILE_INVALID} on any file-level problem
   */
  public ImportFile read(byte[] bytes, int maxRows) {
    String sha256 = sha256(bytes);
    String text = decode(bytes);
    if (text.isBlank()) {
      throw invalid("EMPTY", null);
    }
    checkLineLengths(text);
    String headerLine = firstNonEmptyLine(text);
    Header header = chooseHeader(headerLine);
    List<ImportFile.RawRow> rows = new ArrayList<>();
    boolean[] headerSeen = {false};
    try {
      csv.read(
          text,
          header.delimiter,
          MAX_COLUMNS,
          cells -> {
            if (!headerSeen[0]) {
              headerSeen[0] = true;
              return;
            }
            if (cells.stream().allMatch(String::isBlank)) {
              return;
            }
            int rowNumber = rows.size() + 1;
            if (rowNumber > maxRows) {
              throw new TooManyRows();
            }
            if (cells.size() != header.columns.size()) {
              rows.add(new ImportFile.RawRow(rowNumber, Map.of(), true));
              return;
            }
            Map<ImportColumn, String> byColumn = new EnumMap<>(ImportColumn.class);
            for (int i = 0; i < cells.size(); i++) {
              byColumn.put(header.columns.get(i), cells.get(i));
            }
            rows.add(new ImportFile.RawRow(rowNumber, byColumn, false));
          });
    } catch (TooManyRows tooMany) {
      throw invalid("TOO_MANY_ROWS", null);
    } catch (MalformedCsvException malformed) {
      throw invalid(malformed.reason().name(), null);
    }
    if (rows.isEmpty()) {
      throw invalid("NO_ROWS", null);
    }
    return new ImportFile(
        sha256,
        header.delimiter == ',' ? "COMMA" : "SEMICOLON",
        header.language,
        header.columns,
        rows);
  }

  private static String decode(byte[] bytes) {
    int offset = startsWithBom(bytes) ? BOM.length : 0;
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
          .toString();
    } catch (CharacterCodingException notUtf8) {
      throw invalid("ENCODING", null);
    }
  }

  private static boolean startsWithBom(byte[] bytes) {
    return bytes.length >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2];
  }

  private static void checkLineLengths(String text) {
    int length = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n') {
        length = 0;
      } else if (++length > MAX_LINE_LENGTH) {
        throw invalid("LINE_TOO_LONG", null);
      }
    }
  }

  private static String firstNonEmptyLine(String text) {
    for (String line : text.split("\n", -1)) {
      String stripped = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
      if (!stripped.isEmpty()) {
        return stripped;
      }
    }
    throw invalid("EMPTY", null);
  }

  /** A header candidate for one delimiter. */
  private record Header(
      char delimiter, List<ImportColumn> columns, String language, ApiException problem) {}

  private Header chooseHeader(String headerLine) {
    Header comma = header(headerLine, ',');
    Header semicolon = header(headerLine, ';');
    if (comma.problem == null && semicolon.problem == null) {
      throw invalid("DELIMITER", null);
    }
    if (comma.problem == null) {
      return comma;
    }
    if (semicolon.problem == null) {
      return semicolon;
    }
    // Neither works: report the problem of the delimiter that splits the header into more cells.
    Header likelier = semicolon.columns.size() > comma.columns.size() ? semicolon : comma;
    throw likelier.problem;
  }

  private Header header(String line, char delimiter) {
    List<String> cells = new ArrayList<>();
    try {
      csv.read(
          line,
          delimiter,
          MAX_COLUMNS,
          record -> {
            if (cells.isEmpty()) {
              cells.addAll(record);
            }
          });
    } catch (MalformedCsvException malformed) {
      return new Header(
          delimiter,
          List.of(),
          "en",
          invalid(
              malformed.reason() == Malformation.TOO_MANY_COLUMNS
                  ? "TOO_MANY_COLUMNS"
                  : "MALFORMED",
              null));
    }
    List<ImportColumn> columns = new ArrayList<>();
    Set<ImportColumn.HeaderLanguage> languages = EnumSet.noneOf(ImportColumn.HeaderLanguage.class);
    ApiException problem = null;
    for (int i = 0; i < cells.size(); i++) {
      Optional<ImportColumn.Match> match = ImportColumn.match(cells.get(i));
      if (match.isEmpty()) {
        problem = problem == null ? invalid("COLUMN_UNKNOWN", i + 1) : problem;
        columns.add(null);
        continue;
      }
      ImportColumn column = match.get().column();
      if (columns.contains(column)) {
        problem = problem == null ? invalid("COLUMN_DUPLICATE", i + 1) : problem;
      }
      columns.add(column);
      languages.add(match.get().language());
    }
    if (problem == null) {
      for (ImportColumn column : ImportColumn.values()) {
        if (column.required() && !columns.contains(column)) {
          problem = invalid("COLUMN_MISSING", column.key());
          break;
        }
      }
    }
    return new Header(delimiter, columns, language(languages), problem);
  }

  private static String language(Set<ImportColumn.HeaderLanguage> languages) {
    boolean french = languages.contains(ImportColumn.HeaderLanguage.FR);
    boolean english = languages.contains(ImportColumn.HeaderLanguage.EN);
    if (french && english) {
      return "mixed";
    }
    return french ? "fr" : "en";
  }

  private static ApiException invalid(String reason, Object column) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("reason", reason);
    if (column != null) {
      params.put("column", column);
    }
    return new ApiException(ErrorCode.IMPORT_FILE_INVALID, params);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
