package com.divalhr.core.payroll.domain;

import com.divalhr.core.payroll.domain.CompensationDates.Span;
import com.divalhr.core.payroll.domain.CompensationTimeline.Fact;
import com.divalhr.core.payroll.domain.CompensationTimeline.Plan;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Evaluates one compensation command against the employee's state (MVP-093A, D93-5 to D93-10): the
 * amount range, the date window and reach, the employment containment, the timeline transition and
 * the limits. The same evaluation serves the preview (blockers are an answer) and the commit
 * (blockers are problem codes). A command with a blocker has no plan. Pure rules, no I/O.
 */
public final class CompensationEvaluator {

  /** At most this many changes are recorded per employee. */
  public static final int MAX_CHANGES = 100;

  /** At most this many intervals may start after today. */
  public static final int MAX_SCHEDULED = 10;

  private CompensationEvaluator() {}

  /**
   * A well-formed command.
   *
   * @param action {@code ESTABLISHED}, {@code CHANGED} or {@code CORRECTED}
   * @param effectiveFrom the first day of the value
   * @param amount the parsed amount (never {@code MALFORMED}: that is a request error)
   * @param target the origin change of the fact to correct, or {@code null}
   */
  public record Command(
      CompensationAction action,
      LocalDate effectiveFrom,
      CompensationMoney.Parsed amount,
      UUID target) {

    /** Requires the parts of a command. */
    public Command {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(effectiveFrom, "effectiveFrom");
      Objects.requireNonNull(amount, "amount");
      if (!action.isCommand()) {
        throw new IllegalArgumentException("a cancellation is not a command");
      }
      if (amount.status() == CompensationMoney.Status.MALFORMED) {
        throw new IllegalArgumentException("a malformed amount is a request error");
      }
      if ((action == CompensationAction.CORRECTED) != (target != null)) {
        throw new IllegalArgumentException("a correction names its target, others do not");
      }
    }
  }

  /**
   * The employee's state.
   *
   * @param today business date in the organization's time zone
   * @param employments the employee's employment intervals
   * @param active the active compensation facts
   * @param version the compensation version (the last change sequence, 0 when none)
   */
  public record Context(LocalDate today, List<Span> employments, List<Fact> active, int version) {

    /** Copies the lists. */
    public Context {
      Objects.requireNonNull(today, "today");
      employments = List.copyOf(employments);
      active = List.copyOf(active);
    }
  }

  /**
   * The result of an evaluation.
   *
   * @param blockers the blockers, in the contract's order (empty when the command can be committed)
   * @param warnings the notices
   * @param plan what the command would do; {@code null} when there is a blocker
   * @param retroactive whether the effective date is before today
   * @param requiresAcknowledgement whether the commit must acknowledge the retroactive effect
   */
  public record Evaluation(
      List<CompensationBlocker> blockers,
      List<CompensationWarning> warnings,
      Plan plan,
      boolean retroactive,
      boolean requiresAcknowledgement) {

    /** Copies the lists. */
    public Evaluation {
      blockers = List.copyOf(blockers);
      warnings = List.copyOf(warnings);
    }

    /**
     * Whether the command can be committed.
     *
     * @return true without a blocker
     */
    public boolean committable() {
      return blockers.isEmpty();
    }
  }

  /**
   * Evaluates a command.
   *
   * @param command the command
   * @param context the employee's state
   * @param changeId the change that would be recorded
   * @return the evaluation
   */
  public static Evaluation evaluate(Command command, Context context, UUID changeId) {
    LocalDate today = context.today();
    LocalDate from = command.effectiveFrom();
    Set<CompensationBlocker> blockers = EnumSet.noneOf(CompensationBlocker.class);
    boolean retroactive = CompensationDates.retroactive(from, today);
    boolean acknowledge = retroactive || command.action() == CompensationAction.CORRECTED;
    CompensationMoney money = command.amount().money();
    if (money == null) {
      blockers.add(CompensationBlocker.AMOUNT_OUT_OF_RANGE);
    }
    boolean dateOk = CompensationDates.withinWindow(from, today);
    if (command.action() == CompensationAction.CORRECTED) {
      Fact target = find(context.active(), command.target());
      if (target != null) {
        dateOk =
            dateOk && CompensationDates.reachWithinWindow(target.interval().from(), from, today);
      }
    }
    if (!dateOk) {
      blockers.add(CompensationBlocker.DATE_OUT_OF_RANGE);
    }
    if (!CompensationDates.withinEmployment(from, context.employments())) {
      blockers.add(CompensationBlocker.EMPLOYMENT_CONFLICT);
    }
    Plan plan = null;
    if (money != null) {
      try {
        plan =
            switch (command.action()) {
              case ESTABLISHED ->
                  CompensationTimeline.establish(context.active(), changeId, money, from);
              case CHANGED -> CompensationTimeline.change(context.active(), changeId, money, from);
              case CORRECTED ->
                  CompensationTimeline.correct(
                      context.active(), changeId, command.target(), money, from);
              case SCHEDULED_CHANGE_CANCELLED -> throw new IllegalStateException("not a command");
            };
      } catch (CompensationRuleException refused) {
        blockers.add(refused.blocker());
      }
    }
    if (context.version() >= MAX_CHANGES
        || (plan != null && plan.scheduledAfter(today) > MAX_SCHEDULED)) {
      blockers.add(CompensationBlocker.LIMIT_REACHED);
    }
    List<CompensationWarning> warnings = warnings(plan, changeId, today, retroactive);
    Plan result = blockers.isEmpty() ? plan : null;
    return new Evaluation(List.copyOf(blockers), warnings, result, retroactive, acknowledge);
  }

  /**
   * Evaluates the cancellation of a scheduled change.
   *
   * @param target the origin change of the fact to cancel
   * @param targetIsChange whether that change was a {@code CHANGED}
   * @param context the employee's state
   * @return the evaluation
   */
  public static Evaluation evaluateCancellation(
      UUID target, boolean targetIsChange, Context context) {
    Set<CompensationBlocker> blockers = EnumSet.noneOf(CompensationBlocker.class);
    Plan plan = null;
    try {
      plan = CompensationTimeline.cancel(context.active(), target, targetIsChange, context.today());
    } catch (CompensationRuleException refused) {
      blockers.add(refused.blocker());
    }
    if (context.version() >= MAX_CHANGES) {
      blockers.add(CompensationBlocker.LIMIT_REACHED);
    }
    Plan result = blockers.isEmpty() ? plan : null;
    return new Evaluation(List.copyOf(blockers), List.of(), result, false, false);
  }

  private static List<CompensationWarning> warnings(
      Plan plan, UUID changeId, LocalDate today, boolean retroactive) {
    List<CompensationWarning> warnings = new java.util.ArrayList<>();
    if (retroactive) {
      warnings.add(CompensationWarning.RETROACTIVE);
    }
    if (plan != null
        && plan.after().stream()
            .anyMatch(
                interval ->
                    interval.from().isAfter(today) && !interval.origin().equals(changeId))) {
      warnings.add(CompensationWarning.LATER_CHANGE_SCHEDULED);
    }
    return warnings;
  }

  private static Fact find(List<Fact> active, UUID origin) {
    for (Fact fact : active) {
      if (fact.interval().origin().equals(origin)) {
        return fact;
      }
    }
    return null;
  }
}
