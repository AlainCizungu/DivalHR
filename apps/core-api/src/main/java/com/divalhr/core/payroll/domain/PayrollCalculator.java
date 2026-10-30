package com.divalhr.core.payroll.domain;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The contractual gross of one employee for one month (MVP-094A, D94A-4, ADR 0033 section 4): pure
 * rules over day sets, exact integer arithmetic, no I/O, no clock and, by construction, no hours
 * input: attendance, schedules and timesheets never scale a monthly salary.
 *
 * <p>For each day of the month the employee is {@code employed} (in some employment), {@code
 * placedHere} (placed in this legal entity), {@code placedElsewhere} and covered, or not, by a
 * compensation fact of the run currency. The eligible days are the days that are employed, placed
 * here and covered in the run currency. They are a set, so overlapping intervals never count a day
 * twice; the eligible ranges are the maximal runs of that set, so hire, separation and rehire need
 * no special case. The gross is the exact monthly amount for a full month, otherwise the monthly
 * amount prorated once and rounded HALF_UP.
 *
 * <p>Restricted financial and HR data: no {@code toString} here prints an amount.
 */
public final class PayrollCalculator {

  /** The most source records a line can carry (the database allows 64). */
  static final int MAX_SOURCES = 64;

  private static final BigInteger TWO = BigInteger.TWO;

  private PayrollCalculator() {}

  /** Kinds of source record that fed a calculation. */
  public enum SourceKind {
    /** An employment. */
    EMPLOYMENT,
    /** A placement assignment. */
    PLACEMENT,
    /** A monthly compensation fact. */
    COMPENSATION
  }

  /**
   * One employment of the employee overlapping the month.
   *
   * @param id employment
   * @param version timeline version
   * @param start first day
   * @param end last day, or {@code null}
   */
  public record Employment(UUID id, long version, LocalDate start, LocalDate end) {

    /** Requires identity and start. */
    public Employment {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(start, "start");
    }
  }

  /**
   * One active placement of the employee overlapping the month.
   *
   * @param id placement
   * @param legalEntityId legal entity placed in
   * @param start first day
   * @param end last day, or {@code null}
   */
  public record Placement(UUID id, UUID legalEntityId, LocalDate start, LocalDate end) {

    /** Requires identity, entity and start. */
    public Placement {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(legalEntityId, "legalEntityId");
      Objects.requireNonNull(start, "start");
    }
  }

  /**
   * One active monthly compensation fact (MVP-093A) overlapping the month.
   *
   * @param id fact
   * @param originChangeId the change that introduced it
   * @param version the sequence of the change that created the fact
   * @param currency currency
   * @param minor monthly amount in minor units
   * @param from first day
   * @param to last day, or {@code null}
   */
  public record Fact(
      UUID id,
      UUID originChangeId,
      long version,
      CompensationCurrency currency,
      long minor,
      LocalDate from,
      LocalDate to) {

    /** Requires identity, currency and start. */
    public Fact {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(originChangeId, "originChangeId");
      Objects.requireNonNull(currency, "currency");
      Objects.requireNonNull(from, "from");
    }

    @Override
    public String toString() {
      return "Fact[" + id + "]";
    }
  }

  /**
   * An employee of the population with every source record of the month.
   *
   * @param id employee
   * @param number employee number
   * @param employments employments overlapping the month
   * @param placements placements overlapping the month, in any legal entity
   * @param facts compensation facts overlapping the month, in any currency
   */
  public record Employee(
      UUID id,
      String number,
      List<Employment> employments,
      List<Placement> placements,
      List<Fact> facts) {

    /** Copies the lists. */
    public Employee {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(number, "number");
      employments = List.copyOf(employments);
      placements = List.copyOf(placements);
      facts = List.copyOf(facts);
    }

    @Override
    public String toString() {
      return "Employee[" + id + "]";
    }
  }

  /**
   * What a run calculates.
   *
   * @param legalEntityId the legal entity of the run
   * @param period the month
   * @param currency the run currency
   * @param method the proration method of the policy in force on the last day of the month
   */
  public record Context(
      UUID legalEntityId,
      PayrollPeriod period,
      CompensationCurrency currency,
      PayrollProration method) {

    /** Requires every component. */
    public Context {
      Objects.requireNonNull(legalEntityId, "legalEntityId");
      Objects.requireNonNull(period, "period");
      Objects.requireNonNull(currency, "currency");
      Objects.requireNonNull(method, "method");
    }
  }

