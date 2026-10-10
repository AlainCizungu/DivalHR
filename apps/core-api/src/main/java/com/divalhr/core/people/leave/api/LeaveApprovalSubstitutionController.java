package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.leave.api.LeaveApprovalSubstitutionResponses.Page;
import com.divalhr.core.people.leave.api.LeaveApprovalSubstitutionResponses.Receipt;
import com.divalhr.core.people.leave.api.LeaveApprovalSubstitutionResponses.RevocationReceipt;
import com.divalhr.core.people.leave.application.LeaveApprovalService;
import com.divalhr.core.people.leave.application.LeaveSubstitutionService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-administrator leave approval substitute endpoints (MVP-041G, Issue #97). Every handler is
 * {@code @TenantAdminOperation}: subject, role, tenant, exact MFA and active membership are
 * checked, then the request limits (the leave-approval buckets, reused), before any argument or
 * body is read. No input names a tenant or actor. Responses are {@code private, no-store}.
 */
@RestController
@RequestMapping(LeaveApprovalSubstitutionController.PATH)
public class LeaveApprovalSubstitutionController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/leave-approval-substitutions";

  /** Every response holds Restricted HR data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final LeaveSubstitutionService substitutions;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param substitutions leave approval substitute service
   * @param tenants verified tenant resolver
   */
  public LeaveApprovalSubstitutionController(
      LeaveSubstitutionService substitutions, TenantContextResolver tenants) {
    this.substitutions = substitutions;
    this.tenants = tenants;
  }

  /**
   * Appoints one effective-dated substitute for a manager.
   *
   * @param idempotencyKey required idempotency key
   * @param body manager, substitute, inclusive dates, reason locale and reason
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createLeaveApprovalSubstitution")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = LeaveSubstitutionService.CREATE)
  @SubjectRateLimited(bucket = LeaveApprovalService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = LeaveApprovalService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Receipt> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateLeaveApprovalSubstitutionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return created(substitutions.create(caller(authentication, request), idempotencyKey, body));
  }

  /**
   * The tenant's appointments, newest first, with their states on the business date.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listLeaveApprovalSubstitutions")
  @GetMapping
  @TenantAdminOperation(operation = LeaveSubstitutionService.LIST)
  @SubjectRateLimited(bucket = LeaveApprovalService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(substitutions.list(caller(authentication, request), cursor, limit));
  }

  /**
   * Revokes a scheduled, active or expired appointment, effective immediately; decisions already
   * taken are unchanged.
   *
   * @param substitutionId the appointment
   * @param idempotencyKey required idempotency key
   * @param body reason locale and reason
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "revokeLeaveApprovalSubstitution")
  @PostMapping(path = "/{substitutionId}/revocation", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = LeaveSubstitutionService.REVOKE)
  @SubjectRateLimited(bucket = LeaveApprovalService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = LeaveApprovalService.TENANT_WRITE_BUCKET)
  public ResponseEntity<RevocationReceipt> revoke(
      @PathVariable("substitutionId") String substitutionId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody RevokeLeaveApprovalSubstitutionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return created(
        substitutions.revoke(
            caller(authentication, request), substitutionId, idempotencyKey, body));
  }

  private static <R> ResponseEntity<R> created(IdempotentOperation.Result<R> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private PeopleCaller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new PeopleCaller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}
