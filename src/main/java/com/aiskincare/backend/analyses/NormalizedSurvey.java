package com.aiskincare.backend.analyses;

import com.aiskincare.backend.common.enums.AgeBand;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.Gender;
import com.aiskincare.backend.common.enums.Level;
import com.aiskincare.backend.common.enums.SkinType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 저장·응답용 정규화 설문. 미입력 일반 값은 null, 배열은 [], 선택형 현재 피부 상태는 생략한다. */
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
    @Schema(nullable = true, maxLength = 300) String note,
    @Schema(description = "피할 성분. 최대 30개, 각 1–100자") List<String> avoidIngredients,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "현재 분석에만 기록하는 자가 응답. 기존 분석에는 생략")
        SymptomProfile symptomProfile) {

  public NormalizedSurvey(
      Gender gender,
      AgeBand ageBand,
      SkinType skinType,
      Integer sleepHours,
      Level waterIntakeLevel,
      Level sunExposureLevel,
      Level stressLevel,
      List<Concern> concerns,
      List<String> currentProducts,
      String note,
      List<String> avoidIngredients) {
    this(
        gender,
        ageBand,
        skinType,
        sleepHours,
        waterIntakeLevel,
        sunExposureLevel,
        stressLevel,
        concerns,
        currentProducts,
        note,
        avoidIngredients,
        null);
  }

  public NormalizedSurvey(
      Gender gender,
      AgeBand ageBand,
      SkinType skinType,
      Integer sleepHours,
      Level waterIntakeLevel,
      Level sunExposureLevel,
      Level stressLevel,
      List<Concern> concerns,
      List<String> currentProducts,
      String note) {
    this(
        gender,
        ageBand,
        skinType,
        sleepHours,
        waterIntakeLevel,
        sunExposureLevel,
        stressLevel,
        concerns,
        currentProducts,
        note,
        List.of());
  }

  public NormalizedSurvey {
    concerns = concerns == null ? List.of() : List.copyOf(concerns);
    currentProducts = currentProducts == null ? List.of() : List.copyOf(currentProducts);
    avoidIngredients = avoidIngredients == null ? List.of() : List.copyOf(avoidIngredients);
    if (symptomProfile != null && !symptomProfile.hasAnswers()) symptomProfile = null;
  }
}
