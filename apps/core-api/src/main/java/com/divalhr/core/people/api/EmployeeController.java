package com.divalhr.core.people.api;

import com.divalhr.core.people.api.EmploymentHistoryResponses.AssignmentPage;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmployeePage;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmployeeProfile;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmploymentChangeCancellationPreview;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmploymentChangePage;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmploymentChangePreview;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmploymentChangeResult;
import com.divalhr.core.people.application.EmployeeDirectoryService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee directory and employment history endpoints (MVP-021, issue #47). Every handler is
 * {@code @TenantAdminOperation}: subject, role, tenant, exact MFA and membership are checked, then
 * the request limits, before any argument or body is read. Every response carries or binds to
 * Confidential or Restricted HR data and is {@code private, no-store}.
 */
@RestController
@RequestMapping(EmployeeController.PATH)
public class EmployeeController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/employees";

  private final EmployeeDirectoryService directory;
  private final EmploymentChangeService changes;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param directory reads
   * @param changes previews, changes and cancellations
   * @param tenants verified tenant resolver
   */
  public EmployeeController(
      EmployeeDirectoryService directory,
      EmploymentChangeService changes,
      TenantContextResolver tenants) {
    this.directory = directory;
    this.changes = changes;
    this.tenants = tenants;
  }

  /**
   * Lists employees by employee number.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page
   */
  @Operation(operationId = "listEmployees")
  @GetMapping
  @TenantAdminOperation(operation = EmployeeDirectoryService.LIST)
  @SubjectRateLimited(bucket = EmployeeDirectoryService.SUBJECT_READ_BUCKET)
  public ResponseEntity<EmployeePage> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(directory.list(caller(authentication, request), cursor, limit));
  }

  /**
   * Searches employees.
   *
   * @param body search
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page
   */
  @Operation(operationId = "searchEmployees")
  @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = EmployeeDirectoryService.SEARCH)
  @SubjectRateLimited(bucket = EmployeeDirectoryService.SUBJECT_READ_BUCKET)
  @TenantRateLimited(bucket = EmployeeDirectoryService.TENANT_SEARCH_BUCKET)
  public ResponseEntity<EmployeePage> search(
      @RequestBody EmployeeSearchRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(directory.search(caller(authentication, request), body));
  }

  /**
   * Reads an employee's profile.
   *
   * @param employeeId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the profile
   */
  @Operation(operationId = "getEmployee")
  @GetMapping("/{employeeId}")
  @TenantAdminOperation(operation = EmployeeDirectoryService.READ)
  @SubjectRateLimited(bucket = EmployeeDirectoryService.SUBJECT_READ_BUCKET)
  public ResponseEntity<EmployeeProfile> read(
      @PathVariable("employeeId") String employeeId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(directory.read(caller(authentication, request), employeeId));
  }

  /**
   * Lists an employee's assignment rows.
   *
   * @param employeeId path value
   * @param kind kind filter
   * @param includeSuperseded whether superseded rows are listed
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page
   */
  @Operation(operationId = "listEmploymentTimeline")
  @GetMapping("/{employeeId}/timeline")
  @TenantAdminOperation(operation = EmployeeDirectoryService.TIMELINE)
  @SubjectRateLimited(bucket = EmployeeDirectoryService.SUBJECT_READ_BUCKET)
  public ResponseEntity<AssignmentPage> timeline(
      @PathVariable("employeeId") String employeeId,
      @RequestParam(name = "kind", required = false) String kind,
      @RequestParam(name = "includeSuperseded", required = false) String includeSuperseded,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(
        directory.timeline(
            caller(authentication, request), employeeId, kind, includeSuperseded, cursor, limit));
  }

  /**
   * Lists an employee's recorded changes.
   *
   * @param employeeId path value
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page
   */
  @Operation(operationId = "listEmploymentChanges")
  @GetMapping("/{employeeId}/employment-changes")
  @TenantAdminOperation(operation = EmployeeDirectoryService.CHANGES)
  @SubjectRateLimited(bucket = EmployeeDirectoryService.SUBJECT_READ_BUCKET)
  public ResponseEntity<EmploymentChangePage> changes(
      @PathVariable("employeeId") String employeeId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(directory.changes(caller(authentication, request), employeeId, cursor, limit));
  }

  /**
   * Previews a change or correction.
   *
   * @param employeeId path value
   * @param body command
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewEmploymentChange")
  @PostMapping(
      path = "/{employeeId}/employment-changes/preview",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = EmploymentChangeService.PREVIEW)
  @SubjectRateLimited(bucket = EmployeeDirectoryService.SUBJECT_READ_BUCKET)
  public ResponseEntity<EmploymentChangePreview> preview(
      @PathVariable("employeeId") String employeeId,
      @RequestBody EmploymentChangeRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(changes.preview(caller(authentication, request), employeeId, body));
  }

  /**
   * Records a previewed change or correction.
   *
   * @param employeeId path value
   * @param idempotencyKey required idempotency key
   * @param body command and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the change; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createEmploymentChange")
  @PostMapping(
      path = "/{employeeId}/employment-changes",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = EmploymentChangeService.CREATE)
  @SubjectRateLimited(bucket = EmploymentChangeService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = EmploymentChangeService.TENANT_WRITE_BUCKET)
  public ResponseEntity<EmploymentChangeResult> create(
      @PathVariable("employeeId") String employeeId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateEmploymentChangeRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.CREATED,
        changes.create(caller(authentication, request), employeeId, idempotencyKey, body));
  }

  /**
   * Previews the cancellation of a scheduled change.
   *
   * @param employeeId path value
   * @param changeId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewEmploymentChangeCancellation")
  @PostMapping("/{employeeId}/employment-changes/{changeId}/cancel/preview")
  @TenantAdminOperation(operation = EmploymentChangeService.CANCEL_PREVIEW)
  @SubjectRateLimited(bucket = EmployeeDirectoryService.SUBJECT_READ_BUCKET)
  public ResponseEntity<EmploymentChangeCancellationPreview> previewCancellation(
      @PathVariable("employeeId") String employeeId,
      @PathVariable("changeId") String changeId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(changes.previewCancellation(caller(authentication, request), employeeId, changeId));
  }

  /**
   * Cancels a scheduled change as previewed.
   *
   * @param employeeId path value
   * @param changeId path value
   * @param idempotencyKey required idempotency key
   * @param body confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the cancellation; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "cancelEmploymentChange")
  @PostMapping(
      path = "/{employeeId}/employment-changes/{changeId}/cancel",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = EmploymentChangeService.CANCEL)
  @SubjectRateLimited(bucket = EmploymentChangeService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = EmploymentChangeService.TENANT_WRITE_BUCKET)
  public ResponseEntity<EmploymentChangeResult> cancel(
      @PathVariable("employeeId") String employeeId,
      @PathVariable("changeId") String changeId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CancelEmploymentChangeRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.OK,
        changes.cancel(
            caller(authentication, request), employeeId, changeId, idempotencyKey, body));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, EmployeeImportController.CACHE_CONTROL)
        .body(body);
  }

  private static ResponseEntity<EmploymentChangeResult> respond(
      HttpStatus status, IdempotentOperation.Result<EmploymentChangeResult> result) {
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
