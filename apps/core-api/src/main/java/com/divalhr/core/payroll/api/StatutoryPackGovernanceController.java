package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.StatutoryPackResponses.CommandReceipt;
import com.divalhr.core.payroll.api.StatutoryPackResponses.VersionDetail;
import com.divalhr.core.payroll.api.StatutoryPackResponses.VersionPage;
import com.divalhr.core.payroll.application.StatutoryPackGovernanceService;
import com.divalhr.core.payroll.application.StatutoryPackGovernanceService.Action;
import com.divalhr.core.payroll.application.StatutoryPackGovernanceService.Caller;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.PlatformScoped;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-097A: governance of statutory payroll packs by platform administrators with exact MFA ({@link
 * PlatformScoped}; ADR 0040). Subject, platform role and assurance are checked before the path or
 * body is bound; the caller's {@code tenant_id} claim is never read. There is no operation that
 * creates or edits a pack or its rules. Every response is {@code Cache-Control: private, no-store}.
 * Technical validation is not legal approval.
 */
@RestController
public class StatutoryPackGovernanceController {

  /** Base path of the platform statutory packs. */
  public static final String PATH = "/api/v1/platform/statutory-packs";

  /** Rate-limit bucket of reads. */
  public static final String READ_BUCKET = "statutory-pack-read";

  /** Rate-limit bucket of commands. */
  public static final String COMMAND_BUCKET = "statutory-pack-command";

  private static final String CACHE_CONTROL = "private, no-store";
  private static final String VERSION = PATH + "/{packCode}/versions/{version}";

  private final StatutoryPackGovernanceService packs;

  /**
   * Creates the controller.
   *
   * @param packs governance service
   */
  public StatutoryPackGovernanceController(StatutoryPackGovernanceService packs) {
    this.packs = packs;
  }

