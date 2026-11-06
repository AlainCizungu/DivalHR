package com.divalhr.core.payroll.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The canonical JSON manifest of an accounting journal (MVP-096, ADR 0039 section 9): keys in
 * alphabetical order, no insignificant whitespace, no line feed. It carries identifiers, versions,
 * the period, the currency, the counts, the exact totals, the generation instant (UTC, millisecond
 * precision) and the CSV file name, size and SHA-256. It carries no source status and no employee
 * data, so it is byte-identical on every download, including after the package is voided.
 */
public final class AccountingManifest {

  private static final DateTimeFormatter INSTANT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private AccountingManifest() {}

  /**
   * The facts the manifest states.
   *
   * @param journalId journal
   * @param journalVersion journal version
   * @param packageId package
   * @param packageVersion package version
   * @param calculationId calculation
   * @param calculationVersion calculation version
   * @param legalEntityId legal entity
   * @param legalEntityCode stored legal entity code
   * @param period {@code YYYY-MM}
   * @param currency currency code
   * @param mappingId mapping
   * @param mappingVersion mapping version
   * @param mappingDigest mapping digest
   * @param lineCount lines
   * @param totalDebitsMinor total debits
   * @param totalCreditsMinor total credits
   * @param generatedAt generation instant
   * @param csvFileName generated CSV file name
   * @param csvBytes CSV size in bytes
   * @param csvSha256 SHA-256 of the CSV bytes
   */
  public record Facts(
      UUID journalId,
      int journalVersion,
      UUID packageId,
      int packageVersion,
      UUID calculationId,
      int calculationVersion,
      UUID legalEntityId,
      String legalEntityCode,
      String period,
      String currency,
      UUID mappingId,
      int mappingVersion,
      String mappingDigest,
      int lineCount,
      long totalDebitsMinor,
      long totalCreditsMinor,
      Instant generatedAt,
      String csvFileName,
      int csvBytes,
      String csvSha256) {}

  /**
   * Builds the manifest.
   *
   * @param facts the journal facts
   * @return the bytes
   */
  public static byte[] manifest(Facts facts) {
    Map<String, Object> values = new TreeMap<>();
    values.put("schemaVersion", 1);
    values.put("journalId", facts.journalId().toString());
    values.put("journalVersion", facts.journalVersion());
    values.put("packageId", facts.packageId().toString());
    values.put("packageVersion", facts.packageVersion());
    values.put("calculationId", facts.calculationId().toString());
    values.put("calculationVersion", facts.calculationVersion());
    values.put("legalEntityId", facts.legalEntityId().toString());
    values.put("legalEntityCode", facts.legalEntityCode());
    values.put("period", facts.period());
    values.put("currency", facts.currency());
    values.put("mappingId", facts.mappingId().toString());
    values.put("mappingVersion", facts.mappingVersion());
    values.put("mappingDigest", facts.mappingDigest());
    values.put("lineCount", facts.lineCount());
    values.put("totalDebitsMinor", facts.totalDebitsMinor());
    values.put("totalCreditsMinor", facts.totalCreditsMinor());
    values.put("generatedAt", INSTANT.format(facts.generatedAt()));
    values.put("csvFileName", facts.csvFileName());
    values.put("csvBytes", facts.csvBytes());
    values.put("csvSha256", facts.csvSha256());
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append('"').append(entry.getKey()).append("\":");
      if (entry.getValue() instanceof String text) {
        json.append(quote(text));
      } else {
        json.append(entry.getValue());
      }
    }
    return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String quote(String text) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c < 0x20) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.append('"').toString();
  }
}
