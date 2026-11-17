package com.aiskincare.backend.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * TRD §7.6 — 요청마다 한 줄: requestId(MDC), method, route, status, durationMs, userId. 헤더·본문·쿼리 값(설문 등)은
 * 남기지 않는다.
 */
public class RequestLogFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger("request");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long started = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      int status = response.getStatus();
      Level level = status == 500 ? Level.ERROR : status >= 500 ? Level.WARN : Level.INFO;
      Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      var event =
          log.atLevel(level)
              .addKeyValue("method", request.getMethod())
              .addKeyValue("route", route != null ? route : request.getRequestURI())
              .addKeyValue("status", status)
              .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000);
      // userId·requestId는 MDC에 있다 (AuthInterceptor·RequestIdFilter). 같은 키를 key-value로 또 넣으면 구조화 로그가
      // 줄을 버린다
      event.log("request completed");
    }
  }
}
