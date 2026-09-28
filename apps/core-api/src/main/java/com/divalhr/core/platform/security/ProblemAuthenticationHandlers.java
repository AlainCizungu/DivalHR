package com.divalhr.core.platform.security;

import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/** Writes security failures using the standard problem contract without revealing token details. */
final class ProblemAuthenticationHandlers {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ProblemAuthenticationHandlers() {}

  static AuthenticationEntryPoint entryPoint() {
    return (request, response, exception) ->
        write(request, response, ErrorCode.AUTHENTICATION_REQUIRED);
  }

  static AccessDeniedHandler accessDeniedHandler() {
    return (request, response, exception) -> write(request, response, ErrorCode.ACCESS_DENIED);
  }

  private static void write(
      HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    if (code == ErrorCode.AUTHENTICATION_REQUIRED) {
      response.setHeader("WWW-Authenticate", "Bearer");
    }
    MAPPER.writeValue(response.getOutputStream(), ProblemResponses.asMap(code, request));
  }
}
