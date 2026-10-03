package com.divalhr.core.platform.error;

import com.divalhr.core.platform.audit.AuthorizationDenial.Stage;
import com.divalhr.core.platform.audit.AuthorizationDenialAudit;
import com.divalhr.core.platform.audit.AuthorizationDenialAudit.Outcome;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.security.AssuranceEvidence;
import com.divalhr.core.platform.security.AuthorizedOperation;
import com.divalhr.core.platform.security.MethodSecurityDenialMarker;
import com.divalhr.core.platform.security.MfaRequiredException;
import com.divalhr.core.platform.security.PlatformScoped;
import com.divalhr.core.platform.security.PublicOperation;
import com.divalhr.core.platform.security.TenantScoped;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to the stable error contract. Never echoes request data or stack traces.
 *
 * <p>Ordered first so it takes precedence over Spring Boot's generic problem-details handler; every
 * response therefore carries a stable {@code code}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final Logger SECURITY_LOG = LoggerFactory.getLogger("divalhr.security");

  /** Counter of method-security denials the scope interceptor had allowed (MVP-013, D5). */
  public static final String DRIFT_METRIC = "divalhr.authorization.drift";

  private final OperationMetrics metrics;
  private final AuthorizationDenialAudit audit;
  private final MeterRegistry meters;

  /**
   * Creates the handler.
   *
   * @param metrics operation metrics
   * @param audit durable denial evidence (MVP-013)
   * @param meters meter registry
   */
  public GlobalExceptionHandler(
      OperationMetrics metrics, AuthorizationDenialAudit audit, MeterRegistry meters) {
    this.metrics = metrics;
    this.audit = audit;
    this.meters = meters;
  }

  /**
   * Handles contract-aware exceptions.
   *
   * @param exception the exception
   * @param request the current request
   * @return problem response
   */
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ProblemDetail> handleApi(
      ApiException exception, HttpServletRequest request) {
    if (exception instanceof RateLimitedException limited) {
      return ResponseEntity.status(exception.code().status())
          .header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()))
          .body(ProblemResponses.of(exception.code(), exception.params(), request));
    }
    return respond(exception.code(), exception.params(), request);
  }

  /**
   * Handles bean-validation failures, returning only field names and constraint codes.
   *
   * @param exception the exception
   * @param request the current request
   * @return problem response
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<Map<String, String>> fields =
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    Map.of(
                        "field", error.getField(), "constraint", String.valueOf(error.getCode())))
            .toList();
    return respond(ErrorCode.VALIDATION_FAILED, Map.of("fields", fields), request);
  }

  /**
   * Handles unreadable bodies (malformed JSON, wrong types). Nothing from the body is echoed.
   *
   * @param exception the exception
   * @param request the current request
   * @return problem response
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> handleUnreadable(
      HttpMessageNotReadableException exception,
      HttpServletRequest request,
      HandlerMethod handler) {
    recordValidationFailure(handler);
    return respond(
        ErrorCode.VALIDATION_FAILED,
        Map.of("fields", List.of(Map.of("field", "body", "constraint", "FORMAT"))),
        request);
  }

  /**
   * Handles unsupported content types.
   *
   * @param exception the exception
   * @param request the current request
   * @return problem response
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMediaType(
      HttpMediaTypeNotSupportedException exception,
      HttpServletRequest request,
      HandlerMethod handler) {
    recordValidationFailure(handler);
    return respond(
        ErrorCode.VALIDATION_FAILED,
        Map.of("fields", List.of(Map.of("field", "Content-Type", "constraint", "FORMAT"))),
        request);
  }

  /**
   * Handles methods that are not implemented for a path (for example planned operations).
   *
   * @param exception the exception
   * @param request the current request
   * @return problem response
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethod(
      HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
    return respond(ErrorCode.NOT_FOUND, Map.of(), request);
  }

  /**
   * RFC 9470 step-up challenge for {@code MFA_REQUIRED}. The value is public configuration: the
   * client repeats authorization with these {@code acr_values}.
   */
  static final String STEP_UP_CHALLENGE =
      "Bearer error=\"insufficient_user_authentication\", acr_values=\""
          + AssuranceEvidence.MFA_ACR
          + "\"";

  /**
   * Handles a privileged request whose token does not prove multifactor authentication.
   *
   * @param exception the exception
   * @param request the current request
   * @return problem response with the step-up challenge
   */
  @ExceptionHandler(MfaRequiredException.class)
  public ResponseEntity<ProblemDetail> handleMfaRequired(
      MfaRequiredException exception, HttpServletRequest request) {
    return ResponseEntity.status(ErrorCode.MFA_REQUIRED.status())
        .header(HttpHeaders.WWW_AUTHENTICATE, STEP_UP_CHALLENGE)
        .body(ProblemResponses.of(ErrorCode.MFA_REQUIRED, Map.of(), request));
  }

  /**
   * Handles access denials from the scope interceptor, method security or application code. The
   * response is always the same {@code ACCESS_DENIED}.
   *
   * <p>MVP-013 (D5, A13-1): only when Spring method security itself denied a privileged handler
   * that the scope interceptor had allowed ({@link MethodSecurityDenialMarker}) is this annotation
   * or configuration drift: it is recorded once as a {@code method_security} denial and raises the
   * drift metric and an error log. Any other {@code AccessDeniedException} is never drift.
   *
   * @param exception the exception
   * @param request the current request
   * @return problem response
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(
      AccessDeniedException exception, HttpServletRequest request) {
    AuthorizedOperation authorized = AuthorizedOperation.of(request);
    if (authorized != null && MethodSecurityDenialMarker.deniedByMethodSecurity(request)) {
      Outcome outcome =
          audit.record(
              request,
              SecurityContextHolder.getContext().getAuthentication(),
              authorized.scope(),
              Stage.METHOD_SECURITY,
              authorized.operation(),
              authorized.effectiveTenant());
      Counter.builder(DRIFT_METRIC)
          .description("Method-security denials of handlers the scope interceptor allowed")
          .tag("scope", authorized.scope().value())
          .tag("operation", authorized.operation())
          .register(meters)
          .increment();
      SECURITY_LOG
          .atError()
          .addKeyValue("event", "authorization_drift")
          .addKeyValue("operation", authorized.operation())
          .addKeyValue("scope", authorized.scope().value())
          .addKeyValue("result", "DENIED")
          .addKeyValue("audit", outcome.value())
          .log("authorization_drift");
    }
    return respond(ErrorCode.ACCESS_DENIED, Map.of(), request);
  }

  /**
   * Handles unknown routes.
   *
   * @param exception the exception
   * @param request the current request
   * @return problem response
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> handleNotFound(
      NoResourceFoundException exception, HttpServletRequest request) {
    return respond(ErrorCode.NOT_FOUND, Map.of(), request);
  }

  /**
   * Last-resort handler. Logs the exception type only, never request content.
   *
   * @param exception the exception
   * @param request the current request
   * @return problem response
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(
      Exception exception, HttpServletRequest request) {
    if (exception instanceof ErrorResponse standard) {
      // Other Spring MVC client errors (missing parameters, not acceptable, ...) keep their
      // status class but always use a stable code.
      int status = standard.getStatusCode().value();
      if (status == 404 || status == 405) {
        return respond(ErrorCode.NOT_FOUND, Map.of(), request);
      }
      if (status >= 400 && status < 500) {
        return respond(ErrorCode.VALIDATION_FAILED, Map.of(), request);
      }
    }
    LOG.error("Unhandled exception type={}", exception.getClass().getName());
    return respond(ErrorCode.INTERNAL_ERROR, Map.of(), request);
  }

  /**
   * Bodies that cannot be read never reach the use case, so the validation-failure metric for named
   * operations is recorded here.
   */
  private void recordValidationFailure(HandlerMethod handler) {
    if (handler == null) {
      return;
    }
    String operation = operationOf(handler);
    if (!operation.isEmpty()) {
      metrics.record(operation, OperationMetrics.Outcome.VALIDATION_FAILED);
    }
  }

  private static String operationOf(HandlerMethod handler) {
    PlatformScoped platform = handler.getMethodAnnotation(PlatformScoped.class);
    if (platform != null) {
      return platform.operation();
    }
    PublicOperation publicOperation = handler.getMethodAnnotation(PublicOperation.class);
    if (publicOperation != null) {
      return publicOperation.operation();
    }
    TenantScoped tenant =
        AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), TenantScoped.class);
    return tenant == null ? "" : tenant.operation();
  }

  private static ResponseEntity<ProblemDetail> respond(
      ErrorCode code, Map<String, Object> params, HttpServletRequest request) {
    return ResponseEntity.status(code.status()).body(ProblemResponses.of(code, params, request));
  }
}
