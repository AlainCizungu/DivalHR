package com.divalhr.core.identity.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets {@code Cache-Control: private, no-store} on every access-review response before the handler
 * runs, so validation, not-found, rate-limit, authorization and server errors that may reflect
 * review input are never cached either (MVP-012B, A5).
 */
@Component
public class AccessReviewCacheControlFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (path.equals(AccessReviewController.PATH)
        || path.startsWith(AccessReviewController.PATH + "/")) {
      response.setHeader(HttpHeaders.CACHE_CONTROL, AccessReviewController.CACHE_CONTROL);
    }
    chain.doFilter(request, response);
  }
}
