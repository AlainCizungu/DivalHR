package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.StatutoryProfile;
import com.divalhr.core.payroll.domain.StatutoryProfileTimeline;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository.RunRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.ActivationRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.SourceLineRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.PackRow;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The stateless digests of statutory previews (MVP-094B, ADR 0034 section 6; ADR 0007 style): a
 * SHA-256 over a versioned canonical text. The source run digest covers the run (identifier,
 * version, status, input digest) and each of its lines (identifier and frozen gross); the profile
 * digest covers every profile row of every employee of the run; the rule pack digest covers the
 * activation in effect and the pack (identifier, version, status, rules digest); the calculation
 * digest binds the tenant, the legal entity, the run, the pack, the three digests, the chain
 * version and the expiry. A commit recomputes all four from the current state under the locks; any
 * difference is a stale preview. The digests are only compared and hashed, never logged, audited
 * with their input or stored beside an amount other than as the commit evidence.
 */
final class StatutoryDigests {

  private StatutoryDigests() {}

  /**
   * The digest of the frozen source run.
   *
   * @param run the run
   * @param lines its frozen lines
   * @return the digest
   */
  static String sourceRun(RunRow run, List<SourceLineRow> lines) {
    StringBuilder text = new StringBuilder("DIVALHR-STATUTORY-SOURCE-RUN\nversion=1\n");
    text.append("run=").append(run.id()).append(';').append(run.version()).append('\n');
    text.append("status=").append(run.status()).append('\n');
    text.append("input=").append(run.inputDigest()).append('\n');
    List<SourceLineRow> ordered = new ArrayList<>(lines);
    ordered.sort(Comparator.comparing(SourceLineRow::id));
    for (SourceLineRow line : ordered) {
      text.append("line=").append(line.id()).append(';').append(line.grossMinor()).append('\n');
    }
    return Fingerprints.sha256(text.toString());
  }

  /**
   * The digest of every profile row of every employee of the run.
   *
   * @param employeeIds the employees of the run
   * @param profiles each employee's rows (an employee without an entry has none)
   * @return the digest
   */
  static String profiles(List<UUID> employeeIds, Map<UUID, List<StatutoryProfile>> profiles) {
    StringBuilder text = new StringBuilder("DIVALHR-STATUTORY-PROFILES\nversion=1\n");
    List<UUID> ordered = new ArrayList<>(employeeIds);
    ordered.sort(Comparator.naturalOrder());
    for (UUID employee : ordered) {
      text.append("employee=").append(employee).append('\n');
      for (StatutoryProfile profile :
          StatutoryProfileTimeline.chronological(profiles.getOrDefault(employee, List.of()))) {
        text.append("profile=")
            .append(profile.id())
            .append(';')
            .append(profile.sequence())
            .append(';')
            .append(profile.effectiveFrom())
            .append(';')
            .append(profile.classification())
            .append(';')
            .append(profile.cnssSubject())
            .append(';')
            .append(profile.irppSubject())
            .append(';')
            .append(profile.evidence())
            .append('\n');
      }
    }
    return Fingerprints.sha256(text.toString());
  }

  /**
   * The digest of the activation in effect and its pack.
   *
   * @param activation the activation in effect
   * @param pack its pack
   * @return the digest
   */
  static String rulePack(ActivationRow activation, PackRow pack) {
    String text =
        "DIVALHR-STATUTORY-RULE-PACK\nversion=1\nactivation="
            + activation.id()
            + ';'
            + activation.sequence()
            + "\npack="
            + pack.id()
            + ';'
            + pack.version()
            + ';'
            + pack.status()
            + "\nrules="
            + pack.rulesDigest()
            + "\n";
    return Fingerprints.sha256(text);
  }

  /**
   * The digest of a previewed calculation command.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param runId source run
   * @param packId the pack in effect
   * @param sourceRunDigest the source run digest
   * @param profileDigest the profile digest
   * @param rulePackDigest the rule pack digest
   * @param expectedVersion the chain version
   * @param expiresAt expiry
   * @return the digest
   */
  static String calculation(
      TenantId tenant,
      UUID legalEntityId,
      UUID runId,
      UUID packId,
      String sourceRunDigest,
      String profileDigest,
      String rulePackDigest,
      int expectedVersion,
      Instant expiresAt) {
    String text =
        "DIVALHR-STATUTORY-CALCULATION-PREVIEW\nversion=1\ntenant="
            + tenant
            + "\nentity="
            + legalEntityId
            + "\nrun="
            + runId
            + "\npack="
            + packId
            + "\nsource="
            + sourceRunDigest
            + "\nprofiles="
            + profileDigest
            + "\nrulePack="
            + rulePackDigest
            + "\nexpectedVersion="
            + expectedVersion
            + "\nexpiresAt="
            + expiresAt
            + "\n";
    return Fingerprints.sha256(text);
  }

  /**
   * The digest of a previewed profile entry.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param draft the command
   * @param version the number of entries
   * @param expiresAt expiry
   * @return the digest
   */
  static String profilePreview(
      TenantId tenant,
      UUID employeeId,
      StatutoryCommands.ProfileDraft draft,
      int version,
      Instant expiresAt) {
    String text =
        "DIVALHR-STATUTORY-PROFILE-PREVIEW\nversion=1\ntenant="
            + tenant
            + "\nemployee="
            + employeeId
            + "\neffectiveFrom="
            + draft.effectiveFrom()
            + "\nclassification="
            + draft.classification()
            + "\ncnss="
            + draft.cnssSubject()
            + "\nirpp="
            + draft.irppSubject()
            + "\nevidence="
            + draft.evidence()
            + "\nprofileVersion="
            + version
            + "\nexpiresAt="
            + expiresAt
            + "\n";
    return Fingerprints.sha256(text);
  }
}
