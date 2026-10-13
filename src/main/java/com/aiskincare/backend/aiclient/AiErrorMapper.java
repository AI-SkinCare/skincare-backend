package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.common.error.ErrorCode;
import org.slf4j.event.Level;

/**
 * TRD §12.5 — AI 결과 → 백엔드 코드.
 *
 * <pre>
 * | AI 결과                | 측정 (POST /analyses 안)    | 추천 (워커)                       |
 * | 401 E1001             | 503 E3001 + ERROR          | failed E3001 + ERROR             |
 * | 413 E2003 / 415 E2004 | 그대로                      | —                                |
 * | 422 E2001 / E2002     | 그대로 (AI 문구 우선)         | —                                |
 * | 422 E4002             | 500 E4001 + ERROR          | failed E4001, 재시도 없음          |
 * | 503 E3003             | 그대로                      | 5초 후 1회 재시도 → failed E3003    |
 * | 504 E3002             | 그대로                      | 재시도 없이 failed E3002           |
 * | connection            | 즉시 1회 재시도 → 503 E3001   | 5초 후 1회 재시도 → failed E3001    |
 * | timeout (우리 쪽·524)   | 503 E3001                  | 재시도 없이 failed E3002           |
 * | invalid_response      | 500 E4001 + ERROR          | failed E4001 + ERROR             |
 * | 그 밖의 4xx/5xx         | 500 E4001 + ERROR          | failed E4001 + ERROR             |
 * </pre>
 *
 * 사용자 사진 문제(E2xxx)는 INFO, AI·연결 문제는 WARN, 백엔드·계약 문제는 ERROR로 남긴다.
 */
public final class AiErrorMapper {
  private AiErrorMapper() {}

  public record MeasureFailure(ErrorCode code, Level logLevel, String message) {}

  public record RecommendFailure(ErrorCode code, Level logLevel) {}

  private static final MeasureFailure INTERNAL =
      new MeasureFailure(ErrorCode.E4001, Level.ERROR, null);

  public static MeasureFailure measure(AiError error) {
    switch (error.kind()) {
      case CONNECTION, TIMEOUT:
        return new MeasureFailure(ErrorCode.E3001, Level.WARN, null);
      case INVALID_RESPONSE:
        return INTERNAL;
      case HTTP:
        break;
    }
    int status = error.status() == null ? 0 : error.status();
    String code = error.code();
    return switch (status) {
      // 401을 그대로 내보내면 앱이 로그아웃시킨다
      case 401 -> new MeasureFailure(ErrorCode.E3001, Level.ERROR, null);
      case 413 -> new MeasureFailure(ErrorCode.E2003, Level.INFO, null);
      case 415 -> new MeasureFailure(ErrorCode.E2004, Level.INFO, null);
      case 422 ->
          "E2001".equals(code) || "E2002".equals(code)
              ? new MeasureFailure(
                  ErrorCode.valueOf(code),
                  Level.INFO,
                  error.aiMessage() == null || error.aiMessage().isBlank()
                      ? null
                      : error.aiMessage())
              : INTERNAL; // E4002 = 백엔드 변환 버그
      case 503 ->
          "E3003".equals(code) ? new MeasureFailure(ErrorCode.E3003, Level.WARN, null) : INTERNAL;
      case 504 ->
          "E3002".equals(code) ? new MeasureFailure(ErrorCode.E3002, Level.WARN, null) : INTERNAL;
      default -> INTERNAL;
    };
  }

  /** 측정: 연결 오류만 즉시 1회 재시도 */
  public static boolean measureRetryable(AiError error) {
    return error.kind() == AiError.Kind.CONNECTION;
  }

  public static RecommendFailure recommend(AiError error) {
    switch (error.kind()) {
      case CONNECTION:
        return new RecommendFailure(ErrorCode.E3001, Level.WARN);
      case TIMEOUT:
        return new RecommendFailure(ErrorCode.E3002, Level.WARN);
      case INVALID_RESPONSE:
        return new RecommendFailure(ErrorCode.E4001, Level.ERROR);
      case HTTP:
        break;
    }
    int status = error.status() == null ? 0 : error.status();
    if (status == 401) return new RecommendFailure(ErrorCode.E3001, Level.ERROR);
    if (status == 503 && "E3003".equals(error.code())) {
      return new RecommendFailure(ErrorCode.E3003, Level.WARN);
    }
    if (status == 504 && "E3002".equals(error.code())) {
      return new RecommendFailure(ErrorCode.E3002, Level.WARN);
    }
    return new RecommendFailure(ErrorCode.E4001, Level.ERROR);
  }

  /** 추천: 연결 오류·503만 5초 후 1회. 504·422·우리 쪽 타임아웃은 재시도 금지 */
  public static boolean recommendRetryable(AiError error) {
    return error.kind() == AiError.Kind.CONNECTION
        || (error.kind() == AiError.Kind.HTTP && Integer.valueOf(503).equals(error.status()));
  }
}
