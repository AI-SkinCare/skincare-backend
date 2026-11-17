package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DiseaseLabel;
import com.aiskincare.backend.common.enums.FixtureScenario;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.common.enums.RoutineTime;
import com.aiskincare.backend.common.enums.SkinType;
import java.util.List;
import java.util.Map;

/** AI 서버 연동 모델 (TRD §12). AI 응답은 snake_case → 검증 후 이 타입들로 바꾼다. */
public final class AiModels {
  private AiModels() {}

  public record AiCallContext(String requestId, FixtureScenario scenario) {}

  /** GET /health. 분류기(disease_loaded)가 없으면 접수를 받을 수 없다 (TRD §12.6) */
  public record AiHealth(String status, boolean diseaseLoaded) {}

  public record ClassifyInput(byte[] image, String mimeType) {}

  /**
   * POST /infer/disease 결과 (TRD §12.3). 값은 AI 원본 그대로 — 추천 호출·재시도 때 그대로 다시 보낸다. probabilities는
   * 라벨(한글) → 0–1, {@link DiseaseLabel} 순서.
   */
  public record DiseaseResult(
      DiseaseLabel label,
      double confidence,
      boolean uncertain,
      Map<String, Double> probabilities,
      String modelVersion,
      int inferenceMs) {}

  /**
   * POST /infer/disease-recommend 요청 (TRD §12.4). disease는 저장한 분류 JSON(snake_case), survey는 AI
   * 설문(미입력 키 생략). metrics는 보내지 않는다.
   */
  public record DiseaseRecommendRequest(Map<String, Object> disease, Map<String, Object> survey) {}

  public record Ingredient(String name, String purpose, int priority) {}

  public record RoutineStep(int step, RoutineTime time, String action) {}

  /** 검증을 마치고 라벨을 코드로 바꾼 추천. warnings는 저장은 하되 WARN으로 남길 것 */
  public record RecommendResult(
      SkinType skinType,
      List<Concern> concerns,
      List<Ingredient> ingredients,
      List<RoutineStep> routine,
      List<String> reasoning,
      String answer,
      String disclaimer,
      boolean filtered,
      boolean usedFallback,
      List<String> inputNotes,
      String modelVersion,
      int inferenceMs,
      List<String> warnings) {}

  /**
   * /infer/disease-recommend 결과. recommendation은 generated일 때만 있고 보류(uncertain·no_concern)면 null
   */
  public record DiseaseRecommendResult(
      RecommendationStatus status, RecommendResult recommendation, List<String> guidance) {}
}
