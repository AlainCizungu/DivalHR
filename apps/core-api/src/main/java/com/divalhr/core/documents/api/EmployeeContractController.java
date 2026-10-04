package com.divalhr.core.documents.api;

import com.divalhr.core.documents.api.ContractResponses.Contract;
import com.divalhr.core.documents.api.ContractResponses.Page;
import com.divalhr.core.documents.api.ContractResponses.Preview;
import com.divalhr.core.documents.application.ContractService;
import com.divalhr.core.documents.application.ContractTemplateService;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * An employee's contracts, administrator side (MVP-030, Issue #51). Every handler is
 * {@code @TenantAdminOperation}. Reads and previews are fail-closed disclosures.
 */
@RestController
@RequestMapping(EmployeeContractController.PATH)
public class EmployeeContractController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/employees/{employeeId}/contracts";

  private final ContractService contracts;
  private final Callers callers;

  /**
   * Creates the controller.
   *
   * @param contracts contract service
   * @param callers verified caller helper
   */
  public EmployeeContractController(ContractService contracts, Callers callers) {
    this.contracts = contracts;
    this.callers = callers;
  }

  /**
   * Lists the employee's contracts, newest first.
   *
   * @param employeeId path value
   * @param cursor continuation
   * @param limit page size
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listEmployeeContracts")
  @GetMapping
  @TenantAdminOperation(operation = ContractService.LIST)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @PathVariable("employeeId") String employeeId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(
        contracts.list(callers.caller(authentication, request), employeeId, cursor, limit));
  }

  /**
   * Previews a contract.
   *
   * @param employeeId path value
   * @param body template version and period
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewEmployeeContract")
  @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = ContractService.PREVIEW)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Preview> preview(
      @PathVariable("employeeId") String employeeId,
      @RequestBody ContractPreviewRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(contracts.preview(callers.caller(authentication, request), employeeId, body));
  }

  /**
   * Issues a previewed contract.
   *
   * @param employeeId path value
   * @param idempotencyKey required idempotency key
   * @param body command and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the contract; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "issueEmployeeContract")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = ContractService.ISSUE)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = ContractTemplateService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Contract> issue(
      @PathVariable("employeeId") String employeeId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody IssueContractRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.respond(
        HttpStatus.CREATED,
        contracts.issue(callers.caller(authentication, request), employeeId, idempotencyKey, body));
  }

  /**
   * One contract with its snapshot and evidence.
   *
   * @param employeeId path value
   * @param contractId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the contract
   */
  @Operation(operationId = "getEmployeeContract")
  @GetMapping("/{contractId}")
  @TenantAdminOperation(operation = ContractService.READ)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Contract> read(
      @PathVariable("employeeId") String employeeId,
      @PathVariable("contractId") String contractId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(
        contracts.read(callers.caller(authentication, request), employeeId, contractId));
  }

  /**
   * Voids an issued contract.
   *
   * @param employeeId path value
   * @param contractId path value
   * @param idempotencyKey required idempotency key
   * @param body expected version and reason
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the contract; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "voidEmployeeContract")
  @PostMapping(path = "/{contractId}/void", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = ContractService.VOID)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = ContractTemplateService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Contract> voidContract(
      @PathVariable("employeeId") String employeeId,
      @PathVariable("contractId") String contractId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody VoidContractRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.respond(
        HttpStatus.OK,
        contracts.voidContract(
            callers.caller(authentication, request), employeeId, contractId, idempotencyKey, body));
  }
}
