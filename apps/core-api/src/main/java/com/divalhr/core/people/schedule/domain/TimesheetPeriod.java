package com.divalhr.core.people.schedule.domain;

import com.divalhr.core.platform.idempotency.Fingerprints;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * MVP-071 period lock evidence (Issue #139, L71-1 to L71-6; ADR 0022).
 *
 * <p>The period is one site's generated week; its identity is the generation. The lock holds
 * exactly the week's current approvals: every timesheet of the generation APPROVED by its current
 * action at its current revision and cycle, under the generation's current version. The evidence
 * digest binds the scope, the generation version and every approval in timesheet order, so any
 * later approval, revision, reopening or refresh changes it. A LOCKED action records the digest,
 * the counts and the total; the UNLOCKED action that reverses it repeats them and adds the closed
 * reason and the explanation. Neither the explanation nor any name, number or subject appears in a
 * {@code toString}.
 */
public final class TimesheetPeriod {

  /** The lock states. */
  public enum State {
    /** Not locked (never locked, or unlocked). */
    OPEN,
    /** Locked for payroll preparation. */
    LOCKED
  }

  /** The two period actions. */
  public enum Kind {
    /** The week was locked. */
    LOCKED,
    /** The lock was reversed. */
    UNLOCKED
  }

  /** The closed unlock reasons (L71-6). */
  public enum UnlockReason {
    /** An attendance correction is required inside the locked week. */
    ATTENDANCE_CORRECTION_REQUIRED,
    /** The approved timesheet evidence is wrong. */
    TIMESHEET_EVIDENCE_ERROR,
    /** The organization asked for the unlock. */
    ORGANIZATION_REQUEST,
    /** Another reason, described in the explanation. */
    OTHER
  }

  /** The stable reasons a week is not ready to lock (L71-2); never an employee identity. */
  public enum Blocker {
    /** The generation holds no timesheet. */
    EMPTY,
    /** At least one timesheet is DRAFT, SUBMITTED or REOPENED. */
    NOT_ALL_APPROVED,
    /** An approval is not the current action at the current revision and cycle. */
    APPROVAL_NOT_CURRENT,
    /** The week is already locked. */
    ALREADY_LOCKED
  }

  private TimesheetPeriod() {}

  /**
   * The locked scope: the tenant's legal entity, its site and the generated week as generated.
   *
   * @param legalEntityId legal entity
   * @param period the site, its zone and the exact week boundaries
   * @param generationId the generation
   * @param generationVersion its current version
   */
  public record Scope(
      UUID legalEntityId, Timesheet.Period period, UUID generationId, int generationVersion) {

    /** Requires every component. */
    public Scope {
      Objects.requireNonNull(legalEntityId, "legalEntityId");
      Objects.requireNonNull(period, "period");
      Objects.requireNonNull(generationId, "generationId");
    }
  }

  /**
   * One timesheet of the generation as it stands now.
   *
   * @param timesheetId timesheet
   * @param employeeId employee
   * @param status its current status
   * @param revision its current revision
   * @param cycle its latest submission cycle
   * @param currentActionId its current action, or {@code null}
   * @param currentActionKind that action's kind, or {@code null}
   * @param actionRevision the revision that action binds, or 0
   * @param actionCycle the cycle that action binds, or 0
   * @param actionGenerationVersion the generation version that action binds, or 0
   * @param sourceDigest that action's source digest, or {@code null}
   * @param rows the current row count
   * @param workedMinutes the current worked minutes
   */
  public record Member(
      UUID timesheetId,
      UUID employeeId,
      String status,
      int revision,
      int cycle,
      UUID currentActionId,
      String currentActionKind,
      int actionRevision,
      int actionCycle,
      int actionGenerationVersion,
      String sourceDigest,
      int rows,
      int workedMinutes) {

    /**
     * Whether the timesheet is APPROVED by its current action at its current revision and cycle. An
     * approval binds the generation version of its day; a later refresh never rewrites an approved
     * timesheet (MVP-070D), so the version it binds may be older than the generation's current one
     * and is recorded, not required.
     */
    public boolean approvedNow() {
      return "APPROVED".equals(status)
          && "APPROVED".equals(currentActionKind)
          && currentActionId != null
          && actionRevision == revision
          && actionCycle == cycle;
    }

    /** Identifiers only. */
    @Override
    public String toString() {
      return "TimesheetPeriod.Member[timesheet=" + timesheetId + "]";
    }
  }

  /**
   * Counts and the total of a generation's timesheets.
   *
   * @param timesheets timesheets
   * @param employees distinct employees
   * @param rows rows
   * @param workedMinutes total worked minutes
   */
  public record Totals(int timesheets, int employees, int rows, int workedMinutes) {

    /**
     * The totals of members.
     *
     * @param members members
     * @return the totals
     */
    public static Totals of(List<Member> members) {
      int rows = 0;
      int minutes = 0;
      for (Member member : members) {
        rows = Math.addExact(rows, member.rows());
        minutes = Math.addExact(minutes, member.workedMinutes());
      }
      return new Totals(
          members.size(),
          (int) members.stream().map(Member::employeeId).distinct().count(),
          rows,
          minutes);
    }
  }

  /**
   * The evaluated readiness of a generation (L71-2).
   *
   * @param scope the scope
   * @param members the generation's timesheets, in timesheet order
   * @param totals their totals
   * @param states timesheets by status (DRAFT, SUBMITTED, APPROVED, REOPENED)
   * @param blockers why the week cannot be locked now; empty when it can
   * @param evidenceDigest the approval evidence digest (meaningful when ready)
   */
  public record Readiness(
      Scope scope,
      List<Member> members,
      Totals totals,
      Map<String, Integer> states,
      List<Blocker> blockers,
      String evidenceDigest) {

    /** Copies the collections. */
    public Readiness {
      members = List.copyOf(members);
      states = Map.copyOf(states);
      blockers = List.copyOf(blockers);
    }

    /** Whether the week may be locked now. */
    public boolean ready() {
      return blockers.isEmpty();
    }

    /**
     * Evaluates a generation's timesheets.
     *
     * @param scope the scope
     * @param members the generation's timesheets in any order
     * @param locked whether the week is locked now
     * @return the readiness
     */
    public static Readiness of(Scope scope, List<Member> members, boolean locked) {
      List<Member> ordered = new ArrayList<>(members);
      ordered.sort(Comparator.comparing(m -> m.timesheetId().toString()));
      Map<String, Integer> states = new TreeMap<>();
      for (String status : List.of("DRAFT", "SUBMITTED", "APPROVED", "REOPENED")) {
        states.put(status, 0);
      }
      ordered.forEach(m -> states.merge(m.status(), 1, Integer::sum));
      List<Blocker> blockers = new ArrayList<>();
      if (ordered.isEmpty()) {
        blockers.add(Blocker.EMPTY);
      }
      if (ordered.stream().anyMatch(m -> !"APPROVED".equals(m.status()))) {
        blockers.add(Blocker.NOT_ALL_APPROVED);
      }
      if (ordered.stream().anyMatch(m -> "APPROVED".equals(m.status()) && !m.approvedNow())) {
        blockers.add(Blocker.APPROVAL_NOT_CURRENT);
      }
      if (locked) {
        blockers.add(Blocker.ALREADY_LOCKED);
      }
      return new Readiness(
          scope,
          ordered,
          Totals.of(ordered),
          states,
          blockers,
          TimesheetPeriod.evidenceDigest(scope, ordered));
    }
  }

  /**
   * The current lock state of a generation.
   *
   * @param state OPEN or LOCKED
   * @param version the number of transitions so far (0 before the first lock)
   * @param currentActionId the current LOCKED or UNLOCKED action, or {@code null}
   * @param updatedAt the latest transition instant, or {@code null}
   */
  public record LockState(State state, int version, UUID currentActionId, Instant updatedAt) {

    /** Never locked. */
    public static final LockState NEVER = new LockState(State.OPEN, 0, null, null);

    /** Whether the week is locked now. */
    public boolean locked() {
      return state == State.LOCKED;
    }
  }

  /**
   * One immutable lock or unlock.
   *
   * @param id action
   * @param generationId the generation
   * @param sequence the version after the transition (1, 2, ...)
   * @param kind LOCKED or UNLOCKED
   * @param scope the exact scope and the bound generation version
   * @param totals the locked counts and total
   * @param evidenceDigest the locked approval evidence digest
   * @param lockedActionId for an unlock, the reversed lock; else {@code null}
   * @param unlockReason for an unlock, the closed reason; else {@code null}
   * @param unlockExplanation for an unlock, the explanation (Restricted); else {@code null}
   * @param occurredAt instant
   */
  public record Action(
      UUID id,
      UUID generationId,
      int sequence,
      Kind kind,
      Scope scope,
      Totals totals,
      String evidenceDigest,
      UUID lockedActionId,
      UnlockReason unlockReason,
      String unlockExplanation,
      Instant occurredAt) {

    /** Requires a consistent shape. */
    public Action {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(generationId, "generationId");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(totals, "totals");
      Objects.requireNonNull(evidenceDigest, "evidenceDigest");
      Objects.requireNonNull(occurredAt, "occurredAt");
      boolean unlock = kind == Kind.UNLOCKED;
      if (sequence < 1
          || unlock != (sequence % 2 == 0)
          || unlock != (lockedActionId != null)
          || unlock != (unlockReason != null)
          || unlock != (unlockExplanation != null)) {
        throw new IllegalArgumentException("not a period action");
      }
    }

    /**
     * The explanation's digest: the only form of it that enters a preview or audit digest.
     *
     * @return lowercase hex SHA-256, or {@code ""} for a lock
     */
    public String explanationDigest() {
      return unlockExplanation == null ? "" : TimesheetPeriod.explanationDigest(unlockExplanation);
    }

    /** Identifiers and the kind only: never the explanation or the subject. */
    @Override
    public String toString() {
      return "TimesheetPeriod.Action[id=" + id + ", kind=" + kind + "]";
    }
  }

  /**
   * The SHA-256 of a generation's approval evidence: the scope, the generation version and every
   * timesheet with its current status, revision, cycle, current action, that action's source digest
   * and totals. Identical approvals give the same digest; any transition, revision or refresh
   * changes it.
   *
   * @param scope scope
   * @param members members in timesheet order
   * @return lowercase hex SHA-256
   */
  public static String evidenceDigest(Scope scope, List<Member> members) {
    StringBuilder text =
        new StringBuilder("DIVALHR-TIMESHEET-PERIOD-EVIDENCE\nversion=1\nlegalEntity=")
            .append(scope.legalEntityId())
            .append("\nsite=")
            .append(scope.period().siteId())
            .append("\nweek=")
            .append(scope.period().weekStart())
            .append("\nzone=")
            .append(scope.period().timezone())
            .append("\ngeneration=")
            .append(scope.generationId())
            .append("\ngenerationVersion=")
            .append(scope.generationVersion())
            .append('\n');
    for (Member member : members) {
      text.append(member.timesheetId())
          .append('|')
          .append(member.employeeId())
          .append('|')
          .append(member.status())
          .append('|')
          .append(member.revision())
          .append('|')
          .append(member.cycle())
          .append('|')
          .append(member.currentActionId() == null ? "-" : member.currentActionId())
          .append('|')
          .append(member.sourceDigest() == null ? "-" : member.sourceDigest())
          .append('|')
          .append(member.rows())
          .append('|')
          .append(member.workedMinutes())
          .append('\n');
    }
    return Fingerprints.sha256(text.toString());
  }

  /**
   * The digest of an unlock explanation.
   *
   * @param explanation the trimmed explanation
   * @return lowercase hex SHA-256
   */
  public static String explanationDigest(String explanation) {
    return Fingerprints.sha256("DIVALHR-TIMESHEET-PERIOD-UNLOCK-EXPLANATION\n" + explanation);
  }

  /**
   * A bounded band of total worked minutes for events and audit metadata: never the exact total.
   *
   * @param minutes total worked minutes
   * @return the band
   */
  public static String minuteBand(int minutes) {
    return TimesheetExport.minuteBand(minutes);
  }

  /**
   * A bounded band of a count for logs and metrics: never the exact count.
   *
   * @param count a count
   * @return the band
   */
  public static String countBand(int count) {
    if (count == 0) {
      return "0";
    }
    if (count <= 10) {
      return "1-10";
    }
    return count <= 100 ? "11-100" : "101+";
  }
}
