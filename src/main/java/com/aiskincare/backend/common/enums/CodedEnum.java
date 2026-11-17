package com.aiskincare.backend.common.enums;

/** JSON·DB에 코드로 나가는 열거형 (TRD §7.4). 코드는 소문자 snake_case — 분석 상태만 대문자(개정 B.1). */
public interface CodedEnum {
  String code();

  static <E extends Enum<E> & CodedEnum> E fromCode(Class<E> type, String code) {
    if (code == null) return null;
    for (E value : type.getEnumConstants()) {
      if (value.code().equals(code)) return value;
    }
    return null;
  }
}
