package com.divalhr.core.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to the stable error contract. Never echoes request data or stack traces. */
@RestControllerAdvice
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
    LOG.error("Unhandled exception type={}", exception.getClass().getName());
    return respond(ErrorCode.INTERNAL_ERROR, Map.of(), request);
  }

  private static ResponseEntity<ProblemDetail> respond(
      ErrorCode code, Map<String, Object> params, HttpServletRequest request) {
    return ResponseEntity.status(code.status()).body(ProblemResponses.of(code, params, request));
  }
}
