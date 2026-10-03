package com.divalhr.core.people.api;

import com.divalhr.core.people.application.EmployeeImportCommitService;
import com.divalhr.core.people.application.EmployeeImportService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
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
 * Employee import endpoints (MVP-020, issue #45). Every handler is {@code @TenantAdminOperation}:
 * subject, role, tenant, exact MFA and membership are checked, then the request limits, before any
 * argument or body is read. The upload body is the CSV itself ({@code text/csv}); its media type is
 * checked only after authorization, and it is read as a bounded stream, never bound or buffered by
 * the framework. Responses that carry or bind to personal data are {@code private, no-store}.
 */
@RestController
@RequestMapping(EmployeeImportController.PATH)
public class EmployeeImportController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/employee-imports";

  /** Cache policy of every response that carries or binds to personal data. */
  public static final String CACHE_CONTROL = "private, no-store";

  private final EmployeeImportService imports;
  private final EmployeeImportCommitService commits;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param imports upload, preview, status, discard and template
   * @param commits commit
   * @param tenants verified tenant resolver
   */
  public EmployeeImportController(
      EmployeeImportService imports,
      EmployeeImportCommitService commits,
      TenantContextResolver tenants) {
    this.imports = imports;
    this.commits = commits;
    this.tenants = tenants;
  }

  /**
   * Downloads the header-only template.
   *
   * @param lang {@code fr} or {@code en}
   * @return the CSV
   */
  @Operation(operationId = "getEmployeeImportTemplate")
  @GetMapping(path = "/template", produces = "text/csv")
  @TenantAdminOperation(operation = EmployeeImportService.TEMPLATE)
  public ResponseEntity<byte[]> template(
      @RequestParam(name = "lang", required = false) String lang) {
    byte[] csv = imports.template(lang);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"divalhr-employee-import-" + lang + ".csv\"")
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .body(csv);
  }

  /**
   * Uploads and validates a CSV.
   *
   * @param idempotencyKey required idempotency key
   * @param authentication verified tenant administrator
   * @param servletRequest current request (media type, body stream, correlation ID)
   * @return 201 with the import; replays carry {@code Idempotent-Replayed: true}
   * @throws IOException when the body stream cannot be opened
   */
  @Operation(operationId = "createEmployeeImport")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = EmployeeImportService.CREATE)
  @SubjectRateLimited(bucket = EmployeeImportService.SUBJECT_BUCKET)
  @TenantRateLimited(bucket = EmployeeImportService.TENANT_UPLOAD_BUCKET)
  public ResponseEntity<EmployeeImportResponse> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest)
      throws IOException {
    requireCsv(servletRequest.getContentType());
    try (InputStream body = servletRequest.getInputStream()) {
      IdempotentOperation.Result<EmployeeImportResponse> result =
          imports.create(
              tenants.current().tenantId(),
              subject(authentication),
              idempotencyKey,
              body,
              correlationId(servletRequest));
      return respond(HttpStatus.CREATED, result);
    }
  }

  /**
   * Reads an import's status and counts.
   *
   * @param importId path value
   * @return the import
   */
  @Operation(operationId = "getEmployeeImport")
  @GetMapping("/{importId}")
  @TenantAdminOperation(operation = EmployeeImportService.READ)
  public ResponseEntity<EmployeeImportResponse> read(@PathVariable("importId") String importId) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(imports.read(tenants.current().tenantId(), importId));
  }

  /**
   * Previews an import's rows.
   *
   * @param importId path value
   * @param status {@code all}, {@code valid} or {@code invalid}
   * @param cursor opaque cursor
   * @param limit page size
   * @return a page
   */
  @Operation(operationId = "listEmployeeImportRows")
  @GetMapping("/{importId}/rows")
  @TenantAdminOperation(operation = EmployeeImportService.ROWS)
  public ResponseEntity<EmployeeImportRowPageResponse> rows(
      @PathVariable("importId") String importId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(imports.rows(tenants.current().tenantId(), importId, status, cursor, limit));
  }

  /**
   * Commits the valid rows.
   *
   * @param importId path value
   * @param idempotencyKey required idempotency key
   * @param request body
   * @param authentication verified tenant administrator
   * @param servletRequest current request
   * @return 200 with the committed import; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "commitEmployeeImport")
  @PostMapping(path = "/{importId}/commit", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = EmployeeImportCommitService.COMMIT)
  @SubjectRateLimited(bucket = EmployeeImportService.SUBJECT_BUCKET)
  @TenantRateLimited(bucket = EmployeeImportService.TENANT_COMMIT_BUCKET)
  public ResponseEntity<EmployeeImportResponse> commit(
      @PathVariable("importId") String importId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitEmployeeImportRequest request,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    return respond(
        HttpStatus.OK,
        commits.commit(
            tenants.current().tenantId(),
            subject(authentication),
            importId,
            idempotencyKey,
            request,
            correlationId(servletRequest)));
  }

  /**
   * Discards an open import.
   *
   * @param importId path value
   * @param authentication verified tenant administrator
   * @param servletRequest current request
   * @return the discarded import
   */
  @Operation(operationId = "discardEmployeeImport")
  @PostMapping("/{importId}/discard")
  @TenantAdminOperation(operation = EmployeeImportService.DISCARD)
  public ResponseEntity<EmployeeImportResponse> discard(
      @PathVariable("importId") String importId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            imports.discard(
                tenants.current().tenantId(),
                subject(authentication),
                importId,
                correlationId(servletRequest)));
  }

  /** The body must be {@code text/csv}; multipart and everything else are refused unread. */
  private static void requireCsv(String contentType) {
    boolean csv = false;
    if (contentType != null) {
      try {
        MediaType type = MediaType.parseMediaType(contentType);
        csv = "text".equals(type.getType()) && "csv".equals(type.getSubtype());
      } catch (InvalidMediaTypeException malformed) {
        csv = false;
      }
    }
    if (!csv) {
      new FieldErrors().add("Content-Type", FieldErrors.Constraint.FORMAT).throwIfAny();
    }
  }

  private static ResponseEntity<EmployeeImportResponse> respond(
      HttpStatus status, IdempotentOperation.Result<EmployeeImportResponse> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private static String subject(JwtAuthenticationToken authentication) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return subject;
  }

  private static String correlationId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE));
  }
}
