package com.divalhr.core.documents.api;

import com.divalhr.core.documents.api.ContractResponses.AcknowledgementResult;
import com.divalhr.core.documents.api.ContractResponses.MyContract;
import com.divalhr.core.documents.api.ContractResponses.MyPage;
import com.divalhr.core.documents.application.MyContractService;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own contracts (MVP-030, Issue #51). Every handler is {@code @EmployeeSelfOperation}:
 * subject, role {@code employee}, tenant and an active, unrevoked membership are checked before any
 * argument or body is read; the service then binds every query to the caller's own active
 * employee-access link. An acknowledgement is not an electronic signature.
 */
@RestController
@RequestMapping(MyContractController.PATH)
public class MyContractController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/me/contracts";

  private final MyContractService contracts;
  private final Callers callers;

  /**
   * Creates the controller.
   *
   * @param contracts self-service contract service
   * @param callers verified caller helper
   */
  public MyContractController(MyContractService contracts, Callers callers) {
    this.contracts = contracts;
    this.callers = callers;
  }

  /**
   * Lists the caller's own contracts, newest first.
   *
   * @param cursor continuation
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listMyContracts")
  @GetMapping
  @EmployeeSelfOperation(operation = MyContractService.LIST)
  @SubjectRateLimited(bucket = MyContractService.SUBJECT_BUCKET)
  public ResponseEntity<MyPage> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(contracts.list(callers.caller(authentication, request), cursor, limit));
  }

  /**
   * One of the caller's own contracts.
   *
   * @param contractId path value
   * @param authentication verified employee
   * @param request current request
   * @return the contract with the statements
   */
  @Operation(operationId = "getMyContract")
  @GetMapping("/{contractId}")
  @EmployeeSelfOperation(operation = MyContractService.READ)
  @SubjectRateLimited(bucket = MyContractService.SUBJECT_BUCKET)
  public ResponseEntity<MyContract> read(
      @PathVariable("contractId") String contractId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(contracts.read(callers.caller(authentication, request), contractId));
  }

  /**
   * Acknowledges one of the caller's own contracts as displayed.
   *
   * @param contractId path value
   * @param idempotencyKey required idempotency key
   * @param body what was displayed
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the evidence; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "acknowledgeMyContract")
  @PostMapping(path = "/{contractId}/acknowledgement", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = MyContractService.ACKNOWLEDGE)
  @SubjectRateLimited(bucket = MyContractService.SUBJECT_BUCKET)
  public ResponseEntity<AcknowledgementResult> acknowledge(
      @PathVariable("contractId") String contractId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody AcknowledgeContractRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.respond(
        HttpStatus.OK,
        contracts.acknowledge(
            callers.caller(authentication, request), contractId, idempotencyKey, body));
  }
}
