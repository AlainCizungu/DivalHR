package com.divalhr.core.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the correlation ID before any other processing so that every log line, error response,
 * and downstream call for the request carries it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
    request.setAttribute(CorrelationId.REQUEST_ATTRIBUTE, correlationId);
    response.setHeader(CorrelationId.HEADER, correlationId);
    MDC.put(CorrelationId.MDC_KEY, correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }
}
