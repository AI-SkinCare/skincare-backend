package com.aiskincare.backend.analyses;

import com.aiskincare.backend.aiclient.AiModels.MetricRatios;
import com.aiskincare.backend.common.enums.Grade;
import com.aiskincare.backend.common.enums.Metric;
import com.aiskincare.backend.common.enums.Roi;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * TRD §11 — 점수·등급 순수 함수. 측정 저장(overall_score)과 응답(summary·byRoi)이 같은 함수를 쓴다.
 *
 * <p>{@code 0.565 * 100}은 56.49999…라 반올림이 56을 낸다(정답 57). 그래서 0–1 값을 정수 basis point(bp, 0–10000)로
 * 확정한 뒤 정수끼리 계산하고, 나눗셈은 마지막에 한 번만 한다. 반올림은 0.5 올림 (JavaScript Math.round와 같은 {@link Math#floorDiv}식
 * 처리).
 */
public final class Scoring {
  private Scoring() {}

  /** 지표 4개의 bp (0–10000) */
  public record MetricBp(int moisture, int elasticity, int pigmentation, int pore) {
    public int get(Metric metric) {
      return switch (metric) {
        case MOISTURE -> moisture;
        case ELASTICITY -> elasticity;
        case PIGMENTATION -> pigmentation;
        case PORE -> pore;
      };
    }

    public static MetricBp of(MetricRatios ratios) {
      return new MetricBp(
          toBp(ratios.moisture()),
          toBp(ratios.elasticity()),
          toBp(ratios.pigmentation()),
          toBp(ratios.pore()));
    }
  }

  public record SummaryItem(Metric metric, int value, int score, Grade grade) {}

  public record RoiScore(
      Roi roi,
      int moisture,
      int elasticity,
      int pigmentation,
      int pore,
      int overallScore,
      Grade grade) {}

  public record ScoreReport(int overallScore, List<SummaryItem> summary, List<RoiScore> byRoi) {}

  /** 0–1 원본 → bp. AI 원본(4자리 초과 가능)과 DB 값(4자리) 모두 이것으로 복원한다 */
  public static int toBp(double ratio) {
    return (int) Math.round(ratio * 10000);
  }

  /** bp → 저장할 0–1 값 (4자리 확정) */
  public static double bpToRatio(int bp) {
    return bp / 10000.0;
  }

  /** 0.5 올림 반올림 — 분자·분모가 정수라 .5가 정확히 표현된다 */
  static int roundDiv(long numerator, long denominator) {
    return (int) Math.floorDiv(2 * numerator + denominator, 2 * denominator);
  }

  public static Grade grade(int score) {
    if (score >= 70) return Grade.GOOD;
    if (score >= 40) return Grade.NORMAL;
    return Grade.CARE;
  }

  /** 표시용 정수 value (0–100) */
  public static int valueFromBp(int bp) {
    return roundDiv(bp, 100);
  }

  /** 좋은 방향으로 통일한 정수 score */
  public static int scoreFromValue(Metric metric, int value) {
    return metric.goodDirection() ? value : 100 - value;
  }

  private static int scoreBp(Metric metric, int bp) {
    return metric.goodDirection() ? bp : 10000 - bp;
  }

  /** ROI 하나의 종합 점수 = round(평균(반올림 전 score 4개)) */
  public static int overallFromBp(MetricBp bp) {
    long sum = 0;
    for (Metric metric : Metric.values()) sum += scoreBp(metric, bp.get(metric));
    return roundDiv(sum, 400);
  }

  /**
   * ROI별 지표(bp) → 종합 점수·summary·byRoi. 종합은 full_face 기준, 없으면 검출된 ROI 평균(지표별 합으로 계산해 나눗셈 한 번).
   * byRoi는 full_face 제외·순서 고정.
   */
  public static ScoreReport report(Map<Roi, MetricBp> metricsByRoi) {
    MetricBp fullFace = metricsByRoi.get(Roi.FULL_FACE);
    List<Roi> detected = new ArrayList<>();
    for (Roi roi : Roi.values()) {
      if (roi != Roi.FULL_FACE && metricsByRoi.containsKey(roi)) detected.add(roi);
    }

    int overall;
    Map<Metric, Integer> values = new EnumMap<>(Metric.class);
    if (fullFace != null) {
      overall = overallFromBp(fullFace);
      for (Metric metric : Metric.values()) values.put(metric, valueFromBp(fullFace.get(metric)));
    } else if (!detected.isEmpty()) {
      int n = detected.size();
      long scoreSum = 0;
      for (Metric metric : Metric.values()) {
        long sumBp = 0;
        for (Roi roi : detected) sumBp += metricsByRoi.get(roi).get(metric);
        values.put(metric, roundDiv(sumBp, 100L * n));
        scoreSum += metric.goodDirection() ? sumBp : 10000L * n - sumBp;
      }
      overall = roundDiv(scoreSum, 400L * n);
    } else {
      throw new IllegalArgumentException("지표가 있는 ROI가 없습니다");
    }

    List<SummaryItem> summary = new ArrayList<>();
    for (Metric metric : Metric.values()) {
      int value = values.get(metric);
      int score = scoreFromValue(metric, value);
      summary.add(new SummaryItem(metric, value, score, grade(score)));
    }

    List<RoiScore> byRoi = new ArrayList<>();
    for (Roi roi : detected) {
      MetricBp bp = metricsByRoi.get(roi);
      int roiOverall = overallFromBp(bp);
      byRoi.add(
          new RoiScore(
              roi,
              valueFromBp(bp.moisture()),
              valueFromBp(bp.elasticity()),
              valueFromBp(bp.pigmentation()),
              valueFromBp(bp.pore()),
              roiOverall,
              grade(roiOverall)));
    }
    return new ScoreReport(overall, List.copyOf(summary), List.copyOf(byRoi));
  }
}
