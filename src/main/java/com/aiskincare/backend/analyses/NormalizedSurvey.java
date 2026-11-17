package com.aiskincare.backend.analyses;

import com.aiskincare.backend.common.enums.AgeBand;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.Gender;
import com.aiskincare.backend.common.enums.Level;
import com.aiskincare.backend.common.enums.SkinType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 저장·응답용 정규화 설문 — 모든 키 포함, 미입력은 null, 배열은 [] (TRD §10.1). JSON 순서는 선언 순서. */
public record NormalizedSurvey(
    @Schema(implementation = Gender.class) Gender gender,
    @Schema(implementation = AgeBand.class) AgeBand ageBand,
    @Schema(nullable = true) SkinType skinType,
    @Schema(nullable = true, minimum = "0", maximum = "14") Integer sleepHours,
    @Schema(nullable = true) Level waterIntakeLevel,
    @Schema(nullable = true) Level sunExposureLevel,
    @Schema(nullable = true) Level stressLevel,
    List<Concern> concerns,
    List<String> currentProducts,
    @Schema(nullable = true, maxLength = 300) String note) {

  public NormalizedSurvey {
    concerns = concerns == null ? List.of() : List.copyOf(concerns);
    currentProducts = currentProducts == null ? List.of() : List.copyOf(currentProducts);
  }
}
