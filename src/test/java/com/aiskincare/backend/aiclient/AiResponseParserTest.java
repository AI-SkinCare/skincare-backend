package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.aiskincare.backend.aiclient.AiError.Issue;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.SkinType;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 계약 테스트 — skincare-ai 실제 출력(fixture)이 검증·변환을 통과해야 한다 (TRD §16.1) */
class AiResponseParserTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ObjectNode fixture(String name) {
    try (var in = new ClassPathResource("fixtures/ai/" + name).getInputStream()) {
      return (ObjectNode) JSON.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Issue> issues(Runnable parse) {
    AiError error = catchThrowableOfType(AiError.class, parse::run);
    assertThat(error).as("검증이 통과해 버렸다").isNotNull();
    assertThat(error.kind()).isEqualTo(AiError.Kind.INVALID_RESPONSE);
    return error.issues();
  }

  @Test
  @DisplayName("fixture 계약: skin-metrics.ok.json 통과")
  void skinFixture() {
    var result = AiResponseParser.skinMetrics(fixture("skin-metrics.ok.json"));
    assertThat(result.isStub()).isTrue();
    assertThat(result.metrics()).isNotEmpty();
  }

  @Test
  @DisplayName("fixture 계약: recommend.ok.json 통과 → 라벨이 코드로")
  void recommendFixture() {
    var result = AiResponseParser.recommend(fixture("recommend.ok.json"));
    assertThat(result.skinType()).isEqualTo(SkinType.COMBINATION);
    assertThat(result.concerns()).containsExactly(Concern.PORE, Concern.PIGMENTATION);
    assertThat(result.reasoning()).hasSize(3);
    assertThat(result.warnings()).isEmpty();
  }

  @Nested
  class SkinMetrics {
    @Test
    void unknownRoi() {
      var body = fixture("skin-metrics.ok.json");
      ((ObjectNode) body.get("metrics"))
          .putObject("nose")
          .put("moisture", 0.5)
          .put("elasticity", 0.5)
          .put("pigmentation", 0.5)
          .put("pore", 0.5);
      assertThat(issues(() -> AiResponseParser.skinMetrics(body)))
          .contains(new Issue("metrics.nose", "unknown_roi"));
    }

    @Test
    void outOfRange() {
      var body = fixture("skin-metrics.ok.json");
      ((ObjectNode) body.get("metrics").get("full_face")).put("pore", 1.2);
      assertThat(issues(() -> AiResponseParser.skinMetrics(body)))
          .contains(new Issue("metrics.full_face.pore", "out_of_range"));
    }

    @Test
    void emptyMetrics() {
      var body = fixture("skin-metrics.ok.json");
      body.putObject("metrics");
      assertThat(issues(() -> AiResponseParser.skinMetrics(body)))
          .contains(new Issue("metrics", "empty"));
    }

    @Test
    void notObject() {
      JsonNode html = JSON.getNodeFactory().stringNode("<html>");
      assertThat(issues(() -> AiResponseParser.skinMetrics(html)))
          .containsExactly(new Issue("$", "not_object"));
    }
  }

  @Nested
  class Recommend {
    @Test
    void englishSkinTypeAccepted() {
      var body = fixture("recommend.ok.json").put("skin_type", "oily");
      assertThat(AiResponseParser.recommend(body).skinType()).isEqualTo(SkinType.OILY);
    }

    @Test
    void unknownSkinType() {
      var body = fixture("recommend.ok.json").put("skin_type", "수부지");
      assertThat(issues(() -> AiResponseParser.recommend(body)))
          .contains(new Issue("skin_type", "unknown_skin_type"));
    }

    @Test
    @DisplayName("concerns 라벨이 한 글자라도 다르면 위반 (괄호·슬래시 그대로)")
    void concernLabelExact() {
      var body = fixture("recommend.ok.json");
      body.putArray("concerns").add("미백(색소침착/기미)");
      assertThat(issues(() -> AiResponseParser.recommend(body)))
          .contains(new Issue("concerns[0]", "unknown_concern"));
    }

    @Test
    void unknownRoutineTime() {
      var body = fixture("recommend.ok.json");
      ((ObjectNode) body.get("routine").get(0)).put("time", "night");
      assertThat(issues(() -> AiResponseParser.recommend(body)))
          .contains(new Issue("routine[0].time", "unknown_routine_time"));
    }

    @Test
    void missingRequired() {
      var body = fixture("recommend.ok.json");
      body.remove("disclaimer");
      assertThat(issues(() -> AiResponseParser.recommend(body)))
          .contains(new Issue("disclaimer", "not_string"));
    }

    @Test
    @DisplayName("개수 위반(ingredients > 5, concerns > 2, reasoning ≠ 3)은 WARN만")
    void countWarnings() {
      var body = fixture("recommend.ok.json");
      body.putArray("concerns").add("모공").add("주름").add("피부처짐/탄력저하");
      var ingredients = body.putArray("ingredients");
      for (int i = 0; i < 6; i++)
        ingredients.addObject().put("name", "판테놀").putNull("purpose").put("priority", 1);
      body.putArray("reasoning").add("한 문단");
      assertThat(AiResponseParser.recommend(body).warnings())
          .containsExactly("concerns_count:3", "ingredients_count:6", "reasoning_count:1");
    }

    @Test
    @DisplayName("purpose null·빈 ingredients/routine·input_notes 없음은 정상, 중복 고민은 하나로")
    void lenientParts() {
      var body = fixture("recommend.ok.json");
      body.putArray("ingredients");
      body.putArray("routine");
      body.remove("input_notes");
      body.putArray("concerns").add("모공").add("모공");
      var result = AiResponseParser.recommend(body);
      assertThat(result.ingredients()).isEmpty();
      assertThat(result.inputNotes()).isEmpty();
      assertThat(result.concerns()).containsExactly(Concern.PORE);
    }
  }
}
