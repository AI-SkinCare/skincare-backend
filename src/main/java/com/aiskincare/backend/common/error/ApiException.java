package com.aiskincare.backend.common.error;

import java.util.Map;

/**
 * 서비스 코드가 던지는 유일한 예외. 전역 핸들러가 봉투로 바꾼다: {@code throw new ApiException(ErrorCode.E4003)}.
 *
 * <p>detail에는 이미지·설문 원문·토큰을 넣지 않는다.
 */
public class ApiException extends RuntimeException {
  private final ErrorCode code;
  private final String userMessage;
  private final transient Object detail;
  private final Integer retryAfterSec;

  public ApiException(ErrorCode code) {
    this(code, null, null, null);
  }

  public ApiException(ErrorCode code, String message, Object detail, Integer retryAfterSec) {
    super(code.name());
    this.code = code;
    this.userMessage = message != null ? message : code.message();
    this.detail = detail;
    this.retryAfterSec = retryAfterSec != null ? retryAfterSec : code.retryAfterSec();
  }

  public static ApiException withMessage(ErrorCode code, String message) {
    return new ApiException(code, message, null, null);
  }

  public static ApiException rateLimited(RateLimitReason reason, int retryAfterSec) {
    return new ApiException(
        ErrorCode.E4004, reason.message(), Map.of("reason", reason.code()), retryAfterSec);
  }

  public ErrorCode code() {
    return code;
  }

  public String userMessage() {
    return userMessage;
  }

  public Object detail() {
    return detail;
  }

  public Integer retryAfterSec() {
    return retryAfterSec;
  }
}
