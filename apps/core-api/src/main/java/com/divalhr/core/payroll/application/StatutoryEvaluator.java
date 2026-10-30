package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PayrollPeriod;
import com.divalhr.core.payroll.domain.StatutoryCalculator;
import com.divalhr.core.payroll.domain.StatutoryCalculator.PackStatus;
import com.divalhr.core.payroll.domain.StatutoryCalculator.Result;
import com.divalhr.core.payroll.domain.StatutoryCalculator.SourceLine;
import com.divalhr.core.payroll.domain.StatutoryProfile;
import com.divalhr.core.payroll.domain.StatutoryRules;
import com.divalhr.core.payroll.domain.StatutoryRulesException;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.RunRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.ActivationRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.CalculationRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.SourceLineRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.PackRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.PackWithRules;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.SourceRow;
import com.divalhr.core.platform.access.StatutoryEarningsGuard;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads everything a statutory calculation depends on and calculates it (MVP-094B, ADR 0034
 * section 6): the frozen source run and its lines, the activation and pack in effect for the month,
 * the employees' profiles and the unmodeled earnings, then applies {@link StatutoryCalculator} and
 * computes the three digests. It is shared by the preview (a consistent best effort in a read
 * transaction) and by the commit (under the locks of ADR 0034 section 7, where the result is
 * stable). Restricted financial and HR data: nothing here is logged.
 */
@Component
public class StatutoryEvaluator {

  /** The most source lines a statutory calculation accepts. */
  public static final int MAX_LINES = 5_000;

  private final JdbcStatutoryRepository statutory;
  private final JdbcStatutoryRulePackRepository packs;
  private final StatutoryEarningsGuard earnings;

  /**
   * Creates the evaluator.
   *
   * @param statutory tenant-owned statutory storage
   * @param packs platform-owned rule packs
   * @param earnings the port that reports earnings the preview does not model
   */
  public StatutoryEvaluator(
      JdbcStatutoryRepository statutory,
      JdbcStatutoryRulePackRepository packs,
      StatutoryEarningsGuard earnings) {
    this.statutory = statutory;
    this.packs = packs;
    this.earnings = earnings;
  }

  /**
   * The activation in effect with its pack and parsed rules.
   *
   * @param activation the activation
   * @param pack the pack
   * @param sources the pack's official sources
   * @param rules the parsed rules, or {@code null} when the pack does not parse (never applicable)
   */
  record Resolved(
      ActivationRow activation, PackRow pack, List<SourceRow> sources, StatutoryRules rules) {

    @Override
    public String toString() {
      return "StatutoryResolved[" + pack.id() + "]";
    }
  }

  /**
   * Everything one evaluation read and decided.
   *
   * @param run the source run
   * @param resolved the activation and pack in effect, or {@code null}
   * @param lines the frozen source lines
   * @param result the calculation
   * @param sourceDigest digest of the source run
   * @param profileDigest digest of the profiles
   * @param packDigest digest of the activation and pack, or {@code null}
   * @param chainVersion the highest version of the chain of the run and pack, 0 when none
   * @param draft the current draft of that chain, or {@code null}
   * @param unchanged whether the result is committable and equals the current draft
   */
  record Evaluation(
      RunRow run,
      Resolved resolved,
      List<SourceLineRow> lines,
      Result result,
      String sourceDigest,
      String profileDigest,
      String packDigest,
      int chainVersion,
      CalculationRow draft,
      boolean unchanged) {

    Evaluation {
      lines = List.copyOf(lines);
    }

    boolean current() {
      return "DRAFT".equals(run.status());
    }

    @Override
    public String toString() {
      return "StatutoryEvaluation[" + run.id() + "]";
    }
  }

  /**
   * The activation in effect for a month and its pack, if any.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param month first day of the month
   * @return the resolved pack, or empty when no activation is in effect
   */
  Optional<Resolved> resolve(TenantId tenant, UUID legalEntityId, LocalDate month) {
    Optional<ActivationRow> activation = statutory.activationInEffect(tenant, legalEntityId, month);
    if (activation.isEmpty()) {
      return Optional.empty();
    }
    return resolve(activation.get());
  }

  /**
   * A given activation's pack with its sources and parsed rules.
   *
   * @param activation the activation
   * @return the resolved pack, or empty when the pack row is missing (cannot happen: foreign key)
   */
  Optional<Resolved> resolve(ActivationRow activation) {
    Optional<PackWithRules> found = packs.packWithRules(activation.packId());
    if (found.isEmpty()) {
      return Optional.empty();
    }
    List<SourceRow> sources = packs.sources(activation.packId());
    return Optional.of(
        new Resolved(activation, found.get().row(), sources, rules(found.get().rules(), sources)));
  }

