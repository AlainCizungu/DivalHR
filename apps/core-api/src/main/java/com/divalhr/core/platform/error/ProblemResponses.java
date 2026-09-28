package com.divalhr.core.platform.error;

import com.divalhr.core.platform.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 problem responses with the DivalHR extensions {@code code}, {@code params} and
 * {@code correlationId}. Detail text is intentionally generic English for operators; user-facing
 * text is always produced by clients from {@code code}.
 */
public final class ProblemResponses {

  private static final String TYPE_PREFIX = "https://docs.divalhr.com/errors/";

  private ProblemResponses() {}

  /**
   * Builds a problem detail.
   *
   * @param code stable error code
   * @param params safe interpolation parameters
   * @param request current request, used for the correlation ID
   * @return the problem detail
   */
  public static ProblemDetail of(
      ErrorCode code, Map<String, Object> params, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(code.status());
    problem.setType(URI.create(TYPE_PREFIX + code.name().toLowerCase(java.util.Locale.ROOT)));
    problem.setTitle(code.status().getReasonPhrase());
    problem.setProperty("code", code.name());
    problem.setProperty("params", params);
    problem.setProperty("correlationId", correlationId(request));
    return problem;
  }

  /**
   * Serializable map form, used where no message converter is available (security filters).
   *
   * @param code stable error code
   * @param request current request
   * @return ordered map in problem+json shape
   */
  public static Map<String, Object> asMap(ErrorCode code, HttpServletRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", TYPE_PREFIX + code.name().toLowerCase(java.util.Locale.ROOT));
    body.put("title", code.status().getReasonPhrase());
    body.put("status", code.status().value());
    body.put("code", code.name());
    body.put("params", Map.of());
    body.put("correlationId", correlationId(request));
    return body;
  }

  private static String correlationId(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE);
    return value == null ? CorrelationId.resolve(null) : value.toString();
  }
}
