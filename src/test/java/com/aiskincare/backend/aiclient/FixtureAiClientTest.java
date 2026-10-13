package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.MeasureSkinInput;
import com.aiskincare.backend.aiclient.AiModels.RecommendRequest;
import com.aiskincare.backend.common.enums.DeviceType;
import com.aiskincare.backend.common.enums.FixtureScenario;
import com.aiskincare.backend.common.enums.SkinType;
import com.aiskincare.backend.support.FakeSleeper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** TRD §12.7 */
class FixtureAiClientTest {
  private static final MeasureSkinInput INPUT =
      new MeasureSkinInput(new byte[4], "image/png", DeviceType.SMARTPHONE);
  private static final RecommendRequest REQUEST =
      new RecommendRequest(null, Map.of("gender", "female", "ageBand", "20s"));
  private final FakeSleeper sleeper = new FakeSleeper();

  private FixtureAiClient ai(FixtureScenario scenario) {
    return new FixtureAiClient(scenario, sleeper, JsonMapper.builder().build());
  }

  private static AiCallContext ctx(FixtureScenario scenario) {
    return new AiCallContext("r", scenario);
  }

  @Test
  @DisplayName("ok: 측정 is_stub, 추천 1초 지연, 받은 요청 기록")
  void ok() {
    var ai = ai(FixtureScenario.OK);
    assertThat(ai.measureSkin(INPUT, ctx(null)).isStub()).isTrue();
    assertThat(ai.recommend(REQUEST, ctx(null)).skinType()).isEqualTo(SkinType.COMBINATION);
    assertThat(sleeper.calls()).containsExactly(FixtureAiClient.REC_DELAY);
    assertThat(ai.recommendRequests()).containsExactly(REQUEST);
  }

  @Test
  @DisplayName("no-face: 측정 422 E2001")
  void noFace() {
    AiError error =
        catchThrowableOfType(
            AiError.class,
            () -> ai(FixtureScenario.OK).measureSkin(INPUT, ctx(FixtureScenario.NO_FACE)));
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
  @DisplayName("rec-slow: 60초 지연 후 정상")
  void recSlow() {
    ai(FixtureScenario.OK).recommend(REQUEST, ctx(FixtureScenario.REC_SLOW));
    assertThat(sleeper.calls()).containsExactly(FixtureAiClient.REC_SLOW_DELAY);
  }

  @Test
  @DisplayName("ai-down: 측정·추천·health 모두 connection")
  void aiDown() {
    var ai = ai(FixtureScenario.AI_DOWN);
    assertThatThrownBy(() -> ai.measureSkin(INPUT, ctx(null))).isInstanceOf(AiError.class);
    assertThat(catchThrowableOfType(AiError.class, () -> ai.recommend(REQUEST, ctx(null))).kind())
        .isEqualTo(AiError.Kind.CONNECTION);
    assertThat(catchThrowableOfType(AiError.class, ai::health).kind())
        .isEqualTo(AiError.Kind.CONNECTION);
  }

  @Test
  @DisplayName("health는 env 시나리오만 따른다")
  void healthFollowsEnv() {
    assertThat(ai(FixtureScenario.NO_FACE).health().status()).isEqualTo("ok");
  }
}
