package com.aiskincare.backend.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record SystemStatusResponse(
    @Schema(example = "true") boolean aiAvailable,
    @Schema(example = "3", description = "queued 건수") long queueLength,
    @Schema(example = "165", description = "round(queueLength × 최근 평균 추천ms / 1000)")
        long estimatedWaitSec,
    @Schema(
            example = "2026-10-15T09:12:00Z",
            nullable = true,
            description = "마지막 헬스체크 시각. 첫 검사 전에는 null")
        String checkedAt) {}
