package com.divalhr.core.integrations.partner;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a bounded JSON body from the raw request (MVP-120D). The body is refused as {@link
 * ErrorCode#PARTNER_APPLICATION_INVALID} with a field name and constraint only; its content is
 * never echoed or logged.
 */
final class PartnerBodyReader {

  private PartnerBodyReader() {}

  /**
   * Reads and parses a JSON body.
   *
   * @param request the request
   * @param json JSON mapper
   * @param maxBytes largest accepted body
   * @return the parsed tree
   */
  static JsonNode read(HttpServletRequest request, JsonMapper json, int maxBytes) {
    if (!isJson(request.getContentType())) {
      throw invalid("Content-Type", "FORMAT");
    }
    if (request.getContentLengthLong() > maxBytes) {
      throw invalid("body", "LENGTH");
    }
    byte[] raw;
    try {
      raw = request.getInputStream().readNBytes(maxBytes + 1);
    } catch (IOException unreadable) {
      throw invalid("body", "FORMAT");
    }
    if (raw.length > maxBytes) {
      throw invalid("body", "LENGTH");
    }
    if (raw.length == 0) {
      throw invalid("body", "REQUIRED");
    }
    try {
      JsonNode tree = json.readTree(raw);
      if (tree == null || !tree.isObject()) {
        throw invalid("body", "FORMAT");
      }
      return tree;
    } catch (ApiException refused) {
      throw refused;
    } catch (RuntimeException malformed) {
      throw invalid("body", "FORMAT");
    }
  }

  private static boolean isJson(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return false;
    }
    try {
      MediaType parsed = MediaType.parseMediaType(contentType);
      return parsed.getType().equalsIgnoreCase("application")
          && parsed.getSubtype().equalsIgnoreCase("json");
    } catch (InvalidMediaTypeException invalid) {
      return false;
    }
  }

  private static ApiException invalid(String field, String constraint) {
    return new ApiException(
        ErrorCode.PARTNER_APPLICATION_INVALID,
        Map.of("fields", List.of(Map.of("field", field, "constraint", constraint))));
  }
}
