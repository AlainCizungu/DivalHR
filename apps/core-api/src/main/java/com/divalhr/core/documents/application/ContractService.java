package com.divalhr.core.documents.application;

import com.divalhr.core.documents.api.ContractPreviewRequest;
import com.divalhr.core.documents.api.ContractResponses.Contract;
import com.divalhr.core.documents.api.ContractResponses.Page;
import com.divalhr.core.documents.api.ContractResponses.Preview;
import com.divalhr.core.documents.api.ContractResponses.Summary;
import com.divalhr.core.documents.api.IssueContractRequest;
import com.divalhr.core.documents.api.VoidContractRequest;
import com.divalhr.core.documents.domain.CanonicalSnapshot;
import com.divalhr.core.documents.domain.ContractDigests;
import com.divalhr.core.documents.domain.ContractPlaceholder;
import com.divalhr.core.documents.domain.ContractRenderer;
import com.divalhr.core.documents.domain.ContractRenderer.Rendering;
import com.divalhr.core.documents.domain.ContractValueFormats;
import com.divalhr.core.documents.domain.RenderedSnapshot;
import com.divalhr.core.documents.domain.TemplateGrammar;
import com.divalhr.core.documents.domain.TemplateGrammar.Parsed;
import com.divalhr.core.documents.internal.JdbcContractRepository;
import com.divalhr.core.documents.internal.JdbcContractRepository.ContractRow;
import com.divalhr.core.documents.internal.JdbcContractRepository.NewContract;
import com.divalhr.core.documents.internal.JdbcContractTemplateRepository;
import com.divalhr.core.documents.internal.JdbcContractTemplateRepository.IssuableVersion;
import com.divalhr.core.documents.internal.JdbcContractTemplateRepository.VersionRow;
import com.divalhr.core.platform.access.EmployeeRecords;
import com.divalhr.core.platform.access.EmploymentContractFacts;
import com.divalhr.core.platform.access.EmploymentContractFacts.ContractFacts;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.OrganizationDirectory.OrganizationSummary;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * MVP-030 contracts on the administrator side: preview, issue, list, read and void.
 *
 * <p>Transaction boundary of an issue (lock order, ADR 0008 as extended by ADR 0009): (0) the
 * idempotency reservation; (2) the employment row {@code FOR SHARE} through the people port; (5c)
 * the documents guard row of the employment {@code FOR UPDATE}, then the approved template version
 * {@code FOR SHARE}; (6) the insert, audit and outbox. Everything is re-rendered and the preview
 * digest recomputed under these locks; any difference is {@code 409 CONTRACT_PREVIEW_CHANGED}.
 * Employment history is never written.
 */
@Service
public class ContractService {

  /** Preview operation (and its disclosure audit action). */
  public static final String PREVIEW = "contract.preview";

  /** Issue operation. */
  public static final String ISSUE = "contract.issue";

  /** List operation. */
  public static final String LIST = "contract.list";

  /** Read operation (and the disclosure audit action of lists and reads). */
  public static final String READ = "contract.read";

  /** Void operation. */
  public static final String VOID = "contract.void";

  /** Types whose contract needs an end date (D10). */
  static final Set<String> END_REQUIRED = Set.of("FIXED_TERM", "APPRENTICESHIP", "INTERNSHIP");

  /** Largest distance between the business date and a contract start (D10). */
  static final int START_WINDOW_DAYS = 366;

  /** Largest canonical snapshot (bytes, as the V16 CHECK). */
  static final int MAX_SNAPSHOT_BYTES = 65_536;

  private static final String CONTRACT_CODE = "CT";

  private static final IdempotentOperation.Spec ISSUE_SPEC =
      new IdempotentOperation.Spec(ISSUE, "contract", "issue", "issued", 201);
  private static final IdempotentOperation.Spec VOID_SPEC =
      new IdempotentOperation.Spec(VOID, "contract", "void", "voided", 200);

