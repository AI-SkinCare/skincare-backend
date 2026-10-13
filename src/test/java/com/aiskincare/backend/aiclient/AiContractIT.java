package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.MeasureSkinInput;
import com.aiskincare.backend.analyses.NormalizedSurvey;
import com.aiskincare.backend.common.enums.AgeBand;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DeviceType;
import com.aiskincare.backend.common.enums.Gender;
import com.aiskincare.backend.common.enums.Level;
import com.aiskincare.backend.common.enums.Metric;
import com.aiskincare.backend.common.enums.Roi;
import com.aiskincare.backend.common.enums.SkinType;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.support.TestImages;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 AI 서버(skincare-ai)와의 계약 테스트 — AI_CONTRACT_URL이 있을 때만 돈다 (CI에선 건너뜀).
 *
 * <pre>
 * AI_CONTRACT_URL=http://127.0.0.1:8000 AI_CONTRACT_TOKEN=&lt;AI_INTERNAL_TOKEN&gt; ./gradlew aiContractTest
 * </pre>
 *
 * REC_BACKEND=mock이면 수 초, sllm이면 추천 1건에 약 50초 × 11건이 걸린다. 백엔드가 만든 요청(코드 → 라벨 변환)을 AI가 인식하는지, AI 응답이
 * 우리 검증을 통과하는지 본다.
 */
@Tag("ai-contract")
@EnabledIfEnvironmentVariable(named = "AI_CONTRACT_URL", matches = ".+")
class AiContractIT {
  private static final String URL = System.getenv("AI_CONTRACT_URL");
  private static final String TOKEN = System.getenv().getOrDefault("AI_CONTRACT_TOKEN", "");
  private static final AiCallContext CTX = new AiCallContext("ai-contract-test", null);

  private static HttpAiClient client(String token) {
    return new HttpAiClient(
        new HttpAiClient.Options(URL, token, 20_000, 130_000), JsonMapper.builder().build());
  }

  private static NormalizedSurvey survey(SkinType skinType, List<Concern> concerns) {
    return new NormalizedSurvey(
        Gender.FEMALE,
        AgeBand.TWENTIES,
        skinType,
        null,
        null,
        null,
        null,
        concerns,
        List.of(),
        null);
  }

  @Test
  @DisplayName("GET /health — 인증 없이 status ok")
  void health() {
    assertThat(client("").health().status()).isEqualTo("ok");
  }

  @Test
  @DisplayName("POST /infer/skin-metrics — multipart image·device_type, 응답이 검증을 통과")
  void skinMetrics() {
    var result =
        client(TOKEN)
            .measureSkin(
                new MeasureSkinInput(
                    TestImages.png(1080, 1440), "image/png", DeviceType.SMARTPHONE),
                CTX);
    assertThat(result.metrics()).isNotEmpty();
  }

  static Stream<Arguments> labels() {
    Concern[] concerns = Concern.values();
    SkinType[] types = SkinType.values();
    return IntStream.range(0, concerns.length)
        .mapToObj(i -> Arguments.of(concerns[i], types[i % types.length]));
  }

  @ParameterizedTest(name = "고민 {0} · 피부 타입 {1} 라벨을 AI가 인식한다")
  @MethodSource("labels")
  void labelsRecognized(Concern concern, SkinType skinType) {
    var result =
        client(TOKEN)
            .recommend(
                AiTransform.buildRecommendRequest(null, survey(skinType, List.of(concern))), CTX);
    assertThat(result.skinType()).isEqualTo(skinType);
    assertThat(result.concerns().get(0)).isEqualTo(concern);
    assertThat(result.disclaimer()).isNotBlank();
  }

  @Test
  @DisplayName("측정값(모든 ROI) + 설문 전체 필드도 받는다")
  void fullRequest() {
    Map<String, Map<String, Double>> metrics = new LinkedHashMap<>();
    double[] values = {0.61, 0.74, 0.38, 0.45};
    for (Roi roi : Roi.values()) {
      Map<String, Double> m = new LinkedHashMap<>();
      for (Metric metric : Metric.values()) m.put(metric.code(), values[metric.ordinal()]);
      metrics.put(roi.code(), m);
    }
    var full =
        new NormalizedSurvey(
            Gender.FEMALE,
            AgeBand.TWENTIES,
            SkinType.COMBINATION,
            5,
            Level.LOW,
            Level.HIGH,
            Level.HIGH,
            List.of(Concern.PORE, Concern.PIGMENTATION),
            List.of("토너", "수분크림"),
            "계약 테스트");
    var result = client(TOKEN).recommend(AiTransform.buildRecommendRequest(metrics, full), CTX);
    assertThat(result.concerns()).contains(Concern.PORE);
  }

  @Test
  @DisplayName("필수만 있는 설문(성별·연령대)도 받는다")
  void minimalSurvey() {
    var result =
        client(TOKEN)
            .recommend(AiTransform.buildRecommendRequest(null, survey(null, List.of())), CTX);
    assertThat(result.skinType()).isNotNull();
  }

  @Test
  @DisplayName("내부 토큰이 틀리면 AI 401 E1001 → 측정 503 E3001 / 워커 failed E3001")
  void wrongToken() {
    var wrong = client("wrong-token");
    AiError measure =
        catchThrowableOfType(
            AiError.class,
            () ->
                wrong.measureSkin(
                    new MeasureSkinInput(
                        TestImages.png(800, 800), "image/png", DeviceType.SMARTPHONE),
                    CTX));
    assertThat(measure.kind()).isEqualTo(AiError.Kind.HTTP);
    assertThat(measure.status()).isEqualTo(401);
    assertThat(measure.code()).isEqualTo("E1001");
    assertThat(AiErrorMapper.measure(measure).code()).isEqualTo(ErrorCode.E3001);
    AiError rec =
        catchThrowableOfType(
            AiError.class,
            () ->
                wrong.recommend(
                    AiTransform.buildRecommendRequest(null, survey(null, List.of())), CTX));
    assertThat(AiErrorMapper.recommend(rec).code()).isEqualTo(ErrorCode.E3001);
  }
}
