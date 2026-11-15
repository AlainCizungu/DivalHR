package com.divalhr.core.payroll.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Offline, deterministic technical validation of a statutory pack version against its immutable
 * reviewed reference cases (MVP-097A, D97A-5, ADR 0040). Pure: no clock, no I/O, no locale API, no
 * floating point. The same input always yields the same byte-identical report.
 *
 * <p>Technical validation proves a mechanism: the pack parses under the closed formula model, every
 * source and variable it cites is declared, money is integer minor units, brackets are ordered, the
 * effective period is valid, and every reference case has exact expected results. It is NOT legal
 * approval and says nothing about whether any rate or rule is correct in law.
 */
public final class StatutoryPackValidator {

  /** The version of this validator; recorded with every validation run. */
  public static final String ENGINE_VERSION = "pack-validator-1";

  /** The most reference cases of one pack version. */
  public static final int MAX_CASES = 100;

  /** The most characters of a canonical pack plus fixtures. */
  public static final int MAX_CANONICAL = 1_048_576;

  private static final String FIXTURE_HEADER = "DIVALHR-STATUTORY-REFERENCE-CASES\nversion=1\n";

  private StatutoryPackValidator() {}

  /**
   * One reviewed reference case, as stored.
   *
   * @param key the stable case key
   * @param inputsJson the inputs document: {@code cnssSubject}, {@code irppSubject}, {@code
   *     grossMinor}
   * @param expectedJson the expected results: {@code employeeDeductionsMinor}, {@code
   *     employerContributionsMinor}, {@code components} (id to amount)
   */
  public record ReferenceCase(String key, String inputsJson, String expectedJson) {

    /** Requires every member. */
    public ReferenceCase {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(inputsJson, "inputsJson");
      Objects.requireNonNull(expectedJson, "expectedJson");
    }
  }

  /**
   * One named check and its outcome.
   *
   * @param code the stable check code
   * @param passed whether it passed
   */
  public record Check(String code, boolean passed) {}

  /**
   * The validation report.
   *
   * @param checks every check, in a fixed order
   * @param fixtureSha256 the canonical fixture digest, or {@code null} when the cases are unusable
   * @param engineVersion the validator version
   */
  public record Report(List<Check> checks, String fixtureSha256, String engineVersion) {

    /** Copies the checks. */
    public Report {
      checks = List.copyOf(checks);
    }

    /**
     * Whether every check passed.
     *
     * @return true when the pack validated technically
     */
    public boolean passed() {
      return checks.stream().allMatch(Check::passed);
    }

    /**
     * The number of failed checks.
     *
     * @return the count
     */
    public int failed() {
      return (int) checks.stream().filter(check -> !check.passed()).count();
    }
  }

  /**
   * Validates a pack version.
   *
   * @param rulesJson the pack's rules document
   * @param sourceKeys the pack's declared source keys
   * @param effectiveFrom first day of the interval
   * @param effectiveTo last day of the interval, or {@code null} when open
   * @param cases the reference cases ordered by their sequence
   * @return the report; never throws for bad content
   */
  public static Report validate(
      String rulesJson,
      Set<String> sourceKeys,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      List<ReferenceCase> cases) {
    List<Check> checks = new ArrayList<>();
    StatutoryRules rules = null;
    try {
      rules = StatutoryRules.parse(rulesJson);
      checks.add(new Check("PACK_PARSES", true));
    } catch (StatutoryRulesException invalid) {
      checks.add(new Check("PACK_PARSES", false));
    }
    checks.add(new Check("SOURCES_DECLARED", rules != null && sourcesDeclared(rules, sourceKeys)));
    checks.add(new Check("MONEY_INTEGER_MINOR", rules != null && integerMoney(rulesJson)));
    checks.add(new Check("BRACKETS_ORDERED", rules != null && bracketsOrdered(rules)));
    checks.add(new Check("EFFECTIVE_PERIOD_VALID", period(effectiveFrom, effectiveTo)));
    checks.add(
        new Check("CASES_PRESENT", cases != null && !cases.isEmpty() && cases.size() <= MAX_CASES));
    List<Parsed> parsed = new ArrayList<>();
    boolean wellFormed = cases != null;
    if (wellFormed) {
      Set<String> keys = new HashSet<>();
      for (ReferenceCase reference : cases) {
        Parsed one = parse(reference);
        if (one == null || !keys.add(reference.key())) {
          wellFormed = false;
        } else {
          parsed.add(one);
        }
      }
    }
    checks.add(new Check("CASES_WELL_FORMED", wellFormed));
    checks.add(
        new Check("CASES_COVER_RULES", rules != null && wellFormed && coversRules(rules, parsed)));
    boolean deterministic = false;
    boolean ascii = false;
    if (rules != null && wellFormed) {
      for (Parsed one : parsed) {
        checks.add(new Check("CASE:" + one.key(), matches(rules, one)));
      }
      String first = canonicalResults(rules, parsed);
      String second = canonicalResults(rules, parsed);
      deterministic = first.equals(second);
      ascii = first.chars().allMatch(c -> c == 0x0a || (c >= 0x20 && c < 0x7f));
    } else {
      for (ReferenceCase reference : cases == null ? List.<ReferenceCase>of() : cases) {
        checks.add(new Check("CASE:" + reference.key(), false));
      }
    }
    checks.add(new Check("DETERMINISTIC", deterministic));
    checks.add(new Check("LOCALE_INDEPENDENT", ascii));
    String fixtureSha = wellFormed && !parsed.isEmpty() ? fixtureDigest(parsed) : null;
    checks.add(
        new Check(
            "DIGESTS_STABLE",
            fixtureSha != null
                && fixtureSha.equals(fixtureDigest(parsed))
                && rulesJson.length() + fixtureLength(parsed) <= MAX_CANONICAL));
    return new Report(checks, fixtureSha, ENGINE_VERSION);
  }

