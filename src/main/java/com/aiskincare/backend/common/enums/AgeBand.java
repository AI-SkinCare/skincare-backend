package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** ageBand (부록 A.7) */
public enum AgeBand implements CodedEnum {
  TEENS("10s"),
  TWENTIES("20s"),
  THIRTIES("30s"),
  FORTIES("40s"),
  FIFTIES("50s"),
  SIXTIES("60s"),
  SEVENTIES("70s");

  private final String code;

  AgeBand(String code) {
    this.code = code;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  public static AgeBand fromCode(String code) {
    return CodedEnum.fromCode(AgeBand.class, code);
  }
}
