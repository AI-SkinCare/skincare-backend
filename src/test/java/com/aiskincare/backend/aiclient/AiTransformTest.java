package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.aiclient.AiModels.MetricRatios;
import com.aiskincare.backend.analyses.AnalysisRows.SkinMetricRow;
import com.aiskincare.backend.analyses.NormalizedSurvey;
import com.aiskincare.backend.common.enums.AgeBand;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.Gender;
import com.aiskincare.backend.common.enums.Level;
import com.aiskincare.backend.common.enums.Roi;
import com.aiskincare.backend.common.enums.SkinType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TRD §12.4 변환 규칙, SC-03 §5.2 모양 */
class AiTransformTest {
  private static NormalizedSurvey survey() {
    return new NormalizedSurvey(
        Gender.FEMALE,
        AgeBand.TWENTIES,
        SkinType.COMBINATION,
        6,
        Level.LOW,
        Level.HIGH,
        Level.MEDIUM,
        List.of(Concern.PORE, Concern.PIGMENTATION),
        List.of("토너", "수분크림"),
        "여름에 번들거림이 심해요");
  }

  @Test
  @DisplayName("skinType 코드 → 라벨, concerns → selfReportedConcerns 라벨 (concerns 키 없음)")
  void labels() {
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("gender", "female");
    expected.put("ageBand", "20s");
    expected.put("skinType", "복합성");
    expected.put("sleepHours", 6);
    expected.put("waterIntakeLevel", "low");
    expected.put("sunExposureLevel", "high");
    expected.put("stressLevel", "medium");
    expected.put("currentProducts", List.of("토너", "수분크림"));
    expected.put("selfReportedConcerns", List.of("모공", "미백(색소침착/기미/칙칙함)"));
    expected.put("note", "여름에 번들거림이 심해요");
    var result = AiTransform.toAiSurvey(survey());
    assertThat(result).containsExactlyEntriesOf(expected);
    assertThat(result).doesNotContainKey("concerns");
  }

  @Test
  @DisplayName("미입력 선택 필드는 키를 생략")
  void omitEmpty() {
    var empty =
        new NormalizedSurvey(
            Gender.FEMALE,
            AgeBand.TWENTIES,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            null);
    assertThat(AiTransform.toAiSurvey(empty))
        .isEqualTo(Map.of("gender", "female", "ageBand", "20s"));
  }

  @Test
  @DisplayName("저장된 모든 ROI를 0–1 그대로, ROI 순서로 넘긴다")
  void metrics() {
    var rows =
        List.of(
            new SkinMetricRow(Roi.FOREHEAD, new MetricRatios(0.58, 0.71, 0.35, 0.52)),
            new SkinMetricRow(Roi.FULL_FACE, new MetricRatios(0.61, 0.74, 0.38, 0.45)));
    var metrics =
        AiTransform.buildRecommendRequest(AiTransform.metricsFromRows(rows), survey()).metrics();
    assertThat(metrics.keySet()).containsExactly("full_face", "forehead");
    assertThat(metrics.get("full_face"))
        .isEqualTo(
            Map.of("moisture", 0.61, "elasticity", 0.74, "pigmentation", 0.38, "pore", 0.45));
    assertThat(metrics.get("full_face").keySet())
        .containsExactly("moisture", "elasticity", "pigmentation", "pore");
    assertThat(metrics.get("forehead")).containsEntry("pore", 0.52);
  }

  @Test
  @DisplayName("TC-ANA-09: 데모 측정이면 metrics: null")
  void demo() {
    assertThat(AiTransform.buildRecommendRequest(null, survey()).metrics()).isNull();
  }
}
