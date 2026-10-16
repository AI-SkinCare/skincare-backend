package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.aiclient.AiModels.DiseaseRecommendRequest;
import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;
import com.aiskincare.backend.analyses.NormalizedSurvey;
import com.aiskincare.backend.common.enums.Concern;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TRD §12.4 — 저장한 분류 + 클라이언트 설문 → POST /infer/disease-recommend 요청. 분류는 받은 값 그대로(6개 확률 포함),
 * concerns → selfReportedConcerns 라벨(키 이름도 바뀜, 없어도 []). 고민 코드를 그대로 보내면 AI가 인식하지 못한다. skinType은 코드
 * 그대로(AI가 코드·라벨 모두 인식). 미입력 선택 필드는 키를 생략하고 metrics는 보내지 않는다.
 */
public final class AiTransform {
  private AiTransform() {}

  public static Map<String, Object> toAiSurvey(NormalizedSurvey survey) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("gender", survey.gender().code());
    out.put("ageBand", survey.ageBand().code());
    if (survey.skinType() != null) out.put("skinType", survey.skinType().code());
    if (survey.sleepHours() != null) out.put("sleepHours", survey.sleepHours());
    if (survey.waterIntakeLevel() != null) {
      out.put("waterIntakeLevel", survey.waterIntakeLevel().code());
    }
    if (survey.sunExposureLevel() != null) {
      out.put("sunExposureLevel", survey.sunExposureLevel().code());
    }
    if (survey.stressLevel() != null) out.put("stressLevel", survey.stressLevel().code());
    if (!survey.currentProducts().isEmpty()) out.put("currentProducts", survey.currentProducts());
    out.put("selfReportedConcerns", survey.concerns().stream().map(Concern::label).toList());
    if (survey.note() != null && !survey.note().isEmpty()) out.put("note", survey.note());
    return out;
  }

  /** 저장한 분류 → AI 분류 JSON (snake_case). 값은 바꾸지 않는다 — AI가 확률 합·최고 점수 일치를 검증한다 */
  public static Map<String, Object> toAiDisease(DiseaseResult disease) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("label", disease.label().label());
    out.put("label_en", disease.label().code());
    out.put("confidence", disease.confidence());
    out.put("uncertain", disease.uncertain());
    out.put("probabilities", disease.probabilities());
    out.put("model_version", disease.modelVersion());
    out.put("inference_ms", disease.inferenceMs());
    return out;
  }

  public static DiseaseRecommendRequest buildRecommendRequest(
      DiseaseResult disease, NormalizedSurvey survey) {
    return new DiseaseRecommendRequest(toAiDisease(disease), toAiSurvey(survey));
  }
}
