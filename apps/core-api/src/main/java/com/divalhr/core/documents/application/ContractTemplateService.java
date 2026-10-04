package com.divalhr.core.documents.application;

import com.divalhr.core.documents.api.ApproveContractTemplateVersionRequest;
import com.divalhr.core.documents.api.ContractResponses.Problem;
import com.divalhr.core.documents.api.ContractResponses.Template;
import com.divalhr.core.documents.api.ContractResponses.TemplateLine;
import com.divalhr.core.documents.api.ContractResponses.TemplatePage;
import com.divalhr.core.documents.api.ContractResponses.TemplateSummary;
import com.divalhr.core.documents.api.ContractResponses.Validation;
import com.divalhr.core.documents.api.ContractResponses.Version;
import com.divalhr.core.documents.api.CreateContractTemplateRequest;
import com.divalhr.core.documents.api.CreateContractTemplateVersionRequest;
import com.divalhr.core.documents.api.RetireContractTemplateVersionRequest;
import com.divalhr.core.documents.api.UpdateContractTemplateVersionRequest;
import com.divalhr.core.documents.api.ValidateContractTemplateTextRequest;
import com.divalhr.core.documents.domain.ContractDigests;
import com.divalhr.core.documents.domain.ContractPlaceholder;
import com.divalhr.core.documents.domain.TemplateGrammar;
import com.divalhr.core.documents.domain.TemplateGrammar.Parsed;
import com.divalhr.core.documents.domain.TemplateProblem;
import com.divalhr.core.documents.internal.JdbcContractTemplateRepository;
import com.divalhr.core.documents.internal.JdbcContractTemplateRepository.TemplateRow;
import com.divalhr.core.documents.internal.JdbcContractTemplateRepository.VersionRow;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * MVP-030 contract templates: list, create, read, validate; draft versions created, edited and
 * deleted; approval (irreversible, retiring the previous approved version of the language in the
 * same transaction, D8) and retirement. Template reads are not disclosure-audited (D14): they hold
 * no personal data. Every write is audited; approvals and retirements are published.
 */
@Service
public class ContractTemplateService {

  /** List operation. */
  public static final String LIST = "contract-template.list";

  /** Create operation. */
  public static final String CREATE = "contract-template.create";

  /** Read operation. */
  public static final String READ = "contract-template.read";

  /** Validation operation. */
  public static final String VALIDATE = "contract-template.validate";

  /** Version creation. */
  public static final String VERSION_CREATE = "contract-template-version.create";

  /** Version read. */
  public static final String VERSION_READ = "contract-template-version.read";

  /** Draft edit. */
  public static final String VERSION_UPDATE = "contract-template-version.update";

  /** Draft deletion. */
  public static final String VERSION_DELETE = "contract-template-version.delete";

  /** Approval. */
  public static final String APPROVE = "contract-template-version.approve";

  /** Retirement. */
  public static final String RETIRE = "contract-template-version.retire";

  /** Per-subject bucket of administrator reads (60 per minute). */
  public static final String SUBJECT_READ_BUCKET = "contract-read";

  /** Per-subject bucket of administrator writes (20 per minute). */
  public static final String SUBJECT_WRITE_BUCKET = "contract-write";

  /** Per-tenant bucket of writes (200 per 10 minutes). */
  public static final String TENANT_WRITE_BUCKET = "contract-write";

  /** Longest validated text (a bound on work, beyond the grammar's own limits). */
  private static final int VALIDATE_MAX = 100_000;

