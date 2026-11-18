package com.aiskincare.backend.auth;

import java.util.Locale;
import java.util.Set;

/** TRD §9.1 — 로그인 및 갱신 시 리프레시 토큰을 본문으로 받는 네이티브 앱. */
public final class ClientType {
  private ClientType() {}

  public static final String HEADER = "X-Client";
  public static final Set<String> NATIVE_CLIENTS = Set.of("ios", "android");

  public static boolean isNative(String header) {
    return header != null && NATIVE_CLIENTS.contains(header.trim().toLowerCase(Locale.ROOT));
  }
}
