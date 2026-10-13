package com.aiskincare.backend.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * TRD §15.2 — nginx·터널 뒤라 모든 요청의 출발 IP가 같다. CF-Connecting-IP → 없으면 (TRUST_PROXY면) X-Forwarded-For 맨
 * 앞 → 원격 주소.
 */
public final class ClientIp {
  private ClientIp() {}

  public static String of(HttpServletRequest request, boolean trustProxy) {
    String cf = request.getHeader("CF-Connecting-IP");
    if (cf != null && !cf.isBlank()) return cf.trim();
    if (trustProxy) {
      String forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
