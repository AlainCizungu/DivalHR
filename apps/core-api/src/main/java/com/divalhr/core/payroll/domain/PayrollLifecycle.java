package com.divalhr.core.payroll.domain;

import com.divalhr.core.payroll.domain.PayrollInputs.Blocker;
import com.divalhr.core.payroll.domain.PayrollInputs.Evaluation;
import com.divalhr.core.payroll.domain.PayrollInputs.Run;
import com.divalhr.core.platform.csv.CsvCells;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The lifecycle of a payroll-input run after its generation (MVP-092, Issue #149; ADR 0025):
 * immutable replacement versions, the final lock and the worked-time export. Pure rules, no I/O.
 *
 * <p>Monthly-salary boundary: nothing here derives a salary, an overtime figure, a deduction or a
 * payment from worked minutes. The export is approved worked-time evidence for payroll preparation;
 * the fixed notices below travel with every surface and with the manifest.
 */
public final class PayrollLifecycle {

  /** The export schema version. */
  public static final int EXPORT_SCHEMA_VERSION = 1;

  /** The bounded explanation of the reason {@code OTHER}. */
  public static final int MAX_EXPLANATION = 200;

  /** The explicit row limit of one export (a run above it is refused, never truncated). */
  public static final int MAX_EXPORT_ROWS = 50_000;

  /** The fixed English monthly-salary notice. */
  public static final String NOTICE_EN =
      "Worked-time inputs only. Monthly salary is not calculated from these minutes. This export"
          + " does not calculate or authorize pay, tax, deductions or payment.";

  /** The fixed French monthly-salary notice. */
  public static final String NOTICE_FR =
      "Données de temps travaillé uniquement. Le salaire mensuel n’est pas calculé à partir de ces"
          + " minutes. Cet export ne calcule ni n’autorise le salaire, les impôts, les retenues ou"
          + " le paiement.";

  /** The CSV columns, in their exact order. */
  public static final List<String> COLUMNS =
      List.of(
          "employee_id",
          "employment_id",
          "legal_entity_id",
          "site_id",
          "cost_center_id",
          "work_date",
          "approved_worked_minutes",
          "timesheet_id",
          "timesheet_revision",
          "approval_action_id",
          "approval_cycle",
          "source_week_start",
          "source_generation_id",
          "source_generation_version",
          "source_lock_action_id",
          "source_lock_version",
          "payroll_input_run_id",
          "payroll_input_run_version");

  private static final Pattern PLAIN_TEXT =
      Pattern.compile("^[^<>@&\"\\\\\\p{Cntrl}]{1,200}$", Pattern.UNICODE_CASE);
  private static final Pattern LINK =
      Pattern.compile("(https?:|www\\.|://)", Pattern.CASE_INSENSITIVE);
  private static final Pattern LONG_NUMBER =
      Pattern.compile("[0-9][ .-]?[0-9][ .-]?[0-9][ .-]?" + "[0-9][ .-]?[0-9][ .-]?[0-9]");

  private PayrollLifecycle() {}

  /** Why a version replaces another; stable codes. */
  public enum Reason {
    /** The upstream timesheet evidence was corrected. */
    UPSTREAM_CORRECTION,
    /** The upstream timesheets were approved again. */
    UPSTREAM_REAPPROVAL,
    /** The scope of the run (the sites or the employment attribution) was corrected. */
    SCOPE_CORRECTION,
    /** Any other reason; a bounded plain-text explanation is required. */
    OTHER
  }

  /** The stable blockers of a supersession or a lock; every one is fail-closed. */
  public enum Block {
    /** The run is not the latest version of its legal entity and period. */
    NOT_LATEST_VERSION,
    /** The run is already locked. */
    ALREADY_LOCKED,
    /** The upstream evidence no longer matches the run's. */
    UPSTREAM_EVIDENCE_CHANGED,
    /** A source week is no longer locked (or was never generated). */
    SOURCE_PERIOD_NOT_LOCKED,
    /** The run holds no exportable rows. */
    NO_EXPORTABLE_ROWS,
    /** A supersession is offered only when the upstream evidence changed. */
    NO_CHANGED_EVIDENCE
  }

  /**
   * The final lock of a run.
   *
   * @param id the LOCKED action
   * @param runId the locked run
   * @param lockedAt the instant
   */
  public record Lock(UUID id, UUID runId, Instant lockedAt) {

    /** Identifiers only. */
    @Override
    public String toString() {
      return "PayrollLifecycle.Lock[" + id + "]";
    }
  }

  /**
   * The lifecycle state of one run.
   *
   * @param runId the run
   * @param version its version
   * @param supersedesRunId the run it replaces, or {@code null} for version 1
   * @param reason why, or {@code null} for version 1
   * @param explanation the bounded explanation of OTHER, or {@code null}
   * @param lock the final lock, or {@code null} while the run is OPEN
   * @param latest whether no later version exists
   * @param exportId the run's export, or {@code null}
   */
  public record State(
      UUID runId,
      int version,
      UUID supersedesRunId,
      Reason reason,
      String explanation,
      Lock lock,
      boolean latest,
      UUID exportId) {

    /** Whether the run is locked. */
    public boolean locked() {
      return lock != null;
    }

    /** Identifiers only. */
    @Override
    public String toString() {
      return "PayrollLifecycle.State[" + runId + "]";
    }
  }

  // ------------------------------------------------------------------------------------------
  // Blockers
  // ------------------------------------------------------------------------------------------

  /**
   * What blocks the supersession of a run now (sorted, stable codes). The generation blockers of
   * the evaluation are listed too: a version is never created from partial evidence.
   *
   * @param state the run's state
   * @param run the run
   * @param now the evaluation of the scope now
   * @return the codes; empty when ready
   */
  public static List<String> supersedeBlockers(State state, Run run, Evaluation now) {
    TreeSet<String> codes = new TreeSet<>();
    if (!state.latest()) {
      codes.add(Block.NOT_LATEST_VERSION.name());
    }
    if (state.locked()) {
      codes.add(Block.ALREADY_LOCKED.name());
    }
    for (Blocker blocker : now.blockers().keySet()) {
      codes.add(blocker.name());
    }
    if (now.ready() && now.evidenceDigest().equals(run.evidenceDigest())) {
      codes.add(Block.NO_CHANGED_EVIDENCE.name());
    }
    return List.copyOf(codes);
  }

  /**
   * What blocks the lock of a run now (sorted, stable codes).
   *
   * @param state the run's state
   * @param run the run
   * @param now the evaluation of the scope now
   * @return the codes; empty when ready
   */
  public static List<String> lockBlockers(State state, Run run, Evaluation now) {
    TreeSet<String> codes = new TreeSet<>();
    if (!state.latest()) {
      codes.add(Block.NOT_LATEST_VERSION.name());
    }
    if (state.locked()) {
      codes.add(Block.ALREADY_LOCKED.name());
    }
    if (run.totals().rows() < 1) {
      codes.add(Block.NO_EXPORTABLE_ROWS.name());
    }
    for (Blocker blocker : now.blockers().keySet()) {
      switch (blocker) {
        case WEEK_NOT_GENERATED, WEEK_NOT_LOCKED ->
            codes.add(Block.SOURCE_PERIOD_NOT_LOCKED.name());
        case EVIDENCE_CHANGED, ATTRIBUTION_AMBIGUOUS, NO_APPROVED_TIME ->
            codes.add(Block.UPSTREAM_EVIDENCE_CHANGED.name());
        default -> codes.add(Block.UPSTREAM_EVIDENCE_CHANGED.name());
      }
    }
    if (now.ready() && !now.evidenceDigest().equals(run.evidenceDigest())) {
      codes.add(Block.UPSTREAM_EVIDENCE_CHANGED.name());
    }
    return List.copyOf(codes);
  }

  // ------------------------------------------------------------------------------------------
  // The explanation of OTHER
  // ------------------------------------------------------------------------------------------

  /**
   * Whether an explanation is bounded plain text: 1 to {@value #MAX_EXPLANATION} characters, no
   * markup, address, backslash, quote, control character or link, and no run of six digits (an
   * identifier, a phone number).
   *
   * @param text the explanation
   * @return whether it is acceptable
   */
  public static boolean plainExplanation(String text) {
    return text != null
        && !text.isBlank()
        && text.equals(text.strip())
        && PLAIN_TEXT.matcher(text).matches()
        && !LINK.matcher(text).find()
        && !LONG_NUMBER.matcher(text).find();
  }

  // ------------------------------------------------------------------------------------------
  // The export
  // ------------------------------------------------------------------------------------------

  /**
   * One export row: exactly one immutable source row of the locked run.
   *
   * @param sourceLine the run's source row line
   * @param employeeId employee
   * @param employmentId employment
   * @param legalEntityId legal entity
   * @param siteId site
   * @param costCenterId cost center, or {@code null}
   * @param workDate site-local work date
   * @param workedMinutes approved worked minutes
   * @param timesheetId timesheet
   * @param timesheetRevision its revision
   * @param approvalActionId its current APPROVED action
   * @param approvalCycle that action's cycle
   * @param sourceWeekStart the week's Monday
   * @param sourceGenerationId the locked generation
   * @param sourceGenerationVersion its version
   * @param sourceLockActionId the generation's LOCKED action
   * @param sourceLockVersion the lock version
   * @param runId the payroll-input run
   * @param runVersion its version
   */
  public record Line(
      int sourceLine,
      UUID employeeId,
      UUID employmentId,
      UUID legalEntityId,
      UUID siteId,
      UUID costCenterId,
      LocalDate workDate,
      int workedMinutes,
      UUID timesheetId,
      int timesheetRevision,
      UUID approvalActionId,
      int approvalCycle,
      LocalDate sourceWeekStart,
      UUID sourceGenerationId,
      int sourceGenerationVersion,
      UUID sourceLockActionId,
      int sourceLockVersion,
      UUID runId,
      int runVersion) {

    /** Identifiers only. */
    @Override
    public String toString() {
      return "PayrollLifecycle.Line[run=" + runId + ", line=" + sourceLine + "]";
    }
  }

  /**
   * A stored export.
   *
   * @param id export
   * @param runId the locked run
   * @param runVersion its version
   * @param scope the run's scope
   * @param lockId the run's LOCKED action
   * @param lockVersion the lock version
   * @param employees employee and employment pairs
   * @param rows rows
   * @param workedMinutes total approved worked minutes
   * @param csvSha256 SHA-256 of the CSV bytes
   * @param createdAt creation instant
   */
  public record Export(
      UUID id,
      UUID runId,
      int runVersion,
      PayrollInputs.Scope scope,
      UUID lockId,
      int lockVersion,
      int employees,
      int rows,
      int workedMinutes,
      String csvSha256,
      Instant createdAt) {

    /** Identifiers only. */
    @Override
    public String toString() {
      return "PayrollLifecycle.Export[" + id + "]";
    }
  }

  /** The deterministic CSV order: employee, employment, work date, site, source row. */
  private static final Comparator<Line> ORDER =
      Comparator.comparing((Line l) -> l.employeeId().toString())
          .thenComparing(l -> l.employmentId().toString())
          .thenComparing(Line::workDate)
          .thenComparing(l -> l.siteId().toString())
          .thenComparingInt(Line::sourceLine);

  /**
   * The lines in the deterministic CSV order.
   *
   * @param lines lines in any order
   * @return a new ordered list
   */
  public static List<Line> ordered(List<Line> lines) {
    List<Line> sorted = new ArrayList<>(lines);
    sorted.sort(ORDER);
    return List.copyOf(sorted);
  }

  /**
   * The CSV bytes: UTF-8, CRLF, the header then one row per line in the given order, every cell
   * neutralized and quoted by the platform rule. Identifiers, dates and integers only.
   *
   * @param lines ordered lines
   * @return the bytes
   */
  public static byte[] csv(List<Line> lines) {
    StringBuilder csv = new StringBuilder(CsvCells.line(COLUMNS, ','));
    for (Line line : lines) {
      csv.append(
          CsvCells.line(
              List.of(
                  line.employeeId().toString(),
                  line.employmentId().toString(),
                  line.legalEntityId().toString(),
                  line.siteId().toString(),
                  line.costCenterId() == null ? "" : line.costCenterId().toString(),
                  line.workDate().toString(),
                  Integer.toString(line.workedMinutes()),
                  line.timesheetId().toString(),
                  Integer.toString(line.timesheetRevision()),
                  line.approvalActionId().toString(),
                  Integer.toString(line.approvalCycle()),
                  line.sourceWeekStart().toString(),
                  line.sourceGenerationId().toString(),
                  Integer.toString(line.sourceGenerationVersion()),
                  line.sourceLockActionId().toString(),
                  Integer.toString(line.sourceLockVersion()),
                  line.runId().toString(),
                  Integer.toString(line.runVersion())),
              ','));
    }
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The canonical manifest JSON of a stored export: fixed key order, ASCII identifiers, dates,
   * versions, counts, the minute total and band, the creation instant and the SHA-256 of the exact
   * CSV bytes. No creator, name, number or subject.
   *
   * @param tenant the tenant
   * @param export the stored export
   * @return UTF-8 bytes
   */
  public static byte[] manifest(TenantId tenant, Export export) {
    Objects.requireNonNull(tenant, "tenant");
    String json =
        "{\"schemaVersion\":"
            + EXPORT_SCHEMA_VERSION
            + ",\"exportId\":\""
            + export.id()
            + "\",\"purpose\":\"worked-time inputs for payroll preparation\""
            + ",\"notice\":{\"en\":\""
            + NOTICE_EN
            + "\",\"fr\":\""
            + NOTICE_FR
            + "\"},\"format\":\"text/csv; charset=utf-8; line-endings=CRLF\""
            + ",\"columns\":["
            + String.join(",", COLUMNS.stream().map(c -> "\"" + c + "\"").toList())
            + "],\"tenantId\":\""
            + tenant
            + "\",\"legalEntityId\":\""
            + export.scope().legalEntityId()
            + "\",\"periodStart\":\""
            + export.scope().periodStart()
            + "\",\"periodEnd\":\""
            + export.scope().periodEnd()
            + "\",\"businessTimezone\":\""
            + export.scope().businessTimezone()
            + "\",\"payrollInputRunId\":\""
            + export.runId()
            + "\",\"payrollInputRunVersion\":"
            + export.runVersion()
            + ",\"lockActionId\":\""
            + export.lockId()
            + "\",\"lockVersion\":"
            + export.lockVersion()
            + ",\"counts\":{\"employees\":"
            + export.employees()
            + ",\"rows\":"
            + export.rows()
            + "},\"workedMinutes\":"
            + export.workedMinutes()
            + ",\"workedMinuteBand\":\""
            + PayrollInputs.minuteBand(export.workedMinutes())
            + "\",\"csvSha256\":\""
            + export.csvSha256()
            + "\",\"createdAt\":\""
            + export.createdAt()
            + "\"}\n";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Lowercase hex SHA-256 of bytes.
   *
   * @param bytes bytes
   * @return the digest
   */
  public static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  /**
   * A safe attachment name built only from the approved scope, dates and version.
   *
   * @param export the stored export
   * @param extension {@code csv} or {@code manifest.json}
   * @return the file name
   */
  public static String filename(Export export, String extension) {
    return "divalhr-worked-time-inputs-"
        + export.scope().legalEntityId()
        + "-"
        + export.scope().periodStart()
        + "-"
        + export.scope().periodEnd()
        + "-v"
        + export.runVersion()
        + "."
        + extension;
  }
}
