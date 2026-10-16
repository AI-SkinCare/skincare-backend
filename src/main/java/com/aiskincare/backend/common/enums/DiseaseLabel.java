package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 이미지 분류 라벨 6종 (부록 A.2) — 코드(AI {@code label_en}) ↔ 라벨(AI {@code label})의 단일 출처. 선언 순서가 AI 클래스 순서이자
 * {@code probabilities} 응답 순서다.
 *
 * <p>분류 결과는 참고 정보다. 라벨 → 추천 고민 매핑은 AI 서버 규칙이라 여기에 두지 않는다 (개정 B.1).
 */
public enum DiseaseLabel implements CodedEnum {
  PSORIASIS("psoriasis", "건선"),
  ATOPIC("atopic", "아토피"),
  ACNE("acne", "여드름"),
  NORMAL("normal", "정상"),
  ROSACEA("rosacea", "주사"),
  SEBORRHEIC("seborrheic", "지루");

  private final String code;
  private final String label;

  DiseaseLabel(String code, String label) {
    this.code = code;
    this.label = label;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  /** AI 서버 값 (probabilities의 키) */
  public String label() {
    return label;
  }

  public static DiseaseLabel fromCode(String code) {
    return CodedEnum.fromCode(DiseaseLabel.class, code);
  }

  public static DiseaseLabel fromLabel(String label) {
    for (DiseaseLabel value : values()) {
      if (value.label.equals(label)) return value;
    }
    return null;
  }
}
