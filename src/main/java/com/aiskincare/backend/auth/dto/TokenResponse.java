package com.aiskincare.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;

/** 로그인·재발급 응답. refreshToken은 X-Client: ios일 때만 (없으면 필드 자체가 없다) */
@JsonPropertyOrder({"accessToken", "expiresIn", "refreshToken", "user"})
public record TokenResponse(
    @Schema(example = "eyJhbGciOi…") String accessToken,
    @Schema(example = "3600") long expiresIn,
    @Schema(
            description = "[앱 협의] `X-Client: ios` 요청일 때만. 쿠키 refresh_token과 같은 값",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String refreshToken,
    SessionUser user) {

  public record SessionUser(
      @Schema(example = "42") long id,
      @Schema(example = "user@example.com") String email,
      @Schema(example = "우주") String nickname) {}
}
