package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.StatutoryResponses.ProfilePreview;
import com.divalhr.core.payroll.api.StatutoryResponses.ProfileReceipt;
import com.divalhr.core.payroll.api.StatutoryResponses.ProfileView;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.StatutoryPackService;
import com.divalhr.core.payroll.application.StatutoryProfileService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-094B: the effective-dated statutory profile of an employee (classification, CNSS and IRPP
 * subject flags, a closed evidence code), for tenant administrators with privileged MFA (ADR
 * 0034). Restricted HR data: every response is {@code Cache-Control: private, no-store}.
 */
@RestController
public class StatutoryProfileController {

  /** Base path of an employee's statutory profile. */
  public static final String PATH = "/api/v1/employees/{employeeId}/statutory-profile";

  private static final String CACHE_CONTROL = "private, no-store";

  private final StatutoryProfileService profiles;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param profiles profile service
   * @param tenants verified tenant context
   */
  public StatutoryProfileController(
      StatutoryProfileService profiles, TenantContextResolver tenants) {
    this.profiles = profiles;
    this.tenants = tenants;
  }

  /**
   * The employee's profile timeline.
   *
   * @param employeeId employee
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the timeline
   */
  @Operation(operationId = "getStatutoryProfile")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = StatutoryProfileService.READ)
  @SubjectRateLimited(bucket = StatutoryPackService.SUBJECT_READ_BUCKET)
  public ResponseEntity<ProfileView> get(
      @PathVariable("employeeId") String employeeId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(profiles.read(caller(authentication, request), employeeId));
  }

  /**
   * Previews adding a profile entry.
   *
   * @param employeeId employee
   * @param body the entry
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewStatutoryProfile")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = StatutoryProfileService.PREVIEW)
  @SubjectRateLimited(bucket = StatutoryProfileService.SUBJECT_PREVIEW_BUCKET)
  public ResponseEntity<ProfilePreview> preview(
      @PathVariable("employeeId") String employeeId,
      @RequestBody StatutoryProfileCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(profiles.preview(caller(authentication, request), employeeId, body));
  }

  /**
   * Records a previewed profile entry.
   *
   * @param employeeId employee
   * @param idempotencyKey required idempotency key
   * @param body the previewed entry with its digest
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "commitStatutoryProfile")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = StatutoryProfileService.CHANGE)
  @SubjectRateLimited(bucket = StatutoryProfileService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<ProfileReceipt> commit(
      @PathVariable("employeeId") String employeeId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitStatutoryProfileRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<ProfileReceipt> result =
        profiles.commit(caller(authentication, request), employeeId, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
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
