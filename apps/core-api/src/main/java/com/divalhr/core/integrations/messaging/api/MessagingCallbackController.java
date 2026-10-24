package com.divalhr.core.integrations.messaging.api;

import com.divalhr.core.integrations.messaging.application.MessagingCallbackService;
import com.divalhr.core.integrations.messaging.application.MessagingCallbackService.Outcome;
import com.divalhr.core.integrations.messaging.application.MessagingProperties;
import com.divalhr.core.platform.security.PublicOperation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The provider callback boundary of the WhatsApp connector ({@code x-divalhr-scope: public}). It is
 * served by the token-free public chain and authenticates the provider by the app-secret signature,
 * not a user. Answers are empty or the echoed challenge, always {@code Cache-Control: no-store}.
 */
@RestController
public class MessagingCallbackController {

  /** Full public path. */
  public static final String PATH = "/api/v1/public/messaging/whatsapp/callback";

  /** Verification operation name. */
  public static final String VERIFY = "messaging.whatsapp.callback-verify";

  /** Status callback operation name. */
  public static final String RECEIVE = "messaging.whatsapp.callback";

  private static final String CACHE_CONTROL = "no-store";

  private final MessagingCallbackService callbacks;
  private final int maxBytes;

  /**
   * Creates the controller.
   *
   * @param callbacks the callback service
   * @param properties settings
   */
  public MessagingCallbackController(
      MessagingCallbackService callbacks, MessagingProperties properties) {
    this.callbacks = callbacks;
    this.maxBytes = properties.callbackMaxBytes();
  }

  /**
   * Answers the provider's subscription verification handshake.
   *
   * @param mode the {@code hub.mode} value
   * @param token the {@code hub.verify_token} value
   * @param challenge the {@code hub.challenge} value
   * @return the challenge, or an empty refusal
   */
  @Operation(operationId = "verifyWhatsAppCallback")
  @SecurityRequirements
  @GetMapping(path = PATH)
  @PublicOperation(operation = VERIFY)
  public ResponseEntity<String> verify(
      @RequestParam(name = "hub.mode", required = false) String mode,
      @RequestParam(name = "hub.verify_token", required = false) String token,
      @RequestParam(name = "hub.challenge", required = false) String challenge) {
    return callbacks
        .verify(mode, token, challenge)
        .map(
            echoed ->
                ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(echoed))
        .orElseGet(
            () ->
                ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .<String>build());
  }

  /**
   * Receives a signed delivery-status callback.
   *
   * @param signature the {@code X-Hub-Signature-256} header
   * @param servletRequest current request (the raw bytes are read here, bounded)
   * @return an empty answer
   */
  @Operation(operationId = "receiveWhatsAppCallback")
  @SecurityRequirements
  @PostMapping(path = PATH)
  @PublicOperation(operation = RECEIVE)
  public ResponseEntity<Void> receive(
      @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
      @Parameter(hidden = true) HttpServletRequest servletRequest)
      throws IOException {
    if (servletRequest.getContentLengthLong() > maxBytes) {
      return answer(HttpStatus.CONTENT_TOO_LARGE);
    }
    byte[] raw = servletRequest.getInputStream().readNBytes(maxBytes + 1);
    if (raw.length > maxBytes) {
      return answer(HttpStatus.CONTENT_TOO_LARGE);
    }
    Outcome outcome = callbacks.receive(raw, signature);
    return switch (outcome) {
      case ACCEPTED -> answer(HttpStatus.OK);
      case FORBIDDEN -> answer(HttpStatus.FORBIDDEN);
      case RATE_LIMITED -> answer(HttpStatus.TOO_MANY_REQUESTS);
      case UNAVAILABLE -> answer(HttpStatus.SERVICE_UNAVAILABLE);
    };
  }

  private static ResponseEntity<Void> answer(HttpStatus status) {
    return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).build();
  }
}
