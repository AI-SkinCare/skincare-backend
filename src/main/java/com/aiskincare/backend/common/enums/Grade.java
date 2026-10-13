package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 등급 (부록 A.6) */
public enum Grade implements CodedEnum {
  GOOD("good"),
  NORMAL("normal"),
  CARE("care");

  private final String code;

  Grade(String code) {
    this.code = code;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  public static Grade fromCode(String code) {
    return CodedEnum.fromCode(Grade.class, code);
  }
}