  /**
   * A maximal run of eligible days, inclusive.
   *
   * @param from first day
   * @param to last day
   */
  public record Range(LocalDate from, LocalDate to) {}

  /**
   * A source record that fed the calculation, by identifier and version only.
   *
   * @param kind kind
   * @param id the record
   * @param version its version (employment timeline version, compensation creating sequence, 0 for
   *     a placement)
   * @param from first day
   * @param to last day, or {@code null}
   */
  public record Source(SourceKind kind, UUID id, long version, LocalDate from, LocalDate to) {}

  /**
   * The result for one employee. With any blocker there is no monthly amount and no gross.
   *
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param eligibleDays number of eligible days
   * @param ranges the maximal eligible ranges
   * @param denominator the divisor, or {@code null} for a full month and when blocked
   * @param fullMonth whether every day of the month is eligible (and nothing blocks)
   * @param monthlyMinor monthly amount in minor units, or {@code null} when blocked
   * @param grossMinor contractual gross in minor units, or {@code null} when blocked
   * @param explanations stable explanation codes (empty when blocked)
   * @param blockers stable blockers in declaration order
   * @param sources the records that fed the gross (empty when blocked)
   */
  public record Line(
      UUID employeeId,
      String employeeNumber,
      int eligibleDays,
      List<Range> ranges,
      Integer denominator,
      boolean fullMonth,
      Long monthlyMinor,
      Long grossMinor,
      List<PayrollExplanation> explanations,
      List<PayrollBlocker> blockers,
      List<Source> sources) {

    /** Copies the lists. */
    public Line {
      ranges = List.copyOf(ranges);
      explanations = List.copyOf(explanations);
      blockers = List.copyOf(blockers);
      sources = List.copyOf(sources);
    }

    /**
     * Whether any blocker forbids the run.
     *
     * @return true when blocked
     */
    public boolean blocked() {
      return !blockers.isEmpty();
    }

    /**
     * Whether the line has a gross to record.
     *
     * @return true when not blocked and at least one day is eligible
     */
    public boolean hasGross() {
      return grossMinor != null;
    }

    @Override
    public String toString() {
      return "PayrollLine[" + employeeId + "]";
    }
  }

  // ------------------------------------------------------------------------------------------
  // Money
  // ------------------------------------------------------------------------------------------

  /**
   * {@code round_half_up(numerator / denominator)} for a non-negative numerator, as {@code
   * floor((2n + d) / (2d))}, never through a floating point number.
   *
   * @param numerator non-negative numerator
   * @param denominator positive denominator
   * @return the rounded quotient
   */
  public static BigInteger roundHalfUp(BigInteger numerator, BigInteger denominator) {
    if (numerator.signum() < 0 || denominator.signum() <= 0) {
      throw new IllegalArgumentException("a non-negative numerator and a positive denominator");
    }
    return numerator.multiply(TWO).add(denominator).divide(denominator.multiply(TWO));
  }

  /**
   * The contractual gross of a month in minor units.
   *
   * @param method proration method
   * @param monthlyMinor monthly amount in minor units
   * @param eligibleDays eligible days, 1 to the days of the month
   * @param daysInMonth days of the month
   * @return the gross: exactly the monthly amount for a full month (no division), otherwise
   *     prorated once and rounded HALF_UP
   * @throws IllegalArgumentException for {@code NONE} with a partial month
   */
  public static long gross(
      PayrollProration method, long monthlyMinor, int eligibleDays, int daysInMonth) {
    if (eligibleDays < 1 || eligibleDays > daysInMonth) {
      throw new IllegalArgumentException("eligible days outside the month");
    }
    if (eligibleDays == daysInMonth) {
      return monthlyMinor;
    }
    BigInteger monthly = BigInteger.valueOf(monthlyMinor);
    return switch (method) {
      case CALENDAR_DAYS ->
          roundHalfUp(
                  monthly.multiply(BigInteger.valueOf(eligibleDays)),
                  BigInteger.valueOf(daysInMonth))
              .longValueExact();
      case FIXED_30_DAYS ->
          roundHalfUp(
                  monthly.multiply(BigInteger.valueOf(Math.min(eligibleDays, 30))),
                  BigInteger.valueOf(30))
              .longValueExact();
      case NONE -> throw new IllegalArgumentException("a partial month needs a proration method");
    };
  }

