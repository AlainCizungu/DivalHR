package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.ShiftPage;
import com.divalhr.core.people.schedule.application.MyWorkScheduleService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The employee's own published shifts (MVP-050A, Issue #99). */
@RestController
@RequestMapping(MyWorkScheduleController.PATH)
public class MyWorkScheduleController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/me/work-schedule";

  /** Every response holds Restricted HR/Operations data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final MyWorkScheduleService schedule;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param schedule own-schedule service
   * @param tenants verified tenant resolver
   */
  public MyWorkScheduleController(MyWorkScheduleService schedule, TenantContextResolver tenants) {
    this.schedule = schedule;
    this.tenants = tenants;
  }

  /**
   * The caller's own published shifts, in start order.
   *
   * @param from optional first start date (default: the business date)
   * @param to optional last start date
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listMyWorkSchedule")
  @GetMapping
  @EmployeeSelfOperation(operation = MyWorkScheduleService.SELF_LIST)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<ShiftPage> list(
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(schedule.list(caller(authentication, request), from, to, cursor, limit));
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