  private final JdbcContractTemplateRepository templates;
  private final JdbcContractRepository contracts;
  private final EmploymentContractFacts facts;
  private final EmployeeRecords employees;
  private final OrganizationPlacementDirectory units;
  private final ContractCalendar calendar;
  private final ContractEvents events;
  private final ContractViews views;
  private final ContractOperations flow;
  private final IdempotentOperation operations;
  private final CursorCodec cursors;

  /**
   * Creates the service.
   *
   * @param templates template repository
   * @param contracts contract repository
   * @param facts the people port (joins this module's transactions)
   * @param employees employee existence port
   * @param units organization unit names
   * @param calendar business date
   * @param events audit and outbox
   * @param views response builder
   * @param flow shared operation flow
   * @param operations idempotent operation flow
   * @param cursors keyset cursors
   */
  public ContractService(
      JdbcContractTemplateRepository templates,
      JdbcContractRepository contracts,
      EmploymentContractFacts facts,
      EmployeeRecords employees,
      OrganizationPlacementDirectory units,
      ContractCalendar calendar,
      ContractEvents events,
      ContractViews views,
      ContractOperations flow,
      IdempotentOperation operations,
      CursorCodec cursors) {
    this.templates = templates;
    this.contracts = contracts;
    this.facts = facts;
    this.employees = employees;
    this.units = units;
    this.calendar = calendar;
    this.events = events;
    this.views = views;
    this.flow = flow;
    this.operations = operations;
    this.cursors = cursors;
  }

  // ------------------------------------------------------------------------------------------
  // Planning
  // ------------------------------------------------------------------------------------------

  /** A validated preview or issue command. */
  record Command(UUID templateVersionId, LocalDate startDate, LocalDate endDate) {

    Map<String, Object> canonical() {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("templateVersionId", templateVersionId.toString());
      canonical.put("startDate", startDate.toString());
      canonical.put("endDate", endDate == null ? "-" : endDate.toString());
      return canonical;
    }
  }

  /** A computed contract. */
  private record Planned(
      ContractFacts facts,
      IssuableVersion version,
      RenderedSnapshot snapshot,
      String canonical,
      String snapshotSha256,
      List<String> warnings,
      String previewDigest) {}

