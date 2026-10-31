package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.ComponentFact;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.Facts;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.LineFact;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.Result;
import com.divalhr.core.payroll.domain.PayrollApprovalEvaluator.Totals;
import com.divalhr.core.payroll.domain.StatutoryProfile;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.PackageRow;
import com.divalhr.core.payroll.internal.JdbcPayrollPackageRepository.Preparers;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.RunRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.CalculationRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.ComponentRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.LineRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.SourceLineRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.PackRow;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads everything a payroll approval depends on and decides it (MVP-094C, ADR 0035 sections 6 and
 * 7): the frozen payroll run and its lines, the statutory calculation with its lines and
 * components, the rule pack, the live source, profile and rule-pack digests, a read-only payroll
 * recalculation of the month, the package chain and the creators of the two sources. It is shared
 * by the preview (a consistent best effort in a read transaction) and by the commit (under the
 * locks of ADR 0035 section 8, where the result is stable). Nothing is recalculated except for the
 * source proof; the amounts are copied from the frozen calculation. Restricted financial and HR
 * data: nothing here is logged.
 */
@Component
public class PayrollApprovalEvidence {

  /** A pack whose code starts with this is a synthetic test pack. */
  static final String SYNTHETIC_PREFIX = "SYNTHETIC-TEST";

  private final JdbcPayrollRunRepository runs;
  private final JdbcStatutoryRepository statutory;
  private final JdbcStatutoryRulePackRepository packs;
  private final JdbcPayrollPackageRepository packages;
  private final StatutoryEvaluator evaluator;
  private final PayrollRunService runService;

  /**
   * Creates the loader.
   *
   * @param runs payroll storage
   * @param statutory statutory storage
   * @param packs platform-owned rule packs
   * @param packages payroll package storage
   * @param evaluator the statutory resolver of the activation in effect
   * @param runService the read-only payroll recalculation
   */
  public PayrollApprovalEvidence(
      JdbcPayrollRunRepository runs,
      JdbcStatutoryRepository statutory,
      JdbcStatutoryRulePackRepository packs,
      JdbcPayrollPackageRepository packages,
      StatutoryEvaluator evaluator,
      PayrollRunService runService) {
    this.runs = runs;
    this.statutory = statutory;
    this.packs = packs;
    this.packages = packages;
    this.evaluator = evaluator;
    this.runService = runService;
  }

  /**
   * Everything one load read and decided.
   *
   * @param run the frozen run
   * @param calculation the statutory calculation
   * @param pack the calculation's rule pack, or {@code null}
   * @param lines the calculation's lines (at most 5,001)
   * @param components the lines' components by line
   * @param chain the packages of the chain, version ascending
   * @param result the decision
   * @param sourceRunDigest the live source-run digest
   * @param profileDigest the live profile digest
   * @param rulePackDigest the live rule-pack digest, or {@code null}
   * @param samePreparer whether the subject also prepared a source
   * @param existing the APPROVED package of the chain, or {@code null}
   */
  record Loaded(
      RunRow run,
      CalculationRow calculation,
      PackRow pack,
      List<LineRow> lines,
      Map<UUID, List<ComponentRow>> components,
      List<PackageRow> chain,
      Result result,
      String sourceRunDigest,
      String profileDigest,
      String rulePackDigest,
      boolean samePreparer,
      PackageRow existing) {

    Loaded {
      lines = List.copyOf(lines);
      chain = List.copyOf(chain);
    }

    int expectedVersion() {
      return chain.size();
    }

    @Override
    public String toString() {
      return "PayrollApprovalLoaded[" + run.id() + "]";
    }
  }