  /**
   * The canonical digest of the reference cases (lower-case hex SHA-256), or {@code null} when a
   * case is malformed.
   *
   * @param cases the cases ordered by sequence
   * @return the digest
   */
  public static String fixtureSha256(List<ReferenceCase> cases) {
    List<Parsed> parsed = new ArrayList<>();
    for (ReferenceCase reference : cases) {
      Parsed one = parse(reference);
      if (one == null) {
        return null;
      }
      parsed.add(one);
    }
    return parsed.isEmpty() ? null : fixtureDigest(parsed);
  }

  // ------------------------------------------------------------------------------------------

  private record Parsed(
      String key,
      boolean cnss,
      boolean irpp,
      long gross,
      long employee,
      long employer,
      TreeMap<String, Long> components) {}

  private static Parsed parse(ReferenceCase reference) {
    try {
      if (!reference.key().matches("[A-Z][A-Z0-9_-]{1,63}")) {
        return null;
      }
      Object inputs = StatutoryJson.parse(reference.inputsJson());
      Object expected = StatutoryJson.parse(reference.expectedJson());
      if (!(inputs instanceof Map<?, ?> in) || !(expected instanceof Map<?, ?> out)) {
        return null;
      }
      if (in.size() != 3 || out.size() != 3) {
        return null;
      }
      if (!(in.get("cnssSubject") instanceof Boolean cnss)
          || !(in.get("irppSubject") instanceof Boolean irpp)
          || !(in.get("grossMinor") instanceof Long gross)
          || gross < 0
          || !(out.get("employeeDeductionsMinor") instanceof Long employee)
          || !(out.get("employerContributionsMinor") instanceof Long employer)
          || !(out.get("components") instanceof Map<?, ?> components)) {
        return null;
      }
      TreeMap<String, Long> amounts = new TreeMap<>();
      for (Map.Entry<?, ?> entry : components.entrySet()) {
        if (!(entry.getKey() instanceof String id)
            || !(entry.getValue() instanceof Long amount)
            || amount < 0) {
          return null;
        }
        amounts.put(id, amount);
      }
      return new Parsed(reference.key(), cnss, irpp, gross, employee, employer, amounts);
    } catch (StatutoryRulesException invalid) {
      return null;
    }
  }

  private static boolean matches(StatutoryRules rules, Parsed one) {
    StatutoryLine line;
    try {
      line = StatutoryEngine.calculate(rules, one.cnss(), one.irpp(), one.gross());
    } catch (ArithmeticException | IllegalArgumentException overflow) {
      return false;
    }
    if (line.employeeDeductionsMinor() != one.employee()
        || line.employerContributionsMinor() != one.employer()) {
      return false;
    }
    TreeMap<String, Long> actual = new TreeMap<>();
    for (StatutoryLine.Component component : line.components()) {
      actual.put(component.componentId(), component.amountMinor());
    }
    // An excluded line has no components: the reference then lists none.
    return actual.equals(one.components());
  }

