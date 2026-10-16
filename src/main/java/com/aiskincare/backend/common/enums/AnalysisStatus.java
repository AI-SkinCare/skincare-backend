package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 분석 작업 상태 (부록 A.1). 개정 B.1에 따라 JSON·DB 모두 대문자 — 다른 열거형의 소문자 규칙의 예외 */
public enum AnalysisStatus implements CodedEnum {
  QUEUED("QUEUED"),
  GENERATING("GENERATING"),
  COMPLETED("COMPLETED"),
  WITHHELD("WITHHELD"),
  FAILED("FAILED");

  private final String code;

  AnalysisStatus(String code) {
    this.code = code;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  public static AnalysisStatus fromCode(String code) {
    return CodedEnum.fromCode(AnalysisStatus.class, code);
  }

  public boolean inProgress() {
    return this == QUEUED || this == GENERATING;
  }
}
