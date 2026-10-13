package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** ROI (부록 A.2) — 선언 순서가 byRoi 응답 순서 */
public enum Roi implements CodedEnum {
  FULL_FACE("full_face"),
  FOREHEAD("forehead"),
  GLABELLA("glabella"),
  EYE_LEFT("eye_left"),
  EYE_RIGHT("eye_right"),
  CHEEK_LEFT("cheek_left"),
  CHEEK_RIGHT("cheek_right"),
  LIP("lip"),
  CHIN("chin");

  private final String code;

  Roi(String code) {
    this.code = code;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  public static Roi fromCode(String code) {
    return CodedEnum.fromCode(Roi.class, code);
  }
}