  /**
   * Loads and decides an approval.
   *
   * @param tenant verified tenant
   * @param entity legal entity (already verified)
   * @param runId payroll run
   * @param calculationId statutory calculation
   * @param subject verified subject, for the same-preparer fact
   * @param lockChain whether the chain rows are locked {@code FOR UPDATE} (the commit)
   * @return the evidence
   * @throws ApiException 404 for an unknown run or calculation
   */
  Loaded load(
      TenantId tenant,
      UUID entity,
      UUID runId,
      UUID calculationId,
      String subject,
      boolean lockChain) {
    RunRow run =
        runs.run(tenant, entity, runId)
            .orElseThrow(() -> new ApiException(ErrorCode.PAYROLL_RUN_NOT_FOUND, Map.of()));
    CalculationRow calculation =
        statutory
            .calculation(tenant, entity, runId, calculationId)
            .orElseThrow(
                () -> new ApiException(ErrorCode.STATUTORY_CALCULATION_NOT_FOUND, Map.of()));
    Preparers preparers =
        packages
            .preparers(tenant, entity, runId, calculationId)
            .orElseThrow(
                () -> new ApiException(ErrorCode.STATUTORY_CALCULATION_NOT_FOUND, Map.of()));
    boolean samePreparer =
        subject.equals(preparers.runCreatedBy())
            || subject.equals(preparers.calculationCreatedBy());

    int cap = PayrollApprovalEvaluator.MAX_PAYSLIPS + 1;
    List<SourceLineRow> sourceLines = statutory.sourceLines(tenant, runId, cap);
    List<LineRow> lines = statutory.linePage(tenant, calculationId, null, null, cap);
    Map<UUID, List<ComponentRow>> components =
        lines.size() >= cap
            ? Map.of()
            : statutory.components(tenant, calculationId, lines.stream().map(LineRow::id).toList());
    PackRow pack = packs.pack(calculation.packId()).orElse(null);

    List<UUID> employeeIds = new ArrayList<>();
    for (SourceLineRow line : sourceLines) {
      employeeIds.add(line.employeeId());
    }
    Map<UUID, List<StatutoryProfile>> profiles = statutory.profilesOf(tenant, employeeIds);
    String sourceDigest = StatutoryDigests.sourceRun(run, sourceLines);
    String profileDigest = StatutoryDigests.profiles(employeeIds, profiles);
    String rulePackDigest =
        evaluator
            .resolve(tenant, entity, run.periodStart())
            .map(resolved -> StatutoryDigests.rulePack(resolved.activation(), resolved.pack()))
            .orElse(null);

    boolean runCurrent = "DRAFT".equals(run.status());
    boolean sourceMatches = sourceDigest.equals(calculation.sourceRunDigest());
    boolean policyMatches = true;
    if (runCurrent) {
      Optional<PayrollRunService.Recomputed> fresh =
          runService.recompute(tenant, entity, run.periodStart(), run.currency());
      sourceMatches &= fresh.isPresent() && fresh.get().inputDigest().equals(run.inputDigest());
      policyMatches =
          fresh.isPresent()
              && fresh.get().policyId().equals(run.policyId())
              && fresh.get().policySequence() == run.policySequence();
    }

    List<PackageRow> chain =
        lockChain
            ? packages.chainForUpdate(tenant, entity, run.periodStart(), run.currency().name())
            : packages.chain(tenant, entity, run.periodStart(), run.currency().name());
    boolean exactApproved = false;
    boolean otherApproved = false;
    boolean voidedPair = false;
    PackageRow existing = null;
    for (PackageRow row : chain) {
      boolean samePair = row.runId().equals(runId) && row.calculationId().equals(calculationId);
      if ("APPROVED".equals(row.status())) {
        existing = row;
        exactApproved |= samePair;
        otherApproved |= !samePair;
      } else if (samePair) {
        voidedPair = true;
      }
    }

    Set<UUID> sourceEmployees = new HashSet<>(employeeIds);
    Set<UUID> calculationEmployees = new HashSet<>();
    List<LineFact> facts = new ArrayList<>();
    for (LineRow line : lines) {
      calculationEmployees.add(line.employeeId());
      List<ComponentFact> parts = new ArrayList<>();
      for (ComponentRow component : components.getOrDefault(line.id(), List.of())) {
        parts.add(
            new ComponentFact(component.party(), component.category(), component.amountMinor()));
      }
      facts.add(
          new LineFact(
              line.employeeId(),
              line.employeeNumber(),
              line.excluded(),
              line.grossMinor(),
              line.deductionsMinor(),
              line.contributionsMinor(),
              line.netMinor(),
              line.costMinor(),
              parts));
    }
    boolean employeesMatch =
        sourceEmployees.size() == employeeIds.size()
            && sourceEmployees.equals(calculationEmployees);

    Facts decision =
        new Facts(
            runCurrent,
            run.currency().name(),
            "DRAFT".equals(calculation.status()),
            calculation.currency(),
            pack != null,
            pack != null && "VERIFIED".equals(pack.status()),
            pack != null && pack.code().startsWith(SYNTHETIC_PREFIX),
            policyMatches,
            profileDigest.equals(calculation.profileDigest()),
            rulePackDigest != null && rulePackDigest.equals(calculation.rulePackDigest()),
            sourceMatches,
            sourceLines.size(),
            employeesMatch,
            calculation.supportedCount(),
            calculation.excludedCount(),
            new Totals(
                calculation.grossTotal(),
                calculation.deductionsTotal(),
                calculation.contributionsTotal(),
                calculation.netTotal(),
                calculation.costTotal()),
            samePreparer,
            otherApproved,
            exactApproved,
            voidedPair,
            chain.size());
    Result result = PayrollApprovalEvaluator.evaluate(decision, facts);
    return new Loaded(
        run,
        calculation,
        pack,
        lines,
        components,
        chain,
        result,
        sourceDigest,
        profileDigest,
        rulePackDigest,
        samePreparer,
        existing);
  }
}
