package com.aiskincare.backend.analyses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiskincare.backend.aiclient.AiModels.MetricRatios;
import com.aiskincare.backend.analyses.Scoring.MetricBp;
import com.aiskincare.backend.common.enums.Grade;
import com.aiskincare.backend.common.enums.Metric;
import com.aiskincare.backend.common.enums.Roi;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** TRD §11.3 표를 그대로 옮긴다. */
class ScoringTest {
  private static MetricBp bp(double m, double e, double p, double o) {
    return MetricBp.of(new MetricRatios(m, e, p, o));
  }

  private static List<Integer> values(MetricBp bp) {
    return List.of(
        Scoring.valueFromBp(bp.moisture()),
        Scoring.valueFromBp(bp.elasticity()),
        Scoring.valueFromBp(bp.pigmentation()),
        Scoring.valueFromBp(bp.pore()));
  }

  @Test
  @DisplayName(
      "full_face (0.61, 0.74, 0.38, 0.45) → value 61,74,38,45 / score 61,74,62,55 / overall 63 normal")
  void fullFace() {
    var report = Scoring.report(Map.of(Roi.FULL_FACE, bp(0.61, 0.74, 0.38, 0.45)));
    assertThat(report.summary())
        .extracting(Scoring.SummaryItem::value)
        .containsExactly(61, 74, 38, 45);
    assertThat(report.summary())
        .extracting(Scoring.SummaryItem::score)
        .containsExactly(61, 74, 62, 55);
    assertThat(report.overallScore()).isEqualTo(63);
    assertThat(Scoring.grade(report.overallScore())).isEqualTo(Grade.NORMAL);
  }

  @Test
  @DisplayName("forehead (0.58, 0.71, 0.35, 0.52) → score 58,71,65,48 / overall 61 (60.5 → 61)")
  void forehead() {
    var forehead = bp(0.58, 0.71, 0.35, 0.52);
    assertThat(values(forehead)).containsExactly(58, 71, 35, 52);
    var report =
        Scoring.report(Map.of(Roi.FULL_FACE, bp(0.5, 0.5, 0.5, 0.5), Roi.FOREHEAD, forehead));
    var roi = report.byRoi().get(0);
    assertThat(roi).isEqualTo(new Scoring.RoiScore(Roi.FOREHEAD, 58, 71, 35, 52, 61, Grade.NORMAL));
    assertThat(
            List.of(
                Scoring.scoreFromValue(Metric.MOISTURE, 58),
                Scoring.scoreFromValue(Metric.ELASTICITY, 71),
                Scoring.scoreFromValue(Metric.PIGMENTATION, 35),
                Scoring.scoreFromValue(Metric.PORE, 52)))
        .containsExactly(58, 71, 65, 48);
  }

  @Test
  @DisplayName("cheek_left (0.66, 0.76, 0.41, 0.38) → overall 66 (65.75) normal")
  void cheekLeft() {
    var cheek = bp(0.66, 0.76, 0.41, 0.38);
    assertThat(values(cheek)).containsExactly(66, 76, 41, 38);
    assertThat(Scoring.overallFromBp(cheek)).isEqualTo(66);
  }

  @Test
  @DisplayName("함정: moisture 0.565 → value 57 (naive 56)")
  void trap0565() {
    assertThat(Math.round(0.565 * 100)).isEqualTo(56); // naive가 틀리는 것을 고정
    assertThat(Scoring.valueFromBp(Scoring.toBp(0.565))).isEqualTo(57);
  }

  @ParameterizedTest(name = "함정: {0} → {1}")
  @CsvSource({"0.145, 15", "0.285, 29", "0.565, 57", "0.575, 58"})
  void traps(double ratio, int expected) {
    assertThat(Scoring.valueFromBp(Scoring.toBp(ratio))).isEqualTo(expected);
  }

  @Test
  @DisplayName("함정: (0.8906, 0.971, 0.9249, 0.5167) → overall 61 (naive 60) normal")
  void trapOverall() {
    double naive = (0.8906 * 100 + 0.971 * 100 + (100 - 0.9249 * 100) + (100 - 0.5167 * 100)) / 4;
    assertThat(Math.round(naive - 1e-12)).isEqualTo(60);
    assertThat(Scoring.overallFromBp(bp(0.8906, 0.971, 0.9249, 0.5167))).isEqualTo(61);
  }

  @ParameterizedTest(name = "경계: score {0} → {1}")
  @CsvSource({"70, GOOD", "69, NORMAL", "40, NORMAL", "39, CARE"})
  void boundaries(int score, Grade grade) {
    assertThat(Scoring.grade(score)).isEqualTo(grade);
  }

  @Test
  @DisplayName("summary는 moisture → elasticity → pigmentation → pore, byRoi는 full_face 제외·순서 고정")
  void order() {
    var m = bp(0.5, 0.5, 0.5, 0.5);
    Map<Roi, MetricBp> byRoi = new EnumMap<>(Roi.class);
    byRoi.put(Roi.CHIN, m);
    byRoi.put(Roi.FULL_FACE, m);
    byRoi.put(Roi.CHEEK_LEFT, m);
    byRoi.put(Roi.FOREHEAD, m);
    byRoi.put(Roi.GLABELLA, m);
    var report = Scoring.report(byRoi);
    assertThat(report.summary())
        .extracting(Scoring.SummaryItem::metric)
        .containsExactly(Metric.MOISTURE, Metric.ELASTICITY, Metric.PIGMENTATION, Metric.PORE);
    assertThat(report.byRoi())
        .extracting(Scoring.RoiScore::roi)
        .containsExactly(Roi.FOREHEAD, Roi.GLABELLA, Roi.CHEEK_LEFT, Roi.CHIN);
  }

  @Test
  @DisplayName("full_face가 없으면 검출된 ROI 평균 (나눗셈 한 번)")
  void averageWithoutFullFace() {
    var report =
        Scoring.report(
            Map.of(
                Roi.FOREHEAD,
                bp(0.58, 0.71, 0.35, 0.52),
                Roi.CHEEK_LEFT,
                bp(0.66, 0.76, 0.41, 0.38)));
    // moisture (5800+6600)/200 = 62, elasticity 73.5 → 74, pigmentation 38, pore 45
    assertThat(report.summary())
        .extracting(Scoring.SummaryItem::value)
        .containsExactly(62, 74, 38, 45);
    // (24200 + 26300) / 800 = 63.125 → 63
    assertThat(report.overallScore()).isEqualTo(63);
  }

  @Test
  void emptyThrows() {
    assertThatThrownBy(() -> Scoring.report(Map.of())).isInstanceOf(IllegalArgumentException.class);
  }
}
