package com.divalhr.core.platform.ai;

import com.divalhr.core.platform.ai.OperationalSummaryContract.Highlight;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Limitation;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Request;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Response;
import com.divalhr.core.platform.web.CorrelationId;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP adapter of {@link OperationalSummaryPort} (MVP-101, ADR 0027): one authenticated POST
 * with the closed snapshot, one bounded deadline, no retry. Request and response bodies, the bearer
 * token and the address are never logged; every failure becomes a bounded reason. The answer is
 * parsed strictly: exact property sets, no extra field, no wrong type.
 */
@Component
public class AiServiceOperationalSummaryClient implements OperationalSummaryPort {

  static final String PATH = "/api/v1/internal/operational-summary";
  private static final int MAX_RESPONSE_BYTES = 32_768;
  private static final Logger LOG = LoggerFactory.getLogger("divalhr.ai");

  private final AiServiceProperties properties;
  private final ServiceTokenProvider tokens;
  private final RestClient http;
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * Creates the adapter.
   *
   * @param properties AI service settings
   * @param tokens the service token provider
   */
  public AiServiceOperationalSummaryClient(
      AiServiceProperties properties, ServiceTokenProvider tokens) {
    this.properties = properties;
    this.tokens = tokens;
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(properties.readTimeout());
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public Response generate(Request request) {
    if (!tokens.available()) {
      throw new AiServiceUnavailableException("not_configured");
    }
    String bearer = tokens.token();
    String body = json.writeValueAsString(request.toJson());
    String correlationId = MDC.get(CorrelationId.MDC_KEY);
    Reply reply;
    try {
      reply =
          http.post()
              .uri(URI.create(base() + PATH))
              .headers(
                  headers -> {
                    headers.setBearerAuth(bearer);
                    if (correlationId != null) {
                      headers.set(CorrelationId.HEADER, correlationId);
                    }
                  })
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .exchange(
                  (outgoing, incoming) ->
                      new Reply(
                          incoming.getStatusCode().value(),
                          new String(
                              incoming.getBody().readNBytes(MAX_RESPONSE_BYTES),
                              StandardCharsets.UTF_8)));
    } catch (ResourceAccessException io) {
      throw new AiServiceUnavailableException(timedOut(io) ? "timeout" : "transport");
    } catch (RuntimeException unreadable) {
      throw new AiServiceUnavailableException("transport");
    }
    return switch (reply.status()) {
      case 200 -> parse(reply.body());
      case 401 -> {
        tokens.invalidate();
        LOG.atError().addKeyValue("status", 401).log("ai_service_unauthorized");
        throw new AiServiceUnavailableException("unauthorized");
      }
      case 403 -> {
        LOG.atError().addKeyValue("status", 403).log("ai_service_forbidden");
        throw new AiServiceUnavailableException("forbidden");
      }
      case 400 -> throw new AiServiceUnavailableException("request_rejected");
      case 422 -> throw new AiServiceUnavailableException("snapshot_rejected");
      default ->
          throw new AiServiceUnavailableException(
              reply.status() >= 500 ? "service_error" : "unexpected_status");
    };
  }

  /** Status and (bounded) body; the body is parsed and dropped, never logged. */
  private record Reply(int status, String body) {}

  private String base() {
    String root = properties.baseUrl();
    return root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
  }

  private static boolean timedOut(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.net.http.HttpTimeoutException
          || cause instanceof java.net.SocketTimeoutException) {
        return true;
      }
    }
    return false;
  }

  /** Strict shape check; any deviation is an invalid response. */
  private Response parse(String text) {
    try {
      JsonNode root = json.readTree(text);
      if (!exact(
          root,
          "language",
          "generationMode",
          "templateVersion",
          "headline",
          "highlights",
          "limitations")) {
        throw invalid();
      }
      JsonNode headline = root.get("headline");
      JsonNode highlights = root.get("highlights");
      JsonNode limitations = root.get("limitations");
      if (!exact(headline, "code", "text") || !highlights.isArray() || !limitations.isArray()) {
        throw invalid();
      }
      List<Highlight> parsedHighlights = new ArrayList<>();
      for (JsonNode item : highlights) {
        if (!exact(item, "factId", "factType", "severity", "routeId", "text")) {
          throw invalid();
        }
        parsedHighlights.add(
            new Highlight(
                string(item, "factId"),
                string(item, "factType"),
                string(item, "severity"),
                string(item, "routeId"),
                string(item, "text")));
      }
      List<Limitation> parsedLimitations = new ArrayList<>();
      for (JsonNode item : limitations) {
        if (!exact(item, "code", "text")) {
          throw invalid();
        }
        parsedLimitations.add(new Limitation(string(item, "code"), string(item, "text")));
      }
      return new Response(
          string(root, "language"),
          string(root, "generationMode"),
          string(root, "templateVersion"),
          string(headline, "code"),
          string(headline, "text"),
          parsedHighlights,
          parsedLimitations);
    } catch (AiServiceUnavailableException invalid) {
      throw invalid;
    } catch (RuntimeException unreadable) {
      throw invalid();
    }
  }

  private static boolean exact(JsonNode node, String... names) {
    if (node == null || !node.isObject() || node.size() != names.length) {
      return false;
    }
    for (String name : names) {
      if (!node.has(name)) {
        return false;
      }
    }
    return true;
  }

  private static String string(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isString()) {
      throw invalid();
    }
    return value.asString();
  }

  private static AiServiceUnavailableException invalid() {
    return new AiServiceUnavailableException("invalid_response");
  }
}
