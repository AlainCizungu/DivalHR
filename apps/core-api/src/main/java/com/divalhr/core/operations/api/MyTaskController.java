package com.divalhr.core.operations.api;

import com.divalhr.core.operations.api.TaskResponses.MyDetailView;
import com.divalhr.core.operations.api.TaskResponses.MyPage;
import com.divalhr.core.operations.api.TaskResponses.Receipt;
import com.divalhr.core.operations.application.MyTaskService;
import com.divalhr.core.operations.application.OperationsCaller;
import com.divalhr.core.operations.application.TaskService;
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
 * Employee self-service task endpoints (MVP-080, Issue #167). Every handler is an employee
 * self-service operation ({@code EmployeeSelfOperation}): subject, role {@code employee}, tenant
 * and an active membership are checked, then the request limits, before any argument or body is
 * read; the service binds every query to the caller's own active employee-access link. Responses
 * are {@code private, no-store}.
 */
@RestController
@RequestMapping(MyTaskController.PATH)
public class MyTaskController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/me/tasks";

  private static final String CACHE_CONTROL = "private, no-store";

  private final MyTaskService tasks;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param tasks self-service task service
   * @param tenants verified tenant resolver
   */
  public MyTaskController(MyTaskService tasks, TenantContextResolver tenants) {
    this.tasks = tasks;
    this.tenants = tenants;
  }

  /**
   * The caller's own tasks.
   *
   * @param state state filter
   * @param due due classification filter
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listMyTasks")
  @GetMapping
  @EmployeeSelfOperation(operation = MyTaskService.LIST)
  @SubjectRateLimited(bucket = MyTaskService.SUBJECT_READ_BUCKET)
  public ResponseEntity<MyPage> list(
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "due", required = false) String due,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(tasks.list(caller(authentication, request), cursor, limit, state, due));
  }

  /**
   * One of the caller's own tasks with its history.
   *
   * @param taskId task
   * @param authentication verified employee
   * @param request current request
   * @return the detail, after its disclosure audit committed
   */
  @Operation(operationId = "getMyTask")
  @GetMapping("/{taskId}")
  @EmployeeSelfOperation(operation = MyTaskService.READ)
  @SubjectRateLimited(bucket = MyTaskService.SUBJECT_READ_BUCKET)
  public ResponseEntity<MyDetailView> read(
      @PathVariable("taskId") String taskId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(tasks.read(caller(authentication, request), taskId));
  }

  /**
   * Starts one of the caller's own open tasks.
   *
   * @param taskId task
   * @param idempotencyKey required idempotency key
   * @param body expected version
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "startMyTask")
  @PostMapping(path = "/{taskId}/start", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = MyTaskService.START)
  @SubjectRateLimited(bucket = TaskService.SUBJECT_TRANSITION_BUCKET)
  @TenantRateLimited(bucket = TaskService.TENANT_TRANSITION_BUCKET)
  public ResponseEntity<Receipt> start(
      @PathVariable("taskId") String taskId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody StartTaskRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(tasks.start(caller(authentication, request), taskId, idempotencyKey, body));
  }

  /**
   * Completes one of the caller's own tasks.
   *
   * @param taskId task
   * @param idempotencyKey required idempotency key
   * @param body expected version and optional note
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "completeMyTask")
  @PostMapping(path = "/{taskId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = MyTaskService.COMPLETE)
  @SubjectRateLimited(bucket = TaskService.SUBJECT_TRANSITION_BUCKET)
  @TenantRateLimited(bucket = TaskService.TENANT_TRANSITION_BUCKET)
  public ResponseEntity<Receipt> complete(
      @PathVariable("taskId") String taskId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CompleteTaskRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(tasks.complete(caller(authentication, request), taskId, idempotencyKey, body));
  }

  private static ResponseEntity<Receipt> respond(IdempotentOperation.Result<Receipt> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
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
