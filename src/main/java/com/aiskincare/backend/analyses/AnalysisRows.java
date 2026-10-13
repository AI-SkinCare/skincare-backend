package com.aiskincare.backend.analyses;

import com.aiskincare.backend.aiclient.AiModels.Ingredient;
import com.aiskincare.backend.aiclient.AiModels.MetricRatios;
import com.aiskincare.backend.aiclient.AiModels.RoutineStep;
import com.aiskincare.backend.common.enums.AnalysisStatus;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DeviceType;
import com.aiskincare.backend.common.enums.Roi;
import com.aiskincare.backend.common.enums.SkinType;
import java.time.Instant;
import java.util.List;

/** DB 행 (TRD §8.1). 0–1 원본은 NUMERIC → double로 읽는다. */
public final class AnalysisRows {
  private AnalysisRows() {}

  public record AnalysisRow(
      String id,
      long userId,
      AnalysisStatus status,
      DeviceType deviceType,
      boolean isDemo,
      double confidence,
      boolean calibrationApplied,
      int overallScore,
      String skinModelVersion,
      int measureMs,
      Instant queuedAt,
      Instant completedAt,
      int attemptCount,
      String errorCode,
      Instant createdAt) {}

  public record SkinMetricRow(Roi roi, MetricRatios ratios) {}

  public record RecommendationRow(
      SkinType skinType,
      List<Concern> concerns,
      List<Ingredient> ingredients,
      List<RoutineStep> routine,
      List<String> reasoning,
      String answer,
      String disclaimer,
      List<String> inputNotes,
      boolean filtered,
      boolean usedFallback,
      String modelVersion) {}
}
