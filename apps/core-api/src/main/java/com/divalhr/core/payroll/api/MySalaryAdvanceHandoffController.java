package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.SalaryAdvanceHandoffBodies.CommitHandoff;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffBodies.WithdrawHandoff;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.Page;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.Preview;
import com.divalhr.core.payroll.api.SalaryAdvanceHandoffResponses.View;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.SalaryAdvanceHandoffService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-120B: an employee's own controlled partner handoff (ADR 0042). Subject, role {@code
 * employee}, tenant and an active membership are checked, then the request limit, before any
 * argument is read; the service binds every query to the caller's own active employee-access link.
 * Managers and platform administrators have no route here. Every response is {@code Cache-Control:
 * private, no-store}. The handoff goes to a controlled synthetic test receiver: DivalHR is not the
 * lender, nothing is a credit decision and no funds are sent.
 */
@RestController
public class MySalaryAdvanceHandoffController {

  /** Handoff of one request. */
  public static final String PATH = "/api/v1/me/salary-advance-requests/{requestId}/handoff";

  /** The caller's handoffs. */
  public static final String LIST_PATH = "/api/v1/me/salary-advance-handoffs";

  private static final String CACHE_CONTROL = "private, no-store";

  private final SalaryAdvanceHandoffService handoffs;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param handoffs employee handoff service
   * @param tenants verified tenant context
   */
  public MySalaryAdvanceHandoffController(
      SalaryAdvanceHandoffService handoffs, TenantContextResolver tenants) {
    this.handoffs = handoffs;
    this.tenants = tenants;
  }

  /**
   * Previews the closed handoff disclosure of one of the caller's employer-confirmed requests.
   *
   * @param requestId request
   * @param authentication verified employee
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewMySalaryAdvanceHandoff")
  @PostMapping(PATH + "/preview")
  @EmployeeSelfOperation(operation = SalaryAdvanceHandoffService.SELF_PREVIEW)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffService.SUBJECT_PREVIEW_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceHandoffService.TENANT_PREVIEW_BUCKET)
  public ResponseEntity<Preview> preview(
      @PathVariable("requestId") String requestId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(handoffs.preview(caller(authentication, request), requestId));
  }

  /**
   * Authorizes the handoff with the employee's explicit, versioned consent.
   *
   * @param requestId request
   * @param idempotencyKey required idempotency key
   * @param body the previewed version, digest, expiry and consent affirmation
   * @param authentication verified employee
   * @param request current request
   * @return 201 with the handoff; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "authorizeMySalaryAdvanceHandoff")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @EmployeeSelfOperation(operation = SalaryAdvanceHandoffService.SELF_AUTHORIZE)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceHandoffService.TENANT_WRITE_BUCKET)
  public ResponseEntity<View> authorize(
      @PathVariable("requestId") String requestId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitHandoff body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return replayable(
        HttpStatus.CREATED,
        handoffs.authorize(caller(authentication, request), requestId, idempotencyKey, body));
  }

  /**
   * The delivery and review status of the handoff of one of the caller's own requests.
   *
   * @param requestId request
   * @param authentication verified employee
   * @param request current request
   * @return the handoff
   */
  @Operation(operationId = "getMySalaryAdvanceHandoff")
  @GetMapping(PATH)
  @EmployeeSelfOperation(operation = SalaryAdvanceHandoffService.SELF_READ)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffService.SUBJECT_READ_BUCKET)
  public ResponseEntity<View> get(
      @PathVariable("requestId") String requestId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(handoffs.get(caller(authentication, request), requestId));
  }

  /**
   * Withdraws the handoff and request before the partner durably received it.
   *
   * @param requestId request
   * @param idempotencyKey required idempotency key
   * @param body the handoff version and the confirmation
   * @param authentication verified employee
   * @param request current request
   * @return the withdrawn handoff; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "withdrawMySalaryAdvanceHandoff")
  @PostMapping(path = PATH + "/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = SalaryAdvanceHandoffService.SELF_WITHDRAW)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceHandoffService.TENANT_WRITE_BUCKET)
  public ResponseEntity<View> withdraw(
      @PathVariable("requestId") String requestId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody WithdrawHandoff body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return replayable(
        HttpStatus.OK,
        handoffs.withdraw(caller(authentication, request), requestId, idempotencyKey, body));
  }

  /**
   * The caller's own handoffs, newest first.
   *
   * @param state optional delivery-state filter
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified employee
   * @param request current request
   * @return a page of handoffs
   */
  @Operation(operationId = "listMySalaryAdvanceHandoffs")
  @GetMapping(LIST_PATH)
  @EmployeeSelfOperation(operation = SalaryAdvanceHandoffService.SELF_LIST)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(handoffs.list(caller(authentication, request), state, cursor, limit));
  }

  private static ResponseEntity<View> replayable(
      HttpStatus status, IdempotentOperation.Result<View> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }

  private PayrollCaller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new PayrollCaller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}
