package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 수분 섭취·자외선·스트레스 수준 (부록 A.7) */
public enum Level implements CodedEnum {
  LOW("low"),
  MEDIUM("medium"),
  HIGH("high");

  private final String code;

  Level(String code) {
    this.code = code;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  public static Level fromCode(String code) {
    return CodedEnum.fromCode(Level.class, code);
  }
}
