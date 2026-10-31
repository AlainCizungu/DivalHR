package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PayrollPackageResponses.PackageDetail;
import com.divalhr.core.payroll.api.PayrollPackageResponses.PackagePage;
import com.divalhr.core.payroll.api.PayrollPackageResponses.VoidPreview;
import com.divalhr.core.payroll.api.PayrollPackageResponses.VoidReceipt;
import com.divalhr.core.payroll.application.PayrollApprovalService;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.PayrollPackageService;
import com.divalhr.core.payroll.application.PayrollVoidService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-094C: a legal entity's approved payroll packages and their voiding, for tenant administrators
 * with privileged MFA (ADR 0035). The tenant comes from the verified JWT and the voider is the
 * verified subject (never the body or the path); every response is {@code Cache-Control: private,
 * no-store}. The approver subject and the void explanation of a summary or a receipt are never
 * returned.
 */
@RestController
public class PayrollPackageController {

  /** Base path of a legal entity's packages. */
  public static final String PATH = "/api/v1/legal-entities/{legalEntityId}/payroll-packages";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PayrollPackageService packages;
  private final PayrollVoidService voids;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param packages package reads
   * @param voids void service
   * @param tenants verified tenant context
   */
  public PayrollPackageController(
      PayrollPackageService packages, PayrollVoidService voids, TenantContextResolver tenants) {
    this.packages = packages;
    this.voids = voids;
    this.tenants = tenants;
  }

  /**
   * The packages of a legal entity.
   *
   * @param legalEntityId legal entity
   * @param status optional status filter
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of packages
   */
  @Operation(operationId = "listPayrollPackages")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = PayrollPackageService.READ)
  @SubjectRateLimited(bucket = PayrollPackageService.SUBJECT_READ_BUCKET)
  public ResponseEntity<PackagePage> list(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(packages.list(caller(authentication, request), legalEntityId, status, cursor, limit));
  }

  /**
   * One package with its evidence.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the package
   */
  @Operation(operationId = "getPayrollPackage")
  @GetMapping(PATH + "/{packageId}")
  @TenantAdminOperation(operation = PayrollPackageService.READ)
  @SubjectRateLimited(bucket = PayrollPackageService.SUBJECT_READ_BUCKET)
  public ResponseEntity<PackageDetail> get(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("packageId") String packageId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(packages.get(caller(authentication, request), legalEntityId, packageId));
  }

  /**
   * Previews voiding an approved package.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param body the reason and explanation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewPayrollVoid")
  @PostMapping(
      path = PATH + "/{packageId}/void/preview",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PayrollVoidService.PREVIEW)
  @SubjectRateLimited(bucket = PayrollApprovalService.SUBJECT_PREVIEW_BUCKET)
  @TenantRateLimited(bucket = PayrollApprovalService.TENANT_PREVIEW_BUCKET)
  public ResponseEntity<VoidPreview> previewVoid(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("packageId") String packageId,
      @RequestBody PayrollVoidCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(voids.preview(caller(authentication, request), legalEntityId, packageId, body));
  }

  /**
   * Voids a previewed package.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param idempotencyKey required idempotency key
   * @param body the evidence the preview returned and the no-payment confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "commitPayrollVoid")
  @PostMapping(path = PATH + "/{packageId}/void", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = PayrollVoidService.COMMIT)
  @SubjectRateLimited(bucket = PayrollApprovalService.SUBJECT_COMMIT_BUCKET)
  @TenantRateLimited(bucket = PayrollApprovalService.TENANT_COMMIT_BUCKET)
  public ResponseEntity<VoidReceipt> voidPackage(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("packageId") String packageId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitPayrollVoidRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<VoidReceipt> result =
        voids.voidPackage(
            caller(authentication, request), legalEntityId, packageId, idempotencyKey, body);
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