  /**
   * Parses and validates a pack's rules against its sources. Fail closed.
   *
   * @param json the rules document
   * @param sources the pack's sources
   * @return the rules, or {@code null} when the pack does not parse or cites a missing source
   */
  static StatutoryRules rules(String json, List<SourceRow> sources) {
    try {
      StatutoryRules rules = StatutoryRules.parse(json);
      Set<String> keys = new HashSet<>();
      for (SourceRow source : sources) {
        keys.add(source.key());
      }
      rules.requireSources(keys);
      return rules;
    } catch (StatutoryRulesException invalid) {
      return null;
    }
  }

  /**
   * Whether the pack's interval covers every day of the month.
   *
   * @param pack the pack
   * @param month first day of the month
   * @return true when it does
   */
  static boolean covers(PackRow pack, LocalDate month) {
    LocalDate last = month.plusMonths(1).minusDays(1);
    return !pack.effectiveFrom().isAfter(month)
        && (pack.effectiveTo() == null || !pack.effectiveTo().isBefore(last));
  }

  /**
   * Whether the pack in effect can produce amounts for the month: VERIFIED, parsed and covering
   * the month.
   *
   * @param resolved the pack in effect
   * @param month first day of the month
   * @return true when applicable
   */
  static boolean usable(Resolved resolved, LocalDate month) {
    return "VERIFIED".equals(resolved.pack().status())
        && resolved.rules() != null
        && covers(resolved.pack(), month);
  }

  /**
   * Evaluates a statutory calculation of a source run.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity of the run
   * @param run the source run
   * @return the evaluation
   * @throws ApiException {@code STATUTORY_LIMIT_REACHED} when the run has more than 5,000 lines
   */
  Evaluation evaluate(TenantId tenant, UUID legalEntityId, RunRow run) {
    PayrollPeriod period = PayrollPeriod.parse(run.periodStart().toString().substring(0, 7)).get();
    LocalDate first = period.first();
    LocalDate last = period.last();
    List<SourceLineRow> lines = statutory.sourceLines(tenant, run.id(), MAX_LINES + 1);
    if (lines.size() > MAX_LINES) {
      throw new ApiException(ErrorCode.STATUTORY_LIMIT_REACHED, Map.of());
    }
    Resolved resolved = resolve(tenant, legalEntityId, first).orElse(null);
    List<UUID> employeeIds = new ArrayList<>();
    List<SourceLine> sources = new ArrayList<>();
    for (SourceLineRow line : lines) {
      employeeIds.add(line.employeeId());
      sources.add(
          new SourceLine(
              line.id(),
              line.employeeId(),
              line.employeeNumber(),
              line.grossMinor(),
              line.firstDay() == null ? first : line.firstDay(),
              line.lastDay() == null ? last : line.lastDay()));
    }
    Map<UUID, List<StatutoryProfile>> profiles = statutory.profilesOf(tenant, employeeIds);
    Set<UUID> unmodeled =
        earnings.employeesWithUnmodeledEarnings(tenant, List.copyOf(employeeIds), first, last);
    StatutoryCalculator.Pack pack =
        resolved == null
            ? null
            : new StatutoryCalculator.Pack(
                PackStatus.valueOf(resolved.pack().status()),
                resolved.pack().effectiveFrom(),
                resolved.pack().effectiveTo(),
                resolved.rules());
    Result result =
        StatutoryCalculator.calculate(
            pack,
            new StatutoryCalculator.Run(
                "DRAFT".equals(run.status()), run.currency().name(), run.periodStart()),
            sources,
            profiles,
            unmodeled);
    String sourceDigest = StatutoryDigests.sourceRun(run, lines);
    String profileDigest = StatutoryDigests.profiles(employeeIds, profiles);
    String packDigest =
        resolved == null ? null : StatutoryDigests.rulePack(resolved.activation(), resolved.pack());
    int chainVersion = 0;
    CalculationRow draft = null;
    if (resolved != null) {
      chainVersion = statutory.chainVersion(tenant, run.id(), resolved.pack().id());
      draft = statutory.draft(tenant, run.id(), resolved.pack().id()).orElse(null);
    }
    boolean unchanged =
        draft != null
            && result.committable()
            && draft.sourceRunDigest().equals(sourceDigest)
            && draft.profileDigest().equals(profileDigest)
            && draft.rulePackDigest().equals(packDigest);
    return new Evaluation(
        run,
        resolved,
        lines,
        result,
        sourceDigest,
        profileDigest,
        packDigest,
        chainVersion,
        draft,
        unchanged);
  }
}
