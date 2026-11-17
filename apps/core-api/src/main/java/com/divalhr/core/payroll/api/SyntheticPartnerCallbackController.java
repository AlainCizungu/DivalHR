package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.application.SalaryAdvanceHandoffCallbackService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.security.PublicOperation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed review-status callback door of the controlled synthetic partner (MVP-120B, ADR 0042
 * section 7, {@code x-divalhr-scope: public}). It is served by the token-free public chain and
 * authenticates the partner by a dedicated callback signature, not a user. A callback that is wrong
 * in any way is the same generic 400; the answer to an accepted one is empty. Always {@code
 * Cache-Control: no-store}. The raw bytes are read here, bounded to 4 KiB, because the signature
 * covers them exactly.
 */
@RestController
public class SyntheticPartnerCallbackController {

  /** Full public path. */
  public static final String PATH = "/api/v1/public/partners/synthetic-test/callbacks";

  /** Operation name. */
  public static final String RECEIVE = "salary-advance-handoff.partner-callback";

  /** Largest accepted body. */
  static final int MAX_BYTES = 4096;

  private static final String CACHE_CONTROL = "no-store";

  private final SalaryAdvanceHandoffCallbackService callbacks;

  /**
   * Creates the controller.
   *
   * @param callbacks the callback service
   */
  public SyntheticPartnerCallbackController(SalaryAdvanceHandoffCallbackService callbacks) {
    this.callbacks = callbacks;
  }

  /**
   * Receives a signed review-status callback.
   *
   * @param timestamp the {@code X-Divalhr-Timestamp} header
   * @param callbackId the {@code X-Divalhr-Callback-Id} header
   * @param signature the {@code X-Divalhr-Signature} header
   * @param servletRequest current request (the raw bytes are read here, bounded)
   * @return an empty answer
   * @throws IOException when the body cannot be read
   */
  @Operation(
      operationId = "receiveSyntheticPartnerCallback",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = SalaryAdvanceHandoffBodies.PartnerCallback.class))))
  @SecurityRequirements
  @PostMapping(path = PATH)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PublicOperation(operation = RECEIVE)
  public ResponseEntity<Void> receive(
      @RequestHeader(name = "X-Divalhr-Timestamp", required = false) String timestamp,
      @RequestHeader(name = "X-Divalhr-Callback-Id", required = false) String callbackId,
      @RequestHeader(name = "X-Divalhr-Signature", required = false) String signature,
      @Parameter(hidden = true) HttpServletRequest servletRequest)
      throws IOException {
    if (!isJson(servletRequest.getContentType())) {
      return answer(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
    if (servletRequest.getContentLengthLong() > MAX_BYTES) {
      return answer(HttpStatus.CONTENT_TOO_LARGE);
    }
    byte[] raw = servletRequest.getInputStream().readNBytes(MAX_BYTES + 1);
    if (raw.length > MAX_BYTES) {
      return answer(HttpStatus.CONTENT_TOO_LARGE);
    }
    return switch (callbacks.receive(raw, timestamp, callbackId, signature)) {
      case ACCEPTED -> answer(HttpStatus.NO_CONTENT);
      case INVALID -> throw new ApiException(ErrorCode.PARTNER_CALLBACK_INVALID, Map.of());
      case RATE_LIMITED -> throw new RateLimitedException(ErrorCode.RATE_LIMITED, 60);
      case UNAVAILABLE -> answer(HttpStatus.SERVICE_UNAVAILABLE);
    };
  }

  private static boolean isJson(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return false;
    }
    try {
      MediaType parsed = MediaType.parseMediaType(contentType);
      return parsed.getType().equalsIgnoreCase("application")
          && parsed.getSubtype().equalsIgnoreCase("json");
    } catch (org.springframework.http.InvalidMediaTypeException invalid) {
      return false;
    }
  }

  private static ResponseEntity<Void> answer(HttpStatus status) {
    return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).build();
  }
}
