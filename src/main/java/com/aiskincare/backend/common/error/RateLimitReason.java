package com.aiskincare.backend.common.error;

/** E4004 사유별 문구 (TRD §7.3) */
public enum RateLimitReason {
  IN_PROGRESS("in_progress", "진행 중인 분석이 끝난 뒤에 다시 시도해 주세요."),
  DAILY_LIMIT("daily_limit", "오늘 분석할 수 있는 횟수를 모두 사용했어요. 내일 다시 시도해 주세요."),
  LOGIN_RATE("login_rate", "로그인 시도가 너무 많아요. 1분 뒤에 다시 시도해 주세요.");

  private final String code;
  private final String message;

  RateLimitReason(String code, String message) {
    this.code = code;
    this.message = message;
  }

  public String code() {
    return code;
  }

  public String message() {
    return message;
  }
}
