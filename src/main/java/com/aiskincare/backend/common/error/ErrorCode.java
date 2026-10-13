package com.aiskincare.backend.common.error;

/** TRD §7.3 — 에러 코드·HTTP 상태·기본 문구의 단일 출처. 문구는 사용자에게 그대로 보여도 되는 문장이어야 하고 SC-05 §4 문구 규칙을 지킨다. */
public enum ErrorCode {
  E1001(401, "로그인이 필요해요. 다시 로그인해 주세요.", null),
  E1002(403, "접근 권한이 없어요.", null),
  E1003(401, "이메일 또는 비밀번호가 맞지 않아요.", null),
  E1004(409, "이미 가입된 이메일이에요.", null),
  E2001(422, "얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요.", null),
  E2002(422, "사진이 흐리거나 너무 어두워요. 밝은 곳에서 다시 찍어 주세요.", null),
  E2003(413, "사진 용량이 너무 커요. 10MB 이하로 올려 주세요.", null),
  E2004(415, "JPEG 또는 PNG 사진만 올릴 수 있어요.", null),
  E3001(503, "지금은 분석 서버 점검 중이에요. 잠시 후 다시 시도해 주세요.", 30),
  E3002(504, "추천을 만들지 못했어요. 다시 시도해 주세요.", null),
  E3003(503, "지금 요청이 많아요. 잠시 후 다시 시도해 주세요.", 60),
  E4001(500, "문제가 생겼어요. 잠시 후 다시 시도해 주세요.", null),
  E4002(422, "입력값을 확인해 주세요.", null),
  E4003(404, "결과를 찾을 수 없어요.", null),
  E4004(429, "요청이 너무 많아요. 잠시 후 다시 시도해 주세요.", null),
  E4005(409, "지금은 할 수 없는 요청이에요. 화면을 새로고침해 주세요.", null);

  /** E2002 중 해상도 부족 — TRD §10.1 처리 순서 6 [구현 결정] */
  public static final String LOW_RESOLUTION_MESSAGE = "사진 해상도가 낮아요. 다시 찍어 주세요.";

  private final int status;
  private final String message;
  private final Integer retryAfterSec;

  ErrorCode(int status, String message, Integer retryAfterSec) {
    this.status = status;
    this.message = message;
    this.retryAfterSec = retryAfterSec;
  }

  public int status() {
    return status;
  }

  public String message() {
    return message;
  }

  /** 기본 Retry-After(초). E4004는 사유별로 따로 넘긴다. */
  public Integer retryAfterSec() {
    return retryAfterSec;
  }

  /** 분석 failed 상태에 기록될 수 있는 코드 (TRD §10.2) */
  public boolean isAnalysisFailureCode() {
    return this == E3001 || this == E3002 || this == E3003 || this == E4001;
  }

  public static ErrorCode fromName(String name) {
    if (name == null) return null;
    for (ErrorCode code : values()) {
      if (code.name().equals(name)) return code;
    }
    return null;
  }
}
