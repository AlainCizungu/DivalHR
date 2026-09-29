package com.divalhr.core.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to the stable error contract. Never echoes request data or stack traces.
 *
 * <p>Ordered first so it takes precedence over Spring Boot's generic problem-details handler;
 * every response therefore carries a stable {@code code}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
      HttpMessageNotReadableException exception, HttpServletRequest request) {
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
      HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
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
   * Handles method-security denials.
   *
   * @param exception the exception
   * @param request the current request
   * @return problem response
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(
      AccessDeniedException exception, HttpServletRequest request) {
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

  private static ResponseEntity<ProblemDetail> respond(
      ErrorCode code, Map<String, Object> params, HttpServletRequest request) {
    return ResponseEntity.status(code.status()).body(ProblemResponses.of(code, params, request));
  }
}
