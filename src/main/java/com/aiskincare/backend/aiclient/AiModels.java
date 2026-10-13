package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DeviceType;
import com.aiskincare.backend.common.enums.FixtureScenario;
import com.aiskincare.backend.common.enums.Metric;
import com.aiskincare.backend.common.enums.Roi;
import com.aiskincare.backend.common.enums.RoutineTime;
import com.aiskincare.backend.common.enums.SkinType;
import java.util.List;
import java.util.Map;

/** AI 서버 연동 모델 (TRD §12). AI 응답은 snake_case → 검증 후 이 타입들로 바꾼다. */
public final class AiModels {
  private AiModels() {}

  public record AiCallContext(String requestId, FixtureScenario scenario) {}

  public record AiHealth(String status, String skinBackend) {}

  /** 0–1 원본 지표 */
  public record MetricRatios(double moisture, double elasticity, double pigmentation, double pore) {
    public double get(Metric metric) {
      return switch (metric) {
        case MOISTURE -> moisture;
        case ELASTICITY -> elasticity;
        case PIGMENTATION -> pigmentation;
        case PORE -> pore;
      };
    }
  }

  public record MeasureSkinInput(byte[] image, String mimeType, DeviceType deviceType) {}

  /** 검증·camelCase 변환을 마친 측정 결과 (0–1 원본) */
  public record SkinMetricsResult(
      List<Roi> roiDetected,
      Map<Roi, MetricRatios> metrics,
      double confidence,
      boolean calibrationApplied,
      boolean isStub,
      String modelVersion,
      int inferenceMs) {}

  /**
   * AI 서버에 보내는 요청. metrics는 ROI 코드 → 지표(0–1), is_demo=true면 null (FR-MEA-04). survey는 한글 라벨로 바꾼
   * 설문(미입력 키 생략).
   */
  public record RecommendRequest(
      Map<String, Map<String, Double>> metrics, Map<String, Object> survey) {}

  public record Ingredient(String name, String purpose, int priority) {}

  public record RoutineStep(int step, RoutineTime time, String action) {}

  /** 검증을 마치고 라벨을 코드로 바꾼 추천 결과. warnings는 저장은 하되 WARN으로 남길 것 */
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
}