  /** Control, format, private-use and unassigned characters are refused in a template name. */
  private static final java.util.regex.Pattern CONTROL =
      java.util.regex.Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}\\p{Cs}\\u2028\\u2029]");

  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "contract_template", "create", "created", 201);
  private static final IdempotentOperation.Spec VERSION_SPEC =
      new IdempotentOperation.Spec(
          VERSION_CREATE, "contract_template_version", "create", "created", 201);
  private static final IdempotentOperation.Spec APPROVE_SPEC =
      new IdempotentOperation.Spec(
          APPROVE, "contract_template_version", "approve", "approved", 200);
  private static final IdempotentOperation.Spec RETIRE_SPEC =
      new IdempotentOperation.Spec(RETIRE, "contract_template_version", "retire", "retired", 200);

  private final JdbcContractTemplateRepository templates;
  private final ContractEvents events;
  private final ContractOperations flow;
  private final IdempotentOperation operations;
  private final CursorCodec cursors;

  /**
   * Creates the service.
   *
   * @param templates template repository
   * @param events audit and outbox
   * @param flow shared operation flow
   * @param operations idempotent operation flow
   * @param cursors keyset cursors
   */
  public ContractTemplateService(
      JdbcContractTemplateRepository templates,
      ContractEvents events,
      ContractOperations flow,
      IdempotentOperation operations,
      CursorCodec cursors) {
    this.templates = templates;
    this.events = events;
    this.flow = flow;
    this.operations = operations;
    this.cursors = cursors;
  }

  // ------------------------------------------------------------------------------------------
  // Templates
  // ------------------------------------------------------------------------------------------

  /**
   * A page of the tenant's templates by code, with the state of each language line.
   *
   * @param caller verified caller
   * @param cursor raw cursor, or {@code null}
   * @param limit raw limit, or {@code null}
   * @return the page
   */
  public TemplatePage list(DocumentsCaller caller, String cursor, String limit) {
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
    CursorScope scope = new CursorScope(LIST, tenant, Map.of("limit", Integer.toString(size)));
    String after =
        flow.validated(
            LIST,
            () -> {
              if (cursor == null) {
                return null;
              }
              KeysetPosition position = cursors.decode(cursor, scope);
              TemplateRow row =
                  templates
                      .template(tenant, position.id())
                      .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
              if (!row.code().equals(position.code())) {
                throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
              }
              return row.code();
            });
    List<TemplateRow> rows = templates.templatePage(tenant, after, size + 1);
    boolean more = rows.size() > size;
    List<TemplateRow> shown = more ? rows.subList(0, size) : rows;
    Map<UUID, List<VersionRow>> versions = new LinkedHashMap<>();
    templates
        .versions(tenant, shown.stream().map(TemplateRow::id).toList())
        .forEach(v -> versions.computeIfAbsent(v.templateId(), k -> new ArrayList<>()).add(v));
    List<TemplateSummary> items = new ArrayList<>();
    for (TemplateRow row : shown) {
      List<VersionRow> own = versions.getOrDefault(row.id(), List.of());
      List<TemplateLine> lines = new ArrayList<>();
      for (String locale : List.of("fr", "en")) {
        Optional<VersionRow> approved =
            own.stream()
                .filter(v -> v.locale().equals(locale) && "APPROVED".equals(v.state()))
                .findFirst();
        Optional<VersionRow> draft =
            own.stream()
                .filter(v -> v.locale().equals(locale) && "DRAFT".equals(v.state()))
                .findFirst();
        lines.add(
            new TemplateLine(
                locale,
                approved.map(VersionRow::id).orElse(null),
                approved.map(VersionRow::number).orElse(null),
                draft.map(VersionRow::id).orElse(null)));
      }
      items.add(
          new TemplateSummary(
              row.id(), row.code(), row.name(), row.contractType(), lines, row.createdAt()));
    }
    String next = null;
    if (more) {
      TemplateRow last = shown.get(shown.size() - 1);
      next = cursors.encode(scope, new KeysetPosition(last.code(), last.id()));
    }
    flow.listed(LIST);
    return new TemplatePage(items, next);
  }

  /** A validated template creation. */
  private record NewTemplate(String code, String name, String contractType) {}

  /**
   * Creates a template identity (code and type are immutable).
   *
   * @param caller verified caller
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the template and whether it was replayed
   */
  public IdempotentOperation.Result<Template> create(
      DocumentsCaller caller, String idempotencyKey, CreateContractTemplateRequest request) {
    NewTemplate command =
        operations.validated(
            CREATE_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              requireKey(errors, idempotencyKey);
              String code = null;
              String name = null;
              String type = null;
              if (ContractFields.body(request, errors)) {
                code = ContractFields.code(request.getCode(), "code", errors);
                name = ContractFields.text(request.getName(), "name", 2, 160, errors);
                if (name != null && (!name.equals(name.strip()) || CONTROL.matcher(name).find())) {
                  errors.add("name", FieldErrors.Constraint.FORMAT);
                }
                type =
                    ContractFields.oneOf(
                        request.getContractType(), "contractType", ContractFields.TYPES, errors);
              }
              errors.throwIfAny();
              return new NewTemplate(code, TemplateGrammar.normalize(name), type);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("code", command.code());
    canonical.put("name", command.name());
    canonical.put("contractType", command.contractType());
    return ContractOperations.bounded(
        () ->
            operations.execute(
                CREATE_SPEC,
                caller.subject(),
                idempotencyKey,
                canonical,
                Template.class,
                flow.transactionTimeout(),
                () -> {
                  flow.limit();
                  Instant now = flow.now();
                  TemplateRow row =
                      new TemplateRow(
                          UUID.randomUUID(),
                          command.code(),
                          command.name(),
                          command.contractType(),
                          now,
                          0);
                  try {
                    templates.insertTemplate(caller.tenant(), row, caller.subject());
                  } catch (DataAccessException violated) {
                    throw ContractOperations.mapped(violated);
                  }
                  events.templateCreated(caller, row.id(), now);
                  return new IdempotentOperation.Completed<>(
                      template(caller.tenant(), row), row.id(), Outcome.CREATED);
                }));
  }

  /**
   * A template with its versions (without text).
   *
   * @param caller verified caller
   * @param templateId raw path value
   * @return the template
   */
  public Template read(DocumentsCaller caller, String templateId) {
    UUID id = flow.validated(READ, () -> templateId(templateId));
    TemplateRow row = templates.template(caller.tenant(), id).orElseThrow(this::notFound);
    flow.listed(READ);
    return template(caller.tenant(), row);
  }

  /**
   * Validates a title and body against grammar v1 without writing.
   *
   * @param caller verified caller
   * @param request body
   * @return the report: closed reasons and line numbers only
   */
  public Validation validate(DocumentsCaller caller, ValidateContractTemplateTextRequest request) {
    Parsed parsed =
        flow.validated(
            VALIDATE,
            () -> {
              FieldErrors errors = new FieldErrors();
              String title = null;
              String body = null;
              if (ContractFields.body(request, errors)) {
                title = ContractFields.text(request.getTitle(), "title", 0, VALIDATE_MAX, errors);
                body = ContractFields.text(request.getBody(), "body", 0, VALIDATE_MAX, errors);
              }
              errors.throwIfAny();
              return TemplateGrammar.parse(title, body);
            });
    List<Problem> problems = new ArrayList<>();
    for (TemplateProblem problem : parsed.problems()) {
      problems.add(new Problem(problem.reason().name(), problem.line()));
    }
    flow.listed(VALIDATE);
    return new Validation(parsed.valid(), problems, keys(parsed));
  }

  // ------------------------------------------------------------------------------------------
  // Versions
  // ------------------------------------------------------------------------------------------

  /** A validated draft text. */
  private record Text(String locale, String title, String body, Parsed parsed) {}

  /**
   * Creates a draft version in one language with the next number for that language.
   *
   * @param caller verified caller
   * @param templateId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the draft and whether it was replayed
   */
  public IdempotentOperation.Result<Version> createVersion(
      DocumentsCaller caller,
      String templateId,
      String idempotencyKey,
      CreateContractTemplateVersionRequest request) {
    UUID id = operations.validated(VERSION_SPEC, () -> templateId(templateId));
    Text text =
        operations.validated(
            VERSION_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              requireKey(errors, idempotencyKey);
              String locale = null;
              String title = null;
              String body = null;
              if (ContractFields.body(request, errors)) {
                locale =
                    ContractFields.oneOf(
                        request.getLocale(), "locale", ContractFields.LOCALES, errors);
                title = ContractFields.text(request.getTitle(), "title", 1, 160, errors);
                body = ContractFields.text(request.getBody(), "body", 1, 40_000, errors);
              }
              errors.throwIfAny();
              return checked(locale, title, body);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("templateId", id.toString());
    canonical.put("locale", text.locale());
    canonical.put(
        "textSha256", ContractDigests.templateBody(text.locale(), text.title(), text.body()));
    return ContractOperations.bounded(
        () ->
            operations.execute(
                VERSION_SPEC,
                caller.subject(),
                idempotencyKey,
                canonical,
                Version.class,
                flow.transactionTimeout(),
                () -> {
                  TenantId tenant = caller.tenant();
                  flow.limit();
                  if (!templates.lockTemplate(tenant, id)) {
                    throw notFound();
                  }
                  boolean draftOpen =
                      templates.versions(tenant, List.of(id)).stream()
                          .anyMatch(
                              v -> v.locale().equals(text.locale()) && "DRAFT".equals(v.state()));
                  if (draftOpen) {
                    throw new ApiException(ErrorCode.CONTRACT_TEMPLATE_VERSION_CONFLICT, Map.of());
                  }
                  Instant now = flow.now();
                  VersionRow row =
                      new VersionRow(
                          UUID.randomUUID(),
                          id,
                          text.locale(),
                          templates.nextNumber(tenant, id, text.locale()),
                          "DRAFT",
                          text.title(),
                          text.body(),
                          keys(text.parsed()),
                          ContractDigests.templateBody(text.locale(), text.title(), text.body()),
                          now,
                          now,
                          null,
                          null,
                          0);
                  try {
                    templates.insertDraft(tenant, row, caller.subject());
                  } catch (DataAccessException violated) {
                    throw ContractOperations.mapped(violated);
                  }
                  events.draftWritten(
                      caller, VERSION_CREATE, row.id(), row.number(), 0, row.bodySha256(), now);
                  return new IdempotentOperation.Completed<>(
                      ContractViews.version(id, row), row.id(), Outcome.CREATED);
                }));
  }

  /**
   * One version with its text.
   *
   * @param caller verified caller
   * @param templateId raw path value
   * @param versionId raw path value
   * @return the version
   */
  public Version readVersion(DocumentsCaller caller, String templateId, String versionId) {
    UUID template = flow.validated(VERSION_READ, () -> templateId(templateId));
    UUID version = flow.validated(VERSION_READ, () -> templateId(versionId));
    VersionRow row =
        templates.version(caller.tenant(), template, version, false).orElseThrow(this::notFound);
    flow.listed(VERSION_READ);
    return ContractViews.version(template, row);
  }

  /**
   * Replaces a draft's title and body.
   *
   * @param caller verified caller
   * @param templateId raw path value
   * @param versionId raw path value
   * @param request body with {@code expectedVersion}
   * @return the draft
   */
  public Version updateVersion(
      DocumentsCaller caller,
      String templateId,
      String versionId,
      UpdateContractTemplateVersionRequest request) {
    UUID template = flow.validated(VERSION_UPDATE, () -> templateId(templateId));
    UUID version = flow.validated(VERSION_UPDATE, () -> templateId(versionId));
    record Edit(String title, String body, long expectedVersion) {}
    Edit edit =
        flow.validated(
            VERSION_UPDATE,
            () -> {
              FieldErrors errors = new FieldErrors();
              String title = null;
              String body = null;
              Long expected = null;
              if (ContractFields.body(request, errors)) {
                title = ContractFields.text(request.getTitle(), "title", 1, 160, errors);
                body = ContractFields.text(request.getBody(), "body", 1, 40_000, errors);
                expected =
                    ContractFields.version(request.getExpectedVersion(), "expectedVersion", errors);
              }
              errors.throwIfAny();
              return new Edit(title, body, expected);
            });
    return flow.write(
        VERSION_UPDATE,
        () -> {
          TenantId tenant = caller.tenant();
          VersionRow row = draft(tenant, template, version, edit.expectedVersion());
          Text text = checked(row.locale(), edit.title(), edit.body());
          Instant now = flow.now();
          String sha = ContractDigests.templateBody(text.locale(), text.title(), text.body());
          int updated;
          try {
            updated =
                templates.updateDraft(
                    tenant,
                    version,
                    row.version(),
                    text.title(),
                    text.body(),
                    keys(text.parsed()),
                    sha,
                    now,
                    caller.subject());
          } catch (DataAccessException violated) {
            throw ContractOperations.mapped(violated);
          }
          if (updated != 1) {
            throw new ApiException(ErrorCode.CONTRACT_TEMPLATE_VERSION_CONFLICT, Map.of());
          }
          events.draftWritten(
              caller, VERSION_UPDATE, version, row.number(), row.version() + 1, sha, now);
          return ContractViews.version(
              template,
              templates
                  .version(tenant, template, version, false)
                  .orElseThrow(() -> new IllegalStateException("draft vanished")));
        });
  }

  /**
   * Deletes a never-approved draft.
   *
   * @param caller verified caller
   * @param templateId raw path value
   * @param versionId raw path value
   * @param expectedVersion raw query value
   */
  public void deleteVersion(
      DocumentsCaller caller, String templateId, String versionId, String expectedVersion) {
    UUID template = flow.validated(VERSION_DELETE, () -> templateId(templateId));
    UUID version = flow.validated(VERSION_DELETE, () -> templateId(versionId));
    long expected =
        flow.validated(
            VERSION_DELETE,
            () -> {
              FieldErrors errors = new FieldErrors();
              Long value = null;
              if (expectedVersion == null) {
                errors.add("expectedVersion", FieldErrors.Constraint.REQUIRED);
              } else if (!expectedVersion.matches("^[0-9]{1,18}$")) {
                errors.add("expectedVersion", FieldErrors.Constraint.FORMAT);
              } else {
                value = Long.parseLong(expectedVersion);
              }
              errors.throwIfAny();
              return value;
            });
    flow.write(
        VERSION_DELETE,
        () -> {
          TenantId tenant = caller.tenant();
          VersionRow row = draft(tenant, template, version, expected);
          int deleted;
          try {
            deleted = templates.deleteDraft(tenant, version, row.version());
          } catch (DataAccessException violated) {
            throw ContractOperations.mapped(violated);
          }
          if (deleted != 1) {
            throw new ApiException(ErrorCode.CONTRACT_TEMPLATE_VERSION_CONFLICT, Map.of());
          }
          events.draftWritten(
              caller,
              VERSION_DELETE,
              version,
              row.number(),
              row.version(),
              row.bodySha256(),
              flow.now());
          return Boolean.TRUE;
        });
  }

  /**
   * Approves a draft (irreversible); the previous approved version of its language is retired in
   * the same transaction.
   *
   * @param caller verified caller
   * @param templateId raw path value
   * @param versionId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the approved version and whether it was replayed
   */
  public IdempotentOperation.Result<Version> approve(
      DocumentsCaller caller,
      String templateId,
      String versionId,
      String idempotencyKey,
      ApproveContractTemplateVersionRequest request) {
    UUID template = operations.validated(APPROVE_SPEC, () -> templateId(templateId));
    UUID version = operations.validated(APPROVE_SPEC, () -> templateId(versionId));
    long expected =
        operations.validated(
            APPROVE_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              requireKey(errors, idempotencyKey);
              Long value = null;
              boolean verified = false;
              if (ContractFields.body(request, errors)) {
                value =
                    ContractFields.version(request.getExpectedVersion(), "expectedVersion", errors);
                verified =
                    ContractFields.textVerified(
                        request.getAcknowledgements(), "acknowledgements", errors);
              }
              errors.throwIfAny();
              if (!verified) {
                throw new ApiException(
                    ErrorCode.CONTRACT_TEMPLATE_ACKNOWLEDGEMENT_REQUIRED,
                    Map.of("acknowledgement", ContractFields.TEXT_VERIFIED));
              }
              return value;
            });
    return transition(caller, APPROVE_SPEC, template, version, expected, idempotencyKey, true);
  }

  /**
   * Retires an approved version: it can no longer be issued.
   *
   * @param caller verified caller
   * @param templateId raw path value
   * @param versionId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the retired version and whether it was replayed
   */
  public IdempotentOperation.Result<Version> retire(
      DocumentsCaller caller,
      String templateId,
      String versionId,
      String idempotencyKey,
      RetireContractTemplateVersionRequest request) {
    UUID template = operations.validated(RETIRE_SPEC, () -> templateId(templateId));
    UUID version = operations.validated(RETIRE_SPEC, () -> templateId(versionId));
    long expected =
        operations.validated(
            RETIRE_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              requireKey(errors, idempotencyKey);
              Long value = null;
              if (ContractFields.body(request, errors)) {
                value =
                    ContractFields.version(request.getExpectedVersion(), "expectedVersion", errors);
              }
              errors.throwIfAny();
              return value;
            });
    return transition(caller, RETIRE_SPEC, template, version, expected, idempotencyKey, false);
  }

  private IdempotentOperation.Result<Version> transition(
      DocumentsCaller caller,
      IdempotentOperation.Spec spec,
      UUID template,
      UUID version,
      long expected,
      String idempotencyKey,
      boolean approve) {
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("templateId", template.toString());
    canonical.put("versionId", version.toString());
    canonical.put("expectedVersion", expected);
    return ContractOperations.bounded(
        () ->
            operations.execute(
                spec,
                caller.subject(),
                idempotencyKey,
                canonical,
                Version.class,
                flow.transactionTimeout(),
                () -> {
                  TenantId tenant = caller.tenant();
                  flow.limit();
                  // Template, then version rows (approve and retire never take other locks).
                  if (!templates.lockTemplate(tenant, template)) {
                    throw notFound();
                  }
                  VersionRow row =
                      templates
                          .version(tenant, template, version, true)
                          .orElseThrow(this::notFound);
                  String from = approve ? "DRAFT" : "APPROVED";
                  if (!from.equals(row.state())) {
                    throw new ApiException(
                        approve
                            ? ErrorCode.CONTRACT_TEMPLATE_NOT_DRAFT
                            : ErrorCode.CONTRACT_TEMPLATE_NOT_APPROVED,
                        Map.of());
                  }
                  if (row.version() != expected) {
                    throw new ApiException(ErrorCode.CONTRACT_TEMPLATE_VERSION_CONFLICT, Map.of());
                  }
                  Instant now = flow.now();
                  int retiredPrevious = 0;
                  try {
                    if (approve) {
                      // The stored text is re-validated: only grammar v1 text is ever approved.
                      checked(row.locale(), row.title(), row.body());
                      Optional<VersionRow> previous =
                          templates.lockApproved(tenant, template, row.locale());
                      if (previous.isPresent()) {
                        VersionRow old = previous.get();
                        templates.transition(
                            tenant, old.id(), old.version(), "RETIRED", now, caller.subject());
                        events.versionTransitioned(
                            caller,
                            false,
                            template,
                            old.id(),
                            old.version() + 1,
                            0,
                            old.bodySha256(),
                            now);
                        retiredPrevious = 1;
                      }
                    }
                    int moved =
                        templates.transition(
                            tenant,
                            version,
                            row.version(),
                            approve ? "APPROVED" : "RETIRED",
                            now,
                            caller.subject());
                    if (moved != 1) {
                      throw new ApiException(
                          ErrorCode.CONTRACT_TEMPLATE_VERSION_CONFLICT, Map.of());
                    }
                  } catch (DataAccessException violated) {
                    throw ContractOperations.mapped(violated);
                  }
                  events.versionTransitioned(
                      caller,
                      approve,
                      template,
                      version,
                      row.version() + 1,
                      retiredPrevious,
                      row.bodySha256(),
                      now);
                  VersionRow after =
                      templates
                          .version(tenant, template, version, false)
                          .orElseThrow(() -> new IllegalStateException("version vanished"));
                  return new IdempotentOperation.Completed<>(
                      ContractViews.version(template, after), version, Outcome.UPDATED);
                }));
  }

  // ------------------------------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------------------------------

  private Template template(TenantId tenant, TemplateRow row) {
    List<com.divalhr.core.documents.api.ContractResponses.VersionSummary> versions =
        templates.versions(tenant, List.of(row.id())).stream()
            .map(ContractViews::versionSummary)
            .toList();
    return new Template(
        row.id(), row.code(), row.name(), row.contractType(), row.createdAt(), versions);
  }

  /** The draft to edit or delete, locked, at the expected version. */
  private VersionRow draft(TenantId tenant, UUID template, UUID version, long expected) {
    VersionRow row = templates.version(tenant, template, version, true).orElseThrow(this::notFound);
    if (!"DRAFT".equals(row.state())) {
      throw new ApiException(ErrorCode.CONTRACT_TEMPLATE_NOT_DRAFT, Map.of());
    }
    if (row.version() != expected) {
      throw new ApiException(ErrorCode.CONTRACT_TEMPLATE_VERSION_CONFLICT, Map.of());
    }
    return row;
  }

  /**
   * Normalizes and checks a text against grammar v1: {@code 422 CONTRACT_TEMPLATE_INVALID} with the
   * first problem's closed reason and line, never the text.
   */
  private static Text checked(String locale, String rawTitle, String rawBody) {
    String title = TemplateGrammar.normalize(rawTitle).strip();
    String body = TemplateGrammar.normalize(rawBody);
    Parsed parsed = TemplateGrammar.parse(title, body);
    if (!parsed.valid()) {
      TemplateProblem first = parsed.problems().get(0);
      throw new ApiException(
          ErrorCode.CONTRACT_TEMPLATE_INVALID,
          Map.of("reason", first.reason().name(), "line", first.line()));
    }
    return new Text(locale, title, body, parsed);
  }

  private static List<String> keys(Parsed parsed) {
    return parsed.placeholders().stream().map(ContractPlaceholder::key).sorted().toList();
  }

  private static void requireKey(FieldErrors errors, String key) {
    if (key == null || key.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, FieldErrors.Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(key)) {
      errors.add(IdempotencyKeys.HEADER, FieldErrors.Constraint.FORMAT);
    }
  }

  private UUID templateId(String raw) {
    UUID id = ContractFields.pathId(raw);
    if (id == null) {
      throw notFound();
    }
    return id;
  }

  private ApiException notFound() {
    return new ApiException(ErrorCode.CONTRACT_TEMPLATE_NOT_FOUND, Map.of());
  }
}
