package com.divalhr.core.people.schedule.domain;

import com.divalhr.core.people.schedule.domain.OperationalStatus.Status;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Fact;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Highlight;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Limitation;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Request;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Response;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Shifts;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Waiting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MVP-101 (Issue #156, ADR 0027): the governed, closed candidate facts of the daily briefing and
 * the rules that check what comes back. Core is the authority: it builds the facts from the MVP-100
 * status, and it accepts a briefing only when every highlight is bound to a supplied fact, carries
 * that fact's type, severity and source route, states only that fact's own numbers, and the
 * non-zero facts of priorities 1 to 3 (not checked in, uncovered, overdue approvals) are selected
 * first. The same rules, in the same order, are applied by the AI service ({@code
 * apps/ai-service/.../summary/policy.py}); the shared evaluation set proves both.
 */
public final class OperationalSummary {

  /** Kind of an organization-wide snapshot. */
  public static final String ORGANIZATION = "ORGANIZATION";

  /** Kind of a manager's team snapshot. */
  public static final String TEAM = "TEAM";

  /** The longest text the contract accepts. */
  static final int MAX_TEXT = 400;

  /** The most highlights a briefing may carry. */
  public static final int MAX_HIGHLIGHTS = 5;

  private static final List<String> LIMITATION_CODES =
      List.of("RECORDED_FACTS_ONLY", "NO_CONCLUSION", "REVIEW_SOURCES");
  private static final Pattern VERSION = Pattern.compile("^[a-z0-9.-]{1,32}$");
  private static final Pattern NUMBER = Pattern.compile("(?<![0-9])[0-9]+(?![0-9])");
  private static final Pattern FORBIDDEN_CHARACTERS =
      Pattern.compile("[<>]|://|www\\.|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-");

  private OperationalSummary() {}

  /** The requested briefing language: exactly {@code en} or {@code fr}. */
  public enum Language {
    /** English. */
    EN("en"),
    /** French. */
    FR("fr");

    private final String wire;

    Language(String wire) {
      this.wire = wire;
    }

    /**
     * The wire value.
     *
     * @return {@code en} or {@code fr}
     */
    public String wire() {
      return wire;
    }

    /**
     * Parses the exact wire value (no case folding, no trimming).
     *
     * @param raw submitted value
     * @return the language, or empty
     */
    public static Optional<Language> parse(Object raw) {
      if (raw instanceof String text) {
        for (Language language : values()) {
          if (language.wire.equals(text)) {
            return Optional.of(language);
          }
        }
      }
      return Optional.empty();
    }
  }

  /** The closed candidate-fact types, in the canonical order. */
  public enum FactType {
    /** Shifts with no check-in after the grace. */
    NOT_CHECKED_IN(1, 30, "ATTENDANCE", true),
    /** Shifts without an assigned worker. */
    UNCOVERED_SHIFTS(2, null, "SCHEDULES", true),
    /** Leave requests waiting longer than the threshold. */
    OVERDUE_LEAVE(3, 48, "LEAVE_APPROVALS", true),
    /** Timesheets waiting longer than the threshold. */
    OVERDUE_TIMESHEETS(3, 72, "TIMESHEETS", true),
    /** Late check-ins. */
    LATE_CHECK_INS(4, 5, "ATTENDANCE", true),
    /** Leave requests pending within the threshold. */
    PENDING_LEAVE(5, 48, "LEAVE_APPROVALS", true),
    /** Timesheets pending within the threshold. */
    PENDING_TIMESHEETS(5, 72, "TIMESHEETS", true),
    /** Shifts awaiting a check-in within the grace. */
    EXPECTED_SHIFTS(6, 30, "ATTENDANCE", true),
    /** Shifts in progress. */
    PRESENT_SHIFTS(7, null, "ATTENDANCE", false),
    /** Completed shifts. */
    COMPLETED_SHIFTS(7, null, "ATTENDANCE", false),
    /** All scheduled shifts. */
    SCHEDULED_SHIFTS(7, null, "SCHEDULES", false);

    private final int priority;
    private final Integer threshold;
    private final String route;
    private final boolean banded;

    FactType(int priority, Integer threshold, String route, boolean banded) {
      this.priority = priority;
      this.threshold = threshold;
      this.route = route;
      this.banded = banded;
    }

    /**
     * The D101-9 priority (1 is the most urgent).
     *
     * @return priority
     */
    public int priority() {
      return priority;
    }

    /**
     * Whether the fact must be selected when non-zero (priorities 1 to 3).
     *
     * @return true for a mandatory type
     */
    public boolean mandatory() {
      return priority <= 3;
    }

    /**
     * The fixed threshold stated by the fact.
     *
     * @return 5, 30, 48, 72 or {@code null}
     */
    public Integer threshold() {
      return threshold;
    }

    /**
     * The allow-listed source route.
     *
     * @return route identifier
     */
    public String route() {
      return route;
    }
  }

  /** The closed severity band of a count: 0, 1 to 2, 3 to 9, 10 or more. */
  public static String band(int value) {
    if (value == 0) {
      return "NONE";
    }
    if (value <= 2) {
      return "LOW";
    }
    return value <= 9 ? "MEDIUM" : "HIGH";
  }

  /** A briefing text broke a rule; carries a bounded reason, never the text. */
  public static final class InvalidOutputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    /**
     * Creates the exception.
     *
     * @param reason bounded reason
     */
    public InvalidOutputException(String reason) {
      super(reason);
      this.reason = reason;
    }

    /**
     * The bounded reason.
     *
     * @return reason
     */
    public String reason() {
      return reason;
    }
  }

  /** The MVP-100 source breaks one of its own invariants: nothing is sent anywhere. */
  public static final class InvalidSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception (no value is ever carried). */
    public InvalidSourceException() {
      super("source");
    }
  }

  /**
   * Fails closed unless the status satisfies the MVP-100 invariants: the exclusive partition, the
   * late subset, overdue within pending, non-negative counts, and coverage present exactly for the
   * organization.
   *
   * @param status the status
   * @param team whether it is a manager's team status
   */
  public static void requireConsistent(Status status, boolean team) {
    OperationalStatus.Shifts s = status.shifts();
    int[] counts = {
      s.scheduled(),
      s.expected(),
      s.notCheckedIn(),
      s.present(),
      s.completed(),
      s.late(),
      status.leave().pending(),
      status.leave().overdue(),
      status.timesheets().pending(),
      status.timesheets().overdue()
    };
    for (int count : counts) {
      if (count < 0) {
        throw new InvalidSourceException();
      }
    }
    boolean partition =
        (long) s.expected() + s.notCheckedIn() + s.present() + s.completed() == s.scheduled();
    boolean late = s.late() <= (long) s.present() + s.completed();
    boolean overdue =
        status.leave().overdue() <= status.leave().pending()
            && status.timesheets().overdue() <= status.timesheets().pending();
    boolean coverage = team ? status.uncovered() == null : status.uncovered() != null;
    if (!partition || !late || !overdue || !coverage) {
      throw new InvalidSourceException();
    }
    if (!team && status.uncovered() < 0) {
      throw new InvalidSourceException();
    }
  }

  /**
   * Builds the closed request from the status: aggregate counts, fixed thresholds and the candidate
   * facts. The fact IDs are drawn in a random order per request so that an ID reveals nothing about
   * the fact it names. Nothing else is read from the status.
   *
   * @param status the consistent status
   * @param language the requested language
   * @param siteFilter whether a site filter applies
   * @param legalEntityFilter whether a legal-entity filter applies
   * @param random source of the ID permutation
   * @return the request
   */
  public static Request snapshot(
      Status status,
      Language language,
      boolean siteFilter,
      boolean legalEntityFilter,
      Random random) {
    boolean team = status.uncovered() == null;
    requireConsistent(status, team);
    OperationalStatus.Shifts s = status.shifts();
    Map<FactType, Integer> values = new LinkedHashMap<>();
    values.put(FactType.NOT_CHECKED_IN, s.notCheckedIn());
    if (!team) {
      values.put(FactType.UNCOVERED_SHIFTS, status.uncovered());
    }
    values.put(FactType.OVERDUE_LEAVE, status.leave().overdue());
    values.put(FactType.OVERDUE_TIMESHEETS, status.timesheets().overdue());
    values.put(FactType.LATE_CHECK_INS, s.late());
    values.put(FactType.PENDING_LEAVE, status.leave().pending() - status.leave().overdue());
    values.put(
        FactType.PENDING_TIMESHEETS, status.timesheets().pending() - status.timesheets().overdue());
    values.put(FactType.EXPECTED_SHIFTS, s.expected());
    values.put(FactType.PRESENT_SHIFTS, s.present());
    values.put(FactType.COMPLETED_SHIFTS, s.completed());
    values.put(FactType.SCHEDULED_SHIFTS, s.scheduled());
    List<FactType> included = new ArrayList<>();
    for (Map.Entry<FactType, Integer> entry : values.entrySet()) {
      if (entry.getValue() > 0 || entry.getKey() == FactType.SCHEDULED_SHIFTS) {
        included.add(entry.getKey());
      }
    }
    List<Integer> numbers = new ArrayList<>();
    for (int i = 1; i <= included.size(); i++) {
      numbers.add(i);
    }
    Collections.shuffle(numbers, random);
    List<Fact> facts = new ArrayList<>();
    for (int i = 0; i < included.size(); i++) {
      FactType type = included.get(i);
      int value = values.get(type);
      facts.add(
          new Fact(
              "f" + numbers.get(i),
              type.name(),
              value,
              type.threshold(),
              type.banded ? band(value) : "NONE",
              type.route()));
    }
    return new Request(
        language.wire(),
        status.asOf().toString(),
        team ? TEAM : ORGANIZATION,
        siteFilter,
        legalEntityFilter,
        new Shifts(
            s.scheduled(), s.expected(), s.notCheckedIn(), s.present(), s.completed(), s.late()),
        status.uncovered(),
        new Waiting(status.leave().pending(), status.leave().overdue()),
        new Waiting(status.timesheets().pending(), status.timesheets().overdue()),
        facts);
  }

  /**
   * The one headline family the facts support (D101-6).
   *
   * @param request the request
   * @return headline code
   */
  public static String expectedHeadline(Request request) {
    Map<String, Integer> values = new LinkedHashMap<>();
    request.facts().forEach(fact -> values.put(fact.type(), fact.value()));
    boolean attendance =
        positive(values, FactType.NOT_CHECKED_IN) || positive(values, FactType.LATE_CHECK_INS);
    boolean coverage = positive(values, FactType.UNCOVERED_SHIFTS);
    boolean approvals =
        positive(values, FactType.OVERDUE_LEAVE) || positive(values, FactType.OVERDUE_TIMESHEETS);
    int areas = (attendance ? 1 : 0) + (coverage ? 1 : 0) + (approvals ? 1 : 0);
    if (areas == 0) {
      return "OPERATIONS_WITHIN_RANGE";
    }
    if (areas > 1) {
      return "SEVERAL_AREAS_NEED_ATTENTION";
    }
    if (coverage) {
      return "COVERAGE_NEEDS_ATTENTION";
    }
    return approvals ? "APPROVALS_NEED_ATTENTION" : "ATTENDANCE_NEEDS_ATTENTION";
  }

  private static boolean positive(Map<String, Integer> values, FactType type) {
    return values.getOrDefault(type.name(), 0) > 0;
  }

  /**
   * Accepts the briefing only if it is grounded in the request: language, mode, headline family,
   * one to five unique supplied facts with their own type, severity and route, the mandatory facts
   * first, only each fact's own numbers in its text, closed limitations, and no prohibited
   * conclusion vocabulary or link-like text.
   *
   * @param request what was sent
   * @param response what came back
   * @throws InvalidOutputException with a bounded reason when any rule fails
   */
  public static void validate(Request request, Response response) {
    if (!request.language().equals(response.language())) {
      throw new InvalidOutputException("language");
    }
    if (!List.of("MODEL", "FALLBACK").contains(response.generationMode())) {
      throw new InvalidOutputException("mode");
    }
    if (!VERSION.matcher(response.templateVersion()).matches()) {
      throw new InvalidOutputException("version");
    }
    if (!expectedHeadline(request).equals(response.headlineCode())) {
      throw new InvalidOutputException("headline");
    }
    text(response.headlineText(), request.language());
    Map<String, Fact> byId = new LinkedHashMap<>();
    request.facts().forEach(fact -> byId.put(fact.factId(), fact));
    List<Highlight> highlights = response.highlights();
    if (highlights.isEmpty() || highlights.size() > MAX_HIGHLIGHTS) {
      throw new InvalidOutputException("selection");
    }
    Set<String> selected = new HashSet<>();
    boolean seenOther = false;
    for (Highlight highlight : highlights) {
      Fact fact = byId.get(highlight.factId());
      if (fact == null || !selected.add(highlight.factId())) {
        throw new InvalidOutputException("selection");
      }
      if (!fact.type().equals(highlight.factType())
          || !fact.severity().equals(highlight.severity())
          || !fact.routeId().equals(highlight.routeId())) {
        throw new InvalidOutputException("binding");
      }
      boolean mandatory = isMandatory(fact);
      if (mandatory && seenOther) {
        throw new InvalidOutputException("order");
      }
      seenOther = seenOther || !mandatory;
      text(highlight.text(), request.language());
      grounded(highlight.text(), fact);
    }
    for (Fact fact : request.facts()) {
      if (isMandatory(fact) && !selected.contains(fact.factId())) {
        throw new InvalidOutputException("mandatory");
      }
    }
    List<Limitation> limitations = response.limitations();
    if (limitations.size() != LIMITATION_CODES.size()) {
      throw new InvalidOutputException("limitations");
    }
    for (int i = 0; i < LIMITATION_CODES.size(); i++) {
      Limitation limitation = limitations.get(i);
      if (!LIMITATION_CODES.get(i).equals(limitation.code())
          || limitation.text().isBlank()
          || limitation.text().length() > MAX_TEXT
          || FORBIDDEN_CHARACTERS.matcher(limitation.text()).find()) {
        throw new InvalidOutputException("limitations");
      }
    }
  }

  private static boolean isMandatory(Fact fact) {
    return FactType.valueOf(fact.type()).mandatory() && fact.value() > 0;
  }

  private static void text(String text, String language) {
    if (text.isBlank() || text.length() > MAX_TEXT || FORBIDDEN_CHARACTERS.matcher(text).find()) {
      throw new InvalidOutputException("text");
    }
    String lowered = text.toLowerCase(Locale.ROOT);
    for (String stem : Prohibited.stems(language)) {
      if (lowered.contains(stem)) {
        throw new InvalidOutputException("vocabulary");
      }
    }
  }

  /** Every number in the text is the fact's own value or threshold; a non-zero value appears. */
  private static void grounded(String text, Fact fact) {
    Set<Integer> numbers = new HashSet<>();
    Matcher matcher = NUMBER.matcher(text);
    while (matcher.find()) {
      if (matcher.group().length() > 6) {
        throw new InvalidOutputException("number");
      }
      numbers.add(Integer.parseInt(matcher.group()));
    }
    Set<Integer> allowed = new HashSet<>();
    allowed.add(fact.value());
    if (fact.thresholdValue() != null) {
      allowed.add(fact.thresholdValue());
    }
    if (!allowed.containsAll(numbers)) {
      throw new InvalidOutputException("number");
    }
    if (fact.value() > 0 && !numbers.contains(fact.value())) {
      throw new InvalidOutputException("value");
    }
    if (fact.thresholdValue() != null && !numbers.contains(fact.thresholdValue())) {
      throw new InvalidOutputException("threshold");
    }
  }
}
