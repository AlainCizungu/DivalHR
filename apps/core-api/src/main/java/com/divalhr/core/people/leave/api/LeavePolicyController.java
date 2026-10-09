package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.leave.api.LeavePolicyResponses.Page;
import com.divalhr.core.people.leave.api.LeavePolicyResponses.Result;
import com.divalhr.core.people.leave.application.LeavePolicyService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leave policy endpoints (MVP-040A). Every handler is {@code @TenantAdminOperation}: subject, role,
 * tenant, exact MFA and active membership are checked, then the request limits, before any argument
 * or body is read. The tenant comes only from the verified token. Responses are {@code private,
 * no-store}.
 */
@RestController
@RequestMapping(LeavePolicyController.PATH)
public class LeavePolicyController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/leave-policies";

  /** Every response holds Confidential organization data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final LeavePolicyService policies;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param policies leave policy service
   * @param tenants verified tenant resolver
   */
  public LeavePolicyController(LeavePolicyService policies, TenantContextResolver tenants) {
    this.policies = policies;
    this.tenants = tenants;
  }

  /**
   * Lists the organization's leave policies by code.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listLeavePolicies")
  @GetMapping
  @TenantAdminOperation(operation = LeavePolicyService.LIST)
  @SubjectRateLimited(bucket = LeavePolicyService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(policies.list(caller(authentication, request), cursor, limit));
  }

  /**
   * Creates a leave policy with its version 1.
   *
   * @param idempotencyKey required idempotency key
   * @param body the policy
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the policy; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createLeavePolicy")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = LeavePolicyService.CREATE)
  @SubjectRateLimited(bucket = LeavePolicyService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = LeavePolicyService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Result> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateLeavePolicyRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Result> result =
        policies.create(caller(authentication, request), idempotencyKey, body);
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
