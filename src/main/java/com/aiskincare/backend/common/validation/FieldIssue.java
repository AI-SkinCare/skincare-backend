package com.aiskincare.backend.common.validation;

import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.error.ErrorCode;
import java.util.List;
import java.util.Map;

/** E4002 detail의 필드 하나: {@code { field: "survey.gender", reason: "required" }} (TRD §7.3) */
public record FieldIssue(String field, String reason) {

  public static ApiException exception(List<FieldIssue> fields) {
    return new ApiException(ErrorCode.E4002, null, Map.of("fields", fields), null);
  }

  /** Bean Validation 제약 이름 → reason (소문자 snake_case). 값이 없으면 required */
  public static String reasonFor(String constraint, Object rejectedValue) {
    if (rejectedValue == null) return "required";
    return switch (constraint) {
      case "NotNull", "NotBlank", "NotEmpty" -> "required";
      case "Email", "Pattern" -> "invalid_format";
      case "AssertTrue", "AssertFalse" -> "invalid_value";
      case "Min", "Max", "Positive", "PositiveOrZero", "Range" -> "out_of_range";
      case "Size", "Length" -> "invalid_length";
      default -> constraint.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    };
  }
}
