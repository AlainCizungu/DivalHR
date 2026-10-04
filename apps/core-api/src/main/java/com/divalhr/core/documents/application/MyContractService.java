package com.divalhr.core.documents.application;

import com.divalhr.core.documents.api.AcknowledgeContractRequest;
import com.divalhr.core.documents.api.ContractResponses.AcknowledgementResult;
import com.divalhr.core.documents.api.ContractResponses.MyContract;
import com.divalhr.core.documents.api.ContractResponses.MyPage;
import com.divalhr.core.documents.api.ContractResponses.MySummary;
import com.divalhr.core.documents.domain.AcknowledgementStatement;
import com.divalhr.core.documents.domain.ContractDigests;
import com.divalhr.core.documents.domain.ContractValueFormats;
import com.divalhr.core.documents.domain.TemplateGrammar;
import com.divalhr.core.documents.internal.JdbcContractRepository;
import com.divalhr.core.documents.internal.JdbcContractRepository.AcknowledgementRow;
import com.divalhr.core.documents.internal.JdbcContractRepository.ContractRow;
import com.divalhr.core.documents.internal.JdbcContractRepository.NewAcknowledgement;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * MVP-030 employee self-service: the caller's own contracts and their acknowledgement.
 *
 * <p>Authorization (role {@code employee}, verified tenant, active and unrevoked membership) has
 * run before any of this. Every query is then bound to the caller's own active employee-access
 * link, read through the identity port inside the same transaction ({@code FOR SHARE}, lock order
 * step 4): no link is {@code 403 EMPLOYEE_LINK_REQUIRED}, a business denial counted on the bounded
 * self-service counter, never durable privileged-denial evidence (A30-1). Another employee's or
 * tenant's contract, a malformed ID and an unknown ID are the same {@code 404}.
 *
 * <p>An acknowledgement records only that the authenticated employee made the stated confirmation
 * of the displayed snapshot. It is not an electronic signature (A30-3).
 */
@Service
public class MyContractService {

  /** List operation. */
  public static final String LIST = "contract.self-list";

  /** Read operation (and the disclosure audit action of lists and reads). */
  public static final String READ = "contract.self-read";

  /** Acknowledgement operation. */
  public static final String ACKNOWLEDGE = "contract.acknowledge";

  /** Per-subject bucket of self-service calls (30 per minute). */
  public static final String SUBJECT_BUCKET = "contract-self";

  private static final String CONTRACT_CODE = "MC";

  private static final IdempotentOperation.Spec ACKNOWLEDGE_SPEC =
      new IdempotentOperation.Spec(ACKNOWLEDGE, "contract", "acknowledge", "acknowledged", 200);

  private final JdbcContractRepository contracts;
  private final EmployeeAccessLinks links;
  private final ContractEvents events;
  private final ContractViews views;
  private final ContractOperations flow;
  private final IdempotentOperation operations;
  private final CursorCodec cursors;
  private final MeterRegistry meters;

  /**
   * Creates the service.
   *
   * @param contracts contract repository
   * @param links the identity port (joins this module's transactions)
   * @param events audit and outbox
   * @param views response builder
   * @param flow shared operation flow
   * @param operations idempotent operation flow
   * @param cursors keyset cursors
   * @param meters meter registry (self-service denial counter)
   */
  public MyContractService(
      JdbcContractRepository contracts,
      EmployeeAccessLinks links,
      ContractEvents events,
      ContractViews views,
      ContractOperations flow,
      IdempotentOperation operations,
      CursorCodec cursors,
      MeterRegistry meters) {
    this.contracts = contracts;
    this.links = links;
    this.events = events;
    this.views = views;
    this.flow = flow;
    this.operations = operations;
    this.cursors = cursors;
    this.meters = meters;
  }

