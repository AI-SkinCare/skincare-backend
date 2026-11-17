package com.aiskincare.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** [앱 협의, TRD §9.1] 쿠키가 없을 때만 쓰는 본문 토큰 */
public record RefreshRequest(
    @Schema(description = "쿠키 refresh_token이 없을 때만 사용 (네이티브 앱)") @Size(max = 2048)
        String refreshToken) {}
