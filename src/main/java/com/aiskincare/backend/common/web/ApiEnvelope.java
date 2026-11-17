package com.aiskincare.backend.common.web;

/** 성공 봉투 {@code { success: true, data }} (TRD §7.2). 컨트롤러는 항상 이것으로 감싸서 돌려준다. */
public record ApiEnvelope<T>(boolean success, T data) {
  public static <T> ApiEnvelope<T> ok(T data) {
    return new ApiEnvelope<>(true, data);
  }
}
