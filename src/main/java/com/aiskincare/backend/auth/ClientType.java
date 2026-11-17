package com.aiskincare.backend.auth;

import java.util.Locale;
import java.util.Set;

/**
 * TRD §9.1 [앱 협의] — 리프레시 토큰을 응답 본문으로도 받는 네이티브 앱. Android를 만들면 "android"를 여기에만 추가한다 (TRD 표를 먼저 고칠
 * 것).
 */
public final class ClientType {
  private ClientType() {}

  public static final String HEADER = "X-Client";
  public static final Set<String> NATIVE_CLIENTS = Set.of("ios");

  public static boolean isNative(String header) {
    return header != null && NATIVE_CLIENTS.contains(header.trim().toLowerCase(Locale.ROOT));
  }
}
