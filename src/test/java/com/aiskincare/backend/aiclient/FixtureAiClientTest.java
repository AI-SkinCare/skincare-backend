package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.ClassifyInput;
import com.aiskincare.backend.aiclient.AiModels.DiseaseRecommendRequest;
import com.aiskincare.backend.common.enums.DiseaseLabel;
import com.aiskincare.backend.common.enums.FixtureScenario;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.support.FakeSleeper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** TRD §12.7 */
class FixtureAiClientTest {
  private static final ClassifyInput INPUT = new ClassifyInput(new byte[4], "image/png");
  private static final DiseaseRecommendRequest REQUEST =
      new DiseaseRecommendRequest(
          Map.of("label_en", "atopic"), Map.of("gender", "female", "ageBand", "20s"));
  private final FakeSleeper sleeper = new FakeSleeper();

  private FixtureAiClient ai(FixtureScenario scenario) {
    return new FixtureAiClient(scenario, sleeper, JsonMapper.builder().build());
  }

  private static AiCallContext ctx(FixtureScenario scenario) {
    return new AiCallContext("r", scenario);
  }

  @Test
  @DisplayName("ok: 아토피 0.9 분류, 추천 generated 1초 지연, 받은 요청 기록")
  void ok() {
    var ai = ai(FixtureScenario.OK);
    var disease = ai.classify(INPUT, ctx(null));
    assertThat(disease.label()).isEqualTo(DiseaseLabel.ATOPIC);
    assertThat(disease.uncertain()).isFalse();
    var result = ai.recommend(REQUEST, ctx(null));
    assertThat(result.status()).isEqualTo(RecommendationStatus.GENERATED);
    assertThat(result.recommendation()).isNotNull();
    assertThat(sleeper.calls()).containsExactly(FixtureAiClient.REC_DELAY);
    assertThat(ai.recommendRequests()).containsExactly(REQUEST);
  }

  @Test
  @DisplayName("uncertain: 분류 uncertain=true, 추천 보류(지연 없음)")
  void uncertain() {
    var ai = ai(FixtureScenario.OK);
    assertThat(ai.classify(INPUT, ctx(FixtureScenario.UNCERTAIN)).uncertain()).isTrue();
    var result = ai.recommend(REQUEST, ctx(FixtureScenario.UNCERTAIN));
    assertThat(result.status()).isEqualTo(RecommendationStatus.UNCERTAIN);
    assertThat(result.recommendation()).isNull();
    assertThat(sleeper.calls()).isEmpty();
  }

  @Test
  @DisplayName("no-concern: 정상 분류, 추천 no_concern 보류")
  void noConcern() {
    var ai = ai(FixtureScenario.NO_CONCERN);
    assertThat(ai.classify(INPUT, ctx(null)).label()).isEqualTo(DiseaseLabel.NORMAL);
    var result = ai.recommend(REQUEST, ctx(null));
    assertThat(result.status()).isEqualTo(RecommendationStatus.NO_CONCERN);
    assertThat(result.recommendation()).isNull();
  }

  @Test
  @DisplayName("no-face: 분류 422 E2001")
  void noFace() {
    AiError error =
        catchThrowableOfType(
            AiError.class,
            () -> ai(FixtureScenario.OK).classify(INPUT, ctx(FixtureScenario.NO_FACE)));
    assertThat(error.kind()).isEqualTo(AiError.Kind.HTTP);
    assertThat(error.status()).isEqualTo(422);
    assertThat(error.code()).isEqualTo("E2001");
  }

  @Test
  @DisplayName("rec-timeout: 추천 504 E3002 즉시")
  void recTimeout() {
    AiError error =
        catchThrowableOfType(
            AiError.class, () -> ai(FixtureScenario.REC_TIMEOUT).recommend(REQUEST, ctx(null)));
    assertThat(error.status()).isEqualTo(504);
    assertThat(error.code()).isEqualTo("E3002");
    assertThat(sleeper.calls()).isEmpty();
  }

  @Test
  @DisplayName("rec-slow: 60초 지연 후 generated")
  void recSlow() {
    var result = ai(FixtureScenario.OK).recommend(REQUEST, ctx(FixtureScenario.REC_SLOW));
    assertThat(result.status()).isEqualTo(RecommendationStatus.GENERATED);
    assertThat(sleeper.calls()).containsExactly(FixtureAiClient.REC_SLOW_DELAY);
  }

  @Test
  @DisplayName("ai-down: 분류·추천·health 모두 connection")
  void aiDown() {
    var ai = ai(FixtureScenario.AI_DOWN);
    assertThatThrownBy(() -> ai.classify(INPUT, ctx(null))).isInstanceOf(AiError.class);
    assertThat(catchThrowableOfType(AiError.class, () -> ai.recommend(REQUEST, ctx(null))).kind())
        .isEqualTo(AiError.Kind.CONNECTION);
    assertThat(catchThrowableOfType(AiError.class, ai::health).kind())
        .isEqualTo(AiError.Kind.CONNECTION);
  }

  @Test
  @DisplayName("health는 env 시나리오만 따르고 분류기가 로드된 것으로 답한다")
  void healthFollowsEnv() {
    var health = ai(FixtureScenario.NO_FACE).health();
    assertThat(health.status()).isEqualTo("ok");
    assertThat(health.diseaseLoaded()).isTrue();
  }
}
