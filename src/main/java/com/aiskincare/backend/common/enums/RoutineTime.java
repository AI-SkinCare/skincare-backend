package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 루틴 시간 (부록 A.5) */
public enum RoutineTime implements CodedEnum {
  MORNING("morning"),
  EVENING("evening"),
  DAILY("daily"),
  WEEKLY("weekly"),
  ANYTIME("anytime");

  private final String code;

  RoutineTime(String code) {
    this.code = code;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  public static RoutineTime fromCode(String code) {
    return CodedEnum.fromCode(RoutineTime.class, code);
  }
}
