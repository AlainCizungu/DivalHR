package com.divalhr.core.people.internal;

import com.divalhr.core.people.application.CsvRecordSource;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.springframework.stereotype.Component;

/**
 * {@link CsvRecordSource} on Apache Commons CSV (MVP-020, A20-3), pinned in the build. The format
 * is RFC 4180 with the chosen delimiter: double-quote quoting, doubled quotes as the only escape,
 * no escape character, no comment marker, no trimming or surrounding-space handling (the import
 * normalizes values itself), zero-length lines ignored, headers handled by the caller, and a
 * non-lenient end of file. Any parser exception is reported as {@code MALFORMED} without content.
 */
@Component
public class CommonsCsvRecordSource implements CsvRecordSource {

  static CSVFormat format(char delimiter) {
    return CSVFormat.RFC4180
        .builder()
        .setDelimiter(delimiter)
        .setQuote('"')
        .setEscape((Character) null)
        .setCommentMarker((Character) null)
        .setIgnoreEmptyLines(true)
        .setIgnoreSurroundingSpaces(false)
        .setTrim(false)
        .setIgnoreHeaderCase(false)
        .setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL)
        .setLenientEof(false)
        .setTrailingData(false)
        .get();
  }

  @Override
  public void read(String text, char delimiter, int maxColumns, Consumer<List<String>> sink) {
    if (delimiter != ',' && delimiter != ';') {
      throw new IllegalArgumentException("delimiter");
    }
    try (CSVParser parser = CSVParser.parse(new StringReader(text), format(delimiter))) {
      for (CSVRecord csvRecord : parser) {
        if (csvRecord.size() > maxColumns) {
          throw new MalformedCsvException(Malformation.TOO_MANY_COLUMNS);
        }
        List<String> cells = new ArrayList<>(csvRecord.size());
        for (String cell : csvRecord) {
          cells.add(cell);
        }
        sink.accept(cells);
      }
    } catch (UncheckedIOException | IOException | IllegalStateException malformed) {
      throw new MalformedCsvException(Malformation.MALFORMED);
    }
  }
}
