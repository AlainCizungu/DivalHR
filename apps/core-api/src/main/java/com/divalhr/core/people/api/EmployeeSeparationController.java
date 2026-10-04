package com.divalhr.core.people.api;

import com.divalhr.core.people.api.SeparationResponses.Separation;
import com.divalhr.core.people.api.SeparationResponses.SeparationCancellationPreview;
import com.divalhr.core.people.api.SeparationResponses.SeparationList;
import com.divalhr.core.people.api.SeparationResponses.SeparationPreview;
import com.divalhr.core.people.api.SeparationResponses.SeparationResult;
import com.divalhr.core.people.api.SeparationResponses.Task;
import com.divalhr.core.people.application.EmployeeDirectoryService;
import com.divalhr.core.people.application.EmployeeSeparationService;
import com.divalhr.core.people.application.EmploymentChangeService;
import com.divalhr.core.people.application.PeopleCaller;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Separation endpoints (MVP-022, Issue #49). Every handler is {@code @TenantAdminOperation}:
 * subject, role, tenant, exact MFA and membership are checked, then the request limits, before any
 * argument or body is read. Every response carries or binds to Restricted HR data and is {@code
 * private, no-store}.
 */
@RestController
@RequestMapping(EmployeeSeparationController.PATH)
public class EmployeeSeparationController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/employees/{employeeId}/separations";

  private final EmployeeSeparationService separations;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param separations separation service
   * @param tenants verified tenant resolver
   */
  public EmployeeSeparationController(
      EmployeeSeparationService separations, TenantContextResolver tenants) {
    this.separations = separations;
    this.tenants = tenants;
  }

  /**
   * Lists the employee's separations.
   *
   * @param employeeId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the separations
   */
  @Operation(operationId = "listEmployeeSeparations")
  @GetMapping
  @TenantAdminOperation(operation = EmployeeSeparationService.READ)
  @SubjectRateLimited(bucket = EmployeeDirectoryService.SUBJECT_READ_BUCKET)
  public ResponseEntity<SeparationList> list(
      @PathVariable("employeeId") String employeeId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(separations.list(caller(authentication, request), employeeId));
  }

  /**
   * Previews a separation.
   *
   * @param employeeId path value
   * @param body command
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewEmployeeSeparation")
  @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = EmployeeSeparationService.PREVIEW)
  @SubjectRateLimited(bucket = EmployeeDirectoryService.SUBJECT_READ_BUCKET)
  public ResponseEntity<SeparationPreview> preview(
      @PathVariable("employeeId") String employeeId,
      @RequestBody SeparationRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(separations.preview(caller(authentication, request), employeeId, body));
  }

  /**
   * Records a previewed separation.
   *
   * @param employeeId path value
   * @param idempotencyKey required idempotency key
   * @param body command and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the separation; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createEmployeeSeparation")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = EmployeeSeparationService.CREATE)
  @SubjectRateLimited(bucket = EmploymentChangeService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = EmploymentChangeService.TENANT_WRITE_BUCKET)
  public ResponseEntity<SeparationResult> create(
      @PathVariable("employeeId") String employeeId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateSeparationRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.CREATED,
        separations.create(caller(authentication, request), employeeId, idempotencyKey, body));
  }

  /**
   * Previews the cancellation of a scheduled separation.
   *
   * @param employeeId path value
   * @param separationId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewEmployeeSeparationCancellation")
  @PostMapping("/{separationId}/cancel/preview")
  @TenantAdminOperation(operation = EmployeeSeparationService.CANCEL_PREVIEW)
  @SubjectRateLimited(bucket = EmployeeDirectoryService.SUBJECT_READ_BUCKET)
  public ResponseEntity<SeparationCancellationPreview> previewCancellation(
      @PathVariable("employeeId") String employeeId,
      @PathVariable("separationId") String separationId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(
        separations.previewCancellation(caller(authentication, request), employeeId, separationId));
  }

  /**
   * Cancels a scheduled separation as previewed.
   *
   * @param employeeId path value
   * @param separationId path value
   * @param idempotencyKey required idempotency key
   * @param body confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the separation; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "cancelEmployeeSeparation")
  @PostMapping(path = "/{separationId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = EmployeeSeparationService.CANCEL)
  @SubjectRateLimited(bucket = EmploymentChangeService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = EmploymentChangeService.TENANT_WRITE_BUCKET)
  public ResponseEntity<SeparationResult> cancel(
      @PathVariable("employeeId") String employeeId,
      @PathVariable("separationId") String separationId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CancelSeparationRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.OK,
        separations.cancel(
            caller(authentication, request), employeeId, separationId, idempotencyKey, body));
  }

  /**
   * Updates a follow-up task's status.
   *
   * @param employeeId path value
   * @param separationId path value
   * @param taskId path value
   * @param idempotencyKey required idempotency key
   * @param body status and expected version
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the task; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "updateSeparationTaskStatus")
  @PostMapping(
      path = "/{separationId}/tasks/{taskId}/status",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = EmployeeSeparationService.TASK_UPDATE)
  @SubjectRateLimited(bucket = EmploymentChangeService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = EmploymentChangeService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Task> updateTask(
      @PathVariable("employeeId") String employeeId,
      @PathVariable("separationId") String separationId,
      @PathVariable("taskId") String taskId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody UpdateSeparationTaskRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.OK,
        separations.updateTask(
            caller(authentication, request),
            employeeId,
            separationId,
            taskId,
            idempotencyKey,
            body));
  }

  /**
   * Re-queues the sign-in removal of a separation that needs manual intervention. No body.
   *
   * @param employeeId path value
   * @param separationId path value
   * @param idempotencyKey required idempotency key
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the separation; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "retrySeparationAccessRevocation")
  @PostMapping("/{separationId}/access-revocation/retry")
  @TenantAdminOperation(operation = EmployeeSeparationService.RETRY)
  @SubjectRateLimited(bucket = EmploymentChangeService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = EmploymentChangeService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Separation> retry(
      @PathVariable("employeeId") String employeeId,
      @PathVariable("separationId") String separationId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.OK,
        separations.retry(
            caller(authentication, request), employeeId, separationId, idempotencyKey));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, EmployeeImportController.CACHE_CONTROL)
        .body(body);
  }

  private static <T> ResponseEntity<T> respond(
      HttpStatus status, IdempotentOperation.Result<T> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status)
            .header(HttpHeaders.CACHE_CONTROL, EmployeeImportController.CACHE_CONTROL);
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