  private Planned plan(
      TenantId tenant, UUID employeeId, Command command, ContractFacts found, IssuableVersion v) {
    VersionRow version = v.version();
    if (!"APPROVED".equals(version.state())) {
      throw new ApiException(ErrorCode.CONTRACT_TEMPLATE_NOT_APPROVED, Map.of());
    }
    String locale = version.locale();
    String type = v.contractType();
    // The stored text is checked against its digest and grammar before it is ever rendered.
    if (!ContractDigests.equal(
        ContractDigests.templateBody(locale, version.title(), version.body()),
        version.bodySha256())) {
      throw new IllegalStateException("template version digest mismatch");
    }
    Parsed parsed = TemplateGrammar.parse(version.title(), version.body());
    if (!parsed.valid()) {
      throw new IllegalStateException("approved template version is not grammar v1");
    }
    if (found.separationRecorded() || found.employmentEnd() != null) {
      throw new ApiException(ErrorCode.CONTRACT_EMPLOYMENT_ENDED, Map.of());
    }
    OrganizationSummary organization = calendar.organization(tenant);
    LocalDate today = calendar.today(organization);
    LocalDate start = command.startDate();
    LocalDate end = command.endDate();
    if (start.isBefore(found.employmentStart())
        || start.isBefore(today.minusDays(START_WINDOW_DAYS))
        || start.isAfter(today.plusDays(START_WINDOW_DAYS))) {
      throw new ApiException(ErrorCode.CONTRACT_DATES_INVALID, Map.of("field", "startDate"));
    }
    if (end == null && END_REQUIRED.contains(type)) {
      throw new ApiException(ErrorCode.CONTRACT_DATES_INVALID, Map.of("field", "endDate"));
    }
    if (end != null && end.isBefore(start)) {
      throw new ApiException(ErrorCode.CONTRACT_DATES_INVALID, Map.of("field", "endDate"));
    }

    Map<ContractPlaceholder, String> values = new EnumMap<>(ContractPlaceholder.class);
    values.put(ContractPlaceholder.EMPLOYEE_GIVEN_NAMES, found.givenNames());
    values.put(ContractPlaceholder.EMPLOYEE_FAMILY_NAME, found.familyName());
    values.put(
        ContractPlaceholder.EMPLOYEE_FULL_NAME, found.givenNames() + " " + found.familyName());
    values.put(ContractPlaceholder.EMPLOYEE_NUMBER, found.employeeNumber());
    values.put(ContractPlaceholder.ORGANIZATION_NAME, organization.name());
    Set<UUID> placement = new HashSet<>();
    if (found.legalEntityId() != null) {
      placement.add(found.legalEntityId());
    }
    if (found.siteId() != null) {
      placement.add(found.siteId());
    }
    Map<UUID, UnitView> named =
        placement.isEmpty() ? Map.of() : units.resolveIds(tenant, placement);
    if (found.legalEntityId() != null && named.containsKey(found.legalEntityId())) {
      values.put(ContractPlaceholder.LEGAL_ENTITY_NAME, named.get(found.legalEntityId()).name());
    }
    if (found.siteId() != null && named.containsKey(found.siteId())) {
      values.put(ContractPlaceholder.SITE_NAME, named.get(found.siteId()).name());
    }
    values.put(
        ContractPlaceholder.EMPLOYMENT_START_DATE,
        ContractValueFormats.date(locale, found.employmentStart()));
    values.put(ContractPlaceholder.CONTRACT_TYPE, ContractValueFormats.type(locale, type));
    values.put(ContractPlaceholder.CONTRACT_START_DATE, ContractValueFormats.date(locale, start));
    if (end != null) {
      values.put(ContractPlaceholder.CONTRACT_END_DATE, ContractValueFormats.date(locale, end));
    }
    values.put(ContractPlaceholder.ISSUE_DATE, ContractValueFormats.date(locale, today));

    Rendering rendering = ContractRenderer.render(locale, parsed, values);
    if (rendering.snapshot() == null) {
      // D9: a missing value blocks issue; the first missing placeholder by key is named.
      String first =
          rendering.missing().stream().map(ContractPlaceholder::key).sorted().findFirst().get();
      throw new ApiException(ErrorCode.CONTRACT_VALUE_MISSING, Map.of("placeholder", first));
    }
    String canonical = CanonicalSnapshot.of(rendering.snapshot());
    if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
      throw new ApiException(
          ErrorCode.CONTRACT_TEMPLATE_INVALID, Map.of("reason", "TOO_LONG", "line", 0));
    }
    String snapshotSha256 = ContractDigests.snapshot(canonical);
    List<String> warnings = new ArrayList<>();
    if (found.classification() != null && !found.classification().equals(type)) {
      warnings.add("TYPE_DIFFERS_FROM_CLASSIFICATION");
    }
    String previewDigest =
        ContractDigests.preview(
            new ContractDigests.PreviewBinding(
                version.id(),
                version.bodySha256(),
                employeeId,
                found.employmentId(),
                found.employmentVersion(),
                type,
                locale,
                start,
                end,
                snapshotSha256));
    return new Planned(
        found, v, rendering.snapshot(), canonical, snapshotSha256, warnings, previewDigest);
  }

  // ------------------------------------------------------------------------------------------
  // Preview and issue
  // ------------------------------------------------------------------------------------------

  /**
   * Previews a contract. Writes nothing but the disclosure audit record.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param request body
   * @return the preview
   */
  public Preview preview(
      DocumentsCaller caller, String employeeId, ContractPreviewRequest request) {
    UUID id = flow.validated(PREVIEW, () -> employeeId(employeeId));
    Command command =
        flow.validated(
            PREVIEW,
            () -> {
              FieldErrors errors = new FieldErrors();
              Command parsed = null;
              if (ContractFields.body(request, errors)) {
                parsed =
                    command(
                        request.getTemplateVersionId(),
                        request.getStartDate(),
                        request.getEndDate(),
                        errors);
              }
              errors.throwIfAny();
              return parsed;
            });
    TenantId tenant = caller.tenant();
    return flow.disclose(
        caller,
        PREVIEW,
        PREVIEW,
        "contract-preview",
        "first",
        () -> {
          ContractFacts found =
              facts.read(tenant, id, command.startDate()).orElseThrow(ContractService::noEmployee);
          IssuableVersion version =
              templates
                  .issuable(tenant, command.templateVersionId(), false)
                  .orElseThrow(ContractService::noTemplate);
          Planned planned = plan(tenant, id, command, found, version);
          Preview body =
              new Preview(
                  found.employmentId(),
                  found.employmentVersion(),
                  version.version().id(),
                  version.contractType(),
                  version.version().locale(),
                  command.startDate(),
                  command.endDate(),
                  ContractViews.snapshot(planned.snapshot()),
                  ContractViews.integrity(planned.snapshotSha256()),
                  planned.warnings(),
                  planned.previewDigest());
          return new ContractOperations.Disclosure<>(
              body, "employee", id, List.of(id, version.version().id()));
        });
  }

  /** A validated issue. */
  private record Issue(Command command, long expectedEmploymentVersion, String previewDigest) {}

  /**
   * Issues a previewed contract, or replays an identical earlier issue.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the contract and whether it was replayed
   */
  public IdempotentOperation.Result<Contract> issue(
      DocumentsCaller caller,
      String employeeId,
      String idempotencyKey,
      IssueContractRequest request) {
    UUID id = operations.validated(ISSUE_SPEC, () -> employeeId(employeeId));
    Issue issue =
        operations.validated(
            ISSUE_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              requireKey(errors, idempotencyKey);
              Command parsed = null;
              Long expected = null;
              String digest = null;
              if (ContractFields.body(request, errors)) {
                parsed =
                    command(
                        request.getTemplateVersionId(),
                        request.getStartDate(),
                        request.getEndDate(),
                        errors);
                expected =
                    ContractFields.version(
                        request.getExpectedEmploymentVersion(),
                        "expectedEmploymentVersion",
                        errors);
                digest = ContractFields.digest(request.getPreviewDigest(), "previewDigest", errors);
              }
              errors.throwIfAny();
              return new Issue(parsed, expected, digest);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("employeeId", id.toString());
    canonical.put("command", issue.command().canonical());
    canonical.put("expectedEmploymentVersion", issue.expectedEmploymentVersion());
    canonical.put("previewDigest", issue.previewDigest());
    return ContractOperations.bounded(
        () ->
            operations.execute(
                ISSUE_SPEC,
                caller.subject(),
                idempotencyKey,
                canonical,
                Contract.class,
                flow.transactionTimeout(),
                () -> issueInTransaction(caller, id, issue)));
  }

  private IdempotentOperation.Completed<Contract> issueInTransaction(
      DocumentsCaller caller, UUID employeeId, Issue issue) {
    TenantId tenant = caller.tenant();
    Command command = issue.command();
    flow.limit();
    // (2) The employment row FOR SHARE, through the people port.
    ContractFacts found =
        facts
            .lockForContract(tenant, employeeId, command.startDate())
            .orElseThrow(ContractService::noEmployee);
    if (found.employmentVersion() != issue.expectedEmploymentVersion()) {
      throw new ApiException(ErrorCode.CONTRACT_PREVIEW_CHANGED, Map.of());
    }
    // (5c) This module's guard row of the employment, then the approved version FOR SHARE.
    contracts.lockGuard(tenant, found.employmentId());
    IssuableVersion version =
        templates
            .issuable(tenant, command.templateVersionId(), true)
            .orElseThrow(ContractService::noTemplate);
    Planned planned = plan(tenant, employeeId, command, found, version);
    if (!ContractDigests.equal(planned.previewDigest(), issue.previewDigest())) {
      throw new ApiException(ErrorCode.CONTRACT_PREVIEW_CHANGED, Map.of());
    }
    Instant now = flow.now();
    UUID contractId = UUID.randomUUID();
    try {
      contracts.insert(
          tenant,
          new NewContract(
              contractId,
              employeeId,
              found.employmentId(),
              version.version().templateId(),
              version.version().id(),
              version.contractType(),
              version.version().locale(),
              command.startDate(),
              command.endDate(),
              planned.canonical(),
              planned.snapshotSha256(),
              now),
          caller.subject());
    } catch (DataAccessException violated) {
      throw ContractOperations.mapped(violated);
    }
    events.issued(
        caller, contractId, employeeId, found.employmentId(), planned.snapshotSha256(), now);
    ContractRow row =
        contracts
            .find(tenant, employeeId, contractId, false)
            .orElseThrow(() -> new IllegalStateException("contract vanished"));
    return new IdempotentOperation.Completed<>(
        views.contract(row, null), contractId, Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * An employee's contracts, newest first, without content.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param cursor raw cursor, or {@code null}
   * @param limit raw limit, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public Page list(DocumentsCaller caller, String employeeId, String cursor, String limit) {
    UUID id = flow.validated(LIST, () -> employeeId(employeeId));
    TenantId tenant = caller.tenant();
    int size =
        flow.validated(
            LIST,
            () -> {
              FieldErrors errors = new FieldErrors();
              int value = ContractFields.limit(limit, errors);
              errors.throwIfAny();
              return value;
            });
    CursorScope scope =
        new CursorScope(
            LIST, tenant, Map.of("employeeId", id.toString(), "limit", Integer.toString(size)));
    KeysetPosition after =
        flow.validated(LIST, () -> cursor == null ? null : position(cursor, scope));
    return flow.disclose(
        caller,
        LIST,
        READ,
        "contracts",
        after == null ? "first" : "next",
        () -> {
          if (!employees.exists(tenant, id)) {
            throw noEmployee();
          }
          ContractRow last = null;
          if (after != null) {
            last =
                contracts
                    .find(tenant, id, after.id(), false)
                    .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
          }
          List<ContractRow> rows =
              contracts.page(
                  tenant,
                  id,
                  last == null ? null : last.issuedAt(),
                  last == null ? null : last.id(),
                  size + 1);
          boolean more = rows.size() > size;
          List<ContractRow> shown = more ? rows.subList(0, size) : rows;
          List<Summary> items = shown.stream().map(ContractViews::summary).toList();
          String next =
              more
                  ? cursors.encode(
                      scope, new KeysetPosition(CONTRACT_CODE, shown.get(shown.size() - 1).id()))
                  : null;
          return new ContractOperations.Disclosure<>(
              new Page(items, next), "employee", id, shown.stream().map(ContractRow::id).toList());
        });
  }

  /**
   * One contract of an employee with its snapshot and evidence.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param contractId raw path value
   * @return the contract, after its disclosure audit committed
   */
  public Contract read(DocumentsCaller caller, String employeeId, String contractId) {
    UUID employee = flow.validated(READ, () -> contractScope(employeeId));
    UUID id = flow.validated(READ, () -> contractScope(contractId));
    TenantId tenant = caller.tenant();
    return flow.disclose(
        caller,
        READ,
        READ,
        "contract",
        "first",
        () -> {
          ContractRow row =
              contracts.find(tenant, employee, id, false).orElseThrow(ContractService::noContract);
          Contract body =
              views.contract(row, contracts.acknowledgements(tenant, List.of(id)).get(id));
          return new ContractOperations.Disclosure<>(body, "contract", id, List.of(id));
        });
  }

  // ------------------------------------------------------------------------------------------
  // Void
  // ------------------------------------------------------------------------------------------

  /** A validated void. */
  private record Voiding(long expectedVersion, String reason) {}

  /**
   * Voids an issued contract (never an acknowledged one). Content and digests are kept.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param contractId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the contract and whether it was replayed
   */
  public IdempotentOperation.Result<Contract> voidContract(
      DocumentsCaller caller,
      String employeeId,
      String contractId,
      String idempotencyKey,
      VoidContractRequest request) {
    UUID employee = operations.validated(VOID_SPEC, () -> contractScope(employeeId));
    UUID id = operations.validated(VOID_SPEC, () -> contractScope(contractId));
    Voiding voiding =
        operations.validated(
            VOID_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              requireKey(errors, idempotencyKey);
              Long expected = null;
              String reason = null;
              if (ContractFields.body(request, errors)) {
                expected =
                    ContractFields.version(request.getExpectedVersion(), "expectedVersion", errors);
                reason =
                    ContractFields.oneOf(
                        request.getReasonCode(), "reasonCode", ContractFields.VOID_REASONS, errors);
              }
              errors.throwIfAny();
              return new Voiding(expected, reason);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("employeeId", employee.toString());
    canonical.put("contractId", id.toString());
    canonical.put("expectedVersion", voiding.expectedVersion());
    canonical.put("reasonCode", voiding.reason());
    return ContractOperations.bounded(
        () ->
            operations.execute(
                VOID_SPEC,
                caller.subject(),
                idempotencyKey,
                canonical,
                Contract.class,
                flow.transactionTimeout(),
                () -> {
                  TenantId tenant = caller.tenant();
                  flow.limit();
                  ContractRow unlocked =
                      contracts
                          .find(tenant, employee, id, false)
                          .orElseThrow(ContractService::noContract);
                  // (5c) The guard of the employment, then the contract row.
                  contracts.lockGuard(tenant, unlocked.employmentId());
                  ContractRow row =
                      contracts
                          .find(tenant, employee, id, true)
                          .orElseThrow(ContractService::noContract);
                  if (!"ISSUED".equals(row.state())) {
                    throw new ApiException(
                        ErrorCode.CONTRACT_NOT_VOIDABLE, Map.of("state", row.state()));
                  }
                  if (row.version() != voiding.expectedVersion()) {
                    throw new ApiException(ErrorCode.CONTRACT_VERSION_CONFLICT, Map.of());
                  }
                  Instant now = flow.now();
                  int updated;
                  try {
                    updated =
                        contracts.voidContract(
                            tenant, id, row.version(), voiding.reason(), now, caller.subject());
                  } catch (DataAccessException violated) {
                    throw ContractOperations.mapped(violated);
                  }
                  if (updated != 1) {
                    throw new ApiException(ErrorCode.CONTRACT_VERSION_CONFLICT, Map.of());
                  }
                  events.voided(caller, id, employee, row.version() + 1, now);
                  ContractRow after =
                      contracts
                          .find(tenant, employee, id, false)
                          .orElseThrow(() -> new IllegalStateException("contract vanished"));
                  return new IdempotentOperation.Completed<>(
                      views.contract(after, null), id, Outcome.UPDATED);
                }));
  }

  // ------------------------------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------------------------------

  private static Command command(
      Object rawVersion, Object rawStart, Object rawEnd, FieldErrors errors) {
    UUID version = ContractFields.uuid(rawVersion, "templateVersionId", errors);
    LocalDate start = ContractFields.date(rawStart, "startDate", false, errors);
    LocalDate end = ContractFields.date(rawEnd, "endDate", true, errors);
    if (version == null || start == null) {
      return null;
    }
    return new Command(version, start, end);
  }

  private KeysetPosition position(String cursor, CursorScope scope) {
    KeysetPosition position = cursors.decode(cursor, scope);
    if (!CONTRACT_CODE.equals(position.code())) {
      throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
    }
    return position;
  }

  static void requireKey(FieldErrors errors, String key) {
    if (key == null || key.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, FieldErrors.Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(key)) {
      errors.add(IdempotencyKeys.HEADER, FieldErrors.Constraint.FORMAT);
    }
  }

  private static UUID employeeId(String raw) {
    UUID id = ContractFields.pathId(raw);
    if (id == null) {
      throw noEmployee();
    }
    return id;
  }

  /** On a contract path, a malformed employee or contract ID is the same 404 as a missing one. */
  private static UUID contractScope(String raw) {
    UUID id = ContractFields.pathId(raw);
    if (id == null) {
      throw noContract();
    }
    return id;
  }

  private static ApiException noEmployee() {
    return new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, Map.of());
  }

  private static ApiException noTemplate() {
    return new ApiException(ErrorCode.CONTRACT_TEMPLATE_NOT_FOUND, Map.of());
  }

  static ApiException noContract() {
    return new ApiException(ErrorCode.CONTRACT_NOT_FOUND, Map.of());
  }
}