  // ------------------------------------------------------------------------------------------
  // Calculation
  // ------------------------------------------------------------------------------------------

  /**
   * Calculates one employee.
   *
   * @param context the run
   * @param employee the employee with every source record of the month
   * @return the line
   */
  public static Line calculate(Context context, Employee employee) {
    PayrollPeriod period = context.period();
    int dim = period.days();
    long month = (1L << dim) - 1;

    List<Long> employmentMasks = new ArrayList<>();
    long employed = 0;
    for (Employment employment : employee.employments()) {
      long mask = mask(period, employment.start(), employment.end());
      employmentMasks.add(mask);
      employed |= mask;
    }
    List<Long> placementMasks = new ArrayList<>();
    long placedHere = 0;
    long placedElsewhere = 0;
    for (Placement placement : employee.placements()) {
      long mask = mask(period, placement.start(), placement.end());
      placementMasks.add(mask);
      if (placement.legalEntityId().equals(context.legalEntityId())) {
        placedHere |= mask;
      } else {
        placedElsewhere |= mask;
      }
    }
    List<Long> factMasks = new ArrayList<>();
    long coveredAny = 0;
    long coveredRun = 0;
    for (Fact fact : employee.facts()) {
      long mask = mask(period, fact.from(), fact.to());
      factMasks.add(mask);
      coveredAny |= mask;
      if (fact.currency() == context.currency()) {
        coveredRun |= mask;
      }
    }

    long here = employed & placedHere;
    long eligible = here & coveredRun;
    Set<PayrollBlocker> blockers = EnumSet.noneOf(PayrollBlocker.class);
    if ((here & ~coveredAny) != 0) {
      blockers.add(PayrollBlocker.COMPENSATION_MISSING);
    }
    if ((here & coveredAny & ~coveredRun) != 0) {
      blockers.add(PayrollBlocker.CURRENCY_MISMATCH);
    }
    if ((employed & placedElsewhere) != 0) {
      blockers.add(PayrollBlocker.FOREIGN_PLACEMENT);
    }
    if (overlap(employmentMasks) || overlap(placementMasks) || overlap(factMasks)) {
      blockers.add(PayrollBlocker.OVERLAPPING_EVIDENCE);
    }
    Set<Long> amounts = new TreeSet<>();
    for (Fact fact : employee.facts()) {
      if (fact.currency() == context.currency()
          && (mask(period, fact.from(), fact.to()) & eligible) != 0) {
        amounts.add(fact.minor());
      }
    }
    if (amounts.size() > 1) {
      blockers.add(PayrollBlocker.MULTIPLE_MONTHLY_AMOUNTS);
    }
    int days = Long.bitCount(eligible);
    boolean full = eligible == month;
    if (context.method() == PayrollProration.NONE && days > 0 && !full) {
      blockers.add(PayrollBlocker.PARTIAL_MONTH_NOT_ALLOWED);
    }

    List<Range> ranges = ranges(period, eligible);
    List<Source> sources = sources(period, employee, context, eligible);
    if (sources.size() > MAX_SOURCES || ranges.size() > 16) {
      blockers.add(PayrollBlocker.OVERLAPPING_EVIDENCE);
    }
    List<PayrollBlocker> ordered = new ArrayList<>(blockers);
    if (!ordered.isEmpty() || days == 0) {
      return new Line(
          employee.id(),
          employee.number(),
          days,
          ranges,
          null,
          false,
          null,
          null,
          List.of(),
          ordered,
          List.of());
    }

    long monthly = amounts.iterator().next();
    long gross = gross(context.method(), monthly, days, dim);
    Integer denominator = null;
    if (!full) {
      denominator = context.method() == PayrollProration.FIXED_30_DAYS ? 30 : dim;
    }
    List<PayrollExplanation> explanations = new ArrayList<>();
    if (full) {
      explanations.add(PayrollExplanation.FULL_MONTH);
    } else if (context.method() == PayrollProration.CALENDAR_DAYS) {
      explanations.add(PayrollExplanation.PRORATED_CALENDAR_DAYS);
    } else {
      explanations.add(PayrollExplanation.PRORATED_FIXED_30_DAYS);
      if (days >= 30) {
        explanations.add(PayrollExplanation.CAPPED_AT_FULL_MONTH);
      }
    }
    LocalDate first = period.first();
    LocalDate last = period.last();
    boolean started = false;
    boolean ended = false;
    for (Employment employment : employee.employments()) {
      if ((mask(period, employment.start(), employment.end()) & eligible) == 0) {
        continue;
      }
      if (employment.start().isAfter(first) && !employment.start().isAfter(last)) {
        started = true;
      }
      if (employment.end() != null
          && !employment.end().isBefore(first)
          && employment.end().isBefore(last)) {
        ended = true;
      }
    }
    if (started) {
      explanations.add(PayrollExplanation.STARTED_IN_MONTH);
    }
    if (ended) {
      explanations.add(PayrollExplanation.ENDED_IN_MONTH);
    }
    if (ranges.size() > 1) {
      explanations.add(PayrollExplanation.MULTIPLE_PERIODS);
    }
    return new Line(
        employee.id(),
        employee.number(),
        days,
        ranges,
        denominator,
        full,
        monthly,
        gross,
        explanations,
        ordered,
        sources);
  }