  private static boolean sourcesDeclared(StatutoryRules rules, Set<String> sourceKeys) {
    try {
      rules.requireSources(sourceKeys);
      return true;
    } catch (StatutoryRulesException undeclared) {
      return false;
    }
  }

  /** The parser admits integers only; this re-checks the raw text for a fraction or exponent. */
  private static boolean integerMoney(String rulesJson) {
    boolean inString = false;
    for (int i = 0; i < rulesJson.length(); i++) {
      char c = rulesJson.charAt(i);
      if (c == '"') {
        inString = !inString;
      } else if (!inString
          && (c == '.'
              || ((c == 'e' || c == 'E') && i > 0 && Character.isDigit(rulesJson.charAt(i - 1))))) {
        return false;
      }
    }
    return true;
  }

  private static boolean bracketsOrdered(StatutoryRules rules) {
    for (StatutoryRules.Component component : rules.components()) {
      if (component.method() instanceof StatutoryRules.Progressive progressive) {
        long previous = 0;
        List<StatutoryRules.Bracket> brackets = progressive.brackets();
        for (int i = 0; i < brackets.size(); i++) {
          Long upTo = brackets.get(i).upToAnnual();
          boolean last = i == brackets.size() - 1;
          if (last != (upTo == null) || (upTo != null && upTo <= previous)) {
            return false;
          }
          if (upTo != null) {
            previous = upTo;
          }
        }
      }
    }
    return true;
  }

  private static boolean period(LocalDate from, LocalDate to) {
    LocalDate min = LocalDate.of(2000, 1, 1);
    LocalDate max = LocalDate.of(2999, 12, 31);
    return from != null
        && !from.isBefore(min)
        && !from.isAfter(max)
        && (to == null || (!to.isBefore(from) && !to.isAfter(max)));
  }

  /** Every flag a rule requires is exercised both subject and not subject by some case. */
  private static boolean coversRules(StatutoryRules rules, List<Parsed> cases) {
    for (StatutoryRules.Component component : rules.components()) {
      boolean subject = false;
      boolean notSubject = false;
      for (Parsed one : cases) {
        boolean applies =
            component.requires() == StatutoryRules.Requirement.CNSS_SUBJECT
                ? one.cnss()
                : one.irpp();
        subject |= applies;
        notSubject |= !applies;
      }
      if (!subject || !notSubject) {
        return false;
      }
    }
    return true;
  }

  private static String canonicalResults(StatutoryRules rules, List<Parsed> cases) {
    StringBuilder text = new StringBuilder();
    for (Parsed one : cases) {
      StatutoryLine line;
      try {
        line = StatutoryEngine.calculate(rules, one.cnss(), one.irpp(), one.gross());
      } catch (ArithmeticException | IllegalArgumentException overflow) {
        text.append(one.key()).append("=OVERFLOW\n");
        continue;
      }
      text.append(one.key())
          .append('|')
          .append(line.employeeDeductionsMinor())
          .append('|')
          .append(line.employerContributionsMinor());
      TreeMap<String, Long> amounts = new TreeMap<>();
      for (StatutoryLine.Component component : line.components()) {
        amounts.put(component.componentId(), component.amountMinor());
      }
      amounts.forEach((id, amount) -> text.append('|').append(id).append('=').append(amount));
      text.append('\n');
    }
    return text.toString();
  }

  private static String canonicalFixtures(List<Parsed> cases) {
    StringBuilder text = new StringBuilder(FIXTURE_HEADER);
    for (Parsed one : cases) {
      text.append(one.key())
          .append('|')
          .append(one.cnss())
          .append('|')
          .append(one.irpp())
          .append('|')
          .append(one.gross())
          .append('|')
          .append(one.employee())
          .append('|')
          .append(one.employer());
      one.components()
          .forEach((id, amount) -> text.append('|').append(id).append('=').append(amount));
      text.append('\n');
    }
    return text.toString();
  }

  private static int fixtureLength(List<Parsed> cases) {
    return canonicalFixtures(cases).length();
  }

  private static String fixtureDigest(List<Parsed> cases) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(canonicalFixtures(cases).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(64);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is always available", impossible);
    }
  }
}
