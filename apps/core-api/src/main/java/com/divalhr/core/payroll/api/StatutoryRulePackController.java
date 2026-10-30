package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.StatutoryResponses.PackDetail;
import com.divalhr.core.payroll.api.StatutoryResponses.PackPage;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.StatutoryPackService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-094B: the platform's statutory rule packs, read-only, for tenant administrators with
 * privileged MFA (ADR 0034). There is no operation that creates, edits or verifies a pack. Every
 * response is {@code Cache-Control: private, no-store}.
 */
@RestController
public class StatutoryRulePackController {

  /** Base path of the rule packs. */
  public static final String PATH = "/api/v1/statutory-rule-packs";

  private static final String CACHE_CONTROL = "private, no-store";

  private final StatutoryPackService packs;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param packs rule pack service
   * @param tenants verified tenant context
   */
  public StatutoryRulePackController(StatutoryPackService packs, TenantContextResolver tenants) {
    this.packs = packs;
    this.tenants = tenants;
  }

  /**
   * The rule packs, newest effective date first.
   *
   * @param status optional status filter
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of packs
   */
  @Operation(operationId = "listStatutoryRulePacks")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = StatutoryPackService.READ)
  @SubjectRateLimited(bucket = StatutoryPackService.SUBJECT_READ_BUCKET)
  public ResponseEntity<PackPage> list(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(packs.list(caller(authentication, request), status, cursor, limit));
  }

  /**
   * One rule pack with its sources, rules and verification evidence.
   *
   * @param packId rule pack
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the pack
   */
  @Operation(operationId = "getStatutoryRulePack")
  @GetMapping(PATH + "/{packId}")
  @TenantAdminOperation(operation = StatutoryPackService.READ)
  @SubjectRateLimited(bucket = StatutoryPackService.SUBJECT_READ_BUCKET)
  public ResponseEntity<PackDetail> get(
      @PathVariable("packId") String packId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(packs.get(caller(authentication, request), packId));
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
