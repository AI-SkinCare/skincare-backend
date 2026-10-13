package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 분석 상태 (부록 A.1) */
public enum AnalysisStatus implements CodedEnum {
  QUEUED("queued"),
  RECOMMENDING("recommending"),
  DONE("done"),
  FAILED("failed");

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
    return this == QUEUED || this == RECOMMENDING;
  }
}
