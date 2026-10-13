package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** AI_MODE=fixture 시나리오 (TRD §12.7) */
public enum FixtureScenario implements CodedEnum {
  OK("ok"),
  NO_FACE("no-face"),
  REC_TIMEOUT("rec-timeout"),
  REC_SLOW("rec-slow"),
  AI_DOWN("ai-down");

  private final String code;

  FixtureScenario(String code) {
    this.code = code;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  public static FixtureScenario fromCode(String code) {
    return CodedEnum.fromCode(FixtureScenario.class, code);
  }
}
