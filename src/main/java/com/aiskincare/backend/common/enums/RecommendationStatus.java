package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** recommendation.status (부록 A.1) */
public enum RecommendationStatus implements CodedEnum {
  PENDING("pending"),
  DONE("done"),
  FAILED("failed");

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
