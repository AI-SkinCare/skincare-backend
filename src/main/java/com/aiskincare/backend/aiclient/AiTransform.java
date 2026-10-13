package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.aiclient.AiModels.MetricRatios;
import com.aiskincare.backend.aiclient.AiModels.RecommendRequest;
import com.aiskincare.backend.analyses.AnalysisRows.SkinMetricRow;
import com.aiskincare.backend.analyses.NormalizedSurvey;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.Metric;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TRD §12.4 — 클라이언트 설문 → AI 설문. skinType 코드 → 라벨, concerns → selfReportedConcerns 라벨 (키 이름도 바뀜).
 * 코드를 그대로 보내면 AI가 인식하지 못한다. 미입력 선택 필드는 키를 생략한다.
 */
public final class AiTransform {
  private AiTransform() {}

  public static Map<String, Object> toAiSurvey(NormalizedSurvey survey) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("gender", survey.gender().code());
    out.put("ageBand", survey.ageBand().code());
    if (survey.skinType() != null) out.put("skinType", survey.skinType().label());
    if (survey.sleepHours() != null) out.put("sleepHours", survey.sleepHours());
    if (survey.waterIntakeLevel() != null) {
      out.put("waterIntakeLevel", survey.waterIntakeLevel().code());
    }
    if (survey.sunExposureLevel() != null) {
      out.put("sunExposureLevel", survey.sunExposureLevel().code());
    }
    if (survey.stressLevel() != null) out.put("stressLevel", survey.stressLevel().code());
    if (!survey.currentProducts().isEmpty()) out.put("currentProducts", survey.currentProducts());
    if (!survey.concerns().isEmpty()) {
      out.put("selfReportedConcerns", survey.concerns().stream().map(Concern::label).toList());
    }
    if (survey.note() != null && !survey.note().isEmpty()) out.put("note", survey.note());
    return out;
  }

  /** skin_metrics 행 → AI에 다시 넘길 0–1 지표 (모든 ROI 그대로, ROI 순서) */
  public static Map<String, Map<String, Double>> metricsFromRows(List<SkinMetricRow> rows) {
    Map<String, Map<String, Double>> out = new LinkedHashMap<>();
    rows.stream()
        .sorted(Comparator.comparingInt(row -> row.roi().ordinal()))
        .forEach(row -> out.put(row.roi().code(), ratiosToMap(row.ratios())));
    return out;
  }

  /** is_demo=true면 metrics는 null — 가짜 값이 피부 타입·고민 추정에 섞이지 않게 (FR-MEA-04) */
  public static RecommendRequest buildRecommendRequest(
      Map<String, Map<String, Double>> metrics, NormalizedSurvey survey) {
    return new RecommendRequest(metrics, toAiSurvey(survey));
  }

  private static Map<String, Double> ratiosToMap(MetricRatios ratios) {
    Map<String, Double> out = new LinkedHashMap<>();
    for (Metric metric : Metric.values()) out.put(metric.code(), ratios.get(metric));
    return out;
  }
}