  // ------------------------------------------------------------------------------------------
  // Day sets
  // ------------------------------------------------------------------------------------------

  /** The days of the month covered by an inclusive interval, as bits 0 to 30. */
  static long mask(PayrollPeriod period, LocalDate from, LocalDate to) {
    LocalDate first = period.first();
    LocalDate last = period.last();
    LocalDate start = from.isAfter(first) ? from : first;
    LocalDate end = to == null || to.isAfter(last) ? last : to;
    if (start.isAfter(end)) {
      return 0L;
    }
    long bits = 0L;
    for (int day = start.getDayOfMonth(); day <= end.getDayOfMonth(); day++) {
      bits |= 1L << (day - 1);
    }
    return bits;
  }

  private static boolean overlap(List<Long> masks) {
    long seen = 0L;
    for (long mask : masks) {
      if ((seen & mask) != 0) {
        return true;
      }
      seen |= mask;
    }
    return false;
  }

  private static List<Range> ranges(PayrollPeriod period, long eligible) {
    List<Range> ranges = new ArrayList<>();
    int start = -1;
    for (int day = 0; day <= period.days(); day++) {
      boolean in = day < period.days() && (eligible & (1L << day)) != 0;
      if (in && start < 0) {
        start = day;
      } else if (!in && start >= 0) {
        ranges.add(new Range(period.first().plusDays(start), period.first().plusDays(day - 1L)));
        start = -1;
      }
    }
    return ranges;
  }

  private static List<Source> sources(
      PayrollPeriod period, Employee employee, Context context, long eligible) {
    List<Source> sources = new ArrayList<>();
    for (Employment employment : employee.employments()) {
      if ((mask(period, employment.start(), employment.end()) & eligible) != 0) {
        sources.add(
            new Source(
                SourceKind.EMPLOYMENT,
                employment.id(),
                employment.version(),
                employment.start(),
                employment.end()));
      }
    }
    for (Placement placement : employee.placements()) {
      if (placement.legalEntityId().equals(context.legalEntityId())
          && (mask(period, placement.start(), placement.end()) & eligible) != 0) {
        sources.add(
            new Source(
                SourceKind.PLACEMENT, placement.id(), 0L, placement.start(), placement.end()));
      }
    }
    for (Fact fact : employee.facts()) {
      if (fact.currency() == context.currency()
          && (mask(period, fact.from(), fact.to()) & eligible) != 0) {
        sources.add(
            new Source(SourceKind.COMPENSATION, fact.id(), fact.version(), fact.from(), fact.to()));
      }
    }
    sources.sort(
        Comparator.comparing(Source::kind).thenComparing(Source::from).thenComparing(Source::id));
    return sources;
  }
}
