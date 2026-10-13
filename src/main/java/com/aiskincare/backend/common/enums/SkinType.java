package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 피부 타입 (부록 A.3) — 코드 ↔ AI 라벨의 단일 출처.
 *
 * <p>AI 서버는 라벨(또는 영문 코드)을 준다.
 */
public enum SkinType implements CodedEnum {
  DRY("dry", "건성"),
  NORMAL("normal", "중성"),
  OILY("oily", "지성"),
  COMBINATION("combination", "복합성");

  private final String code;
  private final String label;

  SkinType(String code, String label) {
    this.code = code;
    this.label = label;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  /** AI 서버 값 */
  public String label() {
    return label;
  }

  public static SkinType fromCode(String code) {
    return CodedEnum.fromCode(SkinType.class, code);
  }

  public static SkinType fromLabel(String label) {
    for (SkinType value : values()) {
      if (value.label.equals(label)) return value;
    }
    return null;
  }
}
