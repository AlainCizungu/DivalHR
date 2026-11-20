package com.divalhr.core.integrations.partner;

import com.divalhr.core.integrations.partner.PartnerApplicationBodies.Receipt;
import com.divalhr.core.integrations.partner.PartnerSubmissionService.Outcome;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.ratelimit.ClientAddressResolver;
import com.divalhr.core.platform.security.PublicOperation;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The anonymous partner-application submission ({@code x-divalhr-scope: public}, MVP-120D). This is
 * the only partner route in the public allowlist; the review routes are never public. The body is
 * read as raw bytes (bounded to 16 KiB) and validated strictly; responses are {@code Cache-Control:
 * no-store}. A new application, a duplicate and a honeypot hit answer identically.
 */
@RestController
public class PartnerApplicationPublicController {

  /** Full public path. */
  public static final String PATH = "/api/v1/public/partner-applications";

  /** Largest accepted body. */
  static final int MAX_BYTES = 16 * 1024;

  private static final String CACHE_CONTROL = "no-store";

  private final PartnerSubmissionService submissions;
  private final ClientAddressResolver clients;
  private final JsonMapper json;

  /**
   * Creates the controller.
   *
   * @param submissions submission service
   * @param clients client address resolver (rate-limit key only)
   * @param json JSON mapper
   */
  public PartnerApplicationPublicController(
      PartnerSubmissionService submissions, ClientAddressResolver clients, JsonMapper json) {
    this.submissions = submissions;
    this.clients = clients;
    this.json = json;
  }

  /**
   * Submits a partner application.
   *
   * @param idempotencyKey required idempotency key
   * @param servletRequest current request (the raw body is read here, bounded)
   * @return the safe receipt
   */
  @Operation(
      operationId = "submitPartnerApplication",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PartnerApplicationBodies.Submission.class))))
  @SecurityRequirements
  @PostMapping(path = PATH)
  @ResponseStatus(HttpStatus.CREATED)
  @PublicOperation(operation = "partner-application.submit")
  public ResponseEntity<Receipt> submit(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) HttpServletRequest servletRequest) {
    if (!submissions.available()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
          .build();
    }
    submissions.admit(clients.resolve(servletRequest));
    JsonNode body = PartnerBodyReader.read(servletRequest, json, MAX_BYTES);
    Outcome outcome;
    try {
      outcome =
          submissions.submit(
              idempotencyKey,
              body,
              String.valueOf(servletRequest.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
    } catch (PartnerSubmissionService.Busy busy) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
          .header(HttpHeaders.RETRY_AFTER, "1")
          .build();
    }
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (outcome.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(outcome.receipt());
  }
}
