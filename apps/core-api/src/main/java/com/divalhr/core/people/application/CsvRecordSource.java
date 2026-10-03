package com.divalhr.core.people.application;

import java.util.List;
import java.util.function.Consumer;

/**
 * The people module's port to a mature CSV parser (MVP-020, architect amendment A20-3). The
 * implementation configures the parser for exactly the accepted subset: the given delimiter,
 * double-quote quoting with doubled-quote escapes, no escape character, no comments, no trimming,
 * zero-length lines ignored, and malformed quoting as a hard, deterministic failure. Everything
 * else (encoding, size, line and cell limits, headers, characters) is enforced around it by {@link
 * ImportFileReader}.
 */
public interface CsvRecordSource {

  /**
   * Parses the text record by record.
   *
   * @param text decoded CSV text
   * @param delimiter comma or semicolon
   * @param maxColumns cells allowed per record
   * @param sink receives each record's cells in order; it may throw to stop
   * @throws MalformedCsvException when the text is not well-formed CSV or a record is too wide
   */
  void read(String text, char delimiter, int maxColumns, Consumer<List<String>> sink);

  /** Why the text could not be parsed. */
  enum Malformation {
    /** Malformed quoting or an unterminated quoted value. */
    MALFORMED,
    /** A record has more cells than allowed. */
    TOO_MANY_COLUMNS
  }

  /** The text is not CSV of the accepted subset. Carries no content. */
  final class MalformedCsvException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Malformation reason;

    /**
     * Creates the exception.
     *
     * @param reason why
     */
    public MalformedCsvException(Malformation reason) {
      super(reason.name(), null, false, false);
      this.reason = reason;
    }

    /**
     * Why the text could not be parsed.
     *
     * @return the reason
     */
    public Malformation reason() {
      return reason;
    }
  }
}
