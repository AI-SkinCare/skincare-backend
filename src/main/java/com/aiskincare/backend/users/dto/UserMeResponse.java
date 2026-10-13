package com.aiskincare.backend.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserMeResponse(
    @Schema(example = "42") long id,
    @Schema(example = "user@example.com") String email,
    @Schema(example = "우주") String nickname,
    @Schema(example = "2026-10-03T02:11:00Z") String createdAt,
    @Schema(example = "3", description = "분석 수 (상태 무관)") long analysisCount) {}