  /**
   * The pack versions.
   *
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified platform administrator
   * @param request current request
   * @return a page of versions
   */
  @Operation(operationId = "listStatutoryPackVersions")
  @GetMapping(PATH)
  @PlatformScoped(operation = StatutoryPackGovernanceService.READ)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<VersionPage> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(packs.list(caller(authentication, request), cursor, limit));
  }

  /**
   * One pack version with its evidence, digests, validation results, review and history.
   *
   * @param packCode pack code
   * @param version integer version
   * @param authentication verified platform administrator
   * @param request current request
   * @return the version
   */
  @Operation(operationId = "getStatutoryPackVersion")
  @GetMapping(VERSION)
  @PlatformScoped(operation = StatutoryPackGovernanceService.READ)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<VersionDetail> get(
      @PathVariable("packCode") String packCode,
      @PathVariable("version") String version,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(packs.get(caller(authentication, request), packCode, version));
  }

  /**
   * Runs the offline technical validation (not legal approval).
   *
   * @param packCode pack code
   * @param version integer version
   * @param idempotencyKey required idempotency key
   * @param body command body
   * @param authentication verified platform administrator
   * @param request current request
   * @return the receipt
   */
  @Operation(operationId = "validateStatutoryPackVersion")
  @PostMapping(path = VERSION + ":validate", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = "statutory-pack.validate")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<CommandReceipt> validate(
      @PathVariable("packCode") String packCode,
      @PathVariable("version") String version,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody StatutoryPackCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        Action.VALIDATE, packCode, version, idempotencyKey, body, authentication, request);
  }

  /**
   * Submits a VALIDATED version for independent review.
   *
   * @param packCode pack code
   * @param version integer version
   * @param idempotencyKey required idempotency key
   * @param body command body
   * @param authentication verified platform administrator
   * @param request current request
   * @return the receipt
   */
  @Operation(operationId = "submitStatutoryPackVersionForReview")
  @PostMapping(path = VERSION + ":submit-review", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = "statutory-pack.submit-review")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<CommandReceipt> submitReview(
      @PathVariable("packCode") String packCode,
      @PathVariable("version") String version,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody StatutoryPackCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(Action.SUBMIT, packCode, version, idempotencyKey, body, authentication, request);
  }

  /**
   * Approves a submitted version as an independent reviewer.
   *
   * @param packCode pack code
   * @param version integer version
   * @param idempotencyKey required idempotency key
   * @param body command body
   * @param authentication verified platform administrator
   * @param request current request
   * @return the receipt
   */
  @Operation(operationId = "approveStatutoryPackVersion")
  @PostMapping(path = VERSION + ":approve", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = "statutory-pack.approve")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<CommandReceipt> approve(
      @PathVariable("packCode") String packCode,
      @PathVariable("version") String version,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody StatutoryPackCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        Action.APPROVE, packCode, version, idempotencyKey, body, authentication, request);
  }

  /**
   * Rejects a submitted version: it retires and a new DRAFT version is created.
   *
   * @param packCode pack code
   * @param version integer version
   * @param idempotencyKey required idempotency key
   * @param body command body
   * @param authentication verified platform administrator
   * @param request current request
   * @return the receipt
   */
  @Operation(operationId = "rejectStatutoryPackVersion")
  @PostMapping(path = VERSION + ":reject", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = "statutory-pack.reject")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<CommandReceipt> reject(
      @PathVariable("packCode") String packCode,
      @PathVariable("version") String version,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody StatutoryPackCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(Action.REJECT, packCode, version, idempotencyKey, body, authentication, request);
  }

  /**
   * Schedules a REVIEWED version from a future date.
   *
   * @param packCode pack code
   * @param version integer version
   * @param idempotencyKey required idempotency key
   * @param body command body
   * @param authentication verified platform administrator
   * @param request current request
   * @return the receipt
   */
  @Operation(operationId = "scheduleStatutoryPackVersion")
  @PostMapping(path = VERSION + ":schedule", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = "statutory-pack.schedule")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<CommandReceipt> schedule(
      @PathVariable("packCode") String packCode,
      @PathVariable("version") String version,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody StatutoryPackCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        Action.SCHEDULE, packCode, version, idempotencyKey, body, authentication, request);
  }

  /**
   * Activates a REVIEWED or due SCHEDULED version.
   *
   * @param packCode pack code
   * @param version integer version
   * @param idempotencyKey required idempotency key
   * @param body command body
   * @param authentication verified platform administrator
   * @param request current request
   * @return the receipt
   */
  @Operation(operationId = "activateStatutoryPackVersion")
  @PostMapping(path = VERSION + ":activate", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = "statutory-pack.activate")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<CommandReceipt> activate(
      @PathVariable("packCode") String packCode,
      @PathVariable("version") String version,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody StatutoryPackCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        Action.ACTIVATE, packCode, version, idempotencyKey, body, authentication, request);
  }

  /**
   * Retires a REVIEWED, SCHEDULED or ACTIVE version; it can never be reactivated.
   *
   * @param packCode pack code
   * @param version integer version
   * @param idempotencyKey required idempotency key
   * @param body command body
   * @param authentication verified platform administrator
   * @param request current request
   * @return the receipt
   */
  @Operation(operationId = "retireStatutoryPackVersion")
  @PostMapping(path = VERSION + ":retire", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = "statutory-pack.retire")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<CommandReceipt> retire(
      @PathVariable("packCode") String packCode,
      @PathVariable("version") String version,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody StatutoryPackCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(Action.RETIRE, packCode, version, idempotencyKey, body, authentication, request);
  }

  private ResponseEntity<CommandReceipt> command(
      Action action,
      String packCode,
      String version,
      String idempotencyKey,
      StatutoryPackCommandRequest body,
      JwtAuthenticationToken authentication,
      HttpServletRequest request) {
    IdempotentOperation.Result<CommandReceipt> result =
        packs.command(
            action, caller(authentication, request), packCode, version, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }

  private static Caller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new Caller(
        subject, String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}
