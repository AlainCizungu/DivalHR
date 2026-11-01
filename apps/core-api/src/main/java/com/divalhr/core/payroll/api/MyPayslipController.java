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
import com.divalhr.core.platform.security.EmployeeSelfOperation;
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
 * MVP-094C: an employee's own published payslips (ADR 0035 section 13). Subject, role {@code
 * employee}, tenant and an active membership are checked, then the request limit, before any
 * argument is read; the service binds every query to the caller's own active employee-access link
 * and the employee predicate is part of every statement. No other employee's payslip, employer-wide
 * total, approver or administrator note can be reached, and no notification is sent. Responses are
 * {@code Cache-Control: private, no-store}; the PDF is an attachment with a generated filename and
 * {@code X-Content-Type-Options: nosniff}. A payslip is not proof of payment.
 */
@RestController
public class MyPayslipController {

  /** Base path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/me/payslips";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PayslipService payslips;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param payslips payslip service
   * @param tenants verified tenant resolver
   */
  public MyPayslipController(PayslipService payslips, TenantContextResolver tenants) {
    this.payslips = payslips;
    this.tenants = tenants;
  }

  /**
   * The caller's own payslips, newest first.
   *
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified employee
   * @param request current request
   * @return a page of payslips
   */
  @Operation(operationId = "listMyPayslips")
  @GetMapping(PATH)
  @EmployeeSelfOperation(operation = PayslipService.SELF_READ)
  @SubjectRateLimited(bucket = PayrollPackageService.SUBJECT_READ_BUCKET)
  public ResponseEntity<SlipPage> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(payslips.listMine(caller(authentication, request), cursor, limit));
  }

  /**
   * One of the caller's own payslips.
   *
   * @param payslipId payslip
   * @param locale {@code en} or {@code fr}, or {@code null}
   * @param acceptLanguage the {@code Accept-Language} header, or {@code null}
   * @param authentication verified employee
   * @param request current request
   * @return the payslip view
   */
  @Operation(operationId = "getMyPayslip")
  @GetMapping(PATH + "/{payslipId}")
  @EmployeeSelfOperation(operation = PayslipService.SELF_READ)
  @SubjectRateLimited(bucket = PayrollPackageService.SUBJECT_READ_BUCKET)
  public ResponseEntity<SlipView> get(
      @PathVariable("payslipId") String payslipId,
      @RequestParam(name = "locale", required = false) String locale,
      @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(payslips.getMine(caller(authentication, request), payslipId, locale, acceptLanguage));
  }

  /**
   * One of the caller's own payslips as a PDF.
   *
   * @param payslipId payslip
   * @param locale {@code en} or {@code fr}, or {@code null}
   * @param acceptLanguage the {@code Accept-Language} header, or {@code null}
   * @param authentication verified employee
   * @param request current request
   * @return the PDF attachment
   */
  @Operation(operationId = "downloadMyPayslipPdf")
  @GetMapping(path = PATH + "/{payslipId}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  @EmployeeSelfOperation(operation = PayslipService.SELF_DOWNLOAD)
  @SubjectRateLimited(bucket = PayslipService.SUBJECT_DOWNLOAD_BUCKET)
  @TenantRateLimited(bucket = PayslipService.TENANT_DOWNLOAD_BUCKET)
  public ResponseEntity<byte[]> download(
      @PathVariable("payslipId") String payslipId,
      @RequestParam(name = "locale", required = false) String locale,
      @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    Download download =
        payslips.downloadMine(caller(authentication, request), payslipId, locale, acceptLanguage);
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
