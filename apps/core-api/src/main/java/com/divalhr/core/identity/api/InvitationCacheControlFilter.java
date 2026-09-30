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
 * Applies the invitation cache policies to every response of those paths, including errors, before
 * the handler runs (guardrails 3 and 6): {@code no-store} for the anonymous endpoints and {@code
 * private, no-store} for the administrative ones, whose bodies may contain email addresses.
 */
@Component
public class InvitationCacheControlFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (path.startsWith(PublicInvitationController.PATH)) {
      response.setHeader(HttpHeaders.CACHE_CONTROL, PublicInvitationController.CACHE_CONTROL);
    } else if (path.equals(InvitationController.PATH)
        || path.startsWith(InvitationController.PATH + "/")) {
      response.setHeader(HttpHeaders.CACHE_CONTROL, InvitationController.CACHE_CONTROL);
    }
    chain.doFilter(request, response);
  }
}
