package com.divalhr.core.operations.api;

import com.divalhr.core.operations.api.TaskResponses.DetailView;
import com.divalhr.core.operations.api.TaskResponses.Page;
import com.divalhr.core.operations.api.TaskResponses.Receipt;
import com.divalhr.core.operations.application.OperationsCaller;
import com.divalhr.core.operations.application.TaskService;
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
 * Administrator task endpoints (MVP-080, Issue #167). Every handler is a tenant-administrator
 * operation ({@code TenantAdminOperation}: subject, role, exact MFA, tenant and active membership
 * are checked, then the request limits, before any argument or body is read). Responses are {@code
 * private, no-store}.
 */
@RestController
@RequestMapping(TaskController.PATH)
public class TaskController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/tasks";

  /** Every response holds Confidential tenant data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final TaskService tasks;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param tasks administrator task service
   * @param tenants verified tenant resolver
   */
  public TaskController(TaskService tasks, TenantContextResolver tenants) {
    this.tasks = tasks;
    this.tenants = tenants;
  }

  /**
   * Assigns a task to one active employee.
   *
   * @param idempotencyKey required idempotency key
   * @param body the assignment
   * @param authentication verified administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createTask")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = TaskService.CREATE)
  @SubjectRateLimited(bucket = TaskService.SUBJECT_CREATE_BUCKET)
  @TenantRateLimited(bucket = TaskService.TENANT_CREATE_BUCKET)
  public ResponseEntity<Receipt> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateTaskRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Receipt> result =
        tasks.create(caller(authentication, request), idempotencyKey, body);
    return respond(HttpStatus.CREATED, result);
  }

  /**
   * The tenant's tasks.
   *
   * @param assigneeId assignee filter
   * @param state state filter
   * @param due due classification filter
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified administrator
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listTasks")
  @GetMapping
  @TenantAdminOperation(operation = TaskService.LIST)
  @SubjectRateLimited(bucket = TaskService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "assigneeId", required = false) String assigneeId,
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "due", required = false) String due,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(tasks.list(caller(authentication, request), cursor, limit, assigneeId, state, due));
  }

  /**
   * One task with its history.
   *
   * @param taskId task
   * @param authentication verified administrator
   * @param request current request
   * @return the detail, after its disclosure audit committed
   */
  @Operation(operationId = "getTask")
  @GetMapping("/{taskId}")
  @TenantAdminOperation(operation = TaskService.READ)
  @SubjectRateLimited(bucket = TaskService.SUBJECT_READ_BUCKET)
  public ResponseEntity<DetailView> read(
      @PathVariable("taskId") String taskId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(tasks.read(caller(authentication, request), taskId));
  }

  /**
   * Cancels an unfinished task.
   *
   * @param taskId task
   * @param idempotencyKey required idempotency key
   * @param body expected version, closed reason and explanation
   * @param authentication verified administrator
   * @param request current request
   * @return 200 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "cancelTask")
  @PostMapping(path = "/{taskId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TaskService.CANCEL)
  @SubjectRateLimited(bucket = TaskService.SUBJECT_TRANSITION_BUCKET)
  @TenantRateLimited(bucket = TaskService.TENANT_TRANSITION_BUCKET)
  public ResponseEntity<Receipt> cancel(
      @PathVariable("taskId") String taskId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CancelTaskRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Receipt> result =
        tasks.cancel(caller(authentication, request), taskId, idempotencyKey, body);
    return respond(HttpStatus.OK, result);
  }

  private static ResponseEntity<Receipt> respond(
      HttpStatus status, IdempotentOperation.Result<Receipt> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private OperationsCaller caller(
      JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new OperationsCaller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}
