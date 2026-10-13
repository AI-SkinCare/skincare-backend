package com.aiskincare.backend.auth;

import com.aiskincare.backend.config.Constants;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * TRD §9.1 — 정확히 이 속성: {@code refresh_token=<token>; HttpOnly; Secure; SameSite=Strict;
 * Path=/api/v1/auth; Max-Age=1209600}. 지울 때도 같은 Path로 Max-Age=0.
 */
public final class RefreshCookies {
  private RefreshCookies() {}

  static String header(String value, long maxAge, boolean secure) {
    return Constants.REFRESH_COOKIE_NAME
        + "="
        + value
        + "; HttpOnly"
        + (secure ? "; Secure" : "")
        + "; SameSite=Strict; Path="
        + Constants.REFRESH_COOKIE_PATH
        + "; Max-Age="
        + maxAge;
  }

  public static void set(HttpServletResponse response, String token, boolean secure) {
    response.addHeader("Set-Cookie", header(token, Constants.REFRESH_TOKEN_TTL_SEC, secure));
  }

  public static void clear(HttpServletResponse response, boolean secure) {
    response.addHeader("Set-Cookie", header("", 0, secure));
  }

  /** 쿠키 우선, 없으면 본문 refreshToken [앱 협의]. 둘 다 없으면 null */
  public static String read(HttpServletRequest request, String bodyToken) {
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if (Constants.REFRESH_COOKIE_NAME.equals(cookie.getName())
            && cookie.getValue() != null
            && !cookie.getValue().isEmpty()) {
          return cookie.getValue();
        }
      }
    }
    return bodyToken != null && !bodyToken.isEmpty() ? bodyToken : null;
  }
}
