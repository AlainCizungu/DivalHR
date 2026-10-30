package com.divalhr.core.payroll.domain;

import com.divalhr.core.payroll.domain.StatutoryRulesException.Reason;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The typed, validated rules of a statutory rule pack (MVP-094B, ADR 0034 section 3, schema
 * version 1). The rules are data: components with a party, a category, an order, a requirement on
 * the employee's profile, a base, a method (a flat rate or a progressive schedule with an optional
 * cap) and a rounding. Nothing is executed; {@link StatutoryEngine} interprets the data.
 *
 * <p>Every constructor validates and raises {@link StatutoryRulesException} (fail closed, no input
 * echoed), so a value of this type is always a valid pack whether it came from {@link
 * #parse(String)} or from code. {@code parse} also refuses unknown fields, unknown values,
 * duplicate fields and non-integer numbers.
 *
 * @param components the 1 to 12 components, in ascending order
 */
public record StatutoryRules(List<Component> components) {

  /** The only supported schema version. */
  public static final int SCHEMA_VERSION = 1;

  /** The most components a pack may have. */
  public static final int MAX_COMPONENTS = 12;

  /** A rate or cap is a whole number of basis points (hundredths of a percent): 10000 is 100%. */
  public static final int BASIS_POINTS = 10_000;

  /** The most brackets a progressive schedule may have. */
  public static final int MAX_BRACKETS = 12;

  /** The largest annual threshold of a bracket. */
  public static final long MAX_THRESHOLD = 1_000_000_000_000_000L;

  /** The closed keys of the official sources a component can cite. */
  public static final Set<String> SOURCE_KEYS =
      Set.of(
          "DGI_IRPP",
          "DGI_NOTICE_2026",
          "DGI_PORTAL",
          "CNSS_RATES",
          "CNSS_DECLARATION",
          "BUDGET_2026");

  private static final int MAX_ORDER = 1000;
  private static final Pattern ID = Pattern.compile("[A-Z_]{3,48}");
  private static final Set<String> TOP_KEYS = Set.of("schemaVersion", "components");
  private static final Set<String> COMPONENT_KEYS =
      Set.of(
          "id",
          "party",
          "category",
          "order",
          "requires",
          "base",
          "sourceKey",
          "method",
          "rounding");
  private static final Set<String> RATE_KEYS = Set.of("type", "rateBp");
  private static final Set<String> PROGRESSIVE_KEYS =
      Set.of("type", "periodsPerYear", "brackets");
  private static final Set<String> BRACKET_KEYS = Set.of("upToAnnual", "rateBp");

  /** Who bears a component: it is deducted from the employee, or added to the employer's cost. */
  public enum Party {
    /** Deducted from the employee's gross. */
    EMPLOYEE,
    /** Borne by the employer on top of the gross; never deducted from the employee. */
    EMPLOYER
  }

  /** What a component is. */
  public enum Category {
    /** A social-security contribution. */
    SOCIAL_CONTRIBUTION,
    /** An income-tax withholding. */
    INCOME_TAX
  }

  /** The profile flag that makes a component apply. */
  public enum Requirement {
    /** The employee is subject to CNSS. */
    CNSS_SUBJECT,
    /** The employee is subject to the IRPP salary withholding. */
    IRPP_SUBJECT
  }

  /** The monthly base a component is computed on. */
  public enum Base {
    /** The contractual gross. */
    GROSS,
    /** The gross minus the amounts of the employee social contributions of a lower order. */
    GROSS_LESS_EMPLOYEE_SOCIAL
  }

  /** How an exact quotient is rounded to a whole franc, once. */
  public enum Rounding {
    /** Halves round up. */
    HALF_UP,
    /** Towards zero (floor, amounts are never negative). */
    DOWN,
    /** Away from zero (ceiling). */
    UP
  }

  /**
   * One bracket of a progressive schedule.
   *
   * @param upToAnnual the inclusive annual upper bound of the bracket in whole francs, or {@code
   *     null} for the open last bracket
   * @param rateBp the rate in basis points, 0 to 10000
   */
  public record Bracket(Long upToAnnual, int rateBp) {

    /** Requires a positive bounded threshold (or none) and a rate of 0 to 10000. */
    public Bracket {
      if (upToAnnual != null && (upToAnnual < 1 || upToAnnual > MAX_THRESHOLD)) {
        throw new StatutoryRulesException(Reason.INVALID_BRACKETS);
      }
      if (rateBp < 0 || rateBp > BASIS_POINTS) {
        throw new StatutoryRulesException(Reason.INVALID_RATE);
      }
    }
  }

  /** How the amount of a component is computed. */
  public sealed interface Method permits Rate, Progressive {}

  /**
   * A flat rate: {@code round(base * rateBp / 10000)}.
   *
   * @param rateBp the rate in basis points, 0 to 10000
   */
  public record Rate(int rateBp) implements Method {

    /** Requires a rate of 0 to 10000. */
    public Rate {
      if (rateBp < 0 || rateBp > BASIS_POINTS) {
        throw new StatutoryRulesException(Reason.INVALID_RATE);
      }
    }
  }

  /**
   * A progressive schedule over the annualized base with an optional cap on the monthly result.
   *
   * @param periodsPerYear 1 or 12: the monthly base is multiplied by it, the tax divided by it
   * @param capBp the cap as basis points of the monthly base, 0 to 10000, or {@code null} for none
   * @param brackets the 1 to 12 brackets, ascending, the last one open
   */
  public record Progressive(int periodsPerYear, Integer capBp, List<Bracket> brackets)
      implements Method {

    /** Requires 1 or 12 periods, a cap of 0 to 10000 (or none) and ascending brackets. */
    public Progressive {
      if (periodsPerYear != 1 && periodsPerYear != 12) {
        throw new StatutoryRulesException(Reason.INVALID_PERIODS);
      }
      if (capBp != null && (capBp < 0 || capBp > BASIS_POINTS)) {
        throw new StatutoryRulesException(Reason.INVALID_CAP);
      }
      Objects.requireNonNull(brackets, "brackets");
      brackets = List.copyOf(brackets);
      if (brackets.isEmpty() || brackets.size() > MAX_BRACKETS) {
        throw new StatutoryRulesException(Reason.INVALID_BRACKETS);
      }
      long previous = 0;
      for (int i = 0; i < brackets.size(); i++) {
        Long upTo = brackets.get(i).upToAnnual();
        boolean last = i == brackets.size() - 1;
        if (last != (upTo == null) || (upTo != null && upTo <= previous)) {
          throw new StatutoryRulesException(Reason.INVALID_BRACKETS);
        }
        if (upTo != null) {
          previous = upTo;
        }
      }
    }
  }

  /**
   * One component of the pack.
   *
   * @param id the stable id, {@code [A-Z_]{3,48}}
   * @param party who bears it
   * @param category what it is
   * @param order the position, 1 to 1000, unique and ascending in the pack
   * @param requires the profile flag that makes it apply
   * @param base the monthly base
   * @param sourceKey the official source it cites, one of {@link #SOURCE_KEYS}
   * @param method how the amount is computed
   * @param rounding how the exact amount is rounded, once
   */
  public record Component(
      String id,
      Party party,
      Category category,
      int order,
      Requirement requires,
      Base base,
      String sourceKey,
      Method method,
      Rounding rounding) {

    /** Requires every component to be present and valid. */
    public Component {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(party, "party");
      Objects.requireNonNull(category, "category");
      Objects.requireNonNull(requires, "requires");
      Objects.requireNonNull(base, "base");
      Objects.requireNonNull(sourceKey, "sourceKey");
      Objects.requireNonNull(method, "method");
      Objects.requireNonNull(rounding, "rounding");
      if (!ID.matcher(id).matches()) {
        throw new StatutoryRulesException(Reason.INVALID_COMPONENT_ID);
      }
      if (order < 1 || order > MAX_ORDER) {
        throw new StatutoryRulesException(Reason.INVALID_ORDER);
      }
      if (!SOURCE_KEYS.contains(sourceKey)) {
        throw new StatutoryRulesException(Reason.UNKNOWN_SOURCE_KEY);
      }
    }
  }

  /**
   * Validates the pack as a whole: 1 to 12 components, unique ids, unique ascending orders, and a
   * base {@code GROSS_LESS_EMPLOYEE_SOCIAL} only where an employee social contribution of a lower
   * order exists.
   */
  public StatutoryRules {
    Objects.requireNonNull(components, "components");
    components = List.copyOf(components);
    if (components.isEmpty() || components.size() > MAX_COMPONENTS) {
      throw new StatutoryRulesException(Reason.INVALID_COMPONENT_COUNT);
    }
    Set<String> ids = new HashSet<>();
    int previousOrder = 0;
    boolean employeeSocialBefore = false;
    for (Component component : components) {
      if (!ids.add(component.id())) {
        throw new StatutoryRulesException(Reason.DUPLICATE_COMPONENT_ID);
      }
      if (component.order() <= previousOrder) {
        throw new StatutoryRulesException(Reason.INVALID_ORDER);
      }
      previousOrder = component.order();
      if (component.base() == Base.GROSS_LESS_EMPLOYEE_SOCIAL && !employeeSocialBefore) {
        throw new StatutoryRulesException(Reason.INVALID_BASE_DEPENDENCY);
      }
      if (component.party() == Party.EMPLOYEE
          && component.category() == Category.SOCIAL_CONTRIBUTION) {
        employeeSocialBefore = true;
      }
    }
  }

  /**
   * The source keys the components cite.
   *
   * @return the distinct keys
   */
  public Set<String> citedSourceKeys() {
    Set<String> keys = new LinkedHashSet<>();
    for (Component component : components) {
      keys.add(component.sourceKey());
    }
    return Set.copyOf(keys);
  }

  /**
   * Requires every cited source to be one of the pack's sources.
   *
   * @param availableSourceKeys the keys of the pack's sources
   * @throws StatutoryRulesException {@code UNKNOWN_SOURCE_KEY} when a component cites a source the
   *     pack lacks
   */
  public void requireSources(Set<String> availableSourceKeys) {
    if (!availableSourceKeys.containsAll(citedSourceKeys())) {
      throw new StatutoryRulesException(Reason.UNKNOWN_SOURCE_KEY);
    }
  }

  /**
   * Parses and validates a rule pack document. Fail closed: an unknown or duplicate field, an
   * unknown value, a non-integer number, a missing field or any violation of ADR 0034 section 3
   * raises {@link StatutoryRulesException}; the exception never contains the input.
   *
   * @param json the {@code rules} document of a pack
   * @return the validated rules
   * @throws StatutoryRulesException when the document is not acceptable
   */
  public static StatutoryRules parse(String json) {
    Map<String, Object> top = object(StatutoryJson.parse(json));
    requireKeys(top, TOP_KEYS);
    if (!(top.get("schemaVersion") instanceof Long version)) {
      throw new StatutoryRulesException(Reason.WRONG_TYPE);
    }
    if (version.longValue() != SCHEMA_VERSION) {
      throw new StatutoryRulesException(Reason.UNSUPPORTED_SCHEMA_VERSION);
    }
    if (!(top.get("components") instanceof List<?> nodes)) {
      throw new StatutoryRulesException(Reason.WRONG_TYPE);
    }
    if (nodes.isEmpty() || nodes.size() > MAX_COMPONENTS) {
      throw new StatutoryRulesException(Reason.INVALID_COMPONENT_COUNT);
    }
    List<Component> parsed = new ArrayList<>();
    for (Object node : nodes) {
      parsed.add(component(node));
    }
    return new StatutoryRules(parsed);
  }

  private static Component component(Object node) {
    Map<String, Object> members = object(node);
    requireKeys(members, COMPONENT_KEYS);
    return new Component(
        text(members.get("id")),
        symbol(Party.class, members.get("party")),
        symbol(Category.class, members.get("category")),
        integer(members.get("order")),
        symbol(Requirement.class, members.get("requires")),
        symbol(Base.class, members.get("base")),
        text(members.get("sourceKey")),
        method(members.get("method")),
        symbol(Rounding.class, members.get("rounding")));
  }

  private static Method method(Object node) {
    Map<String, Object> members = object(node);
    if (!members.containsKey("type")) {
      throw new StatutoryRulesException(Reason.MISSING_FIELD);
    }
    String type = text(members.get("type"));
    if ("RATE".equals(type)) {
      requireKeys(members, RATE_KEYS);
      return new Rate(integer(members.get("rateBp")));
    }
    if ("PROGRESSIVE".equals(type)) {
      Set<String> allowed = new HashSet<>(PROGRESSIVE_KEYS);
      allowed.add("capBp");
      requireKeys(members, PROGRESSIVE_KEYS, allowed);
      Integer cap = null;
      if (members.containsKey("capBp")) {
        cap = integer(members.get("capBp"));
      }
      if (!(members.get("brackets") instanceof List<?> nodes)) {
        throw new StatutoryRulesException(Reason.WRONG_TYPE);
      }
      if (nodes.isEmpty() || nodes.size() > MAX_BRACKETS) {
        throw new StatutoryRulesException(Reason.INVALID_BRACKETS);
      }
      List<Bracket> brackets = new ArrayList<>();
      for (Object bracket : nodes) {
        brackets.add(bracket(bracket));
      }
      return new Progressive(integer(members.get("periodsPerYear")), cap, brackets);
    }
    throw new StatutoryRulesException(Reason.UNKNOWN_VALUE);
  }

  private static Bracket bracket(Object node) {
    Map<String, Object> members = object(node);
    requireKeys(members, BRACKET_KEYS);
    Object upTo = members.get("upToAnnual");
    Long threshold = null;
    if (upTo != null) {
      if (!(upTo instanceof Long value)) {
        throw new StatutoryRulesException(Reason.WRONG_TYPE);
      }
      threshold = value;
    }
    return new Bracket(threshold, integer(members.get("rateBp")));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object node) {
    if (!(node instanceof Map<?, ?>)) {
      throw new StatutoryRulesException(Reason.WRONG_TYPE);
    }
    return (Map<String, Object>) node;
  }

  private static void requireKeys(Map<String, Object> members, Set<String> required) {
    requireKeys(members, required, required);
  }

  private static void requireKeys(
      Map<String, Object> members, Set<String> required, Set<String> allowed) {
    for (String key : members.keySet()) {
      if (!allowed.contains(key)) {
        throw new StatutoryRulesException(Reason.UNKNOWN_FIELD);
      }
    }
    for (String key : required) {
      if (!members.containsKey(key)) {
        throw new StatutoryRulesException(Reason.MISSING_FIELD);
      }
    }
  }

  private static String text(Object node) {
    if (!(node instanceof String value)) {
      throw new StatutoryRulesException(Reason.WRONG_TYPE);
    }
    return value;
  }

  private static int integer(Object node) {
    if (!(node instanceof Long value)) {
      throw new StatutoryRulesException(Reason.WRONG_TYPE);
    }
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new StatutoryRulesException(Reason.INVALID_NUMBER);
    }
    return (int) value.longValue();
  }

  private static <E extends Enum<E>> E symbol(Class<E> type, Object node) {
    String name = text(node);
    for (E constant : type.getEnumConstants()) {
      if (constant.name().equals(name)) {
        return constant;
      }
    }
    throw new StatutoryRulesException(Reason.UNKNOWN_VALUE);
  }
}
