package com.aiskincare.backend.common.web;

import io.swagger.v3.oas.annotations.media.Schema;

/** 실패 봉투 {@code { success: false, error: { code, message, detail } }} (TRD §7.2) */
public record ErrorEnvelope(@Schema(example = "false") boolean success, ErrorBody error) {

  public record ErrorBody(
      @Schema(example = "E4002") String code,
      @Schema(description = "사용자에게 그대로 보여줘도 되는 문장", example = "입력값을 확인해 주세요.") String message,
      @Schema(
              description =
                  "개발용 상세 (화면 비표시). E4002: { fields: [{ field, reason }] }, E4004: { reason }",
              nullable = true)
          Object detail) {}

  public static ErrorEnvelope of(String code, String message, Object detail) {
    return new ErrorEnvelope(false, new ErrorBody(code, message, detail));
  }
}
