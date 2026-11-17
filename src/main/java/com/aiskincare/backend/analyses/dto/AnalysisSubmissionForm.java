package com.aiskincare.backend.analyses.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** POST /analyses multipart 본문 — 문서용 (TRD §10.1). 실제 파싱·검증은 {@code Submission}이 한다. */
@Schema(
    name = "AnalysisSubmission",
    requiredProperties = {"image", "survey"})
public record AnalysisSubmissionForm(
    @Schema(type = "string", format = "binary", description = "JPEG/PNG, 10MB 이하, 짧은 변 720px 이상")
        String image,
    // example에 JSON을 넣으면 문서 생성기가 객체로 바꿔 버려서 설명에 적는다
    @Schema(
            description =
                "JSON 문자열. 구조는 NormalizedSurvey (필수 gender·ageBand, 나머지 선택)."
                    + " 예: {\"gender\":\"female\",\"ageBand\":\"20s\",\"concerns\":[\"pore\"]}")
        String survey) {}
