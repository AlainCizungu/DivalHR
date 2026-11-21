package com.divalhr.core.integrations.partner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one authoritative country catalogue of partner applications (R216-1): the 249 officially
 * assigned ISO 3166-1 alpha-2 codes. The web catalogue is generated from the same resource and a
 * parity test fails when the two differ. Display names are never stored here: each client derives
 * them from {@code Intl.DisplayNames}. There is deliberately no {@code OTHER} value.
 */
public final class PartnerCountries {

  /** The classpath resource, one code per line. */
  public static final String RESOURCE = "/catalogues/iso-3166-1-alpha-2.txt";

  private static final Set<String> CODES = load();

  private PartnerCountries() {}

  /**
   * Whether a value is an assigned ISO 3166-1 alpha-2 code (upper case, exact).
   *
   * @param code candidate code
   * @return true when the catalogue lists it
   */
  public static boolean contains(String code) {
    return code != null && CODES.contains(code);
  }

  /**
   * All codes in order.
   *
   * @return a sorted copy, so callers cannot reach the catalogue itself
   */
  public static Set<String> codes() {
    return new TreeSet<>(CODES);
  }

  private static Set<String> load() {
    try (InputStream in = PartnerCountries.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing country catalogue " + RESOURCE);
      }
      Set<String> codes = new TreeSet<>();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String code = line.strip();
          if (!code.isEmpty()) {
            codes.add(code);
          }
        }
      }
      return Collections.unmodifiableSet(codes);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }
}
