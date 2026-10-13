package com.aiskincare.backend.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** TRD §7.5 — 요청 헤더 X-Request-Id가 형식에 맞으면 그대로, 아니면 UUID v4. 가장 먼저 실행해 모든 응답(에러 포함)에 헤더가 붙게 한다. */
public class RequestIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Request-Id";
  public static final String ATTRIBUTE = "requestId";
  private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

  public static String resolve(String incoming) {
    return incoming != null && VALID.matcher(incoming).matches()
        ? incoming
        : UUID.randomUUID().toString();
  }

  public static String of(HttpServletRequest request) {
    Object id = request.getAttribute(ATTRIBUTE);
    return id instanceof String s ? s : resolve(null);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = resolve(request.getHeader(HEADER));
    request.setAttribute(ATTRIBUTE, requestId);
    response.setHeader(HEADER, requestId);
    MDC.put(ATTRIBUTE, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(ATTRIBUTE);
      MDC.remove("userId");
    }
  }
}
