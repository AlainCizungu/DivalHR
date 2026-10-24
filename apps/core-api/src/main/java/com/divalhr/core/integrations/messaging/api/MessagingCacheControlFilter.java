package com.divalhr.core.integrations.messaging.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MVP-111 (D111-12): every response of the WhatsApp management surface, including errors and
 * denials, is {@code Cache-Control: private, no-store}; so is every answer of the provider callback
 * door ({@code no-store}).
 */
@Component
public class MessagingCacheControlFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (path.equals(MessagingController.PATH) || path.startsWith(MessagingController.PATH + "/")) {
      response.setHeader(HttpHeaders.CACHE_CONTROL, MessagingController.CACHE_CONTROL);
    } else if (path.equals(MessagingCallbackController.PATH)) {
      response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
    chain.doFilter(request, response);
  }
}
