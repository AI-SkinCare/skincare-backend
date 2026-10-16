package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.aiskincare.backend.aiclient.AiError.Issue;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DiseaseLabel;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.common.enums.SkinType;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 계약 테스트 — skincare-ai 기록(fixture)이 검증·변환을 통과해야 한다 (TRD §16.1) */
class AiResponseParserTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ObjectNode fixture(String name) {
    try (var in = new ClassPathResource("fixtures/ai/disease-pipeline/" + name).getInputStream()) {
      return (ObjectNode) JSON.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ObjectNode generated() {
    return fixture("generated.response.json");
  }

  private static ObjectNode disease(String fixture) {
    return (ObjectNode) fixture(fixture).get("disease");
  }

  private static List<Issue> issues(Runnable parse) {
    AiError error = catchThrowableOfType(AiError.class, parse::run);
    assertThat(error).as("검증이 통과해 버렸다").isNotNull();
    assertThat(error.kind()).isEqualTo(AiError.Kind.INVALID_RESPONSE);
    return error.issues();
  }

  @Test
  @DisplayName("fixture 계약: generated → 추천 객체, 라벨이 코드로 (purpose null 포함)")
  void generatedFixture() {
    var result = AiResponseParser.diseaseRecommend(generated());
    assertThat(result.status()).isEqualTo(RecommendationStatus.GENERATED);
    assertThat(result.guidance()).hasSize(2);
    var rec = result.recommendation();
    assertThat(rec.skinType()).isEqualTo(SkinType.DRY);
    assertThat(rec.concerns()).containsExactly(Concern.SENSITIVITY);
    assertThat(rec.ingredients()).hasSize(4);
    assertThat(rec.ingredients().get(1).purpose()).isNull();
    assertThat(rec.routine()).hasSize(5);
    assertThat(rec.reasoning()).hasSize(3);
    assertThat(rec.filtered()).isTrue();
    assertThat(rec.usedFallback()).isFalse();
    assertThat(rec.modelVersion()).isEqualTo("rec_sllm_v2");
    assertThat(rec.warnings()).isEmpty();
  }

  @ParameterizedTest(name = "fixture 계약: {0} → 추천 null + guidance")
  @ValueSource(strings = {"uncertain", "no_concern"})
  void withheldFixtures(String name) {
    var result = AiResponseParser.diseaseRecommend(fixture(name + ".response.json"));
    assertThat(result.status()).isEqualTo(RecommendationStatus.fromCode(name));
    assertThat(result.recommendation()).isNull();
    assertThat(result.guidance()).isNotEmpty();
  }

  @Test
  @DisplayName("fixture 계약: 기록의 disease 객체가 분류 응답 검증을 통과 — 확률은 받은 값 그대로, 6종 순서")
  void diseaseFixtures() {
    var atopic = AiResponseParser.disease(disease("generated.response.json"));
    assertThat(atopic.label()).isEqualTo(DiseaseLabel.ATOPIC);
    assertThat(atopic.confidence()).isEqualTo(0.9);
    assertThat(atopic.uncertain()).isFalse();
    assertThat(atopic.probabilities().keySet())
        .containsExactly("건선", "아토피", "여드름", "정상", "주사", "지루");
    assertThat(atopic.probabilities()).containsEntry("건선", 0.019999999999999997);
    assertThat(atopic.modelVersion()).isEqualTo("quality_test_fixture_not_a_patient_prediction");

    assertThat(AiResponseParser.disease(disease("uncertain.response.json")).uncertain()).isTrue();
    assertThat(AiResponseParser.disease(disease("no_concern.response.json")).label())
        .isEqualTo(DiseaseLabel.NORMAL);
  }

  @Nested
  class Disease {
    @Test
    void unknownLabel() {
      var body = disease("generated.response.json").put("label", "습진");
      assertThat(issues(() -> AiResponseParser.disease(body)))
          .contains(new Issue("label", "unknown_label"));
    }

    @Test
    @DisplayName("label과 label_en의 짝이 다르면 위반")
    void labelMismatch() {
      var body = disease("generated.response.json").put("label_en", "acne");
      assertThat(issues(() -> AiResponseParser.disease(body)))
          .containsExactly(new Issue("label_en", "label_mismatch"));
    }

    @Test
    @DisplayName("6종 확률이 모두 있어야 하고 그 밖의 키는 안 된다 (경로에 라벨 대신 코드)")
    void probabilityKeys() {
      var missing = disease("generated.response.json");
      ((ObjectNode) missing.get("probabilities")).remove("지루");
      assertThat(issues(() -> AiResponseParser.disease(missing)))
          .containsExactly(new Issue("probabilities.seborrheic", "not_number"));

      var extra = disease("generated.response.json");
      ((ObjectNode) extra.get("probabilities")).put("기타", 0.0);
      assertThat(issues(() -> AiResponseParser.disease(extra)))
          .containsExactly(new Issue("probabilities", "unknown_label"));
    }

    @Test
    void outOfRange() {
      var body = disease("generated.response.json").put("confidence", 1.2);
      ((ObjectNode) body.get("probabilities")).put("주사", -0.1);
      assertThat(issues(() -> AiResponseParser.disease(body)))
          .contains(
              new Issue("confidence", "out_of_range"),
              new Issue("probabilities.rosacea", "out_of_range"));
    }

    @Test
    @DisplayName("model_version 80자 초과 위반 (DB 길이)")
    void modelVersionTooLong() {
      var body = disease("generated.response.json").put("model_version", "x".repeat(81));
      assertThat(issues(() -> AiResponseParser.disease(body)))
          .containsExactly(new Issue("model_version", "too_long"));
    }

    @Test
    void notObject() {
      JsonNode html = JSON.getNodeFactory().stringNode("<html>");
      assertThat(issues(() -> AiResponseParser.disease(html)))
          .containsExactly(new Issue("$", "not_object"));
    }
  }

  @Nested
  class DiseaseRecommend {
    @Test
    void unknownStatus() {
      var body = generated().put("recommendation_status", "maybe");
      assertThat(issues(() -> AiResponseParser.diseaseRecommend(body)))
          .contains(new Issue("recommendation_status", "unknown_status"));
    }

    @Test
    @DisplayName("generated인데 recommendation이 null이면 위반")
    void generatedWithoutRecommendation() {
      var body = generated();
      body.putNull("recommendation");
      assertThat(issues(() -> AiResponseParser.diseaseRecommend(body)))
          .containsExactly(new Issue("recommendation", "not_object"));
    }

    @Test
    @DisplayName("보류인데 recommendation 객체가 오면 위반 — 빈 추천으로 바꾸지 않는다")
    void withheldWithRecommendation() {
      var body = fixture("uncertain.response.json");
      body.set("recommendation", generated().get("recommendation"));
      assertThat(issues(() -> AiResponseParser.diseaseRecommend(body)))
          .containsExactly(new Issue("recommendation", "not_null"));
    }

    @Test
    void guidanceNotArray() {
      var body = generated().put("guidance", "안내");
      assertThat(issues(() -> AiResponseParser.diseaseRecommend(body)))
          .containsExactly(new Issue("guidance", "not_array"));
    }
  }

  @Nested
  class Recommendation {
    private ObjectNode body;

    private ObjectNode rec() {
      body = generated();
      return (ObjectNode) body.get("recommendation");
    }

    private List<Issue> recIssues() {
      return issues(() -> AiResponseParser.diseaseRecommend(body));
    }

    @Test
    void englishSkinTypeAccepted() {
      rec().put("skin_type", "oily");
      assertThat(AiResponseParser.diseaseRecommend(body).recommendation().skinType())
          .isEqualTo(SkinType.OILY);
    }

    @Test
    void unknownSkinType() {
      rec().put("skin_type", "수부지");
      assertThat(recIssues()).contains(new Issue("recommendation.skin_type", "unknown_skin_type"));
    }

    @Test
    @DisplayName("concerns 라벨이 한 글자라도 다르면 위반 (괄호·슬래시 그대로)")
    void concernLabelExact() {
      rec().putArray("concerns").add("민감성(트러블)");
      assertThat(recIssues()).contains(new Issue("recommendation.concerns[0]", "unknown_concern"));
    }

    @Test
    void unknownRoutineTime() {
      ((ObjectNode) rec().get("routine").get(0)).put("time", "night");
      assertThat(recIssues())
          .contains(new Issue("recommendation.routine[0].time", "unknown_routine_time"));
    }

    @Test
    void missingRequired() {
      rec().remove("disclaimer");
      assertThat(recIssues()).contains(new Issue("recommendation.disclaimer", "not_string"));
    }

    @Test
    @DisplayName("개수 위반(ingredients > 5, concerns > 2, reasoning ≠ 3)은 WARN만")
    void countWarnings() {
      var rec = rec();
      rec.putArray("concerns").add("모공").add("주름").add("피부처짐/탄력저하");
      var ingredients = rec.putArray("ingredients");
      for (int i = 0; i < 6; i++)
        ingredients.addObject().put("name", "판테놀").putNull("purpose").put("priority", 1);
      rec.putArray("reasoning").add("한 문단");
      assertThat(AiResponseParser.diseaseRecommend(body).recommendation().warnings())
          .containsExactly("concerns_count:3", "ingredients_count:6", "reasoning_count:1");
    }

    @Test
    @DisplayName("빈 ingredients/routine·input_notes 없음은 정상, 중복 고민은 하나로")
    void lenientParts() {
      var rec = rec();
      rec.putArray("ingredients");
      rec.putArray("routine");
      rec.remove("input_notes");
      rec.putArray("concerns").add("모공").add("모공");
      var result = AiResponseParser.diseaseRecommend(body).recommendation();
      assertThat(result.ingredients()).isEmpty();
      assertThat(result.inputNotes()).isEmpty();
      assertThat(result.concerns()).containsExactly(Concern.PORE);
    }
  }

  @Test
  @DisplayName("health: disease_loaded가 true일 때만 분류 가능, 필드가 없으면(이전 AI 서버) false")
  void health() {
    assertThat(
            AiResponseParser.health(JSON.readTree("{\"status\":\"ok\",\"disease_loaded\":true}"))
                .diseaseLoaded())
        .isTrue();
    assertThat(
            AiResponseParser.health(JSON.readTree("{\"status\":\"ok\",\"disease_loaded\":false}"))
                .diseaseLoaded())
        .isFalse();
    assertThat(AiResponseParser.health(JSON.readTree("{\"status\":\"ok\"}")).diseaseLoaded())
        .isFalse();
  }
}
