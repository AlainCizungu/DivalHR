package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.StatutoryResponses.ActivationReceipt;
import com.divalhr.core.payroll.api.StatutoryResponses.ActivationView;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.StatutoryActivationService;
import com.divalhr.core.payroll.application.StatutoryPackService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
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
 * MVP-094B: the statutory rule-pack activations of a legal entity, for tenant administrators with
 * privileged MFA (ADR 0034). Only a VERIFIED pack can be activated. Every response is {@code
 * Cache-Control: private, no-store}.
 */
@RestController
public class StatutoryActivationController {

  /** Path of a legal entity's activations. */
  public static final String PATH = "/api/v1/legal-entities/{legalEntityId}/statutory-rule-pack";

  private static final String CACHE_CONTROL = "private, no-store";

  private final StatutoryActivationService activations;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param activations activation service
   * @param tenants verified tenant context
   */
  public StatutoryActivationController(
      StatutoryActivationService activations, TenantContextResolver tenants) {
    this.activations = activations;
    this.tenants = tenants;
  }

  /**
   * The activations and the pack in effect for a month.
   *
   * @param legalEntityId legal entity
   * @param month the payroll month, {@code YYYY-MM}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the activation view
   */
  @Operation(operationId = "getStatutoryActivation")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = StatutoryActivationService.READ)
  @SubjectRateLimited(bucket = StatutoryPackService.SUBJECT_READ_BUCKET)
  public ResponseEntity<ActivationView> get(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestParam(name = "month", required = false) String month,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(activations.read(caller(authentication, request), legalEntityId, month));
  }

  /**
   * Activates a verified pack from a month.
   *
   * @param legalEntityId legal entity
   * @param idempotencyKey required idempotency key
   * @param body the activation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "activateStatutoryRulePack")
  @PostMapping(path = PATH + "/activation", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = StatutoryActivationService.CREATE)
  @SubjectRateLimited(bucket = StatutoryActivationService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<ActivationReceipt> activate(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ActivateStatutoryRulePackRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<ActivationReceipt> result =
        activations.activate(
            caller(authentication, request), legalEntityId, idempotencyKey, body);
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
