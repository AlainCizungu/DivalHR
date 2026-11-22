package com.divalhr.core.integrations.partner.catalogue;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a bounded JSON body from the raw request (MVP-120E). A refusal names a field and a
 * constraint only; the content is never echoed or logged.
 */
final class AccountBodyReader {

  private AccountBodyReader() {}

  static JsonNode read(HttpServletRequest request, JsonMapper json, int maxBytes) {
    if (!isJson(request.getContentType())) {
      throw PartnerAccountCommands.shape("Content-Type", "FORMAT");
    }
    if (request.getContentLengthLong() > maxBytes) {
      throw PartnerAccountCommands.shape("body", "LENGTH");
    }
    byte[] raw;
    try {
      raw = request.getInputStream().readNBytes(maxBytes + 1);
    } catch (IOException unreadable) {
      throw PartnerAccountCommands.shape("body", "FORMAT");
    }
    if (raw.length > maxBytes) {
      throw PartnerAccountCommands.shape("body", "LENGTH");
    }
    if (raw.length == 0) {
      throw PartnerAccountCommands.shape("body", "REQUIRED");
    }
    JsonNode tree;
    try {
      tree = json.readTree(raw);
    } catch (RuntimeException malformed) {
      throw PartnerAccountCommands.shape("body", "FORMAT");
    }
    if (tree == null || !tree.isObject()) {
      throw PartnerAccountCommands.shape("body", "FORMAT");
    }
    return tree;
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
}
