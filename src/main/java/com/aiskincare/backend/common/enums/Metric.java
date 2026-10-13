package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 지표 (부록 A.7) — 선언 순서가 summary 응답 순서. pigmentation·pore는 높을수록 나쁨 */
public enum Metric implements CodedEnum {
  MOISTURE("moisture"),
  ELASTICITY("elasticity"),
  PIGMENTATION("pigmentation"),
  PORE("pore");

  private final String code;

  Metric(String code) {
    this.code = code;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  public static Metric fromCode(String code) {
    return CodedEnum.fromCode(Metric.class, code);
  }

  /** 값이 높을수록 좋은 지표 */
  public boolean goodDirection() {
    return this == MOISTURE || this == ELASTICITY;
  }
}
