package com.aiskincare.backend.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** deviceType (부록 A.7) */
public enum DeviceType implements CodedEnum {
  SMARTPHONE("smartphone"),
  TABLET("tablet"),
  CAMERA("camera");

  private final String code;

  DeviceType(String code) {
    this.code = code;
  }

  @Override
  @JsonValue
  public String code() {
    return code;
  }

  public static DeviceType fromCode(String code) {
    return CodedEnum.fromCode(DeviceType.class, code);
  }
}
