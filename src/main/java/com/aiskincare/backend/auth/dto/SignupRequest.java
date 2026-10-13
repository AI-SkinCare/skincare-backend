package com.aiskincare.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** TRD §9.2 */
public record SignupRequest(
    @Schema(example = "user@example.com", maxLength = 255) @NotBlank @Size(max = 255) @Email
        String email,
    @Schema(example = "Skin!2026", minLength = 8, maxLength = 64)
        @NotBlank
        @Pattern(regexp = PASSWORD)
        String password,
    @Schema(example = "우주", minLength = 2, maxLength = 20) @NotBlank @Size(min = 2, max = 20)
        String nickname,
    @Schema(description = "4개 모두 true") @NotNull @Valid Agreements agreements) {

  /** 8–64자, 영문·숫자·특수문자 중 2종 이상. [구현 결정] 출력 가능한 ASCII(\x21-\x7E)만 → bcrypt 72바이트 한계를 넘지 않는다. */
  public static final String PASSWORD =
      "^(?:(?=.*[A-Za-z])(?=.*\\d)|(?=.*[A-Za-z])(?=.*[^A-Za-z\\d])|(?=.*\\d)(?=.*[^A-Za-z\\d]))[\\x21-\\x7E]{8,64}$";

  /** 이메일은 앞뒤 공백 제거 후 소문자, 닉네임은 앞뒤 공백 제거 */
  public SignupRequest {
    email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    nickname = nickname == null ? null : nickname.trim();
  }

  public record Agreements(
      @Schema(description = "이용약관 동의 — 반드시 true") @NotNull @AssertTrue Boolean terms,
      @Schema(description = "개인정보 수집·이용 동의 — 반드시 true") @NotNull @AssertTrue Boolean privacy,
      @Schema(description = "얼굴 이미지 처리 동의 — 반드시 true") @NotNull @AssertTrue Boolean imageProcessing,
      @Schema(description = "만 14세 이상 확인 — 반드시 true") @NotNull @AssertTrue Boolean over14) {}
}
