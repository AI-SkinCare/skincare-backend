package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;
import com.aiskincare.backend.analyses.NormalizedSurvey;
import com.aiskincare.backend.common.enums.AgeBand;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DiseaseLabel;
import com.aiskincare.backend.common.enums.Gender;
import com.aiskincare.backend.common.enums.Level;
import com.aiskincare.backend.common.enums.SkinType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** TRD §12.4 변환 규칙 */
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

  private static DiseaseResult disease() {
    Map<String, Double> probabilities = new LinkedHashMap<>();
    for (DiseaseLabel label : DiseaseLabel.values()) {
      probabilities.put(label.label(), label == DiseaseLabel.ATOPIC ? 0.9 : 0.019999999999999997);
    }
    return new DiseaseResult(
        DiseaseLabel.ATOPIC, 0.9, false, probabilities, "densenet121_s2_3ea9506", 45);
  }

  @Test
  @DisplayName("skinType 코드 그대로, concerns → selfReportedConcerns 라벨 (concerns 키 없음)")
  void labels() {
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("gender", "female");
    expected.put("ageBand", "20s");
    expected.put("skinType", "combination");
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
  @DisplayName("미입력 선택 필드는 키를 생략, 고민이 없어도 selfReportedConcerns: []")
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
        .isEqualTo(Map.of("gender", "female", "ageBand", "20s", "selfReportedConcerns", List.of()));
  }

  @Test
  @DisplayName("(개정) 저장한 분류 JSON 전체를 snake_case로 그대로 보낸다 — 6개 확률 포함, metrics 없음")
  void diseaseSentAsStored() {
    var request = AiTransform.buildRecommendRequest(disease(), survey());
    Map<String, Object> d = request.disease();
    assertThat(d.keySet())
        .containsExactly(
            "label",
            "label_en",
            "confidence",
            "uncertain",
            "probabilities",
            "model_version",
            "inference_ms");
    assertThat(d)
        .containsEntry("label", "아토피")
        .containsEntry("label_en", "atopic")
        .containsEntry("confidence", 0.9)
        .containsEntry("uncertain", false)
        .containsEntry("model_version", "densenet121_s2_3ea9506")
        .containsEntry("inference_ms", 45);
    @SuppressWarnings("unchecked")
    var probabilities = (Map<String, Double>) d.get("probabilities");
    assertThat(probabilities).hasSize(6).containsEntry("건선", 0.019999999999999997);

    // 직렬화하면 disease·survey 두 키만 (metrics 없음), 값은 반올림되지 않는다
    var json = JsonMapper.builder().build();
    var sent = json.readTree(json.writeValueAsString(request));
    assertThat(sent.propertyNames()).containsExactly("disease", "survey");
    assertThat(sent.get("disease").get("probabilities").get("건선").doubleValue())
        .isEqualTo(0.019999999999999997);
  }
}
