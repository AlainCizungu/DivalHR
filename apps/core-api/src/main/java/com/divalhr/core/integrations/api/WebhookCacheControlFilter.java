package com.divalhr.core.integrations.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MVP-110 (D110-3): every response of the webhook surface, including errors and denials, is {@code
 * Cache-Control: private, no-store}. The surface returns one-time secrets and endpoint
 * configuration, and none of it may be cached by a browser or an intermediary.
 */
@Component
public class WebhookCacheControlFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (path.equals(WebhookController.PATH) || path.startsWith(WebhookController.PATH + "/")) {
      response.setHeader(HttpHeaders.CACHE_CONTROL, WebhookController.CACHE_CONTROL);
    }
    chain.doFilter(request, response);
  }
}
