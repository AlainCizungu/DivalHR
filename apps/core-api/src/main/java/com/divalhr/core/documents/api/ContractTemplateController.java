package com.divalhr.core.documents.api;

import com.divalhr.core.documents.api.ContractResponses.Template;
import com.divalhr.core.documents.api.ContractResponses.TemplatePage;
import com.divalhr.core.documents.api.ContractResponses.Validation;
import com.divalhr.core.documents.api.ContractResponses.Version;
import com.divalhr.core.documents.application.ContractTemplateService;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract template endpoints (MVP-030, Issue #51). Every handler is {@code @TenantAdminOperation}:
 * subject, role, tenant, exact MFA and membership are checked, then the request limits, before any
 * argument or body is read.
 */
@RestController
@RequestMapping(ContractTemplateController.PATH)
public class ContractTemplateController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/contract-templates";

  private final ContractTemplateService templates;
  private final Callers callers;

  /**
   * Creates the controller.
   *
   * @param templates template service
   * @param callers verified caller helper
   */
  public ContractTemplateController(ContractTemplateService templates, Callers callers) {
    this.templates = templates;
    this.callers = callers;
  }

  /**
   * Lists templates by code.
   *
   * @param cursor continuation
   * @param limit page size
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listContractTemplates")
  @GetMapping
  @TenantAdminOperation(operation = ContractTemplateService.LIST)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_READ_BUCKET)
  public ResponseEntity<TemplatePage> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(templates.list(callers.caller(authentication, request), cursor, limit));
  }

  /**
   * Creates a template.
   *
   * @param idempotencyKey required idempotency key
   * @param body code, name and type
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the template; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createContractTemplate")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = ContractTemplateService.CREATE)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = ContractTemplateService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Template> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateContractTemplateRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.respond(
        HttpStatus.CREATED,
        templates.create(callers.caller(authentication, request), idempotencyKey, body));
  }

  /**
   * Validates a title and body against grammar v1 without writing.
   *
   * @param body title and body
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the report
   */
  @Operation(operationId = "validateContractTemplateText")
  @PostMapping(path = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = ContractTemplateService.VALIDATE)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Validation> validate(
      @RequestBody ValidateContractTemplateTextRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(templates.validate(callers.caller(authentication, request), body));
  }

  /**
   * One template with its versions.
   *
   * @param templateId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the template
   */
  @Operation(operationId = "getContractTemplate")
  @GetMapping("/{templateId}")
  @TenantAdminOperation(operation = ContractTemplateService.READ)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Template> read(
      @PathVariable("templateId") String templateId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(templates.read(callers.caller(authentication, request), templateId));
  }

  /**
   * Creates a draft version.
   *
   * @param templateId path value
   * @param idempotencyKey required idempotency key
   * @param body language, title and body
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the draft; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createContractTemplateVersion")
  @PostMapping(path = "/{templateId}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = ContractTemplateService.VERSION_CREATE)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = ContractTemplateService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Version> createVersion(
      @PathVariable("templateId") String templateId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateContractTemplateVersionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.respond(
        HttpStatus.CREATED,
        templates.createVersion(
            callers.caller(authentication, request), templateId, idempotencyKey, body));
  }

  /**
   * One version with its text.
   *
   * @param templateId path value
   * @param versionId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the version
   */
  @Operation(operationId = "getContractTemplateVersion")
  @GetMapping("/{templateId}/versions/{versionId}")
  @TenantAdminOperation(operation = ContractTemplateService.VERSION_READ)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Version> readVersion(
      @PathVariable("templateId") String templateId,
      @PathVariable("versionId") String versionId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(
        templates.readVersion(callers.caller(authentication, request), templateId, versionId));
  }

  /**
   * Replaces a draft's text.
   *
   * @param templateId path value
   * @param versionId path value
   * @param body title, body and expected version
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the draft
   */
  @Operation(operationId = "updateContractTemplateVersion")
  @PutMapping(
      path = "/{templateId}/versions/{versionId}",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = ContractTemplateService.VERSION_UPDATE)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = ContractTemplateService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Version> updateVersion(
      @PathVariable("templateId") String templateId,
      @PathVariable("versionId") String versionId,
      @RequestBody UpdateContractTemplateVersionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(
        templates.updateVersion(
            callers.caller(authentication, request), templateId, versionId, body));
  }

  /**
   * Deletes a never-approved draft.
   *
   * @param templateId path value
   * @param versionId path value
   * @param expectedVersion expected row version
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 204
   */
  @Operation(operationId = "deleteContractTemplateVersion")
  @DeleteMapping("/{templateId}/versions/{versionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @TenantAdminOperation(operation = ContractTemplateService.VERSION_DELETE)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = ContractTemplateService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Void> deleteVersion(
      @PathVariable("templateId") String templateId,
      @PathVariable("versionId") String versionId,
      @RequestParam(name = "expectedVersion", required = false) String expectedVersion,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    templates.deleteVersion(
        callers.caller(authentication, request), templateId, versionId, expectedVersion);
    return ResponseEntity.noContent()
        .header(HttpHeaders.CACHE_CONTROL, Callers.CACHE_CONTROL)
        .build();
  }

  /**
   * Approves a draft.
   *
   * @param templateId path value
   * @param versionId path value
   * @param idempotencyKey required idempotency key
   * @param body expected version and TEXT_VERIFIED
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the version; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "approveContractTemplateVersion")
  @PostMapping(
      path = "/{templateId}/versions/{versionId}/approve",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = ContractTemplateService.APPROVE)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = ContractTemplateService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Version> approve(
      @PathVariable("templateId") String templateId,
      @PathVariable("versionId") String versionId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ApproveContractTemplateVersionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.respond(
        HttpStatus.OK,
        templates.approve(
            callers.caller(authentication, request), templateId, versionId, idempotencyKey, body));
  }

  /**
   * Retires an approved version.
   *
   * @param templateId path value
   * @param versionId path value
   * @param idempotencyKey required idempotency key
   * @param body expected version
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the version; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "retireContractTemplateVersion")
  @PostMapping(
      path = "/{templateId}/versions/{versionId}/retire",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = ContractTemplateService.RETIRE)
  @SubjectRateLimited(bucket = ContractTemplateService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = ContractTemplateService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Version> retire(
      @PathVariable("templateId") String templateId,
      @PathVariable("versionId") String versionId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody RetireContractTemplateVersionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.respond(
        HttpStatus.OK,
        templates.retire(
            callers.caller(authentication, request), templateId, versionId, idempotencyKey, body));
  }
}
