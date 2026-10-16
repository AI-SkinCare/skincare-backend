package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * recommendationStatus (부록 A.1) — AI {@code recommendation_status} 값 그대로. 보류
 * 판단(uncertain·no_concern)은 AI가 하고 백엔드는 복제하지 않는다 (개정 B.1).
 */
public enum RecommendationStatus implements CodedEnum {
  GENERATED("generated"),
  UNCERTAIN("uncertain"),
  NO_CONCERN("no_concern");

  private final String code;

  RecommendationStatus(String code) {
    this.code = code;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  public static RecommendationStatus fromCode(String code) {
    return CodedEnum.fromCode(RecommendationStatus.class, code);
  }
}
