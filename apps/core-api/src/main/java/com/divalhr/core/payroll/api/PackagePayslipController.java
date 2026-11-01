package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PayrollPackageResponses.SlipPage;
import com.divalhr.core.payroll.api.PayrollPackageResponses.SlipView;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.PayrollPackageService;
import com.divalhr.core.payroll.application.PayslipService;
import com.divalhr.core.payroll.application.PayslipService.Download;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-094C: the payslips of a payroll package for tenant administrators with privileged MFA (ADR
 * 0035): the list, the accessible HTML-primary view and the deterministic PDF. The tenant comes
 * from the verified JWT; every response is {@code Cache-Control: private, no-store}. The PDF is an
 * attachment with a generated filename ({@code payslip-YYYY-MM-xxxxxxxx.pdf}, never a name or an
 * employee number) and {@code X-Content-Type-Options: nosniff}. A payslip is not proof of payment.
 */
@RestController
public class PackagePayslipController {

  /** Base path of a package's payslips. */
  public static final String PATH =
      "/api/v1/legal-entities/{legalEntityId}/payroll-packages/{packageId}/payslips";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PayslipService payslips;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param payslips payslip service
   * @param tenants verified tenant context
   */
  public PackagePayslipController(PayslipService payslips, TenantContextResolver tenants) {
    this.payslips = payslips;
    this.tenants = tenants;
  }

  /**
   * The payslips of a package.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of payslips
   */
  @Operation(operationId = "listPackagePayslips")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = PayslipService.READ)
  @SubjectRateLimited(bucket = PayrollPackageService.SUBJECT_READ_BUCKET)
  public ResponseEntity<SlipPage> list(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("packageId") String packageId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            payslips.listPackage(
                caller(authentication, request), legalEntityId, packageId, cursor, limit));
  }

  /**
   * One payslip of a package.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param payslipId payslip
   * @param locale {@code en} or {@code fr}, or {@code null}
   * @param acceptLanguage the {@code Accept-Language} header, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the payslip view
   */
  @Operation(operationId = "getPackagePayslip")
  @GetMapping(PATH + "/{payslipId}")
  @TenantAdminOperation(operation = PayslipService.READ)
  @SubjectRateLimited(bucket = PayrollPackageService.SUBJECT_READ_BUCKET)
  public ResponseEntity<SlipView> get(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("packageId") String packageId,
      @PathVariable("payslipId") String payslipId,
      @RequestParam(name = "locale", required = false) String locale,
      @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            payslips.getPackage(
                caller(authentication, request),
                legalEntityId,
                packageId,
                payslipId,
                locale,
                acceptLanguage));
  }

  /**
   * One payslip of a package as a PDF.
   *
   * @param legalEntityId legal entity
   * @param packageId package
   * @param payslipId payslip
   * @param locale {@code en} or {@code fr}, or {@code null}
   * @param acceptLanguage the {@code Accept-Language} header, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the PDF attachment
   */
  @Operation(operationId = "downloadPackagePayslipPdf")
  @GetMapping(path = PATH + "/{payslipId}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  @TenantAdminOperation(operation = PayslipService.DOWNLOAD)
  @SubjectRateLimited(bucket = PayslipService.SUBJECT_DOWNLOAD_BUCKET)
  @TenantRateLimited(bucket = PayslipService.TENANT_DOWNLOAD_BUCKET)
  public ResponseEntity<byte[]> download(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("packageId") String packageId,
      @PathVariable("payslipId") String payslipId,
      @RequestParam(name = "locale", required = false) String locale,
      @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return attachment(
        payslips.downloadPackage(
            caller(authentication, request),
            legalEntityId,
            packageId,
            payslipId,
            locale,
            acceptLanguage));
  }

  private static ResponseEntity<byte[]> attachment(Download download) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(download.filename()).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.APPLICATION_PDF)
        .body(download.pdf().bytes());
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