  /**
   * The caller's own contracts, newest first, without content.
   *
   * @param caller verified caller
   * @param cursor raw cursor, or {@code null}
   * @param limit raw limit, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public MyPage list(DocumentsCaller caller, String cursor, String limit) {
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
    // Bound to the subject too: a cursor never crosses from one employee to another.
    CursorScope scope =
        new CursorScope(
            LIST,
            tenant,
            Map.of(
                "subject",
                ContractEvents.identifiers("subject", caller.subject()),
                "limit",
                Integer.toString(size)));
    KeysetPosition after =
        flow.validated(
            LIST,
            () -> {
              if (cursor == null) {
                return null;
              }
              KeysetPosition position = cursors.decode(cursor, scope);
              if (!CONTRACT_CODE.equals(position.code())) {
                throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
              }
              return position;
            });
    return counted(
        LIST,
        () ->
            flow.disclose(
                caller,
                LIST,
                READ,
                "my-contracts",
                after == null ? "first" : "next",
                () -> {
                  SelfLink link = link(caller);
                  ContractRow last = null;
                  if (after != null) {
                    last =
                        contracts
                            .find(tenant, link.employeeId(), after.id(), false)
                            .orElseThrow(
                                () -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  }
                  List<ContractRow> rows =
                      contracts.page(
                          tenant,
                          link.employeeId(),
                          last == null ? null : last.issuedAt(),
                          last == null ? null : last.id(),
                          size + 1);
                  boolean more = rows.size() > size;
                  List<ContractRow> shown = more ? rows.subList(0, size) : rows;
                  List<MySummary> items = shown.stream().map(ContractViews::mySummary).toList();
                  String next =
                      more
                          ? cursors.encode(
                              scope,
                              new KeysetPosition(CONTRACT_CODE, shown.get(shown.size() - 1).id()))
                          : null;
                  return new ContractOperations.Disclosure<>(
                      new MyPage(items, next),
                      "employee",
                      link.employeeId(),
                      shown.stream().map(ContractRow::id).toList());
                }));
  }

  /**
   * One of the caller's own contracts with its snapshot, the statements and the evidence.
   *
   * @param caller verified caller
   * @param contractId raw path value
   * @return the contract, after its disclosure audit committed
   */
  public MyContract read(DocumentsCaller caller, String contractId) {
    TenantId tenant = caller.tenant();
    return counted(
        READ,
        () -> {
          UUID id = flow.validated(READ, () -> contractId(contractId));
          return flow.disclose(
              caller,
              READ,
              READ,
              "my-contract",
              "first",
              () -> {
                SelfLink link = link(caller);
                ContractRow row =
                    contracts
                        .find(tenant, link.employeeId(), id, false)
                        .orElseThrow(ContractService::noContract);
                MyContract body =
                    views.myContract(row, contracts.acknowledgements(tenant, List.of(id)).get(id));
                return new ContractOperations.Disclosure<>(body, "contract", id, List.of(id));
              });
        });
  }

  /** What the client displayed and repeats (A30-4). */
  private record Shown(String snapshotSha256, String statementLocale, String statementSha256) {}

  /**
   * Records that the authenticated employee confirmed the stated acknowledgement of the displayed
   * contract. An already acknowledged contract returns its evidence and writes nothing.
   *
   * @param caller verified caller
   * @param contractId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the result and whether it was replayed
   */
  public IdempotentOperation.Result<AcknowledgementResult> acknowledge(
      DocumentsCaller caller,
      String contractId,
      String idempotencyKey,
      AcknowledgeContractRequest request) {
    return counted(
        ACKNOWLEDGE,
        () -> {
          UUID id = operations.validated(ACKNOWLEDGE_SPEC, () -> contractId(contractId));
          Shown shown =
              operations.validated(
                  ACKNOWLEDGE_SPEC,
                  () -> {
                    FieldErrors errors = new FieldErrors();
                    ContractService.requireKey(errors, idempotencyKey);
                    String snapshot = null;
                    String locale = null;
                    String statement = null;
                    if (ContractFields.body(request, errors)) {
                      snapshot =
                          ContractFields.digest(
                              request.getSnapshotSha256(), "snapshotSha256", errors);
                      ContractFields.pinned(
                          request.getSnapshotDigestVersion(),
                          "snapshotDigestVersion",
                          ContractDigests.VERSION,
                          errors);
                      ContractFields.pinned(
                          request.getGrammarVersion(),
                          "grammarVersion",
                          TemplateGrammar.VERSION,
                          errors);
                      ContractFields.pinned(
                          request.getRendererVersion(),
                          "rendererVersion",
                          ContractValueFormats.RENDERER_VERSION,
                          errors);
                      ContractFields.oneOf(
                          request.getStatementCode(),
                          "statementCode",
                          java.util.Set.of(AcknowledgementStatement.CODE),
                          errors);
                      ContractFields.pinned(
                          request.getStatementVersion(),
                          "statementVersion",
                          AcknowledgementStatement.VERSION,
                          errors);
                      locale =
                          ContractFields.oneOf(
                              request.getStatementLocale(),
                              "statementLocale",
                              ContractFields.LOCALES,
                              errors);
                      statement =
                          ContractFields.digest(
                              request.getStatementSha256(), "statementSha256", errors);
                    }
                    errors.throwIfAny();
                    return new Shown(snapshot, locale, statement);
                  });
          Map<String, Object> canonical = new TreeMap<>();
          canonical.put("tenantId", caller.tenant().toString());
          canonical.put("contractId", id.toString());
          canonical.put("snapshotSha256", shown.snapshotSha256());
          canonical.put("statementLocale", shown.statementLocale());
          canonical.put("statementSha256", shown.statementSha256());
          canonical.put("versions", "digest=1;grammar=1;renderer=1;statement=1");
          return ContractOperations.bounded(
              () ->
                  operations.execute(
                      ACKNOWLEDGE_SPEC,
                      caller.subject(),
                      idempotencyKey,
                      canonical,
                      AcknowledgementResult.class,
                      flow.transactionTimeout(),
                      () -> acknowledgeInTransaction(caller, id, shown)));
        });
  }

