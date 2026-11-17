package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 피부 고민 8종 (부록 A.4) — 코드 ↔ AI 라벨의 단일 출처.
 *
 * <p>라벨은 AI 서버가 문자열 일치로 인식하므로 괄호·슬래시까지 한 글자도 바꾸지 않는다. 화면 표기(예: acne → 트러블)는 앱이 코드로 정한다.
 */
public enum Concern implements CodedEnum {
  PIGMENTATION("pigmentation", "미백(색소침착/기미/칙칙함)"),
  PORE("pore", "모공"),
  WRINKLE("wrinkle", "주름"),
  ACNE("acne", "여드름/뾰루지"),
  REDNESS("redness", "붉어짐(홍조)"),
  DRYNESS("dryness", "과각질/악건성"),
  SAGGING("sagging", "피부처짐/탄력저하"),
  SENSITIVITY("sensitivity", "민감성(트러블/자극감)");

  private final String code;
  private final String label;

  Concern(String code, String label) {
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

  public static Concern fromCode(String code) {
    return CodedEnum.fromCode(Concern.class, code);
  }

  public static Concern fromLabel(String label) {
    for (Concern value : values()) {
      if (value.label.equals(label)) return value;
    }
    return null;
  }
}
