package com.aiskincare.backend.common.web;

/** AuthInterceptor가 요청 속성에 넣는 인증된 사용자 */
public record AuthUser(long id) {
  public static final String REQUEST_ATTRIBUTE = AuthUser.class.getName();
}