  private IdempotentOperation.Completed<AcknowledgementResult> acknowledgeInTransaction(
      DocumentsCaller caller, UUID id, Shown shown) {
    TenantId tenant = caller.tenant();
    flow.limit();
    // (4) The caller's own link and membership FOR SHARE, through the identity port.
    SelfLink link = link(caller);
    ContractRow unlocked =
        contracts
            .find(tenant, link.employeeId(), id, false)
            .orElseThrow(ContractService::noContract);
    // (5c) The guard of the employment, then the contract row FOR UPDATE.
    contracts.lockGuard(tenant, unlocked.employmentId());
    ContractRow row =
        contracts
            .find(tenant, link.employeeId(), id, true)
            .orElseThrow(ContractService::noContract);
    if ("ACKNOWLEDGED".equals(row.state())) {
      AcknowledgementRow existing = contracts.acknowledgements(tenant, List.of(id)).get(id);
      return new IdempotentOperation.Completed<>(
          new AcknowledgementResult(views.myContract(row, existing), true), id, Outcome.UNCHANGED);
    }
    if (!"ISSUED".equals(row.state())) {
      throw new ApiException(ErrorCode.CONTRACT_NOT_ACKNOWLEDGEABLE, Map.of("state", row.state()));
    }
    // The server's own values, compared in constant time with what the client displayed.
    AcknowledgementStatement statement =
        AcknowledgementStatement.find(
                AcknowledgementStatement.CODE,
                AcknowledgementStatement.VERSION,
                shown.statementLocale())
            .orElseThrow(() -> new IllegalStateException("statement missing"));
    String statementSha256 = statement.sha256();
    boolean snapshotMatches = ContractDigests.equal(row.snapshotSha256(), shown.snapshotSha256());
    boolean statementMatches = ContractDigests.equal(statementSha256, shown.statementSha256());
    if (!snapshotMatches || !statementMatches) {
      throw new ApiException(ErrorCode.CONTRACT_ACKNOWLEDGEMENT_CHANGED, Map.of());
    }
    Instant at = contracts.statementTime(tenant);
    String evidenceSha256 =
        ContractDigests.evidence(
            new ContractDigests.EvidenceBinding(
                id,
                link.employeeId(),
                link.membershipId(),
                link.linkId(),
                row.snapshotSha256(),
                statement.code(),
                statement.version(),
                statement.locale(),
                statementSha256,
                at));
    int updated;
    try {
      updated =
          contracts.acknowledge(
              tenant,
              new NewAcknowledgement(
                  UUID.randomUUID(),
                  id,
                  link.employeeId(),
                  link.membershipId(),
                  link.linkId(),
                  row.snapshotSha256(),
                  statement.locale(),
                  statementSha256,
                  evidenceSha256,
                  at,
                  caller.correlationId()),
              row.version());
    } catch (DataAccessException violated) {
      throw ContractOperations.mapped(violated);
    }
    if (updated != 1) {
      throw new IllegalStateException("locked contract changed");
    }
    events.acknowledged(caller, id, link.employeeId(), row.version() + 1, evidenceSha256, at);
    ContractRow after =
        contracts
            .find(tenant, link.employeeId(), id, false)
            .orElseThrow(() -> new IllegalStateException("contract vanished"));
    AcknowledgementRow evidence = contracts.acknowledgements(tenant, List.of(id)).get(id);
    return new IdempotentOperation.Completed<>(
        new AcknowledgementResult(views.myContract(after, evidence), false), id, Outcome.UPDATED);
  }

  /** The caller's own active link, or {@code 403 EMPLOYEE_LINK_REQUIRED}. */
  private SelfLink link(DocumentsCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  /**
   * Counts the business denials of self-service (A30-1): link required and not found, on the
   * bounded counter only.
   */
  private <T> T counted(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException denied) {
      if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
      } else if (denied.code() == ErrorCode.CONTRACT_NOT_FOUND) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "not_found");
      }
      throw denied;
    }
  }

  private static UUID contractId(String raw) {
    UUID id = ContractFields.pathId(raw);
    if (id == null) {
      throw ContractService.noContract();
    }
    return id;
  }
}
