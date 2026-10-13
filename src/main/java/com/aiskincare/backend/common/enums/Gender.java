package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** gender (부록 A.7) */
public enum Gender implements CodedEnum {
  MALE("male"),
  FEMALE("female");

  private final String code;

  Gender(String code) {
    this.code = code;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  public static Gender fromCode(String code) {
    return CodedEnum.fromCode(Gender.class, code);
  }
}
