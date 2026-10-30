package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitStatutoryProfileRequest;
import com.divalhr.core.payroll.api.StatutoryProfileCommandRequest;
import com.divalhr.core.payroll.api.StatutoryResponses.ProfileEntry;
import com.divalhr.core.payroll.api.StatutoryResponses.ProfilePreview;
import com.divalhr.core.payroll.api.StatutoryResponses.ProfileReceipt;
import com.divalhr.core.payroll.api.StatutoryResponses.ProfileView;
import com.divalhr.core.payroll.application.StatutoryCommands.ProfileCommit;
import com.divalhr.core.payroll.application.StatutoryCommands.ProfileDraft;
import com.divalhr.core.payroll.domain.StatutoryProfile;
import com.divalhr.core.payroll.domain.StatutoryProfileTimeline;
import com.divalhr.core.payroll.domain.StatutoryProfileTimeline.Blocker;
import com.divalhr.core.payroll.domain.StatutoryProfileTimeline.Plan;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.ProfileRow;
import com.divalhr.core.platform.access.EmploymentIntervals;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Effective-dated statutory profiles of employees for tenant administrators with privileged MFA
 * (MVP-094B, Issue #179, ADR 0034): the timeline, previews and commits. A profile is the employer's
 * declaration of an employee's statutory classification and subject flags with a closed evidence
 * code; it holds no identifier, citizenship, dependant, health data or free text. Profiles are
 * appended, never edited or deleted; a correction is a new entry. Authorization has run before
 * this; tenant, actor and scope never come from the request.
 *
 * <p>A commit runs in one idempotent transaction in the lock order of ADR 0034 section 7: the
 * idempotency reservation, the employee's employment rows {@code FOR SHARE} through the people
 * port, the tenant statutory lock shared, the employee's profile lock, then the checks, the audit
 * record and the outbox event. Audit and event carry the employee, the sequence and the version,
 * never a classification or a flag.
 */
@Service
public class StatutoryProfileService {

  /** Reads of the timeline (interceptor operation and disclosure audit action). */
  public static final String READ = StatutoryRecorder.PROFILE_READ;

  /** Previews (interceptor operation; disclosed as a profile read). */
  public static final String PREVIEW = "statutory-profile.preview";

  /** Commits (idempotency operation, interceptor operation and audit action). */
  public static final String CHANGE = StatutoryRecorder.PROFILE_CHANGE;

  /** Per-subject previews. */
  public static final String SUBJECT_PREVIEW_BUCKET = "statutory-profile-preview";

  /** Per-subject commits. */
  public static final String SUBJECT_WRITE_BUCKET = "statutory-profile-write";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.statutory-profile");
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec CHANGE_SPEC =
      new IdempotentOperation.Spec(CHANGE, "statutory_profile", "change", "changed", 201);

  private final IdempotentOperation operations;
  private final JdbcStatutoryRepository statutory;
  private final JdbcPayrollRunRepository runs;
  private final EmploymentIntervals employments;
  private final CompensationCalendar calendar;
  private final StatutoryRecorder recorder;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param statutory tenant-owned statutory storage
   * @param runs payroll storage (transaction limits)
   * @param employments the people module's employment port (existence and row locks)
   * @param calendar business date and preview window
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   */
  public StatutoryProfileService(
      IdempotentOperation operations,
      JdbcStatutoryRepository statutory,
      JdbcPayrollRunRepository runs,
      EmploymentIntervals employments,
      CompensationCalendar calendar,
      StatutoryRecorder recorder,
      PayrollOperations plumbing) {
    this.operations = operations;
    this.statutory = statutory;
    this.runs = runs;
    this.employments = employments;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
  }

  // ------------------------------------------------------------------------------------------
  // Read
  // ------------------------------------------------------------------------------------------

  /**
   * The statutory profile timeline of an employee.
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @return the timeline, after its disclosure audit committed
   */
  public ProfileView read(PayrollCaller caller, String employeeId) {
    return plumbing.observed(
        LOG,
        "statutory_profile_read",
        READ,
        () -> {
          UUID employee = CompensationCommands.id(employeeId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                employments.read(tenant, employee).orElseThrow(CompensationCommands::notFound);
                Instant now = calendar.now();
                LocalDate today = calendar.today(tenant);
                List<ProfileRow> rows = statutory.profiles(tenant, employee);
                List<StatutoryProfile> profiles = rows.stream().map(ProfileRow::profile).toList();
                List<ProfileEntry> timeline = rows.stream().map(StatutoryViews::entry).toList();
                ProfileEntry current =
                    StatutoryProfileTimeline.inForce(profiles, today)
                        .flatMap(
                            profile ->
                                timeline.stream()
                                    .filter(entry -> entry.profileId().equals(profile.id()))
                                    .findFirst())
                        .orElse(null);
                recorder.disclosed(
                    caller,
                    StatutoryRecorder.PROFILE_READ,
                    "statutory-profile",
                    employee,
                    Map.of("employeeId", employee.toString()),
                    "timeline",
                    true,
                    rows.size(),
                    now);
                return new ProfileView(
                    employee, StatutoryProfileTimeline.version(profiles), today, current, timeline);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Validates a profile entry without writing: the timeline before and after and the stable
   * blockers.
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @param body the entry
   * @return the preview, after its disclosure audit committed
   */
  public ProfilePreview preview(
      PayrollCaller caller, String employeeId, StatutoryProfileCommandRequest body) {
    return plumbing.observed(
        LOG,
        "statutory_profile_preview",
        PREVIEW,
        () -> {
          UUID employee = CompensationCommands.id(employeeId);
          ProfileDraft draft = StatutoryCommands.profileDraft(body);
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                employments.read(tenant, employee).orElseThrow(CompensationCommands::notFound);
                Instant now = calendar.now();
                LocalDate today = calendar.today(tenant);
                List<ProfileRow> rows = statutory.profiles(tenant, employee);
                List<StatutoryProfile> existing = rows.stream().map(ProfileRow::profile).toList();
                int version = StatutoryProfileTimeline.version(existing);
                StatutoryProfile candidate =
                    candidate(previewId(tenant, employee, version + 1, draft), version, draft);
                Plan plan = StatutoryProfileTimeline.plan(existing, candidate, today);
                Instant expiresAt = CompensationCalendar.expiry(now);
                String digest =
                    plan.committable()
                        ? StatutoryDigests.profilePreview(
                            tenant, employee, draft, version, expiresAt)
                        : null;
                recorder.disclosed(
                    caller,
                    StatutoryRecorder.PROFILE_READ,
                    "statutory-profile",
                    employee,
                    Map.of("employeeId", employee.toString()),
                    "preview",
                    true,
                    1,
                    now);
                return new ProfilePreview(
                    employee,
                    version,
                    draft.effectiveFrom(),
                    draft.classification().name(),
                    draft.cnssSubject(),
                    draft.irppSubject(),
                    draft.evidence().name(),
                    rows.stream().map(StatutoryViews::entry).toList(),
                    entries(plan.after(), rows, now),
                    plan.blockers().stream().map(Blocker::name).toList(),
                    digest,
                    digest == null ? null : expiresAt);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Commit
  // ------------------------------------------------------------------------------------------

  /**
   * Records a previewed profile entry, or replays an identical commit.
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the previewed entry with its digest
   * @return the receipt
   */
  public IdempotentOperation.Result<ProfileReceipt> commit(
      PayrollCaller caller,
      String employeeId,
      String idempotencyKey,
      CommitStatutoryProfileRequest body) {
    return plumbing.command(
        CHANGE,
        () -> {
          record Parsed(UUID employee, ProfileCommit commit) {}
          Parsed parsed =
              operations.validated(
                  CHANGE_SPEC,
                  () ->
                      new Parsed(
                          CompensationCommands.id(employeeId),
                          StatutoryCommands.profileCommit(idempotencyKey, body)));
          return operations.execute(
              CHANGE_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.commit().canonical(caller.tenant(), parsed.employee()),
              ProfileReceipt.class,
              TIMEOUT,
              () -> committed(caller, parsed.employee(), parsed.commit()));
        });
  }

  private IdempotentOperation.Completed<ProfileReceipt> committed(
      PayrollCaller caller, UUID employee, ProfileCommit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, TIMEOUT);
    // Step 2: the employee's employment rows FOR SHARE; an unknown employee is the safe 404.
    employments.lockShared(tenant, employee).orElseThrow(CompensationCommands::notFound);
    Instant now = calendar.now();
    LocalDate today = calendar.today(tenant);
    // The tenant statutory lock shared, then the employee's profile chain.
    statutory.lockStatutory(tenant, true);
    statutory.lockProfile(tenant, employee);
    List<StatutoryProfile> existing =
        statutory.profiles(tenant, employee).stream().map(ProfileRow::profile).toList();
    int version = StatutoryProfileTimeline.version(existing);
    if (version != command.expectedVersion()) {
      throw new ApiException(ErrorCode.STATUTORY_VERSION_CONFLICT, Map.of());
    }
    ProfileDraft draft = command.draft();
    StatutoryProfile candidate = candidate(UUID.randomUUID(), version, draft);
    Plan plan = StatutoryProfileTimeline.plan(existing, candidate, today);
    if (plan.blockers().contains(Blocker.LIMIT_REACHED)) {
      throw new ApiException(ErrorCode.STATUTORY_LIMIT_REACHED, Map.of());
    }
    if (!plan.committable()) {
      throw new ApiException(ErrorCode.STATUTORY_PREVIEW_STALE, Map.of());
    }
    requireFresh(tenant, employee, draft, version, command, now);
    try {
      statutory.insertProfile(tenant, employee, candidate, now, caller.subject());
    } catch (DataIntegrityViolationException violated) {
      throw StatutoryActivationService.integrity(violated);
    }
    recorder.profileChanged(
        caller,
        new StatutoryRecorder.ProfileRecorded(employee, candidate.id(), candidate.sequence(), now));
    LOG.atInfo()
        .addKeyValue("operation", CHANGE)
        .addKeyValue("outcome", "recorded")
        .log("statutory_profile_recorded");
    return new IdempotentOperation.Completed<>(
        new ProfileReceipt(
            candidate.id(),
            employee,
            candidate.sequence(),
            candidate.effectiveFrom(),
            candidate.sequence()),
        candidate.id(),
        Outcome.CREATED);
  }

  /** The expiry and the digest, under the locks. */
  private static void requireFresh(
      TenantId tenant,
      UUID employee,
      ProfileDraft draft,
      int version,
      ProfileCommit command,
      Instant now) {
    Instant expiresAt = command.expiresAt();
    if (!expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !StatutoryDigests.profilePreview(tenant, employee, draft, version, expiresAt)
            .equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.STATUTORY_PREVIEW_STALE, Map.of());
    }
  }

  private static StatutoryProfile candidate(UUID id, int version, ProfileDraft draft) {
    return new StatutoryProfile(
        id,
        version + 1,
        draft.effectiveFrom(),
        draft.classification(),
        draft.cnssSubject(),
        draft.irppSubject(),
        draft.evidence());
  }

  /** The timeline after the entry, newest first; the candidate is stamped with the server time. */
  private static List<ProfileEntry> entries(
      List<StatutoryProfile> profiles, List<ProfileRow> rows, Instant now) {
    Map<UUID, Instant> created = new HashMap<>();
    for (ProfileRow row : rows) {
      created.put(row.profile().id(), row.createdAt());
    }
    List<StatutoryProfile> newestFirst = new ArrayList<>(profiles);
    newestFirst.sort(
        Comparator.comparing(StatutoryProfile::effectiveFrom)
            .thenComparingInt(StatutoryProfile::sequence)
            .reversed());
    List<ProfileEntry> entries = new ArrayList<>();
    for (StatutoryProfile profile : newestFirst) {
      entries.add(StatutoryViews.entry(profile, created.getOrDefault(profile.id(), now)));
    }
    return entries;
  }

  private static UUID previewId(TenantId tenant, UUID employee, int sequence, ProfileDraft draft) {
    String seed =
        "statutory-profile-preview:"
            + tenant
            + ":"
            + employee
            + ":"
            + sequence
            + ":"
            + draft.effectiveFrom();
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
  }
}
