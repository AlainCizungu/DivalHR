package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.TimesheetExportResponses.Page;
import com.divalhr.core.people.schedule.api.TimesheetExportResponses.Preview;
import com.divalhr.core.people.schedule.api.TimesheetExportResponses.Result;
import com.divalhr.core.people.schedule.api.TimesheetExportResponses.Summary;
import com.divalhr.core.people.schedule.application.TimesheetExportService;
import com.divalhr.core.people.schedule.application.TimesheetExportService.Download;
import com.divalhr.core.people.schedule.application.WorkScheduleService;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-070E approved-time exports (Issue #134, ADR 0021): tenant administrators with exact MFA
 * preview, create, list, read and download exports of their own tenant. Every response is {@code
 * private, no-store}; downloads are attachments.
 */
@RestController
public class TimesheetExportController {

  /** The administrators' paths (full public path; server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/timesheet-exports";

  /** Every response holds Restricted HR/Operations data. */
  static final String CACHE_CONTROL = "private, no-store";

  private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

  private final TimesheetExportService exports;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param exports the export service
   * @param tenants verified tenant resolver
   */
  public TimesheetExportController(TimesheetExportService exports, TenantContextResolver tenants) {
    this.exports = exports;
    this.tenants = tenants;
  }

  /**
   * Previews the export of a legal entity's site and generated week; writes nothing but its
   * disclosure audit record.
   *
   * @param body legal entity, site and week
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewTimesheetExport")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TimesheetExportService.PREVIEW)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<Preview> preview(
      @RequestBody TimesheetExportPreviewRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(exports.preview(caller(authentication, request), body));
  }

  /**
   * Creates the export of a reviewed preview, or returns the existing export of identical evidence.
   *
   * @param idempotencyKey {@code Idempotency-Key}
   * @param body scope, preview digest and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the result, or the replayed original
   */
  @Operation(operationId = "createTimesheetExport")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TimesheetExportService.CREATE)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Result> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateTimesheetExportRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Result> result =
        exports.create(caller(authentication, request), idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * One page of the tenant's exports, newest first.
   *
   * @param siteId optional site filter
   * @param weekStart optional week filter (a Monday)
   * @param cursor opaque cursor of the next page
   * @param limit page size (1 to 100, default 20)
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listTimesheetExports")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = TimesheetExportService.LIST)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "siteId", required = false) String siteId,
      @RequestParam(name = "weekStart", required = false) String weekStart,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(exports.list(caller(authentication, request), siteId, weekStart, cursor, limit));
  }

  /**
   * One export: the manifest's facts.
   *
   * @param exportId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the export
   */
  @Operation(operationId = "getTimesheetExport")
  @GetMapping(PATH + "/{exportId}")
  @TenantAdminOperation(operation = TimesheetExportService.READ)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Summary> export(
      @PathVariable("exportId") String exportId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(exports.export(caller(authentication, request), exportId));
  }

  /**
   * Downloads the export's CSV.
   *
   * @param exportId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the CSV attachment
   */
  @Operation(operationId = "downloadTimesheetExportCsv")
  @GetMapping(path = PATH + "/{exportId}/csv", produces = "text/csv")
  @TenantAdminOperation(operation = TimesheetExportService.DOWNLOAD)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<byte[]> csv(
      @PathVariable("exportId") String exportId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return attachment(exports.csv(caller(authentication, request), exportId), CSV);
  }

  /**
   * Downloads the export's manifest.
   *
   * @param exportId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the manifest attachment
   */
  @Operation(operationId = "downloadTimesheetExportManifest")
  @GetMapping(path = PATH + "/{exportId}/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TimesheetExportService.DOWNLOAD)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<byte[]> manifest(
      @PathVariable("exportId") String exportId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return attachment(
        exports.manifest(caller(authentication, request), exportId), MediaType.APPLICATION_JSON);
  }

  private static ResponseEntity<byte[]> attachment(Download download, MediaType type) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(download.filename()).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(type)
        .body(download.bytes());
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
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
